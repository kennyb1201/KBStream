package com.kennyb1201.kbstream.ui.mobile

import com.kennyb1201.kbstream.data.tmdb.ResolvedEpisode
import com.kennyb1201.kbstream.data.tmdb.TmdbDetail
import com.kennyb1201.kbstream.data.tmdb.TmdbEpisodeAirInfo
import com.kennyb1201.kbstream.data.tmdb.TmdbSeasonSummary
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The behaviour behind the phone Detail menus' watched-state arithmetic.
 *
 * These rules are the TV page's own (the port keeps that file untouched, so
 * its inline copies stay where they are); pinning them here is what keeps the
 * phone's reading honest when either side changes.
 */
class MobileDetailRulesTest {

    private val today = LocalDate.of(2026, 10, 19)

    private fun episode(
        number: Int,
        airDate: String? = null,
        id: String = "e$number"
    ) = ResolvedEpisode(
        streamId = id,
        episodeNumber = number,
        name = "Episode $number",
        overview = null,
        thumbnail = null,
        runtimeMinutes = 45,
        airDate = airDate,
        voteAverage = null
    )

    private fun detail(
        seasons: List<TmdbSeasonSummary> = emptyList(),
        next: TmdbEpisodeAirInfo? = null
    ) = TmdbDetail(
        id = 1,
        name = "Show",
        seasons = seasons,
        nextEpisodeToAir = next
    )

    @Test
    fun `the loaded season uses its listing`() {
        val episodes = listOf(episode(1), episode(2), episode(3))
        val numbers = MobileDetailRules.seasonEpisodeNumbers(
            season = 1,
            loadedSeason = 1,
            episodes = episodes,
            episodeCount = 10,
            airDatesTrusted = true,
            today = today
        )
        assertEquals(listOf(1, 2, 3), numbers)
    }

    @Test
    fun `an unaired episode is left out of a season mark`() {
        val episodes = listOf(
            episode(1, "2026-10-01"),
            episode(2, "2026-10-08"),
            episode(3, "2026-11-05")
        )
        val numbers = MobileDetailRules.seasonEpisodeNumbers(
            season = 1,
            loadedSeason = 1,
            episodes = episodes,
            episodeCount = 3,
            airDatesTrusted = true,
            today = today
        )
        assertEquals(listOf(1, 2), numbers)
    }

    @Test
    fun `untrusted dates keep the whole listing`() {
        val episodes = listOf(
            episode(1, "2026-10-01"),
            episode(2, "2026-11-05")
        )
        val numbers = MobileDetailRules.seasonEpisodeNumbers(
            season = 1,
            loadedSeason = 1,
            episodes = episodes,
            episodeCount = 2,
            airDatesTrusted = false,
            today = today
        )
        assertEquals(listOf(1, 2), numbers)
    }

    @Test
    fun `a season that is not on screen falls back to its episode count`() {
        val numbers = MobileDetailRules.seasonEpisodeNumbers(
            season = 3,
            loadedSeason = 1,
            episodes = listOf(episode(1)),
            episodeCount = 8,
            airDatesTrusted = true,
            today = today
        )
        assertEquals((1..8).toList(), numbers)
    }

    @Test
    fun `a season no source knows the size of has no numbers`() {
        assertNull(
            MobileDetailRules.seasonEpisodeNumbers(
                season = 3,
                loadedSeason = 1,
                episodes = listOf(episode(1)),
                episodeCount = null,
                airDatesTrusted = true,
                today = today
            )
        )
        assertNull(
            MobileDetailRules.seasonEpisodeNumbers(
                season = 3,
                loadedSeason = 1,
                episodes = listOf(episode(1)),
                episodeCount = 0,
                airDatesTrusted = true,
                today = today
            )
        )
    }

    @Test
    fun `zero numbered rows are dropped`() {
        val numbers = MobileDetailRules.seasonEpisodeNumbers(
            season = 1,
            loadedSeason = 1,
            episodes = listOf(episode(0), episode(1)),
            episodeCount = 1,
            airDatesTrusted = true,
            today = today
        )
        assertEquals(listOf(1), numbers)
    }

    @Test
    fun `the whole series skips the seasons it has no numbers for`() {
        val numbers = MobileDetailRules.wholeSeriesNumbers(listOf(1, 2, 3)) { season ->
            when (season) {
                1 -> listOf(1, 2)
                2 -> null
                else -> emptyList()
            }
        }
        assertEquals(listOf(1 to listOf(1, 2)), numbers)
    }

    @Test
    fun `a season counts as watched only when every aired episode is`() {
        assertTrue(MobileDetailRules.allWatched(listOf(1, 2), setOf(1, 2, 3)))
        assertFalse(MobileDetailRules.allWatched(listOf(1, 2), setOf(1)))
        assertFalse(
            "an empty season is never 'watched' - that is the state a show " +
                "with no known episodes would otherwise report",
            MobileDetailRules.allWatched(emptyList(), setOf(1, 2))
        )
    }

    @Test
    fun `the whole series toggles only when every season is fully watched`() {
        val series = listOf(1 to listOf(1, 2), 2 to listOf(1))
        assertTrue(
            MobileDetailRules.wholeSeriesWatched(series) { season ->
                if (season == 1) setOf(1, 2) else setOf(1)
            }
        )
        assertFalse(
            MobileDetailRules.wholeSeriesWatched(series) { season ->
                if (season == 1) setOf(1, 2) else emptySet()
            }
        )
        assertFalse(
            "no known episodes is not a finished show",
            MobileDetailRules.wholeSeriesWatched(emptyList()) { emptySet() }
        )
    }

    @Test
    fun `a season whose row disagrees with the show pointer is stale`() {
        val stale = MobileDetailRules.staleSeasonDates(
            detail(
                seasons = listOf(
                    TmdbSeasonSummary(seasonNumber = 2, airDate = "2026-09-01")
                ),
                next = TmdbEpisodeAirInfo(
                    seasonNumber = 2,
                    airDate = "2026-09-08"
                )
            )
        )
        assertEquals(setOf(2), stale)
    }

    @Test
    fun `a season whose row agrees with the pointer is trusted`() {
        val stale = MobileDetailRules.staleSeasonDates(
            detail(
                seasons = listOf(
                    TmdbSeasonSummary(seasonNumber = 2, airDate = "2026-09-08")
                ),
                next = TmdbEpisodeAirInfo(
                    seasonNumber = 2,
                    airDate = "2026-09-08"
                )
            )
        )
        assertTrue(stale.isEmpty())
    }

    @Test
    fun `specials sort after the numbered seasons and the numbers stay ascending`() {
        val ordered = MobileDetailRules.seasonsInChipOrder(
            listOf(
                TmdbSeasonSummary(seasonNumber = 0, name = "Specials"),
                TmdbSeasonSummary(seasonNumber = 2),
                TmdbSeasonSummary(seasonNumber = 1),
                TmdbSeasonSummary(seasonNumber = 3)
            )
        )
        assertEquals(listOf(1, 2, 3, 0), ordered.map { it.seasonNumber })
        assertEquals("Specials", ordered.last().name)

        // A show with nothing but a season 0 keeps it: the bucket is then the
        // show's only listing, not something to hide.
        assertEquals(
            listOf(0),
            MobileDetailRules.seasonsInChipOrder(
                listOf(TmdbSeasonSummary(seasonNumber = 0))
            ).map { it.seasonNumber }
        )
        assertTrue(MobileDetailRules.seasonsInChipOrder(emptyList()).isEmpty())
    }

    @Test
    fun `no pointer, no unparseable answer and no row all mean nothing is stale`() {
        assertTrue(MobileDetailRules.staleSeasonDates(null).isEmpty())
        assertTrue(
            MobileDetailRules.staleSeasonDates(
                detail(seasons = listOf(TmdbSeasonSummary(seasonNumber = 1)))
            ).isEmpty()
        )
        assertTrue(
            MobileDetailRules.staleSeasonDates(
                detail(
                    seasons = listOf(TmdbSeasonSummary(seasonNumber = 3, airDate = "x"))
                )
            ).isEmpty()
        )
        assertTrue(
            MobileDetailRules.staleSeasonDates(
                detail(
                    seasons = listOf(TmdbSeasonSummary(seasonNumber = 1)),
                    next = TmdbEpisodeAirInfo(seasonNumber = 2, airDate = "2026-10-01")
                )
            ).isEmpty()
        )
    }
}
