package com.kennyb1201.kbstream.ui.detail

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.mdblist.MdbListClient
import com.kennyb1201.kbstream.data.runCatchingCancellable
import com.kennyb1201.kbstream.data.tmdb.TmdbReview
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Background enrichment for the Detail screen: MDBList audience ratings and
 * the full (multi-source) review list.
 *
 * Split out of [DetailViewModel] (a 2,000-line class that had grown past the
 * size where a single file stayed editable). Both entry points are
 * fire-and-forget by design: they publish into the view model's state flows as
 * results land and never block the detail UI, and every failure degrades to
 * "no extra data" rather than an error.
 */
internal object DetailRatingEnrichment {

    /**
     * Bounds the concurrent review-page reads in [extraReviews]. Review pages
     * are independent, so fetching them one at a time was up to 29 serial TMDB
     * round trips before a heavily-reviewed title's row could finish; four at a
     * time removes most of that wait without a burst the API would notice (the
     * same bounded fan-out shape HomeViewModel uses).
     */
    private val reviewPagesSemaphore = Semaphore(permits = 4)

    /** Prefers the user's key from Settings, falling back to the build field. */
    private fun mdbListApiKey(vm: DetailViewModel): String {
        val fromPrefs = runCatching {
            com.kennyb1201.kbstream.data.settings.AppPreferences
                .getMdbListApiKey(vm.getApplication())
        }.getOrDefault("")
        if (fromPrefs.isNotBlank()) return fromPrefs

        return runCatching {
            com.kennyb1201.kbstream.BuildConfig.MDBLIST_API_KEY
        }.getOrDefault("")
    }

    fun ratings(vm: DetailViewModel, normalizedType: String) {
        val key = mdbListApiKey(vm)
        if (key.isBlank()) {
            Log.i(
                "KBStream",
                "MDBList ratings skipped: no API key (add one in Settings or MDBLIST_API_KEY build field)"
            )
            return
        }
        vm.viewModelScope.launch {
            val meta = vm.meta.value
            val rawId = meta?.id ?: vm.imdbId
            val tmdbId = vm.tmdbDetail.value?.id?.takeIf { it > 0 }
            val resolved = rawId.takeIf { it.startsWith("tt") }
                ?: tmdbId?.let {
                    vm.tmdbRepository.resolveImdbId(it, normalizedType).orEmpty()
                }.orEmpty()
            // The media route is id-based, so a TMDB id is a perfectly good
            // key (provider "tmdb") — an unresolvable IMDb id used to be the
            // end of the ratings row for that title even though TMDB had
            // already handed us the id it needs.
            val queryId = resolved.takeIf { it.startsWith("tt") }
                ?: tmdbId?.toString()
            if (queryId == null) {
                Log.i(
                    "KBStream",
                    "MDBList ratings skipped: no imdb id and no tmdb id (raw=$rawId)"
                )
                return@launch
            }
            Log.d("KBStream", "MDBList ratings query id=$queryId ($normalizedType)")
            val ratings = MdbListClient.fetchRatings(queryId, normalizedType, key)
            if (ratings?.hasAny != true) {
                Log.i(
                    "KBStream",
                    "MDBList ratings empty for $queryId ($normalizedType) — " +
                        "title may not be rated on mdblist.com"
                )
            }
            vm.setMdbListRatings(ratings)
        }
    }

    /**
     * Awards text from OMDB, published into the view model's `awards` flow.
     *
     * Fire-and-forget like [ratings]: it owns its own [DetailViewModel.viewModelScope]
     * launch and is never awaited by the detail load, so a slow or unreachable
     * OMDB cannot delay the screen's first paint — the fact row simply
     * recomposes if and when an answer arrives.
     *
     * The IMDb id comes from the route id when it is already a tt-id (the
     * addon meta carries one for most movies/series) and otherwise from TMDB's
     * external-ids lookup, which [ratings] has usually already resolved and
     * cached by the time this runs.
     */
    fun awards(vm: DetailViewModel, normalizedType: String) {
        val rawId = vm.meta.value?.id ?: vm.imdbId
        val tmdbId = vm.tmdbDetail.value?.id?.takeIf { it > 0 }
        // Captured before the launch: a later load for a different title
        // retargets vm.imdbId, and this answer must not land under it.
        val requestParentId = vm.imdbId

        vm.viewModelScope.launch {
            val imdbId = rawId.takeIf { it.startsWith("tt") }
                ?: tmdbId?.let { id ->
                    runCatchingCancellable { vm.tmdbRepository.resolveImdbId(id, normalizedType) }
                        .getOrNull()
                }
            if (imdbId.isNullOrBlank()) return@launch

            val awards = vm.omdbRepository.awardsFor(imdbId)
            if (vm.imdbId != requestParentId) return@launch
            vm.setAwards(awards)
        }
    }

    /**
     * Reviews beyond the first page. TMDB's detail payload bundles only page
     * 1 of reviews (often just a handful); the standalone endpoint paginates
     * the full list. Fetch pages 2..totalPages (bounded) in the background
     * and merge, de-duped, after the bundled page so the UI paints
     * immediately. Everything fails soft: reviews must never block the detail
     * UI.
     */
    fun extraReviews(vm: DetailViewModel, normalizedType: String) {
        val detail = vm.tmdbDetail.value ?: return
        val tmdbId = detail.id.takeIf { it > 0 } ?: return
        val bundled = detail.reviews?.results.orEmpty()

        vm.viewModelScope.launch {
            val extras = mutableListOf<TmdbReview>()
            var lastPage = 1

            // Probe page 2 for the total; short-circuit when the title only
            // has one page (the common case).
            val second = vm.tmdbRepository.getReviews(tmdbId, normalizedType, 2)
            if (second == null || second.results.isEmpty()) {
                if (second?.results != null) lastPage = second.totalPages ?: 1
            } else {
                lastPage = second.totalPages ?: 2
                extras += second.results
            }

            if (lastPage > 2) {
                // Hard safety cap (server totalPages minus page 1) so a
                // pathological response can never spin the loop; 30 pages
                // = 600 reviews is far beyond any title's real list.
                val maxPage = minOf(lastPage, 31)
                // Fetched in bounded parallel rather than one page at a time:
                // the pages are independent reads, and serializing them made a
                // cold open of a heavily-reviewed title wait on up to 29 round
                // trips before the row could settle.
                val pages = coroutineScope {
                    (3..maxPage).map { page ->
                        async {
                            reviewPagesSemaphore.withPermit {
                                vm.tmdbRepository.getReviews(
                                    tmdbId,
                                    normalizedType,
                                    page
                                )?.results.orEmpty()
                            }
                        }
                    }.awaitAll()
                }
                pages.forEach { pageResults ->
                    if (pageResults.isNotEmpty()) extras += pageResults
                }
            }

            // Publish the pages so the row grows as fast as the network
            // allows.
            vm.setAllReviews((bundled + extras).distinctBy { it.id })
        }
    }
}
