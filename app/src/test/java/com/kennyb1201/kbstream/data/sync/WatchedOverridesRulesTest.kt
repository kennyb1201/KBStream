package com.kennyb1201.kbstream.data.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The publish/apply gate for the manual "Mark as Watched" override set.
 *
 * The failure this pins: the bulk push runs on every device, so a device with
 * NO manual overrides published an empty set stamped with its own push time,
 * and every sibling's hand-marked set was replaced by it a second later.
 *
 * The three rules, in the order they matter:
 *  - an empty set is never published from the bulk push (an unmark is an edit
 *    and publishes through the store's own save path),
 *  - an empty payload is only adopted when it says it means it, so a blank
 *    blob from a build that predates the gate cannot erase anything,
 *  - and a blob older than this device's own last edit or adoption is refused.
 */
class WatchedOverridesRulesTest {

    private val mark = setOf("movie::tt0111161")

    @Test
    fun `an empty override set is never published`() {
        assertFalse(WatchedOverridesRules.shouldPublish(emptySet()))
    }

    @Test
    fun `a set with one mark is published`() {
        assertTrue(WatchedOverridesRules.shouldPublish(mark))
    }

    @Test
    fun `a blank payload from a build that predates the gate is not adopted`() {
        // No `cleared` field at all: the shape an old device's bulk push has,
        // and the one that used to wipe every sibling.
        assertFalse(
            WatchedOverridesRules.shouldApply(
                remoteUpdated = 9_000L,
                localSyncedAt = 0L,
                incoming = emptySet(),
                deliberateClear = false
            )
        )
    }

    @Test
    fun `a deliberate unmark of the last title still propagates`() {
        assertTrue(
            WatchedOverridesRules.shouldApply(
                remoteUpdated = 9_000L,
                localSyncedAt = 8_000L,
                incoming = emptySet(),
                deliberateClear = true
            )
        )
    }

    @Test
    fun `a deliberate clear older than the local edit is refused`() {
        assertFalse(
            WatchedOverridesRules.shouldApply(
                remoteUpdated = 7_000L,
                localSyncedAt = 8_000L,
                incoming = emptySet(),
                deliberateClear = true
            )
        )
    }

    @Test
    fun `a non-empty blob older than the local edit is refused`() {
        assertFalse(
            WatchedOverridesRules.shouldApply(
                remoteUpdated = 1_000L,
                localSyncedAt = 2_000L,
                incoming = mark,
                deliberateClear = false
            )
        )
    }

    @Test
    fun `a non-empty blob at or newer than the local edit is adopted`() {
        assertTrue(
            WatchedOverridesRules.shouldApply(
                remoteUpdated = 2_000L,
                localSyncedAt = 2_000L,
                incoming = mark,
                deliberateClear = false
            )
        )
        assertTrue(
            WatchedOverridesRules.shouldApply(
                remoteUpdated = 3_000L,
                localSyncedAt = 2_000L,
                incoming = mark,
                deliberateClear = false
            )
        )
    }

    @Test
    fun `a non-empty blob with no stamp is adopted`() {
        // A build that predates the stamp: no staleness to compare, and the
        // content is a real one, so it is still better than dropping it.
        assertTrue(
            WatchedOverridesRules.shouldApply(
                remoteUpdated = null,
                localSyncedAt = 2_000L,
                incoming = mark,
                deliberateClear = false
            )
        )
    }
}
