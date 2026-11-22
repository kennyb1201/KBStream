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
 * Two clocks, one row. "Not available" is a statement about today - yesterday's
 * "not yet" is not an answer about today, and getting that wrong means showing a
 * title the filter exists to hide. "Available" is derived from a release date
 * already in the past; that cannot move back into the future, so it stays true
 * for the payload's own 30-day lifetime and saves every current-year title a
 * re-derive on the first launch of every day.
 */
class HomeAvailabilityVerdictTest {

    private val today = 20_700L

    @Test
    fun `a row decided today answers`() {
        assertEquals(true, HomeAvailabilityVerdict.decode(HomeAvailabilityVerdict.encode(today, true), today))
        assertEquals(false, HomeAvailabilityVerdict.decode(HomeAvailabilityVerdict.encode(today, false), today))
    }

    @Test
    fun `an available row is honoured inside the payload TTL`() {
        val oldest = today - HomeAvailabilityVerdict.AVAILABLE_MAX_AGE_DAYS
        assertEquals(
            "a digital date in the past cannot move back into the future",
            true,
            HomeAvailabilityVerdict.decode(HomeAvailabilityVerdict.encode(oldest, true), today)
        )
        assertEquals(
            "yesterday's available answer is still an answer",
            true,
            HomeAvailabilityVerdict.decode(HomeAvailabilityVerdict.encode(today - 1, true), today)
        )
        assertNull(
            "past the payload TTL the answer is re-derived, not trusted",
            HomeAvailabilityVerdict.decode(HomeAvailabilityVerdict.encode(oldest - 1, true), today)
        )
    }

    @Test
    fun `a not-available row is strictly day-scoped`() {
        // Yesterday's "not available" is the one that would hide a title whose
        // window opened overnight.
        assertNull(
            HomeAvailabilityVerdict.decode(HomeAvailabilityVerdict.encode(today - 1, false), today)
        )
    }

    @Test
    fun `a future row is unknown`() {
        assertNull(HomeAvailabilityVerdict.decode(HomeAvailabilityVerdict.encode(today + 1, true), today))
        assertNull(HomeAvailabilityVerdict.decode(HomeAvailabilityVerdict.encode(today + 1, false), today))
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
