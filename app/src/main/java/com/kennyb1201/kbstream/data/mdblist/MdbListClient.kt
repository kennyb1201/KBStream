package com.kennyb1201.kbstream.data.mdblist

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.addon.AppContextHolder
import com.kennyb1201.kbstream.data.cache.TmdbJsonCacheDao
import com.kennyb1201.kbstream.data.cache.TmdbJsonCacheEntity
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.network.BaseHttpClient
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.kennyb1201.kbstream.ui.settings.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * `progress` for POST /scrobble/{start,pause,stop}, as the whole percentage
 * MDBList validates: a fractional value is rejected with HTTP 400
 * ("progress: Ensure that there are no more than 5 digits in total."), and
 * the player hands in a computed position/length ratio scaled to 0-100, so
 * its raw Double (6.184509511134195) failed every live scrobble — nothing
 * ever showed up as now-playing while the separate /sync/watched writes kept
 * working. Clamped so a position past the reported end can never overflow.
 */
internal fun mdblistScrobbleProgress(progress: Double): Int =
    if (progress.isNaN()) 0 else progress.roundToInt().coerceIn(0, 100)

/**
 * One entry of a media response's `ratings` array: `{source, value, score}`.
 *
 * `value` is the provider's own number (IMDb/TMDB/MAL are out of 10,
 * Letterboxd is stars, RT/Metacritic are out of 100) while `score` is
 * MDBList's normalized 0-100 figure — the API documentation calls it the
 * "best field to use consistently", so it is preferred and the native value
 * is only a fallback for older/partial payloads.
 */
data class MdbListRatingEntry(
    val source: String,
    val value: Double? = null,
    val score: Double? = null
)

/**
 * Native scale of each source's `value`, used only when `score` is absent so
 * a fallback is never an order of magnitude off (an IMDb 8.8 must not render
 * as "9%", nor a TMDB 81 as "81.0").
 */
private val MDBLIST_NATIVE_MAX: Map<String, Double> = mapOf(
    "imdb" to 10.0,
    "tmdb" to 10.0,
    "trakt" to 10.0,
    "mal" to 10.0,
    "myanimelist" to 10.0,
    "letterboxd" to 5.0,
    "tomatoes" to 100.0,
    "metacritic" to 100.0,
    "rogerebert" to 100.0,
    "audience" to 100.0
)

/**
 * Canonical 0-100 figure for one rating entry, or null when the source sent
 * nothing usable. A zero is treated as "no rating": MDBList omits absent
 * sources rather than zeroing them, and "0.0" / "0%" on a chip is noise.
 */
internal fun mdbListPercentOf(entry: MdbListRatingEntry): Double? {
    entry.score
        ?.takeIf { it.isFinite() && it > 0.0 }
        ?.let { return it.coerceAtMost(100.0) }

    val native = entry.value?.takeIf { it.isFinite() && it > 0.0 } ?: return null
    val nativeMax = MDBLIST_NATIVE_MAX[entry.source.lowercase()] ?: 100.0
    // Some payloads already hand back a normalized number for a source whose
    // native scale is smaller: a value that cannot fit the native range is
    // read as a percentage instead of being scaled a second time.
    if (nativeMax >= 100.0 || native > nativeMax * 2.0) return native.coerceAtMost(100.0)
    return (native / nativeMax * 100.0).coerceAtMost(100.0)
}

private fun formatOutOfTen(percent: Double?): String? = percent
    ?.let { String.format(java.util.Locale.US, "%.1f", it / 10.0) }

private fun formatPercent(percent: Double?): String? =
    percent?.let { "${it.roundToInt()}%" }

private fun formatMetacritic(percent: Double?): String? =
    percent?.let { "${it.roundToInt()}/100" }

/**
 * Maps a media response's `ratings` array onto the display model.
 *
 * IMDb/TMDB/MyAnimeList read as x/10 (their own convention), Rotten
 * Tomatoes/Trakt/Letterboxd as percent, Metacritic as x/100. Returns null
 * when no source produced a usable figure, which is the UI's signal to omit
 * the row entirely.
 */
internal fun buildMdbListRatings(entries: List<MdbListRatingEntry>): MdbListRatings? {
    if (entries.isEmpty()) return null

    // First entry per source wins: a payload that repeats a source (critics
    // and audience both under "tomatoes") must not flip the chip value
    // between refreshes.
    val bySource = LinkedHashMap<String, MdbListRatingEntry>(entries.size)
    entries.forEach { entry ->
        val key = entry.source.lowercase()
        if (!bySource.containsKey(key)) bySource[key] = entry
    }

    fun percentOf(vararg sources: String): Double? {
        sources.forEach { source ->
            bySource[source]?.let { entry ->
                mdbListPercentOf(entry)?.let { return it }
            }
        }
        return null
    }

    val ratings = MdbListRatings(
        imdb = formatOutOfTen(percentOf("imdb")),
        tmdb = formatOutOfTen(percentOf("tmdb")),
        rottenTomatoes = formatPercent(percentOf("tomatoes", "rotten_tomatoes")),
        metacritic = formatMetacritic(percentOf("metacritic")),
        trakt = formatPercent(percentOf("trakt")),
        letterboxd = formatPercent(percentOf("letterboxd")),
        myAnimeList = formatOutOfTen(percentOf("mal", "myanimelist"))
    )
    return ratings.takeIf { it.hasAny }
}

/**
 * One entry inside an MDBList list or watchlist (GET /lists/{id}/items,
 * GET /watchlist/items). Only the fields the Library tab needs.
 */
data class MdbListEntry(
    val title: String?,
    val mediaType: String,
    val year: Int?,
    val poster: String?,
    val imdbId: String?,
    val tmdbId: Int?
)

/**
 * One user list on the account (GET /lists/user).
 */
data class MdbListUserList(
    val id: Int,
    val name: String,
    val itemCount: Int
)

/**
 * Critic/audience ratings pulled from MDBList for one title. All fields are
 * preformatted display strings so the UI can render them verbatim
 * ("8.1", "95%", "78/100").
 *
 * Replaces the old OMDb row with the same shape but adds more sources
 * (Trakt / Letterboxd / MyAnimeList) that OMDb did not carry. Fails soft:
 * any network/parse problem or a blank key yields null so the detail screen
 * simply omits the ratings row.
 */
data class MdbListRatings(
    val imdb: String? = null,
    val tmdb: String? = null,
    val rottenTomatoes: String? = null,
    val metacritic: String? = null,
    val trakt: String? = null,
    val letterboxd: String? = null,
    val myAnimeList: String? = null
) {
    val hasAny: Boolean
        get() = imdb != null || tmdb != null || rottenTomatoes != null ||
            metacritic != null || trakt != null || letterboxd != null ||
            myAnimeList != null
}

/**
 * One paused MDBList playback session (GET /sync/playback), shaped for the
 * Continue Watching rail merge.
 */
data class MdbListPlaybackItem(
    val sessionId: Int,
    val progress: Double,
    val isMovie: Boolean,
    val imdbId: String?,
    val tmdbId: Int?,
    val title: String?,
    val updatedAtMs: Long,
    val runtimeMinutes: Int,
    val season: Int? = null,
    val episode: Int? = null
)

/**
 * Watched-history snapshot for the badge layer, parsed from GET
 * /sync/watched. Keys use the app's id forms so membership checks are
 * direct: movies/shows are "tt123" / "tmdb:456"; episodes are
 * "tt123:S:E" / "tmdb:456:S:E" (show-anchored, so per-episode lookups
 * key off the show's id).
 *
 * Show-level entries only prove PROGRESS (MDBList's watched history has
 * no per-show watched/total counts), so shows land in [startedShowKeys],
 * never in a "completed" set. Episode keys carry the real per-episode
 * watch state.
 */
data class MdbListWatchedSnapshot(
    val movieKeys: Set<String> = emptySet(),
    val startedShowKeys: Set<String> = emptySet(),
    val episodeKeys: Set<String> = emptySet()
) {
    val isEmpty: Boolean
        get() = movieKeys.isEmpty() && startedShowKeys.isEmpty() &&
            episodeKeys.isEmpty()
}

/**
 * Minimal MDBList client (plain OkHttp + org.json, matching the OMDb helper
 * pattern it replaces). API docs: https://api.mdblist.com/docs/ — ratings via
 * GET /{imdb|tmdb}/{movie|show}/{id} and live tracking via the Scrobble
 * (POST /scrobble/start|pause|stop|clear) and Sync (GET /sync/playback,
 * GET/POST /sync/watched[/remove]) sections. The key travels as the `apikey`
 * query parameter on every request.
 */
object MdbListClient {

    // ------------------------------------------------------------------
    // Daily request budget
    //
    // A free MDBList key allows 1,000 requests per day (docs.mdblist.com →
    // API limits; paid tiers start at 10k). The app could spend that on its own
    // refetchable reads: /sync/watched downloads the whole history page by
    // page, and every scrobble event used to invalidate it, so one playback
    // re-downloaded the history repeatedly. When the day is gone the API
    // answers 429 for EVERYTHING — the scrobbles that actually matter
    // included — so requests are now spent in priority order: history writes
    // always go, refetchable reads stop at [READ_BUDGET] and wait for the
    // UTC-day reset. A 429 also stops the calls outright until the allowance
    // is really back, instead of the retry storm the field log showed
    // (scrobble/start 429ing every few seconds).
    // ------------------------------------------------------------------
    private const val REQUEST_PREFS = "mdblist_request_budget"
    private const val KEY_REQUEST_DAY = "day"
    private const val KEY_REQUEST_COUNT = "count"

    /** Refetchable reads stop here, keeping the rest of the day for writes. */
    private const val READ_BUDGET = 850

    /** Writes stop here — short of the API's own limit, so a 429 is never
     * what stops us. */
    private const val TOTAL_CEILING = 980

    /** Tags a request that must go out (a write) rather than a refetch. */
    private val WRITE_TAG = Any()

    @Volatile private var requestsDay = ""
    private val requestsToday = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var limitedUntilMs = 0L

    private fun budgetPrefs(context: Context) =
        context.getSharedPreferences(REQUEST_PREFS, Context.MODE_PRIVATE)

    private fun utcDay(nowMs: Long): String =
        java.time.Instant.ofEpochMilli(nowMs)
            .atOffset(java.time.ZoneOffset.UTC)
            .toLocalDate()
            .toString()

    private fun nextUtcMidnight(nowMs: Long): Long =
        java.time.Instant.ofEpochMilli(nowMs)
            .atOffset(java.time.ZoneOffset.UTC)
            .toLocalDate()
            .plusDays(1)
            .atStartOfDay(java.time.ZoneOffset.UTC)
            .toInstant()
            .toEpochMilli()

    /** Rolls the persisted counter over when the UTC day changes. */
    private fun ensureBudget() {
        val now = System.currentTimeMillis()
        val today = utcDay(now)
        if (requestsDay == today) return
        val context = com.kennyb1201.kbstream.data.addon.AppContextHolder.appContext
        val stored = if (context != null) {
            val prefs = budgetPrefs(context)
            if (prefs.getString(KEY_REQUEST_DAY, "") == today) {
                prefs.getInt(KEY_REQUEST_COUNT, 0)
            } else 0
        } else 0
        requestsDay = today
        requestsToday.set(stored)
        // A new UTC day is a new allowance: yesterday's reset no longer
        // applies.
        if (limitedUntilMs < now) limitedUntilMs = 0L
    }

    private fun maySpend(essential: Boolean): Boolean {
        ensureBudget()
        if (limitedUntilMs > System.currentTimeMillis()) return false
        return requestsToday.get() < (if (essential) TOTAL_CEILING else READ_BUDGET)
    }

    private fun countRequest() {
        ensureBudget()
        requestsToday.incrementAndGet()
        val context = com.kennyb1201.kbstream.data.addon.AppContextHolder.appContext
        context?.let {
            budgetPrefs(it).edit()
                .putString(KEY_REQUEST_DAY, requestsDay)
                .putInt(KEY_REQUEST_COUNT, requestsToday.get())
                .apply()
        }
    }

    /** 429 → stop calling until the allowance actually returns. */
    private fun noteRateLimited(response: Response) {
        val body = runCatching { response.peekBody(200).string() }.getOrDefault("")
        val daily = body.contains("Daily API limit", ignoreCase = true)
        val now = System.currentTimeMillis()
        val until = if (daily) nextUtcMidnight(now) else now + 60_000L
        if (until <= limitedUntilMs) return
        limitedUntilMs = until
        Log.w(
            TAG,
            "MDBList rate limited (HTTP ${response.code}" +
                (if (daily) ", daily limit exceeded" else "") +
                ") — pausing MDBList calls for ${(until - now) / 1000}s"
        )
        // A pending scrobble/start retry would only spend more of a budget
        // that is already gone.
        cancelStartRetry()
    }

    private val budgetInterceptor = Interceptor { chain ->
        val request = chain.request()
        val essential = request.tag() === WRITE_TAG
        if (maySpend(essential)) {
            countRequest()
            val response = chain.proceed(request)
            if (response.code == 429) noteRateLimited(response)
            response
        } else {
            val limited = limitedUntilMs > System.currentTimeMillis()
            Log.i(
                TAG,
                "skipped ${request.url.encodedPath} — MDBList daily budget " +
                    "(${requestsToday.get()} sent" +
                    (if (limited) ", rate limited" else "") +
                    ")"
            )
            // Callers already fail soft on any non-2xx, and the status says
            // plainly that the budget, not the API, was the limit.
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(429)
                .message("KBStream local MDBList budget")
                .body("".toResponseBody(null))
                .build()
        }
    }

    private val client = BaseHttpClient.derived {
        // 6 s ceiling and the local budget interceptor stay this client's
        // own; the sockets and threads behind it are the process-wide ones.
        callTimeout(6, TimeUnit.SECONDS)
        addInterceptor(budgetInterceptor)
    }

    private const val BASE = "https://api.mdblist.com"
    private const val TAG = "MDBLIST"

    private const val WATCHED_AT_COMPLETE_THRESHOLD = 80.0

    // ── Watched-snapshot cache ────────────────────────────────────────
    // /sync/watched downloads the ENTIRE watch history (paginated). Detail
    // loads used to hit it inline on every series open, adding seconds of
    // latency the moment an API key was configured. The snapshot is now
    // cached in-process for a short TTL; scrobble/mark actions invalidate
    // it so badges never go stale within a session.
    // Was 5 minutes. The snapshot is a paginated download of the whole watch
    // history and the app re-read it on every detail/home load, so a shorter
    // TTL spends the free key's 1,000 requests/day on re-fetching data that
    // has not changed. A mark/unmark still invalidates it outright.
    private const val SNAPSHOT_TTL_MS = 20 * 60 * 1000L
    @Volatile private var cachedSnapshot: MdbListWatchedSnapshot? = null
    @Volatile private var cachedSnapshotAt = 0L

    /** Cached GET /sync/playback — asked for on every detail-screen open. */
    private const val PLAYBACK_TTL_MS = 5 * 60 * 1000L
    @Volatile private var cachedPlayback: List<MdbListPlaybackItem>? = null
    @Volatile private var cachedPlaybackAt = 0L

    /*
     * The API key that produced [cachedPlayback] — the SAME trap
     * [cachedSnapshotKey] closes for the watched snapshot, but here for the
     * paused sessions that become Continue Watching cards. This object is
     * process-wide while the key is PROFILE-scoped (scoped prefs), so without
     * the stamp a profile switch inside the 5-minute TTL served the incoming
     * profile the profile it just left's /sync/playback list: its kids'
     * paused episodes appeared on the other profile's Continue Watching rail
     * until the process restarted or the TTL happened to expire.
     */
    @Volatile private var cachedPlaybackKey = ""

    /*
     * The API key that produced [cachedSnapshot]. This object is
     * process-wide while the key is PROFILE-scoped (scoped prefs), so the
     * cache used to outlive a profile switch and answer the incoming
     * profile with the previous profile's ENTIRE watch history: its
     * started-shows set paints eye badges on the new profile's series and
     * its movies paint checkmarks, with both profiles' Simkl and MDBList
     * histories clean, because the data never came from either account.
     * Stamping the key makes that impossible — a different key always
     * refetches instead of being served from someone else's snapshot.
     */
    @Volatile private var cachedSnapshotKey = ""

    /**
     * Per-title audience ratings, keyed "<apiKey>|<mediaType>|<id>".
     *
     * [fetchRatings] was completely uncached: every Detail open spent one of
     * the free key's 1,000/day requests, and ratings move on the order of
     * days. Cached in memory like the watched snapshot and playback list; the
     * key is part of the cache key so a profile switch never serves the
     * previous profile's key's data.
     */
    private const val RATINGS_TTL_MS = 12 * 60 * 60 * 1000L
    private const val RATINGS_CACHE_MAX = 512
    private val ratingsCache =
        java.util.concurrent.ConcurrentHashMap<String, Pair<Long, MdbListRatings>>()

    // ── Disk cache ────────────────────────────────────────────────────
    // The three reads above were memory-only, so every cold start
    // re-downloaded them and spent the free key's 1,000/day budget on data
    // that has not changed. Persisted in the shared JSON cache table under
    // ACCOUNT-scoped keys (the apiKey is part of every key), so a profile
    // switch can never serve the other profile's blob. A unit test with no
    // app context simply gets no disk cache.
    private const val SNAPSHOT_DISK_TTL_MS = 6 * 60 * 60 * 1000L
    private const val PLAYBACK_DISK_TTL_MS = 30 * 60 * 1000L
    private const val RATINGS_DISK_TTL_MS = 7 * 24 * 60 * 60 * 1000L

    private val diskMoshi by lazy {
        Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    }
    private val ratingsJsonAdapter: JsonAdapter<MdbListRatings> by lazy {
        diskMoshi.adapter(MdbListRatings::class.java)
    }
    private val snapshotJsonAdapter: JsonAdapter<MdbListWatchedSnapshot> by lazy {
        diskMoshi.adapter(MdbListWatchedSnapshot::class.java)
    }
    private val playbackJsonAdapter: JsonAdapter<List<MdbListPlaybackItem>> by lazy {
        diskMoshi.adapter(
            Types.newParameterizedType(List::class.java, MdbListPlaybackItem::class.java)
        )
    }
    private val jsonCacheDao: TmdbJsonCacheDao?
        get() = AppContextHolder.appContext?.let { ctx ->
            runCatching { WatchHistoryDatabase.getInstance(ctx).tmdbJsonCacheDao() }
                .getOrNull()
        }

    private fun snapshotDiskKey(apiKey: String) = "mdblist:snapshot:$apiKey"
    private fun playbackDiskKey(apiKey: String) = "mdblist:playback:$apiKey"
    private fun ratingsDiskKey(apiKey: String, mediaType: String, id: String) =
        "mdblist:ratings:$apiKey:${mediaType.lowercase()}:$id"

    private val snapshotMutex = kotlinx.coroutines.sync.Mutex()

    /** Reads the user-pasted (or build-injected) key, or "" when unset. */    fun apiKey(context: Context): String =
        AppPreferences.getMdbListApiKey(context)

    /** True when scrobbling/sync calls should be attempted. */
    fun isConfigured(context: Context): Boolean =
        apiKey(context).isNotBlank()

    /**
     * Connection check for the Settings panel: GET /user answers the
     * account's username with a valid key and 403 "Invalid API key"
     * otherwise. Returns the username, or null when the key is rejected
     * (or the network fails — the error string distinguishes).
     */
    suspend fun verifyKey(context: Context): Pair<String?, String?> {
        val key = apiKey(context)
        if (key.isBlank()) return null to "No key pasted yet"
        return withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(
                    Request.Builder()
                        .url("$BASE/user?apikey=$key")
                        .get()
                        // Settings' connection check is user-initiated: it
                        // answers even when the day's reads are spent.
                        .tag(WRITE_TAG)
                        .build()
                ).execute().use { response ->
                    when {
                        response.isSuccessful -> {
                            val root = JSONObject(response.body?.string().orEmpty())
                            val name = root.optString("username", "")
                                .ifBlank { root.optString("name", "MDBList account") }
                            name to null
                        }
                        response.code == 401 || response.code == 403 ->
                            null to "Key rejected — check it on mdblist.com/settings"
                        else -> null to "MDBList error HTTP ${response.code}"
                    }
                }
            }.getOrElse { e ->
                null to (e.message ?: "Network error reaching mdblist.com")
            }
        }
    }

    /**
     * A numeric field that MDBList may send as a number OR as a string
     * ("8.8"), and may send as ""/"N/A". Anything unusable is null rather
     * than a NaN that would later format as "NaN".
     */
    private fun JSONObject.numberOrNull(name: String): Double? {
        val raw = opt(name) ?: return null
        val parsed = when (raw) {
            is Number -> raw.toDouble()
            is String -> raw.trim().toDoubleOrNull()
            else -> null
        }
        return parsed?.takeIf { it.isFinite() }
    }

    /**
     * The media route is `/{provider}/{type}/{id}` — the ratings arrive in
     * that response's `ratings` array, NOT on a `/ratings` sub-path.
     *
     * That sub-path is what this used to call
     * (`/movie/tt0111161/ratings`), which matches no route in the API
     * (the path shape is `/{media_provider}/{media_type}/{media_id}`), so
     * every request 404'd and the detail screen silently showed no ratings
     * for any title. The provider is chosen from the id's shape: IMDb ids
     * go through `imdb`, numeric ids through `tmdb`.
     */
    private fun mediaUrl(mediaId: String, mediaType: String, apiKey: String): String {
        val type = if (mediaType.lowercase() == "movie") "movie" else "show"
        val provider = if (mediaId.startsWith("tt")) "imdb" else "tmdb"
        return "$BASE/$provider/$type/$mediaId?apikey=$apiKey"
    }

    /**
     * MDBList ratings for a movie (type="movie") or a show (type="show").
     * Accepts an IMDb id ("tt0111161") or a numeric TMDB id.
     */
    suspend fun fetchRatings(
        mediaId: String,
        mediaType: String,
        apiKey: String
    ): MdbListRatings? = withContext(Dispatchers.IO) {
        val id = mediaId.trim()
        if (apiKey.isBlank() || id.isBlank()) return@withContext null
        if (!id.startsWith("tt") && id.toIntOrNull() == null) return@withContext null

        val cacheKey = "$apiKey|${mediaType.lowercase()}|$id"
        val now = System.currentTimeMillis()
        ratingsCache[cacheKey]?.let { (cachedAt, cached) ->
            if (now - cachedAt < RATINGS_TTL_MS) return@withContext cached
        }
        val diskKey = ratingsDiskKey(apiKey, mediaType, id)
        jsonCacheDao?.getByKey(diskKey)?.let { row ->
            if (now - row.updatedAt < RATINGS_DISK_TTL_MS) {
                runCatching { ratingsJsonAdapter.fromJson(row.json) }.getOrNull()
                    ?.takeIf { it.hasAny }
                    ?.let { cached ->
                        ratingsCache[cacheKey] = now to cached
                        return@withContext cached
                    }
            }
        }

        runCatching {
            val url = mediaUrl(id, mediaType, apiKey)
            client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (!response.isSuccessful) {
                    // Never silent: a routing/auth failure here is exactly what
                    // "the ratings row never shows up" looks like from outside.
                    Log.w(
                        TAG,
                        "ratings HTTP ${response.code} for $id (${mediaType.lowercase()})"
                    )
                    return@use null
                }
                val root = JSONObject(response.body?.string().orEmpty())
                val arr = root.optJSONArray("ratings")
                val entries = ArrayList<MdbListRatingEntry>(arr?.length() ?: 0)
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val node = arr.optJSONObject(i) ?: continue
                        val source = node.optString("source", "").trim()
                        if (source.isBlank()) continue
                        entries += MdbListRatingEntry(
                            source = source,
                            value = node.numberOrNull("value"),
                            score = node.numberOrNull("score")
                        )
                    }
                }
                val ratings = buildMdbListRatings(entries)
                if (ratings == null) {
                    Log.i(
                        TAG,
                        "ratings response had no usable sources for $id " +
                            "(entries=${entries.size})"
                    )
                }
                ratings
            }
        }.getOrNull().also { fetched ->
            // Only real answers are remembered. null is a failed or empty
            // lookup and must keep being retried, not pin "no ratings" for
            // the session.
            if (fetched != null && fetched.hasAny) {
                if (ratingsCache.size > RATINGS_CACHE_MAX) ratingsCache.clear()
                ratingsCache[cacheKey] = now to fetched
                runCatching {
                    jsonCacheDao?.upsert(
                        TmdbJsonCacheEntity(
                            key = diskKey,
                            json = ratingsJsonAdapter.toJson(fetched),
                            updatedAt = now
                        )
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Live scrobbling (Scrobble section) — mirrors the Simkl scrobble path
    // in NativePlayerActivity so one playback updates both trackers.
    //
    // POST /scrobble/start  — begin (or un-pause) a session; replaces any
    //                         existing session for the same movie/episode.
    // POST /scrobble/pause  — save progress; >= 80% marks watched.
    // POST /scrobble/stop   — end; >= 80% marks watched, below saves a
    //                         paused session (Continue Watching).
    // POST /scrobble/clear  — delete a paused session (Remove from rail).
    //
    // ── Losing the start to the player's own cancellation ────────────
    // The player mirrors scrobbles from the Simkl job, and every later
    // player event cancels that job. Simkl's call is the FIRST await in it
    // and the MDBList call is the second, so MDBList is the one that can be
    // cancelled before its request is ever sent — and playback start is
    // exactly when the events fire fastest (a stream that buffers toggles
    // playing -> buffering -> playing, and each toggle cancels the one
    // before it). A dropped `start` means no session on the dashboard for
    // the entire playback, which is half of the "live scrobbling never
    // shows up" report even before the payload was wrong.
    //
    // So a start that did not get through is retried from the client's OWN
    // scope, which the player cannot cancel. Bounded to a few attempts and
    // cancelled the moment the session ends, so a title that genuinely
    // cannot be scrobbled costs two extra requests at most.
    //
    // Deliberately NOT a periodic refresh: /scrobble/start *replaces* the
    // session, so re-sending it with a progress we can only hold stale by
    // would restart the session's clock against a frozen position. A
    // Trakt-style session is extrapolated server-side from the item's
    // runtime, so one accurate start per playback is the correct shape.

    private val START_RETRY_DELAYS_MS =
        longArrayOf(10_000L, 30_000L)

    private val sessionScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val sessionMutex = Mutex()

    /**
     * Serializes the scrobble POSTs themselves. A cancelled action can wake
     * up (see [postScrobble]) while the action that cancelled it is already
     * on the wire, and interleaving the two lets the OLDER state reach the
     * server last — a late "start" landing after the "stop" that ended
     * playback re-opens a session nobody ever closes.
     */
    private val scrobbleSendMutex = Mutex()

    @Volatile
    private var startRetryJob: Job? = null

    /** What a scrobble session points at (movie, or one show episode). */
    private data class ScrobbleTarget(
        val isMovie: Boolean,
        val imdbId: String?,
        val tmdbId: Int?,
        val season: Int?,
        val episode: Int?
    )

    /**
     * Re-posts [action] = "start" after a failure, on a scope the player
     * cannot cancel. Stops as soon as one attempt succeeds, or when the
     * session ends ([cancelStartRetry]) / the key is gone.
     */
    private suspend fun scheduleStartRetry(
        context: Context,
        target: ScrobbleTarget,
        progress: Double
    ) {
        val appContext = context.applicationContext
        // NonCancellable: this runs on the FAILURE path, which is reached
        // exactly when the caller's job is on its way out (the player cancels
        // the whole Simkl+MDBList job on the next playback event). The lock
        // below is a suspension point, so without this the retry that exists
        // to recover a lost start was itself skipped whenever it was needed.
        withContext(NonCancellable) {
            sessionMutex.withLock {
                startRetryJob?.cancel()
                startRetryJob = sessionScope.launch {
                    for (delayMs in START_RETRY_DELAYS_MS) {
                        delay(delayMs)
                        val apiKey = apiKey(appContext)
                        if (apiKey.isBlank()) return@launch
                        val body = scrobbleBody(
                            target.isMovie, target.imdbId, target.tmdbId,
                            target.season, target.episode, progress
                        ) ?: return@launch
                        if (postScrobble(apiKey, "start", body)) {
                            Log.i(TAG, "scrobble/start retry succeeded")
                            return@launch
                        }
                    }
                    Log.w(TAG, "scrobble/start gave up after retries")
                }
            }
        }
    }

    /** The playback moved on (pause / stop / clear): drop any pending retry. */
    private fun cancelStartRetry() {
        startRetryJob?.cancel()
        startRetryJob = null
    }

    //
    // Body (schema: POST /scrobble/{start,pause,stop}):
    //
    //   { movie: { ids: { imdb, tmdb } }, progress: 15 }
    //   { show:  { ids: { imdb, tmdb }, season: 1, episode: 2 }, progress: 10 }
    //
    // The episode target — `season`/`episode` OR the nested
    // `season.number`/`season.episode.number` — lives INSIDE the `show`
    // object, which is the flat form the schema documents. There is no
    // top-level `season`/`episode`: the schema's only declared body fields
    // are `movie`, `show` and `progress`, so sending them beside `show`
    // left the request looking like a show with no episode at all.
    //
    // (That is exactly what the old builder did: it put them inside an
    // `apply` block on the BODY object rather than on the target, so the
    // server could never resolve the episode. Movies were unaffected —
    // their body has no episode to lose — which is why movie scrobbles and
    // the separate /sync/watched writes kept working while series never
    // appeared as live now-playing sessions.)
    private fun scrobbleBody(
        isMovie: Boolean,
        imdbId: String?,
        tmdbId: Int?,
        season: Int?,
        episode: Int?,
        progress: Double
    ): JSONObject? {
        val ids = JSONObject()
        if (!imdbId.isNullOrBlank()) ids.put("imdb", imdbId)
        if (tmdbId != null && tmdbId > 0) ids.put("tmdb", tmdbId)
        if (ids.length() == 0) return null

        val target = JSONObject().put("ids", ids)
        if (!isMovie) {
            if (season != null) target.put("season", season)
            if (episode != null) target.put("episode", episode)
        }

        // `progress` is a whole percentage here, not a fraction and not a
        // high-precision double. MDBList validates it strictly — a fractional
        // value is rejected with HTTP 400 "progress: Ensure that there are no
        // more than 5 digits in total." — and the player passes a computed
        // position/length ratio scaled to 0-100, so the raw Double
        // (6.184509511134195) failed EVERY live scrobble/start|pause|stop.
        // That is why sessions never appeared in the dashboard while the
        // separate /sync/watched writes kept working. Round to whole percent;
        // the API has no sub-percent resolution to preserve.
        return JSONObject()
            .put(if (isMovie) "movie" else "show", target)
            .put("progress", mdblistScrobbleProgress(progress))
    }

    private suspend fun postScrobble(
        apiKey: String,
        action: String,
        body: JSONObject
    ): Boolean = withContext(NonCancellable + Dispatchers.IO) {
        // NonCancellable is the whole point of this function's shape. The
        // player mirrors scrobbles from the same job it scrobbles Simkl with,
        // and the very next playback event (the buffering -> playing toggle
        // on a slow start, a pause, the stop on exit) cancels that job. Simkl
        // is the first await in it and this is the second, so the mirror was
        // cancelled before its request was ever sent — and because the
        // cancellation surfaced from INSIDE here, it also unwound straight
        // past the caller's own start-retry: no session on the MDBList
        // dashboard for the whole playback. A one-shot progress write must
        // not be droppable by whoever happens to cancel its parent.
        scrobbleSendMutex.withLock {
            runCatching {
                val request = Request.Builder()
                    .url("$BASE/scrobble/$action?apikey=$apiKey")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    // A scrobble is a write: it goes out even when the day's
                    // refetchable reads are spent.
                    .tag(WRITE_TAG)
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(
                            TAG,
                            "scrobble/$action failed code=${response.code} " +
                                "body=$body resp=" +
                                response.body?.string().orEmpty().take(200)
                        )
                    } else {
                        // The body is logged too: "did the request even get
                        // sent, and with which ids" is the first question
                        // every "scrobbling doesn't show up" report needs.
                        // Log.i, not Log.d: -assumenosideeffects strips Log.d
                        // from release builds — exactly the build a
                        // "scrobbling doesn't show up" report comes from.
                        Log.i(TAG, "scrobble/$action ok body=$body")
                        // Only "stop"/"clear" can change the watched state;
                        // start/pause just move the playhead. Invalidating the
                        // snapshot on every one of them re-downloaded the
                        // ENTIRE paginated history per playback event, which
                        // was the biggest spender of the daily key.
                        if (action == "stop" || action == "clear") {
                            invalidateWatchedSnapshot()
                        }
                    }
                    response.isSuccessful
                }
            }.getOrDefault(false)
        }
    }

    /**
     * Start (or resume) a scrobble session. Returns true on HTTP 2xx.
     *
     * A failure here is retried in the background (see
     * [scheduleStartRetry]) so a start that lost the player's cancellation
     * race still creates the session.
     */
    suspend fun scrobbleStart(
        context: Context,
        isMovie: Boolean,
        imdbId: String?,
        tmdbId: Int?,
        season: Int? = null,
        episode: Int? = null,
        progress: Double
    ): Boolean {
        val apiKey = apiKey(context)
        if (apiKey.isBlank()) return false
        val body = scrobbleBody(isMovie, imdbId, tmdbId, season, episode, progress)
            ?: return false.also {
                // Neither id resolved (a TVDB-only parent whose TMDB lookup
                // failed): say so, instead of the silence that made this look
                // like "live scrobbling just doesn't work".
                Log.w(TAG, "scrobble/start skipped: no imdb/tmdb id")
            }
        val ok = postScrobble(apiKey, "start", body)
        if (ok) {
            cancelStartRetry()
        } else {
            scheduleStartRetry(
                context = context,
                target = ScrobbleTarget(isMovie, imdbId, tmdbId, season, episode),
                progress = progress
            )
        }
        return ok
    }

    /** Pause the session, saving progress (>= 80% marks watched server-side). */
    suspend fun scrobblePause(
        context: Context,
        isMovie: Boolean,
        imdbId: String?,
        tmdbId: Int?,
        season: Int? = null,
        episode: Int? = null,
        progress: Double
    ): Boolean {
        val apiKey = apiKey(context)
        if (apiKey.isBlank()) return false
        val body = scrobbleBody(isMovie, imdbId, tmdbId, season, episode, progress)
            ?: return false.also { Log.w(TAG, "scrobble/pause skipped: no imdb/tmdb id") }
        val ok = postScrobble(apiKey, "pause", body)
        cancelStartRetry()
        return ok
    }

    /**
     * Stop the session. The server marks the item watched at >= 80% and
     * otherwise keeps it as a paused (Continue Watching) session, so no
     * separate watched call is needed for player-initiated completions.
     */
    suspend fun scrobbleStop(
        context: Context,
        isMovie: Boolean,
        imdbId: String?,
        tmdbId: Int?,
        season: Int? = null,
        episode: Int? = null,
        progress: Double
    ): Boolean {
        val apiKey = apiKey(context)
        if (apiKey.isBlank()) return false
        val body = scrobbleBody(isMovie, imdbId, tmdbId, season, episode, progress)
            ?: return false.also { Log.w(TAG, "scrobble/stop skipped: no imdb/tmdb id") }
        val ok = postScrobble(apiKey, "stop", body)
        cancelStartRetry()
        return ok
    }

/**
     * Delete the paused scrobble session for a title (Continue Watching
     * removal). The clear endpoint targets the session's movie/episode —
     * same body shape as pause/stop minus progress — rather than taking a
     * session id, so it needs the same ids/season/episode the other
     * scrobble calls carry.
     */
    suspend fun scrobbleClear(
        context: Context,
        isMovie: Boolean,
        imdbId: String?,
        tmdbId: Int?,
        season: Int? = null,
        episode: Int? = null
    ): Boolean {
        val apiKey = apiKey(context)
        if (apiKey.isBlank()) return false
        val body = scrobbleBody(isMovie, imdbId, tmdbId, season, episode, 0.0)
            ?: return false
        body.remove("progress")
        val ok = postScrobble(apiKey, "clear", body)
        cancelStartRetry()
        return ok
    }

    // ------------------------------------------------------------------
    // Sync section — direct history writes and paused-session reads.
    // ------------------------------------------------------------------

    private fun idsNode(imdbId: String?, tmdbId: Int?): JSONObject? {
        val ids = JSONObject()
        if (!imdbId.isNullOrBlank()) ids.put("imdb", imdbId)
        if (tmdbId != null && tmdbId > 0) ids.put("tmdb", tmdbId)
        return if (ids.length() == 0) null else ids
    }

    /**
     * POST /sync/watched — record a completed movie ("movie") or a single
     * episode ("episode", needs season+episode). Watched-at is "now".
     */
    suspend fun pushWatched(
        context: Context,
        mediaType: String,
        imdbId: String?,
        tmdbId: Int?,
        season: Int? = null,
        episode: Int? = null
    ): Boolean {
        val apiKey = apiKey(context)
        if (apiKey.isBlank()) return false
        val ids = idsNode(imdbId, tmdbId) ?: return false
        val now = java.time.Instant.now().toString()

        val payload = when (mediaType.lowercase()) {
            "movie" -> JSONObject().put(
                "movies",
                JSONArray().put(JSONObject().put("ids", ids).put("watched_at", now))
            )
            "episode" -> episodeWatchedPayload(
                ids,
                season,
                listOfNotNull(episode?.takeIf { it >= 0 }),
                now
            )
                ?: return false
            else -> return false
        }

        return withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("$BASE/sync/watched?apikey=$apiKey")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(
                            TAG,
                            "sync/watched failed code=${response.code} " +
                                response.body?.string().orEmpty().take(200)
                        )
                    }
                    response.isSuccessful
                }
            }.getOrDefault(false)
        }
    }

    /**
     * POST /sync/watched — record a WHOLE show as watched from its show ids
     * alone (a `shows` entry with no seasons/episodes). MDBList expands that
     * server-side across the show's episodes, exactly like the ids-only
     * `shows` body [/sync/watched/remove] already relies on — so a poster
     * "Mark as Watched" on a series is one call, without the app having to
     * know the season/episode list first (which is the data a long-press on
     * a poster never has).
     */
    suspend fun pushWatchedShow(
        context: Context,
        imdbId: String?,
        tmdbId: Int?
    ): Boolean {
        val apiKey = apiKey(context)
        if (apiKey.isBlank()) return false
        val ids = idsNode(imdbId, tmdbId) ?: return false
        val payload = JSONObject().put(
            "shows",
            JSONArray().put(
                JSONObject()
                    .put("ids", ids)
                    .put("watched_at", java.time.Instant.now().toString())
            )
        )
        return postSync(apiKey, "$BASE/sync/watched?apikey=$apiKey", payload)
    }

    /**
     * Episode watch pushes/removes use the nested Trakt-compatible shape:
     * {shows: [{ids, seasons: [{number, episodes: [{number}]}]}]}. The
     * top-level `episodes` array on /sync/watched matches by episode-level
     * TMDB/TVDB ids, which the app never has — season/episode numbers on
     * the show's ids are what we can always provide.
     */
    private fun episodeWatchedPayload(
        showIds: JSONObject,
        season: Int?,
        episodes: List<Int>,
        watchedAt: String
    ): JSONObject? {
        if (season == null || season < 0 || episodes.isEmpty()) {
            return null
        }
        val episodeEntries = JSONArray()
        for (episode in episodes) {
            if (episode < 0) continue
            val episodeEntry = JSONObject().put("number", episode)
            if (watchedAt.isNotBlank()) {
                episodeEntry.put("watched_at", watchedAt)
            }
            episodeEntries.put(episodeEntry)
        }
        if (episodeEntries.length() == 0) return null
        val seasonEntry = JSONObject()
            .put("number", season)
            .put("episodes", episodeEntries)
        return JSONObject().put(
            "shows",
            JSONArray().put(
                JSONObject().put("ids", showIds).put(
                    "seasons",
                    JSONArray().put(seasonEntry)
                )
            )
        )
    }

    /** Shared OkHttp POST for the /sync/watched[-/remove] endpoints. */
    private suspend fun postSync(
        apiKey: String,
        url: String,
        payload: JSONObject
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                // Every /sync/watched, list and watchlist write lands here, and
                // every one of them is a state change the user asked for:
                // writes are never budget-skipped.
                .tag(WRITE_TAG)
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(
                        TAG,
                        "sync post failed code=${response.code} " +
                            response.body?.string().orEmpty().take(200)
                    )
                } else {
                    invalidateWatchedSnapshot()
                }
                response.isSuccessful
            }
        }.getOrDefault(false)
    }

    /**
     * POST /sync/watched — record several episodes of one season in a
     * single call (season mark / mark-previous mirrors). Watched-at is
     * "now" for every episode in the batch.
     */
    suspend fun pushWatchedEpisodes(
        context: Context,
        imdbId: String?,
        tmdbId: Int?,
        season: Int,
        episodes: List<Int>
    ): Boolean {
        val apiKey = apiKey(context)
        if (apiKey.isBlank()) return false
        val ids = idsNode(imdbId, tmdbId) ?: return false
        val payload = episodeWatchedPayload(
            ids,
            season,
            episodes,
            java.time.Instant.now().toString()
        ) ?: return false
        return postSync(apiKey, "$BASE/sync/watched?apikey=$apiKey", payload)
    }

    /**
     * POST /sync/watched/remove — clear several episodes of one season
     * in a single call (season unmark / mark-previous-unwatched mirrors).
     */
    suspend fun removeWatchedEpisodes(
        context: Context,
        imdbId: String?,
        tmdbId: Int?,
        season: Int,
        episodes: List<Int>
    ): Boolean {
        val apiKey = apiKey(context)
        if (apiKey.isBlank()) return false
        val ids = idsNode(imdbId, tmdbId) ?: return false
        val payload = episodeWatchedPayload(ids, season, episodes, "")
            ?: return false
        return postSync(
            apiKey,
            "$BASE/sync/watched/remove?apikey=$apiKey",
            payload
        )
    }

    /**
     * POST /sync/watched/remove — clear watched history for a movie or a
     * single episode (mark-as-unwatched mirror).
     */
    suspend fun removeWatched(
        context: Context,
        mediaType: String,
        imdbId: String?,
        tmdbId: Int?,
        season: Int? = null,
        episode: Int? = null
    ): Boolean {
        val apiKey = apiKey(context)
        if (apiKey.isBlank()) return false
        val ids = idsNode(imdbId, tmdbId) ?: return false

        val payload = when (mediaType.lowercase()) {
            "movie" -> JSONObject().put(
                "movies",
                JSONArray().put(JSONObject().put("ids", ids))
            )
            "episode" -> episodeWatchedPayload(
                ids,
                season,
                listOfNotNull(episode?.takeIf { it >= 0 }),
                ""
            )
                ?: return false
            else -> return false
        }

        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("$BASE/sync/watched/remove?apikey=$apiKey")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(request).execute().use { it.isSuccessful }
            }.getOrDefault(false)
        }
        if (ok) invalidateWatchedSnapshot()
        return ok
    }

    /**
     * POST /sync/watched/remove — clear a whole show from watch history
     * (ids-only body, no seasons/episodes needed).
     */
    suspend fun removeWatchedShow(
        context: Context,
        imdbId: String?,
        tmdbId: Int?
    ): Boolean {
        val apiKey = apiKey(context)
        if (apiKey.isBlank()) return false
        val ids = idsNode(imdbId, tmdbId) ?: return false
        val payload = JSONObject().put(
            "shows",
            JSONArray().put(JSONObject().put("ids", ids))
        )
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("$BASE/sync/watched/remove?apikey=$apiKey")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(request).execute().use { it.isSuccessful }
            }.getOrDefault(false)
        }
        if (ok) invalidateWatchedSnapshot()
        return ok
    }

    /**
     * GET /sync/playback — every paused playback session on the account.
     * Parsed leniently: movies carry {movie:{ids,title}}; episodes carry
     * {episode:{ids,title,...}, show:{ids}} with season/episode numbers on
     * the episode node when present.
     */
    suspend    fun getPlaybackSessions(context: Context): List<MdbListPlaybackItem> {
        val apiKey = apiKey(context)
        if (apiKey.isBlank()) return emptyList()
        cachedPlayback?.let { fresh ->
            if (
                cachedPlaybackKey == apiKey &&
                System.currentTimeMillis() - cachedPlaybackAt < PLAYBACK_TTL_MS
            ) {
                return fresh
            }
        }
        return withContext(Dispatchers.IO) {
            val diskKey = playbackDiskKey(apiKey)
            jsonCacheDao?.getByKey(diskKey)?.let { row ->
                if (System.currentTimeMillis() - row.updatedAt < PLAYBACK_DISK_TTL_MS) {
                    runCatching { playbackJsonAdapter.fromJson(row.json) }.getOrNull()
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { cached ->
                            cachedPlayback = cached
                            cachedPlaybackAt = System.currentTimeMillis()
                            cachedPlaybackKey = apiKey
                            return@withContext cached
                        }
                }
            }
            runCatching {
                val request = Request.Builder()
                    .url("$BASE/sync/playback?apikey=$apiKey")
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use emptyList()
                    val root = JSONArray(response.body?.string().orEmpty())
                    val out = mutableListOf<MdbListPlaybackItem>()
                    for (i in 0 until root.length()) {
                        val session = root.optJSONObject(i) ?: continue
                        val isMovie =
                            session.optString("type", "movie") == "movie" ||
                                session.optJSONObject("movie") != null
                        val node =
                            session.optJSONObject(if (isMovie) "movie" else "episode")
                                ?: session.optJSONObject("show")
                                ?: continue
                        val ids = node.optJSONObject("ids")
                        val imdbId = ids?.optString("imdb")
                            ?.takeIf { it.startsWith("tt") }
                        val tmdbId = ids?.optInt("tmdb", -1)?.takeIf { it > 0 }
                        if (imdbId == null && tmdbId == null) continue

                        val showNode = session.optJSONObject("show")
                        out += MdbListPlaybackItem(
                            sessionId = session.optInt("id", i),
                            progress = session.optDouble("progress", 0.0),
                            isMovie = isMovie,
                            imdbId = imdbId,
                            tmdbId = tmdbId,
                            title = node.optString("title", "")
                                .ifBlank {
                                    showNode?.optString("title", "").orEmpty().ifBlank { null }
                                },
                            updatedAtMs =
                                session.optLong("updated_at_ts", 0L)
                                    .takeIf { it > 0 }
                                    ?: runCatching {
                                        java.time.Instant.parse(
                                            session.optString("updated_at")
                                        ).toEpochMilli()
                                    }.getOrDefault(0L),
                            runtimeMinutes = session.optInt("runtime", 0),
                            season = if (isMovie) null
                            else node.optInt("season", -1).takeIf { it >= 0 }
                                ?: session.optInt("season", -1).takeIf { it >= 0 },
                            episode = if (isMovie) null
                            else node.optInt("number", -1).takeIf { it >= 0 }
                                ?: session.optInt("episode", -1).takeIf { it >= 0 }
                        )
                    }
                    out
                }
            }.getOrDefault(emptyList()).also { result ->
                if (result.isNotEmpty()) {
                    cachedPlayback = result
                    cachedPlaybackAt = System.currentTimeMillis()
                    cachedPlaybackKey = apiKey
                    val key = playbackDiskKey(apiKey)
                    val at = cachedPlaybackAt
                    runCatching {
                        jsonCacheDao?.upsert(
                            TmdbJsonCacheEntity(
                                key = key,
                                json = playbackJsonAdapter.toJson(result),
                                updatedAt = at
                            )
                        )
                    }
                }
            }
        }
    }

    /**
     * GET /sync/watched — full watched-history snapshot for the badge
     * layer. Parses the TOP-LEVEL movies/shows/episodes arrays (episodes
     * are not nested under shows in this API) and follows `cursor`
     * pagination so large histories aren't silently truncated at the
     * first 100-item page.
     */
    suspend fun getWatchedSnapshot(
        context: Context,
        forceRefresh: Boolean = false
    ): MdbListWatchedSnapshot {
        val apiKey = apiKey(context)
        if (apiKey.isBlank()) return MdbListWatchedSnapshot()

        // Fast path: a fresh cached snapshot answers instantly, so series
        // detail loads never block on the full-history download. It only
        // counts when the KEY matches: the cache is per object, the key is
        // per profile (see [cachedSnapshotKey]).
        val cached = cachedSnapshot
        if (!forceRefresh && cached != null &&
            cachedSnapshotKey == apiKey &&
            System.currentTimeMillis() - cachedSnapshotAt < SNAPSHOT_TTL_MS
        ) {
            return cached
        }

        // Disk layer: this downloads the WHOLE history page by page, so a
        // restart inside the disk window must not pay for it again.
        if (!forceRefresh) {
            val diskKey = snapshotDiskKey(apiKey)
            val diskHit = withContext(Dispatchers.IO) {
                jsonCacheDao?.getByKey(diskKey)?.let { row ->
                    if (System.currentTimeMillis() - row.updatedAt < SNAPSHOT_DISK_TTL_MS) {
                        runCatching { snapshotJsonAdapter.fromJson(row.json) }.getOrNull()
                            ?.takeIf { !it.isEmpty }
                    } else null
                }
            }
            if (diskHit != null) {
                cachedSnapshot = diskHit
                cachedSnapshotAt = System.currentTimeMillis()
                cachedSnapshotKey = apiKey
                return diskHit
            }
        }

        return snapshotMutex.withLock {
            // Re-check inside the lock: a parallel caller may have just
            // refreshed it while this one waited.
            val fresh = cachedSnapshot
            if (!forceRefresh && fresh != null &&
                cachedSnapshotKey == apiKey &&
                System.currentTimeMillis() - cachedSnapshotAt < SNAPSHOT_TTL_MS
            ) {
                return@withLock fresh
            }

            val movieKeys = mutableSetOf<String>()
            val startedShowKeys = mutableSetOf<String>()
            val episodeKeys = mutableSetOf<String>()

            val result = withContext(Dispatchers.IO) {
            runCatching {
                var cursor: String? = null
                var guard = 0
                do {
                    val url = buildString {
                        append("$BASE/sync/watched?apikey=$apiKey&limit=1000")
                        if (!cursor.isNullOrBlank()) {
                            append("&cursor=")
                            append(java.net.URLEncoder.encode(cursor, "UTF-8"))
                        }
                    }
                    val root = client.newCall(
                        Request.Builder().url(url).get().build()
                    ).execute().use { response ->
                        if (!response.isSuccessful) {
                            Log.w(
                                TAG,
                                "sync/watched failed code=${response.code} " +
                                    response.body?.string().orEmpty().take(200)
                            )
                            return@withContext MdbListWatchedSnapshot()
                        }
                        JSONObject(response.body?.string().orEmpty())
                    }

                    root.optJSONArray("movies")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val entry = arr.optJSONObject(i) ?: continue
                            val ids = entry.optJSONObject("movie")?.optJSONObject("ids")
                                ?: entry.optJSONObject("ids")
                                ?: continue
                            addKey(ids, movieKeys)
                        }
                    }

                    root.optJSONArray("shows")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val entry = arr.optJSONObject(i) ?: continue
                            val ids = entry.optJSONObject("show")?.optJSONObject("ids")
                                ?: entry.optJSONObject("ids")
                                ?: continue
                            // Show entries only prove progress; completed
                            // state comes from the episode entries.
                            addKey(ids, startedShowKeys)
                        }
                    }

                    root.optJSONArray("episodes")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val entry = arr.optJSONObject(i) ?: continue
                            val season = entry.optInt("season", -1)
                            val number = entry.optInt("number", entry.optInt("episode", -1))
                            val ids = entry.optJSONObject("ids")
                                ?: entry.optJSONObject("show")?.optJSONObject("ids")
                                ?: continue
                            if (season >= 0 && number >= 0) {
                                addKey(ids, episodeKeys, suffix = ":$season:$number")
                            }
                        }
                    }

                    cursor = root.optJSONObject("pagination")
                        ?.optString("next_cursor")
                        ?.takeIf { it.isNotBlank() }
                    guard += 1
                } while (cursor != null && guard < 50)

                MdbListWatchedSnapshot(
                    movieKeys = movieKeys,
                    startedShowKeys = startedShowKeys,
                    episodeKeys = episodeKeys
                )
            }.getOrDefault(MdbListWatchedSnapshot())
            }

            // Only cache successful non-empty fetches: an empty result from
            // a transient API failure must not blank the badges for 5 min.
            if (!result.isEmpty) {
                cachedSnapshot = result
                cachedSnapshotAt = System.currentTimeMillis()
                cachedSnapshotKey = apiKey
                val key = snapshotDiskKey(apiKey)
                val at = cachedSnapshotAt
                withContext(Dispatchers.IO) {
                    runCatching {
                        jsonCacheDao?.upsert(
                            TmdbJsonCacheEntity(
                                key = key,
                                json = snapshotJsonAdapter.toJson(result),
                                updatedAt = at
                            )
                        )
                    }
                }
            }
            result
        }
    }

    /**
     * Drops the cached watched snapshot AND the cached paused-session list so
     * the next read re-downloads both. Called after every successful
     * scrobble/mark/unmark, keeping badges and Continue Watching honest
     * without a TTL shorter than the network cost justifies.
     */
    fun invalidateWatchedSnapshot() {
        val snapshotKey = cachedSnapshotKey
        val playbackKey = cachedPlaybackKey
        cachedSnapshot = null
        cachedSnapshotAt = 0L
        cachedSnapshotKey = ""
        // Both blobs describe the WHOLE account, so a mark/unmark can move a
        // title between them: the 99%-watched leftover that just became
        // completed has to leave the paused-session list with it, otherwise
        // the card lingers on Continue Watching for the rest of the TTL.
        cachedPlayback = null
        cachedPlaybackAt = 0L
        cachedPlaybackKey = ""
        // Drop the persisted copies too, or the next read serves the just-
        // invalidated blob from disk for up to its (longer) disk TTL.
        if (snapshotKey.isNotBlank() || playbackKey.isNotBlank()) {
            val keys = buildList {
                if (snapshotKey.isNotBlank()) add(snapshotDiskKey(snapshotKey))
                if (playbackKey.isNotBlank()) add(playbackDiskKey(playbackKey))
            }
            sessionScope.launch {
                runCatching { jsonCacheDao?.deleteByKeys(keys) }
            }
        }
    }

    /**
     * Profile-switch isolation: MDBList auth is per-PROFILE (scoped prefs),
     * so every process-wide snapshot here can belong to a different account
     * once the user switches. [invalidateWatchedSnapshot] is the single drop
     * point for both blobs, so this is an alias with the switch-time name.
     */
    fun clearTransientCaches() {
        invalidateWatchedSnapshot()
    }

    // ------------------------------------------------------------------
    // Lists + Watchlist section — Library tab reads and writes.
    // ------------------------------------------------------------------

    /** Shared GET returning a parsed body string, or null when not 2xx. */
    private suspend fun getString(url: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                client.newCall(Request.Builder().url(url).get().build())
                    .execute()
                    .use { response ->
                        if (!response.isSuccessful) {
                            Log.w(
                                TAG,
                                "GET ${'$'}{url.substringBefore('?')} " +
                                    "failed code=${'$'}{response.code}"
                            )
                            null
                        } else {
                            response.body?.string()
                        }
                    }
            }.getOrNull()
        }

    /**
     * MDBList's list/watchlist items carry the artwork as `poster`, but the
     * field is not always an absolute URL: some payloads hand back a bare
     * TMDB path ("\/abc.jpg") and others name the key `poster_url` /
     * `poster_path`. A relative value loaded verbatim by the poster grid is
     * exactly the "MDBList rows have no poster" symptom, so normalize to an
     * absolute image URL (prefixed with TMDB's w500 base) and treat the
     * literal "null" string as absent.
     */
    private fun normalizePosterUrl(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotBlank() && !it.equals("null", true) }
            ?: return null
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        val path = if (value.startsWith("/")) value else "/$value"
        return "https://image.tmdb.org/t/p/w500$path"
    }

    private fun entryFromJson(obj: JSONObject, fallbackType: String): MdbListEntry? {
        val ids = obj.optJSONObject("ids")
        val imdbId = ids?.optString("imdb")?.takeIf { it.startsWith("tt") }
            ?: obj.optString("imdb_id", "").takeIf { it.startsWith("tt") }
        val tmdbId = ids?.optInt("tmdb", -1)?.takeIf { it > 0 }
            ?: obj.optInt("tmdb_id", -1).takeIf { it > 0 }
        val mediatype = obj.optString("mediatype", fallbackType)
        return MdbListEntry(
            title = obj.optString("title", "").ifBlank {
                obj.optString("name", "").ifBlank { null }
            },
            mediaType = if (mediatype.equals("show", true) || mediatype.equals("series", true)) {
                "series"
            } else {
                "movie"
            },
            year = obj.optInt("release_year", -1).takeIf { it > 0 }
                ?: obj.optInt("year", -1).takeIf { it > 0 },
            poster = normalizePosterUrl(
                obj.optString("poster", "").ifBlank {
                    obj.optString("poster_url", "").ifBlank {
                        obj.optString("poster_path", "")
                    }
                }
            ),
            imdbId = imdbId,
            tmdbId = tmdbId
        )
    }

    /**
     * GET /lists/user — the authenticated user's personal lists.
     */
    suspend fun getUserLists(context: Context): List<MdbListUserList> {
        val apiKey = apiKey(context)
        if (apiKey.isBlank()) return emptyList()
        val body = getString("$BASE/lists/user?apikey=$apiKey") ?: return emptyList()
        return runCatching {
            val arr = JSONArray(body)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = obj.optInt("id", -1)
                if (id <= 0) return@mapNotNull null
                MdbListUserList(
                    id = id,
                    name = obj.optString("name", "").ifBlank { "List ${'$'}id" },
                    itemCount = obj.optInt("items", 0)
                )
            }
        }.getOrDefault(emptyList())
    }

    /**
     * GET /lists/{id}/items — titles in one personal list. Cursor
     * pagination is followed so large lists aren't truncated.
     */
    suspend fun getListItems(
        context: Context,
        listId: Int
    ): List<MdbListEntry> {
        val apiKey = apiKey(context)
        if (apiKey.isBlank()) return emptyList()

        val out = mutableListOf<MdbListEntry>()
        var cursor: String? = null
        var guard = 0
        do {
            val url = buildString {
                append("$BASE/lists/$listId/items?apikey=$apiKey&limit=1000")
                if (!cursor.isNullOrBlank()) {
                    append("&cursor=")
                    append(java.net.URLEncoder.encode(cursor, "UTF-8"))
                }
            }
            val body = getString(url) ?: break
            val parsed = runCatching {
                val root = JSONObject(body)
                cursor = root.optJSONObject("pagination")
                    ?.optString("next_cursor")
                    ?.takeIf { it.isNotBlank() }

                fun collect(key: String, fallbackType: String) {
                    root.optJSONArray(key)?.let { arr ->
                        for (i in 0 until arr.length()) {
                            arr.optJSONObject(i)?.let {
                                entryFromJson(it, fallbackType)?.let(out::add)
                            }
                        }
                    }
                }
                // List items can arrive combined or split by mediatype.
                collect("items", "movie")
                collect("movies", "movie")
                collect("shows", "series")
            }
            if (parsed.isFailure) break
            guard += 1
        } while (!cursor.isNullOrBlank() && guard < 30)
        return out
    }

    /**
     * POST /lists/{id}/items/add — add movies/shows (by imdb/tmdb ids)
     * to one personal list in a single call.
     */
    suspend fun addToList(
        context: Context,
        listId: Int,
        entries: List<MdbListEntry>
    ): Boolean {
        val apiKey = apiKey(context)
        if (apiKey.isBlank() || entries.isEmpty()) return false
        val (movies, shows) = entriesPayload(entries)
        val payload = JSONObject()
        if (movies.length() > 0) payload.put("movies", movies)
        if (shows.length() > 0) payload.put("shows", shows)
        if (payload.length() == 0) return false
        return postSync(
            apiKey,
            "$BASE/lists/$listId/items/add?apikey=$apiKey",
            payload
        )
    }

    /** POST /lists/{id}/items/remove — remove entries from a list. */
    suspend fun removeFromList(
        context: Context,
        listId: Int,
        entries: List<MdbListEntry>
    ): Boolean {
        val apiKey = apiKey(context)
        if (apiKey.isBlank() || entries.isEmpty()) return false
        val (movies, shows) = entriesPayload(entries)
        val payload = JSONObject()
        if (movies.length() > 0) payload.put("movies", movies)
        if (shows.length() > 0) payload.put("shows", shows)
        if (payload.length() == 0) return false
        return postSync(
            apiKey,
            "$BASE/lists/$listId/items/remove?apikey=$apiKey",
            payload
        )
    }

    /** POST /lists/user/add — create a new personal list. */
    suspend fun createList(
        context: Context,
        name: String
    ): MdbListUserList? {
        val apiKey = apiKey(context)
        if (apiKey.isBlank()) return null
        val payload = JSONObject().put("name", name)
        return withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("$BASE/lists/user/add?apikey=$apiKey")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "lists/user/add failed code=${'$'}{response.code}")
                        return@use null
                    }
                    val root = JSONObject(response.body?.string().orEmpty())
                    val id = root.optInt("id", -1)
                    if (id <= 0) null
                    else MdbListUserList(
                        id = id,
                        name = root.optString("name", name),
                        itemCount = 0
                    )
                }
            }.getOrNull()
        }
    }

    /**
     * GET /watchlist/items — the account's MDBList watchlist.
     */
    suspend fun getWatchlist(context: Context): List<MdbListEntry> {
        val apiKey = apiKey(context)
        if (apiKey.isBlank()) return emptyList()
        val body = getString("$BASE/watchlist/items?apikey=$apiKey&limit=1000")
            ?: return emptyList()
        return runCatching {
            val root = JSONObject(body)
            val out = mutableListOf<MdbListEntry>()
            fun collect(key: String, fallbackType: String) {
                root.optJSONArray(key)?.let { arr ->
                    for (i in 0 until arr.length()) {
                        arr.optJSONObject(i)?.let {
                            entryFromJson(it, fallbackType)?.let(out::add)
                        }
                    }
                }
            }
            collect("movies", "movie")
            collect("shows", "series")
            out
        }.getOrDefault(emptyList())
    }

    /** POST /watchlist/items/add — add to the MDBList watchlist. */
    suspend fun addToWatchlist(
        context: Context,
        entries: List<MdbListEntry>
    ): Boolean {
        val apiKey = apiKey(context)
        if (apiKey.isBlank() || entries.isEmpty()) return false
        val (movies, shows) = entriesPayload(entries)
        val payload = JSONObject()
        if (movies.length() > 0) payload.put("movies", movies)
        if (shows.length() > 0) payload.put("shows", shows)
        if (payload.length() == 0) return false
        return postSync(
            apiKey,
            "$BASE/watchlist/items/add?apikey=$apiKey",
            payload
        )
    }

    /** POST /watchlist/items/remove — remove from the watchlist. */
    suspend fun removeFromWatchlist(
        context: Context,
        entries: List<MdbListEntry>
    ): Boolean {
        val apiKey = apiKey(context)
        if (apiKey.isBlank() || entries.isEmpty()) return false
        val (movies, shows) = entriesPayload(entries)
        val payload = JSONObject()
        if (movies.length() > 0) payload.put("movies", movies)
        if (shows.length() > 0) payload.put("shows", shows)
        if (payload.length() == 0) return false
        return postSync(
            apiKey,
            "$BASE/watchlist/items/remove?apikey=$apiKey",
            payload
        )
    }

    /** Builds {movies:[{ids:{...}}],shows:[...]} from library entries. */
    private fun entriesPayload(
        entries: List<MdbListEntry>
    ): Pair<JSONArray, JSONArray> {
        val movies = JSONArray()
        val shows = JSONArray()
        entries.forEach { entry ->
            val ids = JSONObject()
            if (!entry.imdbId.isNullOrBlank()) ids.put("imdb", entry.imdbId)
            if (entry.tmdbId != null && entry.tmdbId > 0) ids.put("tmdb", entry.tmdbId)
            if (ids.length() == 0) return@forEach
            if (entry.mediaType.equals("series", true)) {
                shows.put(JSONObject().put("ids", ids))
            } else {
                movies.put(JSONObject().put("ids", ids))
            }
        }
        return movies to shows
    }

    /** Adds "tt123" and "tmdb:456" forms for [ids], optionally suffixed. */
    private fun addKey(
        ids: JSONObject,
        into: MutableSet<String>,
        suffix: String = ""
    ) {
        ids.optString("imdb").takeIf { it.startsWith("tt") }
            ?.let { into.add(it + suffix) }
        ids.optInt("tmdb", -1).takeIf { it > 0 }
            ?.let { into.add("tmdb:$it" + suffix) }
    }
}
