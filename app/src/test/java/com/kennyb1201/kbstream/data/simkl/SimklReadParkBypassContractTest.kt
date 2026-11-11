package com.kennyb1201.kbstream.data.simkl

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Simkl 429 park must gate every read path, not just the two Home legs
 * (F1, 2026-11-11).
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
 * out of the source, the shape the other Simkl contract tests use.
 */
class SimklReadParkBypassContractTest {

    // ── the park itself is unchanged ────────────────────────────────────────

    @Test
    fun `the park stays a deadline that only moves forward`() {
        val repo = source(REPOSITORY)

        assertTrue(
            "a 429 still parks for its Retry-After, coerced into the same bounds",
            repo.contains("?.coerceIn(READ_PARK_MIN_MS, READ_PARK_MAX_MS)")
        )
        assertTrue(
            "and the deadline only ever moves FORWARD - F1 consults the park, it " +
                "does not re-park anything",
            normalized(repo).contains(
                "val until = now + parkMs if (until > readsParkedUntilMs) " +
                    "readsParkedUntilMs = until"
            )
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
        const val READS = "com/kennyb1201/kbstream/data/simkl/SimklReads.kt"
        const val PLAYBACK_TRACKING =
            "com/kennyb1201/kbstream/data/simkl/SimklPlaybackTracking.kt"
        const val DETAIL_RESUME = "com/kennyb1201/kbstream/ui/detail/DetailPlaybackResume.kt"
    }
}
