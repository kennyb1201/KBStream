package com.kennyb1201.kbstream.data.sync

import com.kennyb1201.kbstream.data.cache.WatchedStatusEntity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two TVs, one account: do the poster badges actually agree afterwards?
 *
 * Every badge bug reported from the living room came from a merge decision,
 * not from the UI — a device published its own derived "nothing here" answer
 * over a sibling's real one, an older row won a last-write-wins race, or a
 * reader silently dropped the eye flag. These tests drive the REAL rules
 * ([watchedMarkerRow], [WatchedMarkerRules], [remoteWins], [SyncKeys]) through
 * a tiny in-memory cloud, so the orderings that broke the TVs are pinned.
 *
 * Both TVs use the SAME profile id (that is how profiles are shared across
 * devices); [rows stay inside their profile] covers the other case.
 */
class MarkerConvergenceTest {

    private companion object {
        // Bare profile ids: SyncKeys adds the "p:" scope prefix itself.
        const val FAMILY = "family"
        const val SHOW = "series::tt0903747"
        const val TMDB_SHOW = "series::tmdb:1399"
    }

    /** In-memory stand-in for the sync tables: storedKey -> row payload. */
    private class Cloud {
        val rows = LinkedHashMap<String, JsonObject>()
    }

    /**
     * One TV. [cache] is what its rails read, and the push/pull helpers call
     * exactly the rules the app calls, in the same order.
     */
    private class Device(private val profileId: String) {
        val cache = LinkedHashMap<String, WatchedStatusEntity>()

        fun start(key: String, at: Long, imdbId: String = "tt0000001") {
            cache[key] = WatchedStatusEntity(key, imdbId, "series", false, true, at)
        }

        fun finish(key: String, at: Long, imdbId: String = "tt0000001") {
            cache[key] = WatchedStatusEntity(key, imdbId, "series", true, false, at)
        }

        fun forget(key: String, at: Long, imdbId: String = "tt0000001") {
            cache[key] = WatchedStatusEntity(key, imdbId, "series", false, false, at)
        }

        /**
         * Bulk push: markers only — derived negatives stay local — and only
         * when this device's copy is newer than the account's (mirrors
         * pushWatched, including its cloud-timestamp read).
         */
        fun push(cloud: Cloud) {
            val cloudTimestamps = cloud.rows.mapNotNull { (stored, payload) ->
                val updated = payload["updatedAt"]?.jsonPrimitive?.content?.toLongOrNull()
                if (updated == null) null else stored to updated
            }.toMap()

            cache.values
                .filter { WatchedMarkerRules.shouldPublish(it.isWatched, it.isPartiallyWatched) }
                .filter { entity ->
                    WatchedMarkerRules.shouldPush(
                        localUpdatedAt = entity.updatedAt,
                        cloudUpdatedAt = cloudTimestamps[SyncKeys.scoped(entity.key, profileId)]
                    )
                }
                .forEach { entity ->
                    cloud.rows[SyncKeys.scoped(entity.key, profileId)] =
                        WatchedMarkerRules.payload(
                            key = entity.key,
                            imdbId = entity.imdbId,
                            mediaType = entity.mediaType,
                            isWatched = entity.isWatched,
                            isPartiallyWatched = entity.isPartiallyWatched,
                            updatedAt = entity.updatedAt
                        )
                }
        }

        /** Pull: only this profile's rows, merged by the shared rule. */
        fun pull(cloud: Cloud) {
            cloud.rows.forEach { (storedKey, payload) ->
                if (!SyncKeys.matchesProfile(storedKey, profileId)) return@forEach
                val key = SyncKeys.unscoped(storedKey)
                val merged = watchedMarkerRow(key, payload, cache[key]?.updatedAt ?: 0L)
                if (merged != null) cache[key] = merged
            }
        }

        fun markers(key: String) = cache[key]?.let { it.isWatched to it.isPartiallyWatched }
    }

    private fun cloudRow(
        key: String,
        profileId: String?,
        isWatched: Boolean,
        isPartiallyWatched: Boolean,
        updatedAt: Long
    ): Pair<String, JsonObject> =
        SyncKeys.scoped(key, profileId) to WatchedMarkerRules.payload(
            key = key,
            imdbId = "tt0000001",
            mediaType = "series",
            isWatched = isWatched,
            isPartiallyWatched = isPartiallyWatched,
            updatedAt = updatedAt
        )

    @Test
    fun `eye badge started on one TV reaches the other`() {
        val cloud = Cloud()
        val livingRoom = Device(FAMILY)
        val bedroom = Device(FAMILY)

        livingRoom.start(SHOW, at = 1_000)
        livingRoom.push(cloud)
        bedroom.pull(cloud)

        assertEquals(
            "started-but-unfinished must arrive as the eye, not as nothing",
            false to true,
            bedroom.markers(SHOW)
        )
    }

    @Test
    fun `finished show reaches the other TV as a checkmark`() {
        val cloud = Cloud()
        val livingRoom = Device(FAMILY)
        val bedroom = Device(FAMILY)

        livingRoom.finish(SHOW, at = 1_000)
        livingRoom.push(cloud)
        bedroom.pull(cloud)

        assertEquals(true to false, bedroom.markers(SHOW))
    }

    @Test
    fun `eye flag survives the wire for a tmdb-keyed rail`() {
        val cloud = Cloud()
        val livingRoom = Device(FAMILY)
        val bedroom = Device(FAMILY)

        livingRoom.start(TMDB_SHOW, at = 1_000)
        livingRoom.push(cloud)
        bedroom.pull(cloud)

        assertEquals(false to true, bedroom.markers(TMDB_SHOW))
    }

    @Test
    fun `a device never publishes its own derived negatives`() {
        val cloud = Cloud()
        val bedroom = Device(FAMILY)

        // What a preloaded rail leaves behind: "nothing watched here" rows.
        bedroom.forget(SHOW, at = 5_000)
        bedroom.forget("movie::tt1375666", at = 5_000)
        bedroom.push(cloud)

        assertTrue(
            "negative rows must not race a sibling's real marker",
            cloud.rows.isEmpty()
        )
    }

    @Test
    fun `older negative row cannot erase a newer eye badge`() {
        val cloud = Cloud()
        val bedroom = Device(FAMILY)
        bedroom.start(SHOW, at = 5_000)

        // A stale "not started" row from before the user began the show.
        val (key, payload) = cloudRow(SHOW, FAMILY, false, false, updatedAt = 100)
        cloud.rows[key] = payload

        bedroom.pull(cloud)

        assertEquals(false to true, bedroom.markers(SHOW))
    }

    @Test
    fun `a deliberate unwatch does propagate`() {
        val cloud = Cloud()
        val bedroom = Device(FAMILY)
        bedroom.start(SHOW, at = 5_000)

        val (key, payload) = cloudRow(SHOW, FAMILY, false, false, updatedAt = 9_000)
        cloud.rows[key] = payload

        bedroom.pull(cloud)

        assertEquals(false to false, bedroom.markers(SHOW))
    }

    @Test
    fun `rows stay inside their profile`() {
        val cloud = Cloud()
        val mom = Device("mom")
        val dad = Device("dad")

        mom.start(SHOW, at = 1_000)
        mom.push(cloud)
        dad.pull(cloud)

        assertNull(
            "a marker scoped to another profile must not paint on this one",
            dad.markers(SHOW)
        )
        // And the two profiles can hold different answers for the same show.
        dad.finish(SHOW, at = 2_000)
        dad.push(cloud)
        mom.pull(cloud)
        assertEquals(false to true, mom.markers(SHOW))
        assertEquals(true to false, dad.markers(SHOW))
    }

    @Test
    fun `both TVs agree after exchanging in either order`() {
        listOf(true, false).forEach { livingPushesFirst ->
            val cloud = Cloud()
            val livingRoom = Device(FAMILY)
            val bedroom = Device(FAMILY)

            // Same show, both TVs: one marked it started, the other finished
            // it later. Whatever the order, the newer answer must win.
            livingRoom.start(SHOW, at = 1_000)
            bedroom.finish(SHOW, at = 2_000)

            if (livingPushesFirst) {
                livingRoom.push(cloud)
                bedroom.push(cloud)
                livingRoom.pull(cloud)
                bedroom.pull(cloud)
            } else {
                bedroom.push(cloud)
                livingRoom.push(cloud)
                livingRoom.pull(cloud)
                bedroom.pull(cloud)
            }

            assertEquals(
                "livingFirst=$livingPushesFirst: the TVs disagreed about one show",
                livingRoom.markers(SHOW),
                bedroom.markers(SHOW)
            )
            assertEquals(
                "livingFirst=$livingPushesFirst: the completed badge must win on both",
                true to false,
                bedroom.markers(SHOW)
            )
        }
    }

    @Test
    fun `marker merge ignores rows without a timestamp`() {
        val malformed = JsonObject(
            mapOf("isWatched" to kotlinx.serialization.json.JsonPrimitive(true))
        )

        assertNull(watchedMarkerRow(SHOW, malformed, localUpdatedAt = 0L))
    }

    /**
     * Regression pin for the bug the split fixed: the realtime applier used to
     * read `isWatched` alone, which rebuilt a started show as "watched and not
     * started" — erasing the eye badge on the receiving device until the row
     * aged out. The shared rule must not regress to that shape.
     */
    @Test
    fun `a reader that ignores the eye flag would corrupt the badge`() {
        val cloud = Cloud()
        val livingRoom = Device(FAMILY)
        livingRoom.start(SHOW, at = 1_000)
        livingRoom.push(cloud)

        val payload = cloud.rows[SyncKeys.scoped(SHOW, FAMILY)]
        assertNotNull(payload)

        val merged = watchedMarkerRow(SHOW, payload!!, localUpdatedAt = 0L)
        assertTrue("the rule keeps the eye flag", merged!!.isPartiallyWatched)
        assertFalse(merged.isWatched)

        // The old (buggy) shape, spelled out: only the visible field is read.
        val eyeFlagWouldBeLost = payload["isWatched"]!!.jsonPrimitive.boolean
        assertFalse(
            "reading isWatched alone says 'not watched' — the eye badge only " +
                "survives because the merge reads both flags",
            eyeFlagWouldBeLost
        )
    }
}
