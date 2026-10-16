package com.kennyb1201.kbstream.data.history

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The history row is written locally BEFORE it is queued for the cloud.
 *
 * [WatchHistoryRepository.upsert] used to enqueue its outbox row first and write
 * the database row second. Anything that fails the second step — a profile swap
 * mid-write, a scoped database that cannot be opened — then left a queued cloud
 * copy of a row that does not exist locally, which the next pull brings back as a
 * continue-watching card for something never played. The outbox is keyed by row
 * id, so a re-enqueue after a retry is still harmless; a queue entry for a write
 * that never happened is not.
 *
 * Read out of the source: the ordering is between an Android Room write and a
 * sync enqueue, neither of which a unit test can drive here.
 */
class HistoryUpsertOrderContractTest {

    @Test
    fun `the local write happens before the outbox enqueue`() {
        val source = read("com/kennyb1201/kbstream/data/history/WatchHistoryRepository.kt")
        val upsert = source
            .substringAfter("suspend fun upsert(entry: WatchHistoryEntity) {")
            .substringBefore("\n    }")
        val localWrite = upsert.indexOf("WatchHistoryDatabase.withScopedDao(appContext) { it.upsert(entry) }")
        val enqueue = upsert.indexOf("SupabaseSync.enqueueHistory(entry)")
        assertTrue("the local write must be in the upsert", localWrite >= 0)
        assertTrue("the enqueue must be in the upsert", enqueue >= 0)
        assertTrue(
            "a queued row for a write that then failed is a resume card for a title never played",
            enqueue > localWrite
        )
    }

    private fun read(relative: String): String {
        val file = File(findSourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

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
}
