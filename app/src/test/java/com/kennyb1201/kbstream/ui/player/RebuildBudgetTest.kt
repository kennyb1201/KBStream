package com.kennyb1201.kbstream.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rebuild cap: one failure cause gets a bounded number of rebuilds per
 * session.
 *
 * The field session this pins spent nine rebuilds on one 4K file — the retry
 * ladder re-asking six times, the source ladder switching on top of it, the
 * Dolby Vision strip rebuilding in between — while every one of them asked the
 * same wedged decoder pool for a decoder it could not hand out. Two rebuilds
 * answer a genuinely transient failure; past that the ladder is re-asking the
 * same question of the same component. The numbers here are the spec's.
 */
class RebuildBudgetTest {

    @Test
    fun `the cap is two rebuilds per cause`() {
        assertEquals(2, MAX_REBUILDS_PER_CAUSE)
    }

    @Test
    fun `the same cause thrice stops at two rebuilds`() {
        val budget = RebuildBudget()
        assertTrue("the first rebuild is allowed", budget.allows("decoder-resources"))
        budget.record("decoder-resources")
        assertTrue("the second is still worth trying", budget.allows("decoder-resources"))
        budget.record("decoder-resources")
        assertFalse(
            "the third asks the same component the same question",
            budget.allows("decoder-resources")
        )
        assertEquals(2, budget.spentFor("decoder-resources"))
    }

    @Test
    fun `recording past the cap cannot re-open it`() {
        val budget = RebuildBudget()
        repeat(5) { budget.record("decoder") }
        assertFalse(budget.allows("decoder"))
        assertEquals(5, budget.spentFor("decoder"))
    }

    @Test
    fun `a different cause keeps its own budget`() {
        // A session can hit two unrelated problems: an unopenable source, then
        // a decoder failure. Each gets the full allowance, because they are
        // different questions.
        val budget = RebuildBudget()
        repeat(MAX_REBUILDS_PER_CAUSE) { budget.record("unopenable") }
        assertFalse(budget.allows("unopenable"))
        assertTrue(budget.allows("decoder-missing"))
        assertEquals(0, budget.spentFor("decoder-missing"))
    }

    @Test
    fun `a cause never recorded has spent nothing`() {
        val budget = RebuildBudget()
        assertEquals(0, budget.spentFor("container"))
        assertTrue(budget.allows("container"))
    }

    @Test
    fun `a fresh session starts clean`() {
        val budget = RebuildBudget()
        repeat(MAX_REBUILDS_PER_CAUSE) { budget.record("decoder") }
        budget.reset()
        assertTrue(
            "a new player session must not inherit the previous session's spent ladder",
            budget.allows("decoder")
        )
        assertEquals(0, budget.spentFor("decoder"))
    }

    @Test
    fun `the rule itself reads as first two allowed, third not`() {
        assertTrue(rebuildAllowed(0))
        assertTrue(rebuildAllowed(1))
        assertFalse(rebuildAllowed(MAX_REBUILDS_PER_CAUSE))
        assertFalse(rebuildAllowed(9))
    }

    @Test
    fun `no session can reach the nine-rebuild spiral again`() {
        // The ladder's backoffs and the source ladder are unchanged; the cap is
        // what makes the nine-rebuild session unreproducible. Two rebuilds per
        // cause cannot add up to nine.
        val budget = RebuildBudget()
        var rebuilds = 0
        repeat(12) {
            if (budget.allows("decoder-resources")) {
                rebuilds++
                budget.record("decoder-resources")
            }
        }
        assertEquals(MAX_REBUILDS_PER_CAUSE, rebuilds)
        assertTrue("nine rebuilds must be out of reach", rebuilds < 9)
    }
}
