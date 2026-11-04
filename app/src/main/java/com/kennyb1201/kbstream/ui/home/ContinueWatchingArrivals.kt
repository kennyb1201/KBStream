package com.kennyb1201.kbstream.ui.home

import com.kennyb1201.kbstream.data.settings.ResumeAuthority
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The tracker feeds merged beside the local resume cards, in the order the rail
 * merges them (see [ContinueWatchingArrivals]).
 */
internal enum class TrackerFeed {
    SIMKL,
    MDBLIST,
    TRAKT
}

/**
 * Continue Watching's rail as the tracker feeds arrive, one publish per arrival.
 *
 * The rail already published its local cards first and left the trackers to a
 * single merged publish once ALL of them had answered, so a slow tracker held
 * the entire enriched rail even though its own cards were ready. This holds what
 * has landed so far and republishes on every arrival instead: local cards, then
 * each feed's cards the moment that feed answers.
 *
 * What the incremental publishes must not change is the contents. Every publish
 * runs the same funnel the single merged publish ran - the locally-finished
 * filter over the tracker cards, [dedupeAndSortUpNext] over local-then-tracker,
 * then the dismissals - and the merge order is fixed rather than arrival-ordered,
 * so the rail's final contents are the merged publish's contents whatever order
 * the feeds answered in. A feed's slot is REPLACED when it answers, never
 * appended to, so a fresh result cannot arrive beside its own stale cards; a feed
 * that has not answered leaves its previous cards (seeded from the rail that was
 * already on screen) exactly where they were.
 *
 * [publish] is the sink: the caller puts the snapshot on screen, gated by its own
 * request version. [dismiss] is passed in as a suspending filter because it reads
 * and rewrites the viewer's preferences, and it runs on EVERY publish - a
 * dismissed title must not flash back when a tracker lands late.
 */
internal class ContinueWatchingArrivals(
    private val localCards: List<UpNextItem>,
    private val resumeAuthority: ResumeAuthority,
    previousTrackerCards: List<UpNextItem>,
    private val locallyFinished: (List<UpNextItem>) -> List<UpNextItem>,
    private val dismiss: suspend (List<UpNextItem>) -> List<UpNextItem>,
    private val publish: suspend (List<UpNextItem>) -> Unit
) {

    /**
     * Each feed's latest answer. Seeded with the cards already on the rail so
     * the local-first publish keeps them - replacing the rail with the local
     * cards alone is what used to make every tracker card vanish until the
     * merged publish landed.
     */
    private val feeds = HashMap<TrackerFeed, List<UpNextItem>>()

    /**
     * Serialises arrivals, so two feeds answering within the same frame publish
     * one after the other rather than racing the snapshot they both build on.
     */
    private val arrivals = Mutex()

    init {
        feeds[TrackerFeed.SIMKL] = previousTrackerCards
    }

    /** Publishes the local cards plus whatever trackers already answered. */
    suspend fun publishLocal() {
        arrivals.withLock { publish(snapshot()) }
    }

    /**
     * One feed answered, or answered with nothing (an unconfigured tracker).
     *
     * On FAILURE the caller does not call this at all: no answer is not an empty
     * answer, and the feed's previous cards stay on the rail.
     */
    suspend fun arrive(
        feed: TrackerFeed,
        items: List<UpNextItem>
    ) {
        arrivals.withLock {
            feeds[feed] = items
            publish(snapshot())
        }
    }

    private suspend fun snapshot(): List<UpNextItem> {

        val trackerCards =
            locallyFinished(
                MERGE_ORDER.flatMap { feed -> feeds[feed].orEmpty() }
            )

        val merged =
            dedupeAndSortUpNext(
                localCards + trackerCards,
                resumeAuthority
            )

        // The merged publish's own fallback: a rail of local cards is worth
        // showing when every tracker answered with nothing.
        val result =
            when {
                merged.isNotEmpty() -> merged
                localCards.isNotEmpty() -> dedupeAndSortUpNext(localCards, resumeAuthority)
                else -> emptyList()
            }

        return dismiss(result)
    }

    private companion object {
        /**
         * Today's merge order, kept as the merge order rather than as arrival
         * order: the dedupe keeps the first card of a show and the ranking
         * decides the rest, so this is what makes the final contents
         * independent of which tracker answered first.
         */
        val MERGE_ORDER = listOf(TrackerFeed.SIMKL, TrackerFeed.MDBLIST, TrackerFeed.TRAKT)
    }
}
