package com.kennyb1201.kbstream.ui.search

import com.kennyb1201.kbstream.data.tmdb.TmdbSearchStudioResult

/**
 * One row of the Search screen's Studios rail.
 *
 * Two kinds of entity share that rail. A COMPANY is a TMDB search hit (the
 * rail's only source before this, from `GET search/company`). A NETWORK is one
 * of the browse catalog's curated "Services & Networks" entries: TMDB has no
 * `/search/network` endpoint at all, so the only networks this app can offer are
 * the ones its own catalog already names - the same entries the sidebar draws,
 * with ids that were verified against TMDB.
 *
 * A sealed type rather than fields bolted onto [TmdbSearchStudioResult]: that
 * model is a Moshi `@JsonClass` response shape, and mixing a catalog network
 * into it would make one type mean two different entities. Here the kind is in
 * the type, which is what the open path branches on (a company opens
 * `isNetwork = false`; a network/service opens the browse page, provider rails
 * and all).
 */
sealed interface StudioSearchHit {

    /** What the rail's card/tile prints. */
    val displayName: String

    /**
     * TMDB logo path, for the COMPANY side only.
     *
     * A catalog entry carries no logo, and fetching one would mean a
     * `/network/{id}` read per hit: the tile renders name-only instead, exactly
     * as it does for a company with no logo. The opened page resolves the
     * network's logo at open time, which is where it is actually needed.
     */
    val logoPath: String?
        get() = null

    /** TMDB origin country, for the COMPANY side only (a catalog entry has none). */
    val originCountry: String?
        get() = null

    /**
     * The rail's item key, carrying the KIND.
     *
     * A bare numeric id is not a key here: company and network ids live in
     * different id spaces and collide (Starz's provider id 43 is National
     * Geographic's network id; Peacock's network is 3353 while 3186 is HBO
     * Max's), so the kind has to be part of the identity or two different rows
     * would fight over one LazyList slot.
     */
    val key: String

    data class Company(val result: TmdbSearchStudioResult) : StudioSearchHit {
        override val displayName: String get() = result.name
        override val logoPath: String? get() = result.logoPath
        override val originCountry: String? get() = result.originCountry
        override val key: String get() = "studio:company:${result.id}"
    }

    data class Network(val entry: BrowseEntry) : StudioSearchHit {
        override val displayName: String get() = entry.name

        /**
         * A provider-only service (`id == -1`: Tubi, Pluto, Netflix Kids...)
         * has no network/company id to key by, so its provider id is the only
         * identity it has. Every other entry - a plain network or a service
         * with a real id - keys by that id.
         */
        override val key: String
            get() = if (entry.id == -1) {
                "studio:service:provider:${entry.providerId}"
            } else {
                "studio:network:${entry.id}"
            }
    }
}

/**
 * The Studios rail's catalog hits for [query]: the browse catalog's curated
 * services and networks whose NAME matches, in the order they should be shown.
 *
 * Three bands, in order: an exact name, a name that starts with the query, a
 * name that contains it. Within a band the catalog's own order is kept - it is
 * already the popular-first order the sidebar draws - so "The CW" outranks some
 * alphabetically-earlier substring match, and the order matches what the viewer
 * sees when they browse.
 *
 * The two-letter floor is deliberate: a single letter would substring-match most
 * of the catalog, and no brand here is one letter long. A shorter query makes
 * this contribute nothing at all, leaving the company wave exactly as it was.
 *
 * Entries with neither an id nor a provider id are skipped: the -1 sentinel is
 * ordinary (a provider-only service), but `-1` with no provider could never be
 * opened at all.
 *
 * Pure, in-memory, no I/O: the caller hands it the entries the sidebar is
 * showing, which is also what makes the mode and the hidden-chip filter carry
 * over for free - a kids profile matches only kids-curated brands, and a chip
 * the viewer hid is not offered in the rail either.
 */
internal fun networkStudioHits(
    entries: List<BrowseEntry>,
    query: String,
    max: Int
): List<StudioSearchHit> {
    val needle = query.trim().lowercase()
    if (needle.length < 2 || max <= 0) return emptyList()

    val banded = ArrayList<Pair<Int, StudioSearchHit>>(max)
    entries.forEach { entry ->
        if (entry.id == -1 && entry.providerId == null) return@forEach

        val name = entry.name.trim().lowercase()
        val band = when {
            name == needle -> 0
            name.startsWith(needle) -> 1
            name.contains(needle) -> 2
            else -> return@forEach
        }
        banded += band to StudioSearchHit.Network(entry)
    }

    // Stable sort: band first, the catalog's own order inside a band.
    return banded
        .sortedBy { (band, _) -> band }
        .map { (_, hit) -> hit }
        .distinctBy { it.key }
        .take(max)
}

/**
 * The Studios rail: the catalog's network/service hits first, then the company
 * wave behind them, de-duplicated across the two.
 *
 * Catalog-first is the point of the feature, not an accident: the catalog rows
 * are exact or prefix matches on a curated list and open the richer page
 * (provider rails plus the brand's originals), while the company wave is
 * relevance-ranked TMDB data. Companies-first let eight company rows crowd out
 * the exact network the viewer typed ("CW" -> eight companies, no The CW).
 *
 * A company row is dropped when the brand is ALREADY on the rail, which is
 * decided on two keys because the two id spaces cannot be compared:
 *  - the same normalized NAME (TMDB's "The CW" company vs the catalog's The CW
 *    network), and
 *  - a company-space ID the catalog entry names: its `originalsCompanyId`, or
 *    its `networkOrCompanyId` when that id IS a company
 *    (`networkIsCompany`). TMDB's "Home Box Office (HBO)" company and the
 *    catalog's HBO Max service are one brand under two names; the id catches
 *    what the name does not.
 *
 * [max] bounds the merged rail, not each side, so the rail never grows past
 * what it was.
 */
internal fun mergeStudioHits(
    catalog: List<StudioSearchHit>,
    companies: List<TmdbSearchStudioResult>,
    max: Int
): List<StudioSearchHit> {
    if (max <= 0) return emptyList()

    val out = ArrayList<StudioSearchHit>(max)

    val keys = HashSet<String>()
    val names = HashSet<String>()
    val companyIds = HashSet<Int>()
    catalog.forEach { hit ->
        if (out.size >= max) return@forEach
        // By KEY, not by value: two catalog entries can share a brand name and
        // the key is what the rail's LazyList needs to stay one row per slot.
        if (!keys.add(hit.key)) return@forEach
        out += hit
        names += hit.displayName.trim().lowercase()
        (hit as? StudioSearchHit.Network)?.entry?.let { entry ->
            entry.originalsCompanyId?.let(companyIds::add)
            if (entry.networkIsCompany) entry.networkOrCompanyId?.let(companyIds::add)
        }
    }

    val seenCompanies = HashSet<Int>()
    companies.forEach { company ->
        if (out.size >= max) return@forEach
        if (!seenCompanies.add(company.id)) return@forEach
        if (company.name.trim().lowercase() in names) return@forEach
        if (company.id in companyIds) return@forEach
        out += StudioSearchHit.Company(company)
    }

    return out
}
