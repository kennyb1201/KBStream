package com.kennyb1201.kbstream.data.kb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The merged order the home manager draws, the reorder core moves within, and
 * Home renders.
 *
 * All three used to derive it separately, and the manager's copy listed the
 * Browse rails last while the move buttons acted on a list that put them
 * first - so a Browse rail was drawn at the bottom of the manager, its UP press
 * did nothing, and it could not be interleaved with the catalogs at all. The
 * cases below pin the one rule that replaced those copies, because a
 * disagreement here is invisible in code review and obvious to a viewer.
 */
class KBHomeRailOrderTest {

    // Browse rails first, then catalogs, then collections: where Home falls
    // back to for a rail nothing has arranged.
    private val defaults = listOf("browse:1", "addon:a", "addon:b", "kb:c")

    @Test
    fun `an untouched arrangement is the default order`() {
        assertEquals(defaults, mergedHomeRailKeys(KBHomeOrder(), defaults))
    }

    @Test
    fun `pinned rails lead, then the stored order, then the rest by default`() {
        val prefs = KBHomeOrder(pinned = listOf("kb:c"), order = listOf("addon:b"))

        assertEquals(
            listOf("kb:c", "addon:b", "browse:1", "addon:a"),
            mergedHomeRailKeys(prefs, defaults)
        )
    }

    @Test
    fun `the drawn order and the moved order are the same list`() {
        // The regression, stated as the property that was broken: whatever the
        // manager draws, pressing UP on a row must land it one slot up in that
        // same list. Every row, including the Browse rails and the collection.
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
    fun `a browse rail interleaves between two catalogs`() {
        // Browse rail, catalog, catalog -> the browse rail goes between them.
        val moved = moveRailInMergedOrder(KBHomeOrder(), defaults, "browse:1", +1)

        assertEquals(
            listOf("addon:a", "browse:1", "addon:b", "kb:c"),
            mergedHomeRailKeys(moved, defaults)
        )
    }

    @Test
    fun `a collection interleaves between two catalogs`() {
        // ...and from the other end: the last rail walks up into the catalogs.
        val moved = moveRailInMergedOrder(KBHomeOrder(), defaults, "kb:c", -1)

        assertEquals(
            listOf("browse:1", "addon:a", "kb:c", "addon:b"),
            mergedHomeRailKeys(moved, defaults)
        )
    }

    @Test
    fun `a move that changes nothing is not written`() {
        // Already at that edge, or a key the arrangement does not know: the
        // same instance comes back, so the caller skips the write and the
        // dialog does not look like it swallowed the press.
        val prefs = KBHomeOrder()

        assertSame(prefs, moveRailInMergedOrder(prefs, defaults, "browse:1", -1))
        assertSame(prefs, moveRailInMergedOrder(prefs, defaults, "kb:c", +1))
        assertSame(prefs, moveRailInMergedOrder(prefs, defaults, "addon:missing", +1))
    }

    @Test
    fun `a hidden rail is not in the order a move walks`() {
        // Hiding a rail takes it out of the list the arrows move through, so
        // the neighbours close up instead of leaving a slot that swallows a
        // press.
        val prefs = KBHomeOrder(hidden = listOf("addon:a"))
        val visible = mergedHomeRailKeys(prefs, defaults).filter { it !in prefs.hiddenSet }

        assertEquals(listOf("browse:1", "addon:b", "kb:c"), visible)

        val moved = moveRailInMergedOrder(prefs, defaults, "addon:b", -1)
        assertEquals(
            listOf("addon:b", "browse:1", "kb:c"),
            mergedHomeRailKeys(moved, defaults).filter { it !in moved.hiddenSet }
        )
    }

    @Test
    fun `a pinned rail is reordered inside the pinned block, not out of it`() {
        // A pin is an explicit "above the rest", so it is a block of its own -
        // two pins reorder against each other and neither drops below an
        // unpinned rail.
        val prefs = KBHomeOrder(pinned = listOf("browse:1", "kb:c"))

        assertEquals(
            listOf("browse:1", "kb:c", "addon:a", "addon:b"),
            mergedHomeRailKeys(prefs, defaults)
        )

        val moved = moveRailInMergedOrder(prefs, defaults, "kb:c", -1)
        assertEquals(
            listOf("kb:c", "browse:1", "addon:a", "addon:b"),
            mergedHomeRailKeys(moved, defaults)
        )
        assertEquals(listOf("kb:c", "browse:1"), moved.pinned)
    }

    @Test
    fun `a rail the loader positions reports every move as a no-op`() {
        // The Top Today rows render first whatever the order says, so an arrow
        // on one could only ever rewrite an arrangement that Home ignores.
        val topToday = "addon:https://toptoday.llamayu.com/landscapeTags=true:movie:top"
        val withFixed = listOf(topToday, "browse:1", "addon:a")

        assertTrue(KBHomeOrderPrefs.isPositionFixedKey(topToday))
        assertTrue(KBHomeOrderPrefs.isPositionFixedKey("addon:https://toptoday.llamayu.com/x:series:y"))
        assertTrue(!KBHomeOrderPrefs.isPositionFixedKey("addon:https://toptoday.llamayu.com.example/x:movie:y"))
        assertTrue(!KBHomeOrderPrefs.isPositionFixedKey("addon:https://other.test/:movie:y"))
        assertTrue(!KBHomeOrderPrefs.isPositionFixedKey(null))

        val prefs = KBHomeOrder()
        listOf(Int.MIN_VALUE, -1, +1, Int.MAX_VALUE).forEach { delta ->
            assertTrue(
                "a fixed rail must not offer a $delta move",
                !railMoveChangesOrder(prefs, withFixed, topToday, delta)
            )
        }
    }

    @Test
    fun `an arrow is only live when the press would change the order`() {
        // The enabled state comes from the transforms themselves, so an arrow
        // can never be offered for a move that does nothing.
        val prefs = KBHomeOrder()
        val first = defaults.first()
        val last = defaults.last()

        assertTrue(!railMoveChangesOrder(prefs, defaults, first, -1))
        assertTrue(railMoveChangesOrder(prefs, defaults, first, +1))
        assertTrue(railMoveChangesOrder(prefs, defaults, last, -1))
        assertTrue(!railMoveChangesOrder(prefs, defaults, last, +1))

        // A pinned rail is already at the head of the pinned block, so its TOP
        // press changes nothing; the rail below it can still climb.
        val pinned = KBHomeOrder(pinned = listOf("kb:c"))
        assertTrue(!railMoveChangesOrder(pinned, defaults, "kb:c", Int.MIN_VALUE))
        assertTrue(railMoveChangesOrder(pinned, defaults, "addon:b", Int.MIN_VALUE))
    }

    @Test
    fun `keys the arrangement does not know keep their default slot`() {
        // A rail added since the last arrangement still has somewhere to go.
        val prefs = KBHomeOrder(order = listOf("addon:b"))

        assertEquals(
            listOf("addon:b", "browse:1", "addon:a", "kb:c"),
            mergedHomeRailKeys(prefs, defaults)
        )
    }
}
