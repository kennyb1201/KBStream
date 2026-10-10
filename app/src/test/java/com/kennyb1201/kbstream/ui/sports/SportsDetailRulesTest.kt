package com.kennyb1201.kbstream.ui.sports

import com.kennyb1201.kbstream.data.sports.EspnGameSummary
import com.kennyb1201.kbstream.data.sports.GameLeader
import com.kennyb1201.kbstream.data.sports.GameState
import com.kennyb1201.kbstream.data.sports.SportsGame
import com.kennyb1201.kbstream.data.sports.SportsTeam
import com.kennyb1201.kbstream.data.sports.TournamentEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The game detail sheet's content, as pure functions.
 *
 * The sheet is a view over an already-loaded game, so what it draws is a
 * decision - which rows, in what words - and that is exactly what a unit test
 * can pin without a TV. The two cases the viewer notices are the ones here: an
 * upcoming fixture must not read as 0-0, and a game the feed gives no venue or
 * leaders must draw no rows rather than a gap.
 */
class SportsDetailRulesTest {

    private fun team(
        abbreviation: String,
        name: String = abbreviation,
        score: String? = null,
        record: String? = null,
    ) = SportsTeam(
        id = null,
        abbreviation = abbreviation,
        displayName = name,
        logoUrl = null,
        score = score,
        isHome = false,
        record = record,
    )

    private fun game(
        state: GameState = GameState.UPCOMING,
        away: SportsTeam = team("BOS"),
        home: SportsTeam = team("NYY"),
        dateMs: Long = 0L,
        statusDetail: String = "",
        name: String = "Boston Red Sox at New York Yankees",
        venue: String? = null,
        week: String? = null,
        note: String? = null,
        broadcastNames: List<String> = emptyList(),
        leaders: List<GameLeader> = emptyList(),
    ) = SportsGame(
        id = "1",
        league = "baseball/mlb",
        name = name,
        dateMs = dateMs,
        state = state,
        statusDetail = statusDetail,
        away = away,
        home = home,
        broadcastNames = broadcastNames,
        venue = venue,
        note = note,
        week = week,
        leaders = leaders,
    )

    @Test
    fun `an upcoming fixture reads as versus, never as nil-nil`() {
        assertEquals("vs", SportsDetailRules.scoreLine(game()))
    }

    @Test
    fun `a live or final score is the two sides' own`() {
        val final = game(
            state = GameState.FINAL,
            away = team("BOS", score = "2"),
            home = team("NYY", score = "3"),
        )
        assertEquals("2 - 3", SportsDetailRules.scoreLine(final))
    }

    @Test
    fun `the status line mirrors the card`() {
        assertEquals("Final", SportsDetailRules.statusLine(game(state = GameState.FINAL)))
        assertEquals(
            "Q3 4:32",
            SportsDetailRules.statusLine(game(state = GameState.LIVE, statusDetail = "Q3 4:32"))
        )
        // A live game with no clock still says the one thing that matters.
        assertEquals("LIVE", SportsDetailRules.statusLine(game(state = GameState.LIVE)))
        // An upcoming game with no date and no detail falls back to "Today".
        assertEquals("Today", SportsDetailRules.statusLine(game()))
    }

    @Test
    fun `the detail rows are venue, broadcast, then the week`() {
        val rows = SportsDetailRules.detailRows(
            game(
                venue = "Yankee Stadium · Bronx, NY",
                broadcastNames = listOf("ESPN", "YES"),
                week = "Week 6",
            )
        )

        assertEquals(
            listOf(
                GameDetailRow("Venue", "Yankee Stadium · Bronx, NY"),
                GameDetailRow("Broadcast", "ESPN, YES"),
                GameDetailRow("Week", "Week 6"),
            ),
            rows
        )
    }

    @Test
    fun `ESPN's own note becomes the round when the feed has no week`() {
        val rows = SportsDetailRules.detailRows(game(note = "ALDS - Game 4"))
        assertEquals(listOf(GameDetailRow("Round", "ALDS - Game 4")), rows)
    }

    @Test
    fun `a game the feed gives no venue or round draws no rows`() {
        // Not an empty line: nothing at all, so the sheet has no gap in it.
        assertTrue(SportsDetailRules.detailRows(game()).isEmpty())
        assertTrue(SportsDetailRules.detailRows(game(broadcastNames = listOf("", "  "))).isEmpty())
    }

    @Test
    fun `leaders read as one line per side, with the side and the summary`() {
        val lines = SportsDetailRules.leaderLines(
            game(
                leaders = listOf(
                    GameLeader("LeBron James", "LAL", "28 PTS, 11 REB"),
                    GameLeader("Jayson Tatum", "BOS", "24 PTS"),
                )
            )
        )

        assertEquals(
            listOf(
                "LAL · LeBron James — 28 PTS, 11 REB",
                "BOS · Jayson Tatum — 24 PTS",
            ),
            lines
        )
        // No leaders in the feed is no lines, not a blank one.
        assertTrue(SportsDetailRules.leaderLines(game()).isEmpty())
    }

    @Test
    fun `the watch label is honest about a game the playlist cannot carry`() {
        assertEquals("WATCH", SportsDetailRules.watchLabel(true))
        assertEquals("Not in your playlist", SportsDetailRules.watchLabel(false))
        // The default is the state every ordinary caller is in: a lineup was
        // read, and it does not carry this game.
        assertEquals("Not in your playlist", SportsDetailRules.watchLabel(false, lineupMissing = false))
    }

    @Test
    fun `the watch label says it is looking while matching is still running`() {
        // The 40-60s a large playlist takes must not read as a claimed no-match,
        // so an in-flight pass (matchingDone = false) words the disabled button
        // as a loading state. A matched game still watches straight away, and a
        // settled pass keeps the two honest facts.
        assertEquals("Finding channel…", SportsDetailRules.watchLabel(false, matchingDone = false))
        assertEquals("WATCH", SportsDetailRules.watchLabel(true, matchingDone = false))
        assertEquals("Not in your playlist", SportsDetailRules.watchLabel(false))
        assertEquals("Lineup not loaded", SportsDetailRules.watchLabel(false, lineupMissing = true))
    }

    @Test
    fun `a hub with no lineup blames itself, not the game`() {
        // The same disabled button, a different fact: there was nothing to
        // compare against, so "not in your playlist" would be a claim the hub
        // cannot make - and twenty of them read as a broken hub.
        assertEquals("Lineup not loaded", SportsDetailRules.watchLabel(false, lineupMissing = true))
        assertEquals(
            "a game that DID match still watches, whatever the status says",
            "WATCH",
            SportsDetailRules.watchLabel(true, lineupMissing = true)
        )
    }

    // ── The stats section (the game detail sheet) ────────────────────

    @Test
    fun `no summary at all draws no stats section`() {
        assertFalse(SportsDetailRules.hasStats(null))
        assertFalse(
            "an empty summary is the same thing as none: no heading, no gap",
            SportsDetailRules.hasStats(EspnGameSummary())
        )
    }

    @Test
    fun `any one part of a summary is enough to draw the section`() {
        assertTrue(
            SportsDetailRules.hasStats(EspnGameSummary(homeWinProbability = 68))
        )
        assertTrue(
            SportsDetailRules.hasStats(
                EspnGameSummary(teamStats = listOf(Triple("Hits", "7", "5")))
            )
        )
        assertTrue(
            SportsDetailRules.hasStats(EspnGameSummary(lastPlay = "Corner taken short"))
        )
        assertFalse(
            "a blank play text is not content",
            SportsDetailRules.hasStats(EspnGameSummary(lastPlay = "  "))
        )
    }

    @Test
    fun `the stats heading follows the game, so a box score is never labelled live`() {
        // The same section serves a game in play and one that has just ended:
        // a finished game the hub is still showing must not head its numbers
        // "LIVE STATS".
        assertEquals(
            "LIVE STATS",
            SportsDetailRules.statsHeading(game(state = GameState.LIVE))
        )
        assertEquals(
            "FINAL STATS",
            SportsDetailRules.statsHeading(game(state = GameState.FINAL))
        )
    }

    // ── A tournament's own rows (the tournament detail sheet) ──────────

    private fun tournament(
        state: GameState = GameState.UPCOMING,
        dateMs: Long = 0L,
        statusDetail: String = "",
        venue: String? = null,
        note: String? = null,
        broadcastNames: List<String> = emptyList(),
    ) = TournamentEvent(
        id = "401",
        league = "golf/pga",
        name = "The CJ Cup",
        dateMs = dateMs,
        state = state,
        statusDetail = statusDetail,
        leaders = emptyList(),
        broadcastNames = broadcastNames,
        venue = venue,
        note = note,
    )

    @Test
    fun `a tournament's status line mirrors its card`() {
        assertEquals(
            "Round 1 - Play Complete",
            SportsDetailRules.statusLine(
                tournament(state = GameState.LIVE, statusDetail = "Round 1 - Play Complete")
            )
        )
        // A live round with no detail still says the one thing that matters.
        assertEquals("LIVE", SportsDetailRules.statusLine(tournament(state = GameState.LIVE)))
        assertEquals("Final", SportsDetailRules.statusLine(tournament(state = GameState.FINAL)))
        // Same fallback as a game's: no clock and no detail reads as "Today".
        assertEquals("Today", SportsDetailRules.statusLine(tournament()))
    }

    @Test
    fun `a tournament's rows are venue, broadcast, then its own context line`() {
        val rows = SportsDetailRules.detailRows(
            tournament(
                venue = "Congaree Golf Club · Ridgeville, SC",
                broadcastNames = listOf("Golf Channel", "ESPN+"),
                note = "Round 2",
            )
        )

        assertEquals(
            listOf(
                GameDetailRow("Venue", "Congaree Golf Club · Ridgeville, SC"),
                GameDetailRow("Broadcast", "Golf Channel, ESPN+"),
                GameDetailRow("Event", "Round 2"),
            ),
            rows
        )
        // A stop the feed gives nothing about draws no rows, not blank ones.
        assertTrue(SportsDetailRules.detailRows(tournament()).isEmpty())
        assertTrue(SportsDetailRules.detailRows(tournament(note = "  ")).isEmpty())
    }

    @Test
    fun `the matchup title prefers ESPN's own name`() {
        assertEquals(
            "Boston Red Sox at New York Yankees",
            SportsDetailRules.matchupTitle(game())
        )
        // A nameless event still says who is playing, from the two sides.
        assertEquals(
            "Boston Red Sox at New York Yankees",
            SportsDetailRules.matchupTitle(
                game(
                    name = "",
                    away = team("BOS", "Boston Red Sox"),
                    home = team("NYY", "New York Yankees")
                )
            )
        )
    }
}
