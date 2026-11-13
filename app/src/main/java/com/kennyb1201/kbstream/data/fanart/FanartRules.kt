package com.kennyb1201.kbstream.data.fanart

/**
 * fanart.tv's rules: the URLs, the pick order, the season match and the cache
 * TTLs - with no network, no JSON and no Android, so all of it is pinnable from
 * a JVM test.
 *
 * fanart.tv fills the gaps TMDB leaves (a clearlogo for a title TMDB has none
 * for, season posters at all, a backdrop where TMDB has none). It is NEVER a
 * first choice: every caller reaches it only after TMDB answered nothing, so the
 * pick order here only ever ranks fanart's own entries.
 *
 * One call per title returns every image type in one body, so there is no
 * per-type request and no per-type cache.
 */

/** API v3.2, the current surface. */
internal const val FANART_API_BASE = "https://webservice.fanart.tv/v3"

/** The disk-key prefix; bumping it drops every standing entry at once. */
internal const val FANART_CACHE_DISK_PREFIX = "fanart_art_v1:"

/**
 * How long a bundle that FOUND artwork is trusted: 12h in memory, 30 days on disk
 * - the same pair the hero and entity-logo caches use, for the same reason (a
 * title's artwork changes about as often as TMDB gains an entry).
 */
internal const val FANART_MEMORY_TTL_MS = 12L * 60L * 60L * 1000L
internal const val FANART_DISK_TTL_MS = 30L * 24L * 60L * 60L * 1000L

/** A proven "fanart holds nothing for this title" is remembered for a day. */
internal const val FANART_MISS_TTL_MS = 24L * 60L * 60L * 1000L

/** A FAILED call is remembered for an hour - never mistaken for an answer. */
internal const val FANART_FAILURE_TTL_MS = 60L * 60L * 1000L

/** Session memo bound, matching the entity-logo cache's. */
internal const val FANART_MEMORY_MAX_ENTRIES = 128

/** The two id spaces fanart's v3 endpoint accepts, one each. */
internal enum class FanartKind {
    /** `/v3/movies/{tmdbId}` */
    Movie,

    /** `/v3/tv/{tvdbId}` */
    Tv
}

/** One image entry: `{ id, url, lang, likes, season? }`. */
internal data class FanartImage(
    val url: String,
    val lang: String? = null,
    val likes: Int = 0,
    /** `seasonposter` only: the season this poster is for, or null. */
    val season: String? = null
)

/**
 * One title's whole artwork bundle - what a single `/v3` call returns, grouped by
 * what a consumer can use.
 */
internal data class FanartArt(
    val logos: List<FanartImage> = emptyList(),
    val backgrounds: List<FanartImage> = emptyList(),
    val backgrounds4k: List<FanartImage> = emptyList(),
    val seasonPosters: List<FanartImage> = emptyList()
) {
    val isEmpty: Boolean
        get() = logos.isEmpty() &&
            backgrounds.isEmpty() &&
            backgrounds4k.isEmpty() &&
            seasonPosters.isEmpty()
}

/** The memory/disk key for one title's bundle. One spelling, one prefix. */
internal fun fanartCacheKey(kind: FanartKind, id: Int): String {
    val space = if (kind == FanartKind.Movie) "movie" else "tv"
    return "$FANART_CACHE_DISK_PREFIX$space:$id"
}

/**
 * The movies endpoint: by TMDB id, which every movie in this app already has.
 *
 * `client_key` is the USER's own fanart.tv key from Settings, and it is sent only
 * when they entered one: it is what buys the 2-day delay instead of the 7-day
 * project-key delay. It is a query parameter because that is the API's own shape;
 * it never goes in the build, in a log line, or anywhere but this URL.
 */
internal fun fanartMoviesUrl(tmdbId: Int, apiKey: String, clientKey: String?): String =
    fanartUrl(path = "movies", id = tmdbId.toString(), apiKey = apiKey, clientKey = clientKey)

/**
 * The TV endpoint: by TVDB id, which the detail load already resolves alongside
 * the IMDb id (`TmdbRepository`'s `/external_ids`), so this needs no new id work.
 */
internal fun fanartTvUrl(tvdbId: Int, apiKey: String, clientKey: String?): String =
    fanartUrl(path = "tv", id = tvdbId.toString(), apiKey = apiKey, clientKey = clientKey)

internal fun fanartUrl(
    path: String,
    id: String,
    apiKey: String,
    clientKey: String?
): String {
    val query = buildString {
        append("api_key=").append(fanartEncode(apiKey))
        clientKey?.takeIf { it.isNotBlank() }?.let { personal ->
            append("&client_key=").append(fanartEncode(personal))
        }
    }
    return "$FANART_API_BASE/$path/$id?$query"
}

/**
 * The order every pick uses: English first, then a language-neutral entry, then
 * most liked, then the order the API sent.
 *
 * The same shape TMDB's own logo pick has, and for the same reason: fanart holds
 * artwork in many languages, and a foreign wordmark on an English card is the
 * hazard the TMDB filter was added to fix. A title with no English and no neutral
 * art still gets a logo (some is better than none) - it is only ranked last.
 */
internal fun pickFanartImage(images: List<FanartImage>): FanartImage? =
    images
        .sortedWith(
            compareBy(
                { languageRank(it.lang) },
                { -it.likes }
            )
        )
        .firstOrNull()

/** 0 = English, 1 = no language stated, 2 = anything else. */
internal fun languageRank(lang: String?): Int = when {
    lang.isNullOrBlank() -> 1
    lang.equals("en", ignoreCase = true) -> 0
    lang == "00" -> 1
    else -> 2
}

/**
 * The poster for exactly [season], or null.
 *
 * fanart's `seasonposter` entries carry a `season` field which can be a NUMBER, a
 * numeric string, or the literal `"all"`. `"all"` is not a season and is
 * excluded; season 0 IS a season (Specials), which is why every comparison here
 * is null-based rather than truthiness-based - `if (season)` would drop Specials.
 *
 * An exact match only: a request for one season never answers with another
 * season's poster, and a show with no season art answers nothing at all (the
 * caller then falls back to TMDB's season poster, and then to no poster). The
 * show's own poster is never substituted - it says nothing about the season.
 */
internal fun fanartSeasonPoster(images: List<FanartImage>, season: Int): FanartImage? {
    val matching = images.filter { image ->
        val value = image.season?.trim()?.lowercase()
        if (value.isNullOrBlank() || value == "all") {
            false
        } else {
            value.toIntOrNull() == season
        }
    }
    return pickFanartImage(matching)
}

/**
 * A `bigpreview` URL (400px) for thumbs and grids.
 *
 * fanart's full images are 1920x1080 or larger; a 100dp season thumb has no use
 * for one. The preview hosts are the same path with `/fanart/` swapped, so this
 * is a string edit rather than a second request.
 */
internal fun fanartBigPreviewUrl(url: String): String =
    url.replace("/fanart/", "/bigpreview/")

/** The same, at 200px, for anything smaller than a thumb. */
internal fun fanartPreviewUrl(url: String): String =
    url.replace("/fanart/", "/preview/")

/**
 * Percent-encoding for a query value, kept here (rather than through
 * `android.net.Uri`) so the URL builders stay JVM-testable. A fanart key is
 * alphanumeric, so only the general rule matters.
 */
internal fun fanartEncode(value: String): String {
    val out = StringBuilder(value.length + 8)
    for (byte in value.toByteArray(Charsets.UTF_8)) {
        val ch = byte.toInt().toChar()
        if (
            ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' ||
            ch == '-' || ch == '_' || ch == '.' || ch == '~'
        ) {
            out.append(ch)
        } else {
            out.append('%')
            out.append(HEX[(byte.toInt() shr 4) and 0xF])
            out.append(HEX[byte.toInt() and 0xF])
        }
    }
    return out.toString()
}

private const val HEX = "0123456789ABCDEF"
