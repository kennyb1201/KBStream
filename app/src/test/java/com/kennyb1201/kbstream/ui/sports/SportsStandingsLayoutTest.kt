package com.kennyb1201.kbstream.ui.sports

import com.kennyb1201.kbstream.data.sports.StandingEntry
import com.kennyb1201.kbstream.data.sports.StandingGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    @Test
    fun `the picker's choices are the table's top-level groups, not its divisions`() {
        val nfl = listOf(
            group("AFC East", conference = "AFC", codes = arrayOf("BUF")),
            group("AFC West", conference = "AFC", codes = arrayOf("KC")),
            group("NFC East", conference = "NFC", codes = arrayOf("DAL")),
            group("NFC North", conference = "NFC", codes = arrayOf("DET")),
        )
        assertEquals(listOf("AFC", "NFC"), SportsStandingsLayout.conferences(nfl))

        val college = (1..11).map { group("Conference $it", codes = arrayOf("T$it")) }
        assertEquals(11, SportsStandingsLayout.conferences(college).size)
    }

    @Test
    fun `only a table with more than two top-level groups needs the picker`() {
        val nfl = listOf(
            group("AFC East", conference = "AFC", codes = arrayOf("BUF")),
            group("NFC East", conference = "NFC", codes = arrayOf("DAL")),
        )
        val college = (1..11).map { group("Conference $it", codes = arrayOf("T$it")) }

        assertFalse("a two-conference league is unchanged", SportsStandingsLayout.needsConferenceFilter(nfl))
        assertTrue("the college document is the giant list", SportsStandingsLayout.needsConferenceFilter(college))
    }

    @Test
    fun `a picked conference is that group's rows, and an unknown pick is the whole table`() {
        val college = listOf(
            group("Southeastern Conference", codes = arrayOf("UGA", "BAMA")),
            group("Big Ten Conference", codes = arrayOf("OSU", "MICH")),
        )
        val picked = SportsStandingsLayout.filter(college, "Big Ten Conference")
        assertEquals(listOf("Big Ten Conference"), picked.map { it.name })
        assertEquals(2, picked.single().entries.size)

        // Null is ALL, and a name the table no longer carries (a refresh moved
        // the conferences) must not blank the view.
        assertEquals(college, SportsStandingsLayout.filter(college, null))
        assertEquals(college, SportsStandingsLayout.filter(college, "Pac-12 Conference"))
    }

    @Test
    fun `a chip drops the trailing word every conference shares`() {
        assertEquals("Southeastern", SportsStandingsLayout.label("Southeastern Conference"))
        assertEquals("Big Ten", SportsStandingsLayout.label("Big Ten Conference"))
        assertEquals("Conference USA", SportsStandingsLayout.label("Conference USA"))
    }
}
