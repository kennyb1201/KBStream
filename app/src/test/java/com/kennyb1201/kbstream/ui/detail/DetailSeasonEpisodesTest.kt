package com.kennyb1201.kbstream.ui.detail

import com.kennyb1201.kbstream.data.tmdb.ResolvedEpisode
import com.kennyb1201.kbstream.data.tmdb.TmdbDetail
import com.kennyb1201.kbstream.data.tmdb.TmdbEpisodeAirInfo
import com.kennyb1201.kbstream.data.tmdb.TmdbSeasonSummary
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which episode numbers a season-wide mark may cover.
 *
 * The loaded season's half was already right (drop the dated-future rows); the
 * unloaded half was `1..episode_count` for EVERY season, so "Mark Entire
 * Series as Watched" and a season chip on a never-loaded, still-airing season
 * wrote next week's episode into the local history and pushed it to Simkl,
 * MDBList and Trakt. `next_episode_to_air` is the fact that closes it without a
 * per-season request.
 */
class DetailSeasonEpisodesTest {

    private val today = LocalDate.of(2026, 10, 19)

    private fun episode(number: Int, airDate: String? = null) = ResolvedEpisode(
        streamId = "tt100:2:$number",
        episodeNumber = number,
        name = "Episode $number",
        overview = null,
        thumbnail = null,
        runtimeMinutes = 45,
        airDate = airDate,
        voteAverage = null
    )

    private fun numbers(
        season: Int,
        loadedSeason: Int? = 1,
        episodes: List<ResolvedEpisode> = emptyList(),
        episodeCount: Int? = 10,
        seasonAirDate: String? = null,
        next: TmdbEpisodeAirInfo? = null,
        airDatesTrusted: Boolean = true
    ) = DetailSeasonEpisodes.numbers(
        season = season,
        loadedSeason = loadedSeason,
        episodes = episodes,
        episodeCount = episodeCount,
        seasonAirDate = seasonAirDate,
        nextEpisodeToAir = next,
        airDatesTrusted = airDatesTrusted,
        today = today
    )

    @Test
    fun `the loaded season drops the episodes that have not aired`() {
        val episodes = listOf(
            episode(1, "2026-10-01"),
            episode(2, "2026-10-08"),
            episode(3, "2026-10-26")
        )
        assertEquals(listOf(1, 2), numbers(season = 1, episodes = episodes))
    }

    @Test
    fun `untrusted dates keep the loaded listing whole`() {
        val episodes = listOf(episode(1, "2026-10-01"), episode(2, "2026-12-01"))
        assertEquals(
            listOf(1, 2),
            numbers(season = 1, episodes = episodes, airDatesTrusted = false)
        )
    }

    @Test
    fun `an unloaded season that is still airing stops before the next episode`() {
        // Season 2 is on screen (loaded), season 3 never was: TMDB's pointer
        // says episode 5 is the next one to air, so 1..4 is all that has aired.
        val next = TmdbEpisodeAirInfo(seasonNumber = 3, episodeNumber = 5)
        assertEquals(
            listOf(1, 2, 3, 4),
            numbers(season = 3, seasonAirDate = "2026-09-01", next = next)
        )
    }

    @Test
    fun `a season after the next episode's season has aired nothing`() {
        val next = TmdbEpisodeAirInfo(seasonNumber = 3, episodeNumber = 5)
        assertEquals(
            emptyList<Int>(),
            numbers(season = 4, seasonAirDate = "2027-01-01", next = next)
        )
    }

    @Test
    fun `a season before the pointer is complete`() {
        val next = TmdbEpisodeAirInfo(seasonNumber = 5, episodeNumber = 2)
        assertEquals((1..10).toList(), numbers(season = 2, next = next))
    }

    @Test
    fun `a season whose premiere is still in the future is refused`() {
        assertEquals(
            emptyList<Int>(),
            numbers(
                season = 4,
                seasonAirDate = "2027-01-05",
                next = TmdbEpisodeAirInfo(seasonNumber = 5, episodeNumber = 1)
            )
        )
    }

    @Test
    fun `a pointer on the season's first episode means the season has not started`() {
        assertEquals(
            emptyList<Int>(),
            numbers(
                season = 3,
                seasonAirDate = "2026-10-20",
                next = TmdbEpisodeAirInfo(seasonNumber = 3, episodeNumber = 1)
            )
        )
    }

    @Test
    fun `no pointer leaves a finished show's count standing`() {
        assertEquals((1..8).toList(), numbers(season = 2, episodeCount = 8, next = null))
    }

    @Test
    fun `a season no source can size is unknown, not an invented range`() {
        assertNull(numbers(season = 2, episodeCount = null))
        assertNull(numbers(season = 2, episodeCount = 0))
    }

    @Test
    fun `the stale-season check still reads the same two TMDB fields`() {
        val stale = DetailSeasonEpisodes.staleSeasons(
            TmdbDetail(
                id = 1,
                seasons = listOf(
                    TmdbSeasonSummary(seasonNumber = 2, airDate = "2026-09-01")
                ),
                nextEpisodeToAir = TmdbEpisodeAirInfo(
                    seasonNumber = 2,
                    airDate = "2026-09-08"
                )
            )
        )
        assertEquals(setOf(2), stale)
    }
}
