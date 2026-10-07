package com.kennyb1201.kbstream.data.history

/**
 * One title's line in the stats screen's top-shows list: the runtime it has
 * accrued (finished durations plus the saved position of its open episodes),
 * and how many of its rows are completed.
 *
 * A plain data class so Room can map the [WatchHistoryDao.topShows] projection
 * onto it by column name.
 */
data class TopShow(
    val parentId: String,
    val name: String,
    val poster: String?,
    val ms: Long,
    val done: Int
)

/**
 * The pure half of the viewing-stats screen.
 *
 * The screen reads its numbers from the history table through
 * [WatchHistoryDao]; everything here is arithmetic on those numbers, kept out
 * of the composable (and off the database) so the one rule that is easy to get
 * subtly wrong - the streak - is pinned by a unit test.
 *
 * Honest metric, read this first: history keeps ONE row per episode and updates
 * it in place, so a rewatch or a scrub overwrites the previous value. Exact
 * watch time is therefore not recoverable. What the numbers mean:
 *  - finished runtime = Σ durationMs over completed rows (each counted once);
 *  - in-progress runtime = Σ positionMs over the rows still open;
 *  - a rewound-then-rewatched episode is one row and counts once.
 * The screen labels all of it "finished", never "time watched".
 */
object ViewingStats {

    /** One UTC day in milliseconds: the bucket the streak counts in. */
    const val DAY_MS = 86_400_000L

    /**
     * The current streak: the number of consecutive UTC days ending today (or
     * yesterday, so a streak is still alive before the viewer watches anything
     * today) that carry at least one completion.
     *
     * [times] are completion timestamps in epoch milliseconds, newest or oldest
     * first - order does not matter. 0 when there are no completions, and 1 when
     * today is the only completion or every previous day has a gap.
     */
    fun streakDays(times: List<Long>, nowMs: Long): Int {
        if (times.isEmpty()) return 0
        // Distinct UTC days, newest first.
        val days = times
            .map { it / DAY_MS }
            .toSortedSet()
            .toList()
            .sortedDescending()

        val today = nowMs / DAY_MS
        // A streak whose newest completion is older than yesterday is broken.
        if (days.first() < today - 1) return 0

        var streak = 1
        for (i in 1 until days.size) {
            if (days[i] == days[i - 1] - 1) streak++ else break
        }
        return streak
    }

    /**
     * Finished runtime as whole hours, e.g. "312 h". Rounded down: the screen
     * shows whole hours, and rounding up would overstate what was finished.
     */
    fun formatFinishedRuntime(ms: Long): String = "${ms / 3_600_000} h"
}
