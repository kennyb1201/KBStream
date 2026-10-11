package com.kennyb1201.kbstream.ui.sports

import com.kennyb1201.kbstream.data.sports.GameState
import com.kennyb1201.kbstream.data.sports.SportsGame
import com.kennyb1201.kbstream.data.sports.SportsTeam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The league tabs' order: the viewer's own games first, everything else exactly
 * as ESPN sent it.
 *
 * Three facts, and the third is the one that keeps a poll from churning the
 * list - a favorites-first sort is only worth having if it is STABLE, because
 * the hub re-reads its sections every 30 seconds and an unstable sort would
 * reshuffle two equal games on every tick. The rest is the no-op promises: no
 * favorites means the very same list, and a league none of the followed teams
 * play in keeps its own order.
 */
class SportsGameOrderTest {

    private fun team(id: String, name: String) = SportsTeam(
        id = id,
        abbreviation = name.take(3).uppercase(),
        displayName = name,
        logoUrl = null,
        score = null,
        isHome = false,
        record = null,
        shortName = null
    )

    /** A game keyed by its id, between two teams named after it. */
    private fun game(id: String) = SportsGame(
        id = id,
        league = "basketball/nba",
        name = "$id at $id",
        dateMs = 1_700_000_000_000L,
        state = GameState.LIVE,
        statusDetail = "Q3 4:32",
        away = team("away-$id", "Away $id"),
        home = team("home-$id", "Home $id"),
        broadcastNames = emptyList()
    )

    private fun idsOf(games: List<SportsGame>) = games.map { it.id }

    @Test
    fun `the followed teams' games lead, and nothing else moves`() {
        val games = listOf(game("A"), game("B"), game("C"), game("D"))
        val keys = setOf("away-A", "home-C")

        assertEquals(
            "A and C float, B and D keep their order behind them",
            listOf("A", "C", "B", "D"),
            idsOf(SportsGameOrder.favoritesFirst(games, keys))
        )
    }

    @Test
    fun `the sort is stable, so a re-poll cannot churn the list`() {
        val games = listOf(game("A"), game("B"), game("C"), game("D"), game("E"))
        val keys = setOf("away-A", "home-C", "away-D")

        val first = SportsGameOrder.favoritesFirst(games, keys)
        val second = SportsGameOrder.favoritesFirst(games, keys)

        assertEquals("A, C, D float in ESPN order among themselves", listOf("A", "C", "D"), idsOf(first).take(3))
        assertEquals("and B, E keep theirs", listOf("B", "E"), idsOf(first).drop(3))
        assertEquals("the same input orders the same way twice", idsOf(first), idsOf(second))
    }

    @Test
    fun `a game where both sides are followed still sorts once`() {
        val games = listOf(game("A"), game("B"))
        val keys = setOf("away-A", "home-A", "away-B")

        assertEquals(
            "a doubly-followed game is one favorite, not two",
            listOf("A", "B"),
            idsOf(SportsGameOrder.favoritesFirst(games, keys))
        )
    }

    @Test
    fun `no favorites at all is the feed's own list, the same instance`() {
        val games = listOf(game("A"), game("B"), game("C"))

        assertSame(
            "a viewer who follows nobody gets the list it was, not a copy of it",
            games,
            SportsGameOrder.favoritesFirst(games, emptySet())
        )
    }

    @Test
    fun `a league none of the followed teams play in keeps its order`() {
        val games = listOf(game("A"), game("B"), game("C"))

        assertEquals(
            "every game is a non-favorite, so the order is untouched",
            listOf("A", "B", "C"),
            idsOf(SportsGameOrder.favoritesFirst(games, setOf("someone-else")))
        )
    }

    @Test
    fun `an empty slate orders to nothing`() {
        assertEquals(emptyList<String>(), idsOf(SportsGameOrder.favoritesFirst(emptyList(), setOf("away-A"))))
        assertEquals(emptyList<String>(), idsOf(SportsGameOrder.favoritesFirst(emptyList(), emptySet())))
    }
}
