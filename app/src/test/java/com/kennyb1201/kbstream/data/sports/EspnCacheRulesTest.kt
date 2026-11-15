package com.kennyb1201.kbstream.data.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The cache rules that decide WHICH slate is read.
 *
 * Pure, and here rather than in the repository, because each is arithmetic a
 * unit test can pin exactly: one is a timezone, one is "a game in progress must
 * not vanish when the feed's day rolls over" (audit C1's first half,
 * 2026-10-23), and one is the other side of the same rollover - the viewer's own
 * day, whose evening the feed has already dated tomorrow (C1's second half).
 */
class EspnCacheRulesTest {

    /**
     * ESPN groups its scoreboard by US EASTERN day - the endpoint's own default
     * window - not by the device's. A Pacific viewer's 9 PM tip is already
     * outside it.
     */
    @Test
    fun `the feed's day is Eastern, not the device's`() {
        // 03:00 UTC is 11 PM on the PREVIOUS day, Eastern (EDT).
        assertEquals("20261023", EspnCacheRules.espnDay(espnDateMs("2026-10-24T03:00Z")))
        // 05:00 UTC is 1 AM Eastern, so the day has rolled.
        assertEquals("20261024", EspnCacheRules.espnDay(espnDateMs("2026-10-24T05:00Z")))
    }

    /**
     * The other side of the same rollover: the viewer's own day.
     *
     * A Pacific evening is already tomorrow in the feed's calendar, so the
     * default window answers the NEXT slate and the games that finished earlier
     * that evening - the ones in the FINAL group a moment ago - are on the day
     * the endpoint has stopped asking for (audit C1's second half, 2026-10-23).
     */
    @Test
    fun `a west-coast evening also asks for the viewer's own day`() {
        val pacific = java.time.ZoneId.of("America/Los_Angeles")
        val eastern = java.time.ZoneId.of("America/New_York")
        // 9:30 PM in California on 23 October is 12:30 AM on the 24th Eastern:
        // the feed has rolled over; the viewer has not.
        val evening = espnDateMs("2026-10-24T04:30Z")
        assertEquals("20261024", EspnCacheRules.espnDay(evening))
        assertEquals("20261023", EspnCacheRules.viewerDay(evening, pacific))
        assertEquals("20261023", EspnCacheRules.earlierViewerDay(evening, pacific))

        // An Eastern viewer's day IS the feed's: no second window.
        assertNull(EspnCacheRules.earlierViewerDay(evening, eastern))
        // 6 PM Pacific is 9 PM Eastern - same date, and the default window
        // already answers that evening.
        assertNull(EspnCacheRules.earlierViewerDay(espnDateMs("2026-10-24T01:00Z"), pacific))
        // East of the feed the viewer's date is AHEAD, so asking for their day
        // would fetch a slate they are not watching.
        assertNull(EspnCacheRules.earlierViewerDay(evening, java.time.ZoneId.of("Europe/London")))
    }

    @Test
    fun `a live game that outlived its day asks for that day again`() {
        // The reported shape: a 10:30 PM ET tip, still playing after midnight.
        // The default request has moved on to the next slate, so the game is
        // gone from `fresh` - and this is the one case worth a second read.
        val tipOff = espnDateMs("2026-10-24T02:30Z") // 10:30 PM ET on the 23rd
        val now = espnDateMs("2026-10-24T05:30Z") // 1:30 AM ET on the 24th
        val live = game(id = "g1", dateMs = tipOff, state = GameState.LIVE)

        assertEquals(
            "20261023",
            EspnCacheRules.carryOverDay(cached = listOf(live), fresh = emptyList(), nowMs = now)
        )
        // Still in the fresh window: nothing extra to read.
        assertNull(EspnCacheRules.carryOverDay(listOf(live), listOf(live), now))
        // Same Eastern day: the default window already covers it.
        assertNull(
            EspnCacheRules.carryOverDay(
                cached = listOf(live),
                fresh = emptyList(),
                nowMs = tipOff + hour
            )
        )
    }

    @Test
    fun `only a game in progress is carried - a finished or upcoming one is not`() {
        val tipOff = espnDateMs("2026-10-24T02:30Z")
        val now = espnDateMs("2026-10-24T05:30Z")

        // A final game leaving the window is the feed moving on, not a fault.
        assertNull(
            EspnCacheRules.carryOverDay(
                cached = listOf(game(id = "g1", dateMs = tipOff, state = GameState.FINAL)),
                fresh = emptyList(),
                nowMs = now
            )
        )
        // Neither is an undated fixture, whatever its state: there is no day to
        // ask for.
        assertNull(
            EspnCacheRules.carryOverDay(
                cached = listOf(game(id = "g2", dateMs = 0L, state = GameState.LIVE)),
                fresh = emptyList(),
                nowMs = now
            )
        )
    }

    // ── The second window (college football's FCS slate) ─────────────────

    @Test
    fun `two windows become one list, primary first, matched by id`() {
        // The FCS window rides behind the default one: a game in both is ONE
        // game, and the copy that stays is the default window's - the one the
        // endpoint answered for the viewer's own day.
        val primary = game(id = "fbs", dateMs = espnDateMs("2026-11-15T20:00Z"), GameState.UPCOMING)
        val shared = game(id = "shared", dateMs = espnDateMs("2026-11-15T21:00Z"), GameState.LIVE)
        val staleCopy = game(id = "shared", dateMs = espnDateMs("2026-11-15T21:00Z"), GameState.UPCOMING)
        val fcs = game(id = "fcs", dateMs = espnDateMs("2026-11-15T22:00Z"), GameState.LIVE)

        val merged = EspnCacheRules.mergeSubdivision(listOf(primary, shared), listOf(staleCopy, fcs))

        assertEquals(listOf("fbs", "shared", "fcs"), merged.map { it.id })
        assertSame(shared, merged[1])
    }

    @Test
    fun `an empty or absent second window leaves the slate exactly as it was`() {
        val primary = listOf(game(id = "fbs", dateMs = 1L, GameState.LIVE))

        assertSame(primary, EspnCacheRules.mergeSubdivision(primary, emptyList()))
    }

    @Test
    fun `a failed FCS read keeps tonight's games and drops older finals`() {
        // S5: the tab must not blank when the second window fails. It keeps what
        // it last showed while that is still worth showing - a game in progress,
        // or one dated today - and does not resurrect yesterday's finals.
        val now = espnDateMs("2026-11-15T04:00Z") // 11 PM ET on the 14th
        val liveLastNight = game(id = "live-old", dateMs = espnDateMs("2026-11-14T02:00Z"), GameState.LIVE)
        val finalTonight = game(id = "final-today", dateMs = espnDateMs("2026-11-15T01:00Z"), GameState.FINAL)
        val finalLastNight = game(id = "final-old", dateMs = espnDateMs("2026-11-14T02:00Z"), GameState.FINAL)
        val undated = game(id = "undated", dateMs = 0L, GameState.UPCOMING)
        val stillListed = game(id = "fresh", dateMs = espnDateMs("2026-11-15T01:00Z"), GameState.LIVE)

        val carried = EspnCacheRules.carryStaleSubdivision(
            cached = listOf(liveLastNight, finalTonight, finalLastNight, undated, stillListed),
            fresh = listOf(stillListed),
            nowMs = now
        )

        assertEquals(listOf("live-old", "final-today"), carried.map { it.id })
    }

    private val hour = 60L * 60_000L

    private fun game(id: String, dateMs: Long, state: GameState) = SportsGame(
        id = id,
        league = "basketball/nba",
        name = "Lakers at Celtics",
        dateMs = dateMs,
        state = state,
        statusDetail = "",
        away = SportsTeam(
            abbreviation = "LAL",
            displayName = "Los Angeles Lakers",
            logoUrl = null,
            score = null,
            isHome = false,
            record = null,
        ),
        home = SportsTeam(
            abbreviation = "BOS",
            displayName = "Boston Celtics",
            logoUrl = null,
            score = null,
            isHome = true,
            record = null,
        ),
        broadcastNames = emptyList(),
    )
}
