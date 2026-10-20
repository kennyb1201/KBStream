package com.kennyb1201.kbstream.ui.library

import com.kennyb1201.kbstream.data.library.LibraryList
import com.kennyb1201.kbstream.data.library.LibrarySource
import com.kennyb1201.kbstream.data.library.traktListShelfId
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Trakt is the Library tab's third remote source, beside Simkl and MDBList.
 *
 * The wiring is checked from the sources because the alternative - a device
 * test that connects a Trakt account - cannot run in this suite, and the two
 * ways this can silently regress are exactly the ones a text check catches:
 * a source that renders but never fetches, and a Trakt list being mistaken for
 * a device list because its shelf id happens to be negative.
 */
class TraktLibraryContractTest {

    private fun mainRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("main source root not found")
    }

    private fun main(relative: String): String = File(mainRoot(), relative).readText()

    @Test
    fun `the view model reads, lists and removes Trakt like the other trackers`() {
        val vm = main("com/kennyb1201/kbstream/ui/library/LibraryViewModel.kt")

        assertTrue("the repository is held", vm.contains("TraktRepository.getInstance"))
        assertTrue("the watchlist is read", vm.contains("traktRepository.watchlist()"))
        assertTrue("personal lists are read", vm.contains("traktRepository.userLists()"))
        assertTrue(
            "a list's items are read through the account's own list path",
            vm.contains("TraktListRef(user = \"me\"")
        )
        assertTrue("watchlist rows are tagged", vm.contains("LibrarySource.TRAKT_WATCHLIST"))
        assertTrue("list rows are tagged", vm.contains("LibrarySource.TRAKT_LIST"))
        assertTrue(
            "a watchlist row is removed remotely",
            vm.contains("traktRepository.removeFromWatchlist(")
        )
        assertTrue(
            "a list row is removed remotely",
            vm.contains("removeFromList(")
        )
    }

    @Test
    fun `adds mirror to Trakt and the add row names it only when connected`() {
        val mirror = main("com/kennyb1201/kbstream/data/library/LibraryMirror.kt")
        val adds = main("com/kennyb1201/kbstream/ui/components/LibraryAdds.kt")

        assertTrue("a connected Trakt is recognised", mirror.contains("fun traktConnected("))
        assertTrue("adds reach Trakt's watchlist", mirror.contains(".addToWatchlist("))
        assertTrue("removals reach Trakt's watchlist", mirror.contains(".removeFromWatchlist("))
        assertTrue(
            "the add row's description reads the live connection",
            adds.contains("LibraryMirror.traktConnected(context)")
        )
    }

    @Test
    fun `the add-to-list picker offers Trakt's watchlist and personal lists`() {
        val picker = main("com/kennyb1201/kbstream/ui/components/LibraryPicker.kt")
        val adds = main("com/kennyb1201/kbstream/ui/components/LibraryAdds.kt")

        assertTrue(
            "a Trakt watchlist row exists and is labelled as Trakt's",
            picker.contains("isTraktWatchlist") &&
                picker.contains("\"Watchlist · Trakt\"")
        )
        assertTrue(
            "personal lists are loaded from Trakt",
            picker.contains("trakt.userLists()") &&
                picker.contains("LibrarySource.TRAKT_LIST")
        )
        assertTrue(
            "pressing a Trakt watchlist row writes the watchlist",
            adds.contains("LibraryAddRoute.TraktWatchlist")
        )
        assertTrue(
            "and a personal-list row writes that list",
            adds.contains("addToTraktList(") &&
                adds.contains("TraktRepository.getInstance(context).addToList(")
        )
    }

    @Test
    fun `a Trakt list is distinct from a device list despite its negative shelf id`() {
        assertFalse(LibrarySource.TRAKT_LIST.isLocalList)
        assertFalse(LibrarySource.TRAKT_WATCHLIST.isLocalList)
        assertTrue(LibrarySource.LOCAL_LIST.isLocalList)

        val shelf = traktListShelfId(42)
        assertTrue("the shelf id is negative, like local ids", shelf < 0)
        assertTrue(
            "and outside the range a local id can occupy",
            shelf <= Int.MIN_VALUE + 42
        )
        assertEquals(
            "a list with a Trakt id and no remote id still knows where it lives",
            "Trakt",
            LibraryList(
                id = shelf,
                name = "Watchlist",
                itemCount = 0,
                source = LibrarySource.TRAKT_LIST,
                remoteId = "watchlist"
            ).locationLabel
        )
    }

    @Test
    fun `both Library layouts render and remove Trakt rows`() {
        val tv = main("com/kennyb1201/kbstream/ui/library/LibraryScreen.kt")
        val phone = main("com/kennyb1201/kbstream/ui/mobile/LibraryScreenMobile.kt")

        listOf("TRAKT_WATCHLIST", "TRAKT_LIST", "state.traktConnected").forEach { token ->
            assertTrue("the TV Library handles $token", tv.contains(token))
            assertTrue("the phone Library handles $token", phone.contains(token))
        }
        assertTrue(
            "the TV header names Trakt as a source",
            tv.contains("if (state.traktConnected) add(\"TRAKT\")")
        )
        assertTrue(
            "the phone header names Trakt as a source",
            phone.contains("if (state.traktConnected) add(\"TRAKT\")")
        )
    }
}
