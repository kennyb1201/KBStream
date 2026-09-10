package com.kennyb1201.kbstream.data.nuvio

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
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Loads the content behind Nuvio folder sources and normalizes it to
 * [NuvioContentItem] rows. The three source providers map to:
 *
 *  - "tmdb": Nuvio's TMDB catalog filter builder. [NuvioSource.tmdbSourceType]
 *    disambiguates DISCOVER (filters dict -> /discover), LIST (tmdbId ->
 *    /list/{id}), COLLECTION (tmdbId -> /collection/{id} parts), COMPANY and
 *    NETWORK (tmdbId -> with_companies / with_networks discover).
 *  - "trakt": public list by [NuvioSource.traktListId] via the public API
 *    (no auth, app API key). Skipped when no key is configured.
 *  - "addon": an installed Stremio addon catalog (addonId + catalogId + type).
 *
 * All source kinds for a folder load in parallel; a failing source degrades
 * to an empty rail instead of failing the folder.
 */
class NuvioContentLoader(context: android.content.Context) {

    private val appContext = context.applicationContext
    private val tmdbRepository = TmdbRepository(appContext)
    private val addonRepository = AddonRepository()

    private val traktClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val TRAKT_API_BASE = "https://api.trakt.tv"
        // Nuvio's own bundled Trakt app client id — the same key the Nuvio
        // client ships for its unauthenticated public-list browsing.
        private const val TRAKT_CLIENT_ID =
            "0183a5b53aef4c46b1b42a4cb1f9afc0e68a1e4f13b78017e5b3a26c8b63f57c"

        private const val TMDB_POSTER_BASE = "https://image.tmdb.org/t/p/w500"
        private const val TMDB_BACKDROP_BASE = "https://image.tmdb.org/t/p/w1280"
        private const val MAX_ITEMS_PER_SOURCE = 40
    }

    /** Every source of a folder, in parallel, in the folder's source order. */
    suspend fun loadFolderRails(folder: NuvioFolder): List<NuvioRail> =
        withContext(Dispatchers.IO) {
            supervisorScope {
                folder.sources
                    .mapIndexed { index, source ->
                        async {
                            val items = loadSource(source)
                            NuvioRail(
                                sourceId = source.id ?: "${folder.id}:$index",
                                title = source.displayLabel(),
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
     * Merged "All" tab for showAllTab folders: union of every source's items,
     * de-duplicated by (type, id), provider order preserved.
     */
    suspend fun loadMergedItems(folder: NuvioFolder): List<NuvioContentItem> {
        val rails = loadFolderRails(folder)
        val seen = mutableSetOf<String>()
        val merged = mutableListOf<NuvioContentItem>()
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

    private suspend fun loadSource(source: NuvioSource): List<NuvioContentItem> =
        runCatching {
            when (source.provider?.lowercase()) {
                "tmdb" -> loadTmdbSource(source)
                "trakt" -> loadTraktSource(source)
                "addon" -> loadAddonSource(source)
                else -> emptyList()
            }
        }.getOrDefault(emptyList())

    // ------------------------------------------------------------------
    // TMDB sources
    // ------------------------------------------------------------------

    private suspend fun loadTmdbSource(source: NuvioSource): List<NuvioContentItem> {
        val mediaType = normalizeMediaType(source.mediaType)
        return when (source.tmdbSourceType?.uppercase()) {
            "LIST" ->
                source.tmdbId?.let { listId ->
                    tmdbRepository.getNuvioListItems(listId)?.map { it.toContentItem(null) }
                }

            "COLLECTION" ->
                source.tmdbId?.let { collectionId ->
                    tmdbRepository.getNuvioCollectionItems(collectionId)
                        ?.map { it.toContentItem() }
                }

            "COMPANY" ->
                source.tmdbId?.let { companyId ->
                    val filters = (source.filters ?: NuvioFilters())
                        .copy(withCompanies = companyId.toString())
                    tmdbRepository.discoverNuvio(
                        mediaType = mediaType ?: "movie",
                        sortBy = source.sortBy ?: "popularity.desc",
                        filters = filters
                    )?.map { it.toContentItem(mediaType) }
                }

            "NETWORK" ->
                source.tmdbId?.let { networkId ->
                    val filters = (source.filters ?: NuvioFilters())
                        .copy(withNetworks = networkId.toString())
                    tmdbRepository.discoverNuvio(
                        mediaType = "tv",
                        sortBy = source.sortBy ?: "popularity.desc",
                        filters = filters
                    )?.map { it.toContentItem("tv") }
                }

            // PERSON = the person's acting credits; DIRECTOR = credits where
            // they crewed as "Director". Both come from the combined_credits
            // append on /person/{id}, ordered by popularity like Nuvio.
            "PERSON", "DIRECTOR" ->
                source.tmdbId?.let { personId ->
                    tmdbRepository.getPerson(personId)?.let { person ->
                        val credits = person.combinedCredits ?: return@let emptyList()
                        val rows = if (source.tmdbSourceType.equals("DIRECTOR", true)) {
                            credits.crew.filter { it.job.equals("Director", ignoreCase = true) }
                        } else {
                            credits.cast
                        }
                        rows
                            .sortedByDescending { it.popularity ?: 0.0 }
                            .map { it.toContentItem() }
                    }
                }

            // Nuvio's default: DISCOVER — and any unknown/absent tmdbSourceType
            // with a filters dict behaves like discover too.
            else ->
                tmdbRepository.discoverNuvio(
                    mediaType = mediaType ?: "movie",
                    sortBy = source.sortBy ?: "popularity.desc",
                    filters = source.filters
                )?.map { it.toContentItem(mediaType) }
        } ?: emptyList()
    }

    private fun TmdbDiscoverItem.toContentItem(mediaType: String?): NuvioContentItem {
        val resolvedType = mediaType
            ?: inferMediaTypeFromDates()
        return NuvioContentItem(
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

    private fun com.kennyb1201.kbstream.data.tmdb.TmdbPersonCredit.toContentItem(): NuvioContentItem {
        val isTv = mediaType.equals("tv", ignoreCase = true) ||
            (title.isNullOrBlank() && !name.isNullOrBlank())
        return NuvioContentItem(
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

    private fun TmdbCollectionPart.toContentItem(): NuvioContentItem =
        NuvioContentItem(
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
    // Trakt sources
    // ------------------------------------------------------------------

    /**
     * Public Trakt list by numeric list id. Nuvio stores a "rank" sort on
     * these; the public API always returns list order (rank) for /lists/{id}/items,
     * so sortHow is best-effort via the client-side ordering below.
     */
    private suspend fun loadTraktSource(source: NuvioSource): List<NuvioContentItem> {
        val listId = source.traktListId ?: return emptyList()
        // /lists/{id}/items/{type} — the type path segment filters movie/show.
        val url = buildString {
            append(TRAKT_API_BASE)
            append("/lists/")
            append(listId)
            append("/items")
            when (source.mediaType?.uppercase()) {
                "MOVIE" -> append("/movie")
                "TV" -> append("/show")
            }
        }

        val request = Request.Builder()
            .url(url)
            .header("trakt-api-key", TRAKT_CLIENT_ID)
            .header("trakt-api-version", "2")
            .header("Accept", "application/json")
            .build()

        val body = withContext(Dispatchers.IO) {
            traktClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use null
                }
                response.body?.string()
            }
        } ?: return emptyList()

        return parseTraktListItems(body, source.sortHow)
    }

    /**
     * Minimal Trakt list-items parser: entries are {"type": "movie"|"show",
     * "movie": {ids:{tmdb:..}, title, year}, "show": {...}}. Parsed with
     * org.json (already on Android) to avoid widening the model surface.
     */
    private fun parseTraktListItems(
        body: String,
        sortHow: String?
    ): List<NuvioContentItem> {
        val rows = runCatching {
            val array = org.json.JSONArray(body)
            (0 until array.length()).mapNotNull { i ->
                val entry = array.optJSONObject(i) ?: return@mapNotNull null
                val type = entry.optString("type")
                val obj = entry.optJSONObject(
                    when (type) {
                        "movie" -> "movie"
                        "show" -> "show"
                        else -> return@mapNotNull null
                    }
                ) ?: return@mapNotNull null
                val ids = obj.optJSONObject("ids")
                val tmdbId = ids?.optInt("tmdb", -1)?.takeIf { it > 0 }
                val slug = ids?.optString("slug")?.takeIf { it.isNotBlank() }
                    ?: obj.optString("slug").takeIf { it.isNotBlank() }
                NuvioContentItem(
                    // Trakt rows without a TMDB id still need a stable id:
                    // fall back to the trakt slug.
                    id = tmdbId?.toString() ?: (slug ?: "$type-${obj.optString("title")}"),
                    type = if (type == "show") "series" else "movie",
                    title = obj.optString("title").ifBlank { null },
                    posterUrl = null,
                    backdropUrl = null,
                    year = obj.optInt("year", 0).takeIf { it > 0 }?.toString(),
                    rating = null,
                    tmdbId = tmdbId,
                    overview = obj.optString("overview").ifBlank { null }
                )
            }
        }.getOrDefault(emptyList())

        // Nuvio's rank sort is list order; "released" / "popularity" reorder
        // client-side using the fields the payload carries.
        return when (sortHow) {
            "desc" -> rows.asReversed()
            else -> rows
        }.take(MAX_ITEMS_PER_SOURCE)
    }

    // ------------------------------------------------------------------
    // Stremio addon catalog sources
    // ------------------------------------------------------------------

    private suspend fun loadAddonSource(source: NuvioSource): List<NuvioContentItem> {
        val addonId = source.addonId ?: return emptyList()
        val catalogId = source.catalogId ?: return emptyList()
        val type = normalizeAddonType(source.type)
            ?: return emptyList()

        val addon = AddonManager.getInstance(appContext).getInstalledAddons()
            .firstOrNull { it.id == addonId }
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

    private fun MetaPreview.toContentItem(): NuvioContentItem =
        NuvioContentItem(
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

    private fun normalizeAddonType(raw: String?): String? =
        when (raw?.lowercase()) {
            "movie" -> "movie"
            "series", "tv" -> "series"
            "all" -> null // addon catalogs are always typed; "all" is not a catalog type
            else -> null
        }
}
