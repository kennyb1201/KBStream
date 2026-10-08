package com.kennyb1201.kbstream.data.kb

import com.squareup.moshi.JsonClass

/**
 * KB collections-profile JSON model.
 *
 * A profile export is a top-level JSON array of collections; each collection
 * is a folder of "folders" (tiles), and each tile aggregates one or more
 * content [KBSource]s plus artwork URLs (covers, logos, hero backdrops)
 * the user hosts themselves — the app only ever points at those URLs.
 */
@JsonClass(generateAdapter = true)
data class KBCollectionProfile(
    val id: String? = null,
    val title: String = "",
    val folders: List<KBFolder> = emptyList(),
    val pinToTop: Boolean = false,
    // FOLLOW_LAYOUT renders opened folders as hero + rails (like Home);
    // ROWS renders them as a plain rail list.
    val viewMode: String? = null,
    val showAllTab: Boolean = false,
    val backdropImageUrl: String? = null,
    val focusGlowEnabled: Boolean = true
)

@JsonClass(generateAdapter = true)
data class KBFolder(
    val id: String? = null,
    val title: String = "",
    val sources: List<KBSource> = emptyList(),
    val hideTitle: Boolean = false,
    // LANDSCAPE / POSTER / SQUARE
    val tileShape: String? = null,
    val coverEmoji: String? = null,
    val focusGifUrl: String? = null,
    val heroVideoUrl: String? = null,
    val titleLogoUrl: String? = null,
    val coverImageUrl: String? = null,
    val catalogSources: List<KBCatalogSource> = emptyList(),
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
data class KBCatalogSource(
    val type: String? = null,
    val addonId: String? = null,
    val catalogId: String? = null,
    val genre: String? = null
) {
    /**
     * Convert to the generic source shape so newer exports that carry ONLY
     * catalogSources (no sources array) still load. provider=addon is the
     * same convention KB writes into the sources list for these.
     */
    fun toSource(): KBSource = KBSource(
        provider = "addon",
        addonId = addonId,
        catalogId = catalogId,
        type = type,
        genre = genre
    )
}

/**
 * One content source inside a folder. KB keeps every field present in
 * the export (nulls included), and mixes two providers:
 *  - tmdb: discover filters, a TMDB list, a TMDB collection, a company or a
 *    network (disambiguated by [tmdbSourceType], and by [tmdbId] being set)
 *  - addon: an installed Stremio addon's catalog ([addonId] + [catalogId])
 *
 * KB exports also carry "trakt" list sources; the app no longer reads them
 * (they need a paid Trakt API app), so such a source loads as an empty rail.
 */
@JsonClass(generateAdapter = true)
data class KBSource(
    val id: String? = null,
    val name: String? = null,
    val type: String? = null,
    val genre: String? = null,
    val title: String? = null,
    val sortBy: String? = null,
    val sortHow: String? = null,
    val tmdbId: Int? = null,
    val addonId: String? = null,
    val filters: KBFilters? = null,
    val provider: String? = null,
    val catalogId: String? = null,
    val mediaType: String? = null,
    val tmdbSourceType: String? = null
) {
    /** Source label: KB falls back through source name -> title -> provider. */
    fun displayLabel(): String =
        name?.takeIf { it.isNotBlank() }
            ?: title?.takeIf { it.isNotBlank() }
            ?: providerLabel()

    fun providerLabel(): String =
        when (provider?.lowercase()) {
            "tmdb" -> "TMDB"
            "addon" -> "Add-on"
            else -> provider ?: "Source"
        }
}

/**
 * The full KB TMDB filter builder dict. Every field mirrors a TMDB
 * /discover query parameter and may be null.
 *
 * [year] is typed loosely on purpose: KB exports it as a string
 * ("2026" or "1990-1999"), but hand-edited profiles exist where it is a
 * JSON number — [yearRange] normalizes both.
 */
@JsonClass(generateAdapter = true)
data class KBFilters(
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
    val withoutWatchProviders: String? = null,
    /**
     * Runtime window in MINUTES (`with_runtime.gte` / `.lte`). Null = any.
     * On TV this is the EPISODE runtime, which is the only runtime TMDB
     * reports for a series.
     */
    val withRuntimeGte: Int? = null,
    val withRuntimeLte: Int? = null,
    /**
     * Actor ids (`with_cast`, comma-separated). Actors only, not crew.
     *
     * Movie-only in TMDB: /discover/tv documents no person filter, so a series
     * catalog must not carry one (the builder clears it on the switch rather
     * than shipping a rule the endpoint ignores).
     */
    val withCast: String? = null,
    /**
     * Age rating. [certificationCountry] names the scale (TMDB
     * `certification_country`) and [certification] the value on it
     * (`certification`) - a US movie "PG-13", a US series "TV-14".
     *
     * The two travel together: a certification without its country is a value
     * TMDB cannot resolve, which is why the builder always writes both.
     */
    val certificationCountry: String? = null,
    val certification: String? = null,
    /**
     * An age-rating CEILING (TMDB `certification.lte`): "this rating or
     * milder".
     *
     * The exact-match [certification] cannot express that: TMDB reads it as
     * one value, so `certification=PG` EXCLUDES G - the mildest and safest
     * titles - which is the opposite of what "PG or milder" means. The
     * ceiling is what the "New Kids Movies" / "New Kids Shows" rails ask
     * for, and it reads against [certificationCountry] exactly as
     * [certification] does, because a rating only means something on one
     * country's scale.
     */
    val certificationLte: String? = null,
    /**
     * TV shape, TV-only. [withStatus] is TMDB's status code
     * (0 returning, 1 planned, 2 in production, 3 ended, 4 canceled, 5 pilot)
     * and [withType] its format code (0 documentary ... 4 scripted ...).
     */
    val withStatus: String? = null,
    val withType: String? = null,
    /** Networks to EXCLUDE (TV), as comma-separated network ids. */
    val withoutNetworks: String? = null,
    /**
     * Release types to require (TMDB `with_release_type`), comma-separated.
     *
     * "4" is a DIGITAL release - the type that means a title is watchable at
     * home rather than only in cinemas - and "2,3" is theatrical (limited |
     * wide). Movie-only: /discover/tv has no release-type filter, so a series
     * catalog must not carry one (the builder prunes it on the switch rather
     * than shipping a rule the endpoint ignores). The filter is resolved
     * against a REGION, so the catalog sends its watch region with it.
     */
    val withReleaseType: String? = null
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

/**
 * KB exports a "Top Rated" rail as a vote-AVERAGE sort. Averaging surfaces
 * obscure titles with a handful of 10/10 votes (heavily anime/shorts), which
 * is not what a "Top Rated" rail should show. Any vote-average sort is served
 * as "most voted" (vote_count.desc) instead — the rail keeps the collection's
 * own title, only the filter changes. Every other sort passes through.
 */
internal fun kbMostVotedSort(sortBy: String?): String? =
    when (sortBy?.trim()?.lowercase()) {
        "vote_average.desc", "top_rated", "top-rated" -> "vote_count.desc"
        else -> sortBy
    }

/** A normalized row of items loaded from any source kind (tmdb/addon). */
data class KBContentItem(
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
data class KBRail(
    val sourceId: String,
    val title: String,
    val providerLabel: String,
    val items: List<KBContentItem>
)
