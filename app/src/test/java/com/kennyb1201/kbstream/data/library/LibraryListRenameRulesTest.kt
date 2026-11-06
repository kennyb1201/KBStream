package com.kennyb1201.kbstream.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Renaming a pinned list, and what the pins are afterwards.
 *
 * Field (TV, 2026-11-05): a BingeCat list added from Library to Home, then
 * renamed, showed as TWO rails - the renamed one with posters, and the original
 * name under it with nothing in it. The rename never touched the pin, and the
 * surviving pin names an id the service no longer answers.
 *
 * The rules below are the whole of F1: match on what the list WAS (never on the
 * name, which is the thing that just changed), keep the pin's slot, take the new
 * identity, collapse any second spelling. F3 is the read side: a list whose pin
 * is spelled differently from today's row is still the same list.
 */
class LibraryListRenameRulesTest {

    private val bingecat = "BINGECAT_LIST"
    private val trakt = "TRAKT_LIST"

    private fun pin(source: String, id: String, name: String) =
        LibraryHomeRailPin(source = source, id = id, name = name)

    @Test
    fun `a rename that keeps the id rewrites the title in place`() {
        val office = pin(bingecat, "the-office", "The Office")
        val hbo = pin(trakt, "hbo", "HBO")
        val renamed = pin(bingecat, "the-office", "Office Hours")

        val after = repinnedAfterRename(
            pins = listOf(office, hbo),
            renamed = renamed,
            previousIds = setOf("the-office", "id:3")
        )

        assertEquals("same rail, same slot, new title", listOf(renamed, hbo), after)
    }

    @Test
    fun `a rename that moves the id takes it and keeps the slot`() {
        val first = pin(trakt, "my-list", "My List")
        val other = pin(bingecat, "ref-1", "Binge")
        val renamed = pin(trakt, "my-list-2", "My List 2")

        val after = repinnedAfterRename(
            pins = listOf(first, other),
            renamed = renamed,
            previousIds = setOf("my-list", "id:12")
        )

        assertEquals(
            "the rail stays where the viewer put it, under the id the service now answers",
            listOf(renamed, other),
            after
        )
    }

    @Test
    fun `both spellings of one list collapse into the first`() {
        val byRef = pin(bingecat, "old-ref", "Binge")
        val byNumber = pin(bingecat, "id:0", "Binge")

        val after = repinnedAfterRename(
            pins = listOf(byRef, byNumber),
            renamed = pin(bingecat, "new-ref", "Binge renamed"),
            previousIds = setOf("old-ref", "id:0")
        )

        assertEquals("one list, one rail", 1, after.size)
        assertEquals(
            "the first pin's slot wins, with the identity that is true now",
            pin(bingecat, "new-ref", "Binge renamed"),
            after.first()
        )
    }

    @Test
    fun `a list the viewer never pinned is left unpinned`() {
        val pins = listOf(pin(bingecat, "ref-1", "Binge"))

        val after = repinnedAfterRename(
            pins = pins,
            renamed = pin(bingecat, "ref-9", "Brand new name"),
            previousIds = setOf("ref-9", "id:4")
        )

        assertSame("a rename must never create a pin", pins, after)
    }

    @Test
    fun `a pin on the numeric spelling is found by it`() {
        val numeric = pin(bingecat, "id:7", "Mine")
        val renamed = pin(bingecat, "ref-9", "Mine renamed")

        val after = repinnedAfterRename(
            pins = listOf(numeric),
            renamed = renamed,
            previousIds = setOf("id:7")
        )

        assertEquals(
            "an older pin carrying the rail's own id follows the service's id",
            listOf(renamed),
            after
        )
    }

    @Test
    fun `the same id on another source is a different list`() {
        val traktPin = pin(trakt, "ref-1", "Binge")
        val renamed = pin(bingecat, "ref-1", "Binge renamed")

        val after = repinnedAfterRename(
            pins = listOf(traktPin),
            renamed = renamed,
            previousIds = setOf("ref-1")
        )

        assertEquals("a pin is a list ON a source", listOf(traktPin), after)
    }

    @Test
    fun `the toggle finds a pin under any spelling of the list`() {
        val pins = listOf(
            pin(bingecat, "ref-1", "Binge"),
            pin(bingecat, "id:7", "Older name"),
            pin(trakt, "shared", "Binge"),
            pin(bingecat, "unrelated", "Something else")
        )

        assertEquals(
            "the service's own id",
            listOf(pins[0]),
            pinsNamingList(pins, bingecat, setOf("ref-1"), "Binge renamed")
        )
        assertEquals(
            "the rail's numeric spelling, alongside it",
            listOf(pins[0], pins[1]),
            pinsNamingList(pins, bingecat, setOf("ref-1", "id:7"), "Binge renamed")
        )
        assertEquals(
            "the name the pin carries, scoped to its source",
            listOf(pins[2]),
            pinsNamingList(pins, trakt, setOf("some-other-id"), "Binge")
        )
        assertTrue(
            "and a blank name must not match everything on a source",
            pinsNamingList(pins, bingecat, setOf("ref-1"), "   ").size == 1
        )
        assertTrue(
            "a list on another source is not this list",
            pinsNamingList(pins, "MDBLIST_LIST", setOf("ref-1", "id:7"), "Binge").isEmpty()
        )
    }
}
