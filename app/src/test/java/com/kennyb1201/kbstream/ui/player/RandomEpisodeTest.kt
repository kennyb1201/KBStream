package com.kennyb1201.kbstream.ui.player

import com.kennyb1201.kbstream.data.tmdb.ResolvedEpisode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import kotlin.random.Random

/**
 * The pick behind the detail page's Random button and the player's random
 * continuation: only already-aired episodes, never the one playing now, and
 * spread across the seasons it is handed.
 */
class RandomEpisodeTest {

    private fun episode(
        number: Int,
        airDate: String? = "2020-01-01",
        name: String? = "Episode $number",
        runtime: Int? = 42,
        streamId: String = "tt100:$number"
    ) = ResolvedEpisode(
        streamId = streamId,
        episodeNumber = number,
        name = name,
        overview = null,
        thumbnail = null,
        runtimeMinutes = runtime,
        airDate = airDate,
        voteAverage = null
    )

    private val seasons = listOf(
        1 to listOf(episode(1), episode(2), episode(3)),
        2 to listOf(episode(1), episode(2))
    )

    @Test
    fun `picks only aired episodes`() {
        val future = LocalDate.now().plusDays(7).toString()
        val pool = listOf(
            1 to listOf(episode(1, airDate = future), episode(2, airDate = future)),
            2 to listOf(episode(1, airDate = "2019-05-05"))
        )
        repeat(50) { seed ->
            val pick = chooseRandomEpisode(pool, null, null, Random(seed))
                ?: error("expected a pick for seed $seed")
            assertEquals(2, pick.season)
            assertEquals(1, pick.episode)
        }
    }

    @Test
    fun `an undated episode counts as aired`() {
        // TMDB omits air dates for plenty of already-released episodes, so a
        // blank date must not make a show unpickable.
        val pool = listOf(1 to listOf(episode(1, airDate = null)))
        val pick = chooseRandomEpisode(pool, null, null, Random(1)) ?: error("expected a pick")
        assertEquals(1, pick.episode)
    }

    @Test
    fun `never repeats the episode playing now`() {
        val pool = listOf(1 to listOf(episode(1), episode(2)))
        repeat(50) { seed ->
            val pick = chooseRandomEpisode(
                pool,
                excludeSeason = 1,
                excludeEpisode = 2,
                random = Random(seed)
            ) ?: error("expected a pick for seed $seed")
            assertEquals(1, pick.season)
            assertEquals(1, pick.episode)
        }
    }

    @Test
    fun `only the current episode being aired means no pick`() {
        // Null (the caller falls back to its own next-episode default) beats
        // looping on the episode that just finished.
        val pool = listOf(1 to listOf(episode(1)))
        assertNull(
            chooseRandomEpisode(pool, excludeSeason = 1, excludeEpisode = 1, random = Random(1))
        )
    }

    @Test
    fun `no aired episodes means no pick`() {
        val future = LocalDate.now().plusDays(30).toString()
        val pool = listOf(1 to listOf(episode(1, airDate = future)), 2 to emptyList())
        assertNull(chooseRandomEpisode(pool, null, null, Random(1)))
        assertNull(chooseRandomEpisode(emptyList(), null, null, Random(1)))
    }

    @Test
    fun `spreads across every season it is given`() {
        val seen = mutableSetOf<Int>()
        repeat(200) { seed ->
            val pick = chooseRandomEpisode(seasons, null, null, Random(seed))
                ?: error("expected a pick for seed $seed")
            seen += pick.season
        }
        assertEquals(setOf(1, 2), seen)
    }

    @Test
    fun `carries the episode's own detail`() {
        val pool = listOf(
            4 to listOf(
                episode(7, name = "The Long Goodbye", runtime = 58, streamId = "tt100:4:7")
            )
        )
        val pick = chooseRandomEpisode(pool, null, null, Random(3)) ?: error("expected a pick")
        assertEquals(4, pick.season)
        assertEquals(7, pick.episode)
        assertEquals("The Long Goodbye", pick.name)
        assertEquals(58, pick.runtimeMinutes)
        assertEquals("tt100:4:7", pick.streamId)
        assertEquals(1, pick.episodeCount)
    }

    @Test
    fun `blank names and runtimes are dropped`() {
        val pool = listOf(1 to listOf(episode(1, name = "  ", runtime = 0)))
        val pick = chooseRandomEpisode(pool, null, null, Random(1)) ?: error("expected a pick")
        assertNull(pick.name)
        assertNull(pick.runtimeMinutes)
        assertEquals(1, pick.episodeCount)
    }
}
