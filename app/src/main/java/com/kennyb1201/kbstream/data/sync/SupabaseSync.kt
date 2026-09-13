package com.kennyb1201.kbstream.data.sync

import android.content.Context
import android.util.Log
import io.github.jan_tennert.supabase.SupabaseClient
import io.github.jan_tennert.supabase.createSupabaseClient
import io.github.jan_tennert.supabase.gotrue.Auth
import io.github.jan_tennert.supabase.gotrue.auth
import io.github.jan_tennert.supabase.postgrest.Postgrest
import io.github.jan_tennert.supabase.postgrest.from
import io.github.jan_tennert.supabase.postgrest.query.Columns
import io.github.jan_tennert.supabase.realtime.Realtime
import io.github.jan_tennert.supabase.realtime.channel
import io.github.jan_tennert.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

    // ── Client ──────────────────────────────────────────────────────

    private var client: SupabaseClient? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private const val TABLE_HISTORY = "sync_watch_history"
    private const val TABLE_WATCHED = "sync_watched_status"
    private const val TABLE_PREFS = "sync_prefs"

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
                    runCatching {
                        c.auth.refreshCurrentSession()
                    }.onSuccess {
                        _authState.value = AuthState.SignedIn(savedEmail.orEmpty())
                        _syncEnabled.value = true
                        startRealtime()
                        pullAll(context)
                    }.onFailure { e ->
                        Log.w(TAG, "session refresh failed: ${e.message}")
                        _authState.value = AuthState.SignedOut
                    }
                } else {
                    _authState.value = AuthState.SignedOut
                }
            } catch (e: Exception) {
                Log.e(TAG, "restoreSession failed", e)
                _authState.value = AuthState.SignedOut
            }
        }
    }

    // ── Auth ────────────────────────────────────────────────────────

    fun signIn(context: Context, email: String, password: String) {
        val c = client ?: return
        _authState.value = AuthState.SigningIn
        scope.launch {
            try {
                c.auth.signInWith(io.github.jan_tennert.supabase.gotrue.providers.builtin.Email) {
                    this.email = email.trim()
                    pass = password
                }
                persistSession(context, email.trim())
                _authState.value = AuthState.SignedIn(email.trim())
                _syncEnabled.value = true
                startRealtime()
                pullAll(context)
            } catch (e: Exception) {
                Log.e(TAG, "signIn failed", e)
                _authState.value = AuthState.Error(e.message ?: "Sign-in failed")
            }
        }
    }

    fun signUp(context: Context, email: String, password: String) {
        val c = client ?: return
        _authState.value = AuthState.SigningIn
        scope.launch {
            try {
                c.auth.signUpWith(io.github.jan_tennert.supabase.gotrue.providers.builtin.Email) {
                    this.email = email.trim()
                    pass = password
                }
                persistSession(context, email.trim())
                _authState.value = AuthState.SignedIn(email.trim())
                _syncEnabled.value = true
                startRealtime()
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
        scope.launch {
            runCatching { c.auth.signOut() }
            context.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)
                .edit().clear().apply()
            stopRealtime()
            _authState.value = AuthState.SignedOut
            _syncEnabled.value = false
        }
    }

    private fun persistSession(context: Context, email: String) {
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
        val payload: JsonObject
    )

    private val outbox = ConcurrentHashMap<String, OutboxRow>()

    private fun outboxId(row: OutboxRow) = "${row.table}|${row.keyColumn}|${row.key}"

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
        val row = OutboxRow(TABLE_HISTORY, "item_id", entity.id, payload)
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
        val row = OutboxRow(TABLE_WATCHED, "item_key", entity.key, payload)
        outbox[outboxId(row)] = row
        scheduleFlush()
    }

    /** Prefs/addons/Simkl/IPTV blobs. [prefKey] is a stable string like "player_display_prefs". */
    fun enqueuePrefs(context: Context, prefKey: String, payload: JsonObject) {
        if (!isSignedIn()) return
        val row = OutboxRow(TABLE_PREFS, "pref_key", prefKey, payload)
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

    private suspend fun flushOutbox() {
        val c = client ?: return
        if (!isSignedIn()) return

        val batch = outbox.values.toList()
        if (batch.isEmpty()) return

        for (row in batch) {
            try {
                val body = buildJsonObject {
                    put(
                        when (row.keyColumn) {
                            "item_id" -> "item_id"
                            "item_key" -> "item_key"
                            else -> "pref_key"
                        },
                        row.key
                    )
                    put("payload", row.payload)
                    put("updated_at", java.time.Instant.now().toString())
                }
                c.from(row.table).upsert(body)
                outbox.remove(outboxId(row))
            } catch (e: Exception) {
                Log.w(TAG, "flush ${row.table}/${row.key} failed: ${e.message}")
                // Keep in outbox; retried by the periodic sync loop.
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

    fun pullAll(context: Context) {
        scope.launch {
            pullHistory(context)
            pullWatched(context)
            pullPrefs(context)
            _lastSyncAtMs.value = System.currentTimeMillis()
        }
    }

    fun pushAll(context: Context) {
        scope.launch {
            pushHistory(context)
            pushWatched(context)
            pushPrefsBlobs(context)
            flushOutbox()
            _lastSyncAtMs.value = System.currentTimeMillis()
        }
    }

    private suspend fun pullHistory(context: Context) {
        val c = client ?: return
        try {
            val rows = c.from(TABLE_HISTORY)
                .select()
                .decodeList<SyncRowDto>()

            val db = WatchHistoryDatabase.getInstance(context)
            var applied = 0
            for (row in rows) {
                val remote = row.payload
                val remoteUpdated = remote["updatedAt"]?.jsonPrimitive?.content?.toLongOrNull() ?: continue
                val id = row.itemId ?: continue

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
        }
    }

    private suspend fun pullWatched(context: Context) {
        val c = client ?: return
        try {
            val rows = c.from(TABLE_WATCHED)
                .select()
                .decodeList<SyncRowDto>()

            val db = WatchHistoryDatabase.getInstance(context)
            var applied = 0
            for (row in rows) {
                val remote = row.payload
                val remoteUpdated = remote["updatedAt"]?.jsonPrimitive?.content?.toLongOrNull() ?: continue
                val key = row.itemKey ?: continue

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
                    applied++
                }
            }
            if (applied > 0) {
                Log.i(TAG, "watched pull applied $applied rows")
                WatchedStatusRepository.invalidateAllCaches()
            }
        } catch (e: Exception) {
            Log.w(TAG, "pullWatched failed: ${e.message}")
        }
    }

    private suspend fun pullPrefs(context: Context) {
        val c = client ?: return
        try {
            val rows = c.from(TABLE_PREFS)
                .select()
                .decodeList<SyncRowDto>()

            for (row in rows) {
                val key = row.prefKey ?: continue
                PrefsPayloadApplier.apply(context, key, row.payload)
            }
        } catch (e: Exception) {
            Log.w(TAG, "pullPrefs failed: ${e.message}")
        }
    }

    // ── Push (initial seed / manual sync-now) ───────────────────────

    private suspend fun pushHistory(context: Context) {
        val db = WatchHistoryDatabase.getInstance(context)
        val all = db.watchHistoryDao().getAll()
        all.forEach { enqueueHistory(it) }
        flushOutbox()
    }

    private suspend fun pushWatched(context: Context) {
        val db = WatchHistoryDatabase.getInstance(context)
        val all = db.watchedStatusDao().getAll()
        all.forEach { enqueueWatched(it) }
        flushOutbox()
    }

    private suspend fun pushPrefsBlobs(context: Context) {
        PrefsPayloadBuilder.buildAll(context).forEach { (key, payload) ->
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
        java.util.concurrent.CopyOnWriteArrayList<io.github.jan_tennert.supabase.realtime.RealtimeChannel>()

    private fun startRealtime() {
        val c = client ?: return
        if (realtimeChannels.isNotEmpty()) return

        scope.launch {
            try {
                // One channel per table; the flow must be created BEFORE the
                // channel subscribes (supabase-kt requirement).
                listOf(TABLE_HISTORY, TABLE_WATCHED, TABLE_PREFS).forEach { table ->
                    val ch = c.channel("kbstream_$table")
                    val changeFlow = ch.postgresChangeFlow<io.github.jan_tennert.supabase.realtime.PostgresAction>(
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
            }
        }
    }

    private fun onRemoteChange(action: io.github.jan_tennert.supabase.realtime.PostgresAction) {
        // Remote rows arrive as JSON records; apply via the same merge rules.
        scope.launch {
            try {
                val record = when (action) {
                    is io.github.jan_tennert.supabase.realtime.PostgresAction.Update ->
                        action.record as? JsonObject ?: return@launch
                    is io.github.jan_tennert.supabase.realtime.PostgresAction.Insert ->
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
                    TABLE_PREFS -> PrefsPayloadApplier.apply(appContextRef?.get() ?: return@launch, row.prefKey ?: return@launch, row.payload)
                }
                _lastSyncAtMs.value = System.currentTimeMillis()
            } catch (e: Exception) {
                Log.w(TAG, "onRemoteChange failed: ${e.message}")
            }
        }
    }

    private suspend fun applyHistoryRow(row: SyncRowDto) {
        val context = appContextRef?.get() ?: return
        val db = WatchHistoryDatabase.getInstance(context)
        val remote = row.payload
        val remoteUpdated = remote["updatedAt"]?.jsonPrimitive?.content?.toLongOrNull() ?: return
        val id = row.itemId ?: return

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
        val db = WatchHistoryDatabase.getInstance(context)
        val remote = row.payload
        val remoteUpdated = remote["updatedAt"]?.jsonPrimitive?.content?.toLongOrNull() ?: return
        val key = row.itemKey ?: return

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
        realtimeChannels.forEach { ch -> runCatching { ch.leave() } }
        realtimeChannels.clear()
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
}
