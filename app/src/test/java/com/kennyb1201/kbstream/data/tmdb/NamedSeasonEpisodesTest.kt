package com.kennyb1201.kbstream.data.tmdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 0-filter that runs on the way OUT of the season cache.
 *
 * [TmdbRepository.getSeasonEpisodes] refuses an episode a source never named
 * while it builds the list from TMDB, but the list is then kept for twelve
 * hours in memory and for a week on disk - so a list cached by a build that
 * predates the rule kept handing its "episode 0" row back. Reported as the
 * report coming back after the fix: Continue Watching resolved the show onto
 * that row again ("S02 · E00") and the season browser drew it as a blank chip
 * reading EPISODE 0. The filter is re-applied on every read for exactly that
 * reason, and this pins it.
 */
class NamedSeasonEpisodesTest {

    private fun episode(number: Int, name: String? = null) = ResolvedEpisode(
        streamId = "tt123:2:$number",
        episodeNumber = number,
        name = name,
        overview = null,
        thumbnail = null,
        runtimeMinutes = null,
        airDate = null,
        voteAverage = null
    )

    @Test
    fun `a cached episode numbered zero is dropped on the way out`() {
        val cached = listOf(
            episode(0, "Special"),
            episode(1, "Pilot"),
            episode(2, "Second")
        )

        assertEquals(listOf(1, 2), cached.namedEpisodesOnly().map { it.episodeNumber })
    }

    @Test
    fun `a zero a source did name an episode around is still dropped`() {
        // The name and the still do not make it an episode: TMDB files a
        // "- Specials" row inside a regular season numbered 0, and the number
        // is what the card and every key are built from.
        val cached = listOf(episode(0, "Behind the scenes"))

        assertEquals(emptyList<Int>(), cached.namedEpisodesOnly().map { it.episodeNumber })
    }

    @Test
    fun `negative numbers are not episodes either`() {
        val cached = listOf(episode(-1), episode(1))

        assertEquals(listOf(1), cached.namedEpisodesOnly().map { it.episodeNumber })
    }

    @Test
    fun `a season with no zero at all comes back untouched`() {
        val cached = listOf(episode(1), episode(2), episode(3))

        assertEquals(cached, cached.namedEpisodesOnly())
    }

    @Test
    fun `an empty season stays empty`() {
        assertEquals(emptyList<ResolvedEpisode>(), emptyList<ResolvedEpisode>().namedEpisodesOnly())
    }

    // ── what may be kept as TMDB's answer ───────────────────────────────

    @Test
    fun `a real episode list is an answer`() {
        assertTrue(seasonEpisodesAreAnAnswer(listOf(episode(1), episode(2))))
        assertTrue(seasonEpisodesAreAnAnswer(listOf(episode(7))))
    }

    @Test
    fun `an empty list is not an answer`() {
        // The season cache holds a lookup that never answered (a rate limit, a
        // dropped connection) and a season TMDB has no episodes for as the
        // same empty list. Remembering it is what pinned "No episodes found
        // for this season." on every season of a show for a week, and what
        // left Continue Watching with no episode to resolve for it.
        assertFalse(seasonEpisodesAreAnAnswer(emptyList()))
        assertFalse(seasonEpisodesAreAnAnswer(null))
    }

    @Test
    fun `a list of only unnamed rows is not an answer either`() {
        // Read the way the cache reads: the filter runs first, and what is
        // left of a list of nothing but "episode 0" rows is empty.
        val onlyUnnamed = listOf(episode(0, "Special"), episode(-1))

        assertFalse(seasonEpisodesAreAnAnswer(onlyUnnamed.namedEpisodesOnly()))
    }
}
