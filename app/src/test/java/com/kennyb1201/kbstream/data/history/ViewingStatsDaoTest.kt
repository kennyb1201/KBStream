package com.kennyb1201.kbstream.data.history

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The viewing-stats aggregate queries, run against a real (in-memory) Room
 * database.
 *
 * The screen's arithmetic is trivial; the SQL is not, so it is exercised here
 * rather than trusted: finished runtime must sum only the COMPLETED rows'
 * durations, an in-progress row must contribute its saved position to its
 * show's total without inflating the finished count, and an episode that was
 * rewatched must count once because the writer overwrites its single row. A
 * wrong CASE/COALESCE here is a number that looks plausible and is wrong.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = ViewingStatsDaoTest.NoopApplication::class)
class ViewingStatsDaoTest {

    class NoopApplication : Application()

    private lateinit var db: WatchHistoryDatabase
    private lateinit var dao: WatchHistoryDao

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

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
        parentId: String,
        type: String = "episode",
        name: String = "Show $parentId",
        positionMs: Long = 0L,
        durationMs: Long = 0L,
        isCompleted: Boolean = false,
        completedAt: Long? = null
    ) = WatchHistoryEntity(
        id = id,
        parentId = parentId,
        type = type,
        name = name,
        poster = null,
        streamUrl = null,
        positionMs = positionMs,
        durationMs = durationMs,
        updatedAt = 1_700_000_000_000L,
        isCompleted = isCompleted,
        completedAt = completedAt
    )

    @Test
    fun `finished runtime counts completed durations only`() = runBlocking {
        // One completed episode (40 min) and one in progress at 10 min: the
        // finished runtime must not include the open episode's position.
        dao.upsertRaw(
            row("a", "show1", positionMs = 2_400_000L, durationMs = 2_400_000L, isCompleted = true)
        )
        dao.upsertRaw(row("b", "show1", positionMs = 600_000L, durationMs = 2_400_000L))

        assertEquals(2_400_000L, dao.finishedRuntimeMs())
        assertEquals(1, dao.completedEpisodeCount())
        assertEquals(0, dao.completedMovieCount())
    }

    @Test
    fun `an in-progress row adds its position to its show's top-show total`() = runBlocking {
        dao.upsertRaw(
            row(
                "a", "show1", name = "Show One",
                positionMs = 2_400_000L, durationMs = 2_400_000L, isCompleted = true
            )
        )
        dao.upsertRaw(row("b", "show1", name = "Show One", positionMs = 600_000L))

        val top = dao.topShows().single()
        assertEquals("show1", top.parentId)
        assertEquals(3_000_000L, top.ms)
        assertEquals(1, top.done)
    }

    @Test
    fun `a rewatched episode is one row and counts once`() = runBlocking {
        // The writer updates a single row per episode in place, so a rewatch
        // is the same id being written again.
        dao.upsertRaw(
            row(
                "ep1", "show1",
                positionMs = 1_000L, durationMs = 2_400_000L, isCompleted = true
            )
        )
        // Rewatch overwrites the row's position; the completion stays one.
        dao.upsertRaw(
            row(
                "ep1", "show1",
                positionMs = 2_400_000L, durationMs = 2_400_000L, isCompleted = true
            )
        )

        assertEquals(1, dao.completedEpisodeCount())
        assertEquals(2_400_000L, dao.finishedRuntimeMs())
    }

    @Test
    fun `top shows are ordered by runtime and capped`() = runBlocking {
        repeat(12) { i ->
            dao.upsertRaw(
                row(
                    id = "m$i",
                    parentId = "movie$i",
                    type = "movie",
                    name = "Movie $i",
                    positionMs = 1_000L * (i + 1),
                    durationMs = 1_000L * (i + 1),
                    isCompleted = true
                )
            )
        }

        val top = dao.topShows()
        assertEquals(10, top.size)
        assertEquals("movie11", top.first().parentId)
    }

    @Test
    fun `completion times are newest first and exclude rows without one`() = runBlocking {
        dao.upsertRaw(row("a", "show1", isCompleted = true, completedAt = 100L))
        dao.upsertRaw(row("b", "show1", isCompleted = true, completedAt = 300L))
        dao.upsertRaw(row("c", "show2", isCompleted = false, completedAt = null))
        dao.upsertRaw(row("d", "show2", isCompleted = true, completedAt = 200L))

        // Newest first, and the in-progress row's null timestamp is excluded.
        assertEquals(listOf(300L, 200L, 100L), dao.completionTimes())
        // Two DISTINCT titles completed: show1 (twice) and show2.
        assertEquals(2, dao.finishedTitleCount())
    }
}
