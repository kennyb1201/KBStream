package com.kennyb1201.kbstream.data.history

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The streak rule and the runtime format, pinned directly.
 *
 * The numbers on the stats screen come from the history table, but the one
 * calculation that is easy to get subtly wrong - which completions count as
 * consecutive days - is pure, so it is tested here rather than only inspected
 * on a device. The same goes for the "312 h" format, which must never round up:
 * the screen says "finished", and overstating it would be the one dishonest
 * number on the page.
 */
class ViewingStatsTest {

    private val day = ViewingStats.DAY_MS

    /** Noon UTC on day [d] since the epoch, so bucketing is unambiguous. */
    private fun noon(d: Long): Long = d * day + 12 * 3_600_000L

    @Test
    fun `no completions is no streak`() {
        assertEquals(0, ViewingStats.streakDays(emptyList(), noon(20_000)))
    }

    @Test
    fun `three consecutive days ending today is a three day streak`() {
        val now = noon(20_000)
        val times = listOf(noon(20_000), noon(19_999), noon(19_998))
        assertEquals(3, ViewingStats.streakDays(times, now))
    }

    @Test
    fun `a missed day breaks the streak at the gap`() {
        val now = noon(20_000)
        val times = listOf(noon(20_000), noon(19_998), noon(19_997))
        assertEquals(1, ViewingStats.streakDays(times, now))
    }

    @Test
    fun `a streak last alive yesterday is still counted`() {
        // Nothing watched today yet: the streak is unbroken until a whole day
        // passes with no completion.
        val now = noon(20_000)
        val times = listOf(noon(19_999), noon(19_998))
        assertEquals(2, ViewingStats.streakDays(times, now))
    }

    @Test
    fun `a streak whose newest completion is two days old is over`() {
        // Neither today nor yesterday has a completion, so nothing is alive to
        // continue: the streak is 0, not 1.
        val now = noon(20_000)
        assertEquals(0, ViewingStats.streakDays(listOf(noon(19_998)), now))
    }

    @Test
    fun `many completions on one day are one day`() {
        val now = noon(20_000)
        val times = listOf(noon(20_000) + 1, noon(20_000) + 2, noon(20_000) + 3)
        assertEquals(1, ViewingStats.streakDays(times, now))
    }

    @Test
    fun `finished runtime is whole hours and never rounds up`() {
        assertEquals("312 h", ViewingStats.formatFinishedRuntime(312L * 3_600_000L))
        // 311 h 59 m stays 311 - rounding up would overstate what was finished.
        assertEquals(
            "311 h",
            ViewingStats.formatFinishedRuntime(311L * 3_600_000L + 3_599_000L)
        )
        assertEquals("0 h", ViewingStats.formatFinishedRuntime(0L))
    }
}
