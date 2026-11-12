package com.kennyb1201.kbstream.data.library

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The taste line's CAUSE: why the ratings gave up, not just that they did
 * (spec 2026-11-12).
 *
 * The field paste could only say `Simkl: on queued=15 last error gave up after
 * 5 attempts at 2026-11-12 12:48:09` - the terminal status - so a leg that
 * failed for an HTTP code, a timeout or no network all read the same, and both
 * legs landing on the same second pointed at a shared cause nobody could name.
 * The cause existed all along in `TasteRatingQueueEntry.lastError`, and died at
 * the exact moment it was recorded because the drop deletes the entry.
 *
 * These are storage-and-rendering tests, so they need a Context:
 *
 *  - the cause is recorded IN the failure record, so it survives the drop (S2);
 *  - the paste appends it after the status and timestamp (S1);
 *  - a failure with no cause renders byte-identically to before (S3);
 *  - a newline or a giant body cannot break the paste's one-line shape.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = TasteRatingCausePasteTest.NoopApplication::class)
class TasteRatingCausePasteTest {

    class NoopApplication : Application()

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val prefs
        get() = context.getSharedPreferences(
            ProfileStorage.prefsName(context, "kbstream_taste_rating_queue"),
            Context.MODE_PRIVATE
        )

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
    }

    private fun enqueue(tracker: TasteRatingTracker = TasteRatingTracker.SIMKL) {
        TasteRatingQueue.enqueue(
            context = context,
            tracker = tracker,
            keys = listOf("movie::tmdb:603"),
            mediaType = "movie",
            title = "Fight Club",
            rating = TasteRatingMapping.LIKE_RATING,
            lastRating = null,
            signal = TasteSignals.LIKE,
            atMs = 1_000L
        )
    }

    /** What the drain does at the cap: drop, naming the status and the cause. */
    private fun giveUp(tracker: TasteRatingTracker = TasteRatingTracker.SIMKL) {
        val entry = TasteRatingQueue.entries(context).single { it.tracker == tracker.name }
        TasteRatingQueue.dropEntry(
            context = context,
            entry = entry,
            atMs = 99_000L,
            message = "gave up after ${entry.attempts} attempts",
            cause = entry.lastError.orEmpty()
        )
    }

    private fun tasteLine(): String =
        TasteRatingSubmission.statusLines(context).single()

    // ── S2: the cause outlives the entry it came from ────────────────────

    @Test
    fun `the cause survives the drop because it lives in the failure record`() {
        enqueue()
        repeat(5) { i ->
            // Re-read each round: markAttempt writes the new attempt count.
            val attempt = TasteRatingQueue.entries(context).single()
            TasteRatingQueue.markAttempt(context, attempt, 10_000L + i, "HTTP 401")
        }
        val spent = TasteRatingQueue.entries(context).single()
        assertEquals("HTTP 401", spent.lastError)

        giveUp()

        assertTrue("the entry is gone", TasteRatingQueue.entries(context).isEmpty())
        val failure = TasteRatingQueue.failures(context).getValue(TasteRatingTracker.SIMKL.name)
        assertEquals(99_000L, failure.at)
        assertEquals("gave up after 5 attempts", failure.message)
        assertEquals("the underlying error, kept past the drop", "HTTP 401", failure.cause)
    }

    // ── S1: the paste names it ───────────────────────────────────────────

    @Test
    fun `the paste appends the cause after the status and the timestamp`() {
        enqueue()
        TasteRatingQueue.markAttempt(
            context,
            TasteRatingQueue.entries(context).single(),
            10_000L,
            "HTTP 401"
        )
        giveUp()

        val line = tasteLine()
        assertTrue("the terminal status is still there: $line", line.contains("last error gave up"))
        val status = line.indexOf("last error gave up")
        val at = line.indexOf(" at ", status)
        val cause = line.indexOf("(cause: HTTP 401)", at)
        assertTrue("the cause follows the timestamp: $line", cause > at)
        assertTrue(
            "and it closes the leg's part of the line: $line",
            cause < line.indexOf(" \u00b7 ", cause).let { if (it < 0) line.length else it }
        )
    }

    // ── S3: no cause, no change ─────────────────────────────────────────

    @Test
    fun `a failure with no stored cause renders exactly as it did`() {
        enqueue()
        val entry = TasteRatingQueue.entries(context).single()
        TasteRatingQueue.dropEntry(
            context = context,
            entry = entry,
            atMs = 99_000L,
            message = "gave up after 5 attempts"
        )

        val line = tasteLine()
        assertTrue(line, line.contains("last error gave up after 5 attempts at "))
        assertFalse("no empty parenthetical: $line", line.contains("(cause:"))
    }

    @Test
    fun `a clean report still carries no taste line at all`() {
        // A leg gated off is named on purpose ("my ratings never reach Simkl"),
        // so the clean case only exists while every leg is open.
        org.junit.Assume.assumeTrue(TasteRatingSubmission.anyEnabled())
        assertEquals(emptyList<String>(), TasteRatingSubmission.statusLines(context))
    }

    // ── the line's shape: one line, readable ────────────────────────────

    @Test
    fun `a newline or a giant body cannot break the paste's one-line shape`() {
        enqueue()
        val entry = TasteRatingQueue.entries(context).single()
        TasteRatingQueue.dropEntry(
            context = context,
            entry = entry,
            atMs = 99_000L,
            message = "gave up after 5 attempts",
            cause = "HTTP 500\n" + "x".repeat(500)
        )

        val cause = TasteRatingQueue.failures(context)
            .getValue(TasteRatingTracker.SIMKL.name).cause
        val rendered = TasteRatingSubmission.failureCause(cause)
        assertFalse("a newline would split the taste line in two", rendered.contains("\n"))
        assertTrue("capped at about a line's worth: ${rendered.length}", rendered.length <= 80)
        assertTrue("and the fact it was cut is visible", rendered.endsWith("\u2026"))

        val line = tasteLine()
        assertEquals("one paste line, whatever the error said", 1, line.lines().size)
    }

    @Test
    fun `a blank cause is blank, never the word null`() {
        assertEquals("", TasteRatingSubmission.failureCause(""))
        assertEquals("", TasteRatingSubmission.failureCause("   \n  "))
        assertEquals("", TasteRatingSubmission.failureCause(" null "))
    }

    // ── the wiring the storage test cannot reach ────────────────────────

    @Test
    fun `the drain captures the entry's error before it drops it`() {
        val submission = source(
            "com/kennyb1201/kbstream/data/library/TasteRatingSubmission.kt"
        )
        val spent = submission.substringAfter("if (TasteRatingQueueRules.isSpent(entry)) {")
            .substringBefore("if (!TasteRatingQueueRules.isDue(entry, now))")
        assertTrue(
            "the cause comes from the entry, which is deleted by the call: $spent",
            spent.contains("cause = entry.lastError.orEmpty()")
        )
        assertTrue(
            "and the log line still carries it too",
            spent.contains("(\${entry.lastError ?: \"no detail\"})")
        )
        assertTrue(
            "the status itself is unchanged",
            spent.contains("\"gave up after \${entry.attempts} attempts\"")
        )
        // The other two failure write points keep their old shape: each already
        // stores the error as its MESSAGE, so a cause there would print the same
        // text twice.
        val queue = source("com/kennyb1201/kbstream/data/library/TasteRatingQueue.kt")
        assertTrue(
            "markAttempt's cap branch records the attempt's own error as the message",
            queue.contains("(entry.tracker to LegFailure(atMs, message.orEmpty()))")
        )
        assertTrue(
            "and the leg-level failure does the same",
            queue.contains("(tracker to LegFailure(atMs, message.orEmpty()))")
        )
        assertEquals(
            "while a drop carries a cause of its own",
            1,
            count(queue, "LegFailure(atMs, message, cause)")
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun count(haystack: String, needle: String): Int =
        if (needle.isEmpty()) 0 else haystack.split(needle).size - 1

    private fun source(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) {
                    val file = File(candidate, relative)
                    assertTrue("source missing: $file", file.isFile)
                    return file.readText()
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError("main source root not found")
    }
}
