package com.kennyb1201.kbstream.ui.home

/**
 * How the up-next rail picks and ranks its cards.
 *
 * Extracted from HomeViewModel so the rules that decide what the
 * viewer sees on the rail - which duplicates collapse, which card
 * wins a group, and how the survivors are ordered - can be read (and
 * tested) without the seven-thousand-line class around them. The
 * vocabulary they build on lives in HomeUpNext.kt and
 * UpNextRailOrder.kt.
 */

internal fun dedupeAndSortUpNext(
    items: List<UpNextItem>
): List<UpNextItem> {

    return clusterByIdentityKeys(
        collapseDuplicateUpNextCards(
            items
        )
    ) { item ->
        upNextGroupingKeys(item)
    }
        .mapNotNull { candidates ->

            candidates.maxWithOrNull(

                compareBy<UpNextItem> {
                    winnerScore(it)
                }
                    .thenByDescending {
                        it.recencyTimestamp
                    }

                    .thenBy {
                        targetPrecisionScore(it)
                    }

                    .thenBy {
                        it.title.lowercase()
                    }
            )
        }
        // Watching first, most recently watched first, and the news
        // behind it - see UpNextRailOrder.kt for why that order is the
        // rail's whole point, and UpNextRailOrderTest for the rule.
        .sortedWith(upNextRailComparator)
}

internal fun showDedupeKey(
    item: UpNextItem
): String =
    upNextShowKey(item)

internal fun winnerScore(
    item: UpNextItem
): Int {

    var score =
        0

    if (
        item.badge ==
            UpNextBadge.CONTINUE_WATCHING
    ) {
        score += 5_000
    }

    // Prefer entries that actually have calculated
    // remaining playback time.
    if (
        item.remainingMinutes != null &&
        item.remainingMinutes > 0
    ) {
        score += 1_000
    }

    if (
        item.startPositionMs > 0L ||
        (item.progressPercent ?: 0f) > 0f
    ) {
        score += 2_500
    }

    if (
        !item.episodeStreamId
            .isNullOrBlank()
    ) {
        score += 500
    }

    if (
        item.season != null &&
        item.episode != null
    ) {
        score += 250
    }

    return score
}

internal fun targetPrecisionScore(
    item: UpNextItem
): Int {

    var score =
        0

    if (
        !item.episodeStreamId
            .isNullOrBlank()
    ) {
        score += 3
    }

    if (
        valueOrDefault(
            item.season,
            0
        ) != 0
    ) {
        score += 2
    }

    if (
        item.episode != null
    ) {
        score += 2
    }

    if (
        !item.streamUrl
            .isNullOrBlank()
    ) {
        score += 1
    }

    if (
        !item.poster
            .isNullOrBlank()
    ) {
        score += 1
    }

    return score
}

internal fun valueOrDefault(
    value: Int?,
    default: Int
): Int =
    value ?: default
