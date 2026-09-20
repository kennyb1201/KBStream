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

internal fun SimklRepository.isShowFullyWatched(
    item: SimklWatchingShowItem
): Boolean {

    val status =
        item.status
            ?.trim()
            ?.lowercase()

    val watched =
        item.watchedEpisodesCount ?: 0

    val total =
        item.totalEpisodesCount ?: 0

    val notAired =
        item.notAiredEpisodesCount ?: 0

    val airedTotal =
        if (total > 0) {
            total - notAired
        } else {
            0
        }

    if (
        airedTotal > 0 &&
        watched >= airedTotal
    ) {
        return true
    }

    val hasNext =
        !item.nextToWatch
            .isNullOrBlank()

    val isFinishedStatus =
        status == "completed" ||
            status == "ended" ||
            status == "canceled"

    if (
        isFinishedStatus &&
        !hasNext
    ) {
        return true
    }

    if (
        total > 0 &&
        watched >= total
    ) {
        return true
    }

    return false
}

internal fun SimklRepository.isShowFullyWatched(
    item: SimklWatchingShowDetailedItem
): Boolean {

    val status =
        item.status
            ?.trim()
            ?.lowercase()

    val watched =
        item.watchedEpisodesCount ?: 0

    val total =
        item.totalEpisodesCount ?: 0

    val notAired =
        item.notAiredEpisodesCount ?: 0

    val airedTotal =
        if (total > 0) {
            total - notAired
        } else {
            0
        }

    if (
        airedTotal > 0 &&
        watched >= airedTotal
    ) {
        return true
    }

    val hasNext =
        !item.nextToWatch
            .isNullOrBlank()

    val isFinishedStatus =
        status == "completed" ||
            status == "ended" ||
            status == "canceled"

    if (
        isFinishedStatus &&
        !hasNext
    ) {
        return true
    }

    if (
        total > 0 &&
        watched >= total
    ) {
        return true
    }

    return false
}
