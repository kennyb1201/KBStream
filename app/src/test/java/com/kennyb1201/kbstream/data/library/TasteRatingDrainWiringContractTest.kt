package com.kennyb1201.kbstream.data.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ratings queue is drained by the app's real moments, and a park waits on a
 * timer.
 *
 * Spec 2026-11-18, taste-ratings drain (residual gaps). The audit sweep's H1/H2/H3
 * are in main already; this pins what the sweep left behind:
 *
 *  - F1: the "Home load" trigger was wired into `refreshAllHomeData`, which has
 *    no production caller - the live paths are `onHomeResumed` and the
 *    profile-switch observer;
 *  - F2: a grant adopted from sync or rotated by a token refresh never went
 *    through a connect screen, so nothing drained behind it;
 *  - F3: a 429 park or a not-yet-due backoff scheduled nothing at all;
 *  - F4: the paste's park line claimed "next trigger" for a park that has a wake.
 *
 * Source pins, because the drain's legs need Android.
 */
class TasteRatingDrainWiringContractTest {

    private val submission = source(SUBMISSION)
    private val home = source(HOME_VM)
    private val worker = source(WORKER)

    // ── F1: the Home paths that actually run ─────────────────────────────

    @Test
    fun `the dead Home-load trigger is gone and the live ones carry it`() {
        val refreshAll = block(home, "fun refreshAllHomeData()")
        assertFalse(
            "the drain is not fired from a function with no production caller",
            refreshAll.contains("TasteRatingSubmission\n") ||
                refreshAll.contains("requestDrain(getApplication())")
        )
        val resumed = block(home, "fun onHomeResumed()")
        assertTrue(
            "Home (re)entry asks for a drain, unconditionally - before the " +
                "conditional rebuild, which is the bug this replaces",
            resumed.contains("TasteRatingSubmission\n            .requestDrain(getApplication())") &&
                resumed.indexOf("requestDrain(getApplication())") <
                resumed.indexOf("val currentHideUpcoming =")
        )
        val switch = block(home, "rebuildRailsForActiveProfile()")
        assertTrue(
            "and the switch observer drains the INCOMING profile, after the switch " +
                "landed",
            switch.contains("requestDrain(getApplication())") &&
                switch.indexOf("refreshTopPicks()") < switch.indexOf("requestDrain(")
        )
    }

    @Test
    fun `the drain is still the same profile-safe object`() {
        assertTrue(
            "one implementation, shared by both surfaces",
            submission.contains("object TasteRatingSubmission {") &&
                submission.contains("if (activeProfileId() != profileAtStart) return")
        )
        assertTrue(
            "and a trigger that arrives mid-drain still queues one more pass",
            submission.contains("drainAgain.set(true)")
        )
    }

    // ── F2: a new grant drains without the connect screen ────────────────

    @Test
    fun `an adopted session drains its own leg`() {
        val applier = source(SYNC_PAYLOAD)
        assertTrue(
            "only a CHANGED row of the ACTIVE profile drains",
            applier.contains("if (changed && live) {") &&
                applier.contains("adoptedRatingsTracker(prefKey)?.let { tracker ->")
        )
        assertTrue(
            "and the key names the tracker, so no blanket drain is needed",
            applier.contains("PrefsPayloadBuilder.KEY_SIMKL_AUTH -> TasteRatingTracker.SIMKL") &&
                applier.contains("PrefsPayloadBuilder.KEY_TRAKT_AUTH -> TasteRatingTracker.TRAKT")
        )
        assertTrue(
            "a connect is what re-opens the leg as well as draining",
            submission.contains("fun onTrackerConnected(context: Context, tracker: TasteRatingTracker) {") &&
                submission.contains("closedGrants.remove(tracker)")
        )
    }

    @Test
    fun `both token refreshes drain their leg`() {
        listOf(
            "data/simkl/SimklRepository.kt" to "TasteRatingTracker.SIMKL",
            "data/trakt/TraktRepository.kt" to "TasteRatingTracker.TRAKT"
        ).forEach { (path, tracker) ->
            val src = source(path)
            assertTrue(
                "$path: a rotated token is a grant with no connect screen",
                src.contains("TasteRatingSubmission") && src.contains(tracker)
            )
        }
        assertFalse(
            "but not from the 401 interceptor that calls the refresh: it is " +
                "mid-request in the leg a drain would re-enter",
            source("data/simkl/SimklAuthInterceptors.kt").contains("TasteRatingSubmission")
        )
    }

    // ── F3: a park or a backoff gets a wake ──────────────────────────────

    @Test
    fun `a long park and a not-due entry both schedule the deferred wake`() {
        assertTrue(
            "the park arm splits at the follow-up boundary",
            submission.contains("val longPark = waitMs > FOLLOW_UP_MAX_WAIT_MS") &&
                submission.contains("if (longPark) wakeAtMs = minOf(wakeAtMs, now + waitMs)")
        )
        assertTrue(
            "and a not-due entry contributes its own due time",
            submission.contains("wakeAtMs = minOf(wakeAtMs, TasteRatingQueueRules.dueAt(entry))")
        )
        assertTrue(
            "the pass schedules once, at the end, and only when something needs it",
            submission.contains("if (wakeAtMs != Long.MAX_VALUE) {") &&
                submission.indexOf("if (wakeAtMs != Long.MAX_VALUE) {") <
                submission.indexOf("TasteRatingDrainWorker.enqueue(app, delayMs)")
        )
        assertTrue(
            "a spent entry drops instead of waking anything",
            submission.indexOf("if (TasteRatingQueueRules.isSpent(entry)) {") <
                submission.indexOf("wakeAtMs = minOf(wakeAtMs, TasteRatingQueueRules.dueAt(entry))")
        )
    }

    @Test
    fun `the worker is one-shot, unique, network-gated and never retries`() {
        assertTrue(
            "a CoroutineWorker like OutboxFlushWorker",
            worker.contains("class TasteRatingDrainWorker(") &&
                worker.contains(": CoroutineWorker(appContext, params)")
        )
        assertTrue(
            "one pending wake: unique name + REPLACE, so the soonest wins",
            worker.contains("const val WORK_NAME = \"taste_rating_drain\"") &&
                worker.contains("ExistingWorkPolicy.REPLACE")
        )
        assertTrue(
            "network-gated, and it only runs the ordinary drain",
            worker.contains("setRequiredNetworkType(NetworkType.CONNECTED)") &&
                worker.contains("TasteRatingSubmission.drain(applicationContext)")
        )
        assertTrue(
            "a delay, not a period: one-shot only",
            worker.contains("OneTimeWorkRequestBuilder<TasteRatingDrainWorker>()") &&
                worker.contains("setInitialDelay(") &&
                !worker.contains("PeriodicWorkRequestBuilder")
        )
        assertFalse(
            "never Result.retry() in the body: a parked 429 would loop",
            block(worker, "override suspend fun doWork()").contains("Result.retry()")
        )
        assertTrue(
            "and the app's existing worker pattern, no new dependency or permission",
            source(WORKER_PATTERN).contains("class OutboxFlushWorker(")
        )
    }

    // ── F4: the paste can say whether a wake is coming ───────────────────

    @Test
    fun `the park line names the wake, and says nothing it cannot promise`() {
        val note = block(submission, "private fun notePausedPark(")
        assertTrue(
            "the flag is decided by the caller, which is what knows",
            note.contains("wakeScheduled: Boolean")
        )
        assertTrue(
            "a scheduled park says so",
            note.contains("\"re-drain scheduled in \$waitText\"")
        )
        assertTrue(
            "and an unscheduled one keeps the old, true sentence",
            note.contains("\"retried on the next trigger\"")
        )
        assertTrue(
            "the line keeps the shape the paste parses",
            note.contains("\"\${tracker.label} parked rating writes for \$waitText (429) - \$tail\"")
        )
    }

    @Test
    fun `the invariants are untouched`() {
        val queue = source(QUEUE)
        assertTrue(
            "the attempt cap and the backoff schedule are the queue's own",
            queue.contains("const val MAX_ATTEMPTS = 5") &&
                queue.contains("listOf(1L, 5L, 30L, 120L, 720L).map { it * 60_000L }")
        )
        assertTrue(
            "no periodic work anywhere in the drain",
            !submission.contains("PeriodicWorkRequestBuilder") &&
                !worker.contains("PeriodicWorkRequestBuilder")
        )
        assertTrue(
            "and the short follow-up is still a coroutine delay, not the worker",
            submission.contains("private fun scheduleFollowUp(app: Context, waitMs: Long)") &&
                submission.contains("delay(waitMs.coerceAtLeast(1L))")
        )
        assertEquals(
            "one drain entry point for both warms of trigger",
            1,
            Regex("fun requestDrain\\(").findAll(submission).count()
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun block(source: String, declaration: String): String {
        val at = source.indexOf(declaration)
        require(at >= 0) { "missing: $declaration" }
        val bodyStart = source.indexOf("{", at)
        require(bodyStart > at) { "no body: $declaration" }
        var depth = 0
        for (i in bodyStart until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(at, i + 1)
                }
            }
        }
        error("unbalanced: $declaration")
    }

    private fun source(relative: String): String {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate =
                    File(dir, "${prefix}src/main/java/com/kennyb1201/kbstream/$relative")
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("source not found above ${System.getProperty("user.dir")}: $relative")
    }

    private companion object {
        const val SUBMISSION = "data/library/TasteRatingSubmission.kt"
        const val QUEUE = "data/library/TasteRatingQueue.kt"
        const val HOME_VM = "ui/home/HomeViewModel.kt"
        const val WORKER = "work/TasteRatingDrainWorker.kt"
        const val WORKER_PATTERN = "work/OutboxFlushWorker.kt"
        const val SYNC_PAYLOAD = "data/sync/SyncPrefsPayload.kt"
    }
}
