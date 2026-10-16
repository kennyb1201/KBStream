package com.kennyb1201.kbstream.data.sync

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The user-triggered in-progress reset ("Clear eye badges") must SYNC its
 * clear the way every other delete does, and this pins that wiring at the
 * source.
 *
 * The reset used to hard-DELETE its cloud rows. That cannot work across
 * devices: a delete is invisible to a sibling that still holds the row, so the
 * other TV went on drawing the resume bar and the eye badge the viewer had just
 * cleared here, and its next push put the row back. The clear is now a newer
 * WRITE - a history tombstone per resume row, a cleared watched marker per
 * partial marker - published through the durable outbox, whose queued writes
 * for those same keys are dropped first.
 *
 * The ordering and the queue interaction only exist inside these two functions
 * and cannot be driven without a signed-in account, so they are pinned here;
 * the tombstone/merge rules they rely on are unit tested on their own (see
 * [HistoryTombstoneTest], [WatchedMarkerSyncTest]).
 */
class PoisonSweepClearTombstoneContractTest {

    @Test
    fun `the in-progress reset publishes writes instead of deleting cloud rows`() {
        val body = functionBody(SWEEP, "private suspend fun clearInProgressInternal(")
        assertTrue(
            "the reset must publish its clear through the outbox",
            body.contains("SupabaseSync.publishInProgressClear(")
        )
        assertFalse(
            "a hard cloud DELETE is invisible to a sibling that still holds the row",
            body.contains("deleteCloudKeys(")
        )
        assertTrue(
            "the history rows must be passed as BARE ids - the publisher scopes " +
                "them, and scoping an already-scoped key would double-prefix it",
            body.contains("historyIds = resumeRows.map { it.id }")
        )
    }

    @Test
    fun `the queued writes for those keys go before the tombstones`() {
        val body = functionBody(SYNC, "internal fun publishInProgressClear(")
        val dropHistory = body.indexOf("outbox.removeKeys(TABLE_HISTORY")
        val dropWatched = body.indexOf("outbox.removeKeys(TABLE_WATCHED")
        val stamp = body.indexOf("HistoryTombstoneRules.tombstone(")
        assertTrue("the queued history writes must be dropped", dropHistory >= 0)
        assertTrue("the queued marker writes must be dropped", dropWatched >= 0)
        assertTrue("the history tombstone must be written", stamp >= 0)
        assertTrue(
            "the drops must come FIRST: after the tombstone is queued the live " +
                "upload is already in the outbox and flushes as the newer write",
            dropHistory in 0 until stamp && dropWatched in 0 until stamp
        )
        assertTrue(
            "a cleared marker is published as both flags false - the watched " +
                "table has no tombstone field",
            body.contains("isWatched = false") && body.contains("isPartiallyWatched = false")
        )
        assertTrue(
            "and the clear has to be flushed, or it waits for the next enqueue",
            body.contains("scheduleFlush()")
        )
    }

    @Test
    fun `the published row keeps the profile scope it was read from`() {
        val body = functionBody(SYNC, "internal fun publishInProgressClear(")
        // The watched rows arrive with their STORED (already scoped) key and go
        // straight into the outbox; the history ids are bare and are scoped
        // once, by the same rule every other writer uses.
        assertTrue(
            "a marker row must not be re-scoped on the way out",
            body.contains("outbox.put(\n                OutboxItem(\n                    TABLE_WATCHED,\n                    \"item_key\",\n                    storedKey,")
        )
        assertTrue(body.contains("scopedKey(id, profileId)"))
        assertTrue(
            "the payload key is the unscoped id, or the sibling reads a key " +
                "nothing on its side names",
            body.contains("remote.str(\"key\") ?: unscopedKey(storedKey)")
        )
    }

    /**
     * The body of the function starting at [signature], up to its closing brace
     * (a line indented by exactly four spaces).
     */
    private fun functionBody(path: String, signature: String): String {
        val file = File(sourceRoot(), path)
        assertTrue("source missing: $file", file.isFile)
        val src = file.readText()
        val start = src.indexOf(signature)
        assertTrue("source missing function: $signature", start >= 0)
        val brace = src.indexOf('{', start)
        assertTrue("no body for function: $signature", brace >= 0)
        val rest = src.substring(brace + 1)
        val end = Regex("\\n {4}\\S").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    private fun sourceRoot(): File {
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
        const val SWEEP = "com/kennyb1201/kbstream/data/sync/PoisonSweep.kt"
        const val SYNC = "com/kennyb1201/kbstream/data/sync/SupabaseSync.kt"
    }
}
