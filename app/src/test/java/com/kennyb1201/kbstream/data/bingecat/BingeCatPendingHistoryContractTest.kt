package com.kennyb1201.kbstream.data.bingecat

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Data M2: a BingeCat completion that could not land is replayed later.
 *
 * The failure this closes is invisible by construction - the play shows locally
 * either way, and BingeCat just never hears about it. So the WIRING is pinned:
 * which failures queue a play, that removes never do, that a replay cannot
 * re-queue itself, that every gate is re-checked before a replay, that one
 * failure ends the pass, and that the two doors are the app's own busy moments
 * rather than a poll.
 */
class BingeCatPendingHistoryContractTest {

    private val sourceRoot: File by lazy { findSourceRoot() }

    private fun findSourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
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

    private fun squash(text: String): String = text.replace(Regex("\\s+"), " ")

    private val repository = squash(source("com/kennyb1201/kbstream/data/bingecat/BingeCatRepository.kt"))
    private val store = squash(source("com/kennyb1201/kbstream/data/bingecat/BingeCatPendingHistory.kt"))
    private val app = squash(source("com/kennyb1201/kbstream/MainApplication.kt"))
    private val home = squash(source("com/kennyb1201/kbstream/ui/home/HomeViewModel.kt"))

    private fun slice(from: String, to: String): String {
        val start = repository.indexOf(from)
        assertTrue("missing anchor: $from", start >= 0)
        val end = repository.indexOf(to, start + from.length)
        assertTrue("missing end anchor: $to", end > start)
        return repository.substring(start, end)
    }

    @Test
    fun `a failed completion is queued, at the one choke point`() {
        val submit = slice("private suspend fun submitHistoryOutcome(", "Queues a completion that did not land")
        assertTrue(
            "the 429 park queues the play it refused",
            submit.contains(
                "if (!fromReplay) recordPendingCompletion(add, batch, label) return BingeCatWriteOutcome.FAILED"
            )
        )
        assertTrue(
            "so does a token that would not refresh, a send that never answered, a bare 429 and a throw",
            Regex("if \\(!fromReplay\\) recordPendingCompletion\\(add, batch, label\\)").findAll(submit).count() >= 5
        )
        assertTrue(
            "a 5xx is BingeCat's bad minute, which is worth retrying",
            submit.contains("if (!fromReplay && response.code() >= 500)")
        )
        assertFalse(
            "a 4xx is this request being wrong: replaying it would earn the same answer",
            submit.contains("response.code() >= 400")
        )
        assertTrue(
            "a replay never queues its own retry - the entry it came from is still there",
            submit.contains("fromReplay: Boolean = false")
        )
        assertTrue(
            "and the Boolean the players read keeps its contract: only an attempt that " +
                "did not land is false, a gate is not a failure",
            repository.contains(
                "submitHistoryOutcome(add, batch, label) != BingeCatWriteOutcome.FAILED"
            )
        )
    }

    @Test
    fun `removes are never queued`() {
        val record = slice("private suspend fun recordPendingCompletion(", "private fun pendingKeyOf(")
        assertTrue(
            "completions only: a late remove could delete a play made since, on another device",
            record.contains("if (!add) return")
        )
        assertTrue(
            "with no mapping there is nobody to file it against",
            record.contains("val mapped = mappedProfileId() ?: return")
        )
        assertTrue(
            "and a play that cannot be named is not queued at all",
            record.contains("val key = pendingKeyOf(batch) ?: return")
        )
    }

    @Test
    fun `every gate is re-checked before a replay`() {
        val flush = slice("internal suspend fun flushPendingHistory()", "fun schedulePendingHistoryFlush()")
        listOf(
            "if (!isReady()) return 0" to "no session",
            "if (!hasWriteScope()) return 0" to "a step-1 grant has no history:write",
            "if (bingeCatWriteParkMs() > 0L) return 0" to "a live park is the server asking for silence",
            "val mapped = mappedProfileId() ?: return 0" to "an unmapped profile must never receive these"
        ).forEach { (gate, why) ->
            assertTrue("$why ($gate)", flush.contains(gate))
        }
        assertTrue(
            "and the queue itself is asked only for this profile's plays",
            flush.contains("pendingHistory.usable(mapped)")
        )
        assertTrue(
            "one failure ends the pass rather than hammering a leg that just failed",
            flush.contains("BingeCatWriteOutcome.FAILED -> { pendingHistory.settle(entry.key, success = false) break }")
        )
        assertTrue(
            "a success drops its entry",
            flush.contains("BingeCatWriteOutcome.LANDED -> { pendingHistory.settle(entry.key, success = true)")
        )
        assertTrue(
            "and a refusal keeps it WITHOUT spending an attempt: a reconnect is what " +
                "lets it land, not three tries of the same 403",
            flush.contains("BingeCatWriteOutcome.NOTHING_ATTEMPTED, BingeCatWriteOutcome.REFUSED_SCOPE -> break")
        )
        assertTrue(
            "the replay rides the same gate chain as a live write, marked as one",
            flush.contains("label = \"replay \${entry.label}\", fromReplay = true")
        )
    }

    @Test
    fun `the queue is durable, bounded and profile-scoped on disk`() {
        assertTrue(
            "one file in filesDir, no schema",
            store.contains("const val FILE_NAME = \"bingecat_pending_history.json\"") &&
                store.contains("File(it.filesDir, FILE_NAME)")
        )
        assertTrue(
            "the body is the request itself, so watched_at rides with it",
            store.contains("val body = runCatching { batchAdapter.toJson(batch) }.getOrNull() ?: return")
        )
        assertTrue(
            "an unreadable file is discarded, never repaired",
            store.contains("pending history unreadable, discarding")
        )
        assertTrue(
            "another profile's plays are dropped, not deferred",
            store.contains("if (usable.size != all.size) write(usable)")
        )
        assertTrue(
            "and nothing here throws at its caller",
            repository.contains("runCatching { pendingHistory.enqueue(")
        )
    }

    @Test
    fun `the doors are app start and the Home load, never a poll`() {
        assertTrue(
            "app start replays what the last session could not deliver",
            app.contains("BingeCatRepository .getInstance(applicationContext) .schedulePendingHistoryFlush()")
        )
        assertTrue(
            "and every Home load does, on the taste queue's own reasoning",
            home.contains("BingeCatRepository .getInstance(getApplication()) .schedulePendingHistoryFlush()")
        )
        assertFalse(
            "but nothing polls: no worker, no timer, no periodic replay",
            repository.contains("PeriodicWorkRequest") || repository.contains("delay(")
        )
        assertTrue(
            "the door is off the caller's path",
            repository.contains("fun schedulePendingHistoryFlush() { BackgroundWork.launch {")
        )
    }
}
