package com.kennyb1201.kbstream.ui.library

import com.kennyb1201.kbstream.data.tmdb.ResolvedEpisode
import java.time.LocalDate
import java.time.YearMonth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The calendar's month model, pinned where the reported bugs live.
 *
 * Reported: "it only shows one episode per day so if u have multiple episodes
 * showing on same day u cant see them", "theres nothing in future dates for
 * profile one but profile 2's future dates work", and a month that took so long
 * on the first try that its own diagnostic pass could not tell slowness from
 * emptiness.
 *
 * The list bug was the pane's layout (a day list with about one row of height,
 * see LibraryCalendarSurfaceContractTest), but the MODEL has to be right first:
 * a same-night double episode is two rows on one day, not one collapsed row.
 * The profile bug was a history read against the wrong database, and the
 * slowness is why a month is now assembled a show at a time
 * ([calendarEpisodesFor]) - so these tests pin exactly those seams.
 */
class LibraryCalendarTest {

    private val october = YearMonth.of(2026, 10)
    private val today = LocalDate.of(2026, 10, 24)

    private fun episode(
        number: Int,
        airDate: String?,
        name: String? = "Episode $number"
    ) = ResolvedEpisode(
        streamId = "s1e$number",
        episodeNumber = number,
        name = name,
        overview = null,
        thumbnail = null,
        runtimeMinutes = 50,
        airDate = airDate,
        voteAverage = null
    )

    private fun show(
        title: String,
        vararg episodes: ResolvedEpisode,
        season: Int = 1,
        sourceDates: Map<String, String> = emptyMap()
    ) = CalendarShow(
        showId = "tmdb:${title.hashCode()}",
        openId = "tt${title.hashCode()}",
        title = title,
        seasons = mapOf(season to episodes.toList()),
        sourceDates = sourceDates
    )

    @Test
    fun `two episodes on one night are two rows on that day`() {
        // The reported shape: a premiere block. One show, one day, two rows.
        val month = buildCalendarMonth(
            month = october,
            shows = listOf(
                show(
                    "Some Show",
                    episode(1, "2026-10-14"),
                    episode(2, "2026-10-14")
                )
            ),
            today = today
        )

        val rows = month.episodesOn(LocalDate.of(2026, 10, 14))
        assertEquals("both episodes of the block are on the day", 2, rows.size)
        assertEquals(listOf(1, 2), rows.map { it.episode })
    }

    @Test
    fun `every show airing on one day is kept, not just the first`() {
        val month = buildCalendarMonth(
            month = october,
            shows = listOf(
                show("Alpha", episode(3, "2026-10-20")),
                show("Beta", episode(5, "2026-10-20")),
                show("Gamma", episode(1, "2026-10-20"))
            ),
            today = today
        )

        val rows = month.episodesOn(LocalDate.of(2026, 10, 20))
        assertEquals(3, rows.size)
        assertEquals(
            "and they are ordered by show, so the day does not reorder as shows resolve",
            listOf("Alpha", "Beta", "Gamma"),
            rows.map { it.showTitle }
        )
    }

    @Test
    fun `a show resolved on its own lands in exactly the month the whole set would`() {
        // The incremental build's contract: a month assembled show-by-show
        // (see LibraryCalendarViewModel) must equal the one built in one pass,
        // or paging away mid-build would leave a month that disagrees with the
        // same month built later.
        val shows = listOf(
            show("Alpha", episode(1, "2026-10-01"), episode(2, "2026-10-08")),
            show("Beta", episode(4, "2026-10-08"))
        )
        val whole = buildCalendarMonth(october, shows, today)

        val incrementally = HashMap<LocalDate, MutableList<CalendarEpisode>>()
        shows.forEach { show ->
            calendarEpisodesFor(october, show, today).forEach { (day, episodes) ->
                incrementally.getOrPut(day) { ArrayList() } += episodes
            }
        }

        assertEquals(whole.days, calendarSortedDays(incrementally))
    }

    @Test
    fun `the second source's date correction is the whole-set rule, per show too`() {
        // TMDB still claims a date that has not arrived while the other source
        // says it already aired: the calendar moves the episode, exactly as the
        // Upcoming rail does.
        val corrected = calendarEpisodesFor(
            month = october,
            show = show(
                "Late Show",
                episode(1, "2026-11-05"),
                sourceDates = mapOf("1:1" to "2026-10-21")
            ),
            today = today
        )

        assertTrue(
            "the corrected day is the one that carries the episode",
            corrected.containsKey(LocalDate.of(2026, 10, 21))
        )
    }

    @Test
    fun `candidates take one show from each source before any source repeats`() {
        // The profile bug's fix. The four lists used to be concatenated, so a
        // long local history could consume the whole allowance and leave the
        // tracker-sourced (currently airing) shows out of the calendar. This is
        // also what made one profile's future dates empty and another's fine:
        // their local histories differ in size.
        val order = calendarCandidates(
            sources = listOf(
                listOf("tt1", "tt2", "tt3"),
                listOf("tmdb:10"),
                listOf("tt100"),
                listOf("tmdb:20", "tt200")
            ),
            cap = 7
        )

        assertEquals(
            listOf("tt1", "tmdb:10", "tt100", "tmdb:20", "tt2", "tt200", "tt3"),
            order
        )
        assertTrue(
            "every source is represented before the long one gets a second turn",
            order.take(4).toSet() == setOf("tt1", "tmdb:10", "tt100", "tmdb:20")
        )
    }

    @Test
    fun `candidates collapse a show two sources both name`() {
        val order = calendarCandidates(
            sources = listOf(listOf("tt1", "tt2"), listOf("tt1"), listOf("tt2", "tt3")),
            cap = 10
        )
        assertEquals(listOf("tt1", "tt2", "tt3"), order)
    }

    @Test
    fun `the cap is honoured and a smaller cap still starts source-fair`() {
        val order = calendarCandidates(
            sources = listOf(listOf("a1", "a2", "a3"), listOf("b1", "b2")),
            cap = 3
        )
        assertEquals(listOf("a1", "b1", "a2"), order)
        assertEquals(emptyList<String>(), calendarCandidates(emptyList(), cap = 5))
        assertEquals(emptyList<String>(), calendarCandidates(listOf(listOf("a")), cap = 0))
    }

    @Test
    fun `blank ids never become candidates`() {
        assertEquals(
            listOf("tt1"),
            calendarCandidates(listOf(listOf("", "  "), listOf("tt1")), cap = 10)
        )
    }
}
