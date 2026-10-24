package com.kennyb1201.kbstream.data.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The two cache rules that decide WHICH slate is read.
 *
 * Pure, and here rather than in the repository, because both are arithmetic a
 * unit test can pin exactly: one is a timezone, the other is "a game in progress
 * must not vanish when the feed's day rolls over" (audit C1, 2026-10-23).
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
