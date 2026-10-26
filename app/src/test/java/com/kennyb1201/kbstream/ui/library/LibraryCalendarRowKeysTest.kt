package com.kennyb1201.kbstream.ui.library

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The calendar's LazyColumn keys.
 *
 * The watched-state key is not a row identity: a day can hold the same
 * show/season/episode twice, and using the watched key as the Compose key
 * crashed the library with `Key "tmdb:214162|s5|e4" was already used`
 * (ANDROID-11). These pin the uniqueness the list needs without changing the
 * key watched state is stored under.
 */
class LibraryCalendarRowKeysTest {

    @Test
    fun `distinct episodes keep the plain watched key`() {
        val keys = calendarRowKeys(
            listOf(
                episode(showId = "tmdb:214162", season = 5, episode = 4),
                episode(showId = "tmdb:214162", season = 5, episode = 5)
            )
        )
        assertEquals(
            listOf("tmdb:214162|s5|e4", "tmdb:214162|s5|e5"),
            keys
        )
    }

    @Test
    fun `a repeated show and episode yields no duplicate key`() {
        // The exact shape of the crash report.
        val keys = calendarRowKeys(
            listOf(
                episode(showId = "tmdb:214162", season = 5, episode = 4),
                episode(showId = "tmdb:214162", season = 5, episode = 4)
            )
        )
        assertEquals(keys.size, keys.toSet().size)
        // The first keeps the watched key, so an existing highlight lookup still
        // finds its row by the plain value.
        assertEquals("tmdb:214162|s5|e4", keys[0])
        assertEquals("tmdb:214162|s5|e4#2", keys[1])
    }

    @Test
    fun `a third repeat is still unique`() {
        val keys = calendarRowKeys(
            List(3) { episode(showId = "tmdb:1", season = 1, episode = 1) }
        )
        assertEquals(3, keys.toSet().size)
        assertEquals(listOf("tmdb:1|s1|e1", "tmdb:1|s1|e1#2", "tmdb:1|s1|e1#3"), keys)
    }

    @Test
    fun `an empty day has no keys`() {
        assertTrue(calendarRowKeys(emptyList()).isEmpty())
    }

    private fun episode(
        showId: String,
        season: Int,
        episode: Int,
        airDate: LocalDate = LocalDate.of(2026, 10, 25)
    ) = CalendarEpisode(
        showId = showId,
        openId = showId,
        showTitle = "Show",
        poster = null,
        backdrop = null,
        season = season,
        episode = episode,
        episodeTitle = null,
        airDate = airDate,
        isPremiere = false,
        isFinale = false
    )
}
