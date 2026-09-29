package com.kennyb1201.kbstream.ui.detail

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where Back from a drill-down lands on the Detail page.
 *
 * Reported problem: backing out of an actor / network / production-company page
 * put the focus back on the play button with the page at its top, instead of on
 * the card that had been pressed. The detail page is rebuilt from scratch when
 * a drill-down is backed out of, so the card has to be recorded on the way out
 * and handed back to the page that reopens.
 */
class DetailReturnFocusTest {

    /**
     * The record is process-wide by design - that is what survives the trip -
     * so no test may leave one behind for the next.
     */
    @After
    fun tearDown() {
        DetailReturnFocus.consume("movie:tt1234")
        DetailReturnFocus.consume("series:tt999")
    }

    private fun target(
        detailKey: String = "movie:tt1234",
        targetKey: String = "person99Director",
        rowKey: String = "peoplerow",
        rowIndex: Int = 12,
        itemIndex: Int = 3
    ): DetailReturnTarget =
        DetailReturnTarget(
            detailKey = detailKey,
            targetKey = targetKey,
            rowKey = rowKey,
            rowIndex = rowIndex,
            itemIndex = itemIndex
        )

    @Test
    fun `the card is handed back to the page it was pressed on`() {
        val recorded = target()
        DetailReturnFocus.record(recorded)
        assertEquals(recorded, DetailReturnFocus.consume("movie:tt1234"))
    }

    @Test
    fun `a record is only ever read once`() {
        DetailReturnFocus.record(target())
        assertEquals(target(), DetailReturnFocus.consume("movie:tt1234"))
        assertNull(DetailReturnFocus.consume("movie:tt1234"))
    }

    @Test
    fun `another title's page does not take the record`() {
        DetailReturnFocus.record(target())
        assertNull(DetailReturnFocus.consume("series:tt999"))
    }

    @Test
    fun `a title opened from the first one does not swallow the record`() {
        // A "More Like This" poster opens a second detail page on the way, so
        // the first page's record has to survive that page composing - its own
        // key is what keeps the two apart.
        val recorded = target()
        DetailReturnFocus.record(recorded)
        assertNull(DetailReturnFocus.consume("series:tt999"))
        assertEquals(recorded, DetailReturnFocus.consume("movie:tt1234"))
    }

    @Test
    fun `an ordinary open has nothing to return to`() {
        assertNull(DetailReturnFocus.consume("movie:tt1234"))
    }

    @Test
    fun `the rail and the card come back with the record`() {
        val recorded = target(
            targetKey = "network:213",
            rowKey = "networkrow",
            rowIndex = 7,
            itemIndex = 1
        )
        DetailReturnFocus.record(recorded)
        val consumed = DetailReturnFocus.consume("movie:tt1234")
        assertEquals("networkrow", consumed?.rowKey)
        assertEquals(7, consumed?.rowIndex)
        assertEquals(1, consumed?.itemIndex)
    }
}
