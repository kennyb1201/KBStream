package com.kennyb1201.kbstream.data.kb

import com.kennyb1201.kbstream.data.addon.AddonManager
import com.kennyb1201.kbstream.data.addon.AddonRepository
import com.kennyb1201.kbstream.data.addon.MetaPreview
import com.kennyb1201.kbstream.data.tmdb.TmdbCollectionPart
import com.kennyb1201.kbstream.data.tmdb.TmdbDiscoverItem
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

/**
 * Loads the content behind KB folder sources and normalizes it to
 * [KBContentItem] rows. The two source providers map to:
 *
 *  - "tmdb": KB's TMDB catalog filter builder. [KBSource.tmdbSourceType]
 *    disambiguates DISCOVER (filters dict -> /discover), LIST (tmdbId ->
 *    /list/{id}), COLLECTION (tmdbId -> /collection/{id} parts), COMPANY and
 *    NETWORK (tmdbId -> with_companies / with_networks discover).
 *  - "addon": an installed Stremio addon catalog (addonId + catalogId + type).
 *
 * Any other provider (KB exports also carry "trakt" list sources, which need a
 * paid Trakt API app) degrades to an empty rail.
 *
 * All source kinds for a folder load in parallel; a failing source degrades
 * to an empty rail instead of failing the folder.
 */
class KBContentLoader(context: android.content.Context) {

    private val appContext = context.applicationContext
    private val tmdbRepository = TmdbRepository.getInstance(appContext)
    private val addonRepository = AddonRepository.getInstance()

    companion object {
        private const val TMDB_POSTER_BASE = "https://image.tmdb.org/t/p/w500"
        private const val TMDB_BACKDROP_BASE = "https://image.tmdb.org/t/p/w1280"
        private const val MAX_ITEMS_PER_SOURCE = 40
    }

    /** Every source of a folder, in parallel, in the folder's source order. */
    suspend fun loadFolderRails(folder: KBFolder): List<KBRail> =
        withContext(Dispatchers.IO) {
            supervisorScope {
                dedupeSources(folder.sources.ifEmpty { folder.catalogSources.map { it.toSource() } })
                    .mapIndexed { index, source ->
                        async {
                            val items = loadSource(source)
                            KBRail(
                                sourceId = source.id ?: "${folder.id}:$index",
                                title = railTitleFor(source),
                                providerLabel = source.providerLabel(),
                                items = items
                            )
                        }
                    }
                    .awaitAll()
                    .filter { it.items.isNotEmpty() }
            }
        }

    /**
     * Remove repeated sources inside a folder. Real exports (incl. the
     * davecollections builder) emit the SAME source twice for the two
     * sort directions — e.g. both "top 10 ... week" twin rows — and one
     * profile in the wild carried the same tmdbId twice per movie
     * collection folder (2,198 duplicate entries in one file). Duplicates
     * render as literally identical rails side by side, which reads as
     * "this collection isn't working". Key on every field that addresses
     * the content: provider + tmdb id/source type, addon id/catalog id,
     * plus name/title/sortBy/filters so differently-tuned
     * discover rows ("Recent" vs "Popular") stay distinct.
     */
    private fun dedupeSources(sources: List<KBSource>): List<KBSource> {
        if (sources.size < 2) return sources
        val seen = HashSet<String>()
        return sources.filter { source ->
            seen.add(
                buildString {
                    append(source.provider?.lowercase()).append('|')
                    append(source.tmdbSourceType?.uppercase()).append('|')
                    append(source.tmdbId).append('|')
                    append(source.addonId).append('|')
                    append(source.catalogId).append('|')
                    append(source.type?.lowercase()).append('|')
                    append(source.genre).append('|')
                    append(source.mediaType?.uppercase()).append('|')
                    append(source.name).append('|')
                    append(source.title).append('|')
                    append(source.sortBy).append('|')
                    append(source.sortHow).append('|')
                    append(source.filters)
                }
            )
        }
    }

    /**
     * Rail title matching Home's "AddonName · CatalogName" format. KB
     * exports often omit source.name, which previously left addon rails
     * titled with the generic provider label ("Add-on"); those resolve the
     * addon manifest's catalog display name instead.
     */
    private fun railTitleFor(source: KBSource): String {
        source.name?.takeIf { it.isNotBlank() }?.let { return it }
        source.title?.takeIf { it.isNotBlank() }?.let { return it }

        if (source.provider?.lowercase() != "addon") {
            return source.providerLabel()
        }

        val addonId = source.addonId ?: return source.providerLabel()
        val catalogId = source.catalogId ?: return source.providerLabel()
        val type = normalizeAddonType(source.type)

        val addon = AddonManager.getInstance(appContext)
            .getInstalledAddons()
            .firstOrNull { it.id == addonId }
            ?: return source.providerLabel()
        if (!addon.enabled) return source.providerLabel()

        val catalog = addon.catalogs.firstOrNull {
            it.id == catalogId &&
                (type == null || it.type.equals(type, ignoreCase = true))
        }

        val catalogName = catalog?.displayName?.takeIf { it.isNotBlank() }
            ?: return addon.displayName

        return if (addon.displayName.isNotBlank() &&
            !addon.displayName.equals(catalogName, ignoreCase = true)
        ) {
            "${addon.displayName} · $catalogName"
        } else {
            catalogName
        }
    }

    /**
     * Merged "All" tab for showAllTab folders: union of every source's items,
     * de-duplicated by (type, id), provider order preserved.
     */
    suspend fun loadMergedItems(folder: KBFolder): List<KBContentItem> {
        val rails = loadFolderRails(folder)
        val seen = mutableSetOf<String>()
        val merged = mutableListOf<KBContentItem>()
        for (rail in rails) {
            for (item in rail.items) {
                val key = "${item.type}:${item.id}"
                if (seen.add(key)) {
                    merged += item
                }
            }
        }
        return merged
    }

    private suspend fun loadSource(source: KBSource): List<KBContentItem> =
        runCatching {
            when (source.provider?.lowercase()) {
                "tmdb" -> loadTmdbSource(source)
                "addon" -> loadAddonSource(source)
                else -> emptyList()
            }
        }.getOrDefault(emptyList())

    // ------------------------------------------------------------------
    // TMDB sources
    // ------------------------------------------------------------------

    private suspend fun loadTmdbSource(source: KBSource): List<KBContentItem> {
        val mediaType = normalizeMediaType(source.mediaType)
        return when (source.tmdbSourceType?.uppercase()) {
            "LIST" ->
                source.tmdbId?.let { listId ->
                    tmdbRepository.getKBListItems(listId)?.map { it.toContentItem(null) }
                }

            "COLLECTION" ->
                source.tmdbId?.let { collectionId ->
                    tmdbRepository.getKBCollectionItems(collectionId)
                        ?.map { it.toContentItem() }
                }

            "COMPANY" ->
                source.tmdbId?.let { companyId ->
                    val filters = (source.filters ?: KBFilters())
                        .copy(withCompanies = companyId.toString())
                    tmdbRepository.discoverKB(
                        mediaType = mediaType ?: "movie",
                        sortBy = kbMostVotedSort(source.sortBy) ?: "popularity.desc",
                        filters = filters
                    )?.map { it.toContentItem(mediaType) }
                }

            "NETWORK" ->
                source.tmdbId?.let { networkId ->
                    val filters = (source.filters ?: KBFilters())
                        .copy(withNetworks = networkId.toString())
                    tmdbRepository.discoverKB(
                        mediaType = "tv",
                        sortBy = kbMostVotedSort(source.sortBy) ?: "popularity.desc",
                        filters = filters
                    )?.map { it.toContentItem("tv") }
                }

            // PERSON = the person's acting credits; DIRECTOR = credits where
            // they crewed as "Director"; WRITER = crew jobs in the writing
            // family (Writer, Screenplay, Story). All come from the
            // combined_credits append on /person/{id}, ordered by popularity
            // like KB. WRITER was previously unmapped, so writer rails
            // from exported profiles came back empty.
            "PERSON", "DIRECTOR", "WRITER" ->
                source.tmdbId?.let { personId ->
                    tmdbRepository.getPerson(personId)?.let { person ->
                        val credits = person.combinedCredits ?: return@let emptyList()
                        val rows = when (source.tmdbSourceType?.uppercase()) {
                            "DIRECTOR" ->
                                credits.crew.filter { it.job.equals("Director", ignoreCase = true) }
                            "WRITER" ->
                                credits.crew.filter {
                                    val job = it.job?.lowercase().orEmpty()
                                    job == "writer" || job == "screenplay" ||
                                        job == "story" || job == "screenstory"
                                }
                            else -> credits.cast
                        }
                        rows
                            .sortedByDescending { it.popularity ?: 0.0 }
                            .map { it.toContentItem() }
                    }
                }

            // KB's default: DISCOVER — and any unknown/absent tmdbSourceType
            // with a filters dict behaves like discover too.
            else ->
                tmdbRepository.discoverKB(
                    mediaType = mediaType ?: "movie",
                    sortBy = kbMostVotedSort(source.sortBy) ?: "popularity.desc",
                    filters = source.filters
                )?.map { it.toContentItem(mediaType) }
        } ?: emptyList()
    }

    private fun TmdbDiscoverItem.toContentItem(mediaType: String?): KBContentItem {
        // Normalize to the app's item vocabulary: TMDB calls it "tv" but
        // every other source kind (addon, credits, collections) and
        // the rail-type display use "series". Without this, discover rails
        // showed "Tv" while addon rails showed "Series".
        val resolvedType = when (mediaType?.lowercase()) {
            "series", "show", "tv" -> "series"
            else -> mediaType?.lowercase() ?: inferMediaTypeFromDates()
        }
        return KBContentItem(
            id = id.toString(),
            type = resolvedType,
            title = title ?: name,
            posterUrl = posterPath?.takeIf { it.isNotBlank() }
                ?.let { TMDB_POSTER_BASE + it },
            backdropUrl = backdropPath?.takeIf { it.isNotBlank() }
                ?.let { TMDB_BACKDROP_BASE + it },
            year = releaseDate?.take(4)?.takeIf { it.isNotBlank() }
                ?: firstAirDate?.take(4)?.takeIf { it.isNotBlank() },
            rating = voteAverage,
            tmdbId = id,
            overview = null
        )
    }

    private fun TmdbDiscoverItem.inferMediaTypeFromDates(): String =
        if (firstAirDate != null) "series" else "movie"

    private fun com.kennyb1201.kbstream.data.tmdb.TmdbPersonCredit.toContentItem(): KBContentItem {
        val isTv = mediaType.equals("tv", ignoreCase = true) ||
            (title.isNullOrBlank() && !name.isNullOrBlank())
        return KBContentItem(
            id = id.toString(),
            type = if (isTv) "series" else "movie",
            title = title ?: name,
            posterUrl = posterPath?.takeIf { it.isNotBlank() }
                ?.let { TMDB_POSTER_BASE + it },
            backdropUrl = null,
            year = releaseDate?.take(4)?.takeIf { it.isNotBlank() }
                ?: firstAirDate?.take(4)?.takeIf { it.isNotBlank() },
            rating = voteAverage,
            tmdbId = id
        )
    }

    private fun TmdbCollectionPart.toContentItem(): KBContentItem =
        KBContentItem(
            id = id.toString(),
            type = "movie",
            title = title ?: name,
            posterUrl = posterPath?.takeIf { it.isNotBlank() }
                ?.let { TMDB_POSTER_BASE + it },
            backdropUrl = null,
            year = releaseDate?.take(4)?.takeIf { it.isNotBlank() },
            rating = voteAverage,
            tmdbId = id,
            overview = null
        )

    // ------------------------------------------------------------------
    // Stremio addon catalog sources
    // ------------------------------------------------------------------

    private suspend fun loadAddonSource(source: KBSource): List<KBContentItem> {
        val addonId = source.addonId ?: return emptyList()
        val catalogId = source.catalogId ?: return emptyList()
        // The request type must be the ADDON'S OWN declared type — exports
        // mirror the manifest verbatim, and addons key their handlers on it.
        // Mapping "tv" (IPTV/Live-TV genre catalogs) to "series" made every
        // such rail request /catalog/series/… and come back empty.
        val type = normalizeAddonType(source.type)
            ?: return emptyList()

        val addon = AddonManager.getInstance(appContext).getInstalledAddons()
            .firstOrNull { it.id == addonId && it.enabled }
            ?: return emptyList()

        val metas: List<MetaPreview> = addonRepository.getCatalog(
            baseUrl = addon.manifestUrl,
            type = type,
            catalogId = catalogId
        )

        return metas.map { meta ->
            meta.toContentItem()
        }.take(MAX_ITEMS_PER_SOURCE)
    }

    private fun MetaPreview.toContentItem(): KBContentItem =
        KBContentItem(
            id = id,
            type = when (type.lowercase()) {
                "series", "tv" -> "series"
                else -> "movie"
            },
            title = name,
            posterUrl = poster,
            backdropUrl = background,
            year = releaseInfo
                ?.let { Regex("\\b(?:19|20)\\d{2}\\b").find(it)?.value },
            rating = imdbRating?.toDoubleOrNull(),
            tmdbId = null,
            overview = description
        )

    private fun normalizeMediaType(raw: String?): String? =
        when (raw?.uppercase()) {
            "MOVIE" -> "movie"
            "TV" -> "tv"
            else -> null
        }

    /**
     * Request type for an addon catalog: the raw manifest type, lowercased.
     * NEVER normalize "tv" to "series" here — the catalog path must match
     * what the addon declared ("tv" is how IPTV addons type their channel
     * catalogs; "all" is a real merged-catalog type in the AIOStreams
     * family). Display typing happens later via toContentItem.
     */
    private fun normalizeAddonType(raw: String?): String? {
        val value = raw?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
            ?: return null
        return value
    }
}
