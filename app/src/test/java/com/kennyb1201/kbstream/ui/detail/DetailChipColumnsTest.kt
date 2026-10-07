package com.kennyb1201.kbstream.ui.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The column a vertical D-pad press between the detail page's chip rails
 * lands in.
 *
 * Reported problem: pressing DOWN on the leftmost NETWORK chip sometimes
 * landed on a PRODUCTION chip several columns to the right, and pressing UP
 * from a network chip landed on whichever actor card that rail had last been
 * left on rather than the one directly above. Both rails carry a
 * focusRestorer, so "leave it to the search" answers with a memory.
 */
class DetailChipColumnsTest {

    @Test
    fun `the leftmost chip steps to the leftmost chip of the next rail`() {
        assertEquals(0, DetailChipColumns.columnFor(fromColumn = 0, targetCount = 4))
    }

    @Test
    fun `a column is kept as it is`() {
        assertEquals(1, DetailChipColumns.columnFor(fromColumn = 1, targetCount = 4))
        assertEquals(3, DetailChipColumns.columnFor(fromColumn = 3, targetCount = 4))
    }

    @Test
    fun `a shorter rail takes the press on its last chip`() {
        // Four networks, two production companies: DOWN from network 3 cannot
        // keep its column, and stopping at the end of the row is what a D-pad
        // does everywhere else. Never "no move at all".
        assertEquals(1, DetailChipColumns.columnFor(fromColumn = 3, targetCount = 2))
        assertEquals(0, DetailChipColumns.columnFor(fromColumn = 9, targetCount = 1))
    }

    @Test
    fun `a rail with nothing in it takes no press`() {
        assertNull(DetailChipColumns.columnFor(fromColumn = 0, targetCount = 0))
        assertNull(DetailChipColumns.columnFor(fromColumn = 5, targetCount = -1))
    }

    @Test
    fun `an index off the left edge still starts the next rail`() {
        // Defensive: the columns are derived, so a card that is somehow not in
        // the column list must not send the press to a negative index.
        assertEquals(0, DetailChipColumns.columnFor(fromColumn = -1, targetCount = 3))
    }
}
