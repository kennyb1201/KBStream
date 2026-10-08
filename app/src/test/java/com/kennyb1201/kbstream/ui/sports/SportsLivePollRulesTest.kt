package com.kennyb1201.kbstream.ui.sports

import com.kennyb1201.kbstream.data.sports.GameState
import com.kennyb1201.kbstream.data.sports.SportsGame
import com.kennyb1201.kbstream.data.sports.SportsLeagues
import com.kennyb1201.kbstream.data.sports.SportsTeam
import com.kennyb1201.kbstream.data.sports.TournamentEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live tick's decision: whether to poll at all, and which leagues one poll
 * covers.
 *
 * Pinned here because the ViewModel that owns the Job cannot be observed in CI
 * (it wants an Application, a network and a clock), and because the two faults
 * this prevents are both silent: polling a league nobody is watching wastes the
 * viewer's data, and polling a finished slate forever is a battery drain that
 * never shows up as a bug report.
 */
class SportsLivePollRulesTest {

    private fun team(abbreviation: String, id: String? = null, name: String = abbreviation) =
        SportsTeam(
            id = id,
            abbreviation = abbreviation,
            displayName = name,
            logoUrl = null,
            score = null,
            isHome = false,
            record = null,
        )

    private fun game(
        id: String,
        league: String,
        state: GameState,
        away: SportsTeam = team("AWY"),
        home: SportsTeam = team("HOM"),
    ) = SportsGame(
        id = id,
        league = league,
        name = "away at home",
        dateMs = 0L,
        state = state,
        statusDetail = "",
        away = away,
        home = home,
        broadcastNames = emptyList(),
    )

    private fun event(id: String, league: String, state: GameState) = TournamentEvent(
        id = id,
        league = league,
        name = "event",
        dateMs = 0L,
        state = state,
        statusDetail = "",
        leaders = emptyList(),
        broadcastNames = emptyList(),
    )

    private fun section(
        path: String,
        games: List<SportsGame> = emptyList(),
        tournaments: List<TournamentEvent> = emptyList(),
    ) = LeagueSection(
        league = SportsLeagues.byPath(path) ?: SportsLeagues.FAVORITES,
        games = games,
        tournaments = tournaments,
    )

    private val nfl = section("football/nfl", games = listOf(game("1", "football/nfl", GameState.LIVE)))
    private val nba = section("basketball/nba", games = listOf(game("2", "basketball/nba", GameState.FINAL)))

    @Test
    fun `a league with a live game is polled`() {
        assertEquals(
            listOf("football/nfl"),
            SportsLivePollRules.liveLeagues(listOf(nfl, nba), "football/nfl", emptySet())
        )
    }

    @Test
    fun `a league with nothing in play is not polled`() {
        assertTrue(
            SportsLivePollRules.liveLeagues(listOf(nfl, nba), "basketball/nba", emptySet()).isEmpty()
        )
    }

    @Test
    fun `no selection polls nothing`() {
        assertTrue(SportsLivePollRules.liveLeagues(listOf(nfl), null, emptySet()).isEmpty())
    }

    @Test
    fun `an unknown selection polls nothing`() {
        // The selected league's section has not arrived yet: there is nothing to
        // poll for, and the next refresh re-aims the tick.
        assertTrue(
            SportsLivePollRules.liveLeagues(listOf(nfl), "hockey/nhl", emptySet()).isEmpty()
        )
    }

    @Test
    fun `the favourites tab polls the leagues holding a followed live game`() {
        val followed = team("NYY", id = "10")
        val stranger = team("BOS", id = "11")
        val followedLeague = section(
            "baseball/mlb",
            games = listOf(game("3", "baseball/mlb", GameState.LIVE, away = followed, home = stranger))
        )
        val otherLeague = section(
            "basketball/nba",
            games = listOf(game("4", "basketball/nba", GameState.LIVE, away = stranger, home = team("LAL", id = "13")))
        )

        assertEquals(
            listOf("baseball/mlb"),
            SportsLivePollRules.liveLeagues(
                sections = listOf(followedLeague, otherLeague),
                selectedPath = SportsLeagues.FAVORITES_PATH,
                favoriteKeys = setOf("10"),
            )
        )
    }

    @Test
    fun `the favourites tab polls nothing when none of your games are live`() {
        assertTrue(
            SportsLivePollRules.liveLeagues(
                sections = listOf(nfl),
                selectedPath = SportsLeagues.FAVORITES_PATH,
                favoriteKeys = setOf("10"),
            ).isEmpty()
        )
    }

    @Test
    fun `a live tournament counts as something in play`() {
        val golf = section("golf/pga", tournaments = listOf(event("g1", "golf/pga", GameState.LIVE)))
        assertTrue(SportsLivePollRules.hasLive(golf))
        assertEquals(
            listOf("golf/pga"),
            SportsLivePollRules.liveLeagues(listOf(golf), "golf/pga", emptySet())
        )
    }

    @Test
    fun `an empty section is not live`() {
        assertFalse(SportsLivePollRules.hasLive(section("football/nfl")))
        assertFalse(SportsLivePollRules.hasLive(null))
    }
}
