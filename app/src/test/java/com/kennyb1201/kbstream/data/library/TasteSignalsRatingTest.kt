package com.kennyb1201.kbstream.data.library

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The numeric rating's semantics: one signal, one source of truth.
 *
 * A thumb and a number are two editors of ONE entry, so the invariant lives in
 * the store: when a rating exists the signal is derived from it (7-10 LIKE,
 * 1-3 DISLIKE, 4-6 NONE), and a thumb pressed over a rating edits that same
 * entry through the truth table rather than beside it. Every row of that table
 * is pinned here, because the states it forbids - "liked AND rated 2" - are the
 * ones a later change would reintroduce by accident.
 *
 * The migration half is pinned too: the thumbs-only blob (`entries_v1`) is read
 * as v2 with no ratings, and a v1 blob that will not parse refuses writes
 * instead of being "migrated" into emptiness. The scale itself
 * ([TasteSignals.submissionValue]) is pure and tested without a context.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = TasteSignalsRatingTest.NoopApplication::class)
class TasteSignalsRatingTest {

    class NoopApplication : Application()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val prefs
        get() = context.getSharedPreferences(
            ProfileStorage.prefsName(context, "kbstream_taste_signals"),
            Context.MODE_PRIVATE
        )

    private val title = "Rating Title"

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
    }

    private fun rate(value: Int) = TasteSignals.setRating(
        context = context,
        mediaType = "movie",
        title = title,
        posterUrl = null,
        year = 2000,
        ids = listOf("tmdb:1"),
        rating = value
    )

    private fun thumb(signal: String) = TasteSignals.setSignal(
        context = context,
        mediaType = "movie",
        title = title,
        posterUrl = null,
        year = 2000,
        ids = listOf("tmdb:1"),
        signal = signal
    )

    private fun signal() = TasteSignals.signalFor(
        context = context,
        mediaType = "movie",
        title = title,
        year = 2000,
        ids = listOf("tmdb:1")
    )

    private fun rating() = TasteSignals.ratingFor(
        context = context,
        mediaType = "movie",
        title = title,
        year = 2000,
        ids = listOf("tmdb:1")
    )

    // ── the truth table (§1.2.2) ────────────────────────────────────────

    @Test
    fun `a high rating IS a like, a low one a dislike, a mid one neither`() {
        assertTrue(rate(9))
        assertEquals(TasteSignals.LIKE, signal())
        assertEquals(9, rating())

        assertTrue(rate(2))
        assertEquals(TasteSignals.DISLIKE, signal())
        assertEquals(2, rating())

        assertTrue("a 4 is a real rating, not a thumb", rate(4))
        assertNull("4-6 behaves as no signal for every consumer", signal())
        assertEquals(4, rating())
    }

    @Test
    fun `the derivation thresholds are exactly 7 and 3`() {
        assertEquals(TasteSignals.LIKE, TasteSignals.deriveSignal(10))
        assertEquals(TasteSignals.LIKE, TasteSignals.deriveSignal(7))
        assertEquals(TasteSignals.NONE, TasteSignals.deriveSignal(6))
        assertEquals(TasteSignals.NONE, TasteSignals.deriveSignal(4))
        assertEquals(TasteSignals.DISLIKE, TasteSignals.deriveSignal(3))
        assertEquals(TasteSignals.DISLIKE, TasteSignals.deriveSignal(1))
    }

    @Test
    fun `pressing the thumb a rating already earned clears the number too`() {
        rate(9)
        assertTrue(thumb(TasteSignals.LIKE))
        assertNull(signal())
        assertNull("the whole signal is gone, number included", rating())
    }

    @Test
    fun `a contradicting thumb drops the number and keeps its own signal`() {
        rate(9)
        assertTrue(thumb(TasteSignals.DISLIKE))
        assertEquals(TasteSignals.DISLIKE, signal())
        assertNull("the number does not survive a contradicting thumb", rating())
        assertFalse(
            "'liked and rated 9' must not be reachable: one entry, one verdict",
            TasteSignals.snapshot(context).liked.isNotEmpty() &&
                TasteSignals.snapshot(context).disliked.isNotEmpty()
        )
    }

    @Test
    fun `a thumb over a mid-range rating drops the number`() {
        rate(5)
        assertNull(signal())
        assertTrue(thumb(TasteSignals.LIKE))
        assertEquals(TasteSignals.LIKE, signal())
        assertNull(rating())
    }

    @Test
    fun `a thumb-only signal toggles exactly as the taste spec defined`() {
        assertTrue(thumb(TasteSignals.LIKE))
        assertEquals(TasteSignals.LIKE, signal())
        assertNull("a thumb never writes a rating", rating())

        assertTrue(thumb(TasteSignals.DISLIKE))
        assertEquals(TasteSignals.DISLIKE, signal())
        assertNull(rating())

        assertTrue("pressing the active thumb clears it", thumb(TasteSignals.DISLIKE))
        assertNull(signal())
        assertNull(rating())
    }

    @Test
    fun `clearSignal removes both fields`() {
        rate(10)
        assertTrue(
            TasteSignals.clearSignal(
                context = context,
                mediaType = "movie",
                title = title,
                year = 2000,
                ids = listOf("tmdb:1")
            )
        )
        assertNull(signal())
        assertNull(rating())
    }

    @Test
    fun `an out-of-range rating is a no-op, not a clamp`() {
        assertFalse("0 is not a rating", rate(0))
        assertFalse("11 is not a rating", rate(11))
        assertNull(signal())
        assertNull(rating())
        assertEquals(
            "nothing was persisted at all",
            0,
            TasteSignals.snapshot(context).ratingsByKey.size
        )
    }

    @Test
    fun `a rating set under one id flavor reads back under the other`() {
        rate(9)
        // The same title, arriving from an add-on rail: only the IMDB spelling
        // and the name are known, and the name key bridges them.
        assertEquals(
            9,
            TasteSignals.ratingFor(
                context = context,
                mediaType = "movie",
                title = title,
                year = 2000,
                ids = listOf("tt0133093")
            )
        )
        assertEquals(
            TasteSignals.LIKE,
            TasteSignals.signalFor(
                context = context,
                mediaType = "movie",
                title = title,
                year = 2000,
                ids = listOf("tt0133093")
            )
        )
    }

    @Test
    fun `the snapshot carries the ratings and the effective verdicts`() {
        rate(10)
        val rated = TasteSignals.snapshot(context)
        assertEquals(10, rated.ratingsByKey.entries.firstOrNull()?.value)
        assertTrue(
            "a 10 is a liked seed",
            // The set is the entry's key spellings, not one key per title, so
            // the seed is recognised by the id it was rated under.
            rated.liked.any { it.endsWith("tmdb:1") }
        )
        assertTrue(rated.disliked.isEmpty())

        rate(5)
        val mid = TasteSignals.snapshot(context)
        assertTrue("a 5 neither seeds nor drops", mid.liked.isEmpty() && mid.disliked.isEmpty())
        assertEquals(5, mid.ratingsByKey.entries.firstOrNull()?.value)
    }

    // ── submission value (§5.2) ─────────────────────────────────────────

    @Test
    fun `the submitted value is the rating, with the thumbs mapping as fallback`() {
        assertEquals(9, TasteSignals.submissionValue(TasteSignals.LIKE, 9))
        assertEquals("4-6 is a real rating and submits as itself", 5, TasteSignals.submissionValue(TasteSignals.NONE, 5))
        assertEquals("a thumb-only like falls back to 8", 8, TasteSignals.submissionValue(TasteSignals.LIKE, null))
        assertEquals(2, TasteSignals.submissionValue(TasteSignals.DISLIKE, null))
        assertNull("nothing to submit", TasteSignals.submissionValue(TasteSignals.NONE, null))
    }

    // ── migration (§3.4) ────────────────────────────────────────────────

    private val v1Blob = """{"entries":[{"keys":["movie::tmdb:1"],""" +
        """"title":"Rating Title","mediaType":"movie","posterUrl":null,""" +
        """"signal":"LIKE","updatedAt":5}]}"""

    @Test
    fun `the thumbs-only blob is read as v2 with no rating`() {
        prefs.edit().putString("entries_v1", v1Blob).commit()

        assertEquals(TasteSignals.LIKE, signal())
        assertNull("a pre-rating entry carries no number", rating())
        assertEquals(1, TasteSignals.snapshot(context).liked.size)
    }

    @Test
    fun `the migration persists v2 and drops v1 only on a successful write`() {
        prefs.edit().putString("entries_v1", v1Blob).commit()
        assertEquals(TasteSignals.LIKE, signal())

        assertTrue("reading alone must not rewrite the blob", prefs.getString("entries_v2", null) == null)

        assertTrue(rate(8))
        assertTrue(
            "the write lands as v2",
            prefs.getString("entries_v2", null).orEmpty().contains("\"rating\":8")
        )
        assertNull("and only then is v1 removed", prefs.getString("entries_v1", null))
        assertEquals(8, rating())
    }

    @Test
    fun `a thumbs-only blob that will not parse refuses writes and is kept`() {
        // A title of its own, deliberately: the store keeps the last blob that
        // PARSED in memory across a cleared prefs file (the last-good copy), so
        // asking here about a title another test in this class rated would let
        // that stale copy answer instead of the corrupt blob.
        val other = "Rating Title (unreadable)"
        fun otherSignal(): String? = TasteSignals.signalFor(
            context = context,
            mediaType = "movie",
            title = other,
            year = 2000,
            ids = listOf("tmdb:999")
        )

        val corrupt = """{"entries":[{"keys":"""
        prefs.edit().putString("entries_v1", corrupt).commit()

        assertNull("an unreadable blob must not read as a signal", otherSignal())
        assertFalse("no write may replace a blob this build cannot read", rate(9))
        assertFalse(thumb(TasteSignals.LIKE))
        assertEquals(corrupt, prefs.getString("entries_v1", null))
        assertNull("nothing was migrated over it", prefs.getString("entries_v2", null))
    }

    @Test
    fun `a v2 blob is never re-read from v1`() {
        prefs.edit().putString("entries_v1", v1Blob).commit()
        assertTrue(rate(6))

        // A stale v1 blob reappearing (a restored backup, say) must not win over
        // the v2 blob that has since been written.
        prefs.edit().putString("entries_v1", v1Blob).commit()
        assertNull("the v2 answer stands: a 6 is not a like", signal())
        assertEquals(6, rating())
    }
}
