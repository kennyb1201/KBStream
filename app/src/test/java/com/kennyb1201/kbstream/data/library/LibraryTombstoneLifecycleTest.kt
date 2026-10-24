package com.kennyb1201.kbstream.data.library

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The removal record against the real stores: what leaves My List is written
 * down as a removal, and only the viewer's own re-add takes it back.
 *
 * The field this exists for: an untoggle removed 81 stamped titles, a recount
 * offered 81 again, and a later round reported 413 added - two devices undoing
 * each other's writes, because a removal only ever existed as the ABSENCE of a
 * row in a blob the sync replaces whole. Nothing here needs a device: the store
 * is SharedPreferences and the wiring is ordinary calls.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = LibraryTombstoneLifecycleTest.NoopApplication::class)
class LibraryTombstoneLifecycleTest {

    /** See LibraryAddPersistenceTest: the real Application starts work on IO. */
    class NoopApplication : Application()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun libraryPrefs() =
        context.getSharedPreferences(
            ProfileStorage.prefsName(context, "kbstream_library"),
            Context.MODE_PRIVATE
        )

    @Before
    fun reset() {
        libraryPrefs().edit().clear().apply()
    }

    private fun addStamped(
        title: String,
        imdbId: String,
        tmdbId: Int,
        origin: LibraryOrigin = LibraryOrigin.TORBOX_SYNC
    ) = LocalLibraryStore.addToMyList(
        context = context,
        mediaType = "series",
        imdbId = imdbId,
        tmdbId = tmdbId,
        title = title,
        year = null,
        posterUrl = null,
        origin = origin
    )

    @Test
    fun `the untoggle removal leaves a removal behind, and the pull filter honours it`() {
        addStamped("Bluey", "tt1", 11)
        addStamped("Kenny's pick", "tt2", 22, origin = LibraryOrigin.MANUAL)

        assertEquals("only the stamped row is the toggle's to remove", 1, LocalLibraryStore.removeTorboxSynced(context))

        val record = LocalLibraryStore.tombstones(context)
        assertTrue(
            "the removed title is remembered as removed",
            LibraryTombstones.isRemoved(record, "series", "tt1", 11)
        )
        assertFalse(
            "the manual row was never removed and is not in the record",
            LibraryTombstones.isRemoved(record, "series", "tt2", 22)
        )

        val blob = LocalLibraryStore.myListBlob(context).orEmpty()
        val filtered = LocalLibraryStore.withoutTombstonedRows(blob, record)
        assertFalse(
            "a pull that carries the old list can no longer put the removed title back",
            filtered.contains("Bluey")
        )
        assertTrue(
            "and it keeps everything the removal did not name",
            filtered.contains("Kenny's pick")
        )
    }

    @Test
    fun `a manual re-add clears the removal, and the sync's own add never does`() {
        addStamped("Bluey", "tt1", 11)
        LocalLibraryStore.removeTorboxSynced(context)

        // The sync comes round again and matches the same title: it must not
        // clear what was removed.
        addStamped("Bluey", "tt1", 11)
        assertTrue(
            "a sync add is not the viewer's word, so the title stays removed",
            LibraryTombstones.isRemoved(LocalLibraryStore.tombstones(context), "series", "tt1", 11)
        )

        // The viewer adds it by hand: welcome again.
        addStamped("Bluey", "tt1", 11, origin = LibraryOrigin.MANUAL)
        val record = LocalLibraryStore.tombstones(context)
        assertFalse(
            "a deliberate re-add takes the removal back",
            LibraryTombstones.isRemoved(record, "series", "tt1", 11)
        )
        assertTrue(
            "and it is written as a CLEARANCE rather than a deletion: a deletion " +
                "would be undone by the other device's older removal on the next merge",
            record.values.any { !it.removed && it.key.contains("tt1") }
        )
    }

    @Test
    fun `a pulled record lands in the profile it was addressed to`() {
        val key = LocalLibraryStore.dedupeKeyOf("series", "tt1", 11)
        val incoming = LibraryTombstones.decode(
            LibraryTombstones.encode(
                mapOf(key to LibraryTombstone(key, 1_000L, removed = true))
            )
        )

        val mergedKid = LocalLibraryStore.mergePulledTombstones(
            context = context,
            profileId = "kid",
            incoming = incoming
        )
        assertTrue(
            "the merge answers the union it stored",
            mergedKid.containsKey(key)
        )
        assertTrue(
            "and it landed in that profile's own file, not in the ambient one",
            LocalLibraryStore.tombstones(context, "kid").containsKey(key) &&
                !LocalLibraryStore.tombstones(context).containsKey(key)
        )
    }

    @Test
    fun `the record is capped, and a blob it cannot read is left alone`() {
        val record = (1..(LibraryTombstones.MAX_ENTRIES + 3)).associate { index ->
            val key = LocalLibraryStore.dedupeKeyOf("movie", null, index)
            key to LibraryTombstone(key, index.toLong(), removed = true)
        }
        libraryPrefs().edit()
            .putString(LibraryTombstones.PREFS_KEY, LibraryTombstones.encode(record))
            .apply()

        assertEquals(
            "a library that removes more than the cap keeps the newest",
            LibraryTombstones.MAX_ENTRIES,
            LocalLibraryStore.tombstones(context).size
        )
        assertEquals(
            "an unreadable library blob is handed back unchanged rather than emptied",
            "{not json",
            LocalLibraryStore.withoutTombstonedRows(
                "{not json",
                LocalLibraryStore.tombstones(context)
            )
        )
    }
}
