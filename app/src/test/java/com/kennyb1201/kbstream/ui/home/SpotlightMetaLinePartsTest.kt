package com.kennyb1201.kbstream.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Expanded card's meta line (spec 2026-11-15).
 *
 * A series panel gains its seasons and episodes in the LENGTH slot - the same
 * order the Detail page's own line uses - and a movie panel (or a series TMDB has
 * no counts for) is exactly today's line. The failure this pins is quiet: an
 * empty part taking a slot leaves a doubled separator that reads as a missing
 * value, and the counts landing after the runtime is a different line than the
 * one the spec asks for.
 */
class SpotlightMetaLinePartsTest {

    @Test
    fun `a series line carries the counts in the length slot`() {
        assertEquals(
            "S1: year, then the counts, then the runtime, rating and genre",
            "2014 \u00b7 8 Seasons \u00b7 164 Episodes \u00b7 1h 12min \u00b7 \u2605 8.3 \u00b7 Drama",
            spotlightMetaLineParts(
                year = "2014",
                length = "8 Seasons \u00b7 164 Episodes",
                runtime = "1h 12min",
                rating = "8.3",
                genre = "Drama"
            )
        )
    }

    @Test
    fun `a movie line is exactly today's line`() {
        assertEquals(
            "S2: no counts, nothing reordered, no empty slot",
            "2014 \u00b7 2h 10min \u00b7 \u2605 8.3 \u00b7 Drama",
            spotlightMetaLineParts(
                year = "2014",
                length = null,
                runtime = "2h 10min",
                rating = "8.3",
                genre = "Drama"
            )
        )
    }

    @Test
    fun `a blank count is skipped, not joined`() {
        assertEquals(
            "S3: a series TMDB has no counts for must not leave a gap",
            "2014 \u00b7 45min \u00b7 Drama",
            spotlightMetaLineParts(
                year = "2014",
                length = "   ",
                runtime = "45min",
                rating = null,
                genre = "Drama"
            )
        )
        assertEquals(
            "an empty string is the same answer as null",
            "2014 \u00b7 Drama",
            spotlightMetaLineParts(
                year = "2014",
                length = "",
                runtime = null,
                rating = null,
                genre = "Drama"
            )
        )
    }

    @Test
    fun `the counts keep the length slot even when the year is missing`() {
        assertEquals(
            "4 Seasons \u00b7 40 Episodes \u00b7 Drama",
            spotlightMetaLineParts(
                year = null,
                length = "4 Seasons \u00b7 40 Episodes",
                runtime = null,
                rating = null,
                genre = "Drama"
            )
        )
    }

    @Test
    fun `the rating keeps its star`() {
        assertEquals(
            "the star is the line's only decoration and it stays on the rating",
            "\u2605 7.1",
            spotlightMetaLineParts(
                year = null,
                length = null,
                runtime = null,
                rating = "7.1",
                genre = null
            )
        )
    }

    @Test
    fun `nothing at all is an empty line, not separators`() {
        assertEquals(
            "",
            spotlightMetaLineParts(
                year = null,
                length = null,
                runtime = null,
                rating = null,
                genre = null
            )
        )
    }
}
