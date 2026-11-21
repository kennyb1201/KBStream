package com.kennyb1201.kbstream.data.tmdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The persisted verdict row: what a Home build writes so the next one does not
 * re-derive 1,400 answers.
 *
 * The rule being pinned is the DAY, not a tolerance around it. A verdict says
 * whether a title can be watched at home today; yesterday's "not yet" is not an
 * answer about today, and getting that wrong means showing a title the filter
 * exists to hide - so every row from another day must read as "unknown" and send
 * the caller back to the payload.
 */
class HomeAvailabilityVerdictTest {

    private val today = 20_700L

    @Test
    fun `a row decided today answers`() {
        assertEquals(true, HomeAvailabilityVerdict.decode(HomeAvailabilityVerdict.encode(today, true), today))
        assertEquals(false, HomeAvailabilityVerdict.decode(HomeAvailabilityVerdict.encode(today, false), today))
    }

    @Test
    fun `a row from another day is unknown, in both directions`() {
        // Yesterday's "not available" is the one that would hide a title whose
        // window opened overnight; tomorrow's is a clock change.
        assertNull(HomeAvailabilityVerdict.decode(HomeAvailabilityVerdict.encode(today - 1, false), today))
        assertNull(HomeAvailabilityVerdict.decode(HomeAvailabilityVerdict.encode(today - 1, true), today))
        assertNull(HomeAvailabilityVerdict.decode(HomeAvailabilityVerdict.encode(today + 1, true), today))
    }

    @Test
    fun `a malformed row is unknown rather than a verdict`() {
        listOf(null, "", "  ", ":", "20700", "abc:1", "20700:", "20700:2", "20700:1:1")
            .forEach { row ->
                assertNull("row '$row' must not answer", HomeAvailabilityVerdict.decode(row, today))
            }
    }

    @Test
    fun `surrounding whitespace does not make a good row bad`() {
        assertEquals(true, HomeAvailabilityVerdict.decode(" 20700:1\n", today))
    }

    @Test
    fun `verdict rows live in their own namespace`() {
        val key = HomeAvailabilityVerdict.key("tt123")
        assertEquals("homeavail:tt123", key)
        assertTrue(HomeAvailabilityVerdict.isVerdictKey(key))
        // The payload keys this cache also holds must not be mistaken for one.
        assertFalse(HomeAvailabilityVerdict.isVerdictKey("detail_en:tt123"))
        assertFalse(HomeAvailabilityVerdict.isVerdictKey("rail_en:tt123"))
    }
}
