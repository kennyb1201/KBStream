package com.kennyb1201.kbstream.ui.sports

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hub's day arithmetic (spec audit 2026-11-18, Sports M2).
 *
 * Pure, with an explicit zone, because the whole finding was that the hub had no
 * notion of a day at all: the rules have to be checkable without a feed, a page
 * or a device, and the day a viewer means must not drift with the JVM's default
 * zone between runs.
 */
class SportsDayRulesTest {

    private val eastern = ZoneId.of("America/New_York")

    /** 2026-11-18 12:00 Eastern, a Wednesday. */
    private fun noon(): Long =
        Instant.parse("2026-11-18T17:00:00Z").toEpochMilli()

    private fun endOfDayEastern(): Long =
        Instant.parse("2026-11-19T04:30:00Z").toEpochMilli()

    // ── The three days ─────────────────────────────────────────────────────

    @Test
    fun `the row is yesterday, today, tomorrow, in that order`() {
        assertEquals(
            listOf(SportsDayRules.YESTERDAY, SportsDayRules.TODAY, SportsDayRules.TOMORROW),
            SportsDayRules.offsets
        )
        assertEquals(
            listOf("Yesterday", "Today", "Tomorrow"),
            SportsDayRules.offsets.map { SportsDayRules.label(it) }
        )
    }

    @Test
    fun `an offset outside the row is clamped into it`() {
        // The chips can only be pressed, but a caller that computes an offset
        // (a swipe, a future shortcut) must not be able to ask for last week.
        assertEquals(SportsDayRules.YESTERDAY, SportsDayRules.clamp(-7))
        assertEquals(SportsDayRules.TOMORROW, SportsDayRules.clamp(4))
        assertEquals(SportsDayRules.TODAY, SportsDayRules.clamp(0))
        assertEquals("Yesterday", SportsDayRules.label(-3))
    }

    // ── The ESPN parameter ─────────────────────────────────────────────────

    @Test
    fun `each day sends the date the endpoint reads`() {
        // BASIC_ISO_DATE, the same shape EspnCacheRules writes for its carry-over
        // windows: "20261117", "20261118", "20261119".
        assertEquals("20261117", SportsDayRules.dayParam(-1, noon(), eastern))
        assertEquals("20261118", SportsDayRules.dayParam(0, noon(), eastern))
        assertEquals("20261119", SportsDayRules.dayParam(1, noon(), eastern))
    }

    @Test
    fun `the day is the viewer's own calendar day, not the feed's`() {
        // 11:30 PM in New York is already TOMORROW in UTC, and "tomorrow" from
        // the viewer's chair is their own midnight - which is the whole reason
        // the offset, not a stored date, is the state.
        val lateNight = Instant.parse("2026-11-19T04:30:00Z").toEpochMilli()
        assertEquals("20261118", SportsDayRules.dayParam(0, lateNight, eastern))
        assertEquals("20261119", SportsDayRules.dayParam(1, lateNight, eastern))
        assertEquals("20261117", SportsDayRules.dayParam(-1, lateNight, eastern))
    }

    // ── Placing an undated-for-the-endpoint source ─────────────────────────

    @Test
    fun `a fixture is on the day it starts, in the viewer's zone`() {
        val now = noon()
        val tonight = Instant.parse("2026-11-19T00:30:00Z").toEpochMilli() // 7:30 PM ET
        val lastNight = Instant.parse("2026-11-18T00:30:00Z").toEpochMilli()

        assertTrue(SportsDayRules.containsMs(0, now, tonight, eastern))
        assertFalse(SportsDayRules.containsMs(1, now, tonight, eastern))
        assertTrue(SportsDayRules.containsMs(-1, now, lastNight, eastern))
        assertTrue(
            "the same instant is tomorrow in UTC, and the viewer is not in UTC",
            SportsDayRules.containsMs(1, now, Instant.parse("2026-11-19T05:30:00Z").toEpochMilli(), eastern)
        )
    }

    @Test
    fun `an undated fixture is on no day`() {
        // The same rule the cache rules use for an undated item: there is no
        // number to compare, so it cannot be placed - and treating 0 as "now"
        // would put it on today by accident.
        assertFalse(SportsDayRules.containsMs(0, noon(), 0L, eastern))
        assertFalse(SportsDayRules.containsMs(-1, noon(), -1L, eastern))
    }

    // ── What the day changes elsewhere ─────────────────────────────────────

    @Test
    fun `only today can have a game in play`() {
        assertTrue(SportsDayRules.pollsLive(0))
        assertFalse(SportsDayRules.pollsLive(-1))
        assertFalse(SportsDayRules.pollsLive(1))
    }

    @Test
    fun `an empty slate names its day and the way back`() {
        assertEquals(
            "No NFL games on the schedule right now.",
            SportsDayRules.emptyMessage(0, "NFL")
        )
        assertEquals(
            "No NFL games on tomorrow. Pick TODAY to come back.",
            SportsDayRules.emptyMessage(1, "NFL")
        )
        assertEquals(
            "No NBA games on yesterday. Pick TODAY to come back.",
            SportsDayRules.emptyMessage(-1, "NBA")
        )
    }

    @Test
    fun `the favorites empty sentence keeps its own wording on today`() {
        assertEquals(
            "None of your teams are playing right now.",
            SportsDayRules.emptyFavoritesMessage(0)
        )
        assertEquals(
            "None of your teams are playing tomorrow. Pick TODAY to come back.",
            SportsDayRules.emptyFavoritesMessage(1)
        )
    }

    // ── The page's own input ───────────────────────────────────────────────

    @Test
    fun `the chip row and the day parameter cannot disagree`() {
        // One list drives the chips and one function maps an offset to a date; a
        // chip added without a date would be a dead press, so every offset in the
        // row must produce a distinct, ordered day.
        // 11:30 PM Eastern on the 18th: the row is still the 17th/18th/19th,
        // because the viewer's day has not turned over yet.
        val days = SportsDayRules.offsets.map { SportsDayRules.dayParam(it, endOfDayEastern(), eastern) }
        assertEquals(listOf("20261117", "20261118", "20261119"), days)
        assertEquals(days, days.distinct())
    }
}
