package com.kennyb1201.kbstream.data.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The submission queue's arithmetic, without Android or a network.
 *
 * Two rules carry the whole design and both are pinned here:
 *
 *  - **Coalescing.** A newer signal for the SAME title on the SAME tracker
 *    replaces the pending one in place, so a like -> dislike flip while the like
 *    is still queued sends only the dislike, a clear supersedes a pending submit,
 *    and no stale flip is ever sent after a newer one. The replacement is fresh,
 *    which is what keeps a superseded submit's attempt history from following the
 *    signal that replaced it.
 *  - **Bounded retry.** 1 min / 5 min / 30 min / 2 h / 12 h between attempts, a
 *    cap of five, and then the entry is DROPPED rather than tried a sixth time.
 *    Nothing in this object counts a server-requested pause (a 429, a spent
 *    budget, a revoked grant) as an attempt - that decision lives in the drain,
 *    and the schedule here only advances on a real failure.
 */
class TasteRatingQueueRulesTest {

    private fun entry(
        tracker: TasteRatingTracker = TasteRatingTracker.TRAKT,
        keys: List<String> = listOf("movie::tmdb:603"),
        title: String = "Fight Club",
        rating: Int = TasteRatingMapping.LIKE_RATING,
        removal: Boolean = false,
        signal: String = TasteSignals.LIKE,
        enqueuedAt: Long = 1_000L,
        attempts: Int = 0,
        lastAttemptAt: Long = 0L,
        lastError: String? = null
    ) = TasteRatingQueueEntry(
        tracker = tracker.name,
        keys = keys,
        mediaType = "movie",
        title = title,
        rating = rating,
        removal = removal,
        signal = signal,
        enqueuedAt = enqueuedAt,
        attempts = attempts,
        lastAttemptAt = lastAttemptAt,
        lastError = lastError
    )

    // ── the schedule ─────────────────────────────────────────────────

    @Test
    fun `the waits are one minute, five, thirty, two hours, twelve - then the cap`() {
        assertEquals(
            listOf(60_000L, 300_000L, 1_800_000L, 7_200_000L, 43_200_000L),
            TasteRatingQueueRules.BACKOFF_MS
        )
        assertEquals(5, TasteRatingQueueRules.MAX_ATTEMPTS)
        assertEquals("the wait before a first retry", 60_000L, TasteRatingQueueRules.backoffFor(1))
        assertEquals(300_000L, TasteRatingQueueRules.backoffFor(2))
        assertEquals(1_800_000L, TasteRatingQueueRules.backoffFor(3))
        assertEquals(7_200_000L, TasteRatingQueueRules.backoffFor(4))
        assertEquals(43_200_000L, TasteRatingQueueRules.backoffFor(5))
        assertEquals("past the cap the last step stands", 43_200_000L, TasteRatingQueueRules.backoffFor(9))
        assertEquals("nothing has waited before the first attempt", 60_000L, TasteRatingQueueRules.backoffFor(0))
    }

    @Test
    fun `a fresh entry is due at once and an attempted one waits its step`() {
        val fresh = entry()
        assertEquals(0L, TasteRatingQueueRules.dueAt(fresh))
        assertTrue(TasteRatingQueueRules.isDue(fresh, 0L))

        val once = entry(attempts = 1, lastAttemptAt = 10_000L)
        assertEquals(70_000L, TasteRatingQueueRules.dueAt(once))
        assertFalse("not a millisecond early", TasteRatingQueueRules.isDue(once, 69_999L))
        assertTrue(TasteRatingQueueRules.isDue(once, 70_000L))

        val thrice = entry(attempts = 3, lastAttemptAt = 10_000L)
        assertEquals("the third wait is 30 minutes", 1_810_000L, TasteRatingQueueRules.dueAt(thrice))
        assertFalse(TasteRatingQueueRules.isDue(thrice, 1_809_999L))
    }

    @Test
    fun `the cap is spent at five and the fifth failure drops the entry`() {
        assertFalse(TasteRatingQueueRules.isSpent(entry(attempts = 4)))
        assertTrue(TasteRatingQueueRules.isSpent(entry(attempts = 5)))

        val updated = TasteRatingQueueRules.afterFailure(entry(), 50_000L, "did not answer")
        assertNotNull(updated)
        assertEquals(1, updated!!.attempts)
        assertEquals(50_000L, updated.lastAttemptAt)
        assertEquals("did not answer", updated.lastError)

        assertNull(
            "a spent entry is dropped, never tried a sixth time",
            TasteRatingQueueRules.afterFailure(entry(attempts = 5), 50_000L, "did not answer")
        )
    }

    // ── coalescing ───────────────────────────────────────────────────

    @Test
    fun `a like followed by a dislike leaves one entry, carrying the dislike`() {
        val pending = listOf(entry(rating = 8, signal = TasteSignals.LIKE))
        val flipped = entry(rating = 2, signal = TasteSignals.DISLIKE, enqueuedAt = 2_000L)

        val after = TasteRatingQueueRules.coalesce(pending, flipped)

        assertEquals("one title on one tracker is one entry, always", 1, after.size)
        assertEquals(2, after.single().rating)
        assertFalse(after.single().removal)
        assertEquals(TasteSignals.DISLIKE, after.single().signal)
    }

    @Test
    fun `a replacement inherits nothing from the submit it supersedes`() {
        val tried = entry(attempts = 3, lastAttemptAt = 900_000L, lastError = "did not answer")

        val after = TasteRatingQueueRules.coalesce(
            listOf(tried),
            entry(rating = 2, signal = TasteSignals.DISLIKE)
        )

        assertEquals(0, after.single().attempts)
        assertEquals(0L, after.single().lastAttemptAt)
        assertNull("the old failure must not caption the new signal", after.single().lastError)
        assertTrue(
            "and it is due at once rather than waiting out the old entry's backoff",
            TasteRatingQueueRules.isDue(after.single(), 0L)
        )
    }

    @Test
    fun `a clear supersedes a pending submit as a removal, not as a low rating`() {
        val after = TasteRatingQueueRules.coalesce(
            listOf(entry(rating = 8, signal = TasteSignals.LIKE)),
            entry(
                rating = TasteRatingMapping.removalWireRating(8, TasteSignals.NONE),
                removal = true,
                signal = TasteSignals.NONE
            )
        )

        assertEquals(1, after.size)
        assertTrue(after.single().removal)
    }

    @Test
    fun `two titles on one tracker queue independently`() {
        val pending = listOf(entry(keys = listOf("movie::tmdb:603")))

        val after = TasteRatingQueueRules.coalesce(
            pending,
            entry(keys = listOf("series::tmdb:1399"), title = "The Wire", rating = 2)
        )

        assertEquals(2, after.size)
        assertEquals(listOf("Fight Club", "The Wire"), after.map { it.title })
    }

    @Test
    fun `the same title on two trackers is two entries, never one`() {
        val onTrakt = entry(tracker = TasteRatingTracker.TRAKT)
        val onSimkl = entry(tracker = TasteRatingTracker.SIMKL)

        val after = TasteRatingQueueRules.coalesce(listOf(onTrakt), onSimkl)

        assertEquals("the legs are independent in the queue as everywhere else", 2, after.size)
        assertEquals(
            listOf(TasteRatingTracker.TRAKT, TasteRatingTracker.SIMKL),
            after.mapNotNull { it.trackerOrNull }
        )
    }

    @Test
    fun `twin spellings are one title and unrelated ones are not`() {
        // What the store holds for a title it knew under both flavors: the same
        // title signalled from a TMDB rail and from an add-on rail shares the
        // `tt...` spelling, and that overlap is what makes it one title.
        assertTrue(
            TasteRatingQueueRules.sharesAKey(
                listOf("id::tt0133093"),
                listOf("movie::tmdb:603", "id::tt0133093")
            )
        )
        assertTrue(
            TasteRatingQueueRules.sharesAKey(
                listOf("movie::tmdb:603", "id::tt0133093"),
                listOf("id::tt0133093")
            )
        )
        assertFalse(
            "no shared spelling is a different title",
            TasteRatingQueueRules.sharesAKey(listOf("movie::tmdb:603"), listOf("id::tt0133093"))
        )
        assertFalse(
            "an empty key set names nothing, so it matches nothing",
            TasteRatingQueueRules.sharesAKey(emptyList(), listOf("id::tt0133093"))
        )
        assertFalse(TasteRatingQueueRules.sharesAKey(listOf("id::tt0133093"), emptyList()))
    }

    @Test
    fun `an entry knows its tracker, and a blob older than a rename is survived`() {
        assertEquals(TasteRatingTracker.SIMKL, entry(tracker = TasteRatingTracker.SIMKL).trackerOrNull)
        assertEquals(TasteRatingTracker.TRAKT, entry().trackerOrNull)
        assertNull(
            "a stored name this build does not know is skipped, not crashed on",
            entry().copy(tracker = "MDBLIST").trackerOrNull
        )
        assertEquals("Simkl", TasteRatingTracker.SIMKL.label)
        assertEquals("Trakt", TasteRatingTracker.TRAKT.label)
        assertNull(TasteRatingTracker.from(null))
    }

    @Test
    fun `a removal records the value the cleared title carried, thumb-only included`() {
        // The remove request shares its shape with an add, so it still needs a
        // number: the honest one is what the title carried before the clear.
        val carried = entry(rating = 5, removal = true, signal = TasteSignals.NONE)
        assertEquals(5, carried.rating)
        assertTrue(carried.removal)

        val thumbOnly = entry(
            rating = TasteRatingMapping.removalWireRating(null, TasteSignals.LIKE),
            removal = true,
            signal = TasteSignals.LIKE
        )
        assertEquals(8, thumbOnly.rating)
    }
}
