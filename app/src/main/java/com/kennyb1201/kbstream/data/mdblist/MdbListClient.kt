package com.kennyb1201.kbstream.data.mdblist

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.ui.settings.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

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
 * GET /{movie|show}/{imdbId}/ratings and live tracking via the Scrobble
 * (POST /scrobble/start|pause|stop|clear) and Sync (GET /sync/playback,
 * GET/POST /sync/watched[/remove]) sections. The key travels as the `apikey`
 * query parameter on every request.
 */
object MdbListClient {
    private val client = OkHttpClient.Builder()
        .callTimeout(6, TimeUnit.SECONDS)
        .build()

    private const val BASE = "https://api.mdblist.com"
    private const val TAG = "MDBLIST"

    private const val WATCHED_AT_COMPLETE_THRESHOLD = 80.0

    /** Reads the user-pasted (or build-injected) key, or "" when unset. */    fun apiKey(context: Context): String =
        AppPreferences.getMdbListApiKey(context)

    /** True when scrobbling/sync calls should be attempted. */
    fun isConfigured(context: Context): Boolean =
        apiKey(context).isNotBlank()

    private fun newEmptyObject(): JSONObject = JSONObject()

    /** MDBList ratings for a movie (type="movie") or a show (type="show"). */
    suspend fun fetchRatings(
        imdbId: String,
        mediaType: String,
        apiKey: String
    ): MdbListRatings? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || !imdbId.startsWith("tt")) return@withContext null
        val type = if (mediaType.lowercase() == "movie") "movie" else "show"
        val url = "$BASE/$type/$imdbId/ratings?apikey=$apiKey"
        runCatching {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val root = JSONObject(response.body?.string().orEmpty())

                // MDBList responses vary by endpoint version: the values can
                // sit flat on the root object or nested under a "ratings"
                // object. Read BOTH and prefer whichever carries data.
                val ratingsNode =
                    root.optJSONObject("ratings") ?: newEmptyObject()

                fun value(name: String): Double {
                    val v = ratingsNode.optDouble(name, Double.NaN)
                    return if (v.isNaN()) root.optDouble(name, Double.NaN) else v
                }

                fun has(name: String): Boolean = !value(name).isNaN()

                // x/10 sources render with one decimal ("8.5").
                fun score(name: String): String? =
                    if (!has(name)) null
                    else String.format(java.util.Locale.US, "%.1f", value(name))

                // 0-100 sources render as percent ("95%").
                fun percent(name: String): String? =
                    if (!has(name)) null
                    else "${value(name).toInt()}%"

                // Metacritic is conventionally shown as "78/100".
                fun metacritic(): String? =
                    if (!has("metacritic")) null
                    else "${value("metacritic").toInt()}/100"

                // MyAnimeList scores live on a 1-10 scale at the source, but
                // MDBList may normalize to 0-100; adapt to whichever arrives.
                fun myAnimeList(): String? =
                    if (!has("mal")) null
                    else if (value("mal") > 10.0) "${value("mal").toInt()}%"
                    else String.format(java.util.Locale.US, "%.1f", value("mal"))

                val ratings = MdbListRatings(
                    imdb = score("imdb"),
                    tmdb = score("tmdb"),
                    rottenTomatoes = percent("tomatoes"),
                    metacritic = metacritic(),
                    trakt = percent("trakt"),
                    letterboxd = percent("letterboxd"),
                    myAnimeList = myAnimeList()
                )

                if (ratings.hasAny) ratings else null
            }
        }.getOrNull()
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
    // Body: { movie: {ids:{imdb,tmdb}} | show: {ids:{...}}, season,
    //         episode, progress (0-100) }. Season/episode use the flat
    // form documented in the schema.
    // ------------------------------------------------------------------

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
        return JSONObject()
            .put(if (isMovie) "movie" else "show", target)
            .apply {
                if (!isMovie) {
                    if (season != null) put("season", season)
                    if (episode != null) put("episode", episode)
                }
                put("progress", progress)
            }
    }

    private suspend fun postScrobble(
        apiKey: String,
        action: String,
        body: JSONObject
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("$BASE/scrobble/$action?apikey=$apiKey")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(
                        TAG,
                        "scrobble/$action failed code=${response.code} " +
                            response.body?.string().orEmpty().take(200)
                    )
                }
                response.isSuccessful
            }
        }.getOrDefault(false)
    }

    /** Start (or resume) a scrobble session. Returns true on HTTP 2xx. */
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
            ?: return false
        return postScrobble(apiKey, "start", body)
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
            ?: return false
        return postScrobble(apiKey, "pause", body)
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
            ?: return false
        return postScrobble(apiKey, "stop", body)
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
        return postScrobble(apiKey, "clear", body)
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
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(
                        TAG,
                        "sync post failed code=${response.code} " +
                            response.body?.string().orEmpty().take(200)
                    )
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

        return withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("$BASE/sync/watched/remove?apikey=$apiKey")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(request).execute().use { it.isSuccessful }
            }.getOrDefault(false)
        }
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
        return withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url("$BASE/sync/watched/remove?apikey=$apiKey")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(request).execute().use { it.isSuccessful }
            }.getOrDefault(false)
        }
    }

    /**
     * GET /sync/playback — every paused playback session on the account.
     * Parsed leniently: movies carry {movie:{ids,title}}; episodes carry
     * {episode:{ids,title,...}, show:{ids}} with season/episode numbers on
     * the episode node when present.
     */
    suspend fun getPlaybackSessions(context: Context): List<MdbListPlaybackItem> {
        val apiKey = apiKey(context)
        if (apiKey.isBlank()) return emptyList()
        return withContext(Dispatchers.IO) {
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
            }.getOrDefault(emptyList())
        }
    }

    /**
     * GET /sync/watched — full watched-history snapshot for the badge
     * layer. Parses the TOP-LEVEL movies/shows/episodes arrays (episodes
     * are not nested under shows in this API) and follows `cursor`
     * pagination so large histories aren't silently truncated at the
     * first 100-item page.
     */
    suspend fun getWatchedSnapshot(context: Context): MdbListWatchedSnapshot {
        val apiKey = apiKey(context)
        if (apiKey.isBlank()) return MdbListWatchedSnapshot()

        val movieKeys = mutableSetOf<String>()
        val startedShowKeys = mutableSetOf<String>()
        val episodeKeys = mutableSetOf<String>()

        return withContext(Dispatchers.IO) {
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

    private fun entryFromJson(obj: JSONObject, fallbackType: String): MdbListEntry? {
        val ids = obj.optJSONObject("ids")
        val imdbId = ids?.optString("imdb")?.takeIf { it.startsWith("tt") }
        val tmdbId = ids?.optInt("tmdb", -1)?.takeIf { it > 0 }
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
            poster = obj.optString("poster", "").ifBlank { null },
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
