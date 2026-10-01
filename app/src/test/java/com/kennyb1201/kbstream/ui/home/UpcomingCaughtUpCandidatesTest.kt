package com.kennyb1201.kbstream.ui.home

import com.kennyb1201.kbstream.data.history.WatchHistoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which shows the Upcoming rail asks TMDB about, for the two sources that do
 * not need Simkl: this profile's own completed watch history, and MDBList's
 * watched snapshot.
 *
 * The rail only advertises a caught-up show's next unaired episode, and a
 * caught-up show is on no other rail - so without these the rail would be empty
 * for a local-only or MDBList-only viewer. What is pinned here is the SELECTION
 * (one candidate per show, newest first, capped) and the evidence it hands the
 * caught-up rule: a candidate whose watched episodes are incomplete is a show
 * the rule will reject, but a candidate built from rows that name no episode, or
 * from a "started" show with no watched episodes, is a question that should
 * never have been asked.
 */
class UpcomingCaughtUpCandidatesTest {

    private fun row(
        id: String,
        parentId: String,
        season: Int?,
        episode: Int?,
        completedAt: Long,
        type: String = "series"
    ) = WatchHistoryEntity(
        id = id,
        parentId = parentId,
        type = type,
        name = parentId,
        poster = null,
        streamUrl = null,
        season = season,
        episode = episode,
        positionMs = 0L,
        durationMs = 1_000_000L,
        updatedAt = completedAt,
        isCompleted = true,
        completedAt = completedAt
    )

    // ── this profile's own history ──────────────────────────────────────

    @Test
    fun `one candidate per show, in the order the query returned them`() {
        // Rows arrive ordered by completion time (getCompletedSeriesRows), so
        // the candidates inherit that order and the cap trims the OLD end of a
        // long history - the same end the Continue Watching rail's local pass
        // trims, so the two halves of the rail agree about which shows matter most.
        val candidates = localCaughtUpCandidates(
            rows = listOf(
                row("e2", "tt1", 1, 2, completedAt = 300),
                row("e3", "tt2", 1, 1, completedAt = 200),
                row("e1", "tt1", 1, 1, completedAt = 100)
            ),
            max = 10
        )

        assertEquals(listOf("tt1", "tt2"), candidates.map { it.parentId })
    }

    @Test
    fun `every completed episode of a show is the evidence`() {
        val candidates = localCaughtUpCandidates(
            rows = listOf(
                row("e1", "tt1", 1, 1, completedAt = 100),
                row("e2", "tt1", 1, 2, completedAt = 200),
                row("e3", "tt1", 2, 1, completedAt = 300)
            ),
            max = 10
        )

        assertEquals(
            setOf(1 to 1, 1 to 2, 2 to 1),
            candidates.single().watchedEpisodes
        )
    }

    @Test
    fun `the cap keeps the newest shows`() {
        val candidates = localCaughtUpCandidates(
            rows = listOf(
                row("c", "tt3", 1, 1, completedAt = 30),
                row("b", "tt2", 1, 1, completedAt = 20),
                row("a", "tt1", 1, 1, completedAt = 10)
            ),
            max = 2
        )

        assertEquals(listOf("tt3", "tt2"), candidates.map { it.parentId })
    }

    @Test
    fun `a movie is never a caught-up candidate`() {
        // There is no next episode of a film to wait for.
        val candidates = localCaughtUpCandidates(
            rows = listOf(
                row("m", "tt9", null, null, completedAt = 10, type = "movie")
            ),
            max = 10
        )

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `a row that names no episode proves nothing`() {
        // Episode 0 is the sources' way of saying "no episode" (see
        // namedEpisodeNumber), and a row with no season cannot be placed in
        // the run of episodes the caught-up rule walks. Counting either as
        // watched would let a show read as caught up on evidence that does
        // not exist.
        val candidates = localCaughtUpCandidates(
            rows = listOf(
                row("a", "tt1", 1, 0, completedAt = 10),
                row("b", "tt2", null, 4, completedAt = 20),
                row("c", "tt2", 2, null, completedAt = 30)
            ),
            max = 10
        )

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `a row with no parent id is skipped`() {
        val candidates = localCaughtUpCandidates(
            rows = listOf(
                row("a", "   ", 1, 1, completedAt = 10),
                row("b", "tt2", 1, 1, completedAt = 20)
            ),
            max = 10
        )

        assertEquals(listOf("tt2"), candidates.map { it.parentId })
    }

    @Test
    fun `no room for candidates means no lookups`() {
        assertTrue(
            localCaughtUpCandidates(
                rows = listOf(row("a", "tt1", 1, 1, completedAt = 10)),
                max = 0
            ).isEmpty()
        )
    }

    // ── MDBList's snapshot ──────────────────────────────────────────────

    @Test
    fun `both id forms of a show become candidates`() {
        // addKey writes an imdb key and a tmdb key for the same show, and both
        // are kept on purpose: dropping either would lose shows the tracker
        // knows by only one id, and the schedule builder collapses two rows
        // for one show (both resolve to the same TMDB id).
        val candidates = mdbListCaughtUpCandidates(
            startedShowKeys = setOf("tt1234567", "tmdb:456"),
            episodeKeys = setOf(
                "tt1234567:1:1",
                "tt1234567:1:2",
                "tmdb:456:1:1",
                "tmdb:456:1:2"
            ),
            max = 10
        )

        assertEquals(listOf("tmdb:456", "tt1234567"), candidates.map { it.parentId })
        assertEquals(setOf(1 to 1, 1 to 2), candidates.first().watchedEpisodes)
        assertTrue(candidates.all { it.fromTracker })
    }

    @Test
    fun `a show id cannot swallow a longer one`() {
        // "tt123" must not collect "tt1234"'s episodes: the key is matched
        // with its colon attached.
        val candidates = mdbListCaughtUpCandidates(
            startedShowKeys = setOf("tt123"),
            episodeKeys = setOf("tt1234:1:1", "tt12345:2:3"),
            max = 10
        )

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `a started show with no watched episodes is skipped`() {
        // The snapshot's show list only proves the tracker has seen progress;
        // a show with no episode entries has no run to call caught up.
        val candidates = mdbListCaughtUpCandidates(
            startedShowKeys = setOf("tt1", "tt2"),
            episodeKeys = setOf("tt2:1:1"),
            max = 10
        )

        assertEquals(listOf("tt2"), candidates.map { it.parentId })
    }

    @Test
    fun `a zero-numbered episode key is ignored`() {
        val candidates = mdbListCaughtUpCandidates(
            startedShowKeys = setOf("tt1", "tt2"),
            episodeKeys = setOf("tt1:1:0", "tt2:0:5"),
            max = 10
        )

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun `the mdb list cap bounds the work`() {
        val candidates = mdbListCaughtUpCandidates(
            startedShowKeys = setOf("tt1", "tt2", "tt3"),
            episodeKeys = setOf("tt1:1:1", "tt2:1:1", "tt3:1:1"),
            max = 2
        )

        assertEquals(2, candidates.size)
    }
}
