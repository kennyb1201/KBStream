package com.kennyb1201.kbstream.data.kb

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kennyb1201.kbstream.data.addon.AppContextHolder
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import com.kennyb1201.kbstream.data.settings.prefs.Prefs

/**
 * The two preference stores that preserve an unreadable blob but used to let the
 * next ordinary write replace it (sweep 7 3.11).
 *
 * Both hold state a viewer arranged by hand - the Home rail order and the browse
 * chips mirrored onto Home - so a blob this build cannot parse is the only copy
 * there is. The read side already logged it and kept a copy. What was missing is
 * the other half: the blob must stay where a build that CAN read it will find it,
 * and no write may replace it in the meantime. [KBHomeOrderPrefs.reset] is the
 * deliberate way out.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = HomeBlobReadSafetyTest.NoopApplication::class)
class HomeBlobReadSafetyTest {

    class NoopApplication : Application()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun prefs(baseName: String) =
        Prefs.file(context, ProfileStorage.prefsName(context, baseName))

    private val homeOrder get() = prefs("kbstream_kb_home_order")
    private val chips get() = prefs(BrowseHomeShortcuts.SYNC_STORE)

    private val corruptOrder = """{"order":["""
    private val corruptChips = """{"shortcuts":[{"categoryKey":"""

    @Before
    fun setUp() {
        // The stores mirror to the account on a successful write; this test is
        // about the write happening or not, not about WorkManager.
        AppContextHolder.appContext = null
        homeOrder.edit().clear().commit()
        chips.edit().clear().commit()
    }

    @Test
    fun `an unreadable home order is kept in place and cannot be written over`() {
        homeOrder.edit().putString("home_order_json", corruptOrder).commit()

        assertTrue(
            "the default arrangement is what a read can offer",
            KBHomeOrderPrefs.get(context).order.isEmpty()
        )
        assertTrue(KBHomeOrderPrefs.isBlobUnreadable(context))
        assertEquals(
            "the blob stays where a build that can read it will look",
            corruptOrder,
            homeOrder.getString("home_order_json", null)
        )
        assertEquals(
            "and a copy is kept as evidence",
            corruptOrder,
            homeOrder.getString("home_order_json_corrupt", null)
        )

        KBHomeOrderPrefs.save(context, KBHomeOrder(order = listOf("kb:One")))

        assertEquals(
            "the write is refused rather than saved over the only copy of the " +
                "viewer's arrangement",
            corruptOrder,
            homeOrder.getString("home_order_json", null)
        )
    }

    @Test
    fun `reset is the deliberate way out of an unreadable home order`() {
        homeOrder.edit().putString("home_order_json", corruptOrder).commit()
        // A read is what archives the evidence copy, so the reset has something
        // to leave alone; without it this asserted a copy nothing had written.
        assertTrue(KBHomeOrderPrefs.get(context).order.isEmpty())
        assertEquals(
            corruptOrder,
            homeOrder.getString("home_order_json_corrupt", null)
        )

        KBHomeOrderPrefs.reset(context)

        assertFalse("the store is writable again", KBHomeOrderPrefs.isBlobUnreadable(context))
        KBHomeOrderPrefs.save(context, KBHomeOrder(order = listOf("kb:One")))
        assertEquals(listOf("kb:One"), KBHomeOrderPrefs.get(context).order)
        assertEquals(
            "and the evidence copy survives the reset",
            corruptOrder,
            homeOrder.getString("home_order_json_corrupt", null)
        )
    }

    @Test
    fun `an unreadable chip row is kept, and add reports what is stored`() {
        chips.edit().putString(BrowseHomeShortcuts.SYNC_BLOB_KEY, corruptChips).commit()

        assertTrue(BrowseHomeShortcuts.list(context).isEmpty())
        assertTrue(BrowseHomeShortcuts.isBlobUnreadable(context))

        val after = BrowseHomeShortcuts.add(context, BrowseHomeShortcut("genres", 35, "Comedy"))

        assertTrue("a refused write must not read as an added chip", after.isEmpty())
        assertEquals(
            "the blob is untouched",
            corruptChips,
            chips.getString(BrowseHomeShortcuts.SYNC_BLOB_KEY, null)
        )
        assertEquals(
            "and preserved beside it",
            corruptChips,
            chips.getString("browse_home_shortcuts_json_corrupt", null)
        )
    }

    @Test
    fun `an addon-id remap does not claim a viewer edit`() {
        // The remap runs from a MANIFEST REFRESH (a dynamic addon swapped catalog
        // ids), not from the viewer arranging rails. It used to go through save(),
        // which restamped the arrangement as edited "now" and published - so a
        // device that merely refreshed add-ons outranked the sibling's real
        // move/rename, rejected it, and reverted the arrangement account-wide.
        val stamp = 111_111L
        val oldKey = KBHomeOrderPrefs.addonKeyFromManifest(
            "https://example.test/manifest.json",
            "movie",
            "top"
        )
        val newKey = KBHomeOrderPrefs.addonKeyFromManifest(
            "https://example.test/manifest.json",
            "movie",
            "top-v2"
        )
        KBHomeOrderPrefs.save(context, KBHomeOrder(order = listOf(oldKey)))
        homeOrder.edit().putLong("home_order_synced_at", stamp).commit()

        val applied = KBHomeOrderPrefs.remapAddonKeys(context, mapOf(oldKey to newKey))
        assertTrue("a rewrite that landed must report success", applied)
        assertTrue(
            "and an empty remap has nothing to write, so it cannot fail",
            KBHomeOrderPrefs.remapAddonKeys(context, emptyMap())
        )

        assertEquals(
            "the position follows the new catalog id",
            listOf(newKey),
            KBHomeOrderPrefs.get(context).order
        )
        assertEquals(
            "a refresh must not outrank the sibling's real edit",
            stamp,
            homeOrder.getLong("home_order_synced_at", 0L)
        )
    }

    @Test
    fun `a pulled rename lands without touching the arrangement's edit time`() {
        // The arrangement is one coherent thing and the newer copy owns it - but a
        // rail's NAME is one label on one row, so a name that arrives on a copy
        // which lost that race is still merged in, and never over a name this
        // device already has (see KBHomeOrderPrefs.mergeRenames).
        val stamp = 222_222L
        val key = KBHomeOrderPrefs.BUILTIN_TOP_PICKS_FOR_YOU
        KBHomeOrderPrefs.save(context, KBHomeOrder(order = listOf(key)))
        val localBlob = homeOrder.getString("home_order_json", null).orEmpty()
        KBHomeOrderPrefs.save(
            context,
            KBHomeOrder(order = listOf(key), renames = mapOf(key to "Because you watched"))
        )
        val remoteBlob = homeOrder.getString("home_order_json", null).orEmpty()
        homeOrder.edit()
            .putString("home_order_json", localBlob)
            .putLong("home_order_synced_at", stamp)
            .commit()

        assertTrue(KBHomeOrderPrefs.mergeRenames(context, remoteBlob))

        assertEquals(
            "the pulled name is what Home draws",
            "Because you watched",
            KBHomeOrderPrefs.get(context).renames[key]
        )
        assertEquals(
            "merged, not adopted: the local edit time is untouched",
            stamp,
            homeOrder.getLong("home_order_synced_at", 0L)
        )
        assertFalse(
            "a name already stored here is never overwritten",
            KBHomeOrderPrefs.mergeRenames(context, remoteBlob)
        )
    }

    /**
     * A viewer edit stamps only the rail it acted on.
     *
     * A move shifts its neighbours' indices as a side effect; they were not
     * edited, and stamping them would make them beat a sibling's real decision
     * about those rails on the next pull.
     */
    @Test
    fun `save stamps only the rail the edit placed`() {
        KBHomeOrderPrefs.save(
            context,
            KBHomeOrder(order = listOf("X", "Y", "Z"), editedAt = mapOf("X" to 111L)),
            setOf("Y")
        )

        val stamps = KBHomeOrderPrefs.get(context).editedAt
        assertEquals("an untouched rail keeps its own time", 111L, stamps["X"])
        assertTrue("the acted-on rail carries this edit's time", (stamps["Y"] ?: 0L) > 111L)
        assertEquals(
            "and the rail whose index merely shifted is not stamped",
            setOf("X", "Y"),
            stamps.keys
        )
    }

    /**
     * A blob written before the per-key map existed must still parse.
     *
     * The map is an optional field with a default, so a build that predates it
     * ignores it and an old blob reads with no entries - the merge then treats
     * every rail it places as placed at the blob's own stamp, which is the best
     * per-key time such a blob can offer (see `mergeHomeOrderAdopted`).
     */
    @Test
    fun `a blob written before the per-key map parses with its arrangement intact`() {
        val oldShape =
            """{"order":["A","B"],"pinned":["kb:p"],"hidden":["builtin:top"],"renames":{"A":"Alpha"}}"""

        val parsed = KBHomeOrderPrefs.parseBlob(oldShape)

        assertEquals(listOf("A", "B"), parsed?.order)
        assertEquals(listOf("kb:p"), parsed?.pinned)
        assertEquals(listOf("builtin:top"), parsed?.hidden)
        assertEquals(mapOf("A" to "Alpha"), parsed?.renames)
        assertTrue("no map, not a broken one", parsed?.editedAt?.isEmpty() == true)
    }

    /**
     * An old build re-encoding the blob drops the per-key map and keeps the
     * arrangement, which is why the merge must degrade to the blob stamp rather
     * than to corruption.
     */
    @Test
    fun `an old build's re-encode loses the map without losing the arrangement`() {
        val original = KBHomeOrder(
            order = listOf("A", "B"),
            hidden = listOf("C"),
            renames = mapOf("A" to "Alpha"),
            editedAt = mapOf("A" to 1_700_000_000_000L)
        )
        // What a build that predates the map writes back: the four fields it
        // knows, decoded from the same arrangement.
        val oldBuildReEncode =
            """{"order":["A","B"],"pinned":[],"hidden":["C"],"renames":{"A":"Alpha"}}"""

        val reread = KBHomeOrderPrefs.parseBlob(oldBuildReEncode)

        assertEquals(original.order, reread?.order)
        assertEquals(original.pinned, reread?.pinned)
        assertEquals(original.hidden, reread?.hidden)
        assertEquals(original.renames, reread?.renames)
        assertTrue("the per-key times are gone", reread?.editedAt?.isEmpty() == true)
    }

    /**
     * A manifest refresh that renames a catalog's key carries that rail's
     * placement time to the new key - otherwise the next pull would see the new
     * key as never placed here and could move a rail the viewer arranged. The
     * write still must not claim an edit (the check above).
     */
    @Test
    fun `an addon-id remap carries the rail's placement time to the new key`() {
        val oldKey = KBHomeOrderPrefs.addonKeyFromManifest(
            "https://example.test/manifest.json",
            "movie",
            "top"
        )
        val newKey = KBHomeOrderPrefs.addonKeyFromManifest(
            "https://example.test/manifest.json",
            "movie",
            "top-v2"
        )
        KBHomeOrderPrefs.save(
            context,
            KBHomeOrder(order = listOf(oldKey), editedAt = mapOf(oldKey to 777_000L))
        )

        assertTrue(KBHomeOrderPrefs.remapAddonKeys(context, mapOf(oldKey to newKey)))

        assertEquals(
            "the placement time follows the key",
            mapOf(newKey to 777_000L),
            KBHomeOrderPrefs.get(context).editedAt
        )
    }

    @Test
    fun `a readable store still writes normally`() {
        // The guard must not be a permanent lock: a clean store is a store.
        val after = BrowseHomeShortcuts.add(context, BrowseHomeShortcut("genres", 35, "Comedy"))

        assertEquals(listOf("Comedy"), after.map { it.name })
        assertEquals(after, BrowseHomeShortcuts.list(context))
        assertFalse(BrowseHomeShortcuts.isBlobUnreadable(context))
    }
}
