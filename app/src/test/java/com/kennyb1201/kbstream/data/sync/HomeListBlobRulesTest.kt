package com.kennyb1201.kbstream.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The publish/apply gate for the two full-replace Home blobs: imported
 * collections and the browse chips mirrored to Home.
 *
 * The failure this pins is the one that reads as "it doesn't sync": every
 * device runs the same periodic bulk push, so a device with an empty list
 * would publish that emptiness and erase the account's list everywhere.
 */
class HomeListBlobRulesTest {

    @Test
    fun `an empty collections list is never published`() {
        assertFalse(HomeListBlobRules.shouldPublishCollections(emptyList()))
    }

    @Test
    fun `a non-empty collections list is published`() {
        assertTrue(
            HomeListBlobRules.shouldPublishCollections(
                listOf("https://example.com/collections.json")
            )
        )
    }

    @Test
    fun `an empty browse-chip blob is never published`() {
        assertFalse(HomeListBlobRules.shouldPublishBrowseShortcuts(""))
        assertFalse(HomeListBlobRules.shouldPublishBrowseShortcuts("   "))
    }

    @Test
    fun `a browse-chip blob with content is published`() {
        assertTrue(HomeListBlobRules.shouldPublishBrowseShortcuts("""{"shortcuts":[]}"""))
    }

    @Test
    fun `a blob older than the local edit is rejected`() {
        assertFalse(HomeListBlobRules.shouldApply(remoteUpdated = 1_000L, localSyncedAt = 2_000L))
    }

    @Test
    fun `a blob at or newer than the local edit is adopted`() {
        assertTrue(HomeListBlobRules.shouldApply(remoteUpdated = 2_000L, localSyncedAt = 2_000L))
        assertTrue(HomeListBlobRules.shouldApply(remoteUpdated = 3_000L, localSyncedAt = 2_000L))
    }

    @Test
    fun `a blob with no stamp is adopted`() {
        assertTrue(HomeListBlobRules.shouldApply(remoteUpdated = null, localSyncedAt = 2_000L))
    }

    @Test
    fun `an empty home order is never published`() {
        assertFalse(HomeListBlobRules.shouldPublishHomeOrder(""))
        assertFalse(HomeListBlobRules.shouldPublishHomeOrder("   "))
    }

    @Test
    fun `an arranged home order is published`() {
        assertTrue(
            HomeListBlobRules.shouldPublishHomeOrder(
                """{"order":["browse:\u0001rail:genres"],"pinned":[],"hidden":[]}"""
            )
        )
    }

    @Test
    fun `an arranged blob publishes its own change time, not the push time`() {
        // The bug: buildAll ran on every bulk push and stamped `now`, so an
        // untouched device's copy looked newer than a sibling's real edit and
        // reverted it. The content's own change stamp must win.
        assertEquals(1_000L, HomeListBlobRules.publishStamp(contentChangedAt = 1_000L, now = 9_999L))
    }

    @Test
    fun `a blob with no change stamp falls back to the push time`() {
        // A blob written before the stamp existed still has to publish once.
        assertEquals(9_999L, HomeListBlobRules.publishStamp(contentChangedAt = 0L, now = 9_999L))
    }
}
