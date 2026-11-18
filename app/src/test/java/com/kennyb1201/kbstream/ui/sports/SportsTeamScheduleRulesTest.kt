package com.kennyb1201.kbstream.ui.sports

import com.kennyb1201.kbstream.data.format.DateFormats
import com.kennyb1201.kbstream.data.sports.GameState
import com.kennyb1201.kbstream.data.sports.SportsGame
import com.kennyb1201.kbstream.data.sports.SportsTeam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a team's season reads (spec audit 2026-11-18, Sports M2's second half).
 *
 * Pure, so the two decisions that make a schedule readable are pinned without a
 * feed or a device: the groups are ordered chronologically in the direction each
 * one is asked about, and a FINAL is kept for the whole season rather than aged
 * out the way the scoreboard ages one.
 */
class SportsTeamScheduleRulesTest {

    @Test
    fun `a season splits into what is on, what is next and what was played`() {
        val groups = SportsTeamScheduleRules.groups(
            listOf(
                game("old", "2026-09-02T23:00Z", GameState.FINAL),
                game("now", "2026-11-18T23:00Z", GameState.LIVE),
                game("next", "2026-11-20T23:00Z", GameState.UPCOMING),
            )
        )

        assertEquals(listOf("now"), groups.live.map { it.id })
        assertEquals(listOf("next"), groups.upcoming.map { it.id })
        assertEquals(listOf("old"), groups.final.map { it.id })
        assertEquals(3, groups.total)
        assertFalse(groups.isEmpty)
    }

    @Test
    fun `upcoming games read soonest first, whatever order the feed sends`() {
        val groups = SportsTeamScheduleRules.groups(
            listOf(
                game("december", "2026-12-04T00:00Z", GameState.UPCOMING),
                game("friday", "2026-11-20T23:00Z", GameState.UPCOMING),
                game("next-week", "2026-11-25T23:00Z", GameState.UPCOMING),
            )
        )

        // "When do they play next" is the question, so the next one leads.
        assertEquals(
            listOf("friday", "next-week", "december"),
            groups.upcoming.map { it.id }
        )
    }

    @Test
    fun `results read newest first`() {
        val groups = SportsTeamScheduleRules.groups(
            listOf(
                game("september", "2026-09-02T23:00Z", GameState.FINAL),
                game("october", "2026-10-14T23:00Z", GameState.FINAL),
                game("last-night", "2026-11-17T23:00Z", GameState.FINAL),
            )
        )

        assertEquals(
            listOf("last-night", "october", "september"),
            groups.final.map { it.id }
        )
    }

    @Test
    fun `an old result is kept - a schedule is not a scoreboard`() {
        // The hub's own rule ages a FINAL out of the SCOREBOARD after seven days
        // (SportsFinalWindowRules): a card there is "what is on now". A season's
        // results are the content, so a game from the opening week is still here.
        val opener = game("opener", "2026-04-01T23:00Z", GameState.FINAL)
        val now = espnDateMs("2026-11-18T12:00Z")

        assertTrue(
            "the hub's window really would have dropped this one",
            com.kennyb1201.kbstream.ui.sports.SportsFinalWindowRules
                .keepCurrent(listOf(opener), now).isEmpty()
        )
        assertEquals(listOf("opener"), SportsTeamScheduleRules.groups(listOf(opener)).final.map { it.id })
    }

    @Test
    fun `a repeated fixture is one game`() {
        // A rescheduled game appears on both its old and its new date while
        // ESPN's feed catches up; drawn twice it reads as two games.
        val groups = SportsTeamScheduleRules.groups(
            listOf(
                game("g1", "2026-11-20T23:00Z", GameState.UPCOMING),
                game("g1", "2026-11-21T23:00Z", GameState.UPCOMING),
            )
        )

        assertEquals(1, groups.total)
    }

    @Test
    fun `an undated fixture sorts after the dated ones rather than first`() {
        val groups = SportsTeamScheduleRules.groups(
            listOf(
                game("tbd", 0L, GameState.UPCOMING),
                game("dated", "2026-11-20T23:00Z", GameState.UPCOMING),
            )
        )

        // 0 is "the feed could not say", not "the beginning of time": a TBD game
        // is not the next one on.
        assertEquals(listOf("dated", "tbd"), groups.upcoming.map { it.id })
    }

    @Test
    fun `the state decides the group, not the clock`() {
        // An ESPN game dated in the past that the feed still calls UPCOMING is
        // upcoming: a delayed start belongs in "next", not in "played".
        val groups = SportsTeamScheduleRules.groups(
            listOf(game("delayed", "2026-11-18T00:00Z", GameState.UPCOMING))
        )

        assertEquals(listOf("delayed"), groups.upcoming.map { it.id })
        assertTrue(groups.final.isEmpty())
    }

    @Test
    fun `an empty season is a sentence, not a blank sheet`() {
        val empty = SportsTeamScheduleRules.groups(emptyList())

        assertTrue(empty.isEmpty)
        assertEquals(0, empty.total)
        assertEquals("No games on this team's schedule yet.", SportsTeamScheduleRules.emptyLine(empty))
        assertNull(
            "a season with games says nothing",
            SportsTeamScheduleRules.emptyLine(
                SportsTeamScheduleRules.groups(listOf(game("g", 1L, GameState.UPCOMING)))
            )
        )
    }

    @Test
    fun `only a side the feed gave an id can have a season`() {
        // A person - a tennis player, a fighter - is keyed by name and has no id,
        // and `teams/{id}/schedule` needs one.
        assertTrue(SportsTeamScheduleRules.canOpen(team(id = "19", name = "Yankees")))
        assertFalse(SportsTeamScheduleRules.canOpen(team(id = null, name = "Sinner")))
        assertFalse(
            "a side with no league path has no endpoint either",
            SportsTeamScheduleRules.canOpen(
                team(id = "19", name = "Yankees").copy(league = null)
            )
        )
    }

    @Test
    fun `a row says when the game is and who is in it`() {
        val dated = game("g", "2026-11-20T23:00Z", GameState.UPCOMING)

        assertEquals(
            "the month and day, not the weekday: a season is read against the calendar",
            DateFormats.time(dated.dateMs, DateFormats.MONTH_DAY_CLOCK_12H),
            SportsTeamScheduleRules.dateLine(dated)
        )
        assertEquals("TBD", SportsTeamScheduleRules.dateLine(game("tbd", 0L, GameState.UPCOMING)))
        assertEquals("NYY @ BOS", SportsTeamScheduleRules.matchupLine(dated))
    }

    @Test
    fun `a side with no code falls back to its name`() {
        val game = game("g", 1L, GameState.UPCOMING).copy(
            away = team(id = "1", name = "Inter Miami", abbreviation = "")
        )

        assertEquals("Inter Miami @ BOS", SportsTeamScheduleRules.matchupLine(game))
    }

    // ── Fixtures ─────────────────────────────────────────────────────────

    private fun team(
        id: String?,
        name: String,
        abbreviation: String = "NYY",
    ) = SportsTeam(
        abbreviation = abbreviation,
        displayName = name,
        logoUrl = null,
        score = null,
        isHome = false,
        record = null,
        id = id,
        league = "baseball/mlb",
    )

    private fun game(id: String, dateMs: Long, state: GameState) = SportsGame(
        id = id,
        league = "baseball/mlb",
        name = "Yankees at Red Sox",
        dateMs = dateMs,
        state = state,
        statusDetail = "",
        away = team(id = "10", name = "New York Yankees"),
        home = team(id = "2", name = "Boston Red Sox", abbreviation = "BOS").copy(isHome = true),
        broadcastNames = emptyList(),
    )

    private fun game(id: String, iso: String, state: GameState) =
        game(id, espnDateMs(iso), state)

    // OffsetDateTime rather than Instant: ESPN's own stamps omit the seconds
    // ("2026-11-20T23:00Z"), which Instant.parse refuses.
    private fun espnDateMs(iso: String): Long =
        java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()
}
