package com.kennyb1201.kbstream.data.history

import java.time.Instant
import java.time.ZoneId

/**
 * One title's line in the stats screen's top-shows list: the runtime it has
 * accrued (finished durations plus the saved position of its open episodes),
 * and how many of its rows are completed.
 *
 * A plain data class so Room can map the [WatchHistoryDao.topShows] projection
 * onto it by column name.
 */
data class TopShow(
    /**
     * The row's canonical parent id, with the `tmdb:<n>` / `tt…` flavors folded
     * together (see [WatchHistoryDao.topShows]) - so it is unique per title,
     * which is also what the screen keys its list by.
     */
    val parentId: String,
    val name: String,
    val poster: String?,
    /**
     * The parent's media type, from its newest row. The list ranks every title
     * and movies are titles too (the headline tiles count them separately, so
     * the list cannot pretend they are not finished), and this is what lets a
     * row say "movie finished" rather than filing a film under "1 episode
     * finished" (stats C2).
     */
    val type: String,
    val ms: Long,
    val done: Int
)

/**
 * The pure half of the viewing-stats screen.
 *
 * The screen reads its numbers from the history table through
 * [WatchHistoryDao]; everything here is arithmetic on those numbers, kept out
 * of the composable (and off the database) so the rules that are easy to get
 * subtly wrong - the streak's day boundaries, the runtime format - are pinned
 * by unit tests.
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

    /**
     * One day in milliseconds: the unit the streak's tests space their
     * timestamps by. The streak itself counts CALENDAR days in the viewer's own
     * zone rather than dividing timestamps by this - see [streakDays].
     */
    const val DAY_MS = 86_400_000L

    /**
     * The current streak: the number of consecutive days ending today (or
     * yesterday, so a streak is still alive before the viewer watches anything
     * today) that carry at least one completion.
     *
     * [times] are completion timestamps in epoch milliseconds, newest or oldest
     * first - order does not matter. 0 when there are no completions, and 1 when
     * today is the only completion or every previous day has a gap.
     *
     * The days are the viewer's LOCAL days, not UTC ones. The first version
     * bucketed each timestamp by integer-dividing it by the day length, i.e. by
     * UTC midnight: for a viewer an evening behind UTC, watching on three
     * consecutive evenings could read as a one-day streak (Monday 23:30 local =
     * Tuesday 04:30 UTC, Tuesday 00:30 = Tuesday 05:30 UTC, Wednesday 21:00 =
     * Thursday 01:00 UTC is {Tue, Thu} - a gap, where the viewer really did
     * watch three days running), and one evening straddling UTC midnight could
     * mint two days out of one. [zone] is the viewer's own zone at the call site
     * and a parameter here so the rule can be pinned at a fixed zone (stats C5).
     */
    fun streakDays(times: List<Long>, nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Int {
        if (times.isEmpty()) return 0
        // Distinct local days, newest first.
        val days = times
            .map { localDay(it, zone) }
            .distinct()
            .sortedDescending()

        val today = localDay(nowMs, zone)
        // A streak whose newest completion is older than yesterday is broken.
        if (days.first() < today - 1) return 0

        var streak = 1
        for (i in 1 until days.size) {
            if (days[i] == days[i - 1] - 1) streak++ else break
        }
        return streak
    }

    /** The calendar day a timestamp falls on, as an epoch-day number. */
    private fun localDay(ms: Long, zone: ZoneId): Long =
        Instant.ofEpochMilli(ms).atZone(zone).toLocalDate().toEpochDay()

    /**
     * Finished runtime in the largest whole unit that still says something:
     * whole hours from an hour up ("312 h"), whole minutes below that
     * ("45 min"). Rounded down, never up - the screen says "finished", and
     * overstating it would be the one dishonest number on the page.
     *
     * The minute step is here because hours alone read as "0 h" for anything
     * shorter than an hour: a show with a single finished 45-minute episode was
     * ranked as "0 h · 1 episode finished", i.e. as nothing watched (stats I1).
     */
    fun formatFinishedRuntime(ms: Long): String {
        val hours = ms / 3_600_000L
        return if (hours > 0) "$hours h" else "${ms / 60_000L} min"
    }
}
