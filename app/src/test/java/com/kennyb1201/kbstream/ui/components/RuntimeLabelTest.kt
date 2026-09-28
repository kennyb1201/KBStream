package com.kennyb1201.kbstream.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The runtime on the detail screen's meta line.
 *
 * Reported problem: the line showed a bare number of minutes ("96") with
 * nothing saying what it counted. The value came from the add-on's free-form
 * `runtime` string, which reaches the line unformatted whenever TMDB has no
 * runtime of its own (most series, and any title TMDB is thin on).
 */
class RuntimeLabelTest {

    @Test
    fun `tmdb minutes render in the short form`() {
        assertEquals("1h 42m", formatRuntimeLabel(tmdbMinutes = 102, addonRuntime = null))
        assertEquals("45m", formatRuntimeLabel(tmdbMinutes = 45, addonRuntime = null))
        assertEquals("2h", formatRuntimeLabel(tmdbMinutes = 120, addonRuntime = null))
    }

    @Test
    fun `tmdb minutes win over the add-on's own string`() {
        // Both sources rarely agree exactly; TMDB is the one the rest of the
        // screen (resume, progress, episode lengths) is built on.
        assertEquals("1h 42m", formatRuntimeLabel(102, "96"))
    }

    @Test
    fun `a zero tmdb runtime falls through to the add-on`() {
        assertEquals("1h 36m", formatRuntimeLabel(tmdbMinutes = 0, addonRuntime = "96"))
        assertNull(formatRuntimeLabel(tmdbMinutes = 0, addonRuntime = null))
    }

    @Test
    fun `bare add-on minutes are labelled`() {
        assertEquals("1h 36m", formatRuntimeLabel(null, "96"))
        assertEquals("42m", formatRuntimeLabel(null, "42"))
    }

    @Test
    fun `minutes the add-on already labels are normalised`() {
        assertEquals("2h 14m", formatRuntimeLabel(null, "134 min"))
        assertEquals("2h 14m", formatRuntimeLabel(null, "134mins"))
        assertEquals("1h 36m", formatRuntimeLabel(null, " 96m "))
    }

    @Test
    fun `a runtime the add-on spells out is passed through`() {
        // Nothing here parses as a plain number of minutes, and both already
        // read as durations, so they are left exactly as the add-on wrote them.
        assertEquals("1h 36m", formatRuntimeLabel(null, "1h 36m"))
        assertEquals("2 hr 5 min", formatRuntimeLabel(null, "2 hr 5 min"))
        assertEquals("feature length", formatRuntimeLabel(null, "feature length"))
    }

    @Test
    fun `an absent runtime drops out of the line`() {
        assertNull(formatRuntimeLabel(null, null))
        assertNull(formatRuntimeLabel(null, ""))
        assertNull(formatRuntimeLabel(null, "   "))
        // Not a duration anyone should read as one.
        assertNull(formatRuntimeLabel(null, "0"))
        assertNull(formatRuntimeLabel(null, "0 min"))
    }

    @Test
    fun `the short form keeps whole hours bare`() {
        assertEquals("42m", formatRuntimeMinutes(42))
        assertEquals("1h", formatRuntimeMinutes(60))
        assertEquals("1h 1m", formatRuntimeMinutes(61))
        assertEquals("12h 30m", formatRuntimeMinutes(750))
    }
}
