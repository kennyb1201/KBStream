package com.kennyb1201.kbstream.data.bingecat

/**
 * The profile's watched timeline, folded into what the Upcoming rail needs:
 * every show the feed lists watched episodes for, and WHICH episodes those were.
 *
 * The rail's caught-up test asks one question - is the show's last aired episode
 * in the evidence? (see `LocalSeriesProgress.isCaughtUpOnLastAired`) - so the
 * feed's rows, which are "one show's plays on one local day", are unioned per
 * show. That is the same shape Trakt's watched snapshot takes, which is what
 * lets the two trackers feed the same leg with no second rule.
 *
 * [shows] keeps the feed's own order (newest first), so a cap trims the shows
 * this account has not touched in the longest time.
 */
data class BingeCatWatchedSnapshot(
    val shows: List<BingeCatWatchedShow>
)

/** One show in [BingeCatWatchedSnapshot], by an id the rest of the app uses. */
data class BingeCatWatchedShow(
    val parentId: String,
    val episodes: Set<Pair<Int, Int>>
)

/**
 * Folds a feed's rows into [BingeCatWatchedSnapshot].
 *
 * Three rules, each the same one the MDBList and Trakt halves already apply:
 *
 *  - **Only shows.** A movie row has no next episode to wait for, and its
 *    `episodes[]` is empty anyway.
 *  - **Identity is IMDb first, then `tmdb:<n>`.** Both are what local history's
 *    parent ids look like and what the other trackers are keyed by, so the same
 *    show arriving from two sources collapses into one card downstream
 *    (`selectUpcomingPerShow`). A row with neither id is skipped rather than
 *    guessed at - including the contract's `slug`-only and `bingecat`-id-only
 *    media, which this app has no page for.
 *  - **A row with no usable episode proves nothing.** An unplaceable play
 *    (null season or number) is dropped, and a show whose every play was
 *    unplaceable does not appear: "watched sometime" is not "watched the aired
 *    run", the same skip the other two tracker halves make.
 *
 * Pure and top-level so the fold is a unit test: the repository's job is only
 * the paged read.
 */
fun bingeCatWatchedSnapshot(
    items: List<BingeCatHistoryItem>
): BingeCatWatchedSnapshot {
    val byParent = LinkedHashMap<String, MutableSet<Pair<Int, Int>>>()

    items.forEach { item ->
        if (item.type == "movie" || item.kind == "movie") return@forEach

        val media = item.show ?: return@forEach
        val parentId = bingeCatShowKey(media) ?: return@forEach

        val watched = byParent.getOrPut(parentId) { mutableSetOf() }
        item.episodes.forEach { play ->
            val season = play.season
            val episode = play.number
            if (season != null && season > 0 && episode != null && episode > 0) {
                watched += season to episode
            }
        }
    }

    return BingeCatWatchedSnapshot(
        shows = byParent
            .filterValues { watched -> watched.isNotEmpty() }
            .map { (parentId, watched) ->
                BingeCatWatchedShow(parentId = parentId, episodes = watched.toSet())
            }
    )
}

/**
 * The parent id this app knows a show by: its IMDb id when the feed carries
 * one, else `tmdb:<n>`. Null for a media object with neither.
 */
fun bingeCatShowKey(media: BingeCatMedia): String? {
    val imdb = media.ids?.imdb?.takeIf { it.isNotBlank() }
    if (imdb != null) return imdb

    val tmdb = media.ids?.tmdb?.takeIf { it > 0 }
    return tmdb?.let { "tmdb:$it" }
}
