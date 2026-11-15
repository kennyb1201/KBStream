package com.kennyb1201.kbstream.data.home

import android.content.Context
import com.kennyb1201.kbstream.data.addon.AddonRepository
import com.kennyb1201.kbstream.data.catalogs.CustomCatalogStore
import com.kennyb1201.kbstream.data.runCatchingCancellable
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Warms the pinned rails' first pages at launch (spec 2026-11-15, Home cold
 * start F2).
 *
 * The field: a cold Home took 37.7s to rails-ready, and the pinned batch was
 * 35.5s of it - its rails resolve their items over the network, and rails-ready
 * waited on all of them. The add-on catalogs already had a launch warm (see
 * `startup.catalogWarm`); the pinned rails had nothing.
 *
 * This asks for exactly what the rail build will ask for, no more:
 *
 *  - the two "Top ... Today" catalogs, through the SAME add-on repository call
 *    the build makes (`AddonRepository.getCatalog` with the same base URL, type,
 *    catalog id and skip), so the repository's own cache and in-flight de-dupe
 *    make the build's request a cache hit rather than a second one;
 *  - the viewer's own catalog rails' first page, through the same
 *    `TmdbRepository.discoverKB` call the build makes - page 1, the catalog's own
 *    sort and filters - and therefore through the same page cache.
 *
 * What it deliberately does NOT do:
 *
 *  - never page 2 or beyond: `deepenPinnedRail` decides how many pages a rail
 *    needs from what has SURVIVED the ceiling and digital passes, and guessing
 *    that here would fetch rows the build then discards;
 *  - never MDBList, the trackers or the next-episode leg - this is TMDB and the
 *    top-today feed only, so the 1000/day MDBList budget cannot move;
 *  - never the kids or guest rails, whose queries are built inside the
 *    ViewModel's own loaders (their paint-first publish still applies; they are
 *    simply not pre-fetched);
 *  - it publishes nothing. No rail, no state, no cache of its own: everything it
 *    lands is a cache entry the build reads, so a warm cut short costs one wasted
 *    request and can never draw a stale row.
 *
 * Failure is silent by construction: every leg is guarded, and a launch with no
 * network simply warms nothing.
 */
internal object HomePinnedWarm {

    /** Warms the pinned rails' first pages, all legs at once. */
    suspend fun warm(context: Context) = coroutineScope {
        val repository = AddonRepository.getInstance()
        val baseUrl = PinnedRailsSpec.baseUrl
        PinnedRailsSpec.catalogs.forEach { (catalogId, type, _) ->
            launch {
                runCatchingCancellable {
                    repository.getCatalog(
                        baseUrl = baseUrl,
                        type = type,
                        catalogId = catalogId,
                        skip = 0
                    )
                }
            }
        }
        val tmdb = runCatching { TmdbRepository.getInstance(context) }.getOrNull()
            ?: return@coroutineScope
        val catalogs = runCatching { CustomCatalogStore.list(context) }.getOrNull().orEmpty()
        catalogs.forEach { catalog ->
            launch {
                runCatchingCancellable {
                    tmdb.discoverKB(
                        mediaType = catalog.mediaType,
                        page = 1,
                        sortBy = catalog.tmdbSortKey(),
                        filters = catalog.effectiveFilters()
                    )
                }
            }
        }
    }
}
