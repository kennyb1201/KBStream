package com.kennyb1201.kbstream.data.addon

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kennyb1201.kbstream.data.cache.DiskSweep
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The catalog snapshots kept for a cold Home.
 *
 * The point of the store is that a page written by one launch is readable by
 * the next, so the round-trip is the test that matters; the staleness and
 * corruption cases are the two ways a snapshot must degrade to a miss rather
 * than to wrong data or an error. The sweep is the janitor for a directory that
 * grows by file COUNT, so it is tested against its two caps.
 */
@RunWith(AndroidJUnit4::class)
class AddonCatalogSnapshotStoreTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val dir =
        File(context.cacheDir, "snapshot_test_${System.nanoTime()}")

    @Before
    fun setUp() {
        dir.mkdirs()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
        // The sweep runs against the real cache directory, so the files it
        // leaves behind are cleaned up here too.
        File(context.cacheDir, DiskSweep.ADDON_CATALOG_DIR).deleteRecursively()
    }

    private fun metas(vararg names: String) =
        names.map { name ->
            MetaPreview(
                id = "tt-$name",
                type = "movie",
                name = name
            )
        }

    @Test
    fun `a saved snapshot reads back with its page and stamp`() {
        val stamp = System.currentTimeMillis()

        AddonCatalogSnapshotStore.saveTo(
            dir = dir,
            cacheKey = "https://addon.example/catalog/movie/top.json",
            metas = metas("Alpha", "Beta"),
            cachedAtMs = stamp
        )

        val loaded =
            AddonCatalogSnapshotStore.loadFrom(dir, "https://addon.example/catalog/movie/top.json")

        assertEquals("the page is round-tripped", listOf("Alpha", "Beta"), loaded?.metas?.map { it.name })
        assertEquals("the stamp is round-tripped", stamp, loaded?.cachedAtMs)
    }

    @Test
    fun `a snapshot older than the max age is not returned`() {
        val tooOld =
            System.currentTimeMillis() -
                DiskSweep.ADDON_CATALOG_MAX_AGE_MS -
                60_000L

        AddonCatalogSnapshotStore.saveTo(dir, "key", metas("Alpha"), tooOld)

        assertNull(
            "a snapshot past the age cap must be a miss, not painted",
            AddonCatalogSnapshotStore.loadFrom(dir, "key")
        )
    }

    @Test
    fun `an absent snapshot is a miss`() {
        assertNull(AddonCatalogSnapshotStore.loadFrom(dir, "never-written"))
    }

    @Test
    fun `a corrupt snapshot is a miss, not an error`() {
        AddonCatalogSnapshotStore.fileIn(dir, "key").writeText("{ this is not json")

        assertNull(
            "a torn or corrupt file must degrade to a miss",
            AddonCatalogSnapshotStore.loadFrom(dir, "key")
        )
    }

    @Test
    fun `different pages get different files and the same page reuses one`() {
        val pageA = AddonCatalogSnapshotStore.fileIn(dir, "https://a/catalog/movie/top.json")
        val pageAAgain = AddonCatalogSnapshotStore.fileIn(dir, "https://a/catalog/movie/top.json")
        val pageB = AddonCatalogSnapshotStore.fileIn(dir, "https://a/catalog/series/top.json")

        assertEquals("a page must reuse its own path", pageA, pageAAgain)
        assertNotEquals("two pages must not collide", pageA, pageB)
    }

    @Test
    fun `the sweep drops snapshots past the max age and keeps fresh ones`() =
        runBlocking {
            val sweepDir = File(context.cacheDir, DiskSweep.ADDON_CATALOG_DIR)
            sweepDir.mkdirs()

            val old = File(sweepDir, "old-snapshot.json")
            old.writeText("{}")
            old.setLastModified(
                System.currentTimeMillis() - DiskSweep.ADDON_CATALOG_MAX_AGE_MS - 60_000L
            )

            val fresh = File(sweepDir, "fresh-snapshot.json")
            fresh.writeText("{}")
            fresh.setLastModified(System.currentTimeMillis())

            val removed = DiskSweep.sweepAddonCatalogSnapshots(context)

            assertNull("the old snapshot must be swept", old.takeIf { it.isFile })
            assertEquals("the fresh snapshot must survive", true, fresh.isFile)
            assertNotEquals("the sweep must report the file it removed", 0, removed)
        }
}
