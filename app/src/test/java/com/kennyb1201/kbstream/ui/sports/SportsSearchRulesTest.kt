package com.kennyb1201.kbstream.ui.sports

import com.kennyb1201.kbstream.data.sports.GameState
import com.kennyb1201.kbstream.data.sports.SportsGame
import com.kennyb1201.kbstream.data.sports.SportsKind
import com.kennyb1201.kbstream.data.sports.SportsLeague
import com.kennyb1201.kbstream.data.sports.SportsLeagues
import com.kennyb1201.kbstream.data.sports.SportsTeam
import com.kennyb1201.kbstream.data.sports.TournamentEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hub's search, which is a FILTER over cards the hub has already loaded.
 *
 * Two things are pinned here, and they are the two the spec calls out. First,
 * what a query finds: a team by its nickname ("lightning"), its abbreviation
 * ("lal"), its full name or its city - the same names the EPG tier matches on,
 * so a game a viewer can find is a game the matcher can also place. Second, what
 * it does NOT do: search a league the hub has not fetched (there is nothing to
 * search, and asking would be a fetch), and treat a blank query as a search at
 * all (blank means "the hub as it was").
 */
class SportsSearchRulesTest {

    private fun team(
        abbreviation: String,
        name: String,
        home: Boolean,
        shortName: String? = null
    ) = SportsTeam(
        abbreviation = abbreviation,
        displayName = name,
        logoUrl = null,
        score = null,
        isHome = home,
        record = null,
        shortName = shortName
    )

    private fun game(
        id: String,
        league: String,
        away: SportsTeam,
        home: SportsTeam,
    ) = SportsGame(
        id = id,
        league = league,
        name = "${away.displayName} at ${home.displayName}",
        dateMs = 1_700_000_000_000L,
        state = GameState.UPCOMING,
        statusDetail = "7:30 PM ET",
        away = away,
        home = home,
        broadcastNames = emptyList()
    )

    private fun event(id: String, name: String, venue: String?) = TournamentEvent(
        id = id,
        league = "golf/pga",
        name = name,
        dateMs = 1_700_000_000_000L,
        state = GameState.LIVE,
        statusDetail = "Round 3",
        leaders = emptyList(),
        broadcastNames = emptyList(),
        venue = venue
    )

    private fun section(
        path: String,
        label: String,
        games: List<SportsGame> = emptyList(),
        tournaments: List<TournamentEvent> = emptyList()
    ) = LeagueSection(
        league = SportsLeague(path, label, SportsKind.HEAD_TO_HEAD),
        games = games,
        tournaments = tournaments
    )

    // A slate that spans three leagues and contains two different Tampa Bay
    // teams - the case the hub's field has to get right, since "tampa bay" on
    // its own is two teams in two leagues and "lightning" is only one of them.
    private val lightning = team("TB", "Tampa Bay Lightning", home = true, shortName = "Lightning")
    private val panthers = team("FLA", "Florida Panthers", home = false, shortName = "Panthers")
    private val buccaneers = team("TB", "Tampa Bay Buccaneers", home = true, shortName = "Buccaneers")
    private val falcons = team("ATL", "Atlanta Falcons", home = false, shortName = "Falcons")
    private val lakers = team("LAL", "Los Angeles Lakers", home = false, shortName = "Lakers")
    private val celtics = team("BOS", "Boston Celtics", home = true, shortName = "Celtics")

    private val nhl = section(
        "hockey/nhl",
        "NHL",
        games = listOf(game("nhl-1", "hockey/nhl", panthers, lightning))
    )
    private val nfl = section(
        "football/nfl",
        "NFL",
        games = listOf(game("nfl-1", "football/nfl", falcons, buccaneers))
    )
    private val nba = section(
        "basketball/nba",
        "NBA",
        games = listOf(game("nba-1", "basketball/nba", lakers, celtics))
    )
    private val golf = section(
        "golf/pga",
        "Golf",
        tournaments = listOf(event("golf-1", "The Open Championship", "Royal Liverpool"))
    )
    private val slate = listOf(nhl, nfl, nba, golf)

    // ── What a query finds ───────────────────────────────────────────

    @Test
    fun `a nickname finds its games across leagues`() {
        val byNickname = SportsSearchRules.results(slate, "lightning")

        assertTrue("the Lightning game must be found", byNickname != null)
        assertEquals(listOf("nhl-1"), byNickname!!.games.map { it.id })
        assertTrue(
            "and nothing else - the Buccaneers are not the Lightning",
            byNickname.games.none { it.id == "nfl-1" }
        )
        assertTrue(
            "the abbreviation the feed uses is not the nickname either",
            SportsSearchRules.results(slate, "panthers")?.games?.map { it.id } == listOf("nhl-1")
        )
    }

    @Test
    fun `an abbreviation finds its team`() {
        assertEquals(
            listOf("nba-1"),
            SportsSearchRules.results(slate, "lal")?.games?.map { it.id }
        )
        // "TB" is BOTH Tampa Bay teams, which is correct and is why the results
        // line carries a league count: one query, two leagues.
        assertEquals(
            listOf("nhl-1", "nfl-1"),
            SportsSearchRules.results(slate, "tb")?.games?.map { it.id }
        )
        assertEquals(2, SportsSearchRules.leaguesWithHits(slate, "tb"))
    }

    @Test
    fun `a full name or a city finds the team`() {
        assertEquals(
            listOf("nba-1"),
            SportsSearchRules.results(slate, "los angeles")?.games?.map { it.id }
        )
        assertEquals(
            listOf("nhl-1", "nfl-1"),
            SportsSearchRules.results(slate, "tampa bay")?.games?.map { it.id }
        )
    }

    @Test
    fun `half a name is enough, because typing is incremental`() {
        assertEquals(
            listOf("nba-1"),
            SportsSearchRules.results(slate, "lak")?.games?.map { it.id }
        )
        assertEquals(
            listOf("nhl-1"),
            SportsSearchRules.results(slate, "flor")?.games?.map { it.id }
        )
    }

    @Test
    fun `a matchup pasted off a card still finds its game`() {
        // The whole name is inside what was typed, which is the other direction
        // of a prefix match.
        assertEquals(
            listOf("nba-1"),
            SportsSearchRules.results(slate, "Lakers at Celtics")?.games?.map { it.id }
        )
    }

    @Test
    fun `a tournament matches on its own name and venue`() {
        assertEquals(
            listOf("golf-1"),
            SportsSearchRules.results(slate, "open championship")?.tournaments?.map { it.id }
        )
        assertEquals(
            listOf("golf-1"),
            SportsSearchRules.results(slate, "royal liverpool")?.tournaments?.map { it.id }
        )
    }

    // ── What it does NOT do ──────────────────────────────────────────

    @Test
    fun `a blank query is not a search, so the hub keeps the view it had`() {
        assertNull(SportsSearchRules.results(slate, ""))
        assertNull(SportsSearchRules.results(slate, "   "))
        assertTrue(!SportsSearchRules.isActive(""))
    }

    @Test
    fun `gibberish finds nothing rather than everything`() {
        assertNull(SportsSearchRules.results(slate, "zzzq"))
        // A single letter is not a name either - it is a viewer mid-keystroke,
        // and matching every team with an L in it would be worse than nothing.
        assertNull(SportsSearchRules.results(slate, "q"))
    }

    @Test
    fun `a league that has not loaded is not searched, and is not fetched`() {
        // The hub is handed only the league it has: the NHL section never
        // arrived, so its game cannot match, and nothing here can ask for it -
        // this object has no repository and no coroutine to ask with.
        assertEquals(
            listOf("nfl-1"),
            SportsSearchRules.results(listOf(nfl), "tampa bay")?.games?.map { it.id }
        )
        assertNull(SportsSearchRules.results(listOf(nfl), "lightning"))
        assertNull(SportsSearchRules.results(emptyList(), "tampa bay"))
    }

    @Test
    fun `results keep the leagues' own order`() {
        val results = SportsSearchRules.results(slate, "tampa bay")
        assertEquals(listOf("nhl-1", "nfl-1"), results?.games?.map { it.id })

        // Reversing the input reverses the answer: the order is the sections',
        // never the matcher's or a sort's.
        val reversed = SportsSearchRules.results(slate.reversed(), "tampa bay")
        assertEquals(listOf("nfl-1", "nhl-1"), reversed?.games?.map { it.id })
    }

    @Test
    fun `the favourites tab searches the followed list under its own label`() {
        val favorites = LeagueSection(
            league = SportsLeagues.FAVORITES,
            games = listOf(nhl.games.first())
        )

        val found = SportsSearchRules.results(
            listOf(favorites),
            "lightning",
            SportsLeagues.FAVORITES
        )
        assertSame("the results are labelled with the tab they came from", SportsLeagues.FAVORITES, found?.league)
        assertEquals(listOf("nhl-1"), found?.games?.map { it.id })
        // A team the viewer does not follow is not in that list to be found.
        assertNull(SportsSearchRules.results(listOf(favorites), "lakers", SportsLeagues.FAVORITES))
    }

    @Test
    fun `the combined results are labelled as a search rather than as a league`() {
        val results = SportsSearchRules.results(slate, "lal")
        assertSame(SportsSearchRules.RESULTS_LEAGUE, results?.league)
        assertEquals("Search", results?.league?.label)
        assertTrue(
            "and the synthetic path can never be a real league's",
            SportsLeagues.ALL.none { it.path == SportsSearchRules.RESULTS_LEAGUE.path }
        )
    }
}
