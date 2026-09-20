package com.kennyb1201.kbstream.data.sync

import android.content.Context
import android.util.Log
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.gotrue.Auth
import io.github.jan.supabase.gotrue.SessionStatus
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.kennyb1201.kbstream.BuildConfig
import com.kennyb1201.kbstream.data.addon.AddonManager
import com.kennyb1201.kbstream.data.reporting.CrashReporter
import com.kennyb1201.kbstream.data.cache.WatchedStatusEntity
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.history.WatchHistoryEntity
import com.kennyb1201.kbstream.data.simkl.SimklRepository
import com.kennyb1201.kbstream.data.watched.WatchedStatusRepository

/**
 * Cross-device sync over Supabase.
 *
 * What syncs (per user request):
 *  - watch history / resume positions / completion   → sync_watch_history
 *  - watched markers (overrides + cache)             → sync_watched_status
 *  - display prefs, addons, Simkl token, IPTV config → sync_prefs (keyed JSON blobs)
 *    (playback/decoder settings deliberately EXCLUDED — those are per-device)
 *
 * How:
 *  - Local writes enqueue into an in-memory outbox and flush immediately
 *    (also retried on reconnect/interval) — offline-safe.
 *  - A realtime subscription on all three tables applies remote changes
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
        val firstLine = (e.message ?: e.javaClass.simpleName)
            .lineSequence().firstOrNull()?.trim().orEmpty()
            .ifBlank { e.javaClass.simpleName }
        _syncError.value = "$where failed: $firstLine"
    }

    private fun clearSyncError() {
        _syncError.value = null
    }

    // ── Client ──────────────────────────────────────────────────────

    private var client: SupabaseClient? = null

    // Last-resort net under every launch {} in this object: the per-path
    // try/catch blocks only catch Exception, but an Error/Throwable escaping
    // a launch on an unhandled scope CRASHES THE PROCESS (black screen →
    // launcher on TV). That is exactly what happened on signed-in devices:
    // the restoreSession/pull path is the only startup work signed-out
    // devices never run. This handler converts any such escape into a log +
    // Sentry capture instead of a dead app. Behavior is unchanged when
    // everything works.
    private val uncaughtHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "sync task failed hard", throwable)
        com.kennyb1201.kbstream.data.reporting.CrashReporter.recordNonFatal(
            throwable, mapOf("source" to "supabase_sync_scope")
        )
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + uncaughtHandler)

    private const val TABLE_HISTORY = "sync_watch_history"
    private const val TABLE_WATCHED = "sync_watched_status"
    private const val TABLE_PREFS = "sync_prefs"

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
        if (client != null) return

        client = createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_ANON_KEY
        ) {
            install(Auth)
            install(Postgrest)
            install(Realtime)
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
                    runCatching { persistSessionFromClient(ctx) }
                        .onFailure { Log.w(TAG, "session persist failed", it) }
                    // The cloud half of the one-time cross-profile poison
                    // sweep needs a session; the ProfileManager.init call
                    // usually runs before auth restores, so retry here on
                    // every Authenticated event (flag-guarded → once).
                    runCatching { runOneTimePoisonSweep(ctx) }
                        .onFailure { Log.w(TAG, "poison sweep trigger failed", it) }
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
                val prefs = context.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)
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
                        startRealtime()
                        startPeriodicFlush()
                        ensureBackgroundPull(context)
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
                                        "retrying in ${backoffMs / 1000}s): ${e.message}"
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
                            Log.w(TAG, "session restore failed: ${e.message}")
                            context.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)
                                .edit().remove(KEY_REFRESH_TOKEN).apply()
                            _authState.value = AuthState.SignedOut
                        }
                    }
                } else {
                    _authState.value = AuthState.SignedOut
                }
            } catch (e: Exception) {
                // Any unexpected error path: keep the saved token — the
                // next launch can still restore from it.
                Log.e(TAG, "restoreSession failed (token kept)", e)
                _authState.value = AuthState.SignedOut
            } catch (t: Throwable) {
                // Errors (LinkageError etc., e.g. an R8/codec mismatch on a
                // release build) are NOT Exceptions — letting one escape this
                // launch kills the process at every cold start on signed-in
                // devices. Swallow to the crash reporter and mark signed out.
                Log.e(TAG, "restoreSession crashed (token kept)", t)
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
                c.auth.signInWith(io.github.jan.supabase.gotrue.providers.builtin.Email) {
                    this.email = email.trim()
                    this.password = password
                }
                persistSession(context, email.trim())
                _authState.value = AuthState.SignedIn(email.trim())
                _syncEnabled.value = true
                startRealtime()
                startPeriodicFlush()
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
                Log.e(TAG, "signIn failed", e)
                _authState.value = AuthState.Error(e.message ?: "Sign-in failed")
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
                c.auth.signUpWith(io.github.jan.supabase.gotrue.providers.builtin.Email) {
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
                startRealtime()
                startPeriodicFlush()
                // Fresh account: push local state up as the initial seed.
                pushAll(context)
            } catch (e: Exception) {
                Log.e(TAG, "signUp failed", e)
                _authState.value = AuthState.Error(e.message ?: "Sign-up failed")
            }
        }
    }

    fun signOut(context: Context) {
        val c = client ?: return
        periodicFlushJob?.cancel()
        periodicFlushJob = null
        // Reset the one-shot guards so a subsequent sign-in on the SAME
        // process actually starts the loops again (previously a sign-out →
        // sign-in cycle left the app silently without realtime/flush loops —
        // reported changes only synced when the user manually hit Sync now).
        backgroundPullStarted = false
        scope.launch {
            runCatching { c.auth.signOut() }
            context.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)
                .edit().clear().apply()
            stopRealtime()
            _authState.value = AuthState.SignedOut
            _syncEnabled.value = false
        }
    }

    private suspend fun persistSession(context: Context, email: String) {
        val c = client ?: return
        val refresh = c.auth.currentSessionOrNull()?.refreshToken
        context.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)
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
        val prefs = context.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)
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
        runCatching {
            kotlinx.coroutines.runBlocking { persistSessionFromClient(context) }
        }
    }

    // ── Outbox (offline-safe local-write flush) ─────────────────────

    // [OutboxItem] / [OutboxQueue] hold the coalescing contract (and are
    // unit tested). Every mutation reports the pending count to the Settings
    // sync-health panel.
    private val outbox = OutboxQueue { pending -> _pendingOutbox.value = pending }

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
     */
    private fun currentProfileId(): String? =
        com.kennyb1201.kbstream.data.sync.ProfileManager.activeProfile.value?.id

    // Pure key rules live in [SyncKeys] so they are unit tested directly.
    private fun scopedKey(originalKey: String, pid: String? = currentProfileId()): String =
        SyncKeys.scoped(originalKey, pid)

    private fun unscopedKey(storedKey: String): String = SyncKeys.unscoped(storedKey)

    private fun storedKeyMatchesProfile(storedKey: String, pid: String?): Boolean =
        SyncKeys.matchesProfile(storedKey, pid)

    private fun storedKeyMatchesActiveProfile(storedKey: String): Boolean =
        storedKeyMatchesProfile(storedKey, currentProfileId())

    /** Account-wide keys (the profiles list itself) bypass the profile filter. */
    private fun storedKeyApplies(storedKey: String): Boolean {
        if (storedKey == PrefsPayloadBuilder.KEY_PROFILES) return true
        return storedKeyMatchesActiveProfile(storedKey)
    }

    // ── One-time cross-profile poison sweep ─────────────────────────
    //
    // The profile-switch races fixed above let bulk operations that
    // STARTED under profile A land under profile B when the user
    // switched profiles mid-flight. The races are gone, but whatever
    // ALREADY leaked stays behind:
    //
    //   1. watched_status_cache rows inside the WRONG profile's scoped
    //      Room DB — the phantom watched markers (with clean Simkl/
    //      MDBList dashboards, because the data never came from the
    //      trackers),
    //   2. Simkl per-profile disk blobs cached under the wrong
    //      "<profileId>/simkl:*" key — poisoning badges for up to 12h,
    //   3. sync_watched_status rows in the CLOUD stamped under the
    //      wrong profile scope. These re-download on every pull and
    //      would resurrect the phantom markers even after 1 and 2 were
    //      wiped,
    //   4. sync_watch_history rows likewise duplicated into the other
    //      profile's scope — the phantom Continue Watching cards (plus
    //      their local copies, which would be re-pushed otherwise),
    //   5. watched-OVERRIDE sets: overrides sync as a full-replace blob,
    //      so a mid-pull switch copied one profile's entire set onto
    //      another — phantom manual marks that no tracker explains.
    //
    // The sweep runs once per install (flags live in kbstream_sync_meta, a
    // prefs file that never syncs). Rows are only ever deleted when they are
    // provably the same local write under two profile scopes (identical
    // payload or identical payload timestamp) — see [PoisonDetector].
    // Nothing else is touched.

    private const val SWEEP_PREFS = "kbstream_sync_meta"
    // "v2" flags: the sweep was extended (history rows + watched-override
    // blobs), so a device that ran the narrower first version still gets the
    // wider one.
    private const val SWEEP_FLAG_LOCAL = "poison_sweep_v2_local_done"
    private const val SWEEP_FLAG_CLOUD = "poison_sweep_v2_cloud_done"
    // Duplicated override sets found locally, kept until their cloud blobs
    // are deleted too — clearing the local set erases the evidence needed to
    // find them.
    private const val SWEEP_PENDING_OVERRIDE_CLEARS = "poison_sweep_pending_overrides"
    private const val SWEEP_OVERRIDES_PREFS_BASE = "kbstream_watched_overrides"
    private const val SWEEP_OVERRIDES_KEY = "watched_overrides"
    private const val SWEEP_DB_BASE = "kbstream_watch_history"

    private val sweepMutex = Mutex()

    /**
     * Fire-and-forget entry point; safe to call from anywhere (startup,
     * auth events). Concurrent callers are serialized by [sweepMutex]
     * and the flags make each part idempotent.
     */
    fun runOneTimePoisonSweep(context: Context) {
        val appCtx = context.applicationContext
        scope.launch {
            sweepMutex.withLock {
                runCatching { runPoisonSweepInternal(appCtx) }
                    .onFailure { Log.w(TAG, "poison sweep failed: ${it.message}") }
            }
        }
    }

    /**
     * One-line cleanup status for the Settings → Sync panel, so the user can
     * tell whether the cross-profile cleanup has run on this device.
     */
    fun poisonSweepStatus(context: Context): String {
        val flags = context.getSharedPreferences(SWEEP_PREFS, Context.MODE_PRIVATE)
        val local = flags.getBoolean(SWEEP_FLAG_LOCAL, false)
        val cloud = flags.getBoolean(SWEEP_FLAG_CLOUD, false)
        return when {
            local && cloud -> "completed"
            local -> "local done, cloud pending (sign in)"
            else -> "not run yet"
        }
    }

    private suspend fun runPoisonSweepInternal(context: Context) {
        val flags = context.getSharedPreferences(SWEEP_PREFS, Context.MODE_PRIVATE)

        val profiles = ProfileManager.profiles.value.sortedBy { it.createdAt }
        val orderedPids = profiles.map { it.id }
        val localPids = orderedPids.toSet()

        // ── Part 1: local derived caches (no network needed) ─────────
        if (!flags.getBoolean(SWEEP_FLAG_LOCAL, false)) {
            val activePid = ProfileManager.activeProfile.value?.id
            // Wipe the watched-status CACHE for every profile namespace.
            // It is fully derived (Simkl/MDBList/history/overrides/cloud
            // resolve it back within seconds of first use), so clearing is
            // lossless — and it is exactly where the phantom markers live.
            for (profile in profiles) {
                if (profile.id == activePid) {
                    // Active profile's DB may be open by Room — go through
                    // the DAO instead of raw SQL.
                    runCatching {
                        WatchHistoryDatabase.getInstanceScoped(context)
                            .watchedStatusDao().clearAll()
                    }.onFailure {
                        Log.w(TAG, "poison sweep: active watched cache clear failed: ${it.message}")
                    }
                } else {
                    rawClearWatchedCacheTable(context, ProfileStorage.dbName(profile.id, SWEEP_DB_BASE))
                }
            }
            // Legacy (pre-profiles) unscoped DB — same cache table.
            runCatching {
                WatchHistoryDatabase.getInstance(context)
                    .watchedStatusDao().clearAll()
            }
            // Simkl per-profile disk blobs in the SHARED cache table. A
            // mid-flight switch wrote profile A's lists under profile B's
            // key; deleting them forces a clean refetch from each
            // profile's own Simkl account.
            runCatching {
                val keys = buildList {
                    for (profile in profiles) {
                        add("${profile.id}/simkl:all_show_items")
                        add("${profile.id}/simkl:continue_watching")
                        add("${profile.id}/simkl:completed_movies")
                    }
                    // Legacy bare keys (pre-profiles layout).
                    add("simkl:all_show_items")
                    add("simkl:continue_watching")
                    add("simkl:completed_movies")
                }
                WatchHistoryDatabase.getInstance(context).tmdbJsonCacheDao().deleteByKeys(keys)
            }.onFailure {
                Log.w(TAG, "poison sweep: simkl blob clear failed: ${it.message}")
            }
            // Watched-override sets that are an exact copy of an OLDER
            // profile's set: the full-replace blob applier copied one
            // profile's whole set onto another mid-race. Recorded in prefs so
            // the cloud half (which needs a session) can delete the matching
            // blobs even if it runs in a later attempt.
            runCatching {
                val duplicates = PoisonDetector.duplicateOverrideOwners(
                    orderedPids.map { pid -> pid to localOverrideKeys(context, pid) }
                )
                for (pid in duplicates) {
                    context.getSharedPreferences(
                        ProfileStorage.prefsName(pid, SWEEP_OVERRIDES_PREFS_BASE),
                        Context.MODE_PRIVATE
                    ).edit().putStringSet(SWEEP_OVERRIDES_KEY, emptySet()).apply()
                }
                if (duplicates.isNotEmpty()) {
                    val pending = flags.getStringSet(SWEEP_PENDING_OVERRIDE_CLEARS, emptySet()).orEmpty()
                    flags.edit()
                        .putStringSet(SWEEP_PENDING_OVERRIDE_CLEARS, pending + duplicates)
                        .apply()
                    Log.i(TAG, "poison sweep: cleared ${duplicates.size} duplicated watched-override set(s)")
                }
            }.onFailure {
                Log.w(TAG, "poison sweep: override duplicate check failed: ${it.message}")
            }
            // Drop the matching in-memory caches so nothing stale is
            // served from RAM after the disk wipe.
            WatchedStatusRepository.invalidateAllCaches()
            SimklRepository.clearTransientCaches()
            flags.edit().putBoolean(SWEEP_FLAG_LOCAL, true).apply()
            Log.i(TAG, "poison sweep: local watched caches cleared for ${profiles.size} profile(s)")
        }

        // ── Part 2: cloud rows stamped under the wrong profile ───────
        // Needs a signed-in session; skipped (flag NOT set) when signed
        // out so the Authenticated hook retries after sign-in.
        if (!flags.getBoolean(SWEEP_FLAG_CLOUD, false)) {
            val c = client ?: return
            if (authState.value !is AuthState.SignedIn) return
            // Cross-profile poison requires ≥2 profiles; with fewer there
            // is nothing to attribute, so mark done.
            if (profiles.size < 2) {
                flags.edit().putBoolean(SWEEP_FLAG_CLOUD, true).apply()
                return
            }

            var deleted = 0

            // (1) watched rows, (2) history rows — same fingerprint in both
            // tables: one local write present under two profile scopes.
            for (table in listOf(TABLE_WATCHED, TABLE_HISTORY)) {
                val rows = readCloudRows(c, table) ?: return
                val poison = PoisonDetector.crossScopeDuplicates(rows, orderedPids, localPids)
                if (poison.isEmpty()) continue
                deleted += deleteCloudKeys(c, table, poison)
                Log.i(TAG, "poison sweep: $table — deleting ${poison.size} duplicated row(s)")
                if (table == TABLE_HISTORY) {
                    // Delete the LOCAL copies too, or the next push re-creates
                    // them from this device (they are what the card renders).
                    for ((pid, keys) in poison.groupBy { SyncKeys.scopeOf(it) ?: "" }) {
                        if (pid.isEmpty()) continue
                        deleteLocalHistoryRows(context, pid, keys.map { unscopedKey(it) })
                    }
                }
            }

            // (3) watched-override blobs copied onto a newer profile. Read
            // the pids recorded by part 1 (the local clear erased them from
            // the prefs) plus anything detectable now.
            val overridePids = (flags.getStringSet(SWEEP_PENDING_OVERRIDE_CLEARS, emptySet()).orEmpty() +
                PoisonDetector.duplicateOverrideOwners(
                    orderedPids.map { pid -> pid to localOverrideKeys(context, pid) }
                )).toList()
            if (overridePids.isNotEmpty()) {
                val blobKeys = overridePids.map {
                    scopedKey(PrefsPayloadBuilder.KEY_WATCHED_OVERRIDES, it)
                }
                deleted += deleteCloudKeys(c, TABLE_PREFS, blobKeys)
                Log.i(TAG, "poison sweep: deleting ${blobKeys.size} duplicated override blob(s)")
            }

            flags.edit()
                .putBoolean(SWEEP_FLAG_CLOUD, true)
                .remove(SWEEP_PENDING_OVERRIDE_CLEARS)
                .apply()
            Log.i(TAG, "poison sweep: deleted $deleted cross-profile cloud row(s)")
            // Converge local state with the now-clean cloud tables.
            WatchedStatusRepository.invalidateAllCaches()
            runCatching { pullAllNow(context) }
                .onFailure { Log.w(TAG, "poison sweep: post-clean pull failed: ${it.message}") }
        }
    }

    /**
     * Raw-SQL wipe of watched_status_cache in a profile DB that Room has
     * NOT opened (only the active profile's scoped DB is open at any
     * time; the caller routes that one through the DAO instead).
     */
    private fun rawClearWatchedCacheTable(context: Context, dbName: String) {
        runCatching {
            val file = context.getDatabasePath(dbName)
            if (!file.exists()) return
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
            )
            try {
                db.execSQL("DELETE FROM watched_status_cache")
            } finally {
                db.close()
            }
        }.onFailure {
            Log.w(TAG, "poison sweep: raw clear of $dbName failed: ${it.message}")
        }
    }

    /** This profile's local watched-override keys (scoped prefs file). */
    private fun localOverrideKeys(context: Context, pid: String): Set<String> =
        runCatching {
            context.getSharedPreferences(
                ProfileStorage.prefsName(pid, SWEEP_OVERRIDES_PREFS_BASE),
                Context.MODE_PRIVATE
            ).getStringSet(SWEEP_OVERRIDES_KEY, emptySet()).orEmpty()
        }.getOrDefault(emptySet())

    /**
     * Reads a whole cloud table for this account as poison-detector rows.
     * Returns null when the read failed (caller leaves the flag unset so the
     * next sign-in retries the sweep).
     */
    private suspend fun readCloudRows(
        c: SupabaseClient,
        table: String
    ): List<PoisonDetector.Row>? =
        runCatching {
            c.from(table).select().decodeList<SyncRowDto>().mapNotNull { row ->
                val stored = row.itemKey ?: row.itemId ?: row.prefKey ?: return@mapNotNull null
                PoisonDetector.Row(stored, row.payload)
            }
        }.onFailure {
            Log.w(TAG, "poison sweep: reading $table failed: ${it.message}")
        }.getOrNull()

    /** Deletes [storedKeys] from [table] in chunks; returns how many went. */
    private suspend fun deleteCloudKeys(
        c: SupabaseClient,
        table: String,
        storedKeys: List<String>
    ): Int {
        if (storedKeys.isEmpty()) return 0
        val keyColumn = when (table) {
            TABLE_HISTORY -> "item_id"
            TABLE_PREFS -> "pref_key"
            else -> "item_key"
        }
        var deleted = 0
        for (chunk in storedKeys.chunked(50)) {
            runCatching {
                c.from(table).delete {
                    filter { isIn(keyColumn, chunk) }
                }
                deleted += chunk.size
            }.onFailure {
                Log.w(TAG, "poison sweep: deleting from $table failed (${it.message})")
                return deleted
            }
        }
        return deleted
    }

    /**
     * Removes poisoned history rows from the profile they were copied INTO.
     * The active profile goes through Room (its DB may be open); every other
     * profile's DB is closed, so a raw delete is safe there.
     */
    private suspend fun deleteLocalHistoryRows(
        context: Context,
        pid: String,
        ids: List<String>
    ) {
        if (ids.isEmpty()) return
        if (pid == ProfileManager.activeProfile.value?.id) {
            runCatching {
                val dao = WatchHistoryDatabase.getInstanceScoped(context).watchHistoryDao()
                ids.forEach { dao.deleteById(it) }
            }.onFailure {
                Log.w(TAG, "poison sweep: active-profile history delete failed: ${it.message}")
            }
            return
        }
        rawDeleteHistoryRows(context, ProfileStorage.dbName(pid, SWEEP_DB_BASE), ids)
    }

    private fun rawDeleteHistoryRows(context: Context, dbName: String, ids: List<String>) {
        runCatching {
            val file = context.getDatabasePath(dbName)
            if (!file.exists()) return
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READWRITE
            )
            try {
                db.beginTransaction()
                try {
                    for (id in ids) {
                        db.execSQL("DELETE FROM watch_history WHERE id = ?", arrayOf(id))
                    }
                    db.setTransactionSuccessful()
                } finally {
                    db.endTransaction()
                }
            } finally {
                db.close()
            }
        }.onFailure {
            Log.w(TAG, "poison sweep: raw history delete in $dbName failed: ${it.message}")
        }
    }

    fun enqueueHistory(entity: WatchHistoryEntity, profileId: String? = currentProfileId()) {
        if (!isSignedIn()) return
        val payload = buildJsonObject {
            put("id", entity.id)
            put("parentId", entity.parentId)
            put("type", entity.type)
            put("name", entity.name)
            entity.episodeTitle?.let { put("episodeTitle", it) }
            entity.overview?.let { put("overview", it) }
            entity.clearLogo?.let { put("clearLogo", it) }
            entity.backdropUrl?.let { put("backdropUrl", it) }
            entity.totalEpisodesInSeason?.let { put("totalEpisodesInSeason", it) }
            entity.poster?.let { put("poster", it) }
            entity.streamUrl?.let { put("streamUrl", it) }
            entity.season?.let { put("season", it) }
            entity.episode?.let { put("episode", it) }
            entity.episodeStreamId?.let { put("episodeStreamId", it) }
            put("positionMs", entity.positionMs)
            put("durationMs", entity.durationMs)
            put("updatedAt", entity.updatedAt)
            put("isCompleted", entity.isCompleted)
            entity.completedAt?.let { put("completedAt", it) }
        }
        val row = OutboxItem(TABLE_HISTORY, "item_id", scopedKey(entity.id, profileId), payload)
        outbox.put(row)
        scheduleFlush()
    }

    fun enqueueWatched(entity: WatchedStatusEntity, profileId: String? = currentProfileId()) {
        if (!isSignedIn()) return
        val payload = buildJsonObject {
            put("key", entity.key)
            put("imdbId", entity.imdbId)
            put("mediaType", entity.mediaType)
            put("isWatched", entity.isWatched)
            put("updatedAt", entity.updatedAt)
        }
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
                flushOutbox()
                // Sign-out strands retry rows until the next sign-in (the
                // periodic loop restarts then) — don't busy-loop on them.
                if (!isSignedIn()) break
                // Loop while writes keep arriving so nothing strands: a row
                // enqueued during flushOutbox() re-arms this loop instead of
                // waiting for the next enqueue/60s retry.
                if (outbox.isEmpty) break
            }
        }
    }

    // Retry loop for failed outbox rows (offline writes, RLS/network
    // failures). The header contract promises "retried on interval" — this
    // is that interval; without it a failed flush sits in the outbox until
    // the next local write happens to enqueue something.
    private var periodicFlushJob: kotlinx.coroutines.Job? = null

    private fun startPeriodicFlush() {
        if (periodicFlushJob?.isActive == true) return
        periodicFlushJob = scope.launch {
            while (isSignedIn()) {
                delay(OUTBOX_RETRY_MS)
                if (!outbox.isEmpty) {
                    runCatching { flushOutbox() }
                }
            }
        }
    }

    private suspend fun flushOutbox() {
        val c = client ?: return
        if (!isSignedIn()) return

        val batch = outbox.snapshot()
        if (batch.isEmpty()) return

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
                    chunk.forEach { row -> outbox.remove(row) }
                    clearSyncError()
                } catch (e: Exception) {
                    Log.w(TAG, "flush $table chunk of ${chunk.size} failed: ${e.message}")
                    recordSyncError("Upload", e)
                    // Keep in outbox; retried by the periodic sync loop.
                } catch (t: Throwable) {
                    CrashReporter.recordNonFatal(
                        t,
                        mapOf("source" to "flush_outbox", "table" to table)
                    )
                    Log.e(TAG, "flush $table crashed: ${t.message}")
                }
            }
        }
        } // flushMutex
        val flushedAt = System.currentTimeMillis()
        _lastSyncAtMs.value = flushedAt
        _lastPushAtMs.value = flushedAt
    }

    /** Serializes flushOutbox() bodies (see the mutex acquire above). */
    private val flushMutex = Mutex()

    // ── Pull + merge ────────────────────────────────────────────────

    @Serializable
    private data class SyncRowDto(
        @SerialName("item_id") val itemId: String? = null,
        @SerialName("item_key") val itemKey: String? = null,
        @SerialName("pref_key") val prefKey: String? = null,
        val payload: JsonObject,
        @SerialName("updated_at") val updatedAt: String = ""
    )

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
        pullPrefs(context)
        pullHistory(context)
        pullWatched(context)
        val pulledAt = System.currentTimeMillis()
        _lastSyncAtMs.value = pulledAt
        _lastPullAtMs.value = pulledAt
    }

    private suspend fun pushAllNow(context: Context) {
        pushHistory(context)
        pushWatched(context)
        pushPrefsBlobs(context)
        flushOutbox()
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
            runCatching {
                val entries = com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
                    .getInstanceScoped(context)
                    .watchHistoryDao()
                    .getAll()
                com.kennyb1201.kbstream.data.tv.TvLauncherPublisher.sync(context, entries)
            }.onFailure { e ->
                Log.w(TAG, "launcher republish failed: ${e.message}")
            }
        }
    }

    private suspend fun pullHistory(context: Context) {
        val c = client ?: return
        // Scope the whole pull to the profile active at START: a switch
        // mid-pull would otherwise filter rows against the NEW profile while
        // still writing into the OLD profile's Room instance (captured
        // below), stranding rows in the wrong database.
        val pid = currentProfileId()
        try {
            val rows = c.from(TABLE_HISTORY)
                .select()
                .decodeList<SyncRowDto>()

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
            for (row in rows) {
                // Bail on a mid-pull profile switch: remaining rows belong
                // to a filter/DB pair that no longer matches.
                if (currentProfileId() != pid) return
                val remote = row.payload
                val remoteUpdated = remote["updatedAt"]?.jsonPrimitive?.content?.toLongOrNull() ?: continue
                val storedId = row.itemId ?: continue
                if (!storedKeyMatchesProfile(storedId, pid)) continue
                val id = unscopedKey(storedId)

                val localUpdated = localById[id]?.updatedAt ?: 0L

                if (remoteWins(remoteUpdated, localUpdated)) {
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
                            completedAt = remote.lng("completedAt")
                        )
                    )
                    applied++
                }
            }
            if (applied > 0) {
                Log.i(TAG, "history pull applied $applied rows")
                WatchedStatusRepository.invalidateAllCaches()
                com.kennyb1201.kbstream.data.tv.TvLauncherPublisher.sync(
                    context,
                    db.watchHistoryDao().getAll()
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "pullHistory failed: ${e.message}")
            recordSyncError("History sync", e)
        } catch (t: Throwable) {
            CrashReporter.recordNonFatal(t, mapOf("source" to "pull_history"))
            Log.e(TAG, "pullHistory crashed: ${t.message}")
        }
    }

    private suspend fun pullWatched(context: Context) {
        val c = client ?: return
        // Same captured-scope rule as pullHistory above.
        val pid = currentProfileId()
        try {
            val rows = c.from(TABLE_WATCHED)
                .select()
                .decodeList<SyncRowDto>()

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
                if (currentProfileId() != pid) return
                val remote = row.payload
                val remoteUpdated = remote["updatedAt"]?.jsonPrimitive?.content?.toLongOrNull() ?: continue
                val storedKey = row.itemKey ?: continue
                if (!storedKeyMatchesProfile(storedKey, pid)) continue
                val key = unscopedKey(storedKey)

                val localUpdated = localByKey[key]?.updatedAt ?: 0L

                if (remoteWins(remoteUpdated, localUpdated)) {
                    pendingUpdates.add(
                        WatchedStatusEntity(
                            key = key,
                            imdbId = remote.str("imdbId") ?: "",
                            mediaType = remote.str("mediaType") ?: "movie",
                            isWatched = remote.bool("isWatched"),
                            updatedAt = remoteUpdated
                        )
                    )
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
            Log.w(TAG, "pullWatched failed: ${e.message}")
            recordSyncError("Watched sync", e)
        } catch (t: Throwable) {
            CrashReporter.recordNonFatal(t, mapOf("source" to "pull_watched"))
            Log.e(TAG, "pullWatched crashed: ${t.message}")
        }
    }

    private suspend fun pullPrefs(context: Context) {
        val c = client ?: return
        try {
            val rows = c.from(TABLE_PREFS)
                .select()
                .decodeList<SyncRowDto>()

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
                if (currentProfileId() != pid) return
                PrefsPayloadApplier.apply(context, unscopedKey(storedKey), row.payload)
            }
        } catch (e: Exception) {
            Log.w(TAG, "pullPrefs failed: ${e.message}")
            recordSyncError("Settings sync", e)
        } catch (t: Throwable) {
            CrashReporter.recordNonFatal(t, mapOf("source" to "pull_prefs"))
            Log.e(TAG, "pullPrefs crashed: ${t.message}")
        }
    }

    // ── Push (initial seed / manual sync-now) ───────────────────────

    private suspend fun pushHistory(context: Context) {
        val db = WatchHistoryDatabase.getInstanceScoped(context)
        val all = db.watchHistoryDao().getAll()
        // Scope EVERY row with the profile that was active when the push
        // STARTED. Re-resolving per row (the old behavior) meant a profile
        // switch mid-loop stamped the OLD profile's rows with the NEW
        // profile's cloud scope — those poisoned rows then synced back down
        // onto the new profile as phantom watched markers.
        val pid = currentProfileId()
        all.forEach { enqueueHistory(it, pid) }
        flushOutbox()
    }

    private suspend fun pushWatched(context: Context) {
        val db = WatchHistoryDatabase.getInstanceScoped(context)
        val all = db.watchedStatusDao().getAll()
        // Same captured-scope rule as pushHistory above.
        val pid = currentProfileId()
        all.forEach { enqueueWatched(it, pid) }
        flushOutbox()
    }

    private suspend fun pushPrefsBlobs(context: Context) {
        // Captured once so every blob in this pass carries the SAME scope
        // even if the profile switches mid-loop (same rule as pushHistory).
        val pid = currentProfileId()
        PrefsPayloadBuilder.buildAll(context).forEach { (key, payload) ->
            // Belt-and-braces guard: an empty local profiles list must never
            // reach the cloud. A fresh device pushing before its first pull
            // would otherwise erase the account's profiles for every other
            // device (the sign-in race this guards against is fixed at the
            // call site, but any future caller ordering stays safe).
            if (key == PrefsPayloadBuilder.KEY_PROFILES &&
                (payload["profiles"] as? kotlinx.serialization.json.JsonArray)
                    ?.isEmpty() == true
            ) {
                return@forEach
            }
            enqueuePrefs(context, key, payload, pid)
        }
        flushOutbox()
    }

    /** Manual "Sync now" from Settings. */
    fun syncNow(context: Context) {
        if (!isSignedIn()) return
        scope.launch {
            flushOutbox()
            pushAll(context)
            pullAll(context)
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
            CrashReporter.recordNonFatal(t, mapOf("source" to "force_full_resync"))
            Log.w(TAG, "force full resync failed: ${t.message}")
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
                runCatching { pullAllNow(context) }
                    .onFailure { Log.w(TAG, "catch-up pull failed: ${it.message}") }
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

    // Serializes start/stop so two auth paths that fire near-simultaneously
    // (session restore racing a manual sign-in) can't both pass the
    // "already subscribed?" check and create DUPLICATE channels — which
    // doubled every remote-change event and leaked a websocket.
    private val realtimeMutex = Mutex()

    private fun startRealtime() {
        val c = client ?: return

        scope.launch {
            realtimeMutex.withLock {
                if (realtimeSubscriptions.isNotEmpty()) return@withLock
                try {
                    // One channel per table; the flow must be created BEFORE the
                    // channel subscribes (supabase-kt requirement).
                    listOf(TABLE_HISTORY, TABLE_WATCHED, TABLE_PREFS).forEach { table ->
                        subscribeTable(c, table)
                    }
                    Log.i(TAG, "realtime subscribed to 3 tables")
                    _realtimeStatus.value = "live"
                    _realtimeChannelCount.value = realtimeSubscriptions.size
                    watchRealtimeHealth()
                } catch (e: Exception) {
                    Log.w(TAG, "realtime setup failed: ${e.message}")
                } catch (t: Throwable) {
                    CrashReporter.recordNonFatal(t, mapOf("source" to "realtime_setup"))
                    Log.e(TAG, "realtime setup crashed: ${t.message}")
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
        scope.launch {
            while (isSignedIn()) {
                delay(REALTIME_HEALTH_CHECK_MS)
                val c = client ?: break
                val subs = realtimeSubscriptions.toList()
                if (subs.isEmpty()) break
                var repaired = false
                for (sub in subs) {
                    if (!isSignedIn()) break
                    // Only rebuild genuinely DEAD channels. UNSUBSCRIBED is
                    // the terminal state a kicked/errored channel is left in.
                    // SUBSCRIBING/UNSUBSCRIBING are transient in-flight states
                    // — tearing those down mid-join would race the SDK.
                    if (sub.channel.status.value ==
                        io.github.jan.supabase.realtime.RealtimeChannel.Status.UNSUBSCRIBED
                    ) {
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
                            Log.w(TAG, "rebuild ${sub.table} failed: ${e.message}")
                        } catch (t: Throwable) {
                            CrashReporter.recordNonFatal(t, mapOf("source" to "realtime_resubscribe"))
                        }
                    }
                }
                if (repaired) {
                    appContextRef?.get()?.let { ctx ->
                        runCatching { pullAll(ctx) }
                            .onFailure { Log.w(TAG, "post-rebuild pull failed: ${it.message}") }
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
                    else -> TABLE_PREFS
                }

                // Reuse the pull merge for a single row by decoding through the DTO.
                val row = json.decodeFromJsonElement(SyncRowDto.serializer(), record)
                when (table) {
                    TABLE_HISTORY -> applyHistoryRow(row)
                    TABLE_WATCHED -> applyWatchedRow(row)
                    TABLE_PREFS -> {
                        val context = appContextRef?.get() ?: return@launch
                        val storedKey = row.prefKey ?: return@launch
                        if (storedKeyApplies(storedKey)) {
                            PrefsPayloadApplier.apply(context, unscopedKey(storedKey), row.payload)
                        }
                    }
                }
                _lastSyncAtMs.value = System.currentTimeMillis()
            } catch (e: Exception) {
                Log.w(TAG, "onRemoteChange failed: ${e.message}")
            } catch (t: Throwable) {
                CrashReporter.recordNonFatal(t, mapOf("source" to "remote_change"))
                Log.e(TAG, "onRemoteChange crashed: ${t.message}")
            }
        }
    }

    private suspend fun applyHistoryRow(row: SyncRowDto) {
        val context = appContextRef?.get() ?: return
        val db = WatchHistoryDatabase.getInstanceScoped(context)
        val remote = row.payload
        val remoteUpdated = remote["updatedAt"]?.jsonPrimitive?.content?.toLongOrNull() ?: return
        val storedId = row.itemId ?: return
        if (!storedKeyMatchesActiveProfile(storedId)) return
        val id = unscopedKey(storedId)

        val local = db.watchHistoryDao().getById(id)
        val localUpdated = local?.updatedAt ?: 0L
        if (remoteWins(remoteUpdated, localUpdated)) {
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
                    completedAt = remote.lng("completedAt")
                )
            )
            WatchedStatusRepository.invalidateAllCaches()
        }
    }

    private suspend fun applyWatchedRow(row: SyncRowDto) {
        val context = appContextRef?.get() ?: return
        val db = WatchHistoryDatabase.getInstanceScoped(context)
        val remote = row.payload
        val remoteUpdated = remote["updatedAt"]?.jsonPrimitive?.content?.toLongOrNull() ?: return
        val storedKey = row.itemKey ?: return
        if (!storedKeyMatchesActiveProfile(storedKey)) return
        val key = unscopedKey(storedKey)

        val local = db.watchedStatusDao().getByKeys(listOf(key)).firstOrNull()
        val localUpdated = local?.updatedAt ?: 0L
        if (remoteWins(remoteUpdated, localUpdated)) {
            db.watchedStatusDao().upsertAll(
                listOf(
                    WatchedStatusEntity(
                        key = key,
                        imdbId = remote.str("imdbId") ?: "",
                        mediaType = remote.str("mediaType") ?: "movie",
                        isWatched = remote.bool("isWatched"),
                        updatedAt = remoteUpdated
                    )
                )
            )
            WatchedStatusRepository.invalidateAllCaches()
        }
    }

    private fun stopRealtime() {
        val subs = realtimeSubscriptions.toList()
        realtimeSubscriptions.clear()
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

    // ── Helpers ─────────────────────────────────────────────────────

    @Volatile
    internal var appContextRef: java.lang.ref.WeakReference<Context>? = null

    fun isSignedIn(): Boolean = _authState.value is AuthState.SignedIn

    private fun JsonObject.str(key: String): String? =
        this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

    private fun JsonObject.lng(key: String): Long? =
        str(key)?.toLongOrNull()

    private fun JsonObject.bool(key: String): Boolean =
        str(key)?.toBooleanStrictOrNull() ?: false

    private const val SYNC_PREFS = "kbstream_sync"
    private const val KEY_REFRESH_TOKEN = "supabase_refresh_token"
    private const val KEY_EMAIL = "supabase_email"

    // Outbox retry cadence. One minute: short enough that an offline burst
    // lands promptly after reconnect, rare enough to be invisible.
    private const val OUTBOX_RETRY_MS = 60_000L

    // How often the realtime health watcher verifies the channels are still
    // joined and resubscribes the dead ones. 15s: a dropped channel is
    // repaired well inside the "user noticed nothing synced" window.
    private const val REALTIME_HEALTH_CHECK_MS = 15_000L
}
