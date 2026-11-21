package com.kennyb1201.kbstream.data.simkl

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every Simkl history write both GATES on the playback park and QUEUES what it
 * could not send (spec 2026-11-20, F1 + F2), and the paste can see the park (F3).
 *
 * The field session: three episodes completed, six
 * `tracker completion incomplete (simkl=false ...)` lines, six
 * `[tracker] completion leg failed` errors, and Simkl's quota spent for hours.
 * The cause was one class of defect - the seven write impls each called
 * `noteSimklPlaybackRateLimit` to RECORD a 429 but none read the park before
 * firing, so every retry spent a request to earn the same 429, and a completion
 * still failing when the viewer left was simply gone.
 *
 * This test is the audit rule in executable form: a `noteSimklPlaybackRateLimit`
 * site without a `simklPlaybackParkMs()` gate beside it is the defect returning.
 */
class SimklCompletionParkGateContractTest {

    private companion object {
        const val WRITES = "com/kennyb1201/kbstream/data/simkl/SimklHistoryWrites.kt"
        const val SUBMISSION = "com/kennyb1201/kbstream/data/simkl/SimklCompletionSubmission.kt"
        const val QUEUE = "com/kennyb1201/kbstream/data/simkl/SimklCompletionQueue.kt"
        const val DIAGNOSTICS = "com/kennyb1201/kbstream/data/reporting/Diagnostics.kt"
        const val APPLICATION = "com/kennyb1201/kbstream/MainApplication.kt"

        /** The seven write impls: four pushes and three removals. */
        const val WRITE_IMPLS = 7
    }

    private val sourceRoot: File by lazy { findSourceRoot() }

    private fun findSourceRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun occurrences(haystack: String, needle: String): Int {
        var count = 0
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            count++
            index = haystack.indexOf(needle, index + needle.length)
        }
        return count
    }

    @Test
    fun `every write that notes the park also checks it first`() {
        val writes = source(WRITES)

        assertEquals(
            "the seven write impls are the class this spec is about",
            WRITE_IMPLS,
            occurrences(writes, "noteSimklPlaybackRateLimit(")
        )
        assertEquals(
            "each one must read the park before it fires - this is F1",
            WRITE_IMPLS,
            occurrences(writes, "val parkedMs = simklPlaybackParkMs()")
        )
        assertEquals(
            "and each gate must say so in the log, so a paste can see the skip",
            WRITE_IMPLS,
            occurrences(writes, "skipped: parked for ")
        )
    }

    @Test
    fun `a parked push is queued and answered true, a parked removal is not queued`() {
        val writes = source(WRITES)

        assertEquals(
            "the four pushes hand the parked write to the queue",
            4,
            occurrences(writes, "return queueWhenParked && queueSimklCompletion(")
        )
        assertEquals(
            "and a 429 does the same, so the completion is not lost with the session",
            4,
            occurrences(writes, "if (rateLimited && queueWhenParked) {")
        )
        assertEquals(
            "only pushes reach the queue: the movie push queues from its gate and from " +
                "its 429, and no removal is queued anywhere - a failed removal is visible " +
                "and user-retryable",
            2,
            occurrences(writes, "kind = SimklCompletionKind.PUSH_MOVIE")
        )
        assertTrue(
            "an unmark must cancel the queued push it takes back, or the drain undoes it",
            occurrences(writes, "cancelQueuedSimklCompletions(") >= 3
        )
    }

    @Test
    fun `the drain replays through the impls without queueing itself again`() {
        val submission = source(SUBMISSION)

        assertEquals(
            "all four push impls are replayed with queueing off, or a parked drain " +
                "would requeue its own entry forever",
            4,
            occurrences(submission, "queueWhenParked = false")
        )
        assertTrue(
            "a park in force stops the pass instead of walking the queue against it",
            submission.contains("simklPlaybackParkMs()")
        )
        assertTrue(
            "a 429 mid-drain must not consume an attempt - it is a park",
            submission.contains("reopenedPark")
        )
        assertTrue(
            "the queue is profile-scoped: entries drain under the profile that made them",
            submission.contains("entry.profileId.isNotEmpty() && entry.profileId != profileAtStart")
        )
    }

    @Test
    fun `the paste can see the playback park and what is queued`() {
        val diagnostics = source(DIAGNOSTICS)
        val application = source(APPLICATION)

        assertTrue(
            "F3: the park and the completion depth travel with COPY DIAGNOSTICS",
            diagnostics.contains(
                "com.kennyb1201.kbstream.data.simkl.SimklCompletionSubmission.statusLine(app)"
            )
        )
        assertTrue(
            "and app start is a drain trigger, which is how a park that lifted " +
                "while the app was dead is delivered",
            application.contains("SimklCompletionSubmission")
        )
        assertTrue(
            "the queue's store is on disk, per profile",
            source(QUEUE).contains("ProfileStorage.prefsName")
        )
    }
}
