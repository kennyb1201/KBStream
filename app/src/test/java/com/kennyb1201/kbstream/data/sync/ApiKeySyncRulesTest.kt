package com.kennyb1201.kbstream.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The service-credential blob's rules: the TorBox, OpenSubtitles and MDBList
 * keys.
 *
 * The reported failure: "the torbox / opensubtitle / mdblist api keys aren't
 * syncing device to device for any profile, and they should". They used to be
 * plaintext synced prefs and became device-local when they moved into the
 * encrypted store; these rules are what lets them travel again without the two
 * failures that kind of blob always has: a device that never pasted a key
 * publishing its blanks over the account's copy, and two devices pasting the
 * same key trading writes forever.
 */
class ApiKeySyncRulesTest {

    @Test
    fun `a device with no key of its own never publishes`() {
        assertFalse(ApiKeySyncRules.shouldPublish(latestLocalEditAt = 0L, cloudEditedAt = 0L))
        assertFalse(ApiKeySyncRules.shouldPublish(latestLocalEditAt = 0L, cloudEditedAt = 5L))
    }

    @Test
    fun `a device publishes only ahead of the copy it adopted`() {
        assertTrue(ApiKeySyncRules.shouldPublish(latestLocalEditAt = 6L, cloudEditedAt = 5L))
        // An adopted key is not an edit: the device that just took the account's
        // copy must not echo it straight back.
        assertFalse(ApiKeySyncRules.shouldPublish(latestLocalEditAt = 5L, cloudEditedAt = 5L))
        assertFalse(ApiKeySyncRules.shouldPublish(latestLocalEditAt = 4L, cloudEditedAt = 5L))
    }

    @Test
    fun `a key this device never touched accepts the account copy`() {
        assertTrue(ApiKeySyncRules.remoteKeyWins(remoteEditedAt = 4L, localEditedAt = 0L))
    }

    @Test
    fun `a newer edit on either device wins`() {
        assertTrue(ApiKeySyncRules.remoteKeyWins(remoteEditedAt = 6L, localEditedAt = 5L))
        assertFalse(ApiKeySyncRules.remoteKeyWins(remoteEditedAt = 4L, localEditedAt = 5L))
    }

    @Test
    fun `identical edits converge instead of trading writes forever`() {
        // Both devices pasted the same key (or one adopted the other's): with
        // "at least as new" both sides settle on the same value and stamp, so the
        // next pull is a no-op rather than a write each way.
        assertTrue(ApiKeySyncRules.remoteKeyWins(remoteEditedAt = 5L, localEditedAt = 5L))
    }

    @Test
    fun `the blob stamp is the newest key edit, or zero for none`() {
        assertEquals(9L, ApiKeySyncRules.latestStamp(listOf(3L, 9L, 4L)))
        assertEquals(0L, ApiKeySyncRules.latestStamp(emptyList()))
        assertEquals(0L, ApiKeySyncRules.latestStamp(listOf(0L, 0L)))
    }

    @Test
    fun `a clear is an edit, so it still travels`() {
        // The user blanked the key on this device: the stamp IS the clear, and it
        // must beat the account copy so the other devices stop using the key -
        // which is exactly what a rule keyed on the value alone could not tell
        // from a device that never had one.
        assertTrue(ApiKeySyncRules.shouldPublish(latestLocalEditAt = 6L, cloudEditedAt = 5L))
        assertTrue(ApiKeySyncRules.remoteKeyWins(remoteEditedAt = 6L, localEditedAt = 5L))
    }
}
