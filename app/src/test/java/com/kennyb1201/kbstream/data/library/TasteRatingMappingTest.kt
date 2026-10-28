package com.kennyb1201.kbstream.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one mapping both trackers rate through, and the store-key id extraction
 * the submission legs are addressed with.
 *
 * The scale is the reason there is only one table: Simkl and Trakt both rate on
 * an integer 1-10, the same scale the local store records, so a local rating and
 * a submitted one are the same number with no conversion. The three pinned
 * values are 8 (a like - strong, but leaving 9-10 for an explicit numeric
 * rating), 2 (its mirror) and a REMOVAL for a cleared signal, which is
 * deliberately not a low rating.
 */
class TasteRatingMappingTest {

    // ── the mapping ──────────────────────────────────────────────────

    @Test
    fun `a like submits 8 and a dislike 2`() {
        assertEquals(8, TasteRatingMapping.thumbToRating(TasteSignals.LIKE))
        assertEquals(2, TasteRatingMapping.thumbToRating(TasteSignals.DISLIKE))
        assertEquals(8, TasteRatingMapping.LIKE_RATING)
        assertEquals(2, TasteRatingMapping.DISLIKE_RATING)
    }

    @Test
    fun `a signal with no thumb has nothing to map`() {
        assertNull(TasteRatingMapping.thumbToRating(TasteSignals.NONE))
        assertNull(TasteRatingMapping.thumbToRating(null))
        assertNull(TasteRatingMapping.thumbToRating("SOMETHING ELSE"))
    }

    @Test
    fun `an out-of-range number is clamped into the scale, never passed through raw`() {
        assertEquals(1, TasteRatingMapping.clamp(0))
        assertEquals(1, TasteRatingMapping.clamp(-5))
        assertEquals(10, TasteRatingMapping.clamp(11))
        assertEquals(10, TasteRatingMapping.clamp(999))
        assertEquals(5, TasteRatingMapping.clamp(5))
        assertEquals(TasteSignals.RATING_MIN, TasteRatingMapping.clamp(Int.MIN_VALUE))
        assertEquals(TasteSignals.RATING_MAX, TasteRatingMapping.clamp(Int.MAX_VALUE))
    }

    // ── the value a removal body carries ─────────────────────────────

    @Test
    fun `a removal sends the value the title carried, or its thumb's mapping`() {
        // The last number the cleared entry held.
        assertEquals(5, TasteRatingMapping.removalWireRating(5, TasteSignals.NONE))
        // A thumb-only signal never held a number: its own mapping stands in.
        assertEquals(8, TasteRatingMapping.removalWireRating(null, TasteSignals.LIKE))
        assertEquals(2, TasteRatingMapping.removalWireRating(null, TasteSignals.DISLIKE))
        // Nothing to remember at all: the last resort, and still in range.
        assertEquals(
            TasteRatingMapping.LIKE_RATING,
            TasteRatingMapping.removalWireRating(null, TasteSignals.NONE)
        )
    }

    @Test
    fun `a removal's presence value is clamped like any other`() {
        assertEquals(10, TasteRatingMapping.removalWireRating(44, TasteSignals.NONE))
        assertEquals(1, TasteRatingMapping.removalWireRating(-3, TasteSignals.NONE))
    }

    // ── timestamps ───────────────────────────────────────────────────

    @Test
    fun `a signal timestamp becomes the ISO-8601 UTC the trackers want`() {
        // 1_793_104_496_000 ms is 2026-10-27T12:34:56Z.
        assertEquals("2026-10-27T12:34:56Z", TasteRatingMapping.ratedAtIso(1_793_104_496_000L))
        assertTrue(
            "to the second, no sub-second noise",
            TasteRatingMapping.ratedAtIso(1_793_104_496_789L).orEmpty().endsWith("56Z")
        )
    }

    @Test
    fun `a timestamp the store never recorded is sent as nothing`() {
        assertNull(TasteRatingMapping.ratedAtIso(0L))
        assertNull(TasteRatingMapping.ratedAtIso(-1L))
    }

    // ── the bucket ───────────────────────────────────────────────────

    @Test
    fun `the show bucket is every non-movie spelling`() {
        assertTrue(TasteRatingMapping.isMovie("movie"))
        assertFalse(TasteRatingMapping.isMovie("series"))
        assertFalse(TasteRatingMapping.isMovie("tv"))
        assertFalse(TasteRatingMapping.isMovie("show"))
        assertFalse(TasteRatingMapping.isMovie(null))
    }

    // ── reading ids back out of the store's keys ─────────────────────

    @Test
    fun `both id spellings are read out of one key set`() {
        // What the store writes for a title it knew under both flavors.
        val keys = listOf("movie::tmdb:603", "id::tt0133093", "title::movie::fightclub::1999")
        assertEquals("tt0133093", TasteRatingMapping.imdbIdOf(keys))
        assertEquals(603, TasteRatingMapping.tmdbIdOf(keys))
    }

    @Test
    fun `a tmdb-only title yields its tmdb id and no imdb one`() {
        val keys = listOf("series::tmdb:1399")
        assertNull(TasteRatingMapping.imdbIdOf(keys))
        assertEquals(1399, TasteRatingMapping.tmdbIdOf(keys))
    }

    @Test
    fun `an add-on-only title yields its imdb id and no tmdb one`() {
        val keys = listOf("id::tt0133093")
        assertEquals("tt0133093", TasteRatingMapping.imdbIdOf(keys))
        assertNull(TasteRatingMapping.tmdbIdOf(keys))
    }

    @Test
    fun `a title the store knows by name alone is addressed with no id at all`() {
        // The name key carries no id, and a name is not something either tracker
        // can be addressed with - the legs refuse it before any request.
        val keys = listOf("title::movie::fightclub::1999")
        assertNull(TasteRatingMapping.imdbIdOf(keys))
        assertNull(TasteRatingMapping.tmdbIdOf(keys))
        assertNull(TasteRatingMapping.imdbIdOf(emptyList()))
        assertNull(TasteRatingMapping.tmdbIdOf(emptyList()))
    }

    @Test
    fun `a name that looks like an id is never read as one`() {
        // normalizeTitle strips punctuation, so a title can never smuggle the
        // "tmdb:" marker into its own key... but the guard is worth pinning
        // anyway: only the id prefixes answer.
        val keys = listOf("title::movie::tmdb603::2021", "id::tt1")
        assertEquals("tt1", TasteRatingMapping.imdbIdOf(keys))
        assertNull(TasteRatingMapping.tmdbIdOf(keys))
    }
}
