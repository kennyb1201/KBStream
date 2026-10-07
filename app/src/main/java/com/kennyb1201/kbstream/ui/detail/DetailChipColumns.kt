package com.kennyb1201.kbstream.ui.detail

/**
 * Where a vertical D-pad press between the detail page's narrow chip rails
 * lands.
 *
 * The press keeps its COLUMN. A viewer on the leftmost NETWORK chip belongs on
 * the leftmost PRODUCTION chip below it, and on the leftmost writer/actor card
 * above it - not on whichever chip the target rail happens to have been left
 * on. Those rails are separately measured (chips are 120dp wide and one rail
 * deep), they each carry a `focusRestorer`, and the default geometric search
 * between two such rails answers with "nearest node in the beam", which is a
 * chip several columns away as soon as the rows do not line up. The column is
 * what the press is about, so the column is what decides it.
 *
 * Kept pure and separate from the screen so the rule can be pinned without a
 * device: the wiring that uses it is a key handler, which no unit test can
 * press.
 */
internal object DetailChipColumns {

    /**
     * The column of the rail being moved into, or null when that rail has
     * nothing to move to.
     *
     * [targetCount] is the number of chips the target rail actually has laid
     * out. A SHORTER rail than the one the press came from puts the viewer on
     * its last chip rather than on nothing: the chips above and below are not
     * paired one to one (a show can list four networks and nine production
     * companies), and a press that keeps its column where it can and stops at
     * the end of the row otherwise is what a viewer expects from a D-pad.
     */
    fun columnFor(fromColumn: Int, targetCount: Int): Int? = when {
        targetCount <= 0 -> null
        fromColumn <= 0 -> 0
        fromColumn >= targetCount -> targetCount - 1
        else -> fromColumn
    }
}
