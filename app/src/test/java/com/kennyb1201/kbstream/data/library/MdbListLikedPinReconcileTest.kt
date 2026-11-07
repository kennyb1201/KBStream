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
 * (`"800238"`). Own MDBList lists have no remoteId, so their pins carry
 * `"id:<n>"`. The launch reconcile used to read only `/lists/user` (own lists),
 * so a liked pin could never appear in its live set: it was pruned on every
 * launch and the loss was published to the account, which is why the rails drew
 * fine for a session and vanished after a restart.
 *
 * The live set is now built by [LibraryHomeRailReconcile.mdbListLiveListIds]
 * from both reads, and the prune rule itself ([withoutVanishedPins]) is
 * unchanged - so these tests drive the seam the reconcile actually calls.
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
    private fun liveSet(
        own: List<MdbListUserList>?,
        liked: List<PublicListCard>?
    ): Map<String, Set<String>> {
        val live = mutableMapOf<String, Set<String>>()
        LibraryHomeRailReconcile.mdbListLiveListIds(own, liked)?.let { live[source] = it }
        return live
    }

    @Test
    fun `a liked pin and an own pin both survive the launch prune`() {
        val pins = listOf(pin("800238"), pin("id:123"))

        val kept = withoutVanishedPins(
            pins,
            liveSet(own = listOf(ownList(123)), liked = listOf(likedList("800238")))
        )

        assertEquals("the reported case: a liked list's pin must not be pruned", pins, kept)
    }

    @Test
    fun `a failed liked read never prunes a liked pin`() {
        val map = liveSet(own = listOf(ownList(123)), liked = null)

        assertTrue(
            "an unanswered half of the source is absent, not empty",
            map.isEmpty()
        )
        assertEquals(
            listOf(pin("800238")),
            withoutVanishedPins(listOf(pin("800238")), map)
        )
    }

    @Test
    fun `a failed own read never prunes either`() {
        val map = liveSet(own = null, liked = listOf(likedList("800238")))

        assertTrue("both reads must answer before the source publishes", map.isEmpty())
        assertEquals(
            listOf(pin("id:123")),
            withoutVanishedPins(listOf(pin("id:123")), map)
        )
    }

    @Test
    fun `a genuinely gone liked list is still pruned`() {
        val pins = listOf(pin("800238"), pin("id:123"))

        val kept = withoutVanishedPins(
            pins,
            liveSet(own = listOf(ownList(123)), liked = listOf(likedList("800239")))
        )

        assertEquals("keep-everything would be the opposite bug", listOf(pin("id:123")), kept)
    }

    @Test
    fun `the liked id is the bare ref, not its own-list spelling`() {
        val map = liveSet(own = emptyList(), liked = listOf(likedList("800238")))

        assertEquals(
            "the pin's own spelling, not libraryHomeRailId's",
            setOf("800238"),
            map[source]
        )
        assertTrue(
            "the old leg could only ever produce the id: form, which the pin never carries",
            withoutVanishedPins(
                listOf(pin("800238")),
                mapOf(source to setOf("id:800238"))
            ).isEmpty()
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
