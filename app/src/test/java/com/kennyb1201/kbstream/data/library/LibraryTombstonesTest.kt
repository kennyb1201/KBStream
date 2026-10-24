package com.kennyb1201.kbstream.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The My List removal record, as arithmetic.
 *
 * Why it exists: the library sync replaces a profile's whole list blob behind a
 * timestamp, so one device's round could always put back what another device's
 * cleanup had just removed - the field replayed it as "81 stamped titles removed,
 * 81 offered again, then 413 added". A removal has to travel as a FACT rather
 * than as the absence of a row, which is what this record is: merged as a union,
 * consulted by every pull and every round, and never overwritten by a newer list.
 *
 * The identity rule is the part worth pinning here. A tombstone is filed under
 * the store's de-dupe key, but a title's row on one device can carry both ids
 * while a round's candidate - or the same title on the other device - knows only
 * TMDB's. Matching by key STRING would let exactly those titles come back, so
 * matching compares the ids the key carries, the same rule the library already
 * uses for "this is already in My List".
 */
class LibraryTombstonesTest {

    private fun row(
        title: String,
        mediaType: String = "movie",
        imdbId: String? = null,
        tmdbId: Int? = null,
        origin: LibraryOrigin = LibraryOrigin.TORBOX_SYNC
    ) = LibraryItem(
        source = LibrarySource.LOCAL,
        mediaType = mediaType,
        title = title,
        year = null,
        posterUrl = null,
        imdbId = imdbId,
        tmdbId = tmdbId,
        origin = origin
    )

    private fun key(mediaType: String, imdbId: String?, tmdbId: Int?): String =
        LocalLibraryStore.dedupeKeyOf(mediaType, imdbId, tmdbId)

    // ── Identity ──────────────────────────────────────────────────────────

    @Test
    fun `a tombstone matches the same title however few ids either side knows`() {
        val removed = mapOf(
            key("movie", "tt9", 42) to LibraryTombstone(key("movie", "tt9", 42), 100, true)
        )

        assertTrue(
            "the exact pair matches",
            LibraryTombstones.isRemoved(removed, "movie", "tt9", 42)
        )
        assertTrue(
            "a candidate that only knows TMDB's id is the same title",
            LibraryTombstones.isRemoved(removed, "movie", null, 42)
        )
        assertTrue(
            "and one that only knows the IMDb id",
            LibraryTombstones.isRemoved(removed, "movie", "tt9", null)
        )
        assertFalse(
            "a different title, sharing nothing, is untouched",
            LibraryTombstones.isRemoved(removed, "movie", "tt7", 43)
        )
        assertFalse(
            "and a series is not the movie of the same number",
            LibraryTombstones.isRemoved(removed, "series", "tt9", 42)
        )

        val seriesRecord = mapOf(
            key("series", "tt5", 7) to LibraryTombstone(key("series", "tt5", 7), 100, true)
        )
        assertTrue(
            "the media type aliases collapse here as they do in every other " +
                "library comparison",
            LibraryTombstones.isRemoved(seriesRecord, "tv", "tt5", 7)
        )
    }

    @Test
    fun `a clearance means the title is welcome again`() {
        val cleared = mapOf(
            key("movie", null, 42) to LibraryTombstone(key("movie", null, 42), 100, false)
        )
        assertFalse(
            "only removals count against a title",
            LibraryTombstones.isRemoved(cleared, "movie", null, 42)
        )
        assertFalse(
            "and an empty record removes nothing",
            LibraryTombstones.isRemoved(emptyMap(), "movie", null, 42)
        )
    }

    @Test
    fun `a key this build cannot read removes nothing`() {
        val weird = mapOf(
            "malformed" to LibraryTombstone("malformed", 1, true),
            ":tt1:" to LibraryTombstone(":tt1:", 1, true)
        )
        assertFalse(
            "an unreadable tombstone must not delete a title",
            LibraryTombstones.isRemoved(weird, "movie", "tt1", 1)
        )
    }

    @Test
    fun `the rows a record names are the rows a pull drops`() {
        val record = mapOf(
            key("series", "tt5", 7) to LibraryTombstone(key("series", "tt5", 7), 10, true)
        )
        val rows = listOf(
            row("The Leak", mediaType = "series", imdbId = "tt5", tmdbId = 7),
            row("Kept", mediaType = "series", imdbId = "tt6", tmdbId = 8)
        )

        assertEquals(
            "the removed title leaves the list and nothing else does",
            listOf("Kept"),
            LibraryTombstones.removedFrom(rows, record).map { it.title }
        )
        assertEquals(
            "an empty record is the list unchanged",
            rows,
            LibraryTombstones.removedFrom(rows, emptyMap())
        )
    }

    // ── Merge ────────────────────────────────────────────────────────────

    @Test
    fun `the newest record per key wins`() {
        val tombstones = key("movie", null, 42)
        val merged = LibraryTombstones.merge(
            local = mapOf(tombstones to LibraryTombstone(tombstones, 100, true)),
            incoming = mapOf(tombstones to LibraryTombstone(tombstones, 200, true))
        )
        assertEquals(
            "a pull cannot move a removal's timestamp backwards",
            200L,
            merged.getValue(tombstones).at
        )
    }

    @Test
    fun `a removal does not undo a clearance`() {
        val tombstones = key("movie", null, 42)

        assertFalse(
            "the same key: the later clearance wins",
            LibraryTombstones.merge(
                local = mapOf(tombstones to LibraryTombstone(tombstones, 300, false)),
                incoming = mapOf(tombstones to LibraryTombstone(tombstones, 100, true))
            ).getValue(tombstones).removed
        )
        assertFalse(
            "and a stale device's older removal, filed under another key for the " +
                "same title, is retired by it too - the viewer's word is about the " +
                "title, not about one spelling of its key",
            LibraryTombstones.isRemoved(
                LibraryTombstones.merge(
                    local = mapOf(key("movie", "tt9", 42) to
                        LibraryTombstone(key("movie", "tt9", 42), 100, true)),
                    incoming = mapOf(tombstones to LibraryTombstone(tombstones, 300, false))
                ),
                "movie",
                "tt9",
                42
            )
        )
    }

    @Test
    fun `an older clearance cannot resurrect a newer removal`() {
        val tombstones = key("movie", null, 42)
        val merged = LibraryTombstones.merge(
            local = mapOf(tombstones to LibraryTombstone(tombstones, 500, true)),
            incoming = mapOf(tombstones to LibraryTombstone(tombstones, 100, false))
        )
        assertTrue(
            "removing a title again after re-adding it is a removal",
            merged.getValue(tombstones).removed
        )
    }

    @Test
    fun `the record is capped at its newest keys`() {
        val record = (1..(LibraryTombstones.MAX_ENTRIES + 5)).associate { index ->
            val entry = key("movie", null, index)
            entry to LibraryTombstone(entry, index.toLong(), true)
        }
        val capped = LibraryTombstones.merge(record, emptyMap())

        assertEquals(
            "a library that removes more than the cap keeps the newest, not the oldest",
            LibraryTombstones.MAX_ENTRIES,
            capped.size
        )
        assertTrue(
            "the most recent removal survives the cap",
            capped.containsKey(key("movie", null, LibraryTombstones.MAX_ENTRIES + 5))
        )
        assertFalse(
            "and the oldest is the one that goes",
            capped.containsKey(key("movie", null, 1))
        )
    }

    // ── Storage shape ────────────────────────────────────────────────────

    @Test
    fun `the stored record round-trips, and garbage reads as nothing`() {
        val record = mapOf(
            key("movie", "tt9", 42) to LibraryTombstone(key("movie", "tt9", 42), 1234, true),
            key("series", null, 7) to LibraryTombstone(key("series", null, 7), 99, false)
        )

        assertEquals(
            "what a pull sends is what the store reads back",
            record,
            LibraryTombstones.decode(LibraryTombstones.encode(record))
        )
        assertTrue(
            "a missing or torn blob is an empty record, never a crash on a pull",
            LibraryTombstones.decode(null).isEmpty() &&
                LibraryTombstones.decode("").isEmpty() &&
                LibraryTombstones.decode("{not json").isEmpty()
        )
        assertTrue(
            "and a payload written by a shape this build does not know keeps only " +
                "the entries it can read",
            LibraryTombstones.decode(
                """{"movie:-:42":{"at":5,"removed":true},"junk":5}"""
            ).keys == setOf("movie:-:42")
        )
    }
}
