package com.kennyb1201.kbstream.data.player

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

/**
 * What one title remembers about its addons.
 *
 * Reported problem: a show whose every AIOStreams link was dead played fine
 * from another addon, and nothing carried that to the next episode - the same
 * unplayable links came back at the head of the list.
 *
 * Pinned here: the record is PER TITLE, the last outcome for an addon wins, a
 * blank or unresolved addon is never recorded, an entry expires, and the store
 * stays bounded.
 */
@RunWith(AndroidJUnit4::class)
class SourceAddonMemoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun clearStore() {
        prefs().edit().clear().commit()
    }

    private fun prefs() =
        context.getSharedPreferences(
            ProfileStorage.prefsName(context, "kbstream_source_addons"),
            Context.MODE_PRIVATE
        )

    @Test
    fun `a failed addon and a working one are both remembered for the title`() {
        SourceAddonMemory.rememberFailed(context, "tt1234", "AIOStreams")
        SourceAddonMemory.rememberWorked(context, "tt1234", "FlixStreams")

        val outcomes = SourceAddonMemory.outcomes(context, "tt1234")

        assertEquals(setOf("flixstreams"), outcomes.worked)
        assertEquals(setOf("aiostreams"), outcomes.failed)
    }

    @Test
    fun `the episode's id resolves to the same key the player records under`() {
        // The player knows the show as parentId ("tt1234"); the source fetch
        // knows it only as the stream id it was handed.
        SourceAddonMemory.rememberFailed(context, "tt1234", "AIOStreams")

        val fromStreamId = SourceAddonMemory.outcomes(context, "tt1234:4:11")

        assertEquals(setOf("aiostreams"), fromStreamId.failed)
    }

    @Test
    fun `one show's trouble is not another show's trouble`() {
        SourceAddonMemory.rememberFailed(context, "tt1234", "AIOStreams")

        val other = SourceAddonMemory.outcomes(context, "tt9999")

        assertTrue("a different show knows nothing", other.isEmpty)
    }

    @Test
    fun `the last outcome for an addon wins`() {
        // It failed once, then played: the newer fact is what counts, or a
        // recovered addon would be demoted forever.
        SourceAddonMemory.rememberFailed(context, "tt1234", "AIOStreams")
        SourceAddonMemory.rememberWorked(context, "tt1234", "AIOStreams")

        val outcomes = SourceAddonMemory.outcomes(context, "tt1234")

        assertEquals(setOf("aiostreams"), outcomes.worked)
        assertTrue(outcomes.failed.isEmpty())
    }

    @Test
    fun `a blank show or an unresolved addon records nothing`() {
        SourceAddonMemory.rememberFailed(context, "", "AIOStreams")
        SourceAddonMemory.rememberWorked(context, "tt1234", null)
        SourceAddonMemory.rememberWorked(context, "tt1234", "   ")

        assertTrue(SourceAddonMemory.outcomes(context, "tt1234").isEmpty)
        assertTrue(SourceAddonMemory.outcomes(context, "tt9999").isEmpty)
    }

    @Test
    fun `an expired outcome is dropped`() {
        seed(
            showKey = "tt1234",
            addon = "aiostreams",
            worked = false,
            atMs = System.currentTimeMillis() - SourceAddonMemory.TTL_MS - 1_000L
        )

        assertTrue(
            "a month-old verdict must not keep demoting an addon",
            SourceAddonMemory.outcomes(context, "tt1234").isEmpty
        )
    }

    @Test
    fun `an outcome inside the window still counts`() {
        seed(
            showKey = "tt1234",
            addon = "aiostreams",
            worked = false,
            atMs = System.currentTimeMillis() - 1_000L
        )

        assertEquals(
            setOf("aiostreams"),
            SourceAddonMemory.outcomes(context, "tt1234").failed
        )
    }

    @Test
    fun `forget drops only the named title`() {
        SourceAddonMemory.rememberFailed(context, "tt1234", "AIOStreams")
        SourceAddonMemory.rememberFailed(context, "tt9999", "AIOStreams")

        SourceAddonMemory.forget(context, "tt1234")

        assertTrue(SourceAddonMemory.outcomes(context, "tt1234").isEmpty)
        assertFalse(SourceAddonMemory.outcomes(context, "tt9999").isEmpty)
    }

    @Test
    fun `a title keeps a bounded number of addons`() {
        repeat(15) { index ->
            SourceAddonMemory.rememberFailed(context, "tt1234", "Addon $index")
        }

        val outcomes = SourceAddonMemory.outcomes(context, "tt1234")

        assertEquals(12, outcomes.failed.size)
        assertTrue("the oldest must be the one dropped", "addon0" !in outcomes.failed)
        assertTrue("the newest must survive", "addon14" in outcomes.failed)
    }

    /**
     * Writes an entry straight into the store so its timestamp can be forged -
     * the one thing the public API cannot express, because `record` always
     * stamps "now". The blob uses the store's own field names.
     */
    private fun seed(showKey: String, addon: String, worked: Boolean, atMs: Long) {
        val blob =
            """{"shows":{"$showKey":{"addons":{"$addon":{"worked":$worked,""" +
                """"atMs":$atMs}},"atMs":$atMs}}}"""
        prefs().edit().putString("source_addons_v1", blob).commit()
    }
}
