package com.kennyb1201.kbstream.data.sync

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A delete the viewer made locally outranks a still-live cloud copy - on BOTH
 * channels.
 *
 * The pull path learned this first: a delete sits in the outbox until it is
 * flushed, and a row the pull applies in the meantime would be re-inserted
 * locally while the queued tombstone then removed only the cloud copy, so the
 * deletion survived on the device that made it (see the guard in
 * [pullHistory]). The REALTIME applier skipped that check, which is the same
 * situation with a shorter fuse: a sibling - or this device's own earlier copy
 * re-published on reconnect - sends the row back over the live channel, the
 * single-row applier writes it straight in, and the queued delete no longer has
 * anything local to remove.
 *
 * The two channels share every other rule already (the tombstone payload, the
 * strictly-newer test, the profile-scoped database), so this is the one they
 * disagreed about. Read from the source: driving the realtime channel needs a
 * live Supabase connection, and what is worth pinning is that the two appliers
 * carry the same guard.
 */
class RealtimeHistoryApplyParityContractTest {

    private val sync by lazy { source(SYNC) }

    /** The realtime single-row applier's own body. */
    private val realtimeApply by lazy {
        sync.substringAfter("private suspend fun applyHistoryRow(")
            .substringBefore("private suspend fun applyImageOverrideRow(")
    }

    /** The pull's per-row applier, where the rule was written first. */
    private val pullApply by lazy {
        sync.substringAfter("private suspend fun pullHistory(")
            .substringBefore("private suspend fun pullWatched(")
    }

    @Test
    fun `both channels treat a queued delete as this row's answer`() {
        assertTrue(
            "the pull reads the outbox for this row",
            pullApply.contains(
                "val pendingTombstone = outbox.pending(TABLE_HISTORY, \"item_id\", storedId)"
            )
        )
        assertTrue(
            "and the realtime applier reads it too - a still-live cloud copy " +
                "must not resurrect a row the viewer has already removed",
            realtimeApply.contains(
                "val pendingTombstone = outbox.pending(TABLE_HISTORY, \"item_id\", storedId)"
            )
        )
        assertTrue(
            "the queued row only counts as a delete when it IS a tombstone",
            realtimeApply.contains(
                "?.takeIf { HistoryTombstoneRules.isTombstone(it.payload) }"
            )
        )
    }

    @Test
    fun `the queued delete removes the local row on the same strictly-newer test`() {
        assertTrue(
            "a delete that a later re-watch overtook leaves the newer row alone",
            realtimeApply.contains(
                "HistoryTombstoneRules.tombstoneWins(pendingDeletedAt, local.updatedAt)"
            )
        )
        assertTrue(
            "and when it wins, the local row goes",
            realtimeApply.contains("db.watchHistoryDao().deleteById(id)")
        )
        assertTrue(
            "the live payload is not applied either way: the queued delete is " +
                "what this row means, and the tombstone flush is what clears the " +
                "cloud copy",
            realtimeApply.contains("return@withOwningProfileDb") &&
                realtimeApply.indexOf("val pendingTombstone = outbox.pending") <
                realtimeApply.indexOf("if (historyRemoteWins(")
        )
    }

    private fun source(path: String): String {
        val file = File(findMainSourceRoot(), path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
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
        const val SYNC = "com/kennyb1201/kbstream/data/sync/SupabaseSync.kt"
    }
}
