package com.kennyb1201.kbstream.data.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Continue Watching widget's arithmetic (spec 2026-11-13, F1): progress, time
 * left, the `S1E2 · Name` line, and the freshness/stale copy.
 *
 * Every one of these is what a row PRINTS, and a wrong number here is what the
 * field tests catch last (a bar at 90% on a five-minute episode looks plausible).
 */
class CwWidgetRulesTest {

    @Test
    fun `progress is the played fraction, clamped`() {
        assertEquals(0.5f, CwWidgetRules.progress(1_500_000L, 3_000_000L), 0.0001f)
        assertEquals(1f, CwWidgetRules.progress(4_000_000L, 3_000_000L), 0.0001f)
    }

    @Test
    fun `progress is zero without a duration`() {
        assertEquals(0f, CwWidgetRules.progress(1_000L, 0L), 0.0001f)
        assertEquals(0f, CwWidgetRules.progress(0L, 2_000L), 0.0001f)
        assertEquals(0f, CwWidgetRules.progress(-5L, 2_000L), 0.0001f)
    }

    @Test
    fun `minutes left rounds up, so a playing episode never reads zero`() {
        assertEquals(2, CwWidgetRules.remainingMinutes(1_700_000L, 1_800_000L))
        assertEquals(0, CwWidgetRules.remainingMinutes(1_800_000L, 1_800_000L))
        assertEquals(null, CwWidgetRules.remainingMinutes(1_000L, 0L))
    }

    @Test
    fun `subtitle joins numbering and episode title`() {
        assertEquals("S2E5 · The Long Night", CwWidgetRules.subtitle(2, 5, "The Long Night"))
        assertEquals("S2E5", CwWidgetRules.subtitle(2, 5, null))
        assertEquals("S2E5", CwWidgetRules.subtitle(2, 5, "   "))
        assertEquals("A Film", CwWidgetRules.subtitle(null, null, "A Film"))
        assertEquals("", CwWidgetRules.subtitle(null, null, null))
        // Episode 0 is "no episode" in this codebase (see numberedEpisode): it is
        // not S2E0.
        assertEquals("Pilot", CwWidgetRules.subtitle(2, 0, "Pilot"))
    }

    @Test
    fun `time left reads in minutes then hours`() {
        assertEquals("12m left", CwWidgetRules.timeLeftLabel(12))
        assertEquals("1h left", CwWidgetRules.timeLeftLabel(60))
        assertEquals("1h 12m left", CwWidgetRules.timeLeftLabel(72))
        assertEquals("", CwWidgetRules.timeLeftLabel(0))
        assertEquals("", CwWidgetRules.timeLeftLabel(null))
    }

    @Test
    fun `freshness line is honest about age and staleness`() {
        val now = 1_700_000_000_000L
        assertEquals("Updated just now", CwWidgetRules.freshnessLabel(now - 5_000L, now))
        assertEquals("Updated 5m ago", CwWidgetRules.freshnessLabel(now - 5L * 60_000L, now))
        assertEquals("Updated 2h ago", CwWidgetRules.freshnessLabel(now - 2L * 3_600_000L, now))
        assertEquals(
            "Stale · open the app to refresh",
            CwWidgetRules.freshnessLabel(now - CwWidgetRules.STALE_AFTER_MS - 1L, now)
        )
        assertEquals(
            "Open the app to refresh",
            CwWidgetRules.freshnessLabel(0L, now)
        )
    }

    @Test
    fun `staleness needs an old write, and a missing one counts as stale`() {
        val now = 1_700_000_000_000L
        assertFalse(CwWidgetRules.isStale(now - 60_000L, now))
        assertTrue(CwWidgetRules.isStale(now - CwWidgetRules.STALE_AFTER_MS - 1L, now))
        assertTrue(CwWidgetRules.isStale(0L, now))
    }

    @Test
    fun `the widget never claims more rows than the cap`() {
        assertEquals(8, CwWidgetRules.MAX_ROWS)
    }

    @Test
    fun `a rendered row prefers the rail's own progress and minutes`() {
        val row = CwWidgetRow(
            parentType = "series",
            parentId = "tmdb:1",
            showTitle = "Show",
            season = 1,
            episode = 2,
            positionMs = 900_000L,
            progressPercent = 0.25f,
            remainingMinutes = 7,
            durationMs = 10_000_000L
        )
        assertEquals(0.25f, row.progress, 0.0001f)
        assertEquals(7, row.minutesLeft)
        assertEquals("7m left", row.timeLeft)
        assertEquals("S1E2", row.subtitle)
    }

    @Test
    fun `a row without rail values falls back to its own playhead`() {
        val row = CwWidgetRow(
            parentType = "movie",
            parentId = "tmdb:2",
            showTitle = "Film",
            positionMs = 600_000L,
            durationMs = 2_400_000L
        )
        assertEquals(0.25f, row.progress, 0.0001f)
        assertEquals(30, row.minutesLeft)
    }
}
