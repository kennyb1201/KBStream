package com.kennyb1201.kbstream.data.tmdb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The availability filter's cheapest verdict (0.9.394).
 *
 * The pass exists to hide a movie that is still in its theatrical window or not
 * out yet, and it decided that from one TMDB enriched-detail fetch PER MOVIE -
 * `detailSrc net:436` on a cold Home, against a 150-entry detail cache that
 * cannot hold a rail. The row also carries a release year, and for a movie
 * released in an earlier calendar year that year alone settles the question.
 *
 * These cases pin the arithmetic and, just as importantly, the direction it
 * fails in: anything it cannot read (no year, a junk year, the current year, a
 * future one) must still go to TMDB. The gate may only ever REMOVE a request -
 * a gate that guesses wrong the other way hides titles.
 */
class ReleaseAgeGateTest {

    @Test
    fun `a title released in an earlier year needs no fetch`() {
        assertTrue(
            "a 2019 movie on a 2026 Home is a year or more past any theatrical " +
                "window this verdict distinguishes",
            ReleaseAgeGate.isDefinitelyReleased(2019, 2026)
        )
        assertTrue(
            "last year counts too: the year is a calendar year, so 2025 is " +
                "between 11 and 23 months of release",
            ReleaseAgeGate.isDefinitelyReleased(2025, 2026)
        )
    }

    @Test
    fun `this year and next year are still asked`() {
        assertFalse(
            "a movie released this year can still be inside its theatrical " +
                "window - this is exactly the case the filter exists for",
            ReleaseAgeGate.isDefinitelyReleased(2026, 2026)
        )
        assertFalse(
            "an announced title is not out at all",
            ReleaseAgeGate.isDefinitelyReleased(2027, 2026)
        )
    }

    @Test
    fun `a year it cannot read keeps the fetch`() {
        assertFalse(
            "no releaseInfo and no year: nothing is known, so TMDB is asked",
            ReleaseAgeGate.isDefinitelyReleased(null, 2026)
        )
        assertFalse(
            "an add-on that ships '0' or '1' in releaseInfo must not read as " +
                "'released in year 1' and skip the fetch",
            ReleaseAgeGate.isDefinitelyReleased(0, 2026)
        )
        assertFalse(
            "nor a negative or truncated year",
            ReleaseAgeGate.isDefinitelyReleased(-1990, 2026)
        )
    }

    @Test
    fun `the oldest plausible year is a film, not junk`() {
        assertTrue(
            "the gate's floor is 1870: a real silent film is a real release",
            ReleaseAgeGate.isDefinitelyReleased(1870, 2026)
        )
        assertFalse(
            "one year below it is junk, not a film",
            ReleaseAgeGate.isDefinitelyReleased(1869, 2026)
        )
    }
}
