package com.kennyb1201.kbstream.data.library

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The submission queue's storage half: coalescing as it actually lands on disk,
 * and the corrupt-blob discipline it shares with the taste store.
 *
 * The rules are unit-tested next door ([TasteRatingQueueRulesTest]); this is the
 * half that needs a Context, and it pins the two things a pure test cannot:
 *
 *  - a like -> dislike flip while the like is still queued leaves ONE row in the
 *    blob, and a clear supersedes a pending submit as a REMOVAL;
 *  - a blob this build cannot parse is NOT an empty queue - it is served from the
 *    last copy that parsed, and every write refuses. An unreadable queue that
 *    read as "nothing pending" would silently drop the viewer's ratings, and one
 *    that read as writable would let the next press overwrite the record of the
 *    last few.
 *
 * Profile isolation is structural: the store is opened through
 * [ProfileStorage.prefsName], the same namespacing every other per-profile store
 * uses, so entries queued on one profile drain only while that profile is active.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = TasteRatingQueueStoreTest.NoopApplication::class)
class TasteRatingQueueStoreTest {

    class NoopApplication : Application()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val prefs
        get() = context.getSharedPreferences(
            ProfileStorage.prefsName(context, "kbstream_taste_rating_queue"),
            Context.MODE_PRIVATE
        )

    private val blobKey = "taste_rating_queue_v1"

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
    }

    private fun enqueue(
        tracker: TasteRatingTracker = TasteRatingTracker.TRAKT,
        keys: List<String> = listOf("movie::tmdb:603"),
        title: String = "Fight Club",
        rating: Int? = TasteRatingMapping.LIKE_RATING,
        lastRating: Int? = null,
        signal: String = TasteSignals.LIKE,
        atMs: Long = 1_000L
    ): Boolean = TasteRatingQueue.enqueue(
        context = context,
        tracker = tracker,
        keys = keys,
        mediaType = "movie",
        title = title,
        rating = rating,
        lastRating = lastRating,
        signal = signal,
        atMs = atMs
    )

    @Test
    fun `a like then a dislike leaves one pending row, at the dislike`() {
        assertTrue(enqueue(rating = 8, signal = TasteSignals.LIKE, atMs = 1_000L))
        assertTrue(enqueue(rating = 2, signal = TasteSignals.DISLIKE, atMs = 2_000L))

        val pending = TasteRatingQueue.entries(context)
        assertEquals("coalescing is the whole point of the queue", 1, pending.size)
        assertEquals(2, pending.single().rating)
        assertFalse(pending.single().removal)
        assertEquals(TasteSignals.DISLIKE, pending.single().signal)
        assertEquals("the row is the newest signal's", 2_000L, pending.single().enqueuedAt)
    }

    @Test
    fun `a clear replaces the pending submit with a removal`() {
        enqueue(rating = 8, signal = TasteSignals.LIKE)

        // What the store hands over on a clear: no write value, and the number
        // the entry carried before, purely for the remove body's presence value.
        enqueue(rating = null, lastRating = 8, signal = TasteSignals.NONE)

        val pending = TasteRatingQueue.entries(context)
        assertEquals(1, pending.size)
        assertTrue(pending.single().removal)
        assertEquals(8, pending.single().rating)
    }

    @Test
    fun `a thumb-only clear still carries a value for the remove body`() {
        // A thumb-only like: the store applies the mapping at submit time and
        // never writes a rating into the entry, so the clear has no number to
        // remember and the thumb's own mapping stands in.
        enqueue(rating = 8, signal = TasteSignals.LIKE)
        enqueue(rating = null, lastRating = null, signal = TasteSignals.LIKE)

        val pending = TasteRatingQueue.entries(context)
        assertEquals(1, pending.size)
        assertTrue(pending.single().removal)
        assertEquals(8, pending.single().rating)
    }

    @Test
    fun `two titles queue independently, and so do the two legs`() {
        enqueue(keys = listOf("movie::tmdb:603"), title = "Fight Club")
        enqueue(keys = listOf("series::tmdb:1399"), title = "The Wire")
        assertEquals(2, TasteRatingQueue.entries(context).size)

        // The same title on the other tracker is its own entry with its own
        // attempts and its own disable state.
        enqueue(tracker = TasteRatingTracker.SIMKL)
        val pending = TasteRatingQueue.entries(context)
        assertEquals(3, pending.size)
        assertEquals(
            "the same title on the other tracker is its OWN row",
            1,
            pending.count { it.tracker == TasteRatingTracker.SIMKL.name }
        )
        assertEquals(
            "the title on both legs, once per tracker",
            listOf("Fight Club", "Fight Club", "The Wire").sorted(),
            pending.map { it.title }.sorted()
        )
    }

    @Test
    fun `a disconnect drops one leg's entries and only that leg's`() {
        enqueue(tracker = TasteRatingTracker.TRAKT, keys = listOf("movie::tmdb:603"))
        enqueue(tracker = TasteRatingTracker.SIMKL, keys = listOf("movie::tmdb:603"))
        enqueue(tracker = TasteRatingTracker.SIMKL, keys = listOf("series::tmdb:1399"), title = "The Wire")

        assertEquals(2, TasteRatingQueue.clearTracker(context, TasteRatingTracker.SIMKL))

        val kept = TasteRatingQueue.entries(context)
        assertEquals(1, kept.size)
        assertEquals(TasteRatingTracker.TRAKT.name, kept.single().tracker)
        assertEquals("Fight Club", kept.single().title)
    }

    @Test
    fun `attempts advance one step at a time and the cap drops the entry`() {
        enqueue()

        repeat(5) { index ->
            val entry = TasteRatingQueue.entries(context).single()
            assertTrue(
                "attempt ${index + 1} may still be retried",
                TasteRatingQueue.markAttempt(context, entry, 10_000L + index, "did not answer")
            )
        }
        val spent = TasteRatingQueue.entries(context).single()
        assertEquals(5, spent.attempts)
        assertEquals("did not answer", spent.lastError)

        // The cap: dropped rather than tried a sixth time, with the reason left
        // behind so COPY DIAGNOSTICS can still name it.
        assertFalse(TasteRatingQueue.markAttempt(context, spent, 99_000L, "did not answer"))
        assertTrue(TasteRatingQueue.entries(context).isEmpty())
        val failure = TasteRatingQueue.failures(context)[TasteRatingTracker.TRAKT.name]
        assertNotNull(failure)
        assertEquals(99_000L, failure!!.at)
    }

    @Test
    fun `an unreadable blob is served from the last good copy, and cannot be written over`() {
        enqueue()
        assertEquals(1, TasteRatingQueue.entries(context).size)

        val corrupt = """{"pending":[{"tracker":"""
        prefs.edit().putString(blobKey, corrupt).commit()

        assertEquals(
            "a blob this build cannot parse must not read as an empty queue",
            1,
            TasteRatingQueue.entries(context).size
        )
        assertFalse(
            "and it must refuse to write, so the last readable copy survives",
            enqueue(rating = 2, signal = TasteSignals.DISLIKE)
        )
        assertEquals(
            "the refused write left the stored blob exactly as it was",
            corrupt,
            prefs.getString(blobKey, null)
        )
    }

    @Test
    fun `a title with no usable key is refused rather than queued nameless`() {
        assertFalse(enqueue(keys = emptyList()))
        assertTrue(TasteRatingQueue.entries(context).isEmpty())
        assertNull(prefs.getString(blobKey, null))
    }

    @Test
    fun `the queue persists under the pinned v1 blob key`() {
        // This helper file IS the profile-scoped one (see the class comment), so
        // a write visible here is a write visible to the store's own reader.
        enqueue()
        val raw = prefs.getString(blobKey, null)
        assertNotNull("the blob key is the pinned one", raw)
        assertTrue("and it is the queue's own store shape", raw!!.contains("\"pending\""))
    }
}
