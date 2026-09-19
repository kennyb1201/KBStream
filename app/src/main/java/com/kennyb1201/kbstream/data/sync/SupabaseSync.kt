package com.kennyb1201.kbstream.data.sync

import android.content.Context
import android.util.Log
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.gotrue.Auth
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
import java.util.concurrent.ConcurrentHashMap
import com.kennyb1201.kbstream.BuildConfig
import com.kennyb1201.kbstream.data.addon.AddonManager
import com.kennyb1201.kbstream.data.reporting.CrashReporter
import com.kennyb1201.kbstream.data.cache.WatchedStatusEntity
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.history.WatchHistoryEntity
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

    // ── Outbox (offline-safe local-write flush) ─────────────────────

    private data class OutboxRow(
        val table: String,
        val keyColumn: String,
        val key: String,
        val payload: JsonObject,
        // Wall clock at enqueue time. Uploaded as the row's updated_at column
        // so a retried STALE flush can never carry a newer timestamp than a
        // fresh row that was enqueued and flushed later.
        val enqueuedAtMs: Long = System.currentTimeMillis()
    )

    private val outbox = ConcurrentHashMap<String, OutboxRow>()

    private fun outboxId(row: OutboxRow) = "${row.table}|${row.keyColumn}|${row.key}"

    /**
     * Profile scoping: item keys are prefixed with the active profile id so
     * profiles never see each other's rows (the account-level RLS keeps
     * other accounts out; this keeps sibling profiles separated).
     * "p:<profileId>:<originalKey>".
     */
    private fun scopedKey(originalKey: String): String {
        val pid = com.kennyb1201.kbstream.data.sync.ProfileManager.activeProfile.value?.id
            ?: return originalKey
        return "p:$pid:$originalKey"
    }

    private fun unscopedKey(storedKey: String): String =
        if (storedKey.startsWith("p:")) {
            storedKey.substringAfter("p:").substringAfter(':')
        } else {
            storedKey
        }

    private fun storedKeyMatchesActiveProfile(storedKey: String): Boolean {
        val pid = com.kennyb1201.kbstream.data.sync.ProfileManager.activeProfile.value?.id
            ?: return !storedKey.startsWith("p:")
        return storedKey.startsWith("p:$pid:")
    }

    /** Account-wide keys (the profiles list itself) bypass the profile filter. */
    private fun storedKeyApplies(storedKey: String): Boolean {
        if (storedKey == PrefsPayloadBuilder.KEY_PROFILES) return true
        return storedKeyMatchesActiveProfile(storedKey)
    }

    fun enqueueHistory(entity: WatchHistoryEntity) {
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
        val row = OutboxRow(TABLE_HISTORY, "item_id", scopedKey(entity.id), payload)
        outbox[outboxId(row)] = row
        scheduleFlush()
    }

    fun enqueueWatched(entity: WatchedStatusEntity) {
        if (!isSignedIn()) return
        val payload = buildJsonObject {
            put("key", entity.key)
            put("imdbId", entity.imdbId)
            put("mediaType", entity.mediaType)
            put("isWatched", entity.isWatched)
            put("updatedAt", entity.updatedAt)
        }
        val row = OutboxRow(TABLE_WATCHED, "item_key", scopedKey(entity.key), payload)
        outbox[outboxId(row)] = row
        scheduleFlush()
    }

    /**
     * Prefs/addons/Simkl/IPTV blobs. [prefKey] is a stable string like
     * "display_prefs". Everything except the profiles list itself is stored
     * profile-scoped ("p:<profileId>:<prefKey>") so sibling profiles never
     * overwrite or read each other's settings in the cloud.
     */
    fun enqueuePrefs(context: Context, prefKey: String, payload: JsonObject) {
        if (!isSignedIn()) return
        val stored = if (prefKey == PrefsPayloadBuilder.KEY_PROFILES) {
            prefKey
        } else {
            scopedKey(prefKey)
        }
        val row = OutboxRow(TABLE_PREFS, "pref_key", stored, payload)
        outbox[outboxId(row)] = row
        scheduleFlush()
    }

    private var flushJob: kotlinx.coroutines.Job? = null

    private fun scheduleFlush() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            delay(400) // coalesce bursts (e.g. bulk watched import)
            flushOutbox()
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
                if (outbox.isNotEmpty()) {
                    runCatching { flushOutbox() }
                }
            }
        }
    }

    private suspend fun flushOutbox() {
        val c = client ?: return
        if (!isSignedIn()) return

        val batch = outbox.values.toList()
        if (batch.isEmpty()) return

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
                    chunk.forEach { row -> outbox.remove(outboxId(row), row) }
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
        _lastSyncAtMs.value = System.currentTimeMillis()
    }

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
        _lastSyncAtMs.value = System.currentTimeMillis()
    }

    private suspend fun pushAllNow(context: Context) {
        pushHistory(context)
        pushWatched(context)
        pushPrefsBlobs(context)
        flushOutbox()
        _lastSyncAtMs.value = System.currentTimeMillis()
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
                    if (!storedKeyMatchesActiveProfile(storedId)) null else unscopedKey(storedId)
                }.distinct())
                .associateBy { it.id }
            var applied = 0
            for (row in rows) {
                val remote = row.payload
                val remoteUpdated = remote["updatedAt"]?.jsonPrimitive?.content?.toLongOrNull() ?: continue
                val storedId = row.itemId ?: continue
                if (!storedKeyMatchesActiveProfile(storedId)) continue
                val id = unscopedKey(storedId)

                val localUpdated = localById[id]?.updatedAt ?: 0L

                if (remoteUpdated > localUpdated) {
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
                if (!storedKeyMatchesActiveProfile(storedKey)) null else unscopedKey(storedKey)
            }.distinct()
            val localByKey = if (keysForActiveProfile.isEmpty()) {
                emptyMap()
            } else {
                db.watchedStatusDao().getByKeys(keysForActiveProfile).associateBy { it.key }
            }
            val pendingUpdates = mutableListOf<WatchedStatusEntity>()
            var applied = 0
            for (row in rows) {
                val remote = row.payload
                val remoteUpdated = remote["updatedAt"]?.jsonPrimitive?.content?.toLongOrNull() ?: continue
                val storedKey = row.itemKey ?: continue
                if (!storedKeyMatchesActiveProfile(storedKey)) continue
                val key = unscopedKey(storedKey)

                val localUpdated = localByKey[key]?.updatedAt ?: 0L

                if (remoteUpdated > localUpdated) {
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
            // One batch write instead of one upsert per row.
            if (pendingUpdates.isNotEmpty()) {
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
            for (row in rows) {
                val storedKey = row.prefKey ?: continue
                if (storedKey == PrefsPayloadBuilder.KEY_PROFILES) continue
                if (!storedKeyMatchesActiveProfile(storedKey)) continue
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
        all.forEach { enqueueHistory(it) }
        flushOutbox()
    }

    private suspend fun pushWatched(context: Context) {
        val db = WatchHistoryDatabase.getInstanceScoped(context)
        val all = db.watchedStatusDao().getAll()
        all.forEach { enqueueWatched(it) }
        flushOutbox()
    }

    private suspend fun pushPrefsBlobs(context: Context) {
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
            enqueuePrefs(context, key, payload)
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

    // ── Realtime ────────────────────────────────────────────────────

    private val realtimeChannels =
        java.util.concurrent.CopyOnWriteArrayList<io.github.jan.supabase.realtime.RealtimeChannel>()

    // Serializes start/stop so two auth paths that fire near-simultaneously
    // (session restore racing a manual sign-in) can't both pass the
    // "already subscribed?" check and create DUPLICATE channels — which
    // doubled every remote-change event and leaked a websocket.
    private val realtimeMutex = Mutex()

    private fun startRealtime() {
        val c = client ?: return

        scope.launch {
            realtimeMutex.withLock {
                if (realtimeChannels.isNotEmpty()) return@withLock
                try {
                    // One channel per table; the flow must be created BEFORE the
                    // channel subscribes (supabase-kt requirement).
                    listOf(TABLE_HISTORY, TABLE_WATCHED, TABLE_PREFS).forEach { table ->
                        val ch = c.channel("kbstream_$table")
                        val changeFlow = ch.postgresChangeFlow<io.github.jan.supabase.realtime.PostgresAction>(
                            schema = "public"
                        ) {
                            this.table = table
                        }

                        scope.launch {
                            changeFlow.collect { action -> onRemoteChange(action) }
                        }

                        ch.subscribe(blockUntilSubscribed = false)
                        realtimeChannels.add(ch)
                    }
                    Log.i(TAG, "realtime subscribed to 3 tables")
                } catch (e: Exception) {
                    Log.w(TAG, "realtime setup failed: ${e.message}")
                } catch (t: Throwable) {
                    CrashReporter.recordNonFatal(t, mapOf("source" to "realtime_setup"))
                    Log.e(TAG, "realtime setup crashed: ${t.message}")
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
        if (remoteUpdated > localUpdated) {
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
        if (remoteUpdated > localUpdated) {
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
        val channels = realtimeChannels.toList()
        realtimeChannels.clear()
        scope.launch {
            realtimeMutex.withLock {
                channels.forEach { ch -> runCatching { ch.unsubscribe() } }
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
}
