package com.kennyb1201.kbstream.data.reporting

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The diagnostics line the pinned-rail loader keeps per load.
 *
 * It exists to settle one question from a capture instead of a guess: a Trakt
 * rail drawn title-only is either a read that shipped no poster URL or one that
 * shipped URLs that do not load. So what is pinned here is that both counts are
 * in the line, that the rail's own name and id are in it, and that the block
 * stays one line per rail.
 */
class LibraryRailArtTraceTest {

    @After
    fun tearDown() {
        LibraryRailArtTrace.reset()
    }

    @Test
    fun `the line carries both counts and the rail it is about`() {
        val line = libraryRailCarriedArtLine(
            source = "trakt list",
            listName = "Latest Digitally Released",
            listId = "8874193",
            carried = 40,
            missing = 2
        )
        assertTrue("the service and the rail's name", line.contains("trakt list"))
        assertTrue(line.contains("\"Latest Digitally Released\""))
        assertTrue(
            "and its id, which is what the read is looked up by",
            line.contains("(8874193)")
        )
        assertTrue(
            "how many rows shipped a poster URL - the arm where the READ is fine " +
                "and the card had art it could not draw",
            line.contains("40 of 42 row(s) carried a poster")
        )
        assertTrue(
            "and how many shipped none - the arm where the read itself is short",
            line.contains("2 did not")
        )
    }

    @Test
    fun `a rail of rows with no art reads as such`() {
        val line = libraryRailCarriedArtLine(
            source = "trakt list",
            listName = "Watchlist",
            listId = "1",
            carried = 0,
            missing = 7
        )
        assertTrue(line.contains("0 of 7 row(s) carried a poster"))
        assertTrue(line.contains("7 did not"))
    }

    @Test
    fun `one line per rail, replaced on the next load`() {
        LibraryRailArtTrace.record(
            rail = "trakt-list:1",
            line = libraryRailCarriedArtLine("trakt list", "One", "1", 0, 3)
        )
        LibraryRailArtTrace.record(
            rail = "trakt-list:2",
            line = libraryRailCarriedArtLine("trakt list", "Two", "2", 4, 0)
        )
        assertEquals(2, LibraryRailArtTrace.lines().size)

        LibraryRailArtTrace.record(
            rail = "trakt-list:1",
            line = libraryRailCarriedArtLine("trakt list", "One", "1", 3, 0)
        )
        val lines = LibraryRailArtTrace.lines()
        assertEquals(
            "a reload replaces that rail's line rather than adding a second one " +
                "for the same rail",
            2,
            lines.size
        )
        assertTrue(
            "and the block still holds the newest load's counts",
            lines.any { it.contains("\"One\" (1): 3 of 3 row(s) carried a poster") }
        )
        assertTrue(lines.any { it.contains("\"Two\" (2): 4 of 4") })
    }

    @Test
    fun `the block is bounded`() {
        repeat(LibraryRailArtTrace.MAX_RAILS + 5) { index ->
            LibraryRailArtTrace.record(
                rail = "trakt-list:$index",
                line = libraryRailCarriedArtLine(
                    "trakt list",
                    "Rail $index",
                    "$index",
                    1,
                    0
                )
            )
        }
        val lines = LibraryRailArtTrace.lines()
        assertEquals(LibraryRailArtTrace.MAX_RAILS, lines.size)
        assertTrue(
            "the newest loads are the ones kept: a report describes the rails " +
                "the viewer is looking at",
            lines.last().contains("\"Rail ${LibraryRailArtTrace.MAX_RAILS + 4}\"")
        )
    }
}
