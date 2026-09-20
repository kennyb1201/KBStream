package com.kennyb1201.kbstream.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The watched-marker row is the only thing that carries a poster's checkmark
 * and eye badge between devices, so both flags have to survive the wire.
 *
 * They did not: the payload published only `isWatched`, and the pull/applier
 * rebuilt the entity without the eye flag at all — landing `false` in the
 * receiving profile's cache, where the merge then treats that row as the
 * fresh truth and stops re-deriving the key for the whole cache TTL. Net
 * effect on the second TV: no eye badge, and (once the same table also
 * carried this device's derived "nothing watched here" rows) checkmarks
 * disappearing too. These tests pin the wire format and the publish rule.
 */
class WatchedMarkerSyncTest {

    private val json = Json

    @Test
    fun `payload carries both badge flags`() {
        val payload = WatchedMarkerRules.payload(
            key = "series::tt0903747",
            imdbId = "tt0903747",
            mediaType = "series",
            isWatched = false,
            isPartiallyWatched = true,
            updatedAt = 1_700_000_000_000L
        )

        val markers = WatchedMarkerRules.read(payload)

        assertFalse("started-but-unfinished is not a completed badge", markers.isWatched)
        assertTrue("the eye flag must travel with the row", markers.isPartiallyWatched)
    }

    @Test
    fun `payload round trips through the wire`() {
        // What a device actually does: serialize to the cloud, decode on the
        // other side. A dropped field is indistinguishable from a false one,
        // which is exactly how the eye badge used to vanish.
        val original = WatchedMarkerRules.payload(
            key = "series::tmdb:1399",
            imdbId = "tt0944947",
            mediaType = "series",
            isWatched = false,
            isPartiallyWatched = true,
            updatedAt = 42L
        )

        val transmitted = json.parseToJsonElement(original.toString()).jsonObject

        assertTrue(WatchedMarkerRules.read(transmitted).isPartiallyWatched)
        assertEquals("series::tmdb:1399", transmitted["key"].toString().trim('"'))
        assertEquals("tt0944947", transmitted["imdbId"].toString().trim('"'))
    }

    @Test
    fun `field names are stable`() {
        // Older builds read these exact keys, so renaming one silently
        // downgrades every row to "not watched".
        val payload = WatchedMarkerRules.payload("movie::tt1", "tt1", "movie", true, false, 7L)

        assertEquals(
            setOf("key", "imdbId", "mediaType", "isWatched", "isPartiallyWatched", "updatedAt"),
            payload.keys
        )
    }

    @Test
    fun `rows written before the eye flag existed read as not partial`() {
        val legacy = json.parseToJsonElement(
            """{"key":"series::tt1","imdbId":"tt1","mediaType":"series",""" +
                """"isWatched":false,"updatedAt":5}"""
        ).jsonObject

        val markers = WatchedMarkerRules.read(legacy)

        assertFalse(markers.isWatched)
        assertFalse(markers.isPartiallyWatched)
    }

    @Test
    fun `bulk push publishes markers only`() {
        // A row that is neither watched nor started is this device's DERIVED
        // answer for an item nobody has touched — every preloaded rail item
        // gets one. Publishing it stamps a fresh timestamp on the same key in
        // the cloud, so the other TV's real badge loses the last-write-wins
        // race to a negative it never needed.
        assertFalse(WatchedMarkerRules.shouldPublish(isWatched = false, isPartiallyWatched = false))

        assertTrue("completed checkmark", WatchedMarkerRules.shouldPublish(true, false))
        assertTrue("eye badge", WatchedMarkerRules.shouldPublish(false, true))
        assertTrue("completed wins over eye", WatchedMarkerRules.shouldPublish(true, true))
    }

    @Test
    fun `a partial row and a completed row do not read as each other`() {
        fun read(isWatched: Boolean, isPartiallyWatched: Boolean) =
            WatchedMarkerRules.read(
                WatchedMarkerRules.payload("series::tt1", "tt1", "series", isWatched, isPartiallyWatched, 1L)
            )

        val partial = read(false, true)
        val completed = read(true, false)

        assertTrue(partial.isPartiallyWatched && !partial.isWatched)
        assertTrue(completed.isWatched && !completed.isPartiallyWatched)
    }

    @Test
    fun `unwatched tombstone still travels`() {
        // "Mark as Unwatched" enqueues its row directly, outside the bulk
        // pass, so skipping negatives in pushWatched must not silence it.
        val tombstone: JsonObject = WatchedMarkerRules.payload(
            key = "movie::tt1",
            imdbId = "tt1",
            mediaType = "movie",
            isWatched = false,
            isPartiallyWatched = false,
            updatedAt = 9L
        )

        assertEquals("9", tombstone["updatedAt"].toString())
        assertFalse(WatchedMarkerRules.shouldPublish(false, false))
    }
}
