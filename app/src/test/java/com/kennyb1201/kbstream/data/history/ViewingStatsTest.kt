package com.kennyb1201.kbstream.data.history

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The streak rule and the runtime format, pinned directly.
 *
 * The numbers on the stats screen come from the history table, but the two
 * calculations that are easy to get subtly wrong - which completions count as
 * consecutive days, and how a finished runtime is worded - are pure, so they are
 * tested here rather than only inspected on a device.
 *
 * The day-boundary tests pass their zone EXPLICITLY. That is the point of the
 * parameter: the rule is "consecutive CALENDAR days in the viewer's own zone",
 * and a test that leaned on the machine's default zone would pass in CI (usually
 * UTC) while the field case - a viewer an evening behind or ahead of UTC - went
 * unexercised. The UTC cases below are kept deliberately: they are the rule the
 * screen used to run, so the difference is pinned rather than described.
 */
class ViewingStatsTest {

    private val day = ViewingStats.DAY_MS

    private val utc = ZoneId.of("UTC")
    private val newYork = ZoneId.of("America/New_York")

    /** Noon UTC on day [d] since the epoch, so bucketing is unambiguous. */
    private fun noon(d: Long): Long = d * day + 12 * 3_600_000L

    /** A wall-clock instant in [zone], for the local-day cases. */
    private fun at(zone: ZoneId, year: Int, month: Int, dayOfMonth: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, dayOfMonth, hour, minute)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    @Test
    fun `no completions is no streak`() {
        assertEquals(0, ViewingStats.streakDays(emptyList(), noon(20_000), utc))
    }

    @Test
    fun `three consecutive days ending today is a three day streak`() {
        val now = noon(20_000)
        val times = listOf(noon(20_000), noon(19_999), noon(19_998))
        assertEquals(3, ViewingStats.streakDays(times, now, utc))
    }

    @Test
    fun `a missed day breaks the streak at the gap`() {
        val now = noon(20_000)
        val times = listOf(noon(20_000), noon(19_998), noon(19_997))
        assertEquals(1, ViewingStats.streakDays(times, now, utc))
    }

    @Test
    fun `a streak last alive yesterday is still counted`() {
        // Nothing watched today yet: the streak is unbroken until a whole day
        // passes with no completion.
        val now = noon(20_000)
        val times = listOf(noon(19_999), noon(19_998))
        assertEquals(2, ViewingStats.streakDays(times, now, utc))
    }

    @Test
    fun `a streak whose newest completion is two days old is over`() {
        // Neither today nor yesterday has a completion, so nothing is alive to
        // continue: the streak is 0, not 1.
        val now = noon(20_000)
        assertEquals(0, ViewingStats.streakDays(listOf(noon(19_998)), now, utc))
    }

    @Test
    fun `many completions on one day are one day`() {
        val now = noon(20_000)
        val times = listOf(noon(20_000) + 1, noon(20_000) + 2, noon(20_000) + 3)
        assertEquals(1, ViewingStats.streakDays(times, now, utc))
    }

    // ── The day boundaries are the viewer's, not UTC's (stats C5) ──────────

    @Test
    fun `three consecutive local evenings are a three day streak`() {
        // The field case: a viewer five hours behind UTC watching on three
        // consecutive evenings. In UTC these three completions fall on {Mar 3,
        // Mar 5} - a gap - which is how a real three-day streak reported 1.
        val evenings = listOf(
            at(newYork, 2026, 3, 2, 23, 30), // Mar 3, 04:30 UTC
            at(newYork, 2026, 3, 3, 0, 30),  // Mar 3, 05:30 UTC
            at(newYork, 2026, 3, 4, 21, 0)   // Mar 5, 01:00 UTC
        )

        assertEquals(
            "three consecutive local evenings are three days",
            3,
            ViewingStats.streakDays(evenings, at(newYork, 2026, 3, 4, 22, 0), newYork)
        )
        assertEquals(
            "and the same completions are the one-day UTC streak this rule replaced",
            1,
            ViewingStats.streakDays(evenings, evenings.last(), utc)
        )
    }

    @Test
    fun `one local evening is one day even across UTC midnight`() {
        // Two completions in one evening, either side of UTC midnight.
        val evening = listOf(
            at(newYork, 2026, 3, 2, 18, 30), // Mar 2, 23:30 UTC
            at(newYork, 2026, 3, 2, 19, 30)  // Mar 3, 00:30 UTC
        )

        assertEquals(
            "one evening is one day, so there is no second day to count",
            1,
            ViewingStats.streakDays(evening, evening.last(), newYork)
        )
        assertEquals(
            "in UTC the same evening minted two days out of one",
            2,
            ViewingStats.streakDays(evening, evening.last(), utc)
        )
    }

    // ── The format (stats I1) ─────────────────────────────────────────────

    @Test
    fun `finished runtime is floored, in hours above an hour and minutes below`() {
        assertEquals("312 h", ViewingStats.formatFinishedRuntime(312L * 3_600_000L))
        // 311 h 59 m stays 311 - rounding up would overstate what was finished.
        assertEquals(
            "311 h",
            ViewingStats.formatFinishedRuntime(311L * 3_600_000L + 3_599_000L)
        )
        // The hour boundary, both sides.
        assertEquals("1 h", ViewingStats.formatFinishedRuntime(3_600_000L))
        assertEquals("59 min", ViewingStats.formatFinishedRuntime(59L * 60_000L))
        // The case the hour-only format lost: one finished 45-minute episode
        // used to rank as "0 h · 1 episode finished", i.e. as nothing watched.
        assertEquals("45 min", ViewingStats.formatFinishedRuntime(45L * 60_000L))
        // And nothing finished is minutes rather than "0 h", which read like a
        // runtime instead of like none.
        assertEquals("0 min", ViewingStats.formatFinishedRuntime(0L))
    }
}
