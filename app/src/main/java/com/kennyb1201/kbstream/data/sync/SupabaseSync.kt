package com.kennyb1201.kbstream.data.sync

import android.content.Context
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import com.kennyb1201.kbstream.BuildConfig
import com.kennyb1201.kbstream.data.addon.AddonManager
import com.kennyb1201.kbstream.data.reporting.CrashReporter
import com.kennyb1201.kbstream.data.reporting.Redaction
import com.kennyb1201.kbstream.data.cache.SyncOutboxDao
import com.kennyb1201.kbstream.data.cache.SyncOutboxEntity
import com.kennyb1201.kbstream.data.cache.WatchedStatusEntity
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.history.WatchHistoryEntity
import com.kennyb1201.kbstream.data.history.looksLikeMarkedWatchedRow
import com.kennyb1201.kbstream.data.images.ImageOverrides
import com.kennyb1201.kbstream.data.images.ImageOverrideEntity
import com.kennyb1201.kbstream.data.watched.WatchedStatusRepository
import com.kennyb1201.kbstream.data.runCatchingCancellable

/**
 * Cross-device sync over Supabase.
 *
 * What syncs (per user request):
 *  - watch history / resume positions / completion   → sync_watch_history
 *  - watched markers (overrides + cache)             → sync_watched_status
 *  - poster / backdrop overrides (long-press, Media) → sync_image_overrides
 *  - display prefs, addons, Simkl token, Trakt session, IPTV config → sync_prefs
 *    (keyed JSON blobs)
 *    (playback/decoder settings deliberately EXCLUDED — those are per-device)
 *
 * How:
 *  - Local writes enqueue into an in-memory outbox and flush immediately
 *    (also retried on reconnect/interval) — offline-safe.
 *  - A realtime subscription on all four tables applies remote changes
 *    within ~a second of the other device writing them.
 *  - Merge is last-write-wins per item using payload updatedAt (epoch ms),
 *    so newer progress always wins regardless of which device wrote it.
 */
object SupabaseSync {

    private const val TAG = "SUPABASE_SYNC"

    // Session-restore retry: a TV box is often still offline for the first
    // seconds after boot / an app-update restart. Transient refresh failures
    // are retried with exponential backoff so the saved session signs back
    // in automatically instead of leaving the user manually re-authing.
    @Volatile
    private var restoreAttempts = 0

    private const val MAX_RESTORE_ATTEMPTS = 5
    private const val RESTORE_RETRY_BASE_MS = 15_000L
    private const val RESTORE_RETRY_MAX_MS = 120_000L

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // ── Public state ────────────────────────────────────────────────

    sealed interface AuthState {
        data object SignedOut : AuthState
        data object SigningIn : AuthState
        data class SignedIn(val email: String) : AuthState
        data class Error(val message: String) : AuthState
    }

    private val _authState = MutableStateFlow<AuthState>(AuthState.SignedOut)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    private val _syncEnabled = MutableStateFlow(false)
    val syncEnabled: StateFlow<Boolean> = _syncEnabled.asStateFlow()

    private val _lastSyncAtMs = MutableStateFlow(0L)
    val lastSyncAtMs: StateFlow<Long> = _lastSyncAtMs.asStateFlow()

    // ── Sync health (Settings → Sync) ───────────────────────────────
    // Pull and push are tracked separately: "nothing arrives anymore" and
    // "nothing uploads anymore" are different failures, and the old single
    // timestamp could not tell them apart.
    private val _lastPullAtMs = MutableStateFlow(0L)
    val lastPullAtMs: StateFlow<Long> = _lastPullAtMs.asStateFlow()

    private val _lastPushAtMs = MutableStateFlow(0L)
    val lastPushAtMs: StateFlow<Long> = _lastPushAtMs.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _pendingOutbox = MutableStateFlow(0)
    val pendingOutboxCount: StateFlow<Int> = _pendingOutbox.asStateFlow()

    private val _realtimeStatus = MutableStateFlow("stopped")
    val realtimeStatus: StateFlow<String> = _realtimeStatus.asStateFlow()

    private val _realtimeChannelCount = MutableStateFlow(0)
    val realtimeChannelCount: StateFlow<Int> = _realtimeChannelCount.asStateFlow()

    /**
     * Last sync failure message, surfaced in Settings → Sync. Without this,
     * pull/push failures were swallowed (log-only) and "Last sync" kept
     * updating as if everything worked — a broken sync was invisible to the
     * user (missing profiles looked like a mystery, not an error).
     */
    private val _syncError = MutableStateFlow<String?>(null)
    val syncError: StateFlow<String?> = _syncError.asStateFlow()

    private fun recordSyncError(where: String, e: Exception) {
        // supabase-kt request exceptions embed the full URL + headers
        // (including the bearer token) in the message — showing that raw
        // dump on a TV is useless and leaks the token onto the screen. The
        // first line carries the useful part ("new row violates row-level
        // security policy for table …").
        // Redacted as well: the first line is what reaches the TV screen and
        // the retained-error list, and supabase-kt's messages can carry the
        // request URL and the bearer header on it (see [Redaction]).
        val firstLine = Redaction.text(e.message ?: e.javaClass.simpleName)
            .lineSequence().firstOrNull()?.trim().orEmpty()
            .ifBlank { e.javaClass.simpleName }
        _syncError.value = "$where failed: $firstLine"
    }

    private fun clearSyncError() {
        _syncError.value = null
    }

    // ── Client ──────────────────────────────────────────────────────

    // Built on `scope` (see init) and read from other threads — signIn,
    // signOut, the enqueue paths and the UI all touch it — so publish it
    // safely now that the write is no longer on the caller's thread.
    @Volatile
    private var client: SupabaseClient? = null

    // init() is called once from MainApplication, but the client is now built
    // asynchronously: a plain `client != null` guard would let a second call
    // start a second build before the first one assigns.
    private val initStarted = AtomicBoolean(false)

    // Last-resort net under every launch {} in this object: the per-path
    // try/catch blocks only catch Exception, but an Error/Throwable escaping
    // a launch on an unhandled scope CRASHES THE PROCESS (black screen →
    // launcher on TV). That is exactly what happened on signed-in devices:
    // the restoreSession/pull path is the only startup work signed-out
    // devices never run. This handler converts any such escape into a log +
    // Sentry capture instead of a dead app. Behavior is unchanged when
    // everything works.
    private val uncaughtHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "sync task failed hard", Redaction.throwable(throwable))
        com.kennyb1201.kbstream.data.reporting.CrashReporter.recordNonFatal(
            throwable, mapOf("source" to "supabase_sync_scope")
        )
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + uncaughtHandler)

    private const val TABLE_HISTORY = "sync_watch_history"
    private const val TABLE_WATCHED = "sync_watched_status"
    private const val TABLE_PREFS = "sync_prefs"

    /**
     * Poster / backdrop overrides, one row per (title, kind, surface) - the
     * cloud twin of the local `image_overrides` table. Its key column is
     * `override_key` rather than `item_key`: the realtime applier identifies a
     * table from the fields its record carries, and a second table reusing
     * `item_key` would route its changes into the watched-marker merge.
     */
    private const val TABLE_OVERRIDES = "sync_image_overrides"
    private const val KEY_OVERRIDES = "override_key"

    /**
     * The tables realtime subscribes, in join order. One channel each: a table
     * is joined only when nothing is already tracking it (see [startRealtime]),
     * so this list is a checklist rather than a batch of unconditional joins.
     */
    private val REALTIME_TABLES =
        listOf(TABLE_HISTORY, TABLE_WATCHED, TABLE_OVERRIDES, TABLE_PREFS)

    /** Shown when auth is pressed in a build compiled without Supabase keys. */
    private const val SYNC_NOT_CONFIGURED_MESSAGE =
        "Sync isn't configured in this build. Add SUPABASE_URL and " +
            "SUPABASE_ANON_KEY to local.properties and rebuild to enable " +
            "sign-in, accounts, and cross-device sync."

    fun init(context: Context) {
        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_ANON_KEY.isBlank()) {
            Log.i(TAG, "Supabase not configured — sync disabled")
            return
        }
        if (!initStarted.compareAndSet(false, true)) return

        // Foreground liveness (see [onAppForeground]). Registered here, once,
        // because every Activity — MainActivity and each player — is the app
        // coming back, and only the Application sees all of them.
        registerForegroundCallbacks(context)

        // createSupabaseClient builds a Ktor engine, an OkHttp stack and the
        // Auth/Postgrest/Realtime plugin chain — the single heaviest piece of
        // app startup, and it used to run inline here, on the main thread,
        // ahead of the first frame. Nothing on the first-frame path reads this
        // object: authState/syncEnabled start at SignedOut/false and the only
        // consumers are the onboarding and settings screens. restoreSession()
        // already dispatched to `scope`, so dispatching the client build puts
        // ALL of sync's startup on IO. The scope carries a
        // CoroutineExceptionHandler, so a failure here is logged and reported
        // instead of crashing the process.
        scope.launch {
            hydrateOutbox()
            startClient(context)
        }
    }

    /**
     * Builds the client, then starts session restore and the session-status
     * collector. Runs on [scope] — never the main thread.
     */
    private fun startClient(context: Context) {
        if (client != null) return

        client = createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY
        ) {
            // Pin the Ktor engine instead of letting the SDK resolve one off
            // the classpath. Realtime opens a websocket per channel, so the
            // engine MUST advertise WebSocketCapability — the old
            // ktor-client-android (HttpURLConnection) engine does not, and
            // every join died with "Engine doesn't support WebSocketCapability"
            // while the SDK retried forever. OkHttp is the websocket-capable
            // engine here and the project already ships okhttp 4.12.0.
            httpEngine = io.ktor.client.engine.okhttp.OkHttp.create { }
            install(Auth)
            install(Postgrest)
            install(Realtime)
        }

        // Installing Realtime is not starting it, and this client was never
        // connected. The SDK keeps each channel's authorization in step with
        // the session by fanning setAuth() out to every live channel on every
        // auth event - session restore, sign-in, and each background token
        // refresh - and on a client with no websocket open that call reaches
        // getWebsocket(), which throws IllegalStateException("Websocket not yet
        // initialized") on the SDK's own dispatcher. Nothing here can catch
        // that: it is not raised inside any coroutine this app owns, so it
        // kills the process (the fatal crash on the 0.6.57 build). Opening the
        // socket once, here, is the fix - before session restore below can
        // raise the first auth event, and before startRealtime() registers a
        // channel that would need it. Dispatched rather than awaited so a slow
        // or refused handshake cannot hold up session restore, and guarded so
        // a realtime failure stays a log line instead of taking sync down.
        scope.launch {
            runCatching { client?.realtime?.connect() }
                .onFailure { Log.w(TAG, "realtime connect failed: ${Redaction.text(it.message)}") }
        }

        restoreSession(context)

        // Keep the persisted refresh token in lockstep with the client's
        // session. supabase-kt auto-refreshes in the background and ROTATES
        // the token on every refresh — memory-only unless re-saved here.
        // A saved token one generation behind turns the next forced process
        // restart (the in-app updater!) into Supabase refresh-token
        // reuse-detection, which revokes the whole session family: instant
        // sign-out after updating. Re-persist on every Authenticated event.
        scope.launch {
            client?.auth?.sessionStatus?.collect { status ->
                if (status is SessionStatus.Authenticated) {
                    val ctx = appContextRef?.get() ?: return@collect
                    runCatchingCancellable { persistSessionFromClient(ctx) }
                        .onFailure {
                            // Redacted: a supabase-kt exception carries the
                            // request URL, and the auth URL carries nothing
                            // secret - but the sign-in/sign-up bodies it also
                            // quotes do (see [Redaction.throwable]).
                            Log.w(TAG, "session persist failed", Redaction.throwable(it))
                        }
                    // The cloud half of the one-time cross-profile poison
                    // sweep needs a session; the ProfileManager.init call
                    // usually runs before auth restores, so retry here on
                    // every Authenticated event (flag-guarded → once).
                    runCatching { runOneTimePoisonSweep(ctx) }
                        .onFailure {
                            Log.w(TAG, "poison sweep trigger failed", Redaction.throwable(it))
                        }
                }
            }
        }
    }

    private fun restoreSession(context: Context) {
        val c = client ?: return
        scope.launch {
            try {
                // supabase-kt keeps sessions in memory; we persist the
                // refresh token ourselves so sign-in survives app restarts.
                val prefs = syncPrefs(context)
                val refresh = prefs.getString(KEY_REFRESH_TOKEN, null)
                val savedEmail = prefs.getString(KEY_EMAIL, null)

                if (refresh != null) {
                    // Cold start: the client has NO in-memory session, so
                    // refreshCurrentSession() would fail by definition — the
                    // saved token must be fed in explicitly via
                    // refreshSession(refreshToken), which also installs it as
                    // the current session for auto-refresh from here on.
                    val result = runCatching {
                        c.auth.refreshSession(refreshToken = refresh)
                    }
                    result.onSuccess {
                        // refreshSession() ROTATES the refresh token (the
                        // saved one is now spent). Re-persist the fresh token
                        // immediately: keeping the stale one guarantees the
                        // NEXT cold start trips Supabase's refresh-token
                        // reuse detection, which revokes the whole session
                        // family — the "signed out after updating" report.
                        persistSession(context, savedEmail.orEmpty())
                        _authState.value = AuthState.SignedIn(savedEmail.orEmpty())
                        _syncEnabled.value = true
                        rememberDeferredDeleteAccount(context, savedEmail.orEmpty())
                        startRealtime()
                        startPeriodicFlush()
                        ensureBackgroundPull(context)
                        // Replay this account's signed-out deletes BEFORE the
                        // pull, so the pull cannot restore a row they delete.
                        replayDeferredDeletes(context, savedEmail.orEmpty())
                        pullAll(context)

                        restoreAttempts = 0
                    }.onFailure { e ->
                        // Only a HARD rejection means the token is truly
                        // dead (revoked, password changed, or reuse
                        // detection already fired). Transient failures
                        // (timeout, offline right after an app update,
                        // 5xx) must KEEP the token so the next launch
                        // retries — wiping it here turned every network
                        // hiccup into a surprise sign-out.
                        val msg = (e.message ?: "").lowercase()
                        // Supabase's hard-dead responses say "Invalid
                        // Refresh Token" / "Already Used" (HTTP 4xx) —
                        // none of the transient markers below, so they
                        // still reach the wipe branch.
                        val transient =
                            msg.contains("timeout") ||
                                msg.contains("timed out") ||
                                msg.contains("unable to resolve") ||
                                msg.contains("failed to connect") ||
                                msg.contains("connection") ||
                                msg.contains("econn") ||
                                msg.contains("network") ||
                                msg.contains("server error") ||
                                msg.contains("http 5")
                        if (transient) {
                            // Offline at launch (TV boots before its network
                            // is up, or the update restart raced the Wi-Fi).
                            // The token is kept, so schedule automatic retries
                            // with backoff — no manual sign-in needed. Success
                            // or a hard rejection stops the loop.
                            if (restoreAttempts < MAX_RESTORE_ATTEMPTS) {
                                restoreAttempts += 1
                                val backoffMs = minOf(
                                    RESTORE_RETRY_BASE_MS shl (restoreAttempts - 1),
                                    RESTORE_RETRY_MAX_MS
                                )
                                Log.w(
                                    TAG,
                                    "session restore transient failure " +
                                        "(attempt $restoreAttempts/$MAX_RESTORE_ATTEMPTS, " +
                                        "retrying in ${backoffMs / 1000}s): ${Redaction.text(e.message)}"
                                )
                                _authState.value = AuthState.SignedOut
                                scope.launch {
                                    delay(backoffMs)
                                    restoreSession(context)
                                }
                            } else {
                                Log.w(
                                    TAG,
                                    "session restore gave up after $restoreAttempts " +
                                        "transient failures (token kept for next launch)"
                                )
                                restoreAttempts = 0
                                _authState.value = AuthState.SignedOut
                            }
                        } else {
                            Log.w(TAG, "session restore failed: ${Redaction.text(e.message)}")
                            syncPrefs(context)
                                .edit().remove(KEY_REFRESH_TOKEN).apply()
                            _authState.value = AuthState.SignedOut
                        }
                    }
                } else {
                    _authState.value = AuthState.SignedOut
                }
            } catch (e: Exception) {
                // A canceled restore is not a restore that failed: propagate
                // so the caller's cancellation is honored instead of reported
                // as a token problem.
                if (e is CancellationException) throw e
                // Any unexpected error path: keep the saved token — the
                // next launch can still restore from it.
                Log.e(TAG, "restoreSession failed (token kept)", Redaction.throwable(e))
                _authState.value = AuthState.SignedOut
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                // Errors (LinkageError etc., e.g. an R8/codec mismatch on a
                // release build) are NOT Exceptions — letting one escape this
                // launch kills the process at every cold start on signed-in
                // devices. Swallow to the crash reporter and mark signed out.
                Log.e(TAG, "restoreSession crashed (token kept)", Redaction.throwable(t))
                com.kennyb1201.kbstream.data.reporting.CrashReporter.recordNonFatal(
                    t, mapOf("source" to "restore_session")
                )
                _authState.value = AuthState.SignedOut
            }
        }
    }

    // ── Auth ────────────────────────────────────────────────────────

    fun signIn(context: Context, email: String, password: String) {
        val c = client
        if (c == null) {
            // Sync was never configured into this build (blank keys in
            // local.properties). Never a silent no-op — the user pressed a
            // button and deserves to know why nothing happened.
            _authState.value = AuthState.Error(SYNC_NOT_CONFIGURED_MESSAGE)
            return
        }
        _authState.value = AuthState.SigningIn
        scope.launch {
            try {
                c.auth.signInWith(io.github.jan.supabase.auth.providers.builtin.Email) {
                    this.email = email.trim()
                    this.password = password
                }
                persistSession(context, email.trim())
                _authState.value = AuthState.SignedIn(email.trim())
                _syncEnabled.value = true
                rememberDeferredDeleteAccount(context, email.trim())
                startRealtime()
                startPeriodicFlush()
                // Replay this account's signed-out deletes BEFORE the pull, so
                // the pull cannot restore a row they delete.
                replayDeferredDeletes(context, email.trim())
                // Pull FIRST and AWAIT it: a fresh device must ingest the
                // cloud state before it pushes. pullAll/pushAll are
                // fire-and-forget Jobs — running them back-to-back raced
                // them on the IO dispatcher, and this device's empty local
                // payloads (stamped updatedAt=now by buildAll) overwrote the
                // cloud's profiles/settings blobs before or while the pull
                // read them. The sign-in transfer then had nothing to
                // deliver. Sequencing the push after the pull completes
                // means the push re-seeds the merged state — a no-op for
                // existing rows, correct for genuinely new ones.
                pullAllNow(context)
                pushAllNow(context)
                // SAFETY NET: the awaited pull above covers the happy path,
                // but on flaky TV networks it can silently fail (transient
                // HTTP failure in pullPrefs) and the fresh device would come
                // up empty with no retry. The catch-up pull re-runs in a few
                // seconds; it's idempotent (remote-vs-local updatedAt merge)
                // so a redundant run only costs one read query.
                ensureBackgroundPull(context)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "signIn failed", Redaction.throwable(e))
                _authState.value =
                    AuthState.Error(Redaction.text(e.message).ifBlank { "Sign-in failed" })
            }
        }
    }

    fun signUp(context: Context, email: String, password: String) {
        val c = client
        if (c == null) {
            _authState.value = AuthState.Error(SYNC_NOT_CONFIGURED_MESSAGE)
            return
        }
        _authState.value = AuthState.SigningIn
        scope.launch {
            try {
                c.auth.signUpWith(io.github.jan.supabase.auth.providers.builtin.Email) {
                    this.email = email.trim()
                    this.password = password
                }
                // Projects with "Confirm email" enabled return success but
                // NO session — the account exists only after the user clicks
                // the email link. Session-present means auto-confirm is on
                // and we're signed in for real.
                if (c.auth.currentSessionOrNull() == null) {
                    _authState.value = AuthState.Error(
                        "Account created! Check ${email.trim()} for a confirmation " +
                            "link, then sign in here."
                    )
                    return@launch
                }
                persistSession(context, email.trim())
                _authState.value = AuthState.SignedIn(email.trim())
                _syncEnabled.value = true
                rememberDeferredDeleteAccount(context, email.trim())
                startRealtime()
                startPeriodicFlush()
                // Fresh account: push local state up as the initial seed.
                pushAll(context)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "signUp failed", Redaction.throwable(e))
                _authState.value =
                    AuthState.Error(Redaction.text(e.message).ifBlank { "Sign-up failed" })
            }
        }
    }

    fun signOut(context: Context) {
        val c = client ?: return
        // Capture the account id BEFORE the session store is cleared below: a
        // delete made after this sign-out must be attributed to THIS account so
        // it replays only when this account signs back in (see
        // [stageSignedOutDelete]). An upgraded install may sign out before the
        // id was ever recorded, which is why this reads the session email too.
        rememberDeferredDeleteAccount(context)
        // Drop queued writes: they belong to the account being left, and a
        // later flush (or the OutboxFlushWorker) would push them under
        // whichever account signs in next.
        //
        // This does NOT wait for a flush already in flight (SD-6): one that
        // passed its entry check has its snapshot in hand and will finish
        // uploading. Those rows are the account's own and are correctly
        // attributed, so the outcome is a few lost writes on the way out - not
        // a misattribution - and the local Room state is what a same-account
        // sign-in recovers from. Blocking sign-out on the network to chase them
        // would trade a correct, immediate sign-out for a tardy one.
        outbox.clear()
        periodicFlushJob?.cancel()
        periodicFlushJob = null
        // Reset the one-shot guards so a subsequent sign-in on the SAME
        // process actually starts the loops again (previously a sign-out →
        // sign-in cycle left the app silently without realtime/flush loops —
        // reported changes only synced when the user manually hit Sync now).
        backgroundPullStarted = false
        // Flip the state and drop the session SYNCHRONOUSLY, before the network
        // sign-out runs: the app must stop treating the account as signed-in
        // - and stop stamping writes for it - the instant the user asks, not
        // whenever the server round-trip happens to finish. Doing this inside
        // the coroutine left a window where a queued write could be stamped
        // for the account being left and pushed under the next sign-in.
        syncPrefs(context).edit().clear().apply()
        _authState.value = AuthState.SignedOut
        _syncEnabled.value = false
        // Stop realtime here too, alongside the state flip, and NOT after the
        // network sign-out (SD-7). The channels are subscribed with the account
        // being left, so a change they deliver during that round-trip was still
        // applied to local state - on a device that has already signed out, for
        // a session that is over. Waiting for `c.auth.signOut()` left the
        // subscription live for exactly as long as the network took.
        stopRealtime()
        scope.launch {
            runCatching { c.auth.signOut() }
        }
    }

    private suspend fun persistSession(context: Context, email: String) {
        val c = client ?: return
        val refresh = c.auth.currentSessionOrNull()?.refreshToken
        syncPrefs(context)
            .edit()
            .putString(KEY_REFRESH_TOKEN, refresh)
            .putString(KEY_EMAIL, email)
            .apply()
    }

    /**
     * Re-saves the client's CURRENT refresh token (and the already-known
     * email) to prefs. Used by the session-status collector and the updater.
     */
    private suspend fun persistSessionFromClient(context: Context) {
        val c = client ?: return
        val refresh = c.auth.currentSessionOrNull()?.refreshToken ?: return
        if (refresh.isBlank()) return
        val prefs = syncPrefs(context)
        prefs.edit()
            .putString(KEY_REFRESH_TOKEN, refresh)
            .putString(KEY_EMAIL, prefs.getString(KEY_EMAIL, null).orEmpty())
            .apply()
    }

    /**
     * Synchronous one-shot save of the client's current refresh token.
     * Called right before the in-app updater commits an APK install: the
     * install kills this process, and a background auto-refresh since the
     * last persist would leave the saved token SPENT — the post-update cold
     * start would then trip Supabase's reuse detection and sign the user
     * out. Blocking briefly here is fine: we are on the updater's IO
     * coroutine and about to die anyway.
     */
    fun persistSessionBeforeProcessExit() {
        val context = appContextRef?.get() ?: return
        // Plain runCatching on purpose, not runCatchingCancellable: this runs
        // during an APK install, on a path where the process is about to be
        // replaced and nothing may throw. The block does not suspend (it
        // bridges with runBlocking), so there is no cancellation to swallow -
        // which is also why the migration scanner skips blocks that reach their
        // suspend calls through runBlocking.
        runCatching {
            kotlinx.coroutines.runBlocking { persistSessionFromClient(context) }
        }
    }

    // ── Outbox (offline-safe local-write flush) ─────────────────────

    // [OutboxItem] / [OutboxQueue] hold the coalescing contract (and are
    // unit tested). Every mutation reports the pending count to the Settings
    // sync-health panel.
    /**
     * Durable mirror behind [outbox]: every mutation is written to the
     * `sync_outbox` table so a write that never reached the cloud survives
     * process death. Off the main thread (its own scope), and a persistence
     * failure only costs durability, never the write itself.
     */
    /**
     * One thread for the mirror's write-behind ops.
     *
     * They used to be independent `scope.launch`es on Dispatchers.IO, i.e. no
     * order at all: a `delete` for a row that had just flushed could land before
     * the `save` that queued it (the flushed row resurrects and re-uploads after
     * a restart - idempotent, but a phantom), and a `save` could be overtaken by
     * a later `clear` (a queued write dropped without ever being attempted).
     * A single-parallelism dispatcher gives those three the order they were
     * written in, which is the only order that matches the queue they mirror.
     */
    private val outboxStoreScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private val outboxStore = object : OutboxStore {
        override fun save(item: OutboxItem) {
            outboxStoreScope.launch {
                runCatchingCancellable { outboxDao()?.upsert(item.toEntity()) }
                    .onFailure { Log.w(TAG, "outbox persist failed: ${Redaction.text(it.message)}") }
            }
        }

        override fun delete(ids: Collection<String>) {
            if (ids.isEmpty()) return
            val list = ids.toList()
            outboxStoreScope.launch {
                runCatchingCancellable { outboxDao()?.deleteByIds(list) }
                    .onFailure { Log.w(TAG, "outbox delete failed: ${Redaction.text(it.message)}") }
            }
        }

        override fun clear() {
            outboxStoreScope.launch {
                runCatchingCancellable { outboxDao()?.deleteAll() }
                    .onFailure { Log.w(TAG, "outbox clear failed: ${Redaction.text(it.message)}") }
            }
        }

        /**
         * The delete form of [save]/[delete] - see [OutboxStore.replaceNow] for
         * why a tombstone cannot ride the fire-and-forget pair above.
         *
         * Blocking on purpose, and for a stronger reason than the sign-in
         * bridge ([persistSessionFromClient]) has: the caller has already
         * deleted the local row, so the tombstone is the ONLY half that can
         * repair the pair, and an ordinary launch is one process death away
         * from losing it. Room's suspend DAO runs on its own executor, so this
         * waits on a background write rather than performing one - no disk I/O
         * on the caller's thread - and it is one small write per user delete.
         */
        override fun replaceNow(
            removedIds: Collection<String>,
            items: List<OutboxItem>
        ): Boolean = runCatchingCancellable {
            kotlinx.coroutines.runBlocking {
                val dao = outboxDao()
                if (dao != null) {
                    // The drop goes first: the items below carry those same ids,
                    // and the other order would leave neither.
                    if (removedIds.isNotEmpty()) dao.deleteByIds(removedIds.toList())
                    if (items.isNotEmpty()) dao.upsertAll(items.map { it.toEntity() })
                }
            }
        }.onFailure {
            Log.w(TAG, "outbox delete persist failed: ${Redaction.text(it.message)}")
        }.isSuccess
    }

    private val outbox = OutboxQueue(
        onChange = { pending -> _pendingOutbox.value = pending },
        store = outboxStore
    )

    private fun outboxDao(): SyncOutboxDao? =
        appContextRef?.get()?.let { ctx ->
            runCatching { WatchHistoryDatabase.getInstance(ctx).syncOutboxDao() }
                .getOrNull()
        }

    // Uses the companion id (not outbox.id) on purpose: `outbox` initializes
    // with `outboxStore`, and referencing `outbox` here would be a cycle the
    // compiler cannot infer through.
    private fun OutboxItem.toEntity() = SyncOutboxEntity(
        id = OutboxQueue.id(table, keyColumn, key),
        tableName = table,
        keyColumn = keyColumn,
        itemKey = key,
        payloadJson = payload.toString(),
        enqueuedAtMs = enqueuedAtMs
    )

    /**
     * Rebuilds the in-memory outbox from the durable table on startup, so
     * writes stranded by a previous process death are flushed by the normal
     * periodic loop while the app is open, or by
     * [com.kennyb1201.kbstream.work.OutboxFlushWorker] while it is closed.
     * [OutboxQueue.seed] never re-persists, so this is not a write loop.
     */
    private suspend fun hydrateOutbox() {
        val rows = runCatchingCancellable { outboxDao()?.getAll() }.getOrNull().orEmpty()
        if (rows.isEmpty()) return
        val items = rows.mapNotNull { row ->
            runCatching {
                OutboxItem(
                    table = row.tableName,
                    keyColumn = row.keyColumn,
                    key = row.itemKey,
                    payload = Json.parseToJsonElement(row.payloadJson).jsonObject,
                    enqueuedAtMs = row.enqueuedAtMs
                )
            }.getOrNull()
        }
        outbox.seed(items)
        Log.i(TAG, "outbox hydrated: ${items.size} pending write(s)")
    }

    /**
     * Profile scoping: item keys are prefixed with the profile id captured
     * at enqueue time so profiles never see each other's rows (the
     * account-level RLS keeps other accounts out; this keeps sibling
     * profiles separated). "p:<profileId>:<originalKey>".
     *
     * The pid is passed EXPLICITLY by the bulk push paths (pushHistory/
     * pushWatched stream hundreds of rows through enqueue*): re-resolving
     * the active profile per row meant a mid-push profile switch stamped
     * the OLD profile's rows with the NEW profile's scope — those rows then
     * synced back down onto the new profile as phantom watched markers
     * (with clean Simkl/MDBList dashboards, because the data never came
     * from the trackers — it was our own sync table echoing it back).
     *
     * Resolution also falls back to the profile persisted in the profiles
     * store when the in-memory active profile is not bound yet: this runs
     * from Application.onCreate (session restore, seed push) before
     * MainActivity activates a profile, and a null here used to publish the
     * legacy un-namespaced rows — which every profile then pulled and wrote
     * into its own stores.
     */
    private fun currentProfileId(): String? =
        appContextRef?.get()
            ?.let { com.kennyb1201.kbstream.data.sync.ProfileStorage.activeProfileId(it) }
            ?: com.kennyb1201.kbstream.data.sync.ProfileManager.activeProfile.value?.id

    // Pure key rules live in [SyncKeys] so they are unit tested directly.
    private fun scopedKey(originalKey: String, pid: String? = currentProfileId()): String =
        SyncKeys.scoped(originalKey, pid)

    private fun unscopedKey(storedKey: String): String = SyncKeys.unscoped(storedKey)

    private fun storedKeyMatchesProfile(storedKey: String, pid: String?): Boolean =
        SyncKeys.matchesProfile(storedKey, pid)

    private fun storedKeyMatchesActiveProfile(storedKey: String): Boolean =
        storedKeyMatchesProfile(storedKey, currentProfileId())

    /**
     * Attributes the account's watched titles to the profiles that watched
     * them, from the cloud rows themselves.
     *
     * This is the only place that sees EVERY profile's history at once: a
     * synced row's key is scoped to the profile that wrote it, while the pull
     * below only applies the active profile's rows. The tracker feeds (Simkl,
     * MDBList) are account-wide, so a show watched under a sibling profile came
     * back as a Continue Watching card on this one, with no local history to
     * explain it - "there's a kids show in my continue watching on profile 1
     * that's supposed to be in profile 3". Recording the owners here means the
     * attribution is already complete after the first sync, instead of only
     * once each profile has been opened since the map existed (see
     * TitleProfileOwnership).
     *
     * Legacy unscoped rows name no profile and are skipped: they predate the
     * profile system, and guessing one would be worse than leaving the title
     * unattributed.
     */
    private fun recordHistoryTitleOwners(rows: List<SyncRowDto>) {
        if (rows.isEmpty()) return

        val context = appContextRef?.get() ?: return
        val owners = LinkedHashMap<String, String>()

        for (row in rows) {
            val storedId = row.itemId ?: continue
            val profileId = SyncKeys.scopeOf(storedId) ?: continue
            val remote = row.payload

            val key = com.kennyb1201.kbstream.data.history.titleProfileKey(
                type = remote.str("type"),
                title = remote.str("name")
            ) ?: continue

            owners[key] = profileId
        }

        com.kennyb1201.kbstream.data.history.TitleProfileOwnership
            .recordAll(context, owners)
    }

    /** Account-wide keys (the profiles list itself) bypass the profile filter. */
    private fun storedKeyApplies(storedKey: String): Boolean {
        if (storedKey == PrefsPayloadBuilder.KEY_PROFILES) return true
        return storedKeyMatchesActiveProfile(storedKey)
    }

    // ── One-time cross-profile poison sweep ─────────────────────────
    //
    // The sweep itself — local cache wipe plus cloud-row cleanup for the
    // phantom markers a mid-flight profile switch used to leak — lives in
    // [PoisonSweep]. Only these two entry points stay here, so every existing
    // caller keeps working unchanged.

    /**
     * Fire-and-forget entry point; safe to call from anywhere (startup,
     * auth events). Concurrent callers are serialized inside [PoisonSweep]
     * and its flags make each part idempotent.
     */
    fun runOneTimePoisonSweep(context: Context) {
        PoisonSweep.run(
            context = context,
            scope = scope,
            client = { client },
            isSignedIn = { isSignedIn() },
            pullNow = { pullAllNow(it) }
        )
    }

    /**
     * One-line cleanup status for the Settings → Sync panel, so the user can
     * tell whether the cross-profile cleanup has run on this device.
     */
    fun poisonSweepStatus(context: Context): String = PoisonSweep.status(context)

    /**
     * CLEAR EYE BADGES — the deterministic reset of the ACTIVE profile's
     * in-progress state (resume rows + eye markers, local and cloud). The
     * automatic sweep can only delete rows it can PROVE are copies, which
     * leaves the phantom whose twin was already cleaned on another device; this
     * does not attribute anything, so it cannot miss. Fire-and-forget.
     */
    fun clearInProgressForActiveProfile(context: Context, onDone: (String) -> Unit = {}) {
        PoisonSweep.clearInProgressForActiveProfile(
            context = context,
            scope = scope,
            client = { client },
            isSignedIn = { isSignedIn() },
            pullNow = { pullAllNow(it) },
            onDone = onDone
        )
    }

    fun enqueueHistory(entity: WatchHistoryEntity, profileId: String? = currentProfileId()) {
        if (!isSignedIn()) return
        val payload = HistoryRowRules.payload(entity)
        val row = OutboxItem(TABLE_HISTORY, "item_id", scopedKey(entity.id, profileId), payload)
        outbox.put(row)
        scheduleFlush()
    }

    /**
     * Records a watch-history delete for [ids] as a TOMBSTONE that has to
     * STICK everywhere.
     *
     * A local row deletion used to be invisible to the sync layer, and the
     * merge ruled the other way: a DELETED local row has no timestamp at all, so
     * any surviving cloud copy won [remoteWins] and came straight back on the
     * next pull or realtime event — and realtime dropped the DELETE event
     * entirely, while a sibling device that still held the row re-uploaded it on
     * its next full push. That is how a resume row a "mark as watched" had just
     * cleared returned with its progress bar, and with it the Continue Watching
     * card, while the completed marker stayed behind (the marker is a different
     * row, so the checkmark and the bar showed at once).
     *
     * The delete is therefore a WRITE now: the same cloud row is replaced by a
     * payload that carries `deletedAt` and a matching `updatedAt`, so every
     * merge path (this device's own pull, realtime on a sibling, and the sibling's
     * next full push - see [pushHistory]) treats the delete as the newest write
     * and deletes the row instead of restoring it (see [HistoryTombstoneRules]).
     *
     * Enqueued through the durable outbox rather than fire-and-forget: the
     * tombstone survives process death and flushes on reconnect, so an offline
     * delete still reaches the account instead of leaving the cloud copy to win.
     * The pending outbox writes for those keys are dropped first - a queued
     * upload of the same row would otherwise flush after the tombstone and put
     * the live row back.
     *
     * Delete while SIGNED OUT. A signed-out device still has the account's cloud
     * rows, so dropping the delete let the next same-account sign-in pull the row
     * straight back. Such a delete cannot go into [outbox] - [signOut] clears
     * that on purpose so a queued write never lands under a DIFFERENT account -
     * so it is staged with the account id in [SyncDeferredDeletes] (see
     * [stageSignedOutDelete]) and replayed only when that SAME account signs
     * back in (see [replayDeferredDeletes], run before the pull on both the
     * sign-in and session-restore paths). The pull also honors a tombstone that
     * is queued but not yet flushed, so the pull racing a replay cannot
     * re-insert the row the tombstone deletes.
     */
    fun deleteHistoryRows(
        ids: List<String>,
        profileId: String? = currentProfileId()
    ) {
        if (ids.isEmpty()) return
        if (!isSignedIn()) {
            // Signed out: there is no session to enqueue into, but the
            // account's cloud copy still EXISTS, so the next same-account
            // sign-in would pull this row straight back. Stage the delete under
            // the account it belongs to and replay it on that account's next
            // sign-in (see [SyncDeferredDeletes]).
            stageSignedOutDelete(ids, profileId)
            return
        }

        val cleanIds =
            ids.mapNotNull { id -> id.trim().takeIf { it.isNotBlank() } }.distinct()
        if (cleanIds.isEmpty()) return

        val now = System.currentTimeMillis()
        val tombstones = cleanIds.map { id ->
            OutboxItem(
                TABLE_HISTORY,
                "item_id",
                scopedKey(id, profileId),
                HistoryTombstoneRules.tombstone(id, now)
            )
        }
        // ONE awaited write for the pair: the queued uploads for these keys go
        // and the tombstones land, in that order, before this returns. The
        // local row is already gone here, so the tombstone is the only half
        // that can repair the delete - it may not sit in a launch the drop can
        // outrun, nor one a process death can lose (see
        // [OutboxStore.replaceNow]).
        if (!outbox.replaceKeys(
                TABLE_HISTORY,
                "item_id",
                cleanIds.map { id -> scopedKey(id, profileId) },
                tombstones
            )
        ) {
            Log.w(TAG, "history delete: ${cleanIds.size} tombstone(s) queued, not yet durable")
        }
        scheduleFlush()
    }

    /**
     * Wipes this profile's watch history + watched markers in the CLOUD.
     *
     * The Settings "Clear Continue Watching" reset cleared only the local
     * Room rows, so the pull merge immediately put everything back: the
     * remote copy is newer than the deleted local row (which has no timestamp
     * at all), so `pullHistory` re-inserted every resume row, and the resume
     * bar plus its Continue Watching card reappeared on the next sync - while
     * the completed markers came back too, so the episode card showed a
     * checkmark WITH a progress bar.
     *
     * Pending outbox writes for the profile and the tombstones that supersede
     * them go out as ONE awaited durable write per table (see
     * [OutboxQueue.replaceProfileScoped]): a queued progress write would
     * otherwise flush after the wipe and resurrect the row, and a tombstone
     * left in a fire-and-forget launch is one process death away from being
     * lost. The cloud rows are matched by their profile-scoped key, so only
     * this profile's state is touched.
     *
     * Signed out: the cloud rows still exist but cannot be enumerated (that
     * needs a session), so the wipe is STAGED from the local rows instead,
     * tagged with the last account, and replayed on that account's next
     * sign-in exactly like a single-row signed-out delete (see
     * [stageSignedOutClear]). Without this the reset looked like it worked and
     * then every resume row and marker came straight back on the next sync.
     */
    suspend fun clearWatchStateForActiveProfile() {
        val pid = currentProfileId()
        if (!isSignedIn()) {
            stageSignedOutClear(pid)
            return
        }

        // The wipe is a WRITE now, not a cloud DELETE. A real delete is
        // invisible to a sibling that still holds the row - a pull only iterates
        // rows that still EXIST - so the sibling kept its resume bar and
        // checkmark forever (the "Clear Continue Watching doesn't reach my
        // other TV" report). Instead, enumerate this profile's cloud rows and
        // replace each with a history tombstone / a cleared watched marker
        // through the durable outbox - the same "a delete is a newer write"
        // mechanism single-row deletes use (see [HistoryTombstoneRules]), so
        // every merge path on every device deletes the row too.
        withContext(Dispatchers.IO) {
            val c = client
            val now = System.currentTimeMillis()

            // What replaces each cloud row, read before anything is written:
            // the queued writes for this profile and the tombstones that
            // supersede them have to reach the durable table together (see the
            // pair of writes below).
            val tombstones = if (c == null) {
                emptyList()
            } else {
                runCatching {
                    pullRows(TABLE_HISTORY, "item_id")
                        .mapNotNull { row -> row.itemId }
                        .filter { storedKeyMatchesProfile(it, pid) }
                        .map { storedId ->
                            OutboxItem(
                                TABLE_HISTORY,
                                "item_id",
                                storedId,
                                HistoryTombstoneRules.tombstone(unscopedKey(storedId), now)
                            )
                        }
                }.onFailure { e ->
                    Log.w(TAG, "clearWatchState history failed: ${Redaction.text(e.message)}")
                    (e as? Exception)?.let { recordSyncError("Clear", it) }
                }.getOrDefault(emptyList())
            }

            val clearedMarkers = if (c == null) {
                emptyList()
            } else {
                runCatching {
                    pullRows(TABLE_WATCHED, "item_key")
                        .mapNotNull { row ->
                            val storedKey = row.itemKey ?: return@mapNotNull null
                            if (!storedKeyMatchesProfile(storedKey, pid)) return@mapNotNull null
                            val remote = row.payload
                            // A cleared watched row is a NEGATIVE marker with a
                            // fresh stamp, not a delete: the watched table has
                            // no tombstone field, so retracting a marker is how
                            // it syncs (see [WatchedMarkerRules]).
                            OutboxItem(
                                TABLE_WATCHED,
                                "item_key",
                                storedKey,
                                WatchedMarkerRules.payload(
                                    key = remote.str("key") ?: unscopedKey(storedKey),
                                    imdbId = remote.str("imdbId") ?: "",
                                    mediaType = remote.str("mediaType") ?: "",
                                    isWatched = false,
                                    isPartiallyWatched = false,
                                    updatedAt = now
                                )
                            )
                        }
                }.onFailure { e ->
                    Log.w(TAG, "clearWatchState watched failed: ${Redaction.text(e.message)}")
                    (e as? Exception)?.let { recordSyncError("Clear", it) }
                }.getOrDefault(emptyList())
            }

            // ONE awaited write per table, and both run even when the
            // enumeration above failed: dropping the queued writes is not
            // optional, or one of them flushes after the wipe and puts the row
            // straight back (see [OutboxStore.replaceNow]).
            val historyDurable =
                outbox.replaceProfileScoped(TABLE_HISTORY, "item_id", pid, tombstones)
            val watchedDurable =
                outbox.replaceProfileScoped(TABLE_WATCHED, "item_key", pid, clearedMarkers)
            if (!historyDurable || !watchedDurable) {
                Log.w(TAG, "clearWatchState: tombstones queued for profile=$pid, not yet durable")
            }
            Log.i(TAG, "clearWatchState: queued tombstones for profile=$pid")
        }

        scheduleFlush()
    }

    /**
     * Publishes a cleared set of rows as WRITES instead of cloud deletes.
     *
     * Extracted from [clearWatchStateForActiveProfile] for the one-time
     * in-progress reset (see [PoisonSweep.clearInProgressForActiveProfile]) and
     * now shared with the poison sweep's own cloud pass
     * ([PoisonSweep.run]), which used to hard-DELETE its cloud rows. A real
     * delete is invisible to a sibling that still holds its copy - a pull only
     * iterates rows that still
     * EXIST - so the other TV went on drawing the resume bar and the eye badge
     * the viewer had just cleared on this one, and its next push put the row
     * back here. Each row is therefore replaced by a history tombstone or a
     * cleared watched marker through the durable outbox, the same "a delete is a
     * newer write" mechanism every other delete uses (see
     * [HistoryTombstoneRules] and [WatchedMarkerRules]).
     *
     * [historyIds] are BARE item ids; [watched] is the stored (already
     * profile-scoped) key of each marker row with the payload the cloud copy
     * carries. The pending outbox writes for those exact keys are dropped
     * first: a queued upload of a row this call is deleting would otherwise
     * flush afterwards and put it back.
     */
    internal fun publishClearedRows(
        historyIds: List<String>,
        watched: List<Pair<String, JsonObject>>,
        profileId: String?
    ) {
        if (!isSignedIn()) return
        val ids = historyIds.mapNotNull { id -> id.trim().takeIf { it.isNotBlank() } }.distinct()
        val markers = watched.filter { (storedKey, _) -> storedKey.isNotBlank() }
        if (ids.isEmpty() && markers.isEmpty()) return

        val now = System.currentTimeMillis()
        val historyTombstones = ids.map { id ->
            OutboxItem(
                TABLE_HISTORY,
                "item_id",
                scopedKey(id, profileId),
                HistoryTombstoneRules.tombstone(id, now)
            )
        }
        val clearedMarkers = markers.map { (storedKey, remote) ->
            // A cleared watched row is a NEGATIVE marker with a fresh stamp,
            // not a delete: the watched table has no tombstone field, so
            // retracting a marker is how it syncs (see [WatchedMarkerRules]).
            OutboxItem(
                TABLE_WATCHED,
                "item_key",
                storedKey,
                WatchedMarkerRules.payload(
                    key = remote.str("key") ?: unscopedKey(storedKey),
                    imdbId = remote.str("imdbId") ?: "",
                    mediaType = remote.str("mediaType") ?: "",
                    isWatched = false,
                    isPartiallyWatched = false,
                    updatedAt = now
                )
            )
        }
        // One awaited write per table: the queued writes for these keys go and
        // the clear lands in their place, in that order, before this returns.
        // The local rows are already gone, so a clear left in a
        // fire-and-forget launch is one process death away from putting them
        // back (see [OutboxStore.replaceNow]).
        if (ids.isNotEmpty() &&
            !outbox.replaceKeys(
                TABLE_HISTORY,
                "item_id",
                ids.map { id -> scopedKey(id, profileId) },
                historyTombstones
            )
        ) {
            Log.w(TAG, "in-progress clear: ${ids.size} tombstone(s) queued, not yet durable")
        }
        if (markers.isNotEmpty() &&
            !outbox.replaceKeys(
                TABLE_WATCHED,
                "item_key",
                markers.map { (storedKey, _) -> storedKey },
                clearedMarkers
            )
        ) {
            Log.w(TAG, "in-progress clear: ${markers.size} clear(s) queued, not yet durable")
        }
        scheduleFlush()
    }

    /**
     * Publishes a watched-override blob as a DELIBERATE EMPTY SET.
     *
     * The watched-override table has no tombstone field, so the "a delete is a
     * newer write" rule [publishClearedRows] applies to history rows and
     * watched markers takes its other legal shape here: an empty override set
     * that says it MEANS to be empty ([WatchedOverridesRules.CLEARED_FIELD]),
     * stamped now. The apply side reads that as an unmark of every key the
     * sibling holds, so the duplicated set the poison sweep identified cannot
     * be put back by that sibling's older stamp.
     *
     * Used by the sweep's cloud pass, which used to hard-DELETE these blobs -
     * invisible to a sibling that still holds one, so its next full push
     * re-uploaded exactly the cross-profile set the sweep had just removed.
     *
     * [storedKeys] are the stored (already profile-scoped) `pref_key`s, and the
     * queued writes for those keys are dropped in the same awaited write that
     * queues the clear.
     */
    internal fun publishOverrideClears(storedKeys: List<String>) {
        if (!isSignedIn()) return
        val keys = storedKeys
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (keys.isEmpty()) return
        val now = System.currentTimeMillis()
        val payload = buildJsonObject {
            put("updatedAt", now)
            put(WatchedOverridesRules.CLEARED_FIELD, true)
            putJsonArray("overrides") { }
            putJsonObject(WatchedOverridesRules.TIMESTAMPS_FIELD) { }
            putJsonObject(WatchedOverridesRules.REMOVED_FIELD) { }
        }
        val items = keys.map { stored ->
            OutboxItem(TABLE_PREFS, "pref_key", stored, payload)
        }
        if (!outbox.replaceKeys(TABLE_PREFS, "pref_key", keys, items)) {
            Log.w(TAG, "override clear: ${keys.size} blob(s) queued, not yet durable")
        }
        scheduleFlush()
    }

    /**
     * Publishes one poster/backdrop override to the account.
     *
     * Called by the long-press menu's write ([ImageOverrides.choose]) right
     * after the local row lands, so the choice reaches the other device through
     * the ordinary two channels - the outbox flush for the write, the realtime
     * channel for the sibling that is already open. Signed out, this is a
     * no-op exactly like [enqueueHistory]: there is no account to file it
     * under, and the same-account sign-in re-seeds what this device has (see
     * [pushOverrides]).
     */
    fun enqueueImageOverride(
        entity: ImageOverrideEntity,
        profileId: String? = currentProfileId(),
        updatedAt: Long = System.currentTimeMillis()
    ) {
        if (!isSignedIn()) return
        val row = OutboxItem(
            TABLE_OVERRIDES,
            KEY_OVERRIDES,
            scopedKey(entity.key, profileId),
            ImageOverrideRowRules.payload(entity, updatedAt)
        )
        outbox.put(row)
        scheduleFlush()
    }

    /**
     * Publishes a RESET ("Reset to default") as one tombstone per key.
     *
     * A tombstone rather than a cloud delete, for the reason the history table
     * documents at length: a row that is simply deleted is invisible to a pull,
     * so a sibling that still holds its copy goes on drawing the still the
     * viewer asked to be rid of. The reset is therefore a newer WRITE carrying
     * `deletedAt`, and every merge path reads it as "stop drawing this".
     *
     * Signed out there is no session to enqueue into - and the account's cloud
     * rows still exist - so the reset is staged against the account it belongs
     * to and replayed on that account's next sign-in, exactly like a signed-out
     * history delete ([SyncDeferredDeletes]).
     */
    fun clearImageOverrides(
        keys: List<String>,
        profileId: String? = currentProfileId()
    ) {
        val clean = keys.mapNotNull { key -> key.trim().takeIf { it.isNotBlank() } }.distinct()
        if (clean.isEmpty()) return
        if (!isSignedIn()) {
            stageSignedOutOverrideClear(clean, profileId)
            return
        }
        val now = System.currentTimeMillis()
        val tombstones = clean.map { key ->
            OutboxItem(
                TABLE_OVERRIDES,
                KEY_OVERRIDES,
                scopedKey(key, profileId),
                ImageOverrideRowRules.tombstone(key, now)
            )
        }
        // One awaited write for the pair, for the reason every other delete
        // path takes one: the local rows are gone, so the tombstone is the only
        // repair and may not lag a process death (see
        // [OutboxStore.replaceNow]).
        if (!outbox.replaceKeys(
                TABLE_OVERRIDES,
                KEY_OVERRIDES,
                clean.map { key -> scopedKey(key, profileId) },
                tombstones
            )
        ) {
            Log.w(TAG, "override reset: ${clean.size} tombstone(s) queued, not yet durable")
        }
        scheduleFlush()
    }

    fun enqueueWatched(entity: WatchedStatusEntity, profileId: String? = currentProfileId()) {
        if (!isSignedIn()) return
        // Both badge flags travel: a row that only carried isWatched arrived
        // on the other device looking un-watched, which is how a synced row
        // could erase that device's eye badge (see [WatchedMarkerRules]).
        val payload = WatchedMarkerRules.payload(
            key = entity.key,
            imdbId = entity.imdbId,
            mediaType = entity.mediaType,
            isWatched = entity.isWatched,
            isPartiallyWatched = entity.isPartiallyWatched,
            updatedAt = entity.updatedAt
        )
        val row = OutboxItem(TABLE_WATCHED, "item_key", scopedKey(entity.key, profileId), payload)
        outbox.put(row)
        scheduleFlush()
    }

    /**
     * Prefs/addons/Simkl/IPTV blobs. [prefKey] is a stable string like
     * "display_prefs". Everything except the profiles list itself is stored
     * profile-scoped ("p:<profileId>:<prefKey>") so sibling profiles never
     * overwrite or read each other's settings in the cloud.
     */
    fun enqueuePrefs(
        context: Context,
        prefKey: String,
        payload: JsonObject,
        profileId: String? = currentProfileId()
    ) {
        if (!isSignedIn()) return
        val stored = if (prefKey == PrefsPayloadBuilder.KEY_PROFILES) {
            prefKey
        } else {
            scopedKey(prefKey, profileId)
        }
        val row = OutboxItem(TABLE_PREFS, "pref_key", stored, payload)
        outbox.put(row)
        scheduleFlush()
    }

    // @Volatile: scheduleFlush()/startPeriodicFlush() run on the multi-threaded
    // IO scope, so the `?.isActive` check-then-act must see another thread's
    // write rather than a stale cached read.
    @Volatile
    private var flushJob: kotlinx.coroutines.Job? = null

    // Coalescing window for write bursts (bulk watched import, profile
    // switching, the player's position saves). Single-shot before: the
    // first enqueue started a 400ms timer, and any row enqueued AFTER the
    // timer fired stayed in the outbox until the NEXT enqueue or the 60s
    // periodic retry — remote devices saw that write seconds-to-minutes
    // late or not at all until the app was used again ("flaky sync").
    private const val FLUSH_DEBOUNCE_MS = 400L

    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            while (true) {
                delay(FLUSH_DEBOUNCE_MS)
                val removed = flushOutbox()
                // Sign-out strands retry rows until the next sign-in (the
                // periodic loop restarts then) — don't busy-loop on them.
                if (!isSignedIn()) break
                // Loop while writes keep arriving so nothing strands: a row
                // enqueued during flushOutbox() re-arms this loop instead of
                // waiting for the next enqueue/60s retry.
                if (outbox.isEmpty) break
                // No rows left the queue: a chunk failed (offline, or rejected
                // by RLS), or a write that landed during the flush failed too.
                // Do NOT loop on that — the old `while(true)` retried every
                // 400 ms for as long as one row could never upload, a ~150
                // req/min storm. The 60 s periodic loop and the network-gated
                // OutboxFlushWorker own retries from here; the rows stay queued
                // (dead-lettering them would lose a legitimate offline write).
                if (removed == 0) break
            }
        }
    }

    // Retry loop for failed outbox rows (offline writes, RLS/network
    // failures). The header contract promises "retried on interval" — this
    // is that interval; without it a failed flush sits in the outbox until
    // the next local write happens to enqueue something.
    @Volatile
    private var periodicFlushJob: kotlinx.coroutines.Job? = null

    private fun startPeriodicFlush() {
        if (periodicFlushJob?.isActive == true) return
        periodicFlushJob = scope.launch {
            while (isSignedIn()) {
                delay(OUTBOX_RETRY_MS)
                if (!outbox.isEmpty) {
                    runCatchingCancellable { flushOutbox() }
                }
            }
        }
    }

    /**
     * Returns how many rows left the outbox. A zero result with a non-empty
     * outbox means the flush made no progress — the caller uses that to stop
     * hot-looping on rows that cannot upload.
     */
    private suspend fun flushOutbox(): Int {
        val c = client ?: return 0
        if (!isSignedIn()) return 0

        val batch = outbox.snapshot()
        if (batch.isEmpty()) return 0

        var removed = 0
        // True once any chunk uploaded. Distinguishes a real sync from a run
        // where every chunk threw, so the health timestamps are not stamped
        // over an all-failed attempt (see below).
        var anyChunkOk = false

        // Max one in-flight flush at a time: two flushOutbox() runs racing
        // (scheduled + periodic) would both upload the same rows — harmless
        // but wasteful, and interleaved chunk removals made the exact-row
        // accounting below harder to reason about.
        flushMutex.withLock {

        // Group by table and upload as batched upserts instead of one HTTP
        // round-trip per row — a bulk watched import used to fire hundreds
        // of sequential requests. Chunked so a huge outbox can't blow the
        // PostgREST request-size limit, and a failed chunk doesn't sink the
        // rest (failed rows stay in the outbox and retry idempotently).
        val byTable = batch.groupBy { it.table }
        for ((table, rows) in byTable) {
            rows.chunked(100).forEach { chunk ->
                try {
                    val bodies = chunk.map { row ->
                        buildJsonObject {
                            put(
                                when (row.keyColumn) {
                                    "item_id" -> "item_id"
                                    "item_key" -> "item_key"
                                    KEY_OVERRIDES -> KEY_OVERRIDES
                                    else -> "pref_key"
                                },
                                row.key
                            )
                            put("payload", row.payload)
                            put(
                                "updated_at",
                                java.time.Instant.ofEpochMilli(row.enqueuedAtMs).toString()
                            )
                        }
                    }
                    c.from(table).upsert(bodies)
                    // Remove only if the slot still maps to the EXACT row we
                    // just uploaded. If a newer write for the same key was
                    // enqueued while this upload was in flight, the slot now
                    // holds that newer row — removing by key alone would
                    // silently drop a write that never reached the cloud
                    // (lost update).
                    chunk.forEach { row -> if (outbox.remove(row)) removed++ }
                    anyChunkOk = true
                    clearSyncError()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w(TAG, "flush $table chunk of ${chunk.size} failed: ${Redaction.text(e.message)}")
                    recordSyncError("Upload", e)
                    // Keep in outbox; retried by the periodic sync loop.
                    //
                    // Deliberately unconditional (SD-3): the status is NOT
                    // inspected, so a 4xx/429/RLS-denied chunk retries forever
                    // at the periodic cadence. That is the trade-off this layer
                    // is built on - a write is never dropped - and it is
                    // bounded in practice by the 1/min periodic flush and by
                    // KEEP-coalescing the retry worker, so the cost is a slow
                    // trickle of doomed requests rather than a storm. A
                    // dead-letter after N attempts would cap that, at the price
                    // of a path on which a write can be lost; it is not taken
                    // here on purpose.
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    CrashReporter.recordNonFatal(
                        t,
                        mapOf("source" to "flush_outbox", "table" to table)
                    )
                    Log.e(TAG, "flush $table crashed: ${Redaction.text(t.message)}")
                }
            }
        }
        } // flushMutex
        val flushedAt = System.currentTimeMillis()
        // A flush in which EVERY upload failed is not a successful sync: leave
        // the health timestamps at the last real one, so the UI cannot report a
        // fresh sync over an all-failed run. The failure itself is already
        // surfaced by recordSyncError, and the batch is non-empty here.
        if (anyChunkOk) {
            _lastSyncAtMs.value = flushedAt
            _lastPushAtMs.value = flushedAt
        }

        // Rows still pending means we were offline (or a chunk failed): hand
        // the retry to WorkManager, whose request survives process death, so
        // the writes land even if the app is closed before connectivity is
        // back. KEEP-coalesced, so this cannot queue a burst of workers.
        if (!outbox.isEmpty) {
            appContextRef?.get()?.let { ctx ->
                runCatching {
                    com.kennyb1201.kbstream.work.OutboxFlushWorker.enqueue(ctx)
                }
            }
        }
        return removed
    }

    /**
     * Flush entry point for [com.kennyb1201.kbstream.work.OutboxFlushWorker],
     * which can run with the app closed. Waits briefly for the async session
     * restore (kicked off by [init] from MainApplication) to finish, then
     * flushes. Returns true only when the outbox ends up empty, so a still
     * stranded write asks WorkManager to retry it.
     *
     * Does NOT call [startClient] itself: MainApplication.onCreate already
     * ran [init] in this process, and a second startClient racing the first
     * would build two clients.
     */
    suspend fun flushOutboxWhenReady(
        context: Context,
        timeoutMs: Long = 20_000L
    ): Boolean {
        if (BuildConfig.SUPABASE_URL.isBlank() || BuildConfig.SUPABASE_ANON_KEY.isBlank()) {
            return false
        }
        // Wait on the STATE, not a 250 ms poll.
        //
        // This used to be two `while (...) delay(250)` busy-waits: up to 80
        // wake-ups to watch two values that already have flows, and each wake
        // answered a question this object can answer directly. `client` is a
        // @Volatile field with no flow of its own, so it keeps a (much longer)
        // poll; the signed-in half - the one that actually has to wait on a
        // cold start, since session restore is a network round-trip - now
        // suspends on [_authState] until it leaves SignedOut/SigningIn.
        val deadline = System.currentTimeMillis() + timeoutMs
        while (client == null && System.currentTimeMillis() < deadline) {
            delay(250L)
        }
        if (client == null) return false
        val remaining = deadline - System.currentTimeMillis()
        if (remaining > 0L) {
            // A hard rejection or a transient failure ends as SignedOut too, so
            // this does not wait out the full budget for a session that is not
            // coming back - only a SigningIn that is still in flight keeps it
            // suspended, and the timeout bounds even that.
            withTimeoutOrNull(remaining) {
                _authState.first {
                    it !is AuthState.SignedOut && it !is AuthState.SigningIn
                }
            }
        }
        if (!isSignedIn()) return false
        // A stranded write from a dead process is only in the table until the
        // init-time hydrate ran; seed again here so the worker flushes it even
        // if it started before hydration finished (putIfAbsent makes it safe).
        runCatchingCancellable { hydrateOutbox() }
        runCatchingCancellable { flushOutbox() }
            .onFailure { Log.w(TAG, "outbox flush from worker failed: ${Redaction.text(it.message)}") }
        return outbox.isEmpty
    }

    /** Serializes flushOutbox() bodies (see the mutex acquire above). */
    private val flushMutex = Mutex()

    // ── Pull + merge ────────────────────────────────────────────────

    fun pullAll(context: Context): kotlinx.coroutines.Job = scope.launch {
        pullAllNow(context)
    }

    fun pushAll(context: Context): kotlinx.coroutines.Job = scope.launch {
        pushAllNow(context)
    }

    /**
     * Awaited variants — callers that must sequence push-after-pull.
     * Prefs pull FIRST: the profiles blob inside it creates/activates the
     * account's profile on a fresh device, and the history/watched pulls
     * below filter rows by the ACTIVE profile — running them before the
     * profile exists would drop every scoped row (the fresh-device sign-in
     * transferred nothing, even when the pull itself succeeded).
     */
    private suspend fun pullAllNow(context: Context) {
        // The four pulls answer whether they RAN (see [pullHistory]). The order
        // is unchanged and still load-bearing: prefs FIRST, because the
        // profiles blob inside it creates/activates the account's profile on a
        // fresh device, and the history/watched pulls below filter rows by the
        // ACTIVE profile - running them before the profile exists would drop
        // every scoped row (the fresh-device sign-in transferred nothing, even
        // when the pull itself succeeded).
        val ran =
            listOf(
                pullPrefs(context),
                pullHistory(context),
                pullWatched(context),
                pullOverrides(context)
            )
        // All four false is not a slow sync, it is no sync: the client is gone
        // (signed out, or the session died under it), the tables are
        // unreachable, or every read threw. Stamping the timestamps there made
        // the sync-health panel report a sync that never happened - and "last
        // sync" is the number a viewer reads to decide whether to trust what is
        // on screen. A PARTIAL run still stamps: three tables really did land.
        if (ran.none { it }) return
        val pulledAt = System.currentTimeMillis()
        _lastSyncAtMs.value = pulledAt
        _lastPullAtMs.value = pulledAt
    }

    private suspend fun pushAllNow(context: Context) {
        // The pushes answer whether they RAN, exactly like the four pulls above.
        // Stamping unconditionally made "Sync now" (and a full resync) report a
        // fresh sync after a run in which the client was gone and every push
        // returned immediately - the panel's "last sync" is what a viewer reads
        // to decide whether the screen can be trusted, so it may only move when
        // something was actually attempted. A run with nothing to publish still
        // stamps: the tables really were consulted.
        val ran =
            listOf(
                pushHistory(context),
                pushWatched(context),
                pushOverrides(context),
                pushPrefsBlobs(context)
            )
        flushOutbox()
        if (ran.none { it }) return
        val pushedAt = System.currentTimeMillis()
        _lastSyncAtMs.value = pushedAt
        _lastPushAtMs.value = pushedAt
    }

    /**
     * Called on every profile switch. Outbox rows already carry the profile
     * scope captured at enqueue time ("p:<oldProfile>:…"), so pending writes
     * still land on the right cloud rows — they must NOT be dropped. The only
     * required action is re-pulling the NEW profile's rows (history/watched/
     * prefs) so the UI reflects the profile you switched to immediately.
     */
    fun onProfileSwitched(context: Context) {
        if (isSignedIn()) {
            pullAll(context)
        }
    }

    /**
     * Republishes the TV-launcher Watch Next rows from the ACTIVE profile's
     * history. Runs on [scope] because the DAO read is suspend and callers
     * (ProfileManager's switch sequence) are non-suspend.
     */
    fun launchLauncherRepublish(context: Context) {
        scope.launch {
            runCatchingCancellable {
                val entries = com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
                    .getInstanceScoped(context)
                    .watchHistoryDao()
                    .getAll()
                com.kennyb1201.kbstream.data.tv.TvLauncherPublisher.sync(context, entries)
            }.onFailure { e ->
                Log.w(TAG, "launcher republish failed: ${Redaction.text(e.message)}")
            }
        }
    }

    /** One PostgREST page. Supabase's default `max-rows` is 1,000. */
    private const val PULL_PAGE_SIZE = 500

    /** Hard stop on a runaway walk: 200 pages is 100k rows per table. */
    private const val PULL_MAX_PAGES = 200

    /**
     * Reads a whole sync table, page by page.
     *
     * A bare `select()` is CAPPED by the server's `max-rows` (1,000 by default
     * on Supabase) — and every pull here reads ALL profiles' rows and filters
     * client-side, so one busy account could silently lose everything past the
     * cap, with no error surfaced. An explicit `range` plus a stable order on
     * the table's key column makes the cap a page size instead of a ceiling.
     *
     * The order is the key column rather than `updated_at`: a page boundary
     * needs a total order, and two rows sharing an updated stamp would
     * otherwise be able to repeat or vanish between pages.
     */
    private suspend fun pullRows(table: String, keyColumn: String): List<SyncRowDto> {
        val c = client ?: return emptyList()
        val rows = ArrayList<SyncRowDto>()
        var page = 0
        while (page < PULL_MAX_PAGES) {
            val from = page.toLong() * PULL_PAGE_SIZE
            val batch = c.from(table)
                .select {
                    order(keyColumn, Order.ASCENDING)
                    range(from, from + PULL_PAGE_SIZE - 1)
                }
                .decodeList<SyncRowDto>()
            rows.addAll(batch)
            // A short page is the last one. An exactly-full last page costs one
            // extra (empty) request rather than risking a truncated pull.
            if (batch.size < PULL_PAGE_SIZE) return rows
            page++
        }
        Log.w(TAG, "pull $table hit the page limit; the tail was not read")
        return rows
    }

    private suspend fun pullHistory(context: Context): Boolean {
        val c = client ?: return false
        // Scope the whole pull to the profile active at START: a switch
        // mid-pull would otherwise filter rows against the NEW profile while
        // still writing into the OLD profile's Room instance (captured
        // below), stranding rows in the wrong database.
        val pid = currentProfileId()
        // Whether this pull actually RAN. [pullAllNow] stamps "last sync" from
        // the four answers: a pull that fails swallows its exception here (it
        // must - one broken table cannot abort the other three), so without an
        // answer the stamp below claimed a sync that never happened.
        var ok = true
        try {
            val rows = pullRows(TABLE_HISTORY, "item_id")

            // Which profile each title belongs to, taken from the rows of ALL
            // profiles (see recordHistoryTitleOwners) - done before the merge
            // below, which only applies this profile's.
            recordHistoryTitleOwners(rows)

            val db = WatchHistoryDatabase.getInstanceScoped(context)
            // Batch-load every local row once, then merge in memory: the old
            // per-row getById() loop was an N+1 (one query per remote row) on
            // every pull — a multi-hundred-row history stalled sync for
            // seconds and churned the DB.
            val localById = db.watchHistoryDao()
                .getByIds(rows.mapNotNull { row ->
                    val storedId = row.itemId ?: return@mapNotNull null
                    if (!storedKeyMatchesProfile(storedId, pid)) null else unscopedKey(storedId)
                }.distinct())
                .associateBy { it.id }
            var applied = 0
            var removed = 0
            // Batched like pullWatched below: one upsertAll / deleteByIds for
            // the whole pull instead of a transaction per remote row. Both
            // lists are dropped if the profile switched mid-pull (the new
            // profile's own pull re-runs).
            val pendingUpdates = mutableListOf<WatchHistoryEntity>()
            val pendingDeletes = mutableListOf<String>()
            for (row in rows) {
                // Bail on a mid-pull profile switch: remaining rows belong
                // to a filter/DB pair that no longer matches.
                if (currentProfileId() != pid) return false
                val remote = row.payload
                val remoteUpdated = remote["updatedAt"]?.jsonPrimitive?.content?.toLongOrNull() ?: continue
                val storedId = row.itemId ?: continue
                if (!storedKeyMatchesProfile(storedId, pid)) continue
                val id = unscopedKey(storedId)

                val localRow = localById[id]

                // A delete that is queued but not yet flushed outranks this
                // still-live cloud copy: applying the row here would resurrect
                // it locally, leaving the tombstone flush to remove only the
                // cloud copy. A signed-out delete replayed on sign-in walks
                // straight into this (replay runs before this pull). Apply the
                // queued delete locally instead.
                val pendingTombstone = outbox.pending(TABLE_HISTORY, "item_id", storedId)
                    ?.takeIf { HistoryTombstoneRules.isTombstone(it.payload) }
                if (pendingTombstone != null) {
                    // Removing the local row needs the same strictly-newer test
                    // a cloud tombstone gets below. The queue used to delete
                    // unconditionally, so a staged delete that had been overtaken
                    // by a later re-watch (the local row is the newer write)
                    // erased that resume position when the delete was flushed.
                    // The cloud row is skipped either way: it is the copy this
                    // delete is about to overwrite, and a newer local row is
                    // re-published by the push side once the tombstone is there.
                    val pendingDeletedAt =
                        HistoryTombstoneRules.deletedAt(pendingTombstone.payload)
                    if (localRow != null &&
                        pendingDeletedAt != null &&
                        HistoryTombstoneRules.tombstoneWins(
                            pendingDeletedAt,
                            localRow.updatedAt
                        )
                    ) {
                        pendingDeletes.add(id)
                        removed++
                    }
                    continue
                }

                // A tombstone is a delete, not a row to store: apply it by
                // removing the local copy when it is the newer write (see
                // HistoryTombstoneRules). This is what stops a delete from
                // being undone by the next pull.
                val deletedAt = HistoryTombstoneRules.deletedAt(remote)
                if (deletedAt != null) {
                    if (localRow != null &&
                        HistoryTombstoneRules.tombstoneWins(deletedAt, localRow.updatedAt)
                    ) {
                        pendingDeletes.add(id)
                        removed++
                    }
                    continue
                }

                val localUpdated = localRow?.updatedAt ?: 0L

                if (historyRemoteWins(
                        remoteUpdated = remoteUpdated,
                        remoteToken = HistoryTombstoneRules.mergeToken(remote),
                        localUpdated = localUpdated,
                        localToken = localRow?.let { HistoryTombstoneRules.mergeToken(HistoryRowRules.payload(it)) }
                    )
                ) {
                    pendingUpdates.add(
                        WatchHistoryEntity(
                            id = id,
                            parentId = remote.str("parentId") ?: "",
                            type = remote.str("type") ?: "movie",
                            name = remote.str("name") ?: "",
                            episodeTitle = remote.str("episodeTitle"),
                            overview = remote.str("overview"),
                            clearLogo = remote.str("clearLogo"),
                            backdropUrl = remote.str("backdropUrl"),
                            totalEpisodesInSeason = remote.lng("totalEpisodesInSeason")?.toInt(),
                            poster = remote.str("poster"),
                            streamUrl = remote.str("streamUrl"),
                            season = remote.lng("season")?.toInt(),
                            episode = remote.lng("episode")?.toInt(),
                            episodeStreamId = remote.str("episodeStreamId"),
                            positionMs = remote.lng("positionMs") ?: 0L,
                            durationMs = remote.lng("durationMs") ?: 0L,
                            updatedAt = remoteUpdated,
                            isCompleted = remote.bool("isCompleted"),
                            completedAt = remote.lng("completedAt"),
                            // A marker must stay a marker across devices, or
                            // the stats reads count it again on this one.
                            markedWatched = remote.markedWatchedRow()
                        )
                    )
                    applied++
                }
            }
            if ((pendingUpdates.isNotEmpty() || pendingDeletes.isNotEmpty()) &&
                currentProfileId() == pid
            ) {
                val dao = db.watchHistoryDao()
                if (pendingDeletes.isNotEmpty()) dao.deleteByIds(pendingDeletes)
                if (pendingUpdates.isNotEmpty()) dao.upsertAll(pendingUpdates)
            }
            if (applied > 0 || removed > 0) {
                Log.i(TAG, "history pull applied $applied rows, removed $removed (tombstones)")
                WatchedStatusRepository.invalidateAllCaches()
                com.kennyb1201.kbstream.data.tv.TvLauncherPublisher.sync(
                    context,
                    db.watchHistoryDao().getAll()
                )
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ok = false
            Log.w(TAG, "pullHistory failed: ${Redaction.text(e.message)}")
            recordSyncError("History sync", e)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            ok = false
            CrashReporter.recordNonFatal(t, mapOf("source" to "pull_history"))
            Log.e(TAG, "pullHistory crashed: ${Redaction.text(t.message)}")
        }
        return ok
    }

    private suspend fun pullWatched(context: Context): Boolean {
        val c = client ?: return false
        // Same captured-scope rule as pullHistory above.
        val pid = currentProfileId()
        var ok = true
        try {
            val rows = pullRows(TABLE_WATCHED, "item_key")

            val db = WatchHistoryDatabase.getInstanceScoped(context)
            // Same N+1 fix as pullHistory: load the relevant local rows once
            // and merge in memory instead of one getByKeys() round-trip per
            // remote row.
            val keysForActiveProfile = rows.mapNotNull { row ->
                val storedKey = row.itemKey ?: return@mapNotNull null
                if (!storedKeyMatchesProfile(storedKey, pid)) null else unscopedKey(storedKey)
            }.distinct()
            val localByKey = if (keysForActiveProfile.isEmpty()) {
                emptyMap()
            } else {
                db.watchedStatusDao().getByKeys(keysForActiveProfile).associateBy { it.key }
            }
            val pendingUpdates = mutableListOf<WatchedStatusEntity>()
            var applied = 0
            for (row in rows) {
                // Bail on a mid-pull profile switch (same rule as pullHistory).
                if (currentProfileId() != pid) return false
                val remote = row.payload
                val storedKey = row.itemKey ?: continue
                if (!storedKeyMatchesProfile(storedKey, pid)) continue
                val key = unscopedKey(storedKey)

                val localUpdated = localByKey[key]?.updatedAt ?: 0L

                // One shared merge rule (also used by the realtime applier) so
                // both badge flags always travel together.
                watchedMarkerRow(key, remote, localUpdated)?.let { merged ->
                    pendingUpdates.add(merged)
                    applied++
                }
            }
            // One batch write instead of one upsert per row. Dropped if the
            // profile switched mid-pull (the new profile's own pull re-runs).
            if (pendingUpdates.isNotEmpty() && currentProfileId() == pid) {
                db.watchedStatusDao().upsertAll(pendingUpdates)
            }
            if (applied > 0) {
                Log.i(TAG, "watched pull applied $applied rows")
                WatchedStatusRepository.invalidateAllCaches()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ok = false
            Log.w(TAG, "pullWatched failed: ${Redaction.text(e.message)}")
            recordSyncError("Watched sync", e)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            ok = false
            CrashReporter.recordNonFatal(t, mapOf("source" to "pull_watched"))
            Log.e(TAG, "pullWatched crashed: ${Redaction.text(t.message)}")
        }
        return ok
    }

    /**
     * Pulls the account's poster/backdrop overrides into the local table.
     *
     * Same shape as [pullWatched]: one read of the account's rows, then each of
     * them merged through [applyImageOverrideRow], which is also the realtime
     * applier for a single row - so the pull and the live channel can never
     * disagree about what a remote choice means, and the merge logic (the
     * profile filter, the pending-write skip, the tombstone clear, the no-op
     * for an identical row, the mirror refresh) exists once.
     *
     * What each row DOES is the applier's own verdict - it reads the local row
     * itself - so the pull does not repeat that decision to count it; the log
     * line below is the row count the account holds, not the number that
     * actually changed.
     */
    private suspend fun pullOverrides(context: Context): Boolean {
        val c = client ?: return false
        // Same captured-scope rule as pullHistory above.
        val pid = currentProfileId()
        var ok = true
        try {
            val rows = pullRows(TABLE_OVERRIDES, KEY_OVERRIDES)
            for (row in rows) {
                // Bail on a mid-pull profile switch (same rule as pullHistory).
                if (currentProfileId() != pid) return false
                val storedKey = row.overrideKey ?: continue
                if (!storedKeyMatchesProfile(storedKey, pid)) continue
                applyImageOverrideRow(row)
            }
            if (rows.isNotEmpty()) {
                Log.i(TAG, "override pull read ${rows.size} row(s) for profile=${pid ?: "legacy"}")
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ok = false
            Log.w(TAG, "pullOverrides failed: ${Redaction.text(e.message)}")
            recordSyncError("Artwork sync", e)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            ok = false
            CrashReporter.recordNonFatal(t, mapOf("source" to "pull_overrides"))
            Log.e(TAG, "pullOverrides crashed: ${Redaction.text(t.message)}")
        }
        return ok
    }

    /**
     * Seeds the CLOUD from the local override table, for the keys the account
     * does not carry yet.
     *
     * Deliberately narrow: a key the cloud already holds is NOT re-published
     * here. The local table has no per-row timestamp, so a full push would have
     * to stamp every row with `now` - and a device whose copy of a key is the
     * older choice (it set it before the other TV changed it, or it simply has
     * not pulled yet) would then overwrite the newer one with a fresh stamp.
     * Missing keys are the only rows a push can add without that risk, and they
     * are exactly what a device that chose artwork while signed out has to
     * contribute.
     */
    private suspend fun pushOverrides(context: Context): Boolean {
        val c = client ?: return false
        val pid = currentProfileId()
        val all = WatchHistoryDatabase.getInstanceScoped(context)
            .imageOverrideDao()
            .getAll()
        if (all.isEmpty()) return true
        val cloudKeys = runCatching {
            pullRows(TABLE_OVERRIDES, KEY_OVERRIDES)
                .mapNotNull { row -> row.overrideKey }
                .filter { storedKey -> storedKeyMatchesProfile(storedKey, pid) }
                .map { storedKey -> unscopedKey(storedKey) }
                .toSet()
        }.getOrElse { e ->
            Log.w(TAG, "pushOverrides could not read the cloud rows: ${Redaction.text(e.message)}")
            return false
        }
        if (!PushScopeRules.scopeStillActive(pid, currentProfileId())) return false
        val missing = all.filter { entity -> entity.key !in cloudKeys }
        if (missing.isEmpty()) return true
        val now = System.currentTimeMillis()
        missing.forEach { entity -> enqueueImageOverride(entity, pid, now) }
        flushOutbox()
        return true
    }

    private suspend fun pullPrefs(context: Context): Boolean {
        val c = client ?: return false
        // Declared before the try, not inside it: the catchers are what set it
        // to false, and a variable declared in the try body is not in their
        // scope (same rule as the three pulls above).
        var ok = true
        try {
            val rows = pullRows(TABLE_PREFS, "pref_key")

            // Pass 1: apply the account-wide profiles blob FIRST so the
            // active profile exists before scoped rows are filtered — on a
            // fresh device, every other row in this pull would otherwise be
            // dropped because no profile was active when the filter ran.
            rows.firstOrNull { it.prefKey == PrefsPayloadBuilder.KEY_PROFILES }?.let {
                PrefsPayloadApplier.apply(context, PrefsPayloadBuilder.KEY_PROFILES, it.payload)
            }
            // Pass 2: apply the active profile's scoped rows (plus unscoped
            // rows when no profiles exist — legacy/no-profiles mode).
            val pid = currentProfileId()
            for (row in rows) {
                val storedKey = row.prefKey ?: continue
                if (storedKey == PrefsPayloadBuilder.KEY_PROFILES) continue
                if (!storedKeyMatchesProfile(storedKey, pid)) continue
                // Bail on a mid-pull profile switch: the applier resolves the
                // ACTIVE profile's prefs files at apply time, so continuing
                // here would land the previous profile's blobs (watched
                // overrides included) in the new profile's stores.
                if (currentProfileId() != pid) return false
                PrefsPayloadApplier.apply(context, unscopedKey(storedKey), row.payload)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            ok = false
            Log.w(TAG, "pullPrefs failed: ${Redaction.text(e.message)}")
            recordSyncError("Settings sync", e)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            ok = false
            CrashReporter.recordNonFatal(t, mapOf("source" to "pull_prefs"))
            Log.e(TAG, "pullPrefs crashed: ${Redaction.text(t.message)}")
        }
        return ok
    }

    // ── Push (initial seed / manual sync-now) ───────────────────────

    private suspend fun pushHistory(context: Context): Boolean {
        if (client == null) return false
        // Capture the scope FIRST: the DAO read below resolves the ACTIVE
        // profile, so a profile switch between the read and this capture used
        // to stamp the OLD profile's rows with the NEW profile's cloud scope
        // — poisoned rows that synced back down as phantom watched markers.
        // Pin the profile, read, then verify it is still active before
        // publishing (see [PushScopeRules]).
        val pid = currentProfileId()
        val db = WatchHistoryDatabase.getInstanceScoped(context)
        // getAllRows(), not getAll(): the latter is the launcher/stats read and
        // filters on positionMs > 0, but a COMPLETED row is stored with
        // positionMs = 0 (PlaybackHistoryWriter's own completion shape) - so
        // every completion was invisible here. A title finished on this device
        // was never seeded on sign-in, never re-published by "Sync now", and
        // never restored by the outbox replay after a sign-out, and the next
        // pull deleted it again. Rows are what push carries; position is not
        // the filter.
        val all = db.watchHistoryDao().getAllRows()
        // A row this account already TOMBSTONED must never be re-uploaded. The
        // tombstone loses its race the moment a sibling that still holds the
        // row re-pushes it, which is how a deleted title came back; publishing
        // is therefore gated on the cloud not holding a newer delete (see
        // [HistoryTombstoneRules.shouldPublishOverTombstone]). A title played
        // AGAIN after the delete carries a newer stamp and publishes normally,
        // which is what clears the tombstone.
        val cloudTombstones = cloudHistoryTombstones()
        val toPublish =
            all.filter { entity ->
                val deletedAt = cloudTombstones[scopedKey(entity.id, pid)]
                deletedAt == null ||
                    HistoryTombstoneRules.shouldPublishOverTombstone(
                        localUpdatedAt = entity.updatedAt,
                        cloudDeletedAt = deletedAt
                    )
            }
        if (!PushScopeRules.scopeStillActive(pid, currentProfileId())) return false
        toPublish.forEach { enqueueHistory(it, pid) }
        flushOutbox()
        return true
    }

    /**
     * storedKey -> `deletedAt` for every TOMBSTONED history row in the cloud.
     * Empty when signed out or when the read fails — an empty map publishes
     * everything, the safe fallback (losing a user's real history is worse
     * than the cloud's tombstone being overwritten, and the next pull repairs
     * it). The read mirrors [cloudMarkerTimestamps].
     */
    private suspend fun cloudHistoryTombstones(): Map<String, Long> {
        val c = client ?: return emptyMap()
        return runCatching {
            pullRows(TABLE_HISTORY, "item_id")
                .mapNotNull { row ->
                    val stored = row.itemId ?: return@mapNotNull null
                    val at = HistoryTombstoneRules.deletedAt(row.payload)
                        ?: return@mapNotNull null
                    stored to at
                }
                .toMap()
        }.onFailure {
            Log.w(TAG, "pushHistory: cloud tombstone read failed (${Redaction.text(it.message)})")
        }.getOrDefault(emptyMap())
    }

    private suspend fun pushWatched(context: Context): Boolean {
        if (client == null) return false
        // Same captured-scope rule as pushHistory: pin the profile before the
        // DAO read and the (suspending) cloud-timestamp read, so a switch
        // during either cannot file these markers under the wrong profile.
        val pid = currentProfileId()
        val db = WatchHistoryDatabase.getInstanceScoped(context)
        val all = db.watchedStatusDao().getAll()
        // Only markers, never this device's derived "nothing watched here"
        // rows: every preloaded rail item gets one, and publishing them makes
        // a sibling device's correct badge lose the last-write-wins race to a
        // fresh negative (see [WatchedMarkerRules.shouldPublish]).
        //
        // And only markers NEWER than the account's copy — this device may not
        // have pulled the sibling's newer answer yet (see
        // [WatchedMarkerRules.shouldPush]).
        val cloudTimestamps = cloudMarkerTimestamps()
        val toPublish =
            all.filter { WatchedMarkerRules.shouldPublish(it.isWatched, it.isPartiallyWatched) }
                .filter { entity ->
                    WatchedMarkerRules.shouldPush(
                        localUpdatedAt = entity.updatedAt,
                        cloudUpdatedAt = cloudTimestamps[scopedKey(entity.key, pid)]
                    )
                }
        if (!PushScopeRules.scopeStillActive(pid, currentProfileId())) return false
        toPublish.forEach { enqueueWatched(it, pid) }
        flushOutbox()
        return true
    }

    /**
     * storedKey -> payload updatedAt for every watched row in the cloud.
     * Empty when signed out or when the read fails — publishing everything is
     * the safe fallback, since losing a user's marker is worse than a stale
     * overwrite that the next pull repairs.
     */
    private suspend fun cloudMarkerTimestamps(): Map<String, Long> {
        val c = client ?: return emptyMap()
        return runCatching {
            pullRows(TABLE_WATCHED, "item_key")
                .mapNotNull { row ->
                    val stored = row.itemKey ?: return@mapNotNull null
                    val updated = row.payload["updatedAt"]
                        ?.jsonPrimitive?.content?.toLongOrNull() ?: return@mapNotNull null
                    stored to updated
                }
                .toMap()
        }.onFailure {
            Log.w(TAG, "pushWatched: cloud timestamp read failed (${Redaction.text(it.message)})")
        }.getOrDefault(emptyMap())
    }

    private suspend fun pushPrefsBlobs(context: Context): Boolean {
        if (client == null) return false
        // Captured once so every blob in this pass carries the SAME scope,
        // and verified after the build: the builders read the ACTIVE profile's
        // scoped stores, so a switch between the capture and the build used to
        // file one profile's blobs — its Simkl session and MDBList key among
        // them — under the other profile's cloud scope (the "my tracker login
        // followed me onto another profile" leak). Publish only while the
        // captured profile is still active (see [PushScopeRules]).
        val pid = currentProfileId()
        val blobs = PrefsPayloadBuilder.buildAll(context)
        if (!PushScopeRules.scopeStillActive(pid, currentProfileId())) return false
        blobs.forEach { (key, payload) ->
            // Belt-and-braces guard: an empty local profiles list must never
            // reach the cloud. A fresh device pushing before its first pull
            // would otherwise erase the account's profiles for every other
            // device (the sign-in race this guards against is fixed at the
            // call site, but any future caller ordering stays safe).
            //
            // It is empty AND tombstone-free that is the dangerous shape. A
            // device whose last profile was deleted also pushes an empty list,
            // and that push is the deletion itself: refusing it would strand
            // the tombstone on the device that made it, leaving the profile on
            // every sibling (the merge only ever adds).
            if (key == PrefsPayloadBuilder.KEY_PROFILES &&
                (payload["profiles"] as? kotlinx.serialization.json.JsonArray)
                    ?.isEmpty() == true &&
                (payload["deletedProfiles"] as? kotlinx.serialization.json.JsonArray)
                    ?.isEmpty() != false
            ) {
                return@forEach
            }
            // Same rule for the Simkl session: this bulk push runs on every
            // sign-in / Sync now / full resync, and a device that has never
            // connected Simkl would otherwise publish a blank token — which
            // the applier reads as "signed out", erasing the session on every
            // other device ("Simkl sign-in didn't sync"). A deliberate
            // disconnect publishes through SimklRepository.clearAuth().
            if (key == PrefsPayloadBuilder.KEY_SIMKL_AUTH &&
                !PrefsPayloadBuilder.hasSimklAuthToPublish(context)
            ) {
                return@forEach
            }
            // And the Trakt session on exactly the same rule: a profile that
            // has never connected Trakt publishes nothing, so it cannot blank
            // the account's session; a deliberate disconnect publishes through
            // TraktRepository.revokeAndClear (see [TraktAuthRules]).
            if (key == PrefsPayloadBuilder.KEY_TRAKT_AUTH &&
                !PrefsPayloadBuilder.hasTraktAuthToPublish(context)
            ) {
                return@forEach
            }
            // Display prefs are merged per key, and a blob with no per-key
            // timestamps carries no evidence of a local edit. This bulk push
            // runs on every sign-in / Sync now, so a freshly created profile
            // used to publish its defaults with `updatedAt=now` and an empty
            // timestamp map here - and the applier, reading each missing stamp
            // as `updatedAt`, let every default win on each sibling and
            // replaced that sibling's customized display prefs with them. Publish
            // only when this device has an edit of its own newer than the
            // account copy it last adopted (see [DisplayPrefsRules]).
            if (key == PrefsPayloadBuilder.KEY_DISPLAY_PREFS &&
                !DisplayPrefsRules.shouldPublish(
                    PrefsPayloadBuilder.displayEditedAt(context),
                    PrefsPayloadBuilder.displayCloudAt(context)
                )
            ) {
                return@forEach
            }
            // Catalog naming/ordering/hiding lives in this blob. Publish it
            // only when this device's configuration is newer than the account
            // copy it last adopted, so an untouched device never re-seeds the
            // cloud with its default order and cannot clobber a configured
            // sibling — whether or not it managed to pull first (see
            // [AddonsConfigRules]).
            if (key == PrefsPayloadBuilder.KEY_ADDONS) {
                val addons =
                    com.kennyb1201.kbstream.data.addon.AddonManager.getInstance(context)
                if (!AddonsConfigRules.shouldPublish(
                        addons.addonsConfigEditedAt(),
                        addons.addonsConfigCloudAt()
                    )
                ) {
                    return@forEach
                }
            }
            // Collections and Home's browse chips are full-replace blobs, so
            // the periodic push must not publish an EMPTY list: a device that
            // never imported a collection (or mirrored a chip) would erase the
            // account's list for every other device. A deliberate last-item
            // removal publishes through the store's own save path instead
            // (see [HomeListBlobRules]).
            if (key == PrefsPayloadBuilder.KEY_COLLECTIONS) {
                val urls =
                    (payload["profile_urls"] as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull {
                            (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                        }
                        .orEmpty()
                if (!HomeListBlobRules.shouldPublishCollections(urls)) {
                    return@forEach
                }
            }
            if (key == PrefsPayloadBuilder.KEY_BROWSE_SHORTCUTS) {
                val blob =
                    (payload["shortcuts_json"] as? kotlinx.serialization.json.JsonPrimitive)
                        ?.content
                        .orEmpty()
                if (!HomeListBlobRules.shouldPublishBrowseShortcuts(blob)) {
                    return@forEach
                }
            }
            // Built catalogs are a full-replace blob as well, and the most
            // expensive state in the app to lose: a device that never opened
            // the Catalog Builder publishes nothing rather than an empty list.
            if (key == PrefsPayloadBuilder.KEY_CUSTOM_CATALOGS) {
                val blob =
                    (payload["catalogs_json"] as? kotlinx.serialization.json.JsonPrimitive)
                        ?.content
                        .orEmpty()
                if (!HomeListBlobRules.shouldPublishCustomCatalogs(blob)) {
                    return@forEach
                }
            }
            // IPTV source config: the same rule the Simkl session and the
            // full-replace Home blobs get. This bulk push runs on every
            // sign-in / Sync now, so a profile whose playlist is configured on a
            // SIBLING device used to publish its empty config here, stamped with
            // its own push time - which won last-write-wins in the cloud and
            // replaced the configured device's row on every push, and whose
            // `iptv_synced_at` on the receiving end then blocked the real config
            // (the "the guest profile's IPTV didn't sync, every other profile
            // does" report: a guest profile is exactly the one a second device
            // has nothing set up for). Only a device whose config actually
            // changed, and out-runs the cloud copy it adopted, publishes (see
            // [IptvConfigRules]).
            if (key == PrefsPayloadBuilder.KEY_IPTV &&
                !IptvConfigRules.shouldPublish(
                    PrefsPayloadBuilder.iptvEditedAt(context),
                    PrefsPayloadBuilder.iptvCloudAt(context)
                )
            ) {
                return@forEach
            }
            // Manual watched overrides are a full-replace blob too, and the one
            // whose emptiness is legal in the store: a device that never
            // hand-marked a title — or whose overrides a repair cleared — used
            // to publish its empty set here on every sign-in / Sync now,
            // stamped with the push time, and the apply on every sibling
            // replaced their hand-marked set with it about a second later,
            // silently (the delete paths publish through the store too, so it
            // is really the next pull that removes the rows). Only a device
            // with marks of its own publishes; unmarking the last one is an
            // edit and publishes through
            // [WatchedStatusRepository.applyOverrideKeys] instead (see
            // [WatchedOverridesRules]).
            if (key == PrefsPayloadBuilder.KEY_WATCHED_OVERRIDES) {
                val overrides =
                    (payload["overrides"] as? kotlinx.serialization.json.JsonArray)
                        ?.mapNotNull {
                            (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                        }
                        .orEmpty()
                // The unmarks count as content too: a device that only ever
                // unmarked still has an edit to publish, and gating on the set
                // alone would strand it (see
                // [WatchedOverridesRules.shouldPublish]).
                val unmarked =
                    (payload[WatchedOverridesRules.REMOVED_FIELD]
                        as? kotlinx.serialization.json.JsonObject)
                        ?.keys
                        .orEmpty()
                if (!WatchedOverridesRules.shouldPublish(overrides.toSet(), unmarked)) {
                    return@forEach
                }
            }
            // Same rule for the service credentials (TorBox / OpenSubtitles /
            // MDBList), which travel per key with their own edit times: publish
            // when this device has an edit newer than the account copy it
            // adopted. A device that never pasted a key, and one that has just
            // adopted the account's, both publish nothing — so no device can
            // blank the account's keys, and no adoption is echoed back (see
            // [ApiKeySyncRules]).
            if (key == PrefsPayloadBuilder.KEY_API_KEYS &&
                !ApiKeySyncRules.shouldPublish(
                    PrefsPayloadBuilder.apiKeysEditedAt(context),
                    com.kennyb1201.kbstream.data.settings.AppPreferences
                        .apiKeysCloudAt(context)
                )
            ) {
                return@forEach
            }
            // The home order is a full-replace blob too: a device that never
            // arranged a rail must not publish its empty arrangement, or one
            // untouched TV erases every sibling's rail order (the "the order
            // doesn't sync" report). A deliberate arrangement publishes through
            // KBHomeOrderPrefs.save, not through this bulk push.
            if (key == PrefsPayloadBuilder.KEY_HOME_ORDER) {
                val blob =
                    (payload["home_order_json"] as? kotlinx.serialization.json.JsonPrimitive)
                        ?.content
                        .orEmpty()
                if (!HomeListBlobRules.shouldPublishHomeOrder(blob)) {
                    return@forEach
                }
            }
            enqueuePrefs(context, key, payload, pid)
        }
        flushOutbox()
        return true
    }

    /** Manual "Sync now" from Settings. */
    fun syncNow(context: Context) {
        if (!isSignedIn()) return
        scope.launch {
            flushOutbox()
            // PULL first, then push — same awaited contract as signIn() and
            // forceFullResync(). The old "push, then pull" pair also raced
            // each other (both were fire-and-forget Jobs): this device
            // re-seeded the cloud with its own local blobs before it had
            // ingested the other device's, which is exactly how an unset
            // Simkl session overwrote a live one.
            pullAllNow(context)
            pushAllNow(context)
        }
    }

    /**
     * Pending upload rows for the diagnostics report ("table:key"). Values
     * are the scoped cloud keys, so a row that keeps failing can be matched
     * against the cloud table by hand.
     */
    fun pendingOutboxSummary(limit: Int = 10): List<String> =
        outbox.snapshot().take(limit).map { item -> "${item.table}:${item.key}" }

    /**
     * "Force full resync" from the sync-health panel: ONE awaited
     * flush → pull → push pass, so the panel reports the result of a single
     * attempt instead of three racing fire-and-forget jobs. [isSyncing] stays
     * true for the duration so the button can disable itself.
     */
    fun forceFullResync(context: Context): kotlinx.coroutines.Job = scope.launch {
        if (client == null || !isSignedIn()) return@launch
        if (_isSyncing.value) return@launch
        _isSyncing.value = true
        try {
            flushOutbox()
            pullAllNow(context)
            pushAllNow(context)
            Log.i(TAG, "force full resync complete")
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            CrashReporter.recordNonFatal(t, mapOf("source" to "force_full_resync"))
            Log.w(TAG, "force full resync failed: ${Redaction.text(t.message)}")
        } finally {
            _isSyncing.value = false
        }
    }

    /**
     * Catch-up re-pull after sign-in/restore. The initial pull races flaky
     * TV networks; a silent failure left a fresh device empty with no retry.
     * One delayed, idempotent re-run (merge is remote-vs-local updatedAt)
     * repairs that window without a visible cost in the success case.
     */
    @Volatile
    private var backgroundPullStarted = false

    private fun ensureBackgroundPull(context: Context) {
        if (backgroundPullStarted) return
        backgroundPullStarted = true
        scope.launch {
            delay(8_000L)
            if (isSignedIn()) {
                runCatchingCancellable { pullAllNow(context) }
                    .onFailure { Log.w(TAG, "catch-up pull failed: ${Redaction.text(it.message)}") }
            }
        }
    }

    // ── Realtime ────────────────────────────────────────────────────

    /**
     * One table's realtime channel plus the coroutine collecting its
     * postgres-change flow. Channels are never reused across joins: the
     * SDK's teardown() resets a dropped channel's callback manager, so a
     * repaired join needs a fresh channel object (see [subscribeTable]).
     */
    private class RealtimeSubscription(
        val table: String,
        val channel: io.github.jan.supabase.realtime.RealtimeChannel,
        val collectorJob: kotlinx.coroutines.Job
    )

    private val realtimeSubscriptions =
        java.util.concurrent.CopyOnWriteArrayList<RealtimeSubscription>()

    /*
     * Rebuild attempts per table since its last HEALTHY join.
     *
     * Realtime can be switched off server-side per table (the tables must be
     * added to the `supabase_realtime` publication). When they are not, the
     * join is rejected with a `system` error and supabase-kt leaves the
     * channel UNSUBSCRIBED forever - which this class' health watcher would
     * read as "dead channel, rebuild", re-joining every 15s and logging an
     * ERROR each time, indefinitely. The counter lets a genuinely
     * misconfigured deployment stop churning and say so once instead.
     */
    private val realtimeRebuildAttempts =
        java.util.concurrent.ConcurrentHashMap<String, Int>()

    // Serializes start/stop so two auth paths that fire near-simultaneously
    // (session restore racing a manual sign-in) can't both pass the
    // "already subscribed?" check and create DUPLICATE channels — which
    // doubled every remote-change event and leaked a websocket.
    private val realtimeMutex = Mutex()

    /**
     * The health watcher's job, so a re-join can replace it instead of leaving a
     * second watcher looping over the same table list (see [onAppForeground]).
     */
    private var realtimeHealthJob: kotlinx.coroutines.Job? = null

    /** True while at least one tracked channel is actually joined. */
    private val realtimeLive: Boolean
        get() = realtimeSubscriptions.any {
            it.channel.status.value ==
                io.github.jan.supabase.realtime.RealtimeChannel.Status.SUBSCRIBED
        }

    /**
     * Subscribes the four tables (once per session). [force] is the foreground
     * re-join (see [onAppForeground]): it first drops the tracked channels that
     * are DEAD, because a list that is non-empty but holds nothing live is
     * exactly the state the plain "already subscribed?" check would skip. A
     * channel that is merely SUBSCRIBING is left alone — tearing that down
     * mid-join would race the SDK — and a healthy join needs nothing.
     *
     * Only tables with NO tracked subscription are joined, in either mode. A
     * table that already has one — the SUBSCRIBING/UNSUBSCRIBING channel the
     * drop above leaves in place, or one a setup that threw halfway left
     * joined — must not get a second channel and collector, because nothing
     * ever cleans those up and every remote change would then be applied
     * twice to the same row.
     */
    private fun startRealtime(force: Boolean = false) {
        val c = client ?: return

        scope.launch {
            realtimeMutex.withLock {
                if (!force && realtimeSubscriptions.isNotEmpty()) return@withLock
                if (force) {
                    val dead = realtimeSubscriptions.filter {
                        it.channel.status.value ==
                            io.github.jan.supabase.realtime.RealtimeChannel.Status.UNSUBSCRIBED
                    }
                    if (dead.isNotEmpty()) {
                        Log.w(TAG, "realtime: ${dead.size} dead channel(s); re-joining")
                        dead.forEach { sub ->
                            realtimeSubscriptions.remove(sub)
                            sub.collectorJob.cancel()
                            runCatching { sub.channel.unsubscribe() }
                        }
                    }
                }
                // Read AFTER the drop: a table whose dead channel was just
                // removed has nothing tracking it again and has to re-join,
                // while a healthy or mid-join table keeps the one channel it
                // already has.
                val missing = REALTIME_TABLES.filterNot { table ->
                    realtimeSubscriptions.any { it.table == table }
                }
                if (missing.isEmpty()) return@withLock
                // A fresh sign-in starts with a clean failure budget, so a
                // deployment that has since enabled Realtime recovers. A
                // foreground re-join deliberately KEEPS the budget: a table the
                // server has rejected must not be retried forever (and logged
                // about) just because the app came back, and the health
                // watcher's backoff stays what it was.
                if (!force) realtimeRebuildAttempts.clear()
                try {
                    // One channel per table; the flow must be created BEFORE the
                    // channel subscribes (supabase-kt requirement).
                    missing.forEach { table ->
                        subscribeTable(c, table)
                    }
                    Log.i(
                        TAG,
                        "realtime subscribed to ${missing.size} new table(s); " +
                            "${realtimeSubscriptions.size} tracked"
                    )
                    _realtimeStatus.value = "live"
                    _realtimeChannelCount.value = realtimeSubscriptions.size
                    watchRealtimeHealth()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w(TAG, "realtime setup failed: ${Redaction.text(e.message)}")
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    CrashReporter.recordNonFatal(t, mapOf("source" to "realtime_setup"))
                    Log.e(TAG, "realtime setup crashed: ${Redaction.text(t.message)}")
                }
            }
        }
    }

    /**
     * Creates a fresh channel for [table], registers its postgres-change
     * flow + collector, subscribes, and tracks it.
     *
     * Fresh channels for EVERY join — including repairs. supabase-kt's
     * teardown() (called when a channel closes or errors) resets the
     * channel's callback manager; re-subscribing the same object would
     * re-join the topic but silently STOP dispatching change events to the
     * app's flows. Rebuilding the whole subscription is the only reliable
     * repair.
     */
    private suspend fun subscribeTable(c: SupabaseClient, table: String) {
        val ch = c.channel("kbstream_$table")
        val changeFlow = ch.postgresChangeFlow<io.github.jan.supabase.realtime.PostgresAction>(
            schema = "public"
        ) {
            this.table = table
        }
        val collector = scope.launch {
            changeFlow.collect { action -> onRemoteChange(action) }
        }
        try {
            ch.subscribe(blockUntilSubscribed = false)
        } catch (t: Throwable) {
            // A channel that never joined must not leak its collector.
            collector.cancel()
            throw t
        }
        // The subscribe above suspended; the user may have signed out during
        // it (watcher rebuilds run outside realtimeMutex). Track nothing for
        // a signed-out session — a zombie subscription here would make the
        // NEXT sign-in's startRealtime bail on its not-empty check.
        if (!isSignedIn()) {
            collector.cancel()
            runCatching { ch.unsubscribe() }
            return
        }
        realtimeSubscriptions.add(RealtimeSubscription(table, ch, collector))
        _realtimeChannelCount.value = realtimeSubscriptions.size
        _realtimeStatus.value = if (realtimeSubscriptions.any { it.channel.status.value ==
                io.github.jan.supabase.realtime.RealtimeChannel.Status.UNSUBSCRIBED }
        ) "reconnecting" else "live"
    }

    /**
     * THE core flakiness fix. supabase-kt does NOT re-join a channel that
     * dropped: a server kick / join error leaves it in Status.UNSUBSCRIBED
     * forever (the SDK's rejoinChannels only runs after a full WEBSOCKET
     * reconnect), so remote changes on the other device were simply never
     * delivered until this device did a manual pull (restart, Sync now,
     * profile switch). Symptoms matched the reports exactly: "stuff syncs
     * sometimes and other times it doesn't."
     *
     * This watcher detects dead channels and rebuilds those subscriptions
     * (fresh channel + collector, see [subscribeTable]); each repair is
     * followed by a one-shot pull so changes written while the channel was
     * dead are still ingested.
     */
    private fun watchRealtimeHealth() {
        realtimeHealthJob?.cancel()
        realtimeHealthJob = scope.launch {
            while (isSignedIn()) {
                // A hidden app has no UI to keep live, and the foreground hook
                // ([onAppForeground]) re-joins and catches up on return. Ticking
                // here while backgrounded only rebuilds dead channels and
                // re-opens websockets behind a process the viewer cannot see -
                // background wakeups and battery for a watcher whose only
                // consumer is the foreground UI.
                if (!appForegrounded) {
                    delay(REALTIME_BACKGROUND_WAIT_MS)
                    continue
                }
                delay(REALTIME_HEALTH_CHECK_MS)
                val c = client ?: break
                val subs = realtimeSubscriptions.toList()
                if (subs.isEmpty()) break
                var repaired = false
                for (sub in subs) {
                    if (!isSignedIn()) break

                    val attempts = realtimeRebuildAttempts.getOrDefault(sub.table, 0)
                    if (attempts >= REALTIME_MAX_REBUILD_ATTEMPTS) {
                        // Realtime is not enabled for this table server-side.
                        // Stop re-joining (and stop the per-attempt ERROR spam);
                        // remote changes still arrive via the manual/periodic pull.
                        Log.w(
                            TAG,
                            "realtime ${sub.table}: join rejected $attempts times — " +
                                "giving up; add the table to the supabase_realtime " +
                                "publication to enable live sync"
                        )
                        realtimeSubscriptions.remove(sub)
                        sub.collectorJob.cancel()
                        runCatching { sub.channel.unsubscribe() }
                        _realtimeStatus.value = "unavailable"
                        _realtimeChannelCount.value = realtimeSubscriptions.size
                        continue
                    }
                    // Only rebuild genuinely DEAD channels. UNSUBSCRIBED is
                    // the terminal state a kicked/errored channel is left in.
                    // SUBSCRIBING/UNSUBSCRIBING are transient in-flight states
                    // — tearing those down mid-join would race the SDK.
                    if (sub.channel.status.value ==
                        io.github.jan.supabase.realtime.RealtimeChannel.Status.UNSUBSCRIBED
                    ) {
                        realtimeRebuildAttempts[sub.table] = attempts + 1
                        try {
                            Log.w(TAG, "realtime channel ${sub.channel.topic} is UNSUBSCRIBED; rebuilding")
                            _realtimeStatus.value = "rebuilding ${sub.table}"
                            realtimeSubscriptions.remove(sub)
                            sub.collectorJob.cancel()
                            subscribeTable(c, sub.table)
                            _realtimeStatus.value = "live"
                            _realtimeChannelCount.value = realtimeSubscriptions.size
                            repaired = true
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            Log.w(TAG, "rebuild ${sub.table} failed: ${Redaction.text(e.message)}")
                        } catch (t: Throwable) {
                            if (t is CancellationException) throw t
                            CrashReporter.recordNonFatal(t, mapOf("source" to "realtime_resubscribe"))
                        }
                    } else {
                        // Healthy join: reset the table's failure budget.
                        realtimeRebuildAttempts.remove(sub.table)
                    }
                }
                if (repaired) {
                    appContextRef?.get()?.let { ctx ->
                        runCatching { pullAll(ctx) }
                            .onFailure { Log.w(TAG, "post-rebuild pull failed: ${Redaction.text(it.message)}") }
                    }
                }
            }
        }
    }

    private fun onRemoteChange(action: io.github.jan.supabase.realtime.PostgresAction) {
        // Remote rows arrive as JSON records; apply via the same merge rules.
        scope.launch {
            try {
                val record = when (action) {
                    is io.github.jan.supabase.realtime.PostgresAction.Update ->
                        action.record as? JsonObject ?: return@launch
                    is io.github.jan.supabase.realtime.PostgresAction.Insert ->
                        action.record as? JsonObject ?: return@launch
                    else -> return@launch
                }
                val payload = record["payload"]?.jsonObject ?: return@launch
                val table = when {
                    record["item_id"] != null -> TABLE_HISTORY
                    record["item_key"] != null -> TABLE_WATCHED
                    record[KEY_OVERRIDES] != null -> TABLE_OVERRIDES
                    else -> TABLE_PREFS
                }

                // Reuse the pull merge for a single row by decoding through the DTO.
                val row = json.decodeFromJsonElement(SyncRowDto.serializer(), record)
                when (table) {
                    TABLE_HISTORY -> applyHistoryRow(row)
                    TABLE_WATCHED -> applyWatchedRow(row)
                    TABLE_OVERRIDES -> applyImageOverrideRow(row)
                    TABLE_PREFS -> {
                        val context = appContextRef?.get() ?: return@launch
                        val storedKey = row.prefKey ?: return@launch
                        // The row names its profile in its own key, and that is
                        // the ONLY thing that picks the store here (never the
                        // active profile): a non-active profile's blob is
                        // applied to ITS store — ready for the next switch,
                        // exactly as the cold-start pull used to leave it — and
                        // can never reach the profile on screen. Dropping it
                        // (the old storedKeyApplies pin) is what made a guest
                        // profile's change wait for a restart.
                        val owner = SyncKeys.scopeOf(storedKey)
                        if (owner != null) {
                            PrefsPayloadApplier.applyForProfile(
                                context, owner, unscopedKey(storedKey), row.payload
                            )
                        } else if (storedKeyApplies(storedKey)) {
                            // Legacy unscoped row, or the account-wide profiles
                            // blob: no profile key to resolve, so the old rule
                            // stands.
                            PrefsPayloadApplier.applyForProfile(
                                context, null, unscopedKey(storedKey), row.payload
                            )
                        }
                    }
                }
                _lastSyncAtMs.value = System.currentTimeMillis()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "onRemoteChange failed: ${Redaction.text(e.message)}")
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                CrashReporter.recordNonFatal(t, mapOf("source" to "remote_change"))
                Log.e(TAG, "onRemoteChange crashed: ${Redaction.text(t.message)}")
            }
        }
    }

    /**
     * Runs [block] against the history database of the profile [storedKey]
     * NAMES: the ACTIVE profile's shared instance when that is the one, or a
     * one-shot handle on the owning profile's own file otherwise (see
     * [WatchHistoryDatabase.openForProfile] — the precedent
     * PlaybackHistoryWriter's redirect branch and the poison sweep follow).
     * [block]'s second argument says whether the profile is the active one,
     * which is what gates the live side effects (cache invalidation, the
     * override mirror).
     *
     * The target comes from the KEY, never from the active profile. That is what
     * keeps a sibling profile's realtime row out of the profile on screen, and
     * what lets it land in its own store instead of being dropped — the change
     * that used to only show up after a restart (the cold-start pull activated
     * that profile and applied it).
     *
     * A legacy unscoped row keeps the old rule: it applies only while the device
     * has no profile at all, because there is no profile store it could belong to.
     */
    private suspend fun withOwningProfileDb(
        context: Context,
        storedKey: String,
        block: suspend (WatchHistoryDatabase, Boolean) -> Unit
    ) {
        val owner = SyncKeys.scopeOf(storedKey)
        if (owner == null) {
            // The legacy unscoped row gets the SAME pin as the scoped branch
            // below, and for the same reason: the profile is read to decide
            // whether the row applies at all, and then the database is
            // resolved - two steps with a suspension and a switch possible in
            // between. Checking only before the resolve filed the row under
            // whatever profile had become active by the time the DB opened,
            // which is the defect the scoped branch's own re-check exists to
            // prevent (`owner` is derived from the key there, so that branch
            // cannot be aimed at the wrong profile - this one has to re-ask).
            val active = currentProfileId()
            if (!storedKeyMatchesProfile(storedKey, active)) return
            val db = WatchHistoryDatabase.getInstanceScoped(context)
            if (currentProfileId() != active) return
            block(db, true)
            return
        }
        val active = currentProfileId()
        if (owner == active) {
            // Pin the profile for the whole apply: filter the row against THIS
            // profile, resolve the DB, then drop the row if a switch landed in
            // between. Resolving the DB first and filtering after could file the
            // departing profile's row under the new profile's database.
            val db = WatchHistoryDatabase.getInstanceScoped(context)
            if (currentProfileId() != active) return
            block(db, true)
            return
        }
        // A row scoped to a profile this device does not have (deleted here,
        // or never pulled) must NOT be applied. openForProfile() would have Room
        // CREATE that profile's history database on the spot, so every such row
        // resurrected an orphan file that nothing enumerates or cleans up.
        if (owner !in ProfileManager.profileIds(context)) return
        // Another profile's row: its own file, opened and closed by us, so the
        // active instance's bookkeeping is left untouched (see
        // [WatchHistoryDatabase.openForProfile]).
        val oneShot = WatchHistoryDatabase.openForProfile(context, owner)
        try {
            block(oneShot, false)
        } finally {
            runCatching { oneShot.close() }
        }
    }

    private suspend fun applyHistoryRow(row: SyncRowDto) {
        val context = appContextRef?.get() ?: return
        val remote = row.payload
        val remoteUpdated = remote["updatedAt"]?.jsonPrimitive?.content?.toLongOrNull() ?: return
        val storedId = row.itemId ?: return
        withOwningProfileDb(context, storedId) { db, live ->
            val id = unscopedKey(storedId)

            val local = db.watchHistoryDao().getById(id)

            // A realtime tombstone deletes the local row, exactly like the pull
            // path. It arrives as an INSERT/UPDATE (a delete is a payload now), so
            // this is the same single-row apply a live row takes (see
            // HistoryTombstoneRules).
            val deletedAt = HistoryTombstoneRules.deletedAt(remote)
            if (deletedAt != null) {
                if (local != null &&
                    HistoryTombstoneRules.tombstoneWins(deletedAt, local.updatedAt)
                ) {
                    db.watchHistoryDao().deleteById(id)
                    if (live) WatchedStatusRepository.invalidateAllCaches()
                }
                return@withOwningProfileDb
            }

            // A delete this device has QUEUED (and not yet flushed) outranks
            // this still-live cloud copy, exactly as it does on the pull path
            // ([pullHistory]'s own guard). The live channel used to skip that
            // check: a row the viewer had just removed came back the moment a
            // sibling - or this device's own earlier copy - re-published it,
            // and the queued tombstone then only removed the cloud copy, so the
            // row survived locally for good. Applying the queued delete locally
            // instead is what keeps a removal final across both channels.
            val pendingTombstone = outbox.pending(TABLE_HISTORY, "item_id", storedId)
                ?.takeIf { HistoryTombstoneRules.isTombstone(it.payload) }
            if (pendingTombstone != null) {
                val pendingDeletedAt = HistoryTombstoneRules.deletedAt(pendingTombstone.payload)
                if (local != null &&
                    pendingDeletedAt != null &&
                    HistoryTombstoneRules.tombstoneWins(pendingDeletedAt, local.updatedAt)
                ) {
                    db.watchHistoryDao().deleteById(id)
                    if (live) WatchedStatusRepository.invalidateAllCaches()
                }
                return@withOwningProfileDb
            }

            val localUpdated = local?.updatedAt ?: 0L
            if (historyRemoteWins(
                    remoteUpdated = remoteUpdated,
                    remoteToken = HistoryTombstoneRules.mergeToken(remote),
                    localUpdated = localUpdated,
                    localToken = local?.let { HistoryTombstoneRules.mergeToken(HistoryRowRules.payload(it)) }
                )
            ) {
                db.watchHistoryDao().upsert(
                    WatchHistoryEntity(
                        id = id,
                        parentId = remote.str("parentId") ?: "",
                        type = remote.str("type") ?: "movie",
                        name = remote.str("name") ?: "",
                        episodeTitle = remote.str("episodeTitle"),
                        overview = remote.str("overview"),
                        clearLogo = remote.str("clearLogo"),
                        backdropUrl = remote.str("backdropUrl"),
                        totalEpisodesInSeason = remote.lng("totalEpisodesInSeason")?.toInt(),
                        poster = remote.str("poster"),
                        streamUrl = remote.str("streamUrl"),
                        season = remote.lng("season")?.toInt(),
                        episode = remote.lng("episode")?.toInt(),
                        episodeStreamId = remote.str("episodeStreamId"),
                        positionMs = remote.lng("positionMs") ?: 0L,
                        durationMs = remote.lng("durationMs") ?: 0L,
                        updatedAt = remoteUpdated,
                        isCompleted = remote.bool("isCompleted"),
                        completedAt = remote.lng("completedAt"),
                        markedWatched = remote.markedWatchedRow()
                    )
                )
                if (live) WatchedStatusRepository.invalidateAllCaches()
            }
        }
    }

    /**
     * Applies one remote poster/backdrop override.
     *
     * Unlike the history and watched merges this one has no local clock to
     * compare against - `image_overrides` is one row per surface, replaced in
     * place, and it carries no `updatedAt` (the local DAO and entity are
     * deliberately untouched by this feature). So the rule is the outcome the
     * other tables' last-write-wins produces, read from the other side: the
     * cloud row is the newest write unless this device has one queued for the
     * same key, and a row that differs from the local copy is applied. A row
     * that DOESN'T differ is a no-op, which is what keeps the two devices from
     * echoing each other's write back and forth forever.
     */
    private suspend fun applyImageOverrideRow(row: SyncRowDto) {
        val context = appContextRef?.get() ?: return
        val storedKey = row.overrideKey ?: return
        val key = unscopedKey(storedKey)
        val remote = row.payload
        val remoteUpdated = ImageOverrideRowRules.updatedAt(remote)

        withOwningProfileDb(context, storedKey) { db, live ->
            // A write this device has queued for the same key is the newer one when
            // its stamp is not older - the same test pullHistory applies to a
            // pending tombstone, so a slow flush cannot be walked back by a pull
            // that raced it.
            val queued = outbox.pending(TABLE_OVERRIDES, KEY_OVERRIDES, storedKey)
            if (queued != null && ImageOverrideRowRules.updatedAt(queued.payload) >= remoteUpdated) {
                return@withOwningProfileDb
            }

            val dao = db.imageOverrideDao()
            val local = dao.getAll().firstOrNull { it.key == key }

            val deletedAt = ImageOverrideRowRules.deletedAt(remote)
            if (deletedAt != null) {
                // A reset is a tombstone, not a delete: a row deleted only on the
                // device that asked for it is invisible to this one, which would
                // keep drawing the still the viewer just rejected.
                if (local != null) dao.clear(local.tmdbId, local.type, local.screen)
            } else {
                val entity = ImageOverrideRowRules.entity(key, remote) ?: return@withOwningProfileDb
                if (local?.imagePath == entity.imagePath) return@withOwningProfileDb
                dao.set(entity)
            }
            // Republish the in-memory mirror the surfaces read, so the choice
            // appears live instead of after the screen is rebuilt. The mirror is
            // the ON-SCREEN profile's; a sibling profile's row is picked up when
            // that profile is next activated.
            if (live) ImageOverrides.refresh(context)
        }
    }

    private suspend fun applyWatchedRow(row: SyncRowDto) {
        val context = appContextRef?.get() ?: return
        val remote = row.payload
        val remoteUpdated = remote["updatedAt"]?.jsonPrimitive?.content?.toLongOrNull() ?: return
        val storedKey = row.itemKey ?: return
        val key = unscopedKey(storedKey)

        withOwningProfileDb(context, storedKey) { db, live ->
            val local = db.watchedStatusDao().getByKeys(listOf(key)).firstOrNull()
            val localUpdated = local?.updatedAt ?: 0L
            // Same merge rule as the pull path — one place decides which badge
            // flags a remote row carries, so the live channel can no longer land a
            // row that erased the eye badge (see [watchedMarkerRow]).
            watchedMarkerRow(key, remote, localUpdated)?.let { merged ->
                db.watchedStatusDao().upsertAll(listOf(merged))
                if (live) WatchedStatusRepository.invalidateAllCaches()
            }
        }
    }

    private fun stopRealtime() {
        val subs = realtimeSubscriptions.toList()
        realtimeSubscriptions.clear()
        realtimeHealthJob?.cancel()
        realtimeHealthJob = null
        _realtimeStatus.value = "stopped"
        _realtimeChannelCount.value = 0
        scope.launch {
            realtimeMutex.withLock {
                subs.forEach { sub ->
                    runCatching { sub.collectorJob.cancel() }
                    runCatching { sub.channel.unsubscribe() }
                }
            }
        }
    }

    /**
     * Process-foreground hook for [onAppForeground], registered with the
     * Application at [init].
     *
     * Process-scoped on purpose, and it reuses the kids guard's transition rule
     * rather than counting started Activities again: the app has one Activity per
     * player engine as well as MainActivity, so "the app came back" is the count
     * rising off zero, not any single Activity's onStart.
     */
    private var foregroundCallbacksRegistered = false

    /**
     * True while the PROCESS is foregrounded (the started-Activity count is
     * above zero). Written by the Application's lifecycle callbacks on the main
     * thread and read by the health watcher on [scope], hence @Volatile.
     */
    @Volatile
    private var appForegrounded = false

    private fun registerForegroundCallbacks(context: Context) {
        if (foregroundCallbacksRegistered) return
        val application =
            (context.applicationContext as? android.app.Application) ?: return
        foregroundCallbacksRegistered = true
        var startedActivities = 0
        application.registerActivityLifecycleCallbacks(
            object : android.app.Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: android.app.Activity) {
                    val previous = startedActivities
                    startedActivities += 1
                    if (
                        KidsTimeGuard.foregroundTransition(previous, startedActivities) ==
                        KidsTimeGuard.ForegroundTransition.FOREGROUNDED
                    ) {
                        appForegrounded = true
                        onAppForeground(activity.applicationContext)
                    }
                }

                override fun onActivityStopped(activity: android.app.Activity) {
                    val previous = startedActivities
                    startedActivities = (previous - 1).coerceAtLeast(0)
                    if (
                        KidsTimeGuard.foregroundTransition(previous, startedActivities) ==
                        KidsTimeGuard.ForegroundTransition.BACKGROUNDED
                    ) {
                        appForegrounded = false
                    }
                }

                override fun onActivityCreated(
                    activity: android.app.Activity,
                    savedInstanceState: android.os.Bundle?
                ) = Unit

                override fun onActivityResumed(activity: android.app.Activity) = Unit

                override fun onActivityPaused(activity: android.app.Activity) = Unit

                override fun onActivitySaveInstanceState(
                    activity: android.app.Activity,
                    outState: android.os.Bundle
                ) = Unit

                override fun onActivityDestroyed(activity: android.app.Activity) = Unit
            }
        )
    }

    /**
     * Liveness hardening for the foreground: the app can come back from hours in
     * the background with realtime quietly dead — supabase-kt never re-joins a
     * dropped channel on its own (see [watchRealtimeHealth]) — which is what
     * "it only syncs after a restart" looks like from the sofa.
     *
     * So on every foreground, while signed in:
     *
     *  1. the channels are re-joined when none of them is live ([startRealtime]
     *     with `force`), and
     *  2. one pull follows it — a FULL catch-up pull when the channels had all
     *     died, the PREFS table alone when they were healthy.
     *
     * The full pull is the part that closes the missed window. A re-join only
     * streams FUTURE changes: rows a sibling wrote while all four channels were
     * dead were never delivered here, and the health watcher's compensating pull
     * does not cover this case — it repairs a channel it finds dead at a tick,
     * and this re-join is exactly what leaves them looking healthy. That made
     * cross-device resume/watched changes made during the away window silently
     * never land until a restart or a manual Sync. It costs one full pull per
     * foreground-with-dead-channels, which is a repair path, not a per-foreground
     * one: a healthy foreground still pays only for the cheap prefs table (the
     * display settings a viewer has just changed on the other TV).
     *
     * Foreground-gated only: deliberately no new periodic timer, and the health
     * watcher's own backoff budget is untouched.
     */
    fun onAppForeground(context: Context) {
        if (!isSignedIn()) return
        // Read BEFORE the re-join: `realtimeLive` is what decides whether the
        // forced call below rebuilds anything, and it flips asynchronously.
        val missedWindow = !realtimeLive
        if (missedWindow) startRealtime(force = true)
        scope.launch {
            runCatchingCancellable {
                if (missedWindow) pullAllNow(context) else pullPrefs(context)
            }.onFailure {
                Log.w(TAG, "foreground pull failed: ${Redaction.text(it.message)}")
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────

    @Volatile
    internal var appContextRef: java.lang.ref.WeakReference<Context>? = null

    fun isSignedIn(): Boolean = _authState.value is AuthState.SignedIn

    /**
     * Remembers the account a later SIGNED-OUT delete belongs to (see
     * [stageSignedOutDelete]). Pass [email] when the account is known at the
     * call site; otherwise the current auth state - then the persisted session
     * email - is used, which is what [signOut] needs before it clears the
     * session store.
     */
    private fun rememberDeferredDeleteAccount(context: Context, email: String? = null) {
        val known = email
            ?: (_authState.value as? AuthState.SignedIn)?.email
            ?: syncPrefs(context).getString(KEY_EMAIL, null)
        // Record the Supabase USER ID alongside the email: the user id is the
        // stable identity, because it survives an email change, while the email
        // is what an install that has never had a session id knows (SD-5). Both
        // are stored, and matching accepts either, so a delete staged under the
        // old form still replays.
        SyncDeferredDeletes.rememberAccount(context, currentUserId(), known)
    }

    /**
     * The signed-in Supabase user id, or null when this process has no session
     * (or the session restore has not delivered one yet).
     */
    private fun currentUserId(): String? =
        runCatching { client?.auth?.currentUserOrNull()?.id }.getOrNull()

    /**
     * Stages a signed-out delete for replay on the account's next sign-in. A
     * no-op when no account has ever signed in on this device: there is nothing
     * to attribute the delete to, and no cloud copy it could be wrong about.
     */
    /**
     * Stages a signed-out RESET of poster/backdrop overrides, the override twin
     * of [stageSignedOutDelete]: the account's cloud rows still exist, so
     * dropping the reset would let the next same-account sign-in pull the still
     * straight back.
     */
    private fun stageSignedOutOverrideClear(keys: List<String>, profileId: String?) {
        val context = appContextRef?.get() ?: return
        val accountId = SyncDeferredDeletes.lastAccountId(context) ?: return
        val now = System.currentTimeMillis()
        keys.forEach { key ->
            SyncDeferredDeletes.stage(
                context,
                SyncDeferredDeletes.Staged(
                    accountId = accountId,
                    table = TABLE_OVERRIDES,
                    keyColumn = KEY_OVERRIDES,
                    key = scopedKey(key, profileId),
                    payloadJson = ImageOverrideRowRules.tombstone(key, now).toString(),
                    enqueuedAtMs = now
                )
            )
        }
        Log.i(TAG, "staged ${keys.size} signed-out artwork reset(s) for $accountId")
    }

    private fun stageSignedOutDelete(ids: List<String>, profileId: String?) {
        val context = appContextRef?.get() ?: return
        val accountId = SyncDeferredDeletes.lastAccountId(context) ?: return
        val cleanIds =
            ids.mapNotNull { id -> id.trim().takeIf { it.isNotBlank() } }.distinct()
        if (cleanIds.isEmpty()) return
        val now = System.currentTimeMillis()
        cleanIds.forEach { id ->
            SyncDeferredDeletes.stage(
                context,
                SyncDeferredDeletes.Staged(
                    accountId = accountId,
                    table = TABLE_HISTORY,
                    keyColumn = "item_id",
                    key = scopedKey(id, profileId),
                    payloadJson = HistoryTombstoneRules.tombstone(id, now).toString(),
                    enqueuedAtMs = now
                )
            )
        }
        Log.i(TAG, "staged ${cleanIds.size} signed-out delete(s) for $accountId")
    }

    /**
     * Stages the Settings "Clear Continue Watching" wipe made while SIGNED OUT.
     *
     * The signed-in path enumerates the account's CLOUD rows and tombstones
     * each. Signed out there is no session to enumerate with, but the local
     * rows this reset is about to clear are the device's own record of what the
     * account holds: every local history row becomes a history tombstone, and
     * every local watched row that actually carries a marker
     *
     * Known limitation (SD-4): with no session there is nothing to enumerate
     * the cloud with, so only THIS device's rows can be tombstoned. Rows another
     * device wrote survive the wipe and come back on the next pull - a "clear"
     * that is device-scoped rather than account-scoped - and the tombstones
     * carry stage-time stamps, so a row written elsewhere between staging and
     * replay wins last-write-wins. A truly account-wide wipe needs a
     * server-side clear; that is not something a signed-out client can do
     * safely, and widening this device's authority over rows it cannot see
     * would be worse than the limitation. (see
     * [WatchedMarkerRules.shouldPublish]) becomes a cleared marker - the
     * derived "nothing watched here" negatives are skipped, since they were
     * never published and clearing them would only push noise to the account.
     * All of it is filed under the last account and replayed on that account's
     * next sign-in.
     */
    private suspend fun stageSignedOutClear(profileId: String?) {
        val context = appContextRef?.get() ?: return
        val accountId = SyncDeferredDeletes.lastAccountId(context) ?: return
        val now = System.currentTimeMillis()
        val db = WatchHistoryDatabase.getInstanceScoped(context)

        // getAllRows(): a completed row has positionMs = 0, so getAll() would
        // stage tombstones for resume rows only and leave every completed
        // cloud row to come back on the next sign-in - the clear would not be
        // a clear.
        val history = runCatchingCancellable { db.watchHistoryDao().getAllRows() }
            .getOrNull().orEmpty()
        history.forEach { row ->
            SyncDeferredDeletes.stage(
                context,
                SyncDeferredDeletes.Staged(
                    accountId = accountId,
                    table = TABLE_HISTORY,
                    keyColumn = "item_id",
                    key = scopedKey(row.id, profileId),
                    payloadJson = HistoryTombstoneRules.tombstone(row.id, now).toString(),
                    enqueuedAtMs = now
                )
            )
        }

        val watched = runCatchingCancellable { db.watchedStatusDao().getAll() }
            .getOrNull().orEmpty()
        val markers = watched
            .filter { WatchedMarkerRules.shouldPublish(it.isWatched, it.isPartiallyWatched) }
        markers.forEach { row ->
            SyncDeferredDeletes.stage(
                context,
                SyncDeferredDeletes.Staged(
                    accountId = accountId,
                    table = TABLE_WATCHED,
                    keyColumn = "item_key",
                    key = scopedKey(row.key, profileId),
                    payloadJson = WatchedMarkerRules.payload(
                        key = row.key,
                        imdbId = row.imdbId,
                        mediaType = row.mediaType,
                        isWatched = false,
                        isPartiallyWatched = false,
                        updatedAt = now
                    ).toString(),
                    enqueuedAtMs = now
                )
            )
        }
        Log.i(
            TAG,
            "staged signed-out clear for $accountId " +
                "(${history.size} history, ${markers.size} watched)"
        )
    }

    /**
     * Replays the deletes staged for [accountId] now that it has signed back
     * in: each becomes an ordinary tombstone in the outbox, so it flushes like
     * any other delete. Called BEFORE the pull so the tombstone is queued
     * first; only this account's staged deletes move, the rest stay for their
     * own sign-in.
     */
    private suspend fun replayDeferredDeletes(context: Context, accountId: String?) {
        // Match against BOTH identities the signing-in account is known by: the
        // Supabase user id (what new stages carry, and what survives an email
        // change) and the email (what stages written by an earlier build carry
        // - SD-5). Passing only one left the other form's deletes stranded.
        val uid = currentUserId()
        val normalizedEmail = DeferredDeleteRules.normalizeAccount(accountId)
        if (uid == null && normalizedEmail == null) return
        val staged = SyncDeferredDeletes.stagedFor(context, uid, normalizedEmail)
        if (staged.isEmpty()) return
        staged.forEach { item ->
            // The signed-in delete path may drop a queued upload of this key
            // because the row is going away in the same breath. A REPLAY cannot
            // borrow that: the tombstone was stamped when the delete was made
            // (stage time), and anything the viewer did to the same key since -
            // watching the title again, marking it after the wipe - is queued
            // behind it with a LATER stamp. Dropping that would lose the only
            // copy of the newer write, which is the one thing the outbox exists
            // to prevent.
            //
            // Last-write-wins, as everywhere else in the sync: the newer queued
            // write stands and the stale tombstone is dropped with it (the
            // staged rows are all cleared below). A stage time of 0 is treated
            // as older than anything queued - resurrecting a deleted row is
            // recoverable, losing the viewer's progress is not.
            val queued = outbox.pending(item.table, item.keyColumn, item.key)
            if (queued != null && queued.enqueuedAtMs > item.enqueuedAtMs) {
                Log.i(
                    TAG,
                    "stale staged delete for ${item.key} dropped: " +
                        "a newer write (${queued.enqueuedAtMs}) is queued"
                )
                return@forEach
            }
            // Otherwise: drop any queued upload of this key first, then enqueue
            // the tombstone.
            outbox.removeKeys(item.table, item.keyColumn, listOf(item.key))
            val payload = runCatching {
                Json.parseToJsonElement(item.payloadJson).jsonObject
            }.getOrNull() ?: return@forEach
            outbox.put(
                OutboxItem(
                    table = item.table,
                    keyColumn = item.keyColumn,
                    key = item.key,
                    payload = payload,
                    enqueuedAtMs = item.enqueuedAtMs.takeIf { it > 0L }
                        ?: System.currentTimeMillis()
                )
            )
        }
        SyncDeferredDeletes.remove(context, staged.map { it.id })
        scheduleFlush()
        Log.i(
            TAG,
            "replayed ${staged.size} signed-out delete(s) for " +
                (uid ?: normalizedEmail ?: "an unknown account")
        )
    }

    private fun JsonObject.str(key: String): String? =
        this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

    private fun JsonObject.lng(key: String): Long? =
        str(key)?.toLongOrNull()

    private fun JsonObject.bool(key: String): Boolean =
        str(key)?.toBooleanStrictOrNull() ?: false

    /**
     * Whether a history row is a "marked watched" marker.
     *
     * The explicit flag when the payload carries one (every build from v14 on),
     * otherwise the sentinel shape - a payload pushed by an OLDER build, which
     * has no flag to send. The fallback is the same classification the 13 -> 14
     * migration applies to rows already on disk.
     */
    private fun JsonObject.markedWatchedRow(): Boolean {
        str("markedWatched")?.toBooleanStrictOrNull()?.let { return it }
        return looksLikeMarkedWatchedRow(
            isCompleted = bool("isCompleted"),
            durationMs = lng("durationMs") ?: 0L,
            positionMs = lng("positionMs") ?: 0L
        )
    }

    private const val SYNC_PREFS = "kbstream_sync"
    private const val KEY_REFRESH_TOKEN = "supabase_refresh_token"
    private const val KEY_EMAIL = "supabase_email"

    /**
     * The Supabase session store: the refresh token is a bearer credential,
     * so it lives behind [SecureTokenStore] (encrypted at rest) instead of in
     * a plain prefs file. The email is kept in the same store — it is not a
     * secret, but splitting one logical store across two files would only
     * make the clear-on-sign-out path easy to get wrong.
     */
    private fun syncPrefs(context: Context) =
        com.kennyb1201.kbstream.data.security.SecureTokenStore.prefs(
            context,
            SYNC_PREFS,
            legacyPlaintext = true
        )

    // Outbox retry cadence. One minute: short enough that an offline burst
    // lands promptly after reconnect, rare enough to be invisible.
    private const val OUTBOX_RETRY_MS = 60_000L

    // How often the realtime health watcher verifies the channels are still
    // joined and resubscribes the dead ones. 15s: a dropped channel is
    // repaired well inside the "user noticed nothing synced" window.
    private const val REALTIME_HEALTH_CHECK_MS = 15_000L

    // How long the watcher parks itself while the app is backgrounded. Nothing
    // in the background consumes the channels, and the foreground hook repairs
    // them on return, so this is only the granularity at which the loop notices
    // it may resume - not a repair interval.
    private const val REALTIME_BACKGROUND_WAIT_MS = 60_000L

    /// Rebuild attempts per table before realtime is treated as unavailable
    /// for it (see [realtimeRebuildAttempts]).
    private const val REALTIME_MAX_REBUILD_ATTEMPTS = 3
}
