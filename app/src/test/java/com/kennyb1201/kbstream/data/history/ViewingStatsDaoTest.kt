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

    // The row's `type` is the PARENT's media type, copied from the launch
    // intent by the player (see NativePlayerActivity's history write) and from
    // the detail screen for a Detail-side mark. An episode is therefore filed
    // under the show's own type - "series", "tv", an addon's flavour - and
    // never under "episode"; a fixture that says "episode" is a fixture the
    // real app can never produce, and it hid a query that matched nothing.
    private fun row(
        id: String,
        parentId: String,
        type: String = "series",
        name: String = "Show $parentId",
        positionMs: Long = 0L,
        durationMs: Long = 0L,
        isCompleted: Boolean = false,
        completedAt: Long? = null,
        markedWatched: Boolean = false
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
        completedAt = completedAt,
        markedWatched = markedWatched
    )

    /**
     * A mark row exactly as the mark paths write it: completed, duration 1 ms,
     * no position - and the explicit flag that says it is a mark.
     */
    private fun mark(
        id: String,
        parentId: String,
        type: String = "series",
        completedAt: Long = 1_700_000_000_000L
    ) = row(
        id = id,
        parentId = parentId,
        type = type,
        positionMs = 0L,
        durationMs = 1L,
        isCompleted = true,
        completedAt = completedAt,
        markedWatched = true
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
    fun `episodes are counted by every show flavour and movies only as movies`() = runBlocking {
        dao.upsertRaw(row("s1", "show1", type = "series", isCompleted = true))
        dao.upsertRaw(row("t1", "show2", type = "tv", isCompleted = true))
        dao.upsertRaw(row("k1", "show3", type = "kitsu", isCompleted = true))
        dao.upsertRaw(row("m1", "movie1", type = "movie", isCompleted = true))
        // A live channel is neither an episode nor a movie, and a completed
        // row is not something a channel produces at all - it must not be
        // swept into the episode count by "anything that is not a movie".
        dao.upsertRaw(row("c1", "chan1", type = "channel", isCompleted = true))
        // Uppercase is a flavour the same type can arrive in.
        dao.upsertRaw(row("m2", "movie2", type = "Movie", isCompleted = true))
        // In-progress rows of either kind are not finished anything.
        dao.upsertRaw(row("s9", "show1", type = "series", positionMs = 1_000L))
        dao.upsertRaw(row("m9", "movie3", type = "movie", positionMs = 1_000L))

        assertEquals(3, dao.completedEpisodeCount())
        assertEquals(2, dao.completedMovieCount())
        // Titles finished still counts every completed parent, channels
        // included - it is the distinct-parent number, not a per-kind one.
        assertEquals(6, dao.finishedTitleCount())
    }

    // ── Sweep 5: a mark is not a watch ──────────────────────────────────

    @Test
    fun `marked-watched rows move none of the six reads`() = runBlocking {
        // One real completion (a 42-minute episode) beside a whole marked
        // season: those five marks carry the 1 ms / 0 position sentinel and the
        // explicit flag. They must not count as finished episodes, titles,
        // movies, runtime, completion days or top-show episodes.
        dao.upsertRaw(
            row(
                "ep1", "show1", name = "Show One",
                positionMs = 2_520_000L, durationMs = 2_520_000L,
                isCompleted = true, completedAt = 1_700_000_100_000L
            )
        )
        (1..5).forEach { episode ->
            dao.upsertRaw(
                mark(
                    id = "mark:show2:s1:e$episode",
                    parentId = "show2",
                    completedAt = 1_700_000_200_000L + episode
                )
            )
        }

        assertEquals("only the real finish is a finished episode", 1, dao.completedEpisodeCount())
        assertEquals("and the marked show is not a finished title", 1, dao.finishedTitleCount())
        assertEquals("no marker may invent a finished movie", 0, dao.completedMovieCount())
        assertEquals(
            "runtime is the real duration only - the marks contribute 1 ms each",
            2_520_000L,
            dao.finishedRuntimeMs()
        )
        assertEquals(
            "the streak sees the real completion day, not five mark days",
            listOf(1_700_000_100_000L),
            dao.completionTimes()
        )

        val show1 = dao.topShows().single { it.parentId == "show1" }
        assertEquals(1, show1.done)
        assertEquals(2_520_000L, show1.ms)
        // The marked show must not rank on its marks; a row still exists in
        // the table (badges, Continue Watching, resume and sync read it), it
        // simply contributes nothing to the stats projection.
        val show2 = dao.topShows().firstOrNull { it.parentId == "show2" }
        assertEquals("a mark must not add a finished episode", 0, show2?.done ?: 0)
        assertEquals("nor 5 ms of runtime", 0L, show2?.ms ?: 0L)
    }

    @Test
    fun `a mark promoted to a real completion counts once`() = runBlocking {
        // The player can play a marked episode to the end, which upserts the
        // SAME row in place with the real position/duration and the flag
        // cleared. That watch is then a real one - exactly once, not twice.
        dao.upsertRaw(mark(id = "ep1", parentId = "show1"))
        assertEquals(0, dao.completedEpisodeCount())
        assertEquals(0L, dao.finishedRuntimeMs())

        dao.upsertRaw(
            row(
                "ep1", "show1",
                positionMs = 2_400_000L, durationMs = 2_400_000L,
                isCompleted = true, completedAt = 1_700_000_300_000L,
                markedWatched = false
            )
        )

        assertEquals(1, dao.completedEpisodeCount())
        assertEquals(1, dao.finishedTitleCount())
        assertEquals(2_400_000L, dao.finishedRuntimeMs())
        assertEquals(listOf(1_700_000_300_000L), dao.completionTimes())
        assertEquals(1, dao.topShows().single().done)
    }

    @Test
    fun `a completion with an unknown duration is not treated as a mark`() = runBlocking {
        // The reason the exclusion is an explicit flag rather than a predicate
        // over the sentinel: a player that reached the end without ever
        // learning the file's length writes exactly the sentinel shape
        // (`effectiveDuration = position.coerceAtLeast(1L)`, position reset to
        // 0). That is a real finish and must keep counting.
        dao.upsertRaw(
            row(
                "ep1", "show1",
                positionMs = 0L, durationMs = 1L,
                isCompleted = true, completedAt = 1_700_000_400_000L,
                markedWatched = false
            )
        )

        assertEquals(1, dao.completedEpisodeCount())
        assertEquals(1, dao.finishedTitleCount())
        assertEquals(1L, dao.finishedRuntimeMs())
        assertEquals(listOf(1_700_000_400_000L), dao.completionTimes())
        assertEquals(1, dao.topShows().single().done)
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
