package com.kennyb1201.kbstream.data.mdblist

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * MDBList rejects a fractional `progress` on POST /scrobble/{start,pause,stop}
 * with HTTP 400 ("Ensure that there are no more than 5 digits in total."),
 * which silently killed every live scrobble while /sync/watched kept working.
 * These pin the shape the API accepts.
 */
class MdbListScrobbleProgressTest {

    @Test
    fun `position ratio from the player is rounded to whole percent`() {
        // Exactly the value seen in the field.
        assertEquals(6, mdblistScrobbleProgress(6.184509511134195))
    }

    @Test
    fun `rounds to nearest percent`() {
        assertEquals(16, mdblistScrobbleProgress(15.5))
        assertEquals(15, mdblistScrobbleProgress(15.4))
        assertEquals(100, mdblistScrobbleProgress(99.6))
    }

    @Test
    fun `whole values pass through`() {
        assertEquals(0, mdblistScrobbleProgress(0.0))
        assertEquals(50, mdblistScrobbleProgress(50.0))
        assertEquals(100, mdblistScrobbleProgress(100.0))
    }

    @Test
    fun `stays inside the valid percentage range`() {
        assertEquals(100, mdblistScrobbleProgress(120.0))
        assertEquals(0, mdblistScrobbleProgress(-5.0))
        assertEquals(100, mdblistScrobbleProgress(Double.POSITIVE_INFINITY))
    }

    @Test
    fun `never throws on a value that could not be computed`() {
        assertEquals(0, mdblistScrobbleProgress(Double.NaN))
    }
}
