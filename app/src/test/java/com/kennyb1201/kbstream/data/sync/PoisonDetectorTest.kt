package com.kennyb1201.kbstream.data.sync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Poison detection for the one-time sweep.
 *
 * The only rows the sweep may delete are the same local write duplicated into
 * a second profile scope (the mid-switch race). The dangerous failure mode is
 * the opposite one: deleting a profile's REAL watched state or resume
 * position. These tests pin both directions.
 */
class PoisonDetectorTest {

    private val pidA = "aaaa"
    private val pidB = "bbbb"
    private val pidC = "cccc"
    private val order = listOf(pidA, pidB, pidC)
    private val local = setOf(pidA, pidB, pidC)

    private fun payload(updatedAt: Long, extra: String? = null): JsonObject =
        buildJsonObject {
            put("updatedAt", updatedAt)
            extra?.let { put("name", it) }
        }

    private fun row(key: String, pid: String, payload: JsonObject) =
        PoisonDetector.Row("p:$pid:$key", payload)

    // ── same-write fingerprint ──────────────────────────────────────

    @Test
    fun `identical payloads are one write`() {
        assertTrue(PoisonDetector.sameWrite(payload(1000L), payload(1000L)))
    }

    @Test
    fun `identical timestamps count even when other fields differ`() {
        // Older/newer app versions round-trip different field sets; the
        // timestamp is what a real second playback cannot accidentally share.
        assertTrue(PoisonDetector.sameWrite(payload(1000L, "ep"), payload(1000L)))
    }

    @Test
    fun `different timestamps are different sessions`() {
        assertFalse(PoisonDetector.sameWrite(payload(1000L), payload(2000L)))
        assertFalse(PoisonDetector.sameWrite(payload(1000L, "a"), payload(1000L + 1, "b")))
    }

    @Test
    fun `a missing timestamp cannot match on timestamp alone`() {
        val noStamp = buildJsonObject { put("name", "x") }
        assertFalse(PoisonDetector.sameWrite(noStamp, payload(1000L)))
        // Identical content still counts as one write, timestamp or not.
        assertTrue(PoisonDetector.sameWrite(noStamp, buildJsonObject { put("name", "x") }))
    }

    // ── cross-scope duplicates ──────────────────────────────────────

    @Test
    fun `duplicate under a newer profile is deleted, the owner kept`() {
        val rows = listOf(
            row("movie::tt1", pidA, payload(1000L)),
            row("movie::tt1", pidB, payload(1000L))
        )
        assertEquals(listOf("p:$pidB:movie::tt1"), PoisonDetector.crossScopeDuplicates(rows, order, local))
    }

    @Test
    fun `a real second watch on another profile is never deleted`() {
        val rows = listOf(
            row("movie::tt1", pidA, payload(1000L)),
            row("movie::tt1", pidB, payload(9_000_000L))
        )
        assertTrue(PoisonDetector.crossScopeDuplicates(rows, order, local).isEmpty())
    }

    @Test
    fun `the same item in three scopes keeps only the oldest`() {
        val rows = listOf(
            row("show::tt9", pidC, payload(500L)),
            row("show::tt9", pidA, payload(500L)),
            row("show::tt9", pidB, payload(500L))
        )
        val doomed = PoisonDetector.crossScopeDuplicates(rows, order, local)
        assertEquals(
            setOf("p:$pidB:show::tt9", "p:$pidC:show::tt9"),
            doomed.toSet()
        )
    }

    @Test
    fun `unrelated keys are left alone`() {
        val rows = listOf(
            row("movie::tt1", pidA, payload(1000L)),
            row("movie::tt2", pidB, payload(2000L))
        )
        assertTrue(PoisonDetector.crossScopeDuplicates(rows, order, local).isEmpty())
    }

    @Test
    fun `legacy unscoped rows are never touched`() {
        val rows = listOf(
            PoisonDetector.Row("movie::tt1", payload(1000L)),
            row("movie::tt1", pidB, payload(1000L))
        )
        assertTrue(PoisonDetector.crossScopeDuplicates(rows, order, local).isEmpty())
    }

    @Test
    fun `rows of a profile that only exists on another device are safe`() {
        val remoteOnly = setOf(pidA)
        val rows = listOf(
            row("movie::tt1", pidA, payload(1000L)),
            row("movie::tt1", pidB, payload(1000L))
        )
        assertTrue(PoisonDetector.crossScopeDuplicates(rows, order, remoteOnly).isEmpty())
    }

    // ── watched-override sets ───────────────────────────────────────

    @Test
    fun `an override set copied from an older profile is cleared`() {
        val sets = listOf(
            pidA to setOf("movie::tt1", "show::tt2"),
            pidB to setOf("movie::tt1", "show::tt2")
        )
        assertEquals(listOf(pidB), PoisonDetector.duplicateOverrideOwners(sets))
    }

    @Test
    fun `genuinely different override sets are kept`() {
        val sets = listOf(
            pidA to setOf("movie::tt1"),
            pidB to setOf("movie::tt2")
        )
        assertTrue(PoisonDetector.duplicateOverrideOwners(sets).isEmpty())
    }

    @Test
    fun `empty sets are never treated as copies of each other`() {
        val sets = listOf(pidA to emptySet<String>(), pidB to emptySet<String>())
        assertTrue(PoisonDetector.duplicateOverrideOwners(sets).isEmpty())
    }

    @Test
    fun `a superset is not a copy`() {
        val sets = listOf(
            pidA to setOf("movie::tt1"),
            pidB to setOf("movie::tt1", "movie::tt2")
        )
        assertTrue(PoisonDetector.duplicateOverrideOwners(sets).isEmpty())
    }

    @Test
    fun `every copy of an earlier set is cleared, the first one kept`() {
        val same = setOf("movie::tt1")
        val sets = listOf(pidA to same, pidB to same, pidC to same)
        assertEquals(listOf(pidB, pidC), PoisonDetector.duplicateOverrideOwners(sets))
    }
}
