package com.kennyb1201.kbstream.data.simkl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How long a rate-limited account stays quiet, pinned as arithmetic rather than
 * as source text.
 *
 * The read park used to clamp every `Retry-After` into five seconds..ten
 * minutes, which is correct for a burst and wrong for a spent daily quota: the
 * header said ~16 hours and the app retried every ten, 97 of the last 100
 * requests in the 2026-11-19 debug log. These pin the split, the one bound that
 * moved (the daily ceiling) and every bound that did not.
 */
class SimklReadParkTest {

    private val burst = SimklAuthV2Rules.RateLimit.BURST
    private val userDaily = SimklAuthV2Rules.RateLimit.USER_DAILY
    private val appDaily = SimklAuthV2Rules.RateLimit.APP_DAILY
    private val unclassified = SimklAuthV2Rules.RateLimit.NONE

    private val sixteenHoursMs = 16L * 60L * 60L * 1_000L

    @Test
    fun `a daily quota parks for its whole Retry-After`() {
        assertEquals(
            "16 hours of header is 16 hours of park - this is the bug",
            sixteenHoursMs,
            SimklReadPark.durationMs(429, "57600", userDaily)
        )
        assertEquals(
            "app_limit_exceeded is the same wait: every user is on that reset",
            sixteenHoursMs,
            SimklReadPark.durationMs(429, "57600", appDaily)
        )
    }

    @Test
    fun `a long header wins over a burst class`() {
        assertEquals(
            "the 15:52 paste parked on Retry-After 29460 with the class reading " +
                "burst - clamping that to ten minutes is the polling loop",
            sixteenHoursMs,
            SimklReadPark.durationMs(429, "57600", burst)
        )
        assertEquals(
            "and the same for an unreadable body, which is the other thing " +
                "`burst` used to mean",
            sixteenHoursMs,
            SimklReadPark.durationMs(429, "57600", unclassified)
        )
    }

    @Test
    fun `a header that fits inside the burst ceiling still clamps`() {
        assertEquals(
            "an hour is longer than a burst, so it is taken whole",
            60L * 60L * 1_000L,
            SimklReadPark.durationMs(429, "3600", burst)
        )
        assertEquals(
            "and a genuine burst header is untouched",
            30_000L,
            SimklReadPark.durationMs(429, "30", burst)
        )
        assertEquals(
            "the ceiling itself is the boundary: at it, the clamp applies",
            SimklReadPark.MAX_BURST_MS,
            SimklReadPark.durationMs(429, "600", burst)
        )
    }

    @Test
    fun `the burst ceiling is what decides, not the word`() {
        assertTrue(SimklReadPark.headerOutlastsBurst("3600"))
        assertFalse(SimklReadPark.headerOutlastsBurst("600"))
        assertFalse(SimklReadPark.headerOutlastsBurst(null))
        assertFalse(SimklReadPark.headerOutlastsBurst("later"))
    }

    @Test
    fun `a missing header defaults, a silly one floors`() {
        assertEquals(
            SimklReadPark.DEFAULT_MS,
            SimklReadPark.durationMs(429, null, userDaily)
        )
        assertEquals(
            "an unparseable header is a missing one",
            SimklReadPark.DEFAULT_MS,
            SimklReadPark.durationMs(429, "Wed, 21 Oct 2015 07:28:00 GMT", burst)
        )
        assertEquals(
            SimklReadPark.MIN_MS,
            SimklReadPark.durationMs(429, "0", userDaily)
        )
        assertEquals(
            SimklReadPark.MIN_MS,
            SimklReadPark.durationMs(429, "-600", burst)
        )
    }

    @Test
    fun `an unclassified 429 with a short header parks as a burst`() {
        assertEquals(
            "a 429 whose body could not be read and whose header fits inside a " +
                "burst is clamped, not guessed into a long sleep",
            300_000L,
            SimklReadPark.durationMs(429, "300", unclassified)
        )
    }

    @Test
    fun `anything that is not a 429 is the plain backoff`() {
        assertEquals(
            "a timeout says nothing about a quota",
            SimklReadPark.FAILURE_BACKOFF_MS,
            SimklReadPark.durationMs(null, "57600", unclassified)
        )
        assertEquals(
            "and neither does a 500 that happens to carry a header",
            SimklReadPark.FAILURE_BACKOFF_MS,
            SimklReadPark.durationMs(500, "57600", burst)
        )
    }

    @Test
    fun `Retry-After is seconds, trimmed`() {
        assertEquals(90_000L, SimklReadPark.retryAfterMs(" 90 "))
        assertNull(SimklReadPark.retryAfterMs("Wed, 21 Oct 2015 07:28:00 GMT"))
        assertNull(SimklReadPark.retryAfterMs(null))
    }
}
