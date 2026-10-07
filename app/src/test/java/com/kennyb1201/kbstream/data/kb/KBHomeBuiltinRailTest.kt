package com.kennyb1201.kbstream.data.kb

import com.kennyb1201.kbstream.ui.kb.visibleBuiltinRailKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The built-in rails (Continue Watching, Upcoming Schedule) as members of the
 * home arrangement.
 *
 * They used to be hardcoded `item`s in Home's rail column: moved by nothing,
 * hidden by nothing, renamed by nothing. They are arranged now, and the two
 * things that make that safe rather than surprising are pinned here:
 *
 *  - **the default layout does not move.** An arrangement that mentions no
 *    built-in key - which is every arrangement written before this existed -
 *    still puts Continue Watching first and Upcoming second, so a user who
 *    never opens the home manager sees exactly what they saw before.
 *  - **the rename map survives every transform.** Every move, normalize and
 *    remap rewrites the arrangement through `copy()`; a rename that quietly
 *    fell out of one of those would read as "renaming does not stick".
 */
class KBHomeBuiltinRailTest {

    // Browse, catalogs, collections: the pre-existing defaults, which the
    // built-ins now lead.
    private val existingDefaults = listOf("browse:1", "addon:a", "addon:b", "kb:c")
    private val defaults = KBHomeOrderPrefs.BUILTIN_KEYS + existingDefaults

    private val continueWatching = KBHomeOrderPrefs.BUILTIN_CONTINUE_WATCHING
    private val upcoming = KBHomeOrderPrefs.BUILTIN_UPCOMING_SCHEDULE
    private val topMovies = KBHomeOrderPrefs.BUILTIN_TOP_MOVIES_TODAY
    private val topShows = KBHomeOrderPrefs.BUILTIN_TOP_SHOWS_TODAY

    @Test
    fun `the built-in keys are stable, prefixed and distinct`() {
        assertEquals("builtin:", KB_BUILTIN_KEY_PREFIX)
        assertEquals("builtin:continue_watching", continueWatching)
        assertEquals("builtin:upcoming_schedule", upcoming)
        assertTrue(KBHomeOrderPrefs.isBuiltinKey(continueWatching))
        assertTrue(KBHomeOrderPrefs.isBuiltinKey(upcoming))
        // A collection and a catalog key must not be mistaken for one.
        assertFalse(KBHomeOrderPrefs.isBuiltinKey("kb:c"))
        assertFalse(KBHomeOrderPrefs.isBuiltinKey("addon:a"))
        assertFalse(KBHomeOrderPrefs.isBuiltinKey(null))
        // Continue Watching leads, and the two Top Today rows follow Upcoming:
        // that is the layout being preserved. The Top Today rows are built-ins
        // now too (they used to be addon rails whose position the loader FIXED,
        // which is why the manager could never list them).
        assertEquals(
            listOf(continueWatching, upcoming, topMovies, topShows),
            KBHomeOrderPrefs.BUILTIN_KEYS
        )
        assertTrue(KBHomeOrderPrefs.isBuiltinKey(topMovies))
        assertTrue(KBHomeOrderPrefs.isBuiltinKey(topShows))
        assertEquals("Top Movies Today", KBHomeOrderPrefs.builtinDefaultTitle(topMovies))
        assertEquals("Top Shows Today", KBHomeOrderPrefs.builtinDefaultTitle(topShows))
    }

    @Test
    fun `the default layout is the built-ins first, then everything else`() {
        // This is the parity claim: an arrangement nothing has touched renders
        // Continue Watching, then Upcoming, then the rails that were already
        // there - byte-for-byte the hardcoded order they replace.
        assertEquals(
            defaults,
            mergedHomeRailKeys(KBHomeOrder(), defaults)
        )
    }

    @Test
    fun `an arrangement from an older build neither drops nor duplicates a built-in`() {
        // Written before built-ins existed, so it names no built-in key. Two
        // things must hold for the user who owns it: the built-ins are still
        // positioned somewhere (never silently dropped), and exactly once.
        //
        // Where they land in THIS list - after the stored rails, because an
        // unarranged key takes the tail - is deliberately not the whole story:
        // Home front-loads an unarranged built-in (see KBHomeSlots), so the
        // layout such a user sees is the one they have always seen, Continue
        // Watching first. The manager's list becomes a faithful mirror the
        // moment any move names them explicitly, because a move writes the
        // whole visible list into the arrangement.
        val legacy = KBHomeOrder(
            order = listOf("addon:b", "kb:c"),
            pinned = listOf("kb:c"),
            hidden = listOf("addon:a")
        )

        val merged = mergedHomeRailKeys(legacy, defaults)
        assertTrue(continueWatching in merged)
        assertTrue(upcoming in merged)
        assertEquals("a rail must appear once", merged.size, merged.toSet().size)
        // The pinned rail still leads, and the hidden one is only filtered at
        // render time, never dropped from the order itself.
        assertEquals("kb:c", merged.first())
        assertTrue("addon:a" in merged)
        assertTrue("addon:a" in legacy.hiddenSet)
    }

    @Test
    fun `a built-in is orderable, so moving it changes the merged order`() {
        // Continue Watching is not special-cased out of the move path: it can
        // be pushed below a catalog like any other rail.
        val moved = moveRailInMergedOrder(KBHomeOrder(), defaults, continueWatching, +1)

        assertEquals(
            listOf(
                upcoming,
                continueWatching,
                topMovies,
                topShows,
                "browse:1",
                "addon:a",
                "addon:b",
                "kb:c"
            ),
            mergedHomeRailKeys(moved, defaults)
        )
        assertTrue(railMoveChangesOrder(KBHomeOrder(), defaults, continueWatching, +1))
    }

    @Test
    fun `the drawn order and the moved order stay the same list, built-ins included`() {
        // The same property the pre-existing rails are held to, now covering
        // the built-ins: whatever the manager draws, UP lands a row one slot up
        // in that very list.
        val drawn = mergedHomeRailKeys(KBHomeOrder(), defaults)

        drawn.drop(1).forEachIndexed { index, key ->
            val swapped = drawn.toMutableList().also { it.add(index, it.removeAt(index + 1)) }

            assertEquals(
                "UP on $key should land it one slot up",
                swapped,
                mergedHomeRailKeys(
                    moveRailInMergedOrder(KBHomeOrder(), defaults, key, -1),
                    defaults
                )
            )
        }
    }

    @Test
    fun `a built-in is not pinnable, and pinning one is a no-op`() {
        assertFalse(KBHomeOrderPrefs.isPinnableKey(continueWatching))
        assertFalse(KBHomeOrderPrefs.isPinnableKey(upcoming))

        // The pin control is not offered on a built-in row, so the transform
        // must never write one into the pinned block (where it could not be
        // taken back out again).
        assertEquals(
            KBHomeOrder(),
            toggleCollectionPin(KBHomeOrder(), continueWatching)
        )
    }

    @Test
    fun `a rename survives every arrangement transform`() {
        val renamed = KBHomeOrderPrefs.withRename(
            KBHomeOrder(),
            continueWatching,
            "Keep going"
        )
        assertEquals(mapOf(continueWatching to "Keep going"), renamed.renames)

        // Each of these rewrites the arrangement through copy(); the map has to
        // ride along or a rename disappears the moment a rail is moved.
        val trajectories = listOf(
            "one slot" to moveRailInMergedOrder(renamed, defaults, "addon:a", -1),
            "very top" to moveRailToEnd(renamed, "addon:a", toTop = true),
            "very bottom" to moveRailToEnd(renamed, continueWatching, toTop = false),
            "normalize" to normalizeHomeOrder(
                renamed.copy(pinned = listOf("addon:x"))
            ),
            "remap" to remapAddonOrderKeys(renamed, mapOf("addon:a" to "addon:z"))
        )

        trajectories.forEach { (label, after) ->
            assertEquals(
                "$label must keep the rename",
                mapOf(continueWatching to "Keep going"),
                after.renames
            )
        }
    }

    @Test
    fun `a blank name clears the override instead of storing one`() {
        val renamed = KBHomeOrderPrefs.withRename(KBHomeOrder(), upcoming, "Airing soon")
        val cleared = KBHomeOrderPrefs.withRename(renamed, upcoming, "   ")

        assertTrue(cleared.renames.isEmpty())
        assertEquals(
            "Upcoming",
            KBHomeOrderPrefs.railTitle(cleared, upcoming, "Upcoming")
        )
    }

    @Test
    fun `the title is the override when set and the default otherwise`() {
        val renamed = KBHomeOrderPrefs.withRename(
            KBHomeOrder(),
            continueWatching,
            "Pick up where you left off"
        )

        assertEquals(
            "Pick up where you left off",
            KBHomeOrderPrefs.railTitle(renamed, continueWatching, "Continue Watching")
        )
        assertEquals(
            "Upcoming",
            KBHomeOrderPrefs.railTitle(renamed, upcoming, "Upcoming")
        )
        assertEquals(
            "Continue Watching",
            KBHomeOrderPrefs.builtinDefaultTitle(continueWatching)
        )
        assertEquals("Upcoming", KBHomeOrderPrefs.builtinDefaultTitle(upcoming))
    }

    @Test
    fun `only a shown built-in that has content is emitted`() {
        // The rule the merge applies, tested where it lives rather than read
        // out of the merge: hidden is out, and so is an empty rail.
        assertEquals(
            listOf(continueWatching, upcoming),
            visibleBuiltinRailKeys(
                hidden = emptySet(),
                withContent = setOf(continueWatching, upcoming)
            )
        )
        assertEquals(
            listOf(upcoming),
            visibleBuiltinRailKeys(
                hidden = setOf(continueWatching),
                withContent = setOf(continueWatching, upcoming)
            )
        )
        assertEquals(
            listOf(continueWatching),
            visibleBuiltinRailKeys(
                hidden = emptySet(),
                withContent = setOf(continueWatching)
            )
        )
        assertTrue(
            "an empty rail is not emitted at all",
            visibleBuiltinRailKeys(hidden = emptySet(), withContent = emptySet()).isEmpty()
        )
        // Order is the registry's, never the caller's set iteration.
        assertEquals(
            listOf(continueWatching, upcoming),
            visibleBuiltinRailKeys(
                hidden = emptySet(),
                withContent = setOf(upcoming, continueWatching)
            )
        )
    }

    @Test
    fun `a hidden built-in keeps its slot in the stored order`() {
        val hidden = KBHomeOrder(
            order = listOf(continueWatching, "addon:a"),
            hidden = listOf(continueWatching)
        )

        // Hidden is a filter applied AFTER the merged order is computed, so the
        // position it was given is still there when it is shown again.
        val visible = mergedHomeRailKeys(hidden, defaults).filter { it !in hidden.hiddenSet }
        assertFalse(continueWatching in visible)
        assertTrue(continueWatching in mergedHomeRailKeys(hidden, defaults))
        assertEquals("addon:a", visible.first())
    }
}
