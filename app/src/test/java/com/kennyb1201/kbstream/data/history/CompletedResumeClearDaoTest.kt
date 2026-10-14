package com.kennyb1201.kbstream.data.history

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The completion clears the episode's RESUME point, on a real (in-memory) Room
 * database.
 *
 * The field report: pause an episode with ten minutes left (a resume point is
 * saved), resume from it, watch to the end - and the show still comes back to
 * that ten-minutes-left resume point, with the episode not marked watched.
 *
 * A completion and a resume point are the SAME row in this table, so the
 * everyday case clears the resume point by overwriting it (`positionMs = 0`,
 * `isCompleted = 1`). What this test pins is the case the overwrite cannot
 * reach - the resume row filed under a DIFFERENT row id than the completion,
 * which is what an add-on's video id against the numbered key, or an earlier
 * session's own id flavour, leaves behind. Continue Watching and the Detail
 * screen's progress bar are both built from exactly those rows (see
 * [WatchHistoryDao.getResumeRowsForLauncher] / `getInProgressForParents`), so
 * one left behind is the episode showing a checkmark AND offering to resume.
 *
 * The SQL is what is being verified, not the rule: a wrong WHERE here is a
 * deleted row that should have stayed (another episode's progress) or a
 * survivor that should have gone, and every pure test would still pass.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = CompletedResumeClearDaoTest.NoopApplication::class)
class CompletedResumeClearDaoTest {

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
        positionMs: Long,
        isCompleted: Boolean = false,
        parentId: String = "tt10986410",
        season: Int? = 3,
        episode: Int? = 4,
        episodeStreamId: String? = "tt10986410:3:4",
        type: String = "series"
    ) = WatchHistoryEntity(
        id = id,
        parentId = parentId,
        type = type,
        name = "Show",
        poster = null,
        streamUrl = null,
        season = season,
        episode = episode,
        episodeStreamId = episodeStreamId,
        positionMs = positionMs,
        durationMs = 2_400_000L,
        updatedAt = 1_700_000_000_000L,
        isCompleted = isCompleted
    )

    /** What [PlaybackHistoryWriter] does for a completion: find, then delete. */
    private suspend fun clearSuperseded(
        parents: List<String>,
        streamId: String?,
        season: Int?,
        episode: Int?
    ): List<String> {
        val ids = dao.getSupersededResumeIds(parents, streamId, season, episode)
        if (ids.isNotEmpty()) dao.deleteByIds(ids)
        return ids
    }

    @Test
    fun `a resume row under another row id is deleted, not just overwritten`() = runBlocking {
        // The ten-minutes-left resume point, filed by an earlier session under
        // its own id.
        dao.upsertRaw(row(id = "resume-flavour", positionMs = 2_040_000L))

        // The resumed session completes: a completion row under a DIFFERENT id,
        // which is the row id this launch resolved.
        dao.upsertRaw(row(id = "completion-flavour", positionMs = 0L, isCompleted = true))

        val cleared = clearSuperseded(
            parents = listOf("tt10986410"),
            streamId = "tt10986410:3:4",
            season = 3,
            episode = 4
        )

        assertEquals(listOf("resume-flavour"), cleared)
        assertNull(
            "the resume point must be gone, not merely overwritten",
            dao.getById("resume-flavour")
        )
        assertNotNull(
            "the watched marker stays",
            dao.getById("completion-flavour")?.takeIf { it.isCompleted }
        )
        assertTrue(
            "and nothing is left on the launcher's resume rail",
            dao.getResumeRowsForLauncher().isEmpty()
        )
    }

    @Test
    fun `a resume row is found by its stream id alone`() = runBlocking {
        // An add-on's numbering against TMDB's: the stream id names the episode
        // even when the season/episode numbers do not line up, so the identity
        // half that matches here is the stream id - the season/episode arm
        // cannot (the row says S3E0, the completion S3E4).
        dao.upsertRaw(
            row(
                id = "off-by-one",
                season = 3,
                episode = 0,
                episodeStreamId = "tt10986410:3:4",
                positionMs = 500_000L
            )
        )

        val cleared = clearSuperseded(
            parents = listOf("tt10986410"),
            streamId = "tt10986410:3:4",
            season = 3,
            episode = 4
        )

        assertEquals(listOf("off-by-one"), cleared)
        assertNull(dao.getById("off-by-one"))
    }

    @Test
    fun `a resume row is found by its season and episode when the launch carried no stream id`() =
        runBlocking {
            dao.upsertRaw(
                row(
                    id = "numbered",
                    season = 3,
                    episode = 4,
                    episodeStreamId = null,
                    positionMs = 500_000L
                )
            )

            val cleared = clearSuperseded(
                parents = listOf("tt10986410"),
                streamId = null,
                season = 3,
                episode = 4
            )

            assertEquals(listOf("numbered"), cleared)
            assertNull(dao.getById("numbered"))
        }

    @Test
    fun `another episode's resume point is untouched`() = runBlocking {
        dao.upsertRaw(
            row(id = "ep4", season = 3, episode = 4, episodeStreamId = "tt10986410:3:4", positionMs = 2_040_000L)
        )
        dao.upsertRaw(
            row(id = "ep5", season = 3, episode = 5, episodeStreamId = "tt10986410:3:5", positionMs = 900_000L)
        )

        val cleared = clearSuperseded(
            parents = listOf("tt10986410"),
            streamId = "tt10986410:3:4",
            season = 3,
            episode = 4
        )

        assertEquals(listOf("ep4"), cleared)
        assertNotNull(
            "the next episode's progress is not this completion's business",
            dao.getById("ep5")
        )
    }

    @Test
    fun `another title's resume point is untouched`() = runBlocking {
        dao.upsertRaw(
            row(id = "mine", parentId = "tt10986410", positionMs = 2_040_000L)
        )
        dao.upsertRaw(
            row(id = "theirs", parentId = "tt0903747", positionMs = 2_040_000L)
        )

        val cleared = clearSuperseded(
            parents = listOf("tt10986410"),
            streamId = "tt10986410:3:4",
            season = 3,
            episode = 4
        )

        assertEquals(listOf("mine"), cleared)
        assertNotNull(dao.getById("theirs"))
    }

    @Test
    fun `a completed row is never a candidate`() = runBlocking {
        // The same episode already finished: the row carries positionMs 0 and
        // isCompleted 1, so it cannot match however many times a later save
        // runs the cleanup.
        dao.upsertRaw(
            row(id = "done", season = 3, episode = 4, episodeStreamId = "tt10986410:3:4", positionMs = 0L, isCompleted = true)
        )

        val cleared = clearSuperseded(
            parents = listOf("tt10986410"),
            streamId = "tt10986410:3:4",
            season = 3,
            episode = 4
        )

        assertTrue("nothing to clear", cleared.isEmpty())
        assertNotNull(dao.getById("done"))
    }

    @Test
    fun `a session with no episode identity matches nothing`() = runBlocking {
        // A movie (or a show-level row): its own resume row IS the row the
        // completion overwrites, so there is nothing else to find - and a
        // query with both halves absent must not sweep the parent's rows.
        dao.upsertRaw(
            row(
                id = "movie-resume",
                parentId = "tt0133093",
                season = null,
                episode = null,
                episodeStreamId = null,
                positionMs = 600_000L,
                type = "movie"
            )
        )

        val cleared = clearSuperseded(
            parents = listOf("tt0133093"),
            streamId = null,
            season = null,
            episode = null
        )

        assertTrue(cleared.isEmpty())
        assertNotNull(dao.getById("movie-resume"))
    }

    @Test
    fun `an id flavour twin of the parent is not reached by this query`() = runBlocking {
        // Deliberate: the query is scoped to the parent ids the caller passes,
        // and the writer passes the CANONICAL id both engines write. This pins
        // the boundary so the cleanup cannot be widened silently into deleting
        // progress under another parent.
        dao.upsertRaw(
            row(
                id = "other-flavour",
                parentId = "tmdb:1399",
                season = 3,
                episode = 4,
                episodeStreamId = "tt10986410:3:4",
                positionMs = 600_000L
            )
        )

        val cleared = clearSuperseded(
            parents = listOf("tt10986410"),
            streamId = "tt10986410:3:4",
            season = 3,
            episode = 4
        )

        assertTrue(cleared.isEmpty())
        assertNotNull(dao.getById("other-flavour"))
    }
}
