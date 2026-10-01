package com.kennyb1201.kbstream.data.sync

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
}
