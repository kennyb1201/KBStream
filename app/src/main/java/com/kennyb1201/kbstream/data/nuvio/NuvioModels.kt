package com.kennyb1201.kbstream.data.nuvio

import com.squareup.moshi.JsonClass

/**
 * Nuvio collections-profile JSON model.
 *
 * A profile export is a top-level JSON array of collections; each collection
 * is a folder of "folders" (tiles), and each tile aggregates one or more
 * content [NuvioSource]s plus artwork URLs (covers, logos, hero backdrops)
 * the user hosts themselves — the app only ever points at those URLs.
 */
@JsonClass(generateAdapter = true)
data class NuvioCollectionProfile(
    val id: String? = null,
    val title: String = "",
    val folders: List<NuvioFolder> = emptyList(),
    val pinToTop: Boolean = false,
    // FOLLOW_LAYOUT renders opened folders as hero + rails (like Home);
    // ROWS renders them as a plain rail list.
    val viewMode: String? = null,
    val showAllTab: Boolean = false,
    val backdropImageUrl: String? = null,
    val focusGlowEnabled: Boolean = true
)

@JsonClass(generateAdapter = true)
data class NuvioFolder(
    val id: String? = null,
    val title: String = "",
    val sources: List<NuvioSource> = emptyList(),
    val hideTitle: Boolean = false,
    // LANDSCAPE / POSTER / SQUARE
    val tileShape: String? = null,
    val coverEmoji: String? = null,
    val focusGifUrl: String? = null,
    val heroVideoUrl: String? = null,
    val titleLogoUrl: String? = null,
    val coverImageUrl: String? = null,
    val catalogSources: List<NuvioCatalogSource> = emptyList(),
    val focusGifEnabled: Boolean = false,
    val heroBackdropUrl: String? = null,
    val pinToTop: Boolean = false,
    val focusGlowEnabled: Boolean = true,
    // Copied from the parent profile at parse time (null in raw exports):
    // the folder screen renders with the collection's layout mode.
    val viewMode: String? = null,
    val showAllTab: Boolean = false
)

@JsonClass(generateAdapter = true)
data class NuvioCatalogSource(
    val type: String? = null,
    val addonId: String? = null,
    val catalogId: String? = null
)

/**
 * One content source inside a folder. Nuvio keeps every field present in
 * the export (nulls included), and mixes three providers:
 *  - tmdb: discover filters, a TMDB list, a TMDB collection, a company or a
 *    network (disambiguated by [tmdbSourceType], and by [tmdbId] being set)
 *  - trakt: a public list ([traktListId])
 *  - addon: an installed Stremio addon's catalog ([addonId] + [catalogId])
 */
@JsonClass(generateAdapter = true)
data class NuvioSource(
    val id: String? = null,
    val name: String? = null,
    val type: String? = null,
    val genre: String? = null,
    val title: String? = null,
    val sortBy: String? = null,
    val sortHow: String? = null,
    val tmdbId: Int? = null,
    val addonId: String? = null,
    val filters: NuvioFilters? = null,
    val provider: String? = null,
    val catalogId: String? = null,
    val mediaType: String? = null,
    val traktListId: Int? = null,
    val tmdbSourceType: String? = null
) {
    /** Source label: Nuvio falls back through source name -> title -> provider. */
    fun displayLabel(): String =
        name?.takeIf { it.isNotBlank() }
            ?: title?.takeIf { it.isNotBlank() }
            ?: providerLabel()

    fun providerLabel(): String =
        when (provider?.lowercase()) {
            "tmdb" -> "TMDB"
            "trakt" -> "Trakt"
            "addon" -> "Add-on"
            else -> provider ?: "Source"
        }
}

/**
 * The full Nuvio TMDB filter builder dict. Every field mirrors a TMDB
 * /discover query parameter and may be null.
 *
 * [year] is typed loosely on purpose: Nuvio exports it as a string
 * ("2026" or "1990-1999"), but hand-edited profiles exist where it is a
 * JSON number — [yearRange] normalizes both.
 */
@JsonClass(generateAdapter = true)
data class NuvioFilters(
    val year: Any? = null,
    val withGenres: String? = null,
    val watchRegion: String? = null,
    val voteCountGte: Int? = null,
    val withKeywords: String? = null,
    val withNetworks: String? = null,
    val withCompanies: String? = null,
    val withoutGenres: String? = null,
    val releaseDateGte: String? = null,
    val releaseDateLte: String? = null,
    val voteAverageGte: Int? = null,
    val voteAverageLte: Int? = null,
    val withoutKeywords: String? = null,
    val withoutCompanies: String? = null,
    val withOriginCountry: String? = null,
    val withWatchProviders: String? = null,
    val withOriginalLanguage: String? = null,
    val withoutWatchProviders: String? = null
) {
    /** "1990-1999" range support; null when no year filter is set. */
    fun yearRange(): Pair<String, String>? {
        val raw = when (val y = year) {
            is String -> y.trim()
            is Double -> if (y == y.toLong().toDouble()) y.toLong().toString() else y.toString()
            is Float -> if (y == y.toLong().toFloat()) y.toLong().toString() else y.toString()
            is Int, is Long -> y.toString()
            else -> ""
        }
        if (raw.isBlank()) return null
        return if (raw.contains('-')) {
            val parts = raw.split('-', limit = 2)
            val start = parts.getOrNull(0)?.trim().orEmpty()
            val end = parts.getOrNull(1)?.trim().orEmpty()
            if (start.isBlank() || end.isBlank()) {
                null
            } else {
                "$start-01-01" to "$end-12-31"
            }
        } else {
            "$raw-01-01" to "$raw-12-31"
        }
    }
}

/** A normalized row of items loaded from any source kind (tmdb/trakt/addon). */
data class NuvioContentItem(
    val id: String,
    val type: String,
    val title: String?,
    val posterUrl: String?,
    val backdropUrl: String?,
    val year: String?,
    val rating: Double?,
    val tmdbId: Int? = null,
    val overview: String? = null
)

/** A loaded rail inside a folder: one source -> one rail. */
data class NuvioRail(
    val sourceId: String,
    val title: String,
    val providerLabel: String,
    val items: List<NuvioContentItem>
)
