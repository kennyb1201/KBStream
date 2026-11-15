package com.kennyb1201.kbstream.ui.player

/** A D-pad direction, as the picker's traps care about it. */
internal enum class StripDirection { LEFT, RIGHT, UP, DOWN, OTHER }

/**
 * Where a press on the picker's season strip is allowed to go (spec 2026-11-14).
 *
 * Field report: UP from the episode list reached the strip, and one more
 * LEFT/RIGHT at the strip's end left the panel entirely - focus landed on the
 * player overlay's cast row, where the next OK opened an actor page mid-episode.
 * The strip had exactly one wired edge (DOWN into the list), so the other three
 * fell back to focus search, which walks the whole window and finds the chrome
 * drawn behind the panel.
 *
 * The rule is deliberately "hold at the end" rather than "wrap": a wrap on a
 * two-season show moves the ring to the other end of the strip on a press the
 * viewer read as "there is nothing further", and the two engines have to agree.
 * Holding is also what makes the trap provable - the same chip keeps the ring,
 * and focus cannot leave the strip on any direction but DOWN.
 *
 * Pure on purpose: the decision is one arithmetic comparison per direction and
 * is unit tested without a RecyclerView, a window, or a TV.
 */
internal object PickerDpadRules {

    /**
     * True when this press must be CONSUMED so it never reaches focus search:
     * LEFT on the first chip, RIGHT on the last, and UP anywhere on the strip
     * (there is nothing above it inside the picker).
     *
     * DOWN is never consumed - it is the strip's one real edge, wired through
     * `nextFocusDownId` into the episode list.
     */
    fun holdsWithinStrip(direction: StripDirection, position: Int, lastIndex: Int): Boolean =
        when (direction) {
            StripDirection.LEFT -> position <= 0
            StripDirection.RIGHT -> position >= lastIndex
            StripDirection.UP -> true
            StripDirection.DOWN, StripDirection.OTHER -> false
        }
}
