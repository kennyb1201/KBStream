package com.kennyb1201.kbstream.data.simkl

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Simkl 429 park must gate every read path, not just the two Home legs
 * (F1, 2026-11-11), and since 2026-11-19 it must honour what the 429 says and
 * survive a restart (F1–F4 of the read-park spec).
 *
 * m23 parked the read legs on a 429/`Retry-After`, and only the Continue
 * Watching feed and the all-shows read consulted it. Three other live reads went
 * straight to the network during the same window: the delete-playback-session
 * pair behind every mark-watched, Detail's resume lookup, and the connect
 * screen's watched counts. The consequence was not just traffic: a tracking
 * read failing meant the open paused session was NEVER deleted, so a completed
 * episode's pre-completion resume point resurfaced in Continue Watching at its
 * old progress, with no later event to retry the delete.
 *
 * Each fix is a gate that answers cache/empty/null instead of calling, plus -
 * for the delete - a record that is replayed on the first fetch that gets
 * through. None of that is visible without a rate-limited account, so it is read
 * out of the source, the shape the other Simkl contract tests use. The
 * arithmetic of the park itself is pinned by [SimklReadParkTest], which
 * exercises the real object.
 */
class SimklReadParkBypassContractTest {

    // ── the park itself ─────────────────────────────────────────────────────

    @Test
    fun `the park stays a deadline that only moves forward`() {
        val repo = source(REPOSITORY)

        assertTrue(
            "the deadline only ever moves FORWARD - a short park arriving behind a " +
                "long one must never cut it short, whatever the 429's class",
            normalized(repo).contains(
                "val until = now + SimklReadPark.durationMs(code, retryAfter, quota)"
            ) && normalized(repo).contains("if (until > readsParkedUntilMs) {")
        )
        assertTrue(
            "and the duration is decided in ONE place, from the 429's class",
            repo.contains("SimklReadPark.durationMs(") &&
                repo.contains("bodyError = bodyError")
        )
        assertFalse(
            "the inline clamp that flattened a daily quota's 16 hours into ten " +
                "minutes is gone, not merely bypassed by a second branch",
            repo.contains("READ_PARK_MAX_MS")
        )
    }

    @Test
    fun `a daily quota is the one path whose ceiling moved`() {
        val repo = source(REPOSITORY)
        val park = source(READ_PARK)

        assertTrue(
            "the all-shows 429 hands the body's `error` field - the one the " +
                "rate-limit interceptor already reads - to the park",
            normalized(repo).contains(
                "parkReads( code = response.code(), " +
                    "retryAfter = response.headers()[\"Retry-After\"], " +
                    "bodyError = errorFieldOf(errorText)"
            )
        )
        assertTrue(
            "the CW feed parks for what its exception says too: it is a Home leg, " +
                "and it was the leg polling through the spent quota",
            normalized(repo).contains("parkAfterReadFailure(e)")
        )
        assertTrue(
            "a daily class - or a header that outlasts a burst, whatever the body " +
                "calls it - takes the header whole, floored but not capped",
            park.contains("SimklAuthV2Rules.isDailyQuota(limit) ||") &&
                park.contains("headerOutlastsBurst(retryAfter)") &&
                park.contains("header?.coerceAtLeast(MIN_MS) ?: DEFAULT_MS")
        )
        assertTrue(
            "while a header that fits inside the burst ceiling keeps the old clamp, " +
                "and a missing one keeps the old default",
            park.contains("header?.coerceIn(MIN_MS, MAX_BURST_MS) ?: DEFAULT_MS")
        )
        assertTrue(
            "and an unreadable body parks as a burst, the classifier's own " +
                "fallback: the cheap mistake is a short park",
            park.contains("if (quota == SimklAuthV2Rules.RateLimit.NONE) {") &&
                park.contains("SimklAuthV2Rules.RateLimit.BURST")
        )
    }

    @Test
    fun `the activities poll parks its 429 and answers from the park`() {
        val repo = source(REPOSITORY)

        val helper = section(
            repo,
            "private fun parkAfterActivitiesFailure(",
            "suspend fun getActivities("
        )
        assertTrue(
            "only a 429 parks here: any other failure stays answered as unchanged, " +
                "and a 500 must not delay the next poll",
            helper.contains("if (response.code() != 429) return") &&
                helper.contains("retryAfter = response.headers()[\"Retry-After\"]")
        )

        val changed = section(
            repo,
            "suspend fun hasWatchedActivityChanged(",
            "suspend fun markWatchedActivitySynced("
        )
        val synced = section(
            repo,
            "suspend fun markWatchedActivitySynced(",
            "internal fun trackedAuthHeader(): String"
        )
        // The third reader is not a poll: it is SimklSyncWorker's periodic tick,
        // and its failure used to answer Retry - a retry chain into the same 429.
        val probe = section(
            repo,
            "suspend fun refreshWatchedActivity(",
            "private fun scoreContinueWatchingItem("
        )
        listOf(
            Triple(changed, "hasWatchedActivityChanged", "it"),
            Triple(synced, "markWatchedActivitySynced", "it"),
            Triple(probe, "refreshWatchedActivity", "e")
        ).forEach { (body, name, error) ->
            assertTrue(
                "$name: the call itself is gated, so the park answers instead of " +
                    "spending the read - this poll was 41% of the limited traffic",
                body.indexOf("if (readsAreParked())") in 0 until body.indexOf("getActivities(")
            )
            assertTrue(
                "$name: and its 429 is recorded",
                body.contains("parkAfterActivitiesFailure($error)")
            )
        }

        assertTrue(
            "the worker leg answers the park as a FINISHED round (attempted = false, " +
                "which WorkPolicies maps to Done) - answering failed would put the " +
                "periodic worker into a retry chain against the same 429",
            normalized(probe).contains(
                "if (readsAreParked()) { return WatchedActivityRefreshResult( attempted = false, changed = false )"
            )
        )
    }

    @Test
    fun `the park and its waiting deletes survive a restart`() {
        val repo = source(REPOSITORY)

        assertTrue(
            "the deadline is written when it moves forward and read back once",
            repo.contains("putLong(KEY_READ_PARK_UNTIL_MS, until)") &&
                repo.contains("getLong(KEY_READ_PARK_UNTIL_MS, 0L)")
        )
        assertTrue(
            "a persisted deadline in the past is no park",
            normalized(repo).contains(
                "if (persisted > readsParkedUntilMs) readsParkedUntilMs = persisted"
            )
        )
        assertTrue(
            "the deletes are persisted WITH it - a delete recorded in hour 1 of a " +
                "16-hour park must still replay after the reboot in between",
            repo.contains("encodePendingPlaybackDeletes(pendingPlaybackDeletes)") &&
                repo.contains(
                    "decodePendingPlaybackDeletes(prefs.getString(KEY_PENDING_PLAYBACK_DELETES, null))"
                )
        )
        assertEquals(
            "and the writes stay on record/forget only - one definition, two call " +
                "sites, never per read",
            3,
            repo.split("persistPendingPlaybackDeletes()").size - 1
        )
    }

    @Test
    fun `forceRefresh no longer bypasses the park`() {
        val allShows = section(
            source(REPOSITORY),
            "internal suspend fun getAllShowItemsCached(",
            "val now ="
        )

        assertTrue(
            "a forced caller serves the parked cache like anyone else",
            allShows.contains("if (readsAreParked()) {")
        )
        assertFalse(
            "with a 16-hour park, `forceRefresh` meant one call straight into a " +
                "spent quota and another 429 in answer",
            allShows.contains("!forceRefresh && readsAreParked()")
        )
    }

    // ── (a) the delete paths ────────────────────────────────────────────────

    @Test
    fun `both delete impls answer the park without reading`() {
        val tracking = source(PLAYBACK_TRACKING)

        val parent = section(
            tracking,
            "suspend fun SimklRepository.deletePlaybackSessionsForParentImpl(",
            "suspend fun SimklRepository.deleteOpenPlaybackSessionsForWatchedImpl("
        )
        val watched = section(
            tracking,
            "suspend fun SimklRepository.deleteOpenPlaybackSessionsForWatchedImpl(",
            "internal fun playbackSessionMatchesEpisodeScope("
        )

        listOf(parent to "forParent", watched to "forWatched").forEach { (body, factory) ->
            val gate = body.indexOf("if (readsAreParked())")
            assertTrue(
                "$factory: the delete's own list read must not run while the leg " +
                    "is parked - answering 0 without recording is what lost the " +
                    "delete",
                gate >= 0
            )
            assertTrue(
                "$factory: the parked delete is RECORDED, so it can be replayed",
                body.contains("notePendingPlaybackDelete(") &&
                    body.contains("PendingPlaybackDelete.$factory(")
            )
            assertTrue(
                "$factory: and the gate runs before the network read, not after it",
                gate < body.indexOf("getPlaybackItems()")
            )
        }
    }

    @Test
    fun `a parked delete is replayed on the first successful fetch`() {
        val reads = source(READS)
        val tracking = source(PLAYBACK_TRACKING)
        val repo = source(REPOSITORY)

        val fetch = section(reads, "suspend fun SimklRepository.getPlaybackItemsImpl(")
        assertTrue(
            "the one place a successful session fetch happens is where the " +
                "replay runs - that makes \"the first fetch after the park " +
                "lifts\" the trigger, with no timer to add",
            fetch.contains("replayPendingPlaybackDeletes(sessions)")
        )
        assertTrue(
            "and the answer drops the sessions it just deleted, so a closed " +
                "session cannot reach Continue Watching for one more build",
            normalized(fetch).contains(
                "sessions.filterNot { item -> item.id?.let { it in replayed } == true }"
            )
        )

        val replay = section(
            tracking,
            "internal suspend fun SimklRepository.replayPendingPlaybackDeletes(",
            "/** Whether one open session is what [delete] was recorded to remove. */"
        )
        assertTrue(
            "the replay judges the sessions it was HANDED - the list just read, " +
                "never a pre-park copy",
            replay.contains("sessions: List<SimklPlaybackItem>") &&
                replay.contains("for (item in sessions)")
        )
        assertTrue(
            "it re-reads nothing of its own: the network stays in the caller",
            !replay.contains("api.getPlayback")
        )
        assertTrue(
            "an entry is forgotten only once its delete took - a failed one " +
                "stays queued for the next fetch",
            replay.contains("if (allDeleted) applied += delete") &&
                replay.contains("forgetPendingPlaybackDeletes(applied)")
        )

        assertTrue(
            "the queue is bounded, because a run of parked marks must not grow it",
            repo.contains("MAX_PENDING_PLAYBACK_DELETES = 32") &&
                repo.contains("pendingPlaybackDeletes.size > MAX_PENDING_PLAYBACK_DELETES")
        )
    }

    // ── (b) Detail resume ──────────────────────────────────────────────────

    @Test
    fun `Detail resume answers its memo, or nothing, while parked`() {
        val resume = source(DETAIL_RESUME)

        val gate = resume.indexOf("if (vm.simklRepository.readsAreParked())")
        assertTrue(
            "the resume lookup sits on Detail's critical path and used to spend " +
                "a call the account had just been told to stop making",
            gate >= 0
        )
        assertTrue(
            "the gate runs before the live read",
            gate < resume.indexOf("vm.simklRepository.getPlaybackItems()")
        )
        assertTrue(
            "and it answers the memo it already has, else empty - never a live read",
            resume.contains(
                "return cached\n                ?.takeIf { cachedSimklPlaybackToken == token }\n" +
                    "                .orEmpty()"
            )
        )
        assertTrue(
            "the memo's own rule is untouched: only a successful answer is stored",
            normalized(resume).contains(
                "cachedSimklPlayback = fresh cachedSimklPlaybackAt = " +
                    "System.currentTimeMillis()"
            ) && resume.contains("?: return emptyList()")
        )
    }

    // ── (c) watched counts ──────────────────────────────────────────────────

    @Test
    fun `watched counts answer null while parked, without firing either leg`() {
        val counts = section(
            source(READS),
            "suspend fun SimklRepository.getWatchedCountsImpl(",
            "suspend fun SimklRepository.getAccountInfoImpl("
        )

        val gate = counts.indexOf("if (readsAreParked()) return null")
        assertTrue(
            "null is \"could not ask\", which the connect screen already omits",
            gate >= 0
        )
        assertFalse(
            "forceRefresh is a caller's wish for a coherent pair, not a licence " +
                "to ignore a 429 - the gate must not be conditional on it",
            counts.contains("!forceRefresh && readsAreParked()")
        )
        assertTrue(
            "and the gate is ahead of BOTH legs: the force-refreshed show read " +
                "and the raw movies call",
            gate < counts.indexOf("getAllShowItemsCached(") &&
                gate < counts.indexOf("api.getAllMovieItems(")
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private fun source(path: String): String =
        File(findMainSourceRoot(), path).readText()

    private fun normalized(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    private fun section(source: String, anchor: String, end: String? = null): String {
        val start = source.indexOf(anchor)
        assertTrue("anchor not found: $anchor", start >= 0)
        if (end == null) return source.substring(start)
        val to = source.indexOf(end, start + anchor.length)
        assertTrue("$end must follow $anchor", to > start)
        return source.substring(start, to)
    }

    private fun findMainSourceRoot(): File {
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

    private companion object {
        const val REPOSITORY = "com/kennyb1201/kbstream/data/simkl/SimklRepository.kt"
        const val READ_PARK = "com/kennyb1201/kbstream/data/simkl/SimklReadPark.kt"
        const val READS = "com/kennyb1201/kbstream/data/simkl/SimklReads.kt"
        const val PLAYBACK_TRACKING =
            "com/kennyb1201/kbstream/data/simkl/SimklPlaybackTracking.kt"
        const val DETAIL_RESUME = "com/kennyb1201/kbstream/ui/detail/DetailPlaybackResume.kt"
    }
}
