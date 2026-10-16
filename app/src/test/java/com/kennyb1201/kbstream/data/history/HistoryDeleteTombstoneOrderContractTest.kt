package com.kennyb1201.kbstream.data.history

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A local history delete and the cloud tombstone for it are one operation, and
 * the wiring that makes them so is pinned here.
 *
 * Two shapes used to be wrong, in the same way:
 *
 *  - the tombstone set was computed by ASKING AFTER THE DELETE which rows had
 *    gone (`getById(id) == null`). The ask is a second statement, so a
 *    completion that reused a resume row's id - the marker the same call had
 *    just written - could be filed as a deletion, and the tombstone then erased
 *    the watched state on every other device. The ids now come from the delete
 *    itself, in one Room transaction.
 *  - the local delete ran BEFORE the publish. The tombstone is the durable half
 *    (it is queued in the outbox), so that order leaves nothing to repair a
 *    crash in between: the rows are gone here, nothing is queued, and the next
 *    pull restores them. The rows the caller already has in hand are therefore
 *    published first, and deleted after.
 *
 * Neither rule can be driven without a database and a signed-in account, so
 * they are read out of the source, the shape the other contract tests use.
 */
class HistoryDeleteTombstoneOrderContractTest {

    private fun read(path: String): String {
        val file = File(sourceRoot(), path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
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

    @Test
    fun `a delete that gets tombstoned returns the ids it removed`() {
        val dao = read(DAO)
        listOf(
            "@Transaction\n    suspend fun removeResumeRowsForParentsReturningIds(parentIds: List<String>): List<String>",
            "@Transaction\n    suspend fun removeResumeRowsForEpisodeReturningIds("
        ).forEach { signature ->
            assertTrue(
                "the delete and the ids it removed must be one transaction: $signature",
                dao.contains(signature)
            )
        }
        assertTrue(
            "and the ids must come from the read the same transaction performs",
            dao.contains("val ids = getInProgressForParents(parentIds).map { it.id }")
        )
    }

    @Test
    fun `the repository stops asking after the delete which rows are gone`() {
        val repository = read(REPOSITORY)
        assertTrue(
            "the poster mark must take its tombstone set from the transactional delete",
            repository.contains("removeResumeRowsForParentsReturningIds(parents)")
        )
        assertTrue(
            "and so must the episode mark",
            repository.contains("removeResumeRowsForEpisodeReturningIds(")
        )
        assertFalse(
            "a read taken AFTER the delete can see a row that survived as the completed " +
                "marker, and tombstoning that row erases the watched state elsewhere",
            repository.contains("runCatchingCancellable { historyDao.getById(rowId) }")
        )
    }

    @Test
    fun `the whole-title unmark publishes its tombstones before it deletes anything`() {
        val src = read(REPOSITORY)
        val start = src.indexOf("suspend fun markUnwatchedLocal(")
        val end = src.indexOf("suspend fun clearWatchedOverride(", start)
        assertTrue("markUnwatchedLocal must exist", start >= 0)
        assertTrue("the body must be bounded by the next function", end > start)
        val body = src.substring(start, end)

        val publish = body.indexOf(".deleteHistoryRows(distinctRemovedIds)")
        val movieDelete = body.indexOf("historyDao.deleteByIds(distinctRemovedIds)")
        val seriesDeletes = body.indexOf("historyDao.deleteResumeRowsForParents(parents)")
        assertTrue("the unmark must publish its deletes", publish >= 0)
        assertTrue("the movie row must be deleted", movieDelete >= 0)
        assertTrue("the series rows must be deleted", seriesDeletes >= 0)
        assertTrue(
            "the tombstone is the durable half of the pair, so it goes FIRST - a local " +
                "delete that then fails is repaired by the next pull's tombstone, while " +
                "the other order has nothing to repair it with",
            publish < movieDelete && publish < seriesDeletes
        )
    }

    @Test
    fun `the completion's superseded rows are tombstoned before they are deleted`() {
        val writer = read(WRITER)
        val publish = writer.indexOf("SupabaseSync.deleteHistoryRows(cleared")
        val localDelete = writer.indexOf("dao.deleteByIds(cleared)")
        assertTrue("the writer must publish the tombstones", publish >= 0)
        assertTrue("and delete the rows locally", localDelete >= 0)
        assertTrue(
            "published first, deleted after: see the note at the call site",
            publish < localDelete
        )
    }

    @Test
    fun `the launcher republish is outside the save's success verdict`() {
        val writer = read(WRITER)
        val start = writer.indexOf("suspend fun write(")
        val end = writer.indexOf("private suspend fun supersededResumeIds(", start)
        assertTrue("the writer's entry point must exist", start >= 0)
        assertTrue("and its body must be bounded by the next declaration", end > start)
        val body = writer.substring(start, end)

        val verdict = body.indexOf("}.isSuccess")
        val publish =
            body.indexOf("TvLauncherPublisher.sync(context, dao.getResumeRowsForLauncher())")
        assertTrue("the write's verdict must exist", verdict >= 0)
        assertTrue("and the launcher republish must exist", publish >= 0)
        assertTrue(
            "inside the verdict it decided the SAVE: a launcher publish that " +
                "failed reported a row that had landed as a failed write, which " +
                "sent every caller down its failure path. It mirrors a table the " +
                "save has already written, so it goes after the verdict",
            verdict < publish
        )
        assertTrue(
            "and it is still best-effort and cancellable, like the redirect " +
                "branch's own republish",
            body.substring((publish - 300).coerceAtLeast(0), publish)
                .contains("runCatchingCancellable {")
        )
    }

    private companion object {
        const val DAO = "com/kennyb1201/kbstream/data/history/WatchHistoryDao.kt"
        const val REPOSITORY =
            "com/kennyb1201/kbstream/data/watched/WatchedStatusRepository.kt"
        const val WRITER = "com/kennyb1201/kbstream/data/history/PlaybackHistoryWriter.kt"
    }
}
