package com.kennyb1201.kbstream.data.trakt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which read a pinned Trakt list needs, from the shape of the id the pin holds.
 *
 * The field bug this pins: a liked PUBLIC Trakt list pinned to Home drew no rail
 * at all. The pin was right - it carried the card's numeric Trakt id, the same
 * number `trakt.tv/lists/12345` addresses - but the rail loader read every Trakt
 * pin as the account's own list, `users/me/lists/55/items/movies`. That is a
 * 404 for a list the account does not own, and the repository answers a 404 with
 * an empty list, so the branch produced zero items and the empty rail drew
 * nothing.
 *
 * All rows here are at [traktListReadsForPinnedId] level: no network, no
 * repository, no Android.
 */
class TraktPinReadsTest {

    @Test
    fun `a numeric pin is a public list and is read ownerless first`() {
        assertEquals(
            "a public list's numeric id is answered by Trakt's id-only endpoint, " +
                "which takes no owner; the own-list read is only the fallback",
            listOf(
                TraktListRef(user = null, listId = "55"),
                TraktListRef(user = "me", listId = "55")
            ),
            traktListReadsForPinnedId("55")
        )
    }

    @Test
    fun `a slug pin is an own list and keeps the account read`() {
        assertEquals(
            "an own list's remoteId is its slug, which Trakt serves only under the " +
                "account - and one call, not two",
            listOf(TraktListRef(user = "me", listId = "my-list")),
            traktListReadsForPinnedId("my-list")
        )
    }

    @Test
    fun `only the numeric form gets the second read`() {
        // The fallback is one extra call on a miss, not a permanent double read:
        // the common public pin would otherwise pay two requests for every rail.
        listOf("55", "0", "12345").forEach { id ->
            assertEquals("`$id` is numeric", 2, traktListReadsForPinnedId(id).size)
        }
        listOf("my-list", "my-list-2", "list_55", "55a").forEach { id ->
            assertEquals("`$id` is a slug", 1, traktListReadsForPinnedId(id).size)
        }
    }

    @Test
    fun `the two reads address the same list, and neither invents an owner`() {
        val reads = traktListReadsForPinnedId("55")
        assertEquals(
            "one list, whichever endpoint answers it",
            setOf("55"),
            reads.map { it.listId }.toSet()
        )
        assertEquals(
            "the public read must be ownerless - `me` is the 404 this fix removes",
            null,
            reads.first().user
        )
        assertTrue(
            "and the fallback is the account read that already worked for own lists",
            reads.last().user == "me"
        )
    }

    @Test
    fun `a blank id reads nothing`() {
        assertEquals(
            "a pin with no id must not be turned into a request for the account's " +
                "own lists",
            emptyList<TraktListRef>(),
            traktListReadsForPinnedId("")
        )
        assertEquals(emptyList<TraktListRef>(), traktListReadsForPinnedId("   "))
    }

    @Test
    fun `the id is trimmed, so a stray space is not a slug`() {
        assertEquals(
            listOf(
                TraktListRef(user = null, listId = "55"),
                TraktListRef(user = "me", listId = "55")
            ),
            traktListReadsForPinnedId(" 55 ")
        )
    }
}
