package com.kennyb1201.kbstream.ui.sports

import com.kennyb1201.kbstream.data.sports.GameState
import com.kennyb1201.kbstream.data.sports.SportsGame
import com.kennyb1201.kbstream.data.sports.SportsKind
import com.kennyb1201.kbstream.data.sports.SportsLeague
import com.kennyb1201.kbstream.data.sports.SportsTeam
import com.kennyb1201.kbstream.data.sports.TournamentEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The lookup that reopens the hub's sheet after Back from a game.
 *
 * Two facts are pinned, and they are the two that a TV in CI cannot show. The
 * first is the ordinary case: the id the player was launched with is the id of
 * a game the hub has loaded, so the sheet comes back on that same game. The
 * second is the one that must not misbehave - an id that names nothing on the
 * board, because the game has dropped off it (it went final and the section
 * refilled, the league was switched off, or the hub simply has not loaded it
 * yet) - and there the answer is null, so the hub renders as the hub instead of
 * raising an empty sheet or crashing. Nothing here fetches, which the signature
 * itself guarantees: sections in, an object or null out.
 */
class SportsReopenRulesTest {

    private fun league(path: String) = SportsLeague(path, "NBA", SportsKind.HEAD_TO_HEAD)

    private fun team(abbreviation: String, name: String, home: Boolean) = SportsTeam(
        abbreviation = abbreviation,
        displayName = name,
        logoUrl = null,
        score = null,
        isHome = home,
        record = null,
        shortName = null
    )

    private fun game(id: String) = SportsGame(
        id = id,
        league = "basketball/nba",
        name = "Celtics at Lakers",
        dateMs = 1_700_000_000_000L,
        state = GameState.LIVE,
        statusDetail = "Q3 4:32",
        away = team("BOS", "Celtics", home = false),
        home = team("LAL", "Lakers", home = true),
        broadcastNames = emptyList()
    )

    private fun tournament(id: String) = TournamentEvent(
        id = id,
        league = "golf/pga",
        name = "The Open - Round 2",
        dateMs = 1_700_000_000_000L,
        state = GameState.LIVE,
        statusDetail = "Round 2",
        leaders = emptyList(),
        broadcastNames = emptyList()
    )

    private fun sections(games: List<String>, tournaments: List<String> = emptyList()) =
        listOf(
            LeagueSection(
                league = league("basketball/nba"),
                games = games.map { game(it) }
            ),
            LeagueSection(
                league = league("golf/pga"),
                tournaments = tournaments.map { tournament(it) }
            )
        )

    @Test
    fun `a game on the board is found by the id the player carried`() {
        val board = sections(games = listOf("401", "402", "403"))
        val found = SportsReopenRules.game(board, "402")

        assertEquals("the sheet reopens on the game the id names", "402", found?.id)
        assertSame(
            "and it is the section's own object, not a copy",
            board.first().games[1],
            found
        )
    }

    @Test
    fun `a tournament on the board is found the same way`() {
        val board = sections(games = listOf("401"), tournaments = listOf("ncaa-9"))

        assertEquals("ncaa-9", SportsReopenRules.tournament(board, "ncaa-9")?.id)
    }

    @Test
    fun `a game that dropped off the board reopens nothing`() {
        val board = sections(games = listOf("401", "402"))

        assertNull(
            "the field-report case: the id is real, the game is gone",
            SportsReopenRules.game(board, "499")
        )
        assertNull(
            "and a tournament id names no game",
            SportsReopenRules.tournament(board, "499")
        )
    }

    @Test
    fun `the two lookups never cross the id spaces`() {
        val board = sections(games = listOf("401"), tournaments = listOf("ncaa-9"))

        assertNull(
            "a tournament is not a game",
            SportsReopenRules.game(board, "ncaa-9")
        )
        assertNull(
            "and a game is not a tournament",
            SportsReopenRules.tournament(board, "401")
        )
    }

    @Test
    fun `no id at all opens nothing, which is every other way into the hub`() {
        val board = sections(games = listOf("401"), tournaments = listOf("ncaa-9"))

        assertNull(SportsReopenRules.game(board, null))
        assertNull(SportsReopenRules.tournament(board, null))
        assertNull(
            "a blank id is not an id, so it can never match by accident",
            SportsReopenRules.game(board, "")
        )
    }

    @Test
    fun `a hub whose sections have not loaded yet cannot reopen anything`() {
        assertNull(SportsReopenRules.game(emptyList(), "401"))
        assertNull(SportsReopenRules.tournament(emptyList(), "ncaa-9"))
    }
}
