package com.kennyb1201.kbstream.ui.sports

import com.kennyb1201.kbstream.data.sports.GameState
import com.kennyb1201.kbstream.data.sports.SportsGame
import com.kennyb1201.kbstream.data.sports.SportsTeam
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The order the hub's EPG pass asks its matchup questions in (deep dive F2).
 *
 * The pass stops at a ceiling, and it used to walk the slate league by league -
 * so on a big Saturday the first league's games filled the ceiling and the last
 * leagues got no guide question at all, reading "Not in your playlist" for a
 * game the guide carried. Round-robin across leagues is the fix; these cases pin
 * it to "every league's first matchup survives the ceiling", not to any
 * particular league order.
 */
class SportsEpgOrderTest {

    @Test
    fun `every league's first matchup is asked before any league's second`() {
        val slate = listOf(
            game("football/ncaaf", "a1"),
            game("football/ncaaf", "a2"),
            game("football/ncaaf", "a3"),
            game("basketball/nba", "b1"),
            game("basketball/nba", "b2"),
            game("hockey/nhl", "c1"),
        )

        assertEquals(
            listOf("a1", "b1", "c1", "a2", "b2", "a3"),
            epgMatchupOrder(slate).map { it.id }
        )
    }

    @Test
    fun `the leagues keep the feed's order and each league keeps its slate order`() {
        val slate = listOf(
            game("soccer/eng.1", "s1"),
            game("soccer/eng.1", "s2"),
            game("basketball/nba", "n1"),
        )

        // The interleave never re-orders inside a league, and the leagues appear
        // in the order they first appeared on the slate.
        assertEquals(listOf("s1", "n1", "s2"), epgMatchupOrder(slate).map { it.id })
    }

    @Test
    fun `a single-league slate is left exactly as it was`() {
        // The ordinary case: one league enabled, well under the ceiling, so the
        // fairness rule must be invisible.
        val slate = listOf(game("football/nfl", "1"), game("football/nfl", "2"), game("football/nfl", "3"))
        assertEquals(slate.map { it.id }, epgMatchupOrder(slate).map { it.id })
    }

    @Test
    fun `no game is dropped or asked twice`() {
        val slate = (1..7).map { game("league/${it % 3}", "g$it") }

        val ordered = epgMatchupOrder(slate)

        assertEquals(slate.size, ordered.size)
        assertEquals(slate.map { it.id }.toSet(), ordered.map { it.id }.toSet())
        assertEquals(ordered.size, ordered.map { it.id }.distinct().size)
    }

    @Test
    fun `an empty slate stays empty`() {
        assertEquals(emptyList<SportsGame>(), epgMatchupOrder(emptyList()))
    }

    private fun game(league: String, id: String) = SportsGame(
        id = id,
        league = league,
        name = id,
        dateMs = 0L,
        state = GameState.UPCOMING,
        statusDetail = "7:30 PM",
        away = side("AWY", home = false),
        home = side("HOM", home = true),
        broadcastNames = emptyList(),
    )

    private fun side(code: String, home: Boolean) = SportsTeam(
        abbreviation = code,
        displayName = code,
        logoUrl = null,
        score = null,
        isHome = home,
        record = null,
    )
}
