package com.kennyb1201.kbstream.data.watched

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule that keeps Detail's watched markers whole (spec 2026-11-12).
 *
 * Field report, build 0.9.253: finishing the last aired episode of a fully
 * watched show and going straight back to Details showed only the LAST season
 * ticked; the earlier seasons came right on a later entry. Timing, not state:
 * the episode rows render from one merged key set, and a rebuild that had only
 * heard from local history (tracker legs slow, or a leg's read failing and
 * being swallowed into an empty set) replaced the complete one.
 */
class WatchedKeysRebuildTest {

    // ── F1: a partial rebuild never replaces a complete set ──────────────

    @Test
    fun `a complete rebuild always replaces`() {
        assertTrue(
            WatchedKeysRebuild.shouldReplace(
                sameTitle = true,
                holdingComplete = true,
                rebuildComplete = true
            )
        )
        assertTrue(
            "even with nothing complete held",
            WatchedKeysRebuild.shouldReplace(
                sameTitle = true,
                holdingComplete = false,
                rebuildComplete = true
            )
        )
    }

    @Test
    fun `a partial rebuild is held while a complete set is on screen`() {
        assertFalse(
            "local-only rebuild over a complete set: this is the bug",
            WatchedKeysRebuild.shouldReplace(
                sameTitle = true,
                holdingComplete = true,
                rebuildComplete = false
            )
        )
    }

    @Test
    fun `a partial rebuild still publishes when nothing complete is held`() {
        assertTrue(
            "a cold page has to draw something, and a first mark has to show",
            WatchedKeysRebuild.shouldReplace(
                sameTitle = true,
                holdingComplete = false,
                rebuildComplete = false
            )
        )
    }

    @Test
    fun `another title never inherits the held set`() {
        assertTrue(
            "the held keys belong to the previous show; they must not stand in",
            WatchedKeysRebuild.shouldReplace(
                sameTitle = false,
                holdingComplete = true,
                rebuildComplete = false
            )
        )
    }

    // ── F2: the just-watched episode survives the rebuild ────────────────

    @Test
    fun `a mark made in flight is unioned into the rebuild's snapshot`() {
        val built = setOf("tt1:1:1", "tt1:1:2")
        val justWatched = setOf("tt1:3:10")
        assertEquals(
            "the episode just finished cannot be dropped by a snapshot that predates its write",
            setOf("tt1:1:1", "tt1:1:2", "tt1:3:10"),
            WatchedKeysRebuild.merge(
                built = built,
                addedInFlight = justWatched,
                removedInFlight = emptySet()
            )
        )
    }

    @Test
    fun `an un-watch made in flight beats the rebuild's snapshot`() {
        assertEquals(
            setOf("tt1:1:1"),
            WatchedKeysRebuild.merge(
                built = setOf("tt1:1:1", "tt1:1:2"),
                addedInFlight = emptySet(),
                removedInFlight = setOf("tt1:1:2")
            )
        )
    }

    @Test
    fun `the snapshot's own keys are kept, and a removal wins over an add`() {
        assertEquals(
            setOf("tt1:1:1", "tt1:2:1"),
            WatchedKeysRebuild.merge(
                built = setOf("tt1:1:1"),
                addedInFlight = setOf("tt1:2:1", "tt1:2:2"),
                removedInFlight = setOf("tt1:2:2")
            )
        )
    }
}
