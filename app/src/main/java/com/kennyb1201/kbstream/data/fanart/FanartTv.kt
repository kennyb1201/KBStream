package com.kennyb1201.kbstream.data.fanart

import com.kennyb1201.kbstream.BuildConfig
import com.kennyb1201.kbstream.data.network.BaseHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The fanart.tv client: one GET per title, one body with every image type.
 *
 * Deliberately small and blocking, like the other take-it-or-leave-it JSON
 * reads in this app (TMDB's, OMDb's, TVmaze's): a caller on a background
 * dispatcher calls [fetch] and gets an answer, a proven miss, or a failure it can
 * tell apart from the miss.
 *
 * ## Keys
 *
 * [FANART_PROJECT_KEY] is the PROJECT key (`api_key`): a build value, wired like
 * TMDB's. It is a public identifier by construction - it rides in every request
 * URL and in every published APK - so it is never named or handled as a secret.
 * The user's own `client_key` comes from Settings (see the fanart row) and is sent
 * only when they entered one: it buys the 2-day delay instead of 7 days.
 *
 * ## Failure is always soft
 *
 * Anything that is not a readable answer - no network, a 5xx, a 429, a body that
 * is not JSON - is [FanartResult.Failed]: the caller returns "no fanart art" to
 * the screen, never an exception. A 429 also parks the client for the window the
 * service asked for, so a burst of surfaces cannot hammer a key it has just
 * closed.
 */
internal object FanartTv {

    /** 8s: a lazy artwork lookup, never on a screen's blocking path. */
    private val client = BaseHttpClient.derived {
        callTimeout(8, TimeUnit.SECONDS)
    }

    /** The project key, exactly as TMDB's `api_key` is wired. */
    val projectKey: String
        get() = BuildConfig.FANART_API_KEY

    /** True when this build carries a project key at all. */
    val isConfigured: Boolean
        get() = projectKey.isNotBlank()

    /** How long a 429 parks the client when the response names no window. */
    private const val PARK_MS = 60_000L

    @Volatile
    private var parkedUntilMs = 0L

    /** Test hook: forget a parked window. */
    internal fun resetRateLimitForTest() {
        parkedUntilMs = 0L
    }

    /**
     * One title's artwork, or why there is none.
     *
     * [clientKey] is the viewer's own fanart.tv key, or null when they have not
     * entered one.
     */
    internal fun fetch(
        kind: FanartKind,
        id: Int,
        clientKey: String?,
        now: Long = System.currentTimeMillis()
    ): FanartResult {
        val apiKey = projectKey
        if (apiKey.isBlank()) return FanartResult.Failed
        if (id <= 0) return FanartResult.NotFound
        if (now < parkedUntilMs) return FanartResult.Failed

        val url = when (kind) {
            FanartKind.Movie -> fanartMoviesUrl(id, apiKey, clientKey)
            FanartKind.Tv -> fanartTvUrl(id, apiKey, clientKey)
        }

        return try {
            client.newCall(fanartRequest(url)).execute().use { response ->
                when {
                    response.code == 429 -> {
                        parkedUntilMs = now + parkMs(response.header("Retry-After"))
                        FanartResult.Failed
                    }
                    response.code == 404 -> FanartResult.NotFound
                    !response.isSuccessful -> FanartResult.Failed
                    else -> {
                        val body = response.body?.string().orEmpty()
                        val root = JSONObject(body)
                        val art = readFanartArt(root)
                        if (art.isEmpty) FanartResult.NotFound else FanartResult.Found(art)
                    }
                }
            }
        } catch (_: Exception) {
            FanartResult.Failed
        }
    }

    /**
     * The window a 429 asked for. `Retry-After` is seconds per the HTTP spec;
     * a missing or unreadable header uses [PARK_MS].
     */
    internal fun parkMs(retryAfterHeader: String?): Long {
        val seconds = retryAfterHeader?.trim()?.toLongOrNull()
        return seconds?.takeIf { it > 0L }?.let { it * 1_000L } ?: PARK_MS
    }

    /** The one request shape; the keys travel in the URL, nothing else does. */
    internal fun fanartRequest(url: String): Request =
        Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", "KBStream/" + BuildConfig.VERSION_NAME)
            .build()
}

/** What one fanart lookup produced - three different facts, kept apart. */
internal sealed interface FanartResult {
    /** The body carried at least one usable image. */
    data class Found(val art: FanartArt) : FanartResult

    /** The service answered and has nothing for this title: a proven miss. */
    data object NotFound : FanartResult

    /** No usable answer (no key, offline, 429, bad body): cache as a failure. */
    data object Failed : FanartResult
}

/**
 * Reads one `/v3` body into a bundle.
 *
 * Every list is optional: a title with only a backdrop is normal, and an empty
 * `[]` for a type is as common as an absent key. Unknown keys (character art,
 * discs, banners, thumbs) are ignored - the bundle parses only what a surface
 * consumes, and a field the service adds later cannot break the read.
 *
 * `clearlogo` is read beside the HD types because fanart uses it for the older
 * transparent logo entries; the pick rule then ranks English first and likes
 * after, so an HD English logo still wins.
 */
internal fun readFanartArt(root: JSONObject): FanartArt {
    fun images(vararg keys: String): List<FanartImage> =
        keys.flatMap { key -> readFanartImages(root.optJSONArray(key)) }

    return FanartArt(
        logos = images("hdmovielogo", "movielogo", "hdtvlogo", "clearlogo"),
        backgrounds = images("moviebackground", "showbackground"),
        backgrounds4k = images("movie4kbackground", "show4kbackground"),
        seasonPosters = images("seasonposter")
    )
}

/** One image array; a malformed entry is skipped, never fatal. */
internal fun readFanartImages(array: JSONArray?): List<FanartImage> {
    if (array == null) return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        val entry = array.optJSONObject(index) ?: return@mapNotNull null
        val url = entry.optString("url").trim()
        if (url.isEmpty()) return@mapNotNull null
        FanartImage(
            url = url,
            lang = entry.optString("lang").takeIf { it.isNotBlank() },
            likes = entry.optInt("likes", 0),
            season = entry.optString("season").takeIf { it.isNotBlank() }
        )
    }
}

/**
 * The bundle as the cache stores it: our own shape, so a read never depends on
 * fanart's field names and an entry written today still parses after the service
 * renames one.
 */
internal fun fanartArtToJson(art: FanartArt): String =
    JSONObject().apply {
        put("logos", fanartImagesToJson(art.logos))
        put("backgrounds", fanartImagesToJson(art.backgrounds))
        put("backgrounds4k", fanartImagesToJson(art.backgrounds4k))
        put("seasonPosters", fanartImagesToJson(art.seasonPosters))
    }.toString()

internal fun fanartArtFromJson(json: String): FanartArt? = runCatching {
    val root = JSONObject(json)
    FanartArt(
        logos = readFanartImages(root.optJSONArray("logos")),
        backgrounds = readFanartImages(root.optJSONArray("backgrounds")),
        backgrounds4k = readFanartImages(root.optJSONArray("backgrounds4k")),
        seasonPosters = readFanartImages(root.optJSONArray("seasonPosters"))
    )
}.getOrNull()

private fun fanartImagesToJson(images: List<FanartImage>): JSONArray =
    JSONArray().apply {
        images.forEach { image ->
            put(
                JSONObject().apply {
                    put("url", image.url)
                    image.lang?.let { put("lang", it) }
                    put("likes", image.likes)
                    image.season?.let { put("season", it) }
                }
            )
        }
    }
