package com.kennyb1201.kbstream.data.anime

import com.kennyb1201.kbstream.BuildConfig
import com.kennyb1201.kbstream.data.network.BaseHttpClient
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * AniList's read surface (spec 2026-11-13).
 *
 * GraphQL over POST, no key: public catalogue data and a public profile's list
 * are both readable without an account, which is the whole of v1. Writes and
 * private lists need OAuth and are the next phase - nothing here sends a token.
 *
 * RATE LIMITS are the constraint that shapes this client. AniList documents 90
 * requests a minute, but live responses carry `x-ratelimit-limit: 30` (a degraded
 * mode), so the client plans for 30. Over the limit AniList answers 429 with a
 * `Retry-After` header and parks the caller for a minute; retrying inside that
 * window only extends it. So the client owns a sliding one-minute window and a
 * park, and every call goes through both - a caller can loop over titles as
 * naturally as it likes.
 *
 * The User-Agent names the app and the build, so a report pins the release.
 */
internal object AniListClient {

    const val USER_AGENT = "KBStream/" + BuildConfig.VERSION_NAME

    const val GRAPHQL_URL = "https://graphql.anilist.co"

    /** What the live API reports in degraded mode, not the documented 90. */
    const val MAX_REQUESTS_PER_MINUTE = 30

    private const val DEFAULT_TIMEOUT_MS = 8_000L

    /** Used when a 429 carries no `Retry-After`; AniList's own window is a minute. */
    private const val DEFAULT_PARK_MS = 60_000L

    private val JSON_MEDIA_TYPE = "application/json".toMediaType()

    private val httpClient = BaseHttpClient.derived {
        callTimeout(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    private val gate = Mutex()
    private val recentRequestsMs = ArrayDeque<Long>()

    @Volatile
    private var parkedUntilMs = 0L

    /**
     * The one query the ID hub and the Detail enrichment need.
     *
     * One call answers all of it - AniList id, MAL id, the community score and
     * the next airing episode - so a title is never asked about twice for two of
     * those facts.
     */
    const val MEDIA_BY_SEARCH_QUERY =
        "query (\$search: String) { Media(search: \$search, type: ANIME, isAdult: false) " +
            "{ id idMal averageScore title { romaji english native } " +
            "nextAiringEpisode { episode airingAt } } }"

    /**
     * A public profile's anime list, by status.
     *
     * `MediaListCollection` is the documented read for a user's list. Without a
     * token it answers only for a PUBLIC profile: a private one returns nothing,
     * and the caller reports that rather than pretending the list is empty.
     *
     * `lists` is AniList's `MediaListGroup[]`: the six status groups AND the
     * viewer's own custom lists (spec 2026-11-13 F1 - "the collection already
     * carries custom lists"). Its `name` and `isCustomList` are asked for here
     * because the Library names each group by that field; a status group's name
     * is "Watching"/"Planning"/..., a custom list's is whatever the viewer
     * called it. The group's `status` is still read as the fallback for a body
     * that omits `name`.
     */
    const val MEDIA_LIST_COLLECTION_QUERY =
        "query (\$username: String) { MediaListCollection(userName: \$username, type: ANIME) " +
            "{ lists { name isCustomList status entries { mediaId status progress score " +
            "media { id idMal title { romaji english } format episodes " +
            "nextAiringEpisode { episode airingAt } } } } } }"

    /** The `Media` object for [showTitle], or null when AniList has no answer. */
    suspend fun mediaByTitle(showTitle: String): JSONObject? {
        val variables = JSONObject().put("search", showTitle.trim())
        return post(MEDIA_BY_SEARCH_QUERY, variables)?.optJSONObject("data")
            ?.optJSONObject("Media")
    }

    /** The whole `MediaListCollection` for [username], or null on any failure. */
    suspend fun mediaListCollection(username: String): JSONObject? {
        val variables = JSONObject().put("username", username.trim())
        return post(MEDIA_LIST_COLLECTION_QUERY, variables)?.optJSONObject("data")
            ?.optJSONObject("MediaListCollection")
    }

    /**
     * The authenticated viewer (`Viewer { id name }`), or null.
     *
     * The connect's own validation: a pasted token is kept only when AniList
     * accepts it as a session, so the row never shows Connected for a value that
     * every write would refuse.
     */
    suspend fun viewer(accessToken: String): JSONObject? =
        when (val answer = authed(
            query = AniListWriteRules.VIEWER_QUERY,
            variables = JSONObject(),
            accessToken = accessToken
        )) {
            is AniListAnswer.Ok -> answer.json.optJSONObject("data")?.optJSONObject("Viewer")
            else -> null
        }

    /**
     * One authenticated GraphQL POST (phase 2, F3).
     *
     * It shares the READ client's one-minute window and its 429 park, because the
     * limit is per-token, not per-endpoint: a page of reads and a burst of writes
     * draw on the same 30-a-minute budget, so two independent gates would let the
     * app exceed it. The answer is classified rather than nulled, because a write
     * needs to know a 401 from a 429 from a network failure - those three mean
     * "reconnect", "wait" and "try again later" respectively.
     */
    suspend fun authed(
        query: String,
        variables: JSONObject,
        accessToken: String
    ): AniListAnswer =
        withContext(Dispatchers.IO) {
            if (accessToken.isBlank()) return@withContext AniListAnswer.Unauthorized
            awaitSlot()

            val body = JSONObject()
                .put("query", query)
                .put("variables", variables)
                .toString()

            val request =
                Request.Builder()
                    .url(GRAPHQL_URL)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .header("Authorization", "Bearer $accessToken")
                    .post(body.toRequestBody(JSON_MEDIA_TYPE))
                    .build()

            runCatching {
                httpClient.newCall(request).execute().use { response ->
                    when {
                        response.code == 401 || response.code == 403 ->
                            AniListAnswer.Unauthorized
                        response.code == 429 -> {
                            val until = AniListWriteRules.parkMs(
                                response.header("Retry-After"),
                                System.currentTimeMillis()
                            )
                            parkedUntilMs = until
                            AniListAnswer.Parked(until)
                        }
                        !response.isSuccessful ->
                            AniListAnswer.Failed("HTTP ${response.code}")
                        else -> {
                            val json = JSONObject(response.body?.string().orEmpty())
                            if (json.has("errors") && !json.has("data")) {
                                AniListAnswer.Failed("graphql error")
                            } else {
                                AniListAnswer.Ok(json)
                            }
                        }
                    }
                }
            }.getOrElse { error -> AniListAnswer.Failed(error.javaClass.simpleName) }
        }

    /** True when a 429 parked every call until [nowMs] has passed. */
    fun parked(nowMs: Long = System.currentTimeMillis()): Boolean = parkedUntilMs > nowMs

    /**
     * One GraphQL POST, rate-limited and parked on 429.
     *
     * Null covers every kind of failure - a transport error, a non-2xx, a body
     * that is not JSON, a GraphQL `errors` envelope - because every caller treats
     * them the same way: this source has nothing to say about that title.
     */
    private suspend fun post(query: String, variables: JSONObject): JSONObject? =
        withContext(Dispatchers.IO) {
            awaitSlot()

            val body = JSONObject()
                .put("query", query)
                .put("variables", variables)
                .toString()

            val request =
                Request.Builder()
                    .url(GRAPHQL_URL)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/json")
                    .post(body.toRequestBody(JSON_MEDIA_TYPE))
                    .build()

            runCatching {
                httpClient.newCall(request).execute().use { response ->
                    if (response.code == 429) {
                        park(response.header("Retry-After"))
                        return@use null
                    }
                    if (!response.isSuccessful) return@use null
                    val json = JSONObject(response.body?.string().orEmpty())
                    // A GraphQL error envelope carries `errors` and no `data`;
                    // treating it as "no answer" keeps a rate-limited or
                    // malformed query from reading as a real absence.
                    if (json.has("errors") && !json.has("data")) null else json
                }
            }.getOrNull()
        }

    /** Blocks until a request fits the sliding window and no park is in force. */
    private suspend fun awaitSlot() {
        gate.withLock {
            while (true) {
                val now = System.currentTimeMillis()

                if (now < parkedUntilMs) {
                    delay((parkedUntilMs - now).coerceAtMost(5_000L))
                    continue
                }

                while (recentRequestsMs.isNotEmpty() && now - recentRequestsMs.first() >= 60_000L) {
                    recentRequestsMs.removeFirst()
                }

                if (recentRequestsMs.size < MAX_REQUESTS_PER_MINUTE) {
                    recentRequestsMs.addLast(now)
                    return
                }

                // Full window: wait for the oldest request to age out.
                delay(1_000L)
            }
        }
    }

    /**
     * Parks the client after a 429.
     *
     * `Retry-After` is seconds in AniList's answers (it occasionally sends a
     * date); anything unreadable falls back to a minute, the window the API
     * itself names.
     */
    private fun park(retryAfter: String?) {
        val seconds = retryAfter?.trim()?.toLongOrNull()
        val waitMs = seconds?.takeIf { it > 0L }?.times(1_000L) ?: DEFAULT_PARK_MS
        parkedUntilMs = System.currentTimeMillis() + waitMs
    }

    /** Test hook: forgets the window and any park. */
    internal fun resetRateLimitForTest() {
        recentRequestsMs.clear()
        parkedUntilMs = 0L
    }
}

/**
 * What one authenticated GraphQL POST said (spec 2026-11-13 phase 2, F3).
 *
 * Four answers, because the caller acts differently on each: [Ok] carries the
 * envelope, [Unauthorized] means "mark the connection needs-reconnect and stop"
 * (AniList has no refresh to attempt), [Parked] means "wait out the window", and
 * [Failed] means "this attempt failed, the next may not".
 */
internal sealed interface AniListAnswer {
    data class Ok(val json: JSONObject) : AniListAnswer

    data object Unauthorized : AniListAnswer

    data class Parked(val untilMs: Long) : AniListAnswer

    data class Failed(val reason: String) : AniListAnswer
}
