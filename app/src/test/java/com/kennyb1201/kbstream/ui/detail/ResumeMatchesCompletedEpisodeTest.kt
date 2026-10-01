package com.kennyb1201.kbstream.ui.detail

import com.kennyb1201.kbstream.data.history.WatchHistoryEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether a RESUME row names an episode the local history already shows
 * finished.
 *
 * The bug this pins: after an episode finished in the player, a Simkl/MDBList
 * paused-session resume for that same episode was never dropped on the way back
 * to Detail - the hero's RESUME bar and the episode card's progress bar both
 * fall back to that row - so the card stayed mid-progress next to its own
 * checkmark until the screen was reopened past the freshness window. The two
 * sides name the episode differently (the player keys on episodeStreamId, cloud
 * rows may carry only season/episode), which is why either match counts.
 */
class ResumeMatchesCompletedEpisodeTest {

    private fun entity(
        id: String,
        season: Int? = null,
        episode: Int? = null,
        episodeStreamId: String? = null,
        isCompleted: Boolean = true
    ) = WatchHistoryEntity(
        id = id,
        parentId = "tt123",
        type = "series",
        name = "Show",
        poster = null,
        streamUrl = null,
        season = season,
        episode = episode,
        episodeStreamId = episodeStreamId,
        positionMs = 0L,
        durationMs = 1L,
        updatedAt = 0L,
        isCompleted = isCompleted,
        completedAt = 0L
    )

    private fun resume(
        season: Int? = null,
        episode: Int? = null,
        episodeStreamId: String? = null
    ) = entity(
        id = "resume",
        season = season,
        episode = episode,
        episodeStreamId = episodeStreamId,
        isCompleted = false
    )

    @Test
    fun `the same stream id is the same episode`() {
        // The player's own rows are keyed by episodeStreamId, so this is the
        // match the finished-episode case normally takes.
        assertTrue(
            resumeMatchesCompletedEpisode(
                resume = resume(episodeStreamId = "tt123:2:5"),
                completedEntries = listOf(entity("done", episodeStreamId = "tt123:2:5"))
            )
        )
    }

    @Test
    fun `a cloud row with no stream id still matches on season and episode`() {
        // A tracker resume carries the episode numbers but not this app's
        // stream id; the numbers are what has to carry the match.
        assertTrue(
            resumeMatchesCompletedEpisode(
                resume = resume(season = 2, episode = 5, episodeStreamId = null),
                completedEntries = listOf(entity("done", season = 2, episode = 5))
            )
        )
    }

    @Test
    fun `a different episode is left alone`() {
        // The viewer finished S2E5; the cloud session is parked on S2E6. That
        // resume is still live and must not be cleared.
        assertFalse(
            resumeMatchesCompletedEpisode(
                resume = resume(season = 2, episode = 5),
                completedEntries = listOf(entity("done", season = 2, episode = 6))
            )
        )
    }

    @Test
    fun `nothing completed means nothing matches`() {
        assertFalse(
            resumeMatchesCompletedEpisode(
                resume = resume(season = 1, episode = 1, episodeStreamId = "tt123:1:1"),
                completedEntries = emptyList()
            )
        )
    }

    @Test
    fun `a blank stream id does not match another blank one`() {
        // Two rows that both carry no stream id and no episode numbers must not
        // be read as the same episode.
        assertFalse(
            resumeMatchesCompletedEpisode(
                resume = resume(episodeStreamId = "  "),
                completedEntries = listOf(entity("done", episodeStreamId = "  "))
            )
        )
    }

    @Test
    fun `a completed row missing one half still matches by stream id`() {
        // The marker the app writes may not carry season/episode; the identity
        // the UI keys progress on is the stream id.
        assertTrue(
            resumeMatchesCompletedEpisode(
                resume = resume(season = 3, episode = 1, episodeStreamId = "tt123:3:1"),
                completedEntries = listOf(entity("done", season = null, episode = null, episodeStreamId = "tt123:3:1"))
            )
        )
    }

    @Test
    fun `a half-numbered resume matches on the pair it does have`() {
        assertTrue(
            resumeMatchesCompletedEpisode(
                resume = resume(season = 4, episode = 2),
                completedEntries = listOf(entity("done", season = 4, episode = 2))
            )
        )
        assertFalse(
            resumeMatchesCompletedEpisode(
                resume = resume(season = 4, episode = 2),
                completedEntries = listOf(entity("done", season = 4, episode = 3))
            )
        )
    }
}
