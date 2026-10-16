package com.kennyb1201.kbstream.data.sync

import org.junit.Assert.assertEquals
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

    // ── the per-key merge ──────────────────────────────────────────────

    private val t1 = "movie::tt0111161"
    private val t2 = "movie::tt0068646"

    private fun merge(
        incoming: Set<String> = emptySet(),
        incomingStamps: Map<String, Long> = emptyMap(),
        incomingRemoved: Map<String, Long> = emptyMap(),
        localKeys: Set<String> = emptySet(),
        localStamps: Map<String, Long> = emptyMap()
    ) = WatchedOverridesRules.merge(
        incoming = incoming,
        incomingStamps = incomingStamps,
        incomingRemoved = incomingRemoved,
        localKeys = localKeys,
        localStamps = localStamps
    )

    @Test
    fun `a mark made here survives a sibling's later mark of another title`() {
        // The reported shape: one TV hand-marks one title, the other TV hand-
        // marks a different one, and the newer blob replaced the whole set - so
        // each device's mark erased the other's and both kept losing.
        val result = merge(
            incoming = setOf(t2),
            incomingStamps = mapOf(t2 to 200L),
            localKeys = setOf(t1),
            localStamps = mapOf(t1 to 100L)
        )

        assertEquals(setOf(t2), result.added)
        assertTrue("nothing is removed by a sibling that simply never heard of it", result.removed.isEmpty())
        assertEquals(mapOf(t2 to 200L), result.stamps)
    }

    @Test
    fun `an unmark outranks an older mark of the same title`() {
        val result = merge(
            incomingRemoved = mapOf(t1 to 150L),
            localKeys = setOf(t1),
            localStamps = mapOf(t1 to 100L)
        )

        assertEquals(setOf(t1), result.removed)
        assertEquals(mapOf(t1 to 150L), result.stamps)
    }

    @Test
    fun `a stale unmark cannot take out a newer local mark`() {
        val result = merge(
            incomingRemoved = mapOf(t1 to 200L),
            localKeys = setOf(t1),
            localStamps = mapOf(t1 to 300L)
        )

        assertTrue("the newer local mark is the one that stands", result.isEmpty)
    }

    @Test
    fun `an unmark is remembered even when the title is not held here`() {
        // The unmark arrived before this device ever saw the mark, or the mark
        // was already gone. Either way the stamp has to be kept, or a blob that
        // still carries the older mark puts the title back.
        val first = merge(incomingRemoved = mapOf(t1 to 400L))
        assertEquals(mapOf(t1 to 400L), first.stamps)
        assertTrue(first.removed.isEmpty())

        val later = merge(
            incoming = setOf(t1),
            incomingStamps = mapOf(t1 to 300L),
            localStamps = first.stamps
        )
        assertTrue("the older mark must not come back", later.added.isEmpty())
    }

    @Test
    fun `a key this device never touched is adopted from either side`() {
        val added = merge(incoming = setOf(t1), incomingStamps = mapOf(t1 to 10L))
        assertEquals(setOf(t1), added.added)
        assertEquals(mapOf(t1 to 10L), added.stamps)

        // A blob from a build that predates the per-key stamps: the mark still
        // arrives, it just carries no time of its own.
        val legacy = merge(incoming = setOf(t2))
        assertEquals(setOf(t2), legacy.added)
        assertEquals(mapOf(t2 to 0L), legacy.stamps)
    }

    @Test
    fun `opinions keep the newer of a mark and an unmark of one key`() {
        assertEquals(
            mapOf(t1 to 900L),
            WatchedOverridesRules.opinions(mapOf(t1 to 400L), mapOf(t1 to 900L))
        )
        assertEquals(
            mapOf(t1 to 900L),
            WatchedOverridesRules.opinions(mapOf(t1 to 900L), mapOf(t1 to 400L))
        )
    }

    @Test
    fun `stamp maps survive a round trip and junk reads as no opinion`() {
        val stamps = mapOf(t1 to 1_700_000_000_000L, t2 to 42L)
        assertEquals(stamps, WatchedOverridesRules.decodeStamps(WatchedOverridesRules.encodeStamps(stamps)))
        assertTrue(WatchedOverridesRules.decodeStamps(null).isEmpty())
        assertTrue(WatchedOverridesRules.decodeStamps("not json").isEmpty())
    }

    @Test
    fun `a device that only unmarked still has something to publish`() {
        assertTrue(WatchedOverridesRules.shouldPublish(emptySet(), setOf(t1)))
        assertFalse(WatchedOverridesRules.shouldPublish(emptySet(), emptySet()))
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
