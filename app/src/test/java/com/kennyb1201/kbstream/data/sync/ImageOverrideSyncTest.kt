package com.kennyb1201.kbstream.data.sync

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.images.ImageOverrideDao
import com.kennyb1201.kbstream.data.images.ImageOverrideEntity
import com.kennyb1201.kbstream.data.images.ImageOverrides
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Two TVs, one account: does the poster a viewer picked on one actually reach
 * the other?
 *
 * The field report: a poster / backdrop override set by long-press in the Media
 * section stayed on the device that set it. `image_overrides` was a local table
 * with no cloud twin, and the sync layer carried three tables - history, watched
 * markers and prefs - so the Onn 4K Plus went on drawing TMDB's artwork until
 * somebody chose it again there.
 *
 * These tests drive the REAL rules ([ImageOverrideRowRules], [SyncKeys]) through
 * a tiny in-memory cloud and a stand-in local table, in the order the app uses
 * them: the write enqueues a payload, the flush publishes it, and the sibling
 * applies it through the same decision the realtime applier and the pull share
 * (`image override set on device A syncs to device B`). The last test puts the
 * published payload into a real (in-memory) Room `image_overrides` table, so the
 * column mapping is exercised rather than assumed.
 *
 * Both TVs use the SAME profile id - that is how profiles are shared across
 * devices; `rows stay inside their profile` covers the other case.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = ImageOverrideSyncTest.NoopApplication::class)
class ImageOverrideSyncTest {

    class NoopApplication : Application()

    private companion object {
        const val FAMILY = "family"
        const val TMDB_ID = 603
        const val OTHER_TMDB_ID = 1399
        val POSTER_KEY = ImageOverrides.key(TMDB_ID, ImageOverrides.TYPE_POSTER, ImageOverrides.SCREEN_POSTER)
        val DETAIL_KEY =
            ImageOverrides.key(TMDB_ID, ImageOverrides.TYPE_BACKDROP, ImageOverrides.SCREEN_DETAIL)
    }

    private lateinit var db: WatchHistoryDatabase
    private lateinit var dao: ImageOverrideDao

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(context, WatchHistoryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.imageOverrideDao()
    }

    @After
    fun close() {
        db.close()
    }

    private fun entity(
        key: String,
        path: String,
        tmdbId: Int = TMDB_ID,
        type: String = ImageOverrides.TYPE_POSTER,
        screen: String = ImageOverrides.SCREEN_POSTER
    ) = ImageOverrideEntity(key = key, tmdbId = tmdbId, type = type, screen = screen, imagePath = path)

    private fun poster(path: String) =
        entity(ImageOverrides.key(TMDB_ID, ImageOverrides.TYPE_POSTER, ImageOverrides.SCREEN_POSTER), path)

    // ── the in-memory cloud and one TV ─────────────────────────────────────

    /** In-memory stand-in for `sync_image_overrides`: storedKey -> row payload. */
    private class Cloud {
        val rows = LinkedHashMap<String, JsonObject>()

        /** The keys this profile's rows were stored under, unscoped. */
        fun keysFor(profileId: String): Set<String> =
            rows.keys.filter { SyncKeys.matchesProfile(it, profileId) }
                .map { SyncKeys.unscoped(it) }
                .toSet()
    }

    /**
     * One TV. [table] is its local `image_overrides` rows and [queued] is its
     * outbox: the write path enqueues a payload there, and it only reaches the
     * cloud when [flush] runs - which is what makes the race tests meaningful.
     */
    private class Device(private val profileId: String) {
        val table = LinkedHashMap<String, ImageOverrideEntity>()
        val queued = LinkedHashMap<String, JsonObject>()

        fun choose(entity: ImageOverrideEntity, at: Long) {
            table[entity.key] = entity
            queued[SyncKeys.scoped(entity.key, profileId)] =
                ImageOverrideRowRules.payload(entity, at)
        }

        fun reset(key: String, at: Long) {
            table.remove(key)
            queued[SyncKeys.scoped(key, profileId)] = ImageOverrideRowRules.tombstone(key, at)
        }

        /** The outbox flush: this device's own queued writes win their slots. */
        fun flush(cloud: Cloud) {
            queued.forEach { (storedKey, payload) -> cloud.rows[storedKey] = payload }
            queued.clear()
        }

        /** pushOverrides: seed only the keys the account does not carry yet. */
        fun seedPush(cloud: Cloud, at: Long) {
            val missing = table.values.filter { it.key !in cloud.keysFor(profileId) }
            missing.forEach { entity ->
                queued[SyncKeys.scoped(entity.key, profileId)] =
                    ImageOverrideRowRules.payload(entity, at)
            }
            flush(cloud)
        }

        /**
         * The apply path, row by row - the same decisions [SupabaseSync
         * .applyImageOverrideRow] makes, including the pending-write skip.
         */
        fun pull(cloud: Cloud) {
            cloud.rows.forEach { (storedKey, payload) ->
                if (!SyncKeys.matchesProfile(storedKey, profileId)) return@forEach
                val key = SyncKeys.unscoped(storedKey)
                val remoteUpdated = ImageOverrideRowRules.updatedAt(payload)
                val pending = queued[storedKey]
                if (pending != null &&
                    ImageOverrideRowRules.updatedAt(pending) >= remoteUpdated
                ) {
                    return@forEach
                }
                val deletedAt = ImageOverrideRowRules.deletedAt(payload)
                if (deletedAt != null) {
                    table.remove(key)
                    return@forEach
                }
                val incoming = ImageOverrideRowRules.entity(key, payload) ?: return@forEach
                if (table[key]?.imagePath == incoming.imagePath) return@forEach
                table[key] = incoming
            }
        }
    }

    // ── the payload the wire carries ───────────────────────────────────────

    @Test
    fun `the payload carries every column the local row has`() {
        val payload = ImageOverrideRowRules.payload(poster("/poster.jpg"), updatedAt = 1_000L)

        assertEquals(POSTER_KEY, payload.str("key"))
        assertEquals(TMDB_ID.toString(), payload.str("tmdbId"))
        assertEquals(ImageOverrides.TYPE_POSTER, payload.str("type"))
        assertEquals(ImageOverrides.SCREEN_POSTER, payload.str("screen"))
        assertEquals("/poster.jpg", payload.str("imagePath"))
        assertEquals(1_000L, ImageOverrideRowRules.updatedAt(payload))
    }

    @Test
    fun `a row without an image is a reset, not a choice`() {
        val tombstone = ImageOverrideRowRules.tombstone(POSTER_KEY, deletedAt = 2_000L)

        assertTrue(ImageOverrideRowRules.isTombstone(tombstone))
        assertEquals(2_000L, ImageOverrideRowRules.deletedAt(tombstone))
        assertNull(
            "a reset must not be read back as a choice",
            ImageOverrideRowRules.entity(POSTER_KEY, tombstone)
        )
    }

    @Test
    fun `an incomplete payload is refused rather than filed`() {
        // A row from a build that wrote fewer fields must not become a row with
        // a blank image path - that reads as \"the viewer chose nothing\".
        val missingPath = JsonObject(
            mapOf(
                "key" to JsonPrimitive(POSTER_KEY),
                "tmdbId" to JsonPrimitive(TMDB_ID),
                "type" to JsonPrimitive(ImageOverrides.TYPE_POSTER),
                "screen" to JsonPrimitive(ImageOverrides.SCREEN_POSTER)
            )
        )
        assertNull(ImageOverrideRowRules.entity(POSTER_KEY, missingPath))

        val missingId = JsonObject(
            mapOf(
                "key" to JsonPrimitive(POSTER_KEY),
                "type" to JsonPrimitive(ImageOverrides.TYPE_POSTER),
                "screen" to JsonPrimitive(ImageOverrides.SCREEN_POSTER),
                "imagePath" to JsonPrimitive("/poster.jpg")
            )
        )
        assertNull(ImageOverrideRowRules.entity(POSTER_KEY, missingId))
    }

    // ── device to device ───────────────────────────────────────────────────

    @Test
    fun `a poster chosen on one TV arrives on the other`() {
        val cloud = Cloud()
        val livingRoom = Device(FAMILY)
        val bedroom = Device(FAMILY)

        livingRoom.choose(poster("/chosen.jpg"), at = 1_000L)
        livingRoom.flush(cloud)
        bedroom.pull(cloud)

        assertEquals(
            "the choice must survive the trip, path and all",
            "/chosen.jpg",
            bedroom.table[POSTER_KEY]?.imagePath
        )
    }

    @Test
    fun `a backdrop chosen for one surface does not claim the others`() {
        val cloud = Cloud()
        val livingRoom = Device(FAMILY)
        val bedroom = Device(FAMILY)

        livingRoom.choose(
            entity(
                key = DETAIL_KEY,
                path = "/detail.jpg",
                type = ImageOverrides.TYPE_BACKDROP,
                screen = ImageOverrides.SCREEN_DETAIL
            ),
            at = 1_000L
        )
        livingRoom.flush(cloud)
        bedroom.pull(cloud)

        assertEquals("/detail.jpg", bedroom.table[DETAIL_KEY]?.imagePath)
        assertNull(
            "another surface of the same title keeps TMDB's art",
            bedroom.table[
                ImageOverrides.key(
                    TMDB_ID,
                    ImageOverrides.TYPE_BACKDROP,
                    ImageOverrides.SCREEN_STREAMS
                )
            ]
        )
    }

    @Test
    fun `a reset on one TV clears the choice on the other`() {
        val cloud = Cloud()
        val livingRoom = Device(FAMILY)
        val bedroom = Device(FAMILY)

        livingRoom.choose(poster("/chosen.jpg"), at = 1_000L)
        livingRoom.flush(cloud)
        bedroom.pull(cloud)
        assertNotNull(bedroom.table[POSTER_KEY])

        // \"Reset to default\" on the living room TV. A plain cloud DELETE would
        // be invisible to the bedroom TV, which still holds its copy and would
        // go on drawing the still the viewer just rejected.
        livingRoom.reset(POSTER_KEY, at = 2_000L)
        livingRoom.flush(cloud)
        bedroom.pull(cloud)

        assertNull(bedroom.table[POSTER_KEY])
    }

    @Test
    fun `the most recent choice wins and both TVs converge`() {
        val cloud = Cloud()
        val livingRoom = Device(FAMILY)
        val bedroom = Device(FAMILY)

        livingRoom.choose(poster("/first.jpg"), at = 1_000L)
        livingRoom.flush(cloud)
        bedroom.choose(poster("/second.jpg"), at = 2_000L)
        bedroom.flush(cloud)

        livingRoom.pull(cloud)
        bedroom.pull(cloud)

        assertEquals(
            "the later choice is the account's, and the TV that had the older " +
                "one must adopt it",
            "/second.jpg",
            livingRoom.table[POSTER_KEY]?.imagePath
        )
        assertEquals(
            "and the two TVs agree",
            livingRoom.table[POSTER_KEY]?.imagePath,
            bedroom.table[POSTER_KEY]?.imagePath
        )
    }

    @Test
    fun `a device's own unpublished choice is not walked back by an older row`() {
        val cloud = Cloud()
        val livingRoom = Device(FAMILY)

        // The account still holds an older choice from the bedroom TV...
        cloud.rows[SyncKeys.scoped(POSTER_KEY, FAMILY)] =
            ImageOverrideRowRules.payload(poster("/older.jpg"), updatedAt = 2_000L)
        // ...while this TV has just chosen a newer one, not yet flushed (a
        // slow network, or the app closed during the write).
        livingRoom.choose(poster("/newer.jpg"), at = 5_000L)

        livingRoom.pull(cloud)

        assertEquals(
            "the pending write is the newer one and must not be overwritten by " +
                "the pull that raced it",
            "/newer.jpg",
            livingRoom.table[POSTER_KEY]?.imagePath
        )
    }

    @Test
    fun `a stale pending write does not block a newer remote choice`() {
        val cloud = Cloud()
        val livingRoom = Device(FAMILY)

        cloud.rows[SyncKeys.scoped(POSTER_KEY, FAMILY)] =
            ImageOverrideRowRules.payload(poster("/newer.jpg"), updatedAt = 5_000L)
        livingRoom.choose(poster("/older.jpg"), at = 2_000L)

        livingRoom.pull(cloud)

        assertEquals(
            "last write wins: the remote choice is newer, so the local one goes",
            "/newer.jpg",
            livingRoom.table[POSTER_KEY]?.imagePath
        )
    }

    @Test
    fun `a choice the other TV already has is a no-op, not an echo`() {
        val cloud = Cloud()
        val livingRoom = Device(FAMILY)
        val bedroom = Device(FAMILY)

        livingRoom.choose(poster("/same.jpg"), at = 1_000L)
        livingRoom.flush(cloud)
        bedroom.pull(cloud)

        // The bedroom TV pulls again (a later sync, a realtime replay of its own
        // row): applying an identical row must change nothing and must not
        // re-publish, or two devices would echo one choice forever.
        val before = bedroom.table[POSTER_KEY]
        bedroom.pull(cloud)
        bedroom.seedPush(cloud, at = 9_000L)

        assertEquals(before, bedroom.table[POSTER_KEY])
        assertEquals(
            "the unchanged row is not re-published with a fresh stamp, which " +
                "would clobber a sibling's newer edit",
            1,
            cloud.rows.size
        )
        assertEquals(
            "and its stamp is still the original one",
            1_000L,
            ImageOverrideRowRules.updatedAt(cloud.rows[SyncKeys.scoped(POSTER_KEY, FAMILY)]!!)
        )
    }

    @Test
    fun `a seeded device publishes the choices the account does not have`() {
        val cloud = Cloud()
        val livingRoom = Device(FAMILY)

        // Chosen while signed out: local only, no account row yet.
        livingRoom.table[POSTER_KEY] = poster("/offline.jpg")
        livingRoom.seedPush(cloud, at = 3_000L)

        assertNotNull(cloud.rows[SyncKeys.scoped(POSTER_KEY, FAMILY)])
    }

    @Test
    fun `rows stay inside their profile`() {
        val cloud = Cloud()
        val mom = Device("mom")
        val dad = Device("dad")

        mom.choose(poster("/mom.jpg"), at = 1_000L)
        mom.flush(cloud)
        dad.pull(cloud)

        assertNull(
            "a choice scoped to another profile must not paint on this one",
            dad.table[POSTER_KEY]
        )
        // And the two profiles can hold different artwork for the same title.
        dad.choose(poster("/dad.jpg"), at = 2_000L)
        dad.flush(cloud)
        mom.pull(cloud)
        assertEquals("/mom.jpg", mom.table[POSTER_KEY]?.imagePath)
        assertEquals("/dad.jpg", dad.table[POSTER_KEY]?.imagePath)
    }

    // ── the payload against the real table it is written into ──────────────

    @Test
    fun `a published payload lands as a row in the receiving device's table`() = runBlocking {
        val cloud = Cloud()
        val livingRoom = Device(FAMILY)
        livingRoom.choose(poster("/chosen.jpg"), at = 1_000L)
        livingRoom.flush(cloud)

        // The receiving device applies the cloud row exactly as the pull does.
        val storedKey = SyncKeys.scoped(POSTER_KEY, FAMILY)
        val key = SyncKeys.unscoped(storedKey)
        val payload = cloud.rows[storedKey]!!
        val entity = ImageOverrideRowRules.entity(key, payload)
        assertNotNull(entity)
        dao.set(entity!!)

        assertEquals(
            "/chosen.jpg",
            dao.get(TMDB_ID, ImageOverrides.TYPE_POSTER, ImageOverrides.SCREEN_POSTER)?.imagePath
        )
        assertEquals(
            "and the mirror the surfaces read is loaded from exactly these rows",
            mapOf(POSTER_KEY to "/chosen.jpg"),
            dao.getAll().associate { it.key to it.imagePath }
        )
    }

    @Test
    fun `a reset payload clears the row and only that surface`() = runBlocking {
        dao.set(poster("/poster.jpg"))
        dao.set(
            entity(
                key = DETAIL_KEY,
                path = "/detail.jpg",
                type = ImageOverrides.TYPE_BACKDROP,
                screen = ImageOverrides.SCREEN_DETAIL
            )
        )

        // The apply path for a tombstone: clear using the LOCAL row's own
        // columns, so no parsing of the composed key is needed.
        val local = dao.getAll().firstOrNull { it.key == POSTER_KEY }
        assertNotNull(local)
        dao.clear(local!!.tmdbId, local.type, local.screen)

        assertNull(dao.get(TMDB_ID, ImageOverrides.TYPE_POSTER, ImageOverrides.SCREEN_POSTER))
        assertNotNull(
            "the other surface's choice is not the one that was reset",
            dao.get(TMDB_ID, ImageOverrides.TYPE_BACKDROP, ImageOverrides.SCREEN_DETAIL)
        )
    }

    @Test
    fun `another profile's override for the same title is a separate row`() = runBlocking {
        dao.set(poster("/family.jpg"))
        dao.set(
            entity(
                key = ImageOverrides.key(OTHER_TMDB_ID, ImageOverrides.TYPE_POSTER, ImageOverrides.SCREEN_POSTER),
                path = "/other.jpg",
                tmdbId = OTHER_TMDB_ID
            )
        )

        assertEquals(2, dao.getAll().size)
    }

    private fun JsonObject.str(field: String): String =
        (this[field] as? JsonPrimitive)?.content.orEmpty()
}
