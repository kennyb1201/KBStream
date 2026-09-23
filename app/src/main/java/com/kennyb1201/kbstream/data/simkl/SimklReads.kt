package com.kennyb1201.kbstream.data.simkl

/*
 * Read endpoints for [SimklRepository]: live playback sessions, the
 * watching-shows feed, library totals for the connect screen, the account
 * identity, and the "is this show finished" rule used by the watched-marker
 * resolution.
 *
 * These live outside SimklRepository.kt only to keep that file readable.
 * The repository keeps same-named members that delegate here, so callers are
 * unchanged.
 */

/**
 * Upcoming-rail diagnostics. On, the repository says why a following show was
 * kept off Continue Watching and the Home rail reports what it made of each
 * caught-up candidate - enough to tell a show that never qualifies from one
 * whose next episode TMDB has no date for. One line per show per feed build
 * (the feed itself is cached), so the cost is a log write, not a request.
 * Flip to false to silence.
 */
internal const val UPCOMING_DIAGNOSTICS =
    true

suspend fun SimklRepository.getPlaybackItemsImpl(
    accessToken: String =
        trackedAccessToken()
): List<SimklPlaybackItem> {

    require(
        clientId.isNotBlank()
    ) {
        "SIMKL_CLIENT_ID is missing"
    }

    return api.getPlayback(
        authorization =
            trackedAuthHeaderFor(
                accessToken
            ),

        extended =
            "full"
    )
}

suspend fun SimklRepository.getWatchingShowsImpl(
    accessToken: String =
        trackedAccessToken()
): SimklWatchingShowsResponse {

    require(
        clientId.isNotBlank()
    ) {
        "SIMKL_CLIENT_ID is missing"
    }

    return api.getWatchingShows(
        authorization =
            trackedAuthHeaderFor(
                accessToken
            ),

        dateFrom =
            null,

        extended =
            "full"
    )
}

/**
 * Library totals for the connect screen: how many distinct shows and
 * movies the Simkl account has any watch history for. Shows come from
 * the cached all-shows library (the same source Continue Watching
 * resolves against) and movies from the all-items movies endpoint.
 * Returns null on failure so the UI can simply hide the counters.
 */
suspend fun SimklRepository.getWatchedCountsImpl(): SimklWatchedCounts? {
    val accessToken =
        runCatching { trackedAccessToken() }.getOrNull()
            ?: return null

    // Both legs are forced fresh so the pair is a coherent snapshot:
    // the old mix (shows from a 12h-cached blob + movies fetched live)
    // made the two numbers disagree on every visit — the screen always
    // displayed counts from two different moments.
    //
    // Series counts only WATCHING + COMPLETED shows: the all-items
    // endpoint returns every list (including plantowatch / hold /
    // dropped), and "SERIES WATCHED" over-counts when it includes
    // shows never actually watched. Matches what Simkl's own site
    // reports as watched.
    val watchedShowStatuses = setOf("watching", "completed")
    val shows =
        runCatching {
            getAllShowItemsCached(
                accessToken = accessToken,
                forceRefresh = true
            )
        }.getOrNull()?.shows
            ?.count { it.status?.lowercase()?.trim() in watchedShowStatuses }

    val movies =
        runCatching {
            api.getAllMovieItems(
                authorization = trackedAuthHeaderFor(accessToken),
                dateFrom = null,
                extended = "min"
            ).body()?.movies?.size
        }.getOrNull()

    if (shows == null && movies == null) return null
    return SimklWatchedCounts(
        series = shows ?: 0,
        movies = movies ?: 0
    )
}

/**
 * The connected account's identity for the connect screen ("Signed in
 * as …"). Best-effort: null on any failure so the UI simply omits the
 * line. No caching — the call is cheap and correctness here matters
 * more than latency (this is how the user verifies WHICH profile's
 * Simkl they are looking at).
 */
suspend fun SimklRepository.getAccountInfoImpl(): SimklUser? {
    val accessToken =
        runCatching { trackedAccessToken() }.getOrNull()
            ?: return null

    return runCatching {
        api.getUserSettings(
            authorization = trackedAuthHeaderFor(accessToken)
        ).user
    }.getOrNull()
}

/**
 * The pure decision rules behind the watched markers, kept out of
 * [SimklRepository] so they can be unit-tested without an Android context.
 */
object ShowCompletionRules {

    private val FINISHED_STATUSES =
        setOf("completed", "ended", "canceled")

    /**
     * The completed CHECKMARK rule.
     *
     * Simkl's own episode tally decides whenever it has one: every episode of
     * the show watched means finished. Watching every AIRED episode of a show
     * that is still airing is "caught up", not "completed" — the old
     * aired-total rule returned true there, which painted the finished
     * checkmark over shows the user was still in the middle of and hid the
     * eye marker from a show marked up to the current progress. The tally
     * also outranks the show's list status, because unmarking part of a show
     * (or a tracker that keeps a stale "completed" status) leaves a
     * completed status over an incomplete tally — that is not finished
     * either.
     *
     * With no tally to go on, the show's status and a waiting next episode
     * are all there is: a finished status with nothing left to watch counts
     * as finished, a listed next episode does not.
     */
    fun isFullyWatched(
        status: String?,
        watchedEpisodesCount: Int?,
        totalEpisodesCount: Int?,
        nextToWatch: String?
    ): Boolean {

        val watched =
            watchedEpisodesCount ?: 0

        val total =
            totalEpisodesCount ?: 0

        if (
            total > 0
        ) {
            return watched >= total
        }

        if (
            !nextToWatch.isNullOrBlank()
        ) {
            return false
        }

        val normalizedStatus =
            status
                ?.trim()
                ?.lowercase()

        return normalizedStatus in FINISHED_STATUSES
    }

    /**
     * Continue Watching's INCLUSION rule for a watching-feed show: is this
     * show worth a rail card because the user has something left to watch?
     *
     * The show's list status must not veto this on its own. Marking a series
     * watched whole-show sets Simkl's status to "completed", and unmarking a
     * season removes the episodes WITHOUT resetting that status - so keying
     * the rail off the status alone kept a show the user had just made
     * resumable again hidden from Continue Watching forever.
     *
     * The episode tally is the authority whenever it exists: nothing aired
     * left to watch means nothing to resume (the caught-up show stays off).
     * Only with no tally at all does the status fall back to deciding, and
     * only a genuinely finished show with nothing queued next is excluded.
     * The one unconditional exclusion is "dropped": the user took it off
     * their list, so it never belongs on the rail.
     */
    fun isContinueWatchingCandidate(
        status: String?,
        watchedEpisodesCount: Int?,
        totalEpisodesCount: Int?,
        notAiredEpisodesCount: Int?,
        nextToWatch: String?
    ): Boolean {

        val normalizedStatus =
            status
                ?.trim()
                ?.lowercase()

        // Deliberately off the user's list: never a rail card.
        if (
            normalizedStatus == "dropped"
        ) {
            return false
        }

        val watched =
            watchedEpisodesCount ?: 0

        val total =
            totalEpisodesCount ?: 0

        val notAired =
            notAiredEpisodesCount ?: 0

        val airedTotal =
            if (total > 0) {
                total - notAired
            } else {
                0
            }

        // The tally decides when it exists: every aired episode watched
        // means there is nothing to resume.
        if (
            airedTotal > 0
        ) {
            return watched < airedTotal
        }

        // No tally, but Simkl is queueing an episode to watch next.
        if (
            !nextToWatch.isNullOrBlank()
        ) {
            return true
        }

        return normalizedStatus !in FINISHED_STATUSES
    }

    /**
     * Continue Watching's rule: true when every AIRED episode is watched,
     * even though more episodes may still be coming. The rail uses this to
     * keep a caught-up show off it (there is nothing to resume), while the
     * badge rule above stays strict - a caught-up show is still "started,
     * not finished", so it shows the eye marker.
     */
    fun isCaughtUpOnAiredEpisodes(
        watchedEpisodesCount: Int?,
        totalEpisodesCount: Int?,
        notAiredEpisodesCount: Int?
    ): Boolean {

        val watched =
            watchedEpisodesCount ?: 0

        val total =
            totalEpisodesCount ?: 0

        val notAired =
            notAiredEpisodesCount ?: 0

        val airedTotal =
            if (total > 0) {
                total - notAired
            } else {
                0
            }

        return airedTotal > 0 &&
            watched >= airedTotal
    }

    /**
     * A paused tracker session this far along is not a resume point.
     *
     * The player itself treats 95% as finished ([FINISHED_PLAYBACK_PERCENT]
     * mirrors NativePlayerActivity's COMPLETION_THRESHOLD_RATIO): an episode
     * played to that point is marked complete locally, so a tracker session
     * still reporting it - and Simkl marks watched on a stop at 80%, so a
     * session that late which is still OPEN was never closed - is a leftover
     * record rather than somewhere to resume. This is the card that showed an
     * episode the user never started as "99% watched".
     */
    fun isFinishedPlaybackSession(
        progressPercent: Float?
    ): Boolean =
        (progressPercent ?: 0f) >=
            FINISHED_PLAYBACK_PERCENT

    /** Progress at/above which a tracker playback session counts as finished. */
    const val FINISHED_PLAYBACK_PERCENT =
        95f

    /**
     * The show statuses a caught-up Upcoming card may come from.
     *
     * "completed" has to be one of them. A show the user has finished
     * everything aired of reads as COMPLETED on the tracker, and Simkl only
     * moves it back to "watching" once the new season's first episode AIRS - so
     * a watching-only rule hides exactly the case the rail exists for: a show
     * whose new season is announced. A finished-and-staying-finished show is
     * still kept out by the tally (it has nothing unaired), and the statuses
     * that mean "not following" stay out below this list.
     */
    private val UPCOMING_FOLLOWED_STATUSES =
        setOf("watching", "completed")

    /**
     * The Upcoming rail's caught-up rule: a show the account is CAUGHT UP
     * on, still being followed, with episodes Simkl already knows are
     * UNAIRED.
     *
     * Caught-up shows are deliberately kept off Continue Watching (there is
     * nothing to resume), which used to mean they surfaced nowhere - a
     * returning show's new season never showed up in Upcoming either. This is
     * that gap closed: ANY next unaired episode qualifies, whether it opens a
     * season or lands mid-season, because a show the user has watched
     * everything aired of and is still following has upcoming content either
     * way.
     */
    fun isCaughtUpUpcomingCandidate(
        status: String?,
        watchedEpisodesCount: Int?,
        totalEpisodesCount: Int?,
        notAiredEpisodesCount: Int?
    ): Boolean {

        // Only shows the user is still following. "dropped" is off the list
        // on purpose, "hold" is a deliberate pause, and anything never
        // started is not caught up on anything.
        if (
            status
                ?.trim()
                ?.lowercase() !in UPCOMING_FOLLOWED_STATUSES
        ) {
            return false
        }

        // Nothing unaired means nothing to announce. Cheap pre-filter too:
        // the rail looks the show up on TMDB, so unaired episodes Simkl
        // already knows about are what justify that call.
        if (
            (notAiredEpisodesCount ?: 0) <= 0
        ) {
            return false
        }

        return isCaughtUpOnAiredEpisodes(
            watchedEpisodesCount =
                watchedEpisodesCount,

            totalEpisodesCount =
                totalEpisodesCount,

            notAiredEpisodesCount =
                notAiredEpisodesCount
        )
    }
}

internal fun SimklRepository.isCaughtUpOnAiredEpisodes(
    item: SimklWatchingShowItem
): Boolean =
    ShowCompletionRules.isCaughtUpOnAiredEpisodes(
        watchedEpisodesCount =
            item.watchedEpisodesCount,

        totalEpisodesCount =
            item.totalEpisodesCount,

        notAiredEpisodesCount =
            item.notAiredEpisodesCount
    )

internal fun SimklRepository.isShowFullyWatched(
    item: SimklWatchingShowItem
): Boolean =
    ShowCompletionRules.isFullyWatched(
        status =
            item.status,

        watchedEpisodesCount =
            item.watchedEpisodesCount,

        totalEpisodesCount =
            item.totalEpisodesCount,

        nextToWatch =
            item.nextToWatch
    )

internal fun SimklRepository.isContinueWatchingCandidate(
    item: SimklWatchingShowItem
): Boolean =
    ShowCompletionRules.isContinueWatchingCandidate(
        status =
            item.status,

        watchedEpisodesCount =
            item.watchedEpisodesCount,

        totalEpisodesCount =
            item.totalEpisodesCount,

        notAiredEpisodesCount =
            item.notAiredEpisodesCount,

        nextToWatch =
            item.nextToWatch
    )

/**
 * The Upcoming rail's caught-up candidates: shows the account is caught up on
 * while Simkl still knows of UNAIRED episodes.
 *
 * Returned in the same wire shape the Continue Watching feed uses, so the
 * rail enriches them through the exact path it already has (TMDB detail,
 * artwork, next-episode-to-air) instead of a second one. These cards are
 * Upcoming-only: a caught-up show is never a Continue Watching card.
 *
 * Costs no request of its own - the all-shows library is the blob the
 * watched-state resolution already reads, cached for 15 minutes in memory
 * and half a day on disk.
 */
suspend fun SimklRepository.getCaughtUpUnreleasedShowsImpl():
    List<SimklContinueWatchingItem> {

    if (
        clientId.isBlank()
    ) {
        return emptyList()
    }

    val accessToken =
        runCatching {
            trackedAccessToken()
        }.getOrNull()
            ?.takeIf {
                it.isNotBlank()
            }
            ?: return emptyList()

    val body =
        runCatching {
            getAllShowItemsCached(
                accessToken =
                    accessToken
            )
        }.getOrNull()
            ?: return emptyList()

    return body.shows
        .asSequence()
        .filter { item ->

            val show =
                item.show
                    ?: return@filter false

            // An id the rail can navigate and ask TMDB about.
            val hasId =
                !show.ids
                    ?.imdb
                    .isNullOrBlank() ||
                    (show.ids?.tmdb ?: 0) > 0

            hasId &&
                ShowCompletionRules.isCaughtUpUpcomingCandidate(
                    status =
                        item.status,

                    watchedEpisodesCount =
                        item.watchedEpisodesCount,

                    totalEpisodesCount =
                        item.totalEpisodesCount,

                    notAiredEpisodesCount =
                        item.notAiredEpisodesCount
                )
        }
        .mapNotNull { item ->

            val show =
                item.show
                    ?: return@mapNotNull null

            val imdbId =
                show.ids
                    ?.imdb
                    ?.takeIf {
                        it.isNotBlank()
                    }

            SimklContinueWatchingItem(
                id =
                    "caught-up-" +
                        (
                            show.ids
                                ?.simkl
                                ?.toString()
                                ?: imdbId
                                ?: show.ids
                                    ?.tmdb
                                    ?.toString()
                                ?: return@mapNotNull null
                            ),

                imdbId =
                    imdbId,

                tmdbId =
                    show.ids
                        ?.tmdb,

                simklId =
                    show.ids
                        ?.simkl,

                title =
                    show.title
                        ?: "Untitled show",

                year =
                    show.year,

                // No poster here on purpose: the rail's builder replaces
                // this from the TMDB detail it has to fetch anyway (the
                // next-episode-to-air that decides whether the show even
                // belongs on the rail), so a Simkl poster path would only be
                // a second source of the same artwork.
                posterUrl =
                    null,

                lastWatchedAt =
                    item.lastWatchedAt,

                // No paused session behind these: the rail only needs the
                // show, its next episode comes from TMDB.
                progress =
                    null,

                upNextText =
                    null,

                mediaType =
                    "series",

                source =
                    "watching",

                season =
                    null,

                episode =
                    null
            )
        }
        .toList()
}

internal fun SimklRepository.isShowFullyWatched(
    item: SimklWatchingShowDetailedItem
): Boolean =
    ShowCompletionRules.isFullyWatched(
        status =
            item.status,

        watchedEpisodesCount =
            item.watchedEpisodesCount,

        totalEpisodesCount =
            item.totalEpisodesCount,

        nextToWatch =
            item.nextToWatch
    )
