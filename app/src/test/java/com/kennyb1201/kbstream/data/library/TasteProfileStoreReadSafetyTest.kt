package com.kennyb1201.kbstream.data.library

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The taste profile's corrupt-blob discipline, in the LocalLibraryStore shape.
 *
 * The profile is the one store in the app that cannot be rebuilt from anything
 * else: the signals and the history are the raw material, but the affinities
 * themselves exist only in this blob, and a blob that will not parse must not
 * read as "no taste" - the next rebuild would persist that emptiness over
 * everything the profile ever learned. The nearer file (HiddenTitles, which
 * TasteSignals copies) resets an unreadable blob to EMPTY instead; this pins the
 * stronger rule, so a later reader cannot quietly copy the wrong neighbour.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = TasteProfileStoreReadSafetyTest.NoopApplication::class)
class TasteProfileStoreReadSafetyTest {

    class NoopApplication : Application()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val prefs
        get() = context.getSharedPreferences(
            ProfileStorage.prefsName(context, "kbstream_taste_profile"),
            Context.MODE_PRIVATE
        )

    private val corrupt = """{"entries":[{"dimension":"""

    private val readable = """{"entries":[],"evidenceFingerprint":""" +
        """{"signalCount":0,"signalMaxUpdatedAt":0,"completionCount":0,"completionMaxAt":0},""" +
        """"computedAt":0,"dirty":false}"""

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
    }

    @Test
    fun `an unreadable profile blob is kept in place and cannot be written over`() {
        prefs.edit().putString("profile_v1", corrupt).commit()

        TasteProfileStore.markDirty(context)

        assertEquals(
            "a dirty mark must not replace a blob this build cannot parse",
            corrupt,
            prefs.getString("profile_v1", null)
        )
    }

    @Test
    fun `a write after a readable blob succeeds`() {
        prefs.edit().putString("profile_v1", readable).commit()

        TasteProfileStore.markDirty(context)

        val stored = prefs.getString("profile_v1", null).orEmpty()
        assertTrue("the write went through: $stored", stored.contains("\"dirty\":true"))
    }

    @Test
    fun `a profile that was never written is empty, not an error`() {
        TasteProfileStore.markDirty(context)

        val stored = prefs.getString("profile_v1", null).orEmpty()
        assertTrue("nothing to lose, so the mark is written: $stored", stored.contains("\"dirty\":true"))
    }
}
