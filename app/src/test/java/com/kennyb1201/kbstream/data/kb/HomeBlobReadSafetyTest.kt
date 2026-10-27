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
        context.getSharedPreferences(
            ProfileStorage.prefsName(context, baseName),
            Context.MODE_PRIVATE
        )

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
    fun `a readable store still writes normally`() {
        // The guard must not be a permanent lock: a clean store is a store.
        val after = BrowseHomeShortcuts.add(context, BrowseHomeShortcut("genres", 35, "Comedy"))

        assertEquals(listOf("Comedy"), after.map { it.name })
        assertEquals(after, BrowseHomeShortcuts.list(context))
        assertFalse(BrowseHomeShortcuts.isBlobUnreadable(context))
    }
}
