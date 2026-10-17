package com.kennyb1201.kbstream.data.history

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The one-shot root migration's APPLY step, against a real (in-memory) Room
 * database.
 *
 * The rule is pinned purely in [HistoryRootMigrationRulesTest]; what cannot be
 * checked there is what the database looks like afterwards. A renamed row is a
 * delete plus an insert, so the two orderings matter: a write that lands before
 * the id it is leaving is deleted away is silently lost, and a collision that
 * leaves both rows is one episode on Continue Watching twice, with a progress
 * bar on each.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = HistoryRootMigrationApplyTest.NoopApplication::class)
class HistoryRootMigrationApplyTest {

    class NoopApplication : Application()

    private lateinit var db: WatchHistoryDatabase
    private lateinit var dao: WatchHistoryDao

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private val verified = "tt4873036"
    private val stale = "tt3121722"

    @Before
    fun open() {
        db = Room.inMemoryDatabaseBuilder(context, WatchHistoryDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.watchHistoryDao()
    }

    @After
    fun close() {
        db.close()
    }

    private fun row(
        id: String,
        parentId: String = id,
        type: String = "series",
        episodeStreamId: String? = null,
        positionMs: Long = 60_000L,
        updatedAt: Long = 1L
    ) = WatchHistoryEntity(
        id = id,
        parentId = parentId,
        type = type,
        name = "PAW Patrol",
        poster = null,
        streamUrl = null,
        season = 7,
        episode = 3,
        episodeStreamId = episodeStreamId,
        positionMs = positionMs,
        durationMs = 1_200_000L,
        updatedAt = updatedAt
    )

    private suspend fun planForStale() =
        planHistoryRootMigration(dao.getAllRows()) { verified }

    @Test
    fun `a stale row becomes the canonical one, and its old id is gone`() = runBlocking {
        dao.upsertRaw(row("$stale:7:3", parentId = stale, episodeStreamId = "$stale:7:3"))

        val plan = planForStale()
        assertFalse("the row is stale, so there is work", plan.isEmpty)
        dao.applyRootMigration(plan.deletedIds, plan.writes)

        val after = dao.getAllRows()
        assertEquals(listOf("$verified:7:3"), after.map { it.id })
        assertEquals(verified, after.single().parentId)
        assertEquals("$verified:7:3", after.single().episodeStreamId)
        // A migration is not a viewer's write: the progress and the stamp stand.
        assertEquals(60_000L, after.single().positionMs)
        assertEquals(1L, after.single().updatedAt)
        assertNull(dao.getById("$stale:7:3"))
    }

    @Test
    fun `applying the plan is idempotent`() = runBlocking {
        dao.upsertRaw(row("$stale:7:3", parentId = stale))
        val plan = planForStale()
        dao.applyRootMigration(plan.deletedIds, plan.writes)

        val second = planForStale()
        assertTrue("a second pass over the same rows is a no-op", second.isEmpty)
        assertTrue(second.tombstonedIds.isEmpty())
        assertEquals(1, dao.getAllRows().size)
    }

    @Test
    fun `a write into an id another row vacates survives the delete`() = runBlocking {
        // The same episode twice: the canonical row is older, the stale row the
        // viewer actually kept watching is newer. The stale row's canonical id
        // is BOTH a delete (the older twin goes) and a write (the newer row
        // arrives there), which is why the delete runs first.
        dao.upsertRaw(row("$verified:7:3", parentId = verified, positionMs = 10L, updatedAt = 10L))
        dao.upsertRaw(row("$stale:7:3", parentId = stale, positionMs = 90_000L, updatedAt = 20L))

        val plan = planForStale()
        assertEquals(listOf("$stale:7:3", "$verified:7:3"), plan.deletedIds.sorted())
        dao.applyRootMigration(plan.deletedIds, plan.writes)

        val after = dao.getAllRows()
        assertEquals(listOf("$verified:7:3"), after.map { it.id })
        assertEquals(90_000L, after.single().positionMs)
    }

    @Test
    fun `the migration sees rows the launcher read skips`() = runBlocking {
        dao.upsertRaw(row("$stale:7:3", parentId = stale, positionMs = 0L))
        assertTrue("no saved position: getAll is not the whole table", dao.getAll().isEmpty())
        assertEquals(1, dao.getAllRows().size)
        assertEquals(listOf("$verified:7:3"), planForStale().writes.map { it.id })
    }

    @Test
    fun `a film row is re-rooted as itself`() = runBlocking {
        dao.upsertRaw(row("tmdb:550", type = "movie"))
        val plan = planHistoryRootMigration(dao.getAllRows()) { "tt0137523" }
        dao.applyRootMigration(plan.deletedIds, plan.writes)

        val after = dao.getAllRows()
        assertEquals(listOf("tt0137523"), after.map { it.id })
        assertEquals("tt0137523", after.single().parentId)
    }
}
