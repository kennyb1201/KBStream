package com.kennyb1201.kbstream.ui.player

/**
 * How far the season strip has to scroll to show one chip whole
 * (spec 2026-11-17, F3).
 *
 * The field's report is a half-cut chip at the panel's edge: the season the
 * viewer was about to pick, unreadable. Focus search on a RecyclerView asks for
 * "make this child visible", and a child that is already partly visible satisfies
 * that - it never moves, and the strip is left with half a chip.
 *
 * The answer is the smallest scroll that puts the whole rectangle inside the
 * viewport, which is the same rule a horizontal list wants at either end: align
 * the near edge when the child is off it, the far edge when it overhangs, and do
 * nothing when it already fits. Kept free of Android types so the arithmetic -
 * including the chip that is wider than the strip - is testable without a window.
 */
internal object SeasonStripReveal {

    /**
     * The dx to scroll by, in pixels: negative moves the content left.
     *
     * [childLeft] and [childRight] are in the strip's own coordinates (its left
     * edge is 0), and [viewportLeft]/[viewportRight] are the box the strip can
     * show. A child wider than that box cannot be shown whole; it is aligned to
     * the near edge, which is the only part worth reading.
     */
    fun dx(childLeft: Int, childRight: Int, viewportLeft: Int, viewportRight: Int): Int {
        val available = viewportRight - viewportLeft
        val width = childRight - childLeft
        return when {
            // Wider than the strip, or hanging off its near edge.
            width >= available || childLeft < viewportLeft -> childLeft - viewportLeft
            // Off the far edge.
            childRight > viewportRight -> childRight - viewportRight
            else -> 0
        }
    }
}
