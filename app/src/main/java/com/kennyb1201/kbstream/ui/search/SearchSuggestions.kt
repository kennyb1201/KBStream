package com.kennyb1201.kbstream.ui.search

import com.kennyb1201.kbstream.data.tmdb.TmdbSearchTitleResult
import com.kennyb1201.kbstream.data.tmdb.tmdbImage
import java.net.URLDecoder
import java.net.URLEncoder

/** One row the system's search UI draws for KBStream. */
internal data class SearchSuggestionRow(
    val id: Long,
    val title: String,
    val subtitle: String,
    /** Poster URL, when TMDB has one; the row draws fine without it. */
    val posterUrl: String?,
    /** What the framework launches when the row is picked. */
    val deepLink: String,
    val dataId: String
)

/**
 * How many rows the launcher is given. The system search UI shows a handful at
 * a time and every row costs a network round-trip inside one query call, so the
 * list is bounded rather than whatever TMDB returned.
 */
internal const val MAX_SEARCH_SUGGESTIONS = 8

/**
 * Turns TMDB's two search responses into the rows the TV launcher's global
 * search draws for this app.
 *
 * Two decisions worth stating, because both are the difference between a usable
 * suggestion list and a broken-feeling one:
 *
 *  1. **The two lists are interleaved, one for one.** TMDB ranks each list on
 *     its own, so appending the shows after the movies would hide every series
 *     behind a page of similarly-named films - and a bare title is one of the
 *     most common searches there is ("the office", "heat"). Alternating keeps
 *     the show next to its namesakes instead of under them.
 *  2. **Order inside each kind is TMDB's, not popularity's.** A search box is
 *     asking what the viewer typed, so relevance wins; re-sorting by votes would
 *     answer "play The Office" with whichever namesake is famous.
 *
 * A result with no name at all is dropped (there is nothing to draw), but no
 * artwork filter is applied here: unlike the single "play X" resolution there is
 * no wrong answer to avoid, only rows to fill.
 */
internal fun buildSearchSuggestions(
    movies: List<TmdbSearchTitleResult>,
    shows: List<TmdbSearchTitleResult>
): List<SearchSuggestionRow> =
    buildList {
        val rounds = maxOf(movies.size, shows.size)
        for (index in 0 until rounds) {
            shows.getOrNull(index)?.let { add("tv" to it) }
            movies.getOrNull(index)?.let { add("movie" to it) }
        }
    }
        .filter { (_, item) -> item.suggestionTitle().isNotBlank() }
        .take(MAX_SEARCH_SUGGESTIONS)
        .mapIndexed { index, (type, item) ->
            SearchSuggestionRow(
                id = index.toLong(),
                title = item.suggestionTitle(),
                subtitle = suggestionSubtitle(type, item),
                posterUrl = tmdbImage(item.posterPath, "w154"),
                deepLink = TitleDeepLink.build(type, item.id, item.suggestionTitle()),
                dataId = "$type:${item.id}"
            )
        }

/** The result's own name, whichever endpoint it came from. */
private fun TmdbSearchTitleResult.suggestionTitle(): String =
    (title ?: name).orEmpty().trim()

/** "TV · 2005" / "Movie · 2019", the year taken from whichever date it has. */
private fun suggestionSubtitle(type: String, item: TmdbSearchTitleResult): String {
    val year = (item.releaseDate ?: item.firstAirDate).orEmpty().take(4)
    val kind = if (type == "tv") "TV" else "Movie"
    return listOfNotNull(kind, year.takeIf { it.length == 4 }).joinToString(" \u00B7 ")
}

/**
 * The link a picked search suggestion carries.
 *
 * It names the title by its TMDB id rather than the IMDB one the detail screen
 * and the add-ons speak, because the id has to be produced inside the launcher's
 * query call: resolving IMDB ids for a whole suggestion list would be one
 * network round-trip per row. The tap pays that lookup instead, bounded, exactly
 * as a spoken title does (see [VoiceSearchActivity]) - and the title itself
 * rides along so a lookup that fails can still land on Search with the query
 * applied rather than on nothing.
 *
 * Parsed by hand rather than with `android.net.Uri` so this stays a pure JVM
 * rule: the launcher's tap is a path that must not depend on a device.
 */
internal object TitleDeepLink {

    private const val SCHEME = "kbstream"
    private const val HOST = "title"
    private const val QUERY_PARAM = "q"

    /** The kinds this link can name. Anything else is not a title. */
    private val KINDS = setOf("movie", "tv")

    internal data class Parsed(
        val type: String,
        val tmdbId: Int,
        /** The title as typed/found, for the fallback Search query. */
        val title: String?
    )

    fun build(type: String, tmdbId: Int, title: String?): String {
        val base = "$SCHEME://$HOST/$type/$tmdbId"
        val text = title?.trim().orEmpty()
        if (text.isEmpty()) return base
        return "$base?$QUERY_PARAM=" + URLEncoder.encode(text, "UTF-8")
    }

    /** The parsed link, or null when [data] is not one of ours. */
    fun parse(data: String?): Parsed? {
        val raw = data?.trim().orEmpty()
        val prefix = "$SCHEME://$HOST/"
        if (raw.length <= prefix.length) return null
        if (!raw.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)) return null

        val withoutPrefix = raw.substring(prefix.length)
        val path = withoutPrefix.substringBefore('?')
        val query = withoutPrefix.substringAfter('?', missingDelimiterValue = "")

        val parts = path.split('/').filter { it.isNotBlank() }
        val type = parts.getOrNull(0)?.lowercase()?.takeIf { it in KINDS } ?: return null
        val tmdbId = parts.getOrNull(1)?.toIntOrNull() ?: return null

        return Parsed(
            type = type,
            tmdbId = tmdbId,
            title = queryParam(query, QUERY_PARAM)
        )
    }

    /** One decoded `key=value` out of a raw query string, or null. */
    private fun queryParam(query: String, key: String): String? =
        query.split('&')
            .firstNotNullOfOrNull { pair ->
                val name = pair.substringBefore('=', "")
                if (name != key) return@firstNotNullOfOrNull null
                val value = pair.substringAfter('=', "")
                runCatching { URLDecoder.decode(value, "UTF-8") }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
            }
}
