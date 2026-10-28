package com.kennyb1201.kbstream.data.player

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The binge identity a show was last played with (audit S-F5).
 *
 * Every CONTINUATION already carried `bingeGroup`/addonName; a COLD start had
 * nowhere to get them, so a binge that had held one provider for ten episodes
 * jumped to another provider's file on the first Play pressed from Detail after
 * a restart. Three properties make the stored copy safe, checked here against
 * real preferences:
 *
 *  - it is per SHOW and carries both halves (the group a tagging provider
 *    continues with, the addon for one that stopped tagging);
 *  - a show nothing was ever stored for answers null, and so does a blank key;
 *  - an entry ages out ([BingeGroupMemory.TTL_MS]) rather than reordering a
 *    fresh list from a stale catalogue forever.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = BingeGroupMemoryTest.NoopApplication::class)
class BingeGroupMemoryTest {

    class NoopApplication : Application()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val prefs
        get() = context.getSharedPreferences(
            ProfileStorage.prefsName(context, BASE),
            Context.MODE_PRIVATE
        )

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
    }

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
    }

    @Test
    fun `a remembered identity comes back for the show it was played with`() {
        BingeGroupMemory.remember(context, SHOW, "torrentio|4k", "AIOStreams")

        val identity = BingeGroupMemory.recall(context, SHOW)
        assertEquals("torrentio|4k", identity?.bingeGroup)
        assertEquals("AIOStreams", identity?.addonName)
        assertNull("and it belongs to that show only", BingeGroupMemory.recall(context, OTHER_SHOW))
    }

    @Test
    fun `either half alone is worth remembering`() {
        BingeGroupMemory.remember(context, SHOW, null, "AIOStreams")
        assertEquals(
            "a provider that stopped tagging still has an addon to continue with",
            "AIOStreams",
            BingeGroupMemory.recall(context, SHOW)?.addonName
        )

        BingeGroupMemory.remember(context, OTHER_SHOW, "torrentio|4k", null)
        assertNull(BingeGroupMemory.recall(context, OTHER_SHOW)?.addonName)
        assertEquals("torrentio|4k", BingeGroupMemory.recall(context, OTHER_SHOW)?.bingeGroup)
    }

    @Test
    fun `nothing to say writes nothing`() {
        BingeGroupMemory.remember(context, SHOW, null, null)
        assertNull(
            "a stream with no group and no addon is not an identity, so nothing is stored",
            prefs.getString(BLOB, null)
        )
        assertNull(BingeGroupMemory.recall(context, SHOW))

        BingeGroupMemory.remember(context, "", "torrentio|4k", "AIOStreams")
        assertNull(
            "a blank show key has nothing to key on",
            prefs.getString(BLOB, null)
        )
    }

    @Test
    fun `a blank on-disk entry reads as no identity`() {
        BingeGroupMemory.remember(context, SHOW, "   ", "   ")
        assertNull(BingeGroupMemory.recall(context, SHOW))
    }

    @Test
    fun `a stale entry is not applied`() {
        // Written directly, one millisecond past the TTL: a group from a
        // provider's catalogue a fortnight ago describes files that may not be
        // there any more, and the plain rank order is the safer answer.
        val stale = (System.currentTimeMillis() - BingeGroupMemory.TTL_MS - 1L).toString()
        prefs.edit()
            .putString(BLOB, "$SHOW\u001F$stale\u001Ftorrentio|4k\u001FAIOStreams")
            .commit()

        assertNull(BingeGroupMemory.recall(context, SHOW))
    }

    @Test
    fun `the store is bounded, so a long-lived install cannot grow it forever`() {
        repeat(60) { index ->
            BingeGroupMemory.remember(context, "show$index", "group|$index", "addon$index")
        }

        val stored = prefs.getString(BLOB, null).orEmpty()
        assertTrue(
            "a bounded store keeps at most MAX_ENTRIES lines",
            stored.lines().size <= MAX_ENTRIES
        )
    }

    private companion object {
        const val BASE = "kbstream_binge_groups"
        const val BLOB = "binge_groups_v1"

        const val SHOW = "tt1234"
        const val OTHER_SHOW = "tt5678"

        /** The store's own bound (BingeGroupMemory.MAX_ENTRIES). */
        const val MAX_ENTRIES = 40
    }
}
