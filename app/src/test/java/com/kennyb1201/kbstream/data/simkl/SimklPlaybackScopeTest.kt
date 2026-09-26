package com.kennyb1201.kbstream.data.simkl

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which paused Simkl sessions a mark-watched is allowed to close.
 *
 * Reported bug: marking ONE episode watched from its card left that episode's
 * progress bar up AND left the show sitting in Continue Watching. Closing the
 * paused sessions was the part that only worked by accident - the shared
 * completion path is progress-gated at 95%, so a session paused earlier than
 * that was never closed and kept feeding the rail a card at its old position.
 *
 * The fix closes them for an explicit mark whatever the progress, but has to
 * be scoped: the show may legitimately be part-way through a DIFFERENT
 * episode, and that one belongs on the rail.
 */
class SimklPlaybackScopeTest {

    private fun session(
        season: Int?,
        episode: Int?,
        progress: Float = 40f
    ) = SimklPlaybackItem(
        id = 1,
        pausedAt = "2026-09-26T20:00:00Z",
        progress = progress,
        movie = null,
        // Ids are irrelevant to this rule: the parent match has already
        // happened by the time it runs.
        show = SimklPlaybackShow(
            title = "The Show",
            year = 2024,
            poster = null,
            ids = null
        ),
        episode = SimklPlaybackEpisode(
            title = "Some Episode",
            season = season,
            episode = episode
        )
    )

    @Test
    fun `a null scope means the whole show, so every session matches`() {
        // The series mark sweeps the parent: nothing about the show is left
        // to resume, whatever episode the session names.
        assertTrue(playbackSessionMatchesEpisodeScope(session(3, 1), null))
        assertTrue(playbackSessionMatchesEpisodeScope(session(3, 9), null))
        assertTrue(playbackSessionMatchesEpisodeScope(session(null, null), null))
    }

    @Test
    fun `the marked episode's session matches`() {
        assertTrue(
            playbackSessionMatchesEpisodeScope(
                item = session(13, 1),
                seasonsEpisodes = setOf(13 to 1)
            )
        )
    }

    @Test
    fun `another episode's session is left alone`() {
        // The reason the scope exists: marking S13E1 watched must not clear
        // the resume point of S13E2, which is still being watched.
        assertFalse(
            playbackSessionMatchesEpisodeScope(
                item = session(13, 2),
                seasonsEpisodes = setOf(13 to 1)
            )
        )
        assertFalse(
            playbackSessionMatchesEpisodeScope(
                item = session(12, 1),
                seasonsEpisodes = setOf(13 to 1)
            )
        )
    }

    @Test
    fun `a whole season marks each of its episodes`() {
        val season = setOf(3 to 1, 3 to 2, 3 to 3)

        assertTrue(
            playbackSessionMatchesEpisodeScope(session(3, 2), season)
        )
        assertFalse(
            playbackSessionMatchesEpisodeScope(session(3, 4), season)
        )
        assertFalse(
            playbackSessionMatchesEpisodeScope(session(4, 1), season)
        )
    }

    @Test
    fun `a session with no episode numbers is not deleted on a guess`() {
        // Deleting something we cannot attribute to an episode could take a
        // legitimately in-progress episode off the rail.
        assertFalse(
            playbackSessionMatchesEpisodeScope(
                item = session(season = null, episode = null),
                seasonsEpisodes = setOf(13 to 1)
            )
        )
        assertFalse(
            playbackSessionMatchesEpisodeScope(
                item = session(season = 13, episode = null),
                seasonsEpisodes = setOf(13 to 1)
            )
        )
        assertFalse(
            playbackSessionMatchesEpisodeScope(
                item = session(season = null, episode = 1),
                seasonsEpisodes = setOf(13 to 1)
            )
        )
    }
}
