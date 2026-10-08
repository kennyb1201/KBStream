package com.kennyb1201.kbstream.ui.sports

import com.kennyb1201.kbstream.data.sports.StandingEntry
import com.kennyb1201.kbstream.data.sports.StandingGroup
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which leagues get two columns, and where the split falls.
 *
 * The rule decides a shape the viewer reads across a room - NFL AFC left / NFC
 * right, the Premier League as one table, a college league's ten conferences as
 * a single list - so it is pure and pinned here rather than guessed at in the
 * composition. The repository has already flattened conferences into divisions,
 * which is why the conference each group came from is carried on the group.
 */
class SportsStandingsLayoutTest {

    private fun entry(abbreviation: String) = StandingEntry(
        abbreviation = abbreviation,
        displayName = abbreviation,
        logoUrl = null,
        wins = 0,
        losses = 0,
        ties = 0,
        winPercent = "",
        gamesBehind = "",
        streak = "",
    )

    private fun group(name: String, conference: String? = null, vararg codes: String) =
        StandingGroup(name = name, entries = codes.map(::entry), conference = conference)

    @Test
    fun `a single group is one column`() {
        val groups = listOf(group("Eastern Conference", codes = arrayOf("BOS")))
        val columns = SportsStandingsLayout.columns(groups, widthDp = 1920)
        assertEquals(1, columns.size)
        assertEquals(listOf("Eastern Conference"), columns.single().map { it.name })
    }

    @Test
    fun `two conferences put each one's divisions in its own column`() {
        val groups = listOf(
            group("AFC East", conference = "AFC", codes = arrayOf("BUF")),
            group("AFC West", conference = "AFC", codes = arrayOf("KC")),
            group("NFC East", conference = "NFC", codes = arrayOf("DAL")),
            group("NFC North", conference = "NFC", codes = arrayOf("DET")),
        )
        val columns = SportsStandingsLayout.columns(groups, widthDp = 1280)
        assertEquals(2, columns.size)
        assertEquals(listOf("AFC East", "AFC West"), columns[0].map { it.name })
        assertEquals(listOf("NFC East", "NFC North"), columns[1].map { it.name })
    }

    @Test
    fun `a flat two-conference payload splits on the group names`() {
        // The live NBA/NHL shape: conferences with entries and no division
        // children, so each group is its own conference and keys on its name.
        val groups = listOf(
            group("Eastern Conference", codes = arrayOf("BOS")),
            group("Western Conference", codes = arrayOf("LAL")),
        )
        val columns = SportsStandingsLayout.columns(groups, widthDp = 1280)
        assertEquals(2, columns.size)
        assertEquals(listOf("Eastern Conference"), columns[0].map { it.name })
        assertEquals(listOf("Western Conference"), columns[1].map { it.name })
    }

    @Test
    fun `a narrow screen collapses the two conferences into one column`() {
        val groups = listOf(
            group("AFC East", conference = "AFC", codes = arrayOf("BUF")),
            group("NFC East", conference = "NFC", codes = arrayOf("DAL")),
        )
        val columns = SportsStandingsLayout.columns(groups, widthDp = 720)
        assertEquals(1, columns.size)
        assertEquals(listOf("AFC East", "NFC East"), columns.single().map { it.name })
    }

    @Test
    fun `a college league's ten conferences cannot sit side by side`() {
        val groups = (1..10).map { group("Conference $it", codes = arrayOf("T$it")) }
        val columns = SportsStandingsLayout.columns(groups, widthDp = 1280)
        assertEquals(1, columns.size)
        assertEquals(10, columns.single().size)
    }
}
