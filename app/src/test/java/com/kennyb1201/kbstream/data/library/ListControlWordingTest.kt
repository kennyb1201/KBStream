package com.kennyb1201.kbstream.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The list-control wording, pinned once for both surfaces (spec F4's "the
 * unified list control spec governs the wording: 'Remove from [Source]'").
 *
 * The failure this prevents is entirely verbal and entirely silent: a SAVED
 * community list removed from Library only drops a LIKE, and a dialog that says
 * "Delete from Trakt?" over that write tells the viewer their press deletes
 * someone else's list. Both the TV dialog and the phone sheet call these
 * functions, so pinning them here pins what both will say.
 */
class ListControlWordingTest {

    @Test
    fun `an ordinary list is attributed to its source alone`() {
        assertEquals("Trakt", ListControlWording.attribution("Trakt", false, null))
        assertEquals(
            "an owner on an ordinary list is not printed - it is the viewer's",
            "Trakt",
            ListControlWording.attribution("Trakt", false, "kenny")
        )
    }

    @Test
    fun `a saved community list names its vendor and its owner`() {
        assertEquals(
            "Trakt community list · by kenny",
            ListControlWording.attribution("Trakt", true, "kenny")
        )
        assertEquals(
            "MDBList community list",
            ListControlWording.attribution("MDBList", true, "  ")
        )
    }

    @Test
    fun `the summary carries the count and the attribution`() {
        assertEquals(
            "24 titles · Trakt community list · by kenny",
            ListControlWording.summary(24, "Trakt", true, "kenny")
        )
        assertEquals(
            "1 title · MDBList community list",
            ListControlWording.summary(1, "MDBList", true, null)
        )
        assertEquals("0 titles · Trakt", ListControlWording.summary(0, "Trakt", false, null))
    }

    @Test
    fun `a saved list is REMOVED from its source, never deleted there`() {
        listOf("Trakt", "MDBList").forEach { label ->
            assertEquals(
                "the action row says what the write is",
                "REMOVE FROM ${label.uppercase()}",
                ListControlWording.removeActionLabel(label, local = false, liked = true)
            )
            assertEquals(
                "and the confirm asks the same question",
                "Remove from $label?",
                ListControlWording.removeConfirmTitle(label, local = false, liked = true)
            )
            assertEquals(
                "the button matches the question",
                "REMOVE",
                ListControlWording.removeConfirmAction(liked = true)
            )
            val body = ListControlWording.removeConfirmBody(label, local = false, liked = true, itemCount = 24)
            assertTrue(
                "the body has to say the list survives: $body",
                body.contains("stays on $label")
            )
            assertTrue(
                "and that only the like goes: $body",
                body.contains("Only your like is removed")
            )
            assertFalse(
                "the word 'delete' must not appear on a saved list's removal: $body",
                body.contains("delete", ignoreCase = true)
            )
        }
    }

    @Test
    fun `an owned remote list is still deleted at its source`() {
        assertEquals(
            "DELETE FROM TRAKT",
            ListControlWording.removeActionLabel("Trakt", local = false, liked = false)
        )
        assertEquals(
            "Delete from Trakt?",
            ListControlWording.removeConfirmTitle("Trakt", local = false, liked = false)
        )
        assertEquals(
            "DELETE",
            ListControlWording.removeConfirmAction(liked = false)
        )
        assertTrue(
            ListControlWording.removeConfirmBody("Trakt", local = false, liked = false, itemCount = 3)
                .contains("The list is deleted from Trakt")
        )
    }

    @Test
    fun `a local list is removed from this device, and says so`() {
        assertEquals(
            "REMOVE FROM THIS DEVICE",
            ListControlWording.removeActionLabel("this device", local = true, liked = false)
        )
        assertEquals(
            "Delete list?",
            ListControlWording.removeConfirmTitle("this device", local = true, liked = false)
        )
        assertTrue(
            ListControlWording.removeConfirmBody("this device", local = true, liked = false, itemCount = 5)
                .contains("removed from this device")
        )
    }

    @Test
    fun `the missing rename is explained, not just missing`() {
        val named = ListControlWording.renameUnavailableNote("Trakt", "kenny")
        assertTrue(
            "the note names where the list came from and who owns it: $named",
            named.contains("Trakt's public lists") && named.contains("(kenny)")
        )
        assertTrue(
            "and it names the way out, which is the removal: $named",
            named.contains("Remove it from your Library")
        )
        val unnamed = ListControlWording.renameUnavailableNote("MDBList", null)
        assertTrue(
            "with no owner it still says the name is not the viewer's: $unnamed",
            unnamed.contains("list's author")
        )
        assertFalse(
            "nothing here may offer a rename: $unnamed",
            unnamed.contains("rename it", ignoreCase = true)
        )
    }

    @Test
    fun `a create destination is named the same on both surfaces`() {
        // The TV rail's create field and the phone's each draw a chip per
        // destination, and each used to keep its own copy of these strings.
        // One function now answers for both.
        assertEquals(
            "THIS DEVICE",
            ListControlWording.createSourceLabel(LibrarySource.LOCAL_LIST)
        )
        assertEquals(
            "TRAKT",
            ListControlWording.createSourceLabel(LibrarySource.TRAKT_LIST)
        )
        assertEquals(
            "MDBLIST",
            ListControlWording.createSourceLabel(LibrarySource.MDBLIST_LIST)
        )
        assertEquals(
            "BINGECAT",
            ListControlWording.createSourceLabel(LibrarySource.BINGECAT_LIST)
        )
        // A source with no create of its own still gets a name, upper-cased from
        // its label, rather than an empty chip.
        assertEquals(
            "MDBLIST",
            ListControlWording.createSourceLabel(LibrarySource.MDBLIST_WATCHLIST)
        )
    }
}
