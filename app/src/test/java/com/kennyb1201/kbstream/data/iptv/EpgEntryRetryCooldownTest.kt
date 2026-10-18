package com.kennyb1201.kbstream.data.iptv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How long the guide's entry auto-import stays quiet after a stall failure.
 *
 * The stall itself is only half the field report: the wedged run never wrote the
 * freshness marker, so every later entry to Live TV saw a stale guide and
 * started another import, which wedged again - "the guide seems to reimport a
 * lot". A cooldown is what makes the failed run cost a bounded number of
 * attempts instead of one per entry.
 */
class EpgEntryRetryCooldownTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `a source that never failed is not cooling down`() {
        assertFalse(epgEntryImportCoolingDown(lastStallAtMs = 0L, nowMs = now))
    }

    @Test
    fun `entry stays quiet inside the window`() {
        assertTrue(
            epgEntryImportCoolingDown(
                lastStallAtMs = now - 60_000L,
                nowMs = now
            )
        )
        assertTrue(
            "one millisecond before the window closes it still holds",
            epgEntryImportCoolingDown(
                lastStallAtMs = now - EPG_ENTRY_RETRY_COOLDOWN_MS + 1,
                nowMs = now
            )
        )
    }

    @Test
    fun `entry retries once the window has closed`() {
        assertFalse(
            "at exactly the cooldown the next entry may try again",
            epgEntryImportCoolingDown(
                lastStallAtMs = now - EPG_ENTRY_RETRY_COOLDOWN_MS,
                nowMs = now
            )
        )
        assertFalse(
            epgEntryImportCoolingDown(
                lastStallAtMs = now - EPG_ENTRY_RETRY_COOLDOWN_MS * 2,
                nowMs = now
            )
        )
    }

    @Test
    fun `a clock set back does not disarm the cooldown`() {
        // A TV box that just synced its clock can hand a timestamp in the
        // future; reading that as "long ago" would let the entry hammer the
        // provider the cooldown exists to spare.
        assertTrue(
            epgEntryImportCoolingDown(
                lastStallAtMs = now + 60_000L,
                nowMs = now
            )
        )
    }
}
