package com.kennyb1201.kbstream.ui.sports

import com.kennyb1201.kbstream.data.sports.StandingEntry
import com.kennyb1201.kbstream.data.sports.StandingGroup
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which row leads a table.
 *
 * ESPN's own order is not a rule: the NBA's standings document arrives
 * worst-first (the reported bug - the 0-3 teams filled the top of the Eastern
 * Conference), while the NFL's and the WNBA's arrive best-first. The rule is
 * pinned here because a table read from the bottom is arithmetic, not layout.
 */
class SportsStandingsOrderTest {

    private fun entry(code: String, wins: Int, losses: Int, pct: String, points: String = "") =
        StandingEntry(
            abbreviation = code,
            displayName = code,
            logoUrl = null,
            wins = wins,
            losses = losses,
            ties = 0,
            winPercent = pct,
            gamesBehind = "",
            streak = "",
            points = points,
        )

    @Test
    fun `a table that arrives worst-first is read best record first`() {
        val feed = listOf(
            entry("BKN", wins = 0, losses = 3, pct = ".000"),
            entry("MIA", wins = 0, losses = 3, pct = ".000"),
            entry("ORL", wins = 1, losses = 2, pct = ".333"),
            entry("BOS", wins = 3, losses = 0, pct = "1.000"),
        )

        assertEquals(
            listOf("BOS", "ORL", "BKN", "MIA"),
            SportsStandingsOrder.sorted(feed, pointsTable = false).map { it.abbreviation },
        )
    }

    @Test
    fun `a table that arrives sorted is returned in exactly that order`() {
        // The NFL's and the WNBA's documents, and a table whose teams all share a
        // record: ESPN's own tiebreakers (goal difference, division record,
        // playoff seed) live in that order, and a stable sort keeps them.
        val feed = listOf(
            entry("KC", wins = 5, losses = 1, pct = ".833"),
            entry("BUF", wins = 5, losses = 1, pct = ".833"),
            entry("CLE", wins = 5, losses = 2, pct = ".714"),
        )

        assertEquals(
            listOf("KC", "BUF", "CLE"),
            SportsStandingsOrder.sorted(feed, pointsTable = false).map { it.abbreviation },
        )
    }

    @Test
    fun `a points table is ranked on points rather than a win percentage`() {
        // Soccer and the NHL: three wins can be worth nine points or six.
        val feed = listOf(
            entry("ARS", wins = 4, losses = 1, pct = ".800", points = "13"),
            entry("MNC", wins = 7, losses = 1, pct = ".875", points = "21"),
            entry("LIV", wins = 4, losses = 1, pct = ".800", points = "15"),
        )

        assertEquals(
            listOf("MNC", "LIV", "ARS"),
            SportsStandingsOrder.sorted(feed, pointsTable = true).map { it.abbreviation },
        )
    }

    @Test
    fun `a row the feed gave no figure for is not promoted to the lead`() {
        // College football publishes no win percentage before the first
        // conference game; a blank read as zero would still be wrong to LEAD with,
        // so a missing figure ranks last.
        val feed = listOf(
            entry("UTSA", wins = 0, losses = 0, pct = ""),
            entry("MEM", wins = 2, losses = 1, pct = ".667"),
        )

        assertEquals(
            listOf("MEM", "UTSA"),
            SportsStandingsOrder.sorted(feed, pointsTable = false).map { it.abbreviation },
        )
    }

    @Test
    fun `a group the feed sent with no figures at all keeps its own order`() {
        val feed = listOf(
            entry("UTSA", wins = 0, losses = 0, pct = ""),
            entry("MEM", wins = 0, losses = 0, pct = ""),
            entry("UNT", wins = 0, losses = 0, pct = ""),
        )

        assertEquals(
            listOf("UTSA", "MEM", "UNT"),
            SportsStandingsOrder.sorted(feed, pointsTable = false).map { it.abbreviation },
        )
    }

    @Test
    fun `the groups keep their own order and their conference`() {
        // The conferences are the table's own shape (and the picker's choices);
        // sorting is inside a group, never across them.
        val groups = listOf(
            StandingGroup(
                name = "Southeastern Conference",
                entries = listOf(
                    entry("VAN", wins = 0, losses = 3, pct = ".000"),
                    entry("UGA", wins = 3, losses = 0, pct = "1.000"),
                ),
            ),
            StandingGroup(
                name = "Atlantic",
                entries = listOf(entry("BOS", wins = 1, losses = 1, pct = ".500")),
                conference = "Eastern Conference",
            ),
        )

        val sorted = SportsStandingsOrder.sortedGroups(groups, pointsTable = false)

        assertEquals(
            listOf("Southeastern Conference", "Atlantic"),
            sorted.map { it.name },
        )
        assertEquals(listOf("UGA", "VAN"), sorted[0].entries.map { it.abbreviation })
        assertEquals("Eastern Conference", sorted[1].conference)
    }
}
