package com.kennyb1201.kbstream.data.library

import com.kennyb1201.kbstream.data.mdblist.MdbListUserList
import com.kennyb1201.kbstream.data.publiclists.PublicListCard
import com.kennyb1201.kbstream.data.publiclists.PublicListVendor
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MDBList pins to the launch-time reconcile.
 *
 * A LIKED (saved public) MDBList list is a row on the Library rail whose
 * `remoteId` is the card's ref, so its Home pin carries that bare ref
 * (`"800238"`) - and an older pin may carry `"id:800238"` instead. Own MDBList
 * lists have no remoteId, so their pins carry `"id:<n>"` - or the bare number,
 * from a build that predates the `id:` form. Every one of those names the same
 * live list, and the prune used to compare only one spelling: a liked pin could
 * never appear in its live set at all, and every `id:`-form pin of an own list
 * was deleted and the deletion published.
 *
 * The live set is now built by [LibraryHomeRailReconcile.mdbListLiveListIds]
 * from both reads, each row carrying its current id beside its spellings, and
 * [reconciledPins] is the decision the store writes (prune + re-point).
 */
class MdbListLikedPinReconcileTest {

    private val source = LibrarySource.MDBLIST_LIST.name

    private fun ownList(id: Int) = MdbListUserList(id = id, name = "Own $id", itemCount = 12)

    private fun likedList(ref: String) = PublicListCard(
        vendor = PublicListVendor.MDBLIST,
        ref = ref,
        name = "Liked $ref",
        owner = "someone",
        description = null,
        itemCount = 24,
        likes = 100
    )

    private fun pin(id: String) = LibraryHomeRailPin(source = source, id = id, name = "Rail $id")

    /** The branch under test: a source is in the map only if it answered. */
    private fun liveMap(
        own: List<MdbListUserList>?,
        liked: List<PublicListCard>?
    ): Map<String, List<LibraryHomeRailLiveList>> {
        val live = mutableMapOf<String, List<LibraryHomeRailLiveList>>()
        LibraryHomeRailReconcile.mdbListLiveListIds(own, liked)?.let { live[source] = it }
        return live
    }

    @Test
    fun `a liked pin and an own pin both survive the launch prune`() {
        val pins = listOf(pin("800238"), pin("id:123"))

        val kept = reconciledPins(
            pins,
            liveMap(own = listOf(ownList(123)), liked = listOf(likedList("800238")))
        )

        assertEquals("the reported case: a liked list's pin must not be pruned", pins, kept)
    }

    @Test
    fun `a pin carrying the liked list's other spelling is kept and re-pointed`() {
        val old = pin("id:800238")

        val kept = reconciledPins(
            listOf(old),
            liveMap(own = emptyList(), liked = listOf(likedList("800238")))
        )

        assertEquals(
            "the pin survives under the spelling it was written with AND moves to the id this build writes",
            listOf(old.copy(id = "800238")),
            kept
        )
    }

    @Test
    fun `a pin carrying an own list's bare number is kept and re-pointed`() {
        val old = pin("123")

        val kept = reconciledPins(
            listOf(old),
            liveMap(own = listOf(ownList(123)), liked = emptyList())
        )

        assertEquals(
            "an older build wrote the bare number; the row's id today is id:123",
            listOf(old.copy(id = "id:123")),
            kept
        )
    }

    @Test
    fun `a failed liked read never prunes a liked pin`() {
        val map = liveMap(own = listOf(ownList(123)), liked = null)

        assertTrue(
            "an unanswered half of the source is absent, not empty",
            map.isEmpty()
        )
        assertEquals(
            listOf(pin("800238")),
            reconciledPins(listOf(pin("800238")), map)
        )
    }

    @Test
    fun `a failed own read never prunes either`() {
        val map = liveMap(own = null, liked = listOf(likedList("800238")))

        assertTrue("both reads must answer before the source publishes", map.isEmpty())
        assertEquals(
            listOf(pin("id:123")),
            reconciledPins(listOf(pin("id:123")), map)
        )
    }

    @Test
    fun `a genuinely gone liked list is still pruned`() {
        val pins = listOf(pin("800238"), pin("id:123"))

        val kept = reconciledPins(
            pins,
            liveMap(own = listOf(ownList(123)), liked = listOf(likedList("800239")))
        )

        assertEquals("keep-everything would be the opposite bug", listOf(pin("id:123")), kept)
    }

    @Test
    fun `the liked row's current id is the bare ref, and the id colon form is its other spelling`() {
        val row = likedListRow(LibrarySource.MDBLIST_LIST, likedList("800238"))

        assertEquals("the pin's current spelling, not the rail's own-list spelling", "800238", libraryHomeRailId(row))
        assertTrue(
            "and the id: form names the same list, so it must not read as a deletion",
            "id:800238" in libraryHomeRailLiveSpellings(row)
        )
        assertTrue(
            "the old single-spelling set is what pruned it",
            withoutVanishedPins(
                listOf(pin("id:800238")),
                mapOf(source to setOf("800238"))
            ).isEmpty()
        )
    }

    @Test
    fun `nothing to change writes nothing`() {
        val pins = listOf(pin("800238"), pin("id:123"))

        assertEquals(
            "the store compares the answer with what it holds, and must not publish a no-op",
            pins,
            reconciledPins(
                pins,
                liveMap(own = listOf(ownList(123)), liked = listOf(likedList("800238")))
            )
        )
    }

    @Test
    fun `the reconcile reads the liked leg behind the Library's own accessor`() {
        val reconcile = sourceFile("com/kennyb1201/kbstream/data/library/LibraryHomeRailReconcile.kt")
        val branch = reconcile.slice(
            "if (LibrarySource.MDBLIST_LIST.name in wanted",
            "if (LibrarySource.BINGECAT_LIST.name in wanted)"
        )

        assertTrue(
            "the same read the Library refresh builds its liked rows from",
            branch.contains("MdbListClient.likedPublicLists(context)")
        )
        assertTrue(
            "only a Loaded answer says which lists exist",
            branch.contains("(answer as? PublicListsAnswer.Loaded)?.value")
        )
        assertTrue(
            "and both halves are required before the source publishes",
            branch.contains("mdbListLiveListIds(own, liked)")
        )
        assertTrue(
            "the liked rows come from the Library's own builder, so the band id cannot differ",
            reconcile.contains("likedListRow(LibrarySource.MDBLIST_LIST, card)")
        )
        assertNull(
            "the own read must still answer empty on failure, never null-as-empty",
            LibraryHomeRailReconcile.mdbListLiveListIds(listOf(ownList(1)), null)
        )
    }

    private fun sourceFile(relative: String): String {
        val file = File(findSourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun String.slice(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        assertTrue("$startMarker is missing", start >= 0)
        val end = indexOf(endMarker, start)
        assertTrue("$endMarker must follow $startMarker", end > start)
        return substring(start, end)
    }

    private fun findSourceRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }
}
