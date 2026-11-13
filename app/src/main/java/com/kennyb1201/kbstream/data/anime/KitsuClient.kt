package com.kennyb1201.kbstream.data.anime

import com.kennyb1201.kbstream.BuildConfig
import com.kennyb1201.kbstream.data.network.BaseHttpClient
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject

/**
 * Kitsu's read surface (spec 2026-11-13).
 *
 * JSON:API over GET, no key: the catalogue and a public profile's library are
 * both readable without an account. `kitsu.app/api/edge` is the API's current
 * home (`kitsu.io` still answers but the API moved), and `Accept:
 * application/vnd.api+json` is what it expects - a plain `*&#47;*` read usually
 * still parses, but naming the media type is the documented contract.
 *
 * RATE LIMITS are not published and no rate headers come back, which is not the
 * same as having none: the ceiling is treated as unspecified - one request a
 * second, page limit 20. The gate below is a plain one-second spacing, which is
 * what the clients that work against this API do.
 *
 * Its distinct value is the mapping graph: one Kitsu id fans out to MAL, AniList,
 * AniDB and TVDB (see [mappings]).
 */
internal object KitsuClient {

    const val USER_AGENT = "KBStream/" + BuildConfig.VERSION_NAME

    /** One literal for the JSON:API base - see `KitsuConfig.API_BASE`. */
    const val BASE_URL = com.kennyb1201.kbstream.data.kitsu.KitsuConfig.API_BASE

    /** Kitsu's page ceiling; also the default we ask for. */
    const val PAGE_LIMIT = 20

    private const val DEFAULT_TIMEOUT_MS = 8_000L

    /** The spacing the API is used at by clients that work: one request a second. */
    private const val MIN_SPACING_MS = 1_000L

    private val httpClient = BaseHttpClient.derived {
        callTimeout(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    private val gate = Mutex()
    private var lastRequestMs = 0L

    /** The first `GET /anime?filter[text]=` result for [showTitle], or null. */
    suspend fun searchAnime(showTitle: String): JSONObject? {
        val url =
            "$BASE_URL/anime?filter[text]=" + animeUrlEncode(showTitle.trim()) +
                "&page[limit]=1"
        return get(url)?.optJSONArray("data")?.optJSONObject(0)
    }

    /**
     * One anime's external mappings, keyed by Kitsu's `externalSite` string
     * (`myanimelist/anime`, `anilist/anime`, `thetvdb/anime`, ...).
     *
     * The `externalId` is a STRING on the wire even for a numeric id, so the
     * caller parses it. Empty on any failure - a title with no mappings simply
     * has nothing Kitsu can add.
     */
    suspend fun mappings(kitsuId: String): Map<String, String> {
        if (kitsuId.isBlank()) return emptyMap()

        val body = get("$BASE_URL/anime/" + animeUrlEncode(kitsuId) + "/mappings")
            ?: return emptyMap()
        val data = body.optJSONArray("data") ?: return emptyMap()

        val out = LinkedHashMap<String, String>(data.length())
        for (index in 0 until data.length()) {
            val attributes = data.optJSONObject(index)?.optJSONObject("attributes") ?: continue
            val site = attributes.optString("externalSite").trim()
            val id = attributes.optString("externalId").trim()
            if (site.isNotEmpty() && id.isNotEmpty()) out[site] = id
        }
        return out
    }

    /** The user id behind [username], or null when Kitsu has no such user. */
    suspend fun userIdByName(username: String): String? {
        val url = "$BASE_URL/users?filter[name]=" + animeUrlEncode(username.trim())
        return get(url)?.optJSONArray("data")?.optJSONObject(0)?.optString("id")?.takeIf {
            it.isNotBlank()
        }
    }

    /**
     * One page of a user's library entries.
     *
     * Public libraries answer without a token; a private one answers with an
     * empty `data` array, which is why the caller reports "private or empty"
     * rather than claiming the viewer tracks nothing.
     */
    suspend fun libraryEntries(userId: String, offset: Int = 0): JSONObject? {
        val url =
            // `include=anime` is not optional: an entry carries only the anime's
            // ID, so without it every card would need a second request per
            // title - twenty requests a page instead of one.
            "$BASE_URL/library-entries?filter[user_id]=" + animeUrlEncode(userId) +
                "&include=anime&page[limit]=$PAGE_LIMIT&page[offset]=$offset"
        return get(url)
    }

    /**
     * One `GET`, spaced a second apart from the last one.
     *
     * Null covers a transport error, a non-2xx and a body that is not JSON: every
     * caller reads it as "Kitsu has nothing to say", never as an empty list.
     */
    private suspend fun get(url: String): JSONObject? = withContext(Dispatchers.IO) {
        awaitSlot()

        val request =
            Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/vnd.api+json")
                .get()
                .build()

        runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                JSONObject(response.body?.string().orEmpty())
            }
        }.getOrNull()
    }

    private suspend fun awaitSlot() {
        gate.withLock {
            val now = System.currentTimeMillis()
            val waitMs = lastRequestMs + MIN_SPACING_MS - now
            if (waitMs > 0L) delay(waitMs)
            lastRequestMs = System.currentTimeMillis()
        }
    }

    /** Test hook: forgets the spacing state. */
    internal fun resetRateLimitForTest() {
        lastRequestMs = 0L
    }
}
