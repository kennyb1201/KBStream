package com.kennyb1201.kbstream.data.download

import com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §8's isolation rules, asserted rather than promised.
 *
 * Downloads are device state: they never travel to the account, to another phone
 * or to a television, and the two switches that govern them do not follow the
 * profile either. Two mechanisms hold that, and both are cheap to break by
 * accident:
 *
 *  1. the sync payload is an ALLOW-LIST of preference key names, so a download
 *     key can never leave the device by being forgotten - it can only leave by
 *     being ADDED to that list. This class fails if one ever is;
 *  2. a profile EXPORT is not an allow-list: BackupManager copies every key of
 *     the player-prefs file. So the download records and switches deliberately do
 *     not live in that file at all, and the last test pins that - the file name
 *     here must not be the one a backup carries.
 */
class DownloadsSyncIsolationTest {

    @Test
    fun `no download preference is on the sync allow-list`() {
        val synced = PrefsPayloadBuilder.SYNCED_PREF_KEYS
        listOf(
            DownloadsStore.KEY_RECORDS,
            DownloadsStore.KEY_DOWNLOAD_OVER_CELLULAR,
            DownloadsStore.KEY_DELETE_AFTER_FINISH
        ).forEach { key ->
            assertFalse(
                "$key must never sync: a download is device state (spec §8)",
                key in synced
            )
        }
    }

    @Test
    fun `the download store is not a file a profile export carries`() {
        // BackupManager exports the player-prefs file key by key with no
        // allow-list, so a download toggle stored there would ride every backup
        // silently. Its own file is the structural half of that rule.
        assertNotEquals(
            "the downloads' store must not be the file a profile backup copies",
            "kbstream_player_prefs",
            DownloadsStore.PREFS_NAME
        )
        assertTrue(DownloadsStore.PREFS_NAME.isNotBlank())
    }

    @Test
    fun `the download keys are not player-prefs keys either`() {
        // A second lock on the same door: the same three names must not collide
        // with keys that live in the exported file, because a collision would
        // make the store's own reads pick up an exported value.
        listOf(
            DownloadsStore.KEY_RECORDS,
            DownloadsStore.KEY_DOWNLOAD_OVER_CELLULAR,
            DownloadsStore.KEY_DELETE_AFTER_FINISH
        ).forEach { key ->
            assertFalse(
                "$key looks like a player-prefs key and could collide with one",
                key.startsWith("default_") || key in PrefsPayloadBuilder.EXCLUDED_PREF_KEYS
            )
        }
    }
}
