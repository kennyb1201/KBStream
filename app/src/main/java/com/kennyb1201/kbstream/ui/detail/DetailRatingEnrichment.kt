package com.kennyb1201.kbstream.ui.detail

import android.util.Log
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.mdblist.MdbListClient
import com.kennyb1201.kbstream.data.reporting.Redaction
import com.kennyb1201.kbstream.data.runCatchingCancellable
import com.kennyb1201.kbstream.data.tmdb.TmdbReview
import com.kennyb1201.kbstream.data.trakt.TraktComment
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
private const val REVIEW_RAIL_MAX_PAGE = 3

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
     * Trakt comments, appended to the same review rail as TMDB's.
     *
     * Read-only (posting is a separate decision), sorted by likes, and silent
     * on every failure: a tracker outage must not cost the page its reviews.
     * The IMDb id is preferred because Trakt's comments path accepts it
     * directly; a TMDB-only title is resolved by the repository.
     */
    fun traktComments(vm: DetailViewModel, normalizedType: String) {
        val rawId = vm.meta.value?.id ?: vm.imdbId
        val tmdbId = vm.tmdbDetail.value?.id?.takeIf { it > 0 }
        // Captured before the launch: a later load for a different title
        // retargets vm.imdbId, and this answer must not land under it.
        val requestParentId = vm.imdbId
        val isMovie = normalizedType.lowercase() == "movie"

        vm.viewModelScope.launch {
            if (!vm.traktRepository.isConfigured() || !vm.traktRepository.hasToken()) {
                vm.noteTraktReviewsEmpty("no-token")
                return@launch
            }
            val imdbId = rawId.takeIf { it.startsWith("tt") }
                ?: tmdbId?.let { id ->
                    runCatchingCancellable {
                        vm.tmdbRepository.resolveImdbId(id, normalizedType)
                    }.getOrNull()
                }
            val commentsResult = runCatchingCancellable {
                vm.traktRepository.commentsFor(
                    tmdbId = tmdbId,
                    imdbId = imdbId,
                    isMovie = isMovie
                )
            }
            val comments = commentsResult.getOrNull().orEmpty()

            // Every silent exit says why (spec 2026-11-22, F3): a paste that
            // shows neither comments nor a reason cannot tell a disconnected
            // account from a title nobody commented on.
            // The retarget check comes FIRST: a stale answer's "empty" says
            // nothing about the title now on screen, so it must not be recorded
            // as that title's Trakt result.
            if (vm.imdbId != requestParentId) {
                vm.noteTraktReviewsEmpty("guard")
                return@launch
            }
            if (comments.isEmpty()) {
                val reason = commentsResult.exceptionOrNull()?.let { error ->
                    // Redacted and capped: this line goes into a paste, and an
                    // error body can carry a URL or a token-shaped string.
                    "error:" + Redaction.text(error.message)
                        .ifBlank { error.javaClass.simpleName }
                        .take(60)
                } ?: "none"
                vm.noteTraktReviewsEmpty(reason)
                return@launch
            }
            vm.addAllReviews(comments.map { comment -> comment.toTmdbReview() })
        }
    }

    private fun TraktComment.toTmdbReview(): TmdbReview = TmdbReview(
        id = "trakt:$id",
        author = user?.name?.takeIf { it.isNotBlank() }
            ?: user?.username?.takeIf { it.isNotBlank() }
            ?: "Trakt user",
        content = comment.orEmpty(),
        createdAt = createdAt,
        spoiler = spoiler
    )

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
        // F2 (spec 2026-11-22): the same retarget guard [traktComments] already
        // carries. Without it a slow page fetch for the title the viewer just
        // left published its pages under whatever title is on screen now.
        val requestParentId = vm.imdbId

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
                // The rail can show 60 reviews (20 per page), so page 3 is the
                // last one that can ever reach the screen. Fetching pages
                // 3..31 spent up to 29 extra TMDB requests on a title browsed
                // for a few seconds, for reviews the `take(60)` discards.
                val maxPage = minOf(lastPage, REVIEW_RAIL_MAX_PAGE)
                // Fetched in bounded parallel rather than one page at a time:
                // the pages are independent reads, and serializing them made a
                // cold open of a heavily-reviewed title wait on one round trip
                // per page before the row could settle.
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
            // allows - but only onto the title this read was started for.
            if (vm.imdbId != requestParentId) return@launch
            vm.setAllReviews((bundled + extras).distinctBy { it.id })
        }
    }

}
