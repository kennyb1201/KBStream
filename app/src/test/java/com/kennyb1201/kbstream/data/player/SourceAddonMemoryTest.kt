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
 * Reported problem (the other half): a source that opens and then cannot keep
 * up was demoted for the session and forgotten, so the next episode started
 * from the same slow source again.
 *
 * Pinned here: the record is PER TITLE, the last outcome for an addon wins, a
 * stall is its own third state (neither working nor failed), two of them make
 * the addon slow while one does not, a success clears the count, a blank or
 * unresolved addon is never recorded, an entry expires, and the store stays
 * bounded.
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

    @Test
    fun `one stall-downshift is not yet a pattern`() {
        SourceAddonMemory.rememberStalled(context, "tt1234", "AIOStreams")

        val outcomes = SourceAddonMemory.outcomes(context, "tt1234")

        assertTrue(
            "a single downshift is a bad evening: the addon has to stay in" +
                " the unknown tier, where the ranker put it",
            outcomes.isEmpty
        )
    }

    @Test
    fun `two stall-downshifts make the addon slow for this title`() {
        SourceAddonMemory.rememberStalled(context, "tt1234", "AIOStreams")
        SourceAddonMemory.rememberStalled(context, "tt1234", "AIOStreams")

        assertEquals(
            setOf("aiostreams"),
            SourceAddonMemory.outcomes(context, "tt1234").slow
        )
    }

    @Test
    fun `a stalling addon is neither working nor failed`() {
        // The heart of the third state: a source that opens and cannot keep up
        // still PLAYS, so recording it as a failure would send a playable addon
        // to the back of the list - and recording it as working would hide the
        // stalls the feature exists to remember.
        repeat(2) { SourceAddonMemory.rememberStalled(context, "tt1234", "AIOStreams") }

        val outcomes = SourceAddonMemory.outcomes(context, "tt1234")

        assertTrue("a slow addon is not a dead one", outcomes.failed.isEmpty())
        assertTrue(outcomes.worked.isEmpty())
    }

    @Test
    fun `a success clears the stall count`() {
        repeat(2) { SourceAddonMemory.rememberStalled(context, "tt1234", "AIOStreams") }

        SourceAddonMemory.rememberWorked(context, "tt1234", "AIOStreams")

        val outcomes = SourceAddonMemory.outcomes(context, "tt1234")
        assertEquals(setOf("aiostreams"), outcomes.worked)
        assertTrue("one good session forgives", outcomes.slow.isEmpty())
    }

    @Test
    fun `a failure supersedes the stall count`() {
        repeat(2) { SourceAddonMemory.rememberStalled(context, "tt1234", "AIOStreams") }

        SourceAddonMemory.rememberFailed(context, "tt1234", "AIOStreams")

        val outcomes = SourceAddonMemory.outcomes(context, "tt1234")
        assertEquals(setOf("aiostreams"), outcomes.failed)
        assertTrue(outcomes.slow.isEmpty())
    }

    @Test
    fun `a stall count from more than a month ago does not count`() {
        seedStalls(
            showKey = "tt1234",
            addon = "aiostreams",
            stalls = 3,
            lastStallAtMs = System.currentTimeMillis() - SourceAddonMemory.TTL_MS - 1_000L
        )

        assertTrue(
            "the source has had a month of chances since: a stale stall must" +
                " not keep demoting it",
            SourceAddonMemory.outcomes(context, "tt1234").slow.isEmpty()
        )
    }

    @Test
    fun `a stall inside the window counts`() {
        seedStalls(
            showKey = "tt1234",
            addon = "aiostreams",
            stalls = 2,
            lastStallAtMs = System.currentTimeMillis() - 1_000L
        )

        assertEquals(
            setOf("aiostreams"),
            SourceAddonMemory.outcomes(context, "tt1234").slow
        )
    }

    /**
     * Writes a stall record straight into the store so its timestamp can be
     * forged - the one thing the public API cannot express, because
     * `rememberStalled` always stamps "now". The blob uses the store's own
     * field names.
     */
    private fun seedStalls(showKey: String, addon: String, stalls: Int, lastStallAtMs: Long) {
        val blob =
            """{"shows":{"$showKey":{"addons":{"$addon":{"worked":null,""" +
                """"atMs":$lastStallAtMs,"stalls":$stalls,""" +
                """"lastStallAtMs":$lastStallAtMs}},"atMs":$lastStallAtMs}}}"""
        prefs().edit().putString("source_addons_v1", blob).commit()
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
