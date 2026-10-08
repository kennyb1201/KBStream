package com.kennyb1201.kbstream.ui.addons

import com.kennyb1201.kbstream.data.catalogs.CATALOG_MEDIA_TV
import com.kennyb1201.kbstream.data.catalogs.CatalogSort
import com.kennyb1201.kbstream.data.catalogs.CustomCatalog
import com.kennyb1201.kbstream.data.kb.KBFilters
import com.kennyb1201.kbstream.ui.search.BROWSE_DECADES
import com.kennyb1201.kbstream.ui.search.BROWSE_GENRES
import com.kennyb1201.kbstream.ui.search.BROWSE_PROVIDER_ENTRIES
import com.kennyb1201.kbstream.ui.search.BROWSE_STUDIOS
import com.kennyb1201.kbstream.ui.search.BrowseEntry

// This file owns the Catalog Builder's PRESENTATION vocabulary: which chips each
// filter row offers, what a rule set reads like on the list row, and the two
// pure rules that keep a saved catalog valid (genre ids per media type, and
// dropping a genre a media-type switch invalidated).
//
// The options come from the Browse browser's own catalogs rather than a second
// hand-kept list, so a service or studio the viewer can browse to is the same
// service or studio they can build a catalog out of - one source, one truth.

/** One selectable chip in the builder. */
internal data class CatalogFilterOption(val id: Int, val label: String)

/** One selectable two-letter code (language, country, watch region). */
internal data class CatalogCodeOption(val code: String, val label: String)

/**
 * TMDB's movie genre ids (/genre/movie/list). "Action" (28) and "Sci-Fi"
 * (878) are MOVIE genres: there is no TV genre 28.
 */
internal val CATALOG_MOVIE_GENRE_IDS: Set<Int> = setOf(
    28, 12, 16, 35, 80, 99, 18, 10751, 14, 36, 27, 10402, 9648, 10749, 878,
    10770, 53, 10752, 37
)

/**
 * TMDB's TV genre ids (/genre/tv/list). The two action/adventure and sci-fi
 * families are SPLIT on TV (10759 / 10765), which is exactly why the builder
 * cannot offer one shared genre list and leave the media type to the loader.
 */
internal val CATALOG_TV_GENRE_IDS: Set<Int> = setOf(
    10759, 16, 35, 80, 99, 18, 10751, 10762, 9648, 10763, 10764, 10765, 10766,
    10767, 10768, 37
)

/** The genre ids TMDB accepts for [mediaType]. */
internal fun catalogGenreIdsFor(mediaType: String): Set<Int> =
    if (mediaType == CATALOG_MEDIA_TV) CATALOG_TV_GENRE_IDS else CATALOG_MOVIE_GENRE_IDS

/**
 * The genre chips the builder offers for [mediaType].
 *
 * Filtered, not merely labelled: picking "Action" (28) on a series catalog would
 * ask /discover/tv for a genre it does not have and come back empty, which reads
 * as "this catalog is broken" rather than as a rule mismatch.
 */
internal fun catalogGenreOptions(mediaType: String): List<CatalogFilterOption> {
    val allowed = catalogGenreIdsFor(mediaType)
    return BROWSE_GENRES
        .filter { it.id in allowed }
        .map { CatalogFilterOption(it.id, it.name) }
}

/** The display name of a genre id, or null when this build does not know it. */
internal fun catalogGenreLabel(id: Int): String? =
    BROWSE_GENRES.firstOrNull { it.id == id }?.name

/** Providers (services) the viewer can require, from the Browse catalog. */
internal val CATALOG_SERVICE_OPTIONS: List<CatalogFilterOption> =
    BROWSE_PROVIDER_ENTRIES
        .mapNotNull { entry ->
            entry.providerId?.let { provider -> CatalogFilterOption(provider, entry.name) }
        }
        .distinctBy { it.id }

/** Production companies, from the Browse catalog's studios. */
internal val CATALOG_STUDIO_OPTIONS: List<CatalogFilterOption> =
    BROWSE_STUDIOS.map { CatalogFilterOption(it.id, it.name) }

/**
 * Broadcast networks, from the Browse catalog's entries that carry no
 * watch-provider id (a network is not a place you stream, it is who made it).
 *
 * The id read here is the entry's OWN `id`, not `networkOrCompanyId`. A plain
 * network page keeps its TMDB network id in `id` and leaves the three provider
 * fields null (see [BrowseEntry]), so reading `networkOrCompanyId` matched only
 * the SERVICE entries that have no watch-provider id - which is why this row
 * used to offer a single chip while the Browse browser listed 77 networks. The
 * `-1` sentinel is the service-shaped fallback for an entry with no id at all.
 */
internal val CATALOG_NETWORK_OPTIONS: List<CatalogFilterOption> =
    BROWSE_PROVIDER_ENTRIES
        .mapNotNull { entry: BrowseEntry ->
            val isPlainNetwork =
                entry.providerId == null && !entry.networkIsCompany && entry.id > 0
            if (isPlainNetwork) CatalogFilterOption(entry.id, entry.name) else null
        }
        .distinctBy { it.id }

/** Decades, as single-select year-range chips ("1990s" -> "1990-1999"). */
internal val CATALOG_DECADE_OPTIONS: List<CatalogFilterOption> =
    BROWSE_DECADES.map { CatalogFilterOption(it.id, it.name) }

/**
 * Original languages the builder offers. A deliberately short list of the
 * languages an English-language media app's catalogue actually stars in, rather
 * than TMDB's full ISO 639-1 set: the field is a code, so a typo is a silently
 * empty catalog.
 */
internal val CATALOG_LANGUAGE_OPTIONS: List<CatalogCodeOption> = listOf(
    CatalogCodeOption("en", "English"),
    CatalogCodeOption("es", "Spanish"),
    CatalogCodeOption("fr", "French"),
    CatalogCodeOption("de", "German"),
    CatalogCodeOption("it", "Italian"),
    CatalogCodeOption("pt", "Portuguese"),
    CatalogCodeOption("ja", "Japanese"),
    CatalogCodeOption("ko", "Korean"),
    CatalogCodeOption("hi", "Hindi"),
    CatalogCodeOption("zh", "Chinese"),
    CatalogCodeOption("ru", "Russian"),
    CatalogCodeOption("sv", "Swedish"),
    CatalogCodeOption("da", "Danish"),
    CatalogCodeOption("no", "Norwegian")
)

/** Origin countries (production country), as ISO 3166-1 alpha-2 codes. */
internal val CATALOG_COUNTRY_OPTIONS: List<CatalogCodeOption> = listOf(
    CatalogCodeOption("US", "United States"),
    CatalogCodeOption("GB", "United Kingdom"),
    CatalogCodeOption("CA", "Canada"),
    CatalogCodeOption("AU", "Australia"),
    CatalogCodeOption("IE", "Ireland"),
    CatalogCodeOption("FR", "France"),
    CatalogCodeOption("DE", "Germany"),
    CatalogCodeOption("IT", "Italy"),
    CatalogCodeOption("ES", "Spain"),
    CatalogCodeOption("IN", "India"),
    CatalogCodeOption("JP", "Japan"),
    CatalogCodeOption("KR", "South Korea"),
    CatalogCodeOption("BR", "Brazil"),
    CatalogCodeOption("MX", "Mexico"),
    CatalogCodeOption("CN", "China"),
    CatalogCodeOption("SE", "Sweden")
)

/**
 * Watch regions, for the "where on this service" half of a provider filter.
 * TMDB resolves `with_watch_providers` against a region, so a provider rule is
 * meaningless without one - which is why the builder always carries a region
 * (defaulting to the same US region the Browse browser uses).
 */
internal val CATALOG_REGION_OPTIONS: List<CatalogCodeOption> = listOf(
    CatalogCodeOption("US", "United States"),
    CatalogCodeOption("GB", "United Kingdom"),
    CatalogCodeOption("CA", "Canada"),
    CatalogCodeOption("AU", "Australia"),
    CatalogCodeOption("IE", "Ireland"),
    CatalogCodeOption("DE", "Germany"),
    CatalogCodeOption("FR", "France"),
    CatalogCodeOption("ES", "Spain"),
    CatalogCodeOption("IT", "Italy"),
    CatalogCodeOption("BR", "Brazil"),
    CatalogCodeOption("MX", "Mexico"),
    CatalogCodeOption("IN", "India"),
    CatalogCodeOption("JP", "Japan")
)

/**
 * Year bounds for the explicit release window. 0 means "any".
 *
 * Coarse on purpose: this is a TV remote, and a day-precision date picker is
 * both unusable on one and unnecessary - "released since 2015" is the question
 * a viewer actually asks, and the DECADE row above answers "the 90s".
 */
internal val CATALOG_YEAR_BOUND_OPTIONS: List<Int> =
    listOf(0, 2026, 2020, 2015, 2010, 2000, 1990, 1980)

/** Minimum-rating chips. 0 means "any" and is written as no filter at all. */
internal val CATALOG_MIN_RATING_OPTIONS: List<Int> = listOf(0, 5, 6, 7, 8)

/** Maximum-rating chips; 0 means "any". */
internal val CATALOG_MAX_RATING_OPTIONS: List<Int> = listOf(0, 6, 7, 8, 9)

/** Minimum-vote-count chips. 0 means "any". */
internal val CATALOG_MIN_VOTE_OPTIONS: List<Int> = listOf(0, 50, 200, 500, 1000)

/**
 * Runtime window chips, in MINUTES (0 = "any").
 *
 * On a series this is the EPISODE runtime - the only runtime TMDB reports for
 * one - which is also the length that decides whether a title fits the evening.
 */
internal val CATALOG_RUNTIME_MIN_OPTIONS: List<Int> = listOf(0, 30, 45, 60, 90, 120)

internal val CATALOG_RUNTIME_MAX_OPTIONS: List<Int> = listOf(0, 60, 90, 120, 150, 180)

/**
 * The country whose age-rating scale a certification is read against.
 *
 * TMDB resolves `certification` per COUNTRY, so the two are one rule: a
 * certification with no country is a value the endpoint cannot place.
 */
/**
 * The scale the age-rating row opens on. TMDB resolves a certification against
 * a country, so one is always written with the value.
 */
internal const val CATALOG_DEFAULT_CERTIFICATION_COUNTRY = "US"

internal val CATALOG_CERTIFICATION_COUNTRY_OPTIONS: List<CatalogCodeOption> = listOf(
    CatalogCodeOption("US", "United States"),
    CatalogCodeOption("GB", "United Kingdom"),
    CatalogCodeOption("CA", "Canada"),
    CatalogCodeOption("AU", "Australia"),
    CatalogCodeOption("DE", "Germany"),
    CatalogCodeOption("FR", "France"),
    CatalogCodeOption("BR", "Brazil"),
    CatalogCodeOption("JP", "Japan")
)

/** US movie age ratings (the scale a /discover/movie certification is read on). */
internal val CATALOG_MOVIE_CERTIFICATIONS: List<CatalogCodeOption> = listOf(
    CatalogCodeOption("G", "G"),
    CatalogCodeOption("PG", "PG"),
    CatalogCodeOption("PG-13", "PG-13"),
    CatalogCodeOption("R", "R"),
    CatalogCodeOption("NC-17", "NC-17")
)

/** US TV age ratings. A separate scale, not a movie rating with a TV prefix. */
internal val CATALOG_TV_CERTIFICATIONS: List<CatalogCodeOption> = listOf(
    CatalogCodeOption("TV-Y", "TV-Y"),
    CatalogCodeOption("TV-Y7", "TV-Y7"),
    CatalogCodeOption("TV-G", "TV-G"),
    CatalogCodeOption("TV-PG", "TV-PG"),
    CatalogCodeOption("TV-14", "TV-14"),
    CatalogCodeOption("TV-MA", "TV-MA")
)

/**
 * The certification values TMDB accepts for [mediaType].
 *
 * Filtered per media type for the same reason the genre chips are: "TV-14"
 * asked of /discover/movie is not a rating TMDB has, and the empty page it
 * answers with reads as a broken catalog. The two ratings share one field, so
 * a media-type switch re-reads it against the new scale (see
 * [pruneMediaTypeFilters]).
 */
internal fun catalogCertificationOptions(
    mediaType: String
): List<CatalogCodeOption> =
    if (mediaType == CATALOG_MEDIA_TV) CATALOG_TV_CERTIFICATIONS else CATALOG_MOVIE_CERTIFICATIONS

/** Whether [value] is a rating on [mediaType]'s scale. */
internal fun isCatalogCertification(mediaType: String, value: String?): Boolean =
    value != null && catalogCertificationOptions(mediaType).any { it.code == value }

/** TMDB's TV status codes (0-5), as the menu should read them. */
internal val CATALOG_TV_STATUS_OPTIONS: List<CatalogCodeOption> = listOf(
    CatalogCodeOption("0", "Returning"),
    CatalogCodeOption("1", "Planned"),
    CatalogCodeOption("2", "In Production"),
    CatalogCodeOption("3", "Ended"),
    CatalogCodeOption("4", "Canceled"),
    CatalogCodeOption("5", "Pilot")
)

/** TMDB's TV type codes (0-6): what kind of series it is, not what genre. */
internal val CATALOG_TV_TYPE_OPTIONS: List<CatalogCodeOption> = listOf(
    CatalogCodeOption("0", "Documentary"),
    CatalogCodeOption("1", "News"),
    CatalogCodeOption("2", "Miniseries"),
    CatalogCodeOption("3", "Reality"),
    CatalogCodeOption("4", "Scripted"),
    CatalogCodeOption("5", "Show"),
    CatalogCodeOption("6", "Video")
)

/**
 * TMDB's release types, as `with_release_type` codes. Movie-only: /discover/tv
 * has no release-type filter, so a series catalog never carries one (the
 * media-type switch prunes it off - see [pruneMediaTypeFilters]).
 *
 * "Digital release" is the one that matters day to day: it marks the moment a
 * title is actually watchable at home rather than only in cinemas, and TMDB
 * models it as release type 4. Theatrical is 2|3 (limited | wide), spelled as
 * the comma-separated list TMDB accepts, and the empty code is "any release".
 * The filter is region-scoped, which is why a catalog sends its own watch
 * region with it (see TmdbRepository.discoverKB).
 */
internal val CATALOG_RELEASE_TYPE_OPTIONS: List<CatalogCodeOption> = listOf(
    CatalogCodeOption("", "Any release"),
    CatalogCodeOption("4", "Digital release"),
    CatalogCodeOption("5", "Physical release"),
    CatalogCodeOption("2,3", "Theatrical"),
    CatalogCodeOption("1", "Premiere"),
    CatalogCodeOption("6", "TV")
)

/** The sort chips, in menu order. */
internal val CATALOG_SORT_OPTIONS: List<CatalogSort> = CatalogSort.entries.toList()

/**
 * Applies a media-type switch to a catalog, PRUNING the genres that are not
 * valid for the new type.
 *
 * A series catalog that kept "Action" (28) would ask TMDB for a TV genre that
 * does not exist. Pruning on the switch means the builder can offer both media
 * types on one draft without ever writing a rule the new type cannot answer -
 * and the viewer sees the genre quietly leave the row rather than the catalog
 * silently going empty after the save.
 */
internal fun withCatalogMediaType(
    catalog: CustomCatalog,
    mediaType: String
): CustomCatalog {
    if (catalog.mediaType == mediaType) return catalog
    return catalog.copy(
        mediaType = mediaType,
        filters = pruneMediaTypeFilters(catalog.filters, mediaType)
    )
}

/**
 * Every rule a media-type switch invalidates, dropped.
 *
 * Wider than [pruneGenreFilters] because the post-genre filters have their own
 * per-type validity: `with_cast` is movie-only (TMDB's TV discover has no
 * person filter), `with_status` / `with_type` / `without_networks` are TV-only,
 * and a certification belongs to ONE scale - "TV-14" is not a movie rating, and
 * asking /discover/movie for it returns an empty page rather than ignoring it.
 * The certification COUNTRY goes with the value: kept alone it would be a scale
 * with nothing read against it.
 */
internal fun pruneMediaTypeFilters(
    filters: KBFilters,
    mediaType: String
): KBFilters {
    val pruned = pruneGenreFilters(filters, mediaType)
    val isTv = mediaType == CATALOG_MEDIA_TV
    val keptCertification = pruned.certification
        ?.takeIf { isCatalogCertification(mediaType, it) }
    // The ceiling is a value on the SAME country scale, so it is pruned with
    // the exact match: "TV-14 or milder" asked of /discover/movie is a rating
    // that scale does not have, and TMDB answers an empty page rather than
    // ignoring the rule.
    val keptCeiling = pruned.certificationLte
        ?.takeIf { isCatalogCertification(mediaType, it) }

    return pruned.copy(
        withCast = pruned.withCast?.takeIf { !isTv },
        // with_release_type exists on /discover/movie only, like with_cast.
        withReleaseType = pruned.withReleaseType?.takeIf { !isTv },
        withStatus = pruned.withStatus?.takeIf { isTv },
        withType = pruned.withType?.takeIf { isTv },
        withoutNetworks = pruned.withoutNetworks?.takeIf { isTv },
        certification = keptCertification,
        certificationLte = keptCeiling,
        // The scale stays while EITHER age rule is read against it: a catalog
        // whose only age rule is the ceiling would otherwise lose the country
        // the ceiling means something on.
        certificationCountry = pruned.certificationCountry
            ?.takeIf { keptCertification != null || keptCeiling != null }
    )
}

/** Drops from `withGenres` / `withoutGenres` every id invalid for [mediaType]. */
internal fun pruneGenreFilters(filters: KBFilters, mediaType: String): KBFilters {
    val allowed = catalogGenreIdsFor(mediaType)
    fun keepOnly(value: String?): String? {
        val kept = com.kennyb1201.kbstream.data.catalogs
            .splitIds(value)
            .filter { it in allowed }
        return kept.takeIf { it.isNotEmpty() }?.joinToString(",")
    }
    return filters.copy(
        withGenres = keepOnly(filters.withGenres),
        withoutGenres = keepOnly(filters.withoutGenres)
    )
}

/**
 * How many constraints a rule set carries. Shown on the list row so a catalog
 * the viewer forgot the rules of is readable at a glance.
 */
internal fun catalogRuleCount(filters: KBFilters): Int {
    val cleaned = com.kennyb1201.kbstream.data.catalogs.normalizedFilters(filters)
    var count = 0
    if (com.kennyb1201.kbstream.data.catalogs.splitIds(cleaned.withGenres).isNotEmpty()) count++
    if (com.kennyb1201.kbstream.data.catalogs.splitIds(cleaned.withoutGenres).isNotEmpty()) count++
    if (com.kennyb1201.kbstream.data.catalogs.splitIds(cleaned.withWatchProviders).isNotEmpty()) count++
    if (com.kennyb1201.kbstream.data.catalogs.splitIds(cleaned.withoutWatchProviders).isNotEmpty()) count++
    if (com.kennyb1201.kbstream.data.catalogs.splitIds(cleaned.withCompanies).isNotEmpty()) count++
    if (com.kennyb1201.kbstream.data.catalogs.splitIds(cleaned.withoutCompanies).isNotEmpty()) count++
    if (com.kennyb1201.kbstream.data.catalogs.splitIds(cleaned.withNetworks).isNotEmpty()) count++
    if (cleaned.withReleaseType != null) count++
    if (com.kennyb1201.kbstream.data.catalogs.splitIds(cleaned.withKeywords).isNotEmpty()) count++
    if (com.kennyb1201.kbstream.data.catalogs.splitIds(cleaned.withoutKeywords).isNotEmpty()) count++
    if (cleaned.year != null) count++
    if (cleaned.releaseDateGte != null) count++
    if (cleaned.releaseDateLte != null) count++
    if (com.kennyb1201.kbstream.data.catalogs.splitCodes(cleaned.withOriginalLanguage).isNotEmpty()) count++
    if (com.kennyb1201.kbstream.data.catalogs.splitCodes(cleaned.withOriginCountry).isNotEmpty()) count++
    if (cleaned.voteAverageGte != null) count++
    if (cleaned.voteAverageLte != null) count++
    if (cleaned.voteCountGte != null) count++
    if (cleaned.withRuntimeGte != null) count++
    if (cleaned.withRuntimeLte != null) count++
    if (com.kennyb1201.kbstream.data.catalogs.splitIds(cleaned.withCast).isNotEmpty()) count++
    if (cleaned.certification != null) count++
    if (cleaned.certificationLte != null) count++
    if (cleaned.withStatus != null) count++
    if (cleaned.withType != null) count++
    if (com.kennyb1201.kbstream.data.catalogs.splitIds(cleaned.withoutNetworks).isNotEmpty()) count++
    return count
}

/**
 * The one-line description of a catalog, used on the builder's list row and as
 * the editor's subtitle: media type, sort, and how many rules narrow it.
 */
internal fun catalogSummaryLine(catalog: CustomCatalog): String {
    val type = if (catalog.mediaType == CATALOG_MEDIA_TV) "Series" else "Movies"
    val sort = CatalogSort.fromId(catalog.sort).label
    val rules = catalogRuleCount(catalog.filters)
    val rulesText = when (rules) {
        0 -> "no filters"
        1 -> "1 filter"
        else -> "$rules filters"
    }
    return "$type \u00b7 $sort \u00b7 $rulesText"
}

/**
 * The genre names a rule set names, for the row's second line. Empty when the
 * catalog has no genre rule, so the caller can fall back to the rule count.
 */
internal fun catalogGenreSummary(filters: KBFilters): String =
    com.kennyb1201.kbstream.data.catalogs
        .splitIds(filters.withGenres)
        .mapNotNull { catalogGenreLabel(it) }
        .joinToString(", ")

// ---------------------------------------------------------------------------
// Hand-typed ids: the way past the shipped chip vocabulary.
// ---------------------------------------------------------------------------

/**
 * One id-list filter a hand-typed TMDB id can be added to.
 *
 * Every chip row here is a fixed vocabulary - what this app happens to ship -
 * and what a viewer wants is often not on it: a regional service, a niche
 * studio, a network from their own country, a watch provider added to TMDB
 * after this build. TMDB identify all of them by a number, so the escape hatch
 * is the number itself, and this enum is what says which filter it lands in.
 */
internal enum class CatalogIdField(val label: String) {
    GENRES("Genres"),
    EXCLUDE_GENRES("Exclude genres"),
    SERVICES("Services"),
    EXCLUDE_SERVICES("Exclude services"),
    STUDIOS("Studios"),
    EXCLUDE_STUDIOS("Exclude studios"),
    NETWORKS("Networks"),
    EXCLUDE_NETWORKS("Exclude networks");

    /** The ids currently on this field, in the order the filter stores them. */
    fun read(filters: KBFilters): List<Int> = when (this) {
        GENRES -> com.kennyb1201.kbstream.data.catalogs.splitIds(filters.withGenres)
        EXCLUDE_GENRES -> com.kennyb1201.kbstream.data.catalogs.splitIds(filters.withoutGenres)
        SERVICES -> com.kennyb1201.kbstream.data.catalogs.splitIds(filters.withWatchProviders)
        EXCLUDE_SERVICES -> com.kennyb1201.kbstream.data.catalogs
            .splitIds(filters.withoutWatchProviders)
        STUDIOS -> com.kennyb1201.kbstream.data.catalogs.splitIds(filters.withCompanies)
        EXCLUDE_STUDIOS -> com.kennyb1201.kbstream.data.catalogs
            .splitIds(filters.withoutCompanies)
        NETWORKS -> com.kennyb1201.kbstream.data.catalogs.splitIds(filters.withNetworks)
        EXCLUDE_NETWORKS -> com.kennyb1201.kbstream.data.catalogs
            .splitIds(filters.withoutNetworks)
    }

    /**
     * [filters] with [ids] added to this field.
     *
     * Order is kept and duplicates drop, so a typed id behaves exactly like a
     * tapped one: it lands on the same CSV the chip rows read and write, which
     * is what makes it visible as a chip, removable, and pruned by a media-type
     * switch the same way.
     */
    fun withIds(filters: KBFilters, ids: List<Int>): KBFilters {
        val merged = (read(filters) + ids).distinct()
        val value = merged.takeIf { it.isNotEmpty() }?.joinToString(",")
        return when (this) {
            GENRES -> filters.copy(withGenres = value)
            EXCLUDE_GENRES -> filters.copy(withoutGenres = value)
            SERVICES -> filters.copy(withWatchProviders = value)
            EXCLUDE_SERVICES -> filters.copy(withoutWatchProviders = value)
            STUDIOS -> filters.copy(withCompanies = value)
            EXCLUDE_STUDIOS -> filters.copy(withoutCompanies = value)
            NETWORKS -> filters.copy(withNetworks = value)
            EXCLUDE_NETWORKS -> filters.copy(withoutNetworks = value)
        }
    }
}

/**
 * The TMDB ids in what the viewer typed.
 *
 * Forgiving on purpose: this is a remote keyboard, and the ids a viewer has to
 * hand are the ones printed on a TMDB page or pasted out of its URL, so "8",
 * "#8", "tmdb:8" and "https://www.themoviedb.org/movie/8" all mean what they
 * look like - the last run of digits in the token. A token with no digits at
 * all (a name, a stray word) is dropped rather than read as 0, and ids are
 * de-duplicated so pasting the same one twice is one chip.
 */
internal fun parseCustomIds(text: String): List<Int> =
    text.split(',', ' ', ';', '\n', '\t')
        .mapNotNull { token -> Regex("\\d+").findAll(token).lastOrNull()?.value?.toIntOrNull() }
        .filter { it > 0 }
        .distinct()

/**
 * [options] plus a chip for every picked id the shipped list does not carry.
 *
 * A hand-typed id has to show up somewhere or it is a rule the viewer can set
 * and then never see or take back: the chip rows render exactly this list, so
 * an unknown id is drawn as "#id", selected like any other, and toggling it off
 * removes it. Known ids are left alone, so a shipped chip keeps its name.
 */
internal fun catalogOptionsWithCustom(
    options: List<CatalogFilterOption>,
    picked: List<Int>
): List<CatalogFilterOption> {
    val known = options.mapTo(mutableSetOf()) { it.id }
    return options + picked.filterNot { it in known }.distinct()
        .map { CatalogFilterOption(it, "#$it") }
}
