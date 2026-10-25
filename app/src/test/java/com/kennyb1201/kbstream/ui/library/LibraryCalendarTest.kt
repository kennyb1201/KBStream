package com.kennyb1201.kbstream.ui.library

import com.kennyb1201.kbstream.data.simkl.SimklContinueWatchingItem
import com.kennyb1201.kbstream.data.tmdb.ResolvedEpisode
import java.io.IOException
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.runBlocking
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

    @Test
    fun `an announced next episode the season payloads do not list is still on its day`() {
        // Reported: a caught-up show's new season airs Dec 1 and the Home
        // Upcoming rail (which reads TMDB's next_episode_to_air) shows it,
        // while the calendar - which only walked season payloads - had nothing
        // to place. Same show, same date, both screens.
        val december = YearMonth.of(2026, 12)
        val month = buildCalendarMonth(
            month = december,
            shows = listOf(
                CalendarShow(
                    showId = "tmdb:1",
                    openId = "tt1",
                    title = "Bad Monkey",
                    seasons = mapOf(3 to listOf(episode(10, "2025-10-08"))),
                    nextEpisode = CalendarNextEpisode(
                        season = 4,
                        episode = episode(1, "2026-12-01", name = "Pilot")
                    )
                )
            ),
            today = today
        )

        val rows = month.episodesOn(LocalDate.of(2026, 12, 1))
        assertEquals("the announced premiere is on the rail's own date", 1, rows.size)
        assertEquals(4, rows.single().season)
        assertEquals(1, rows.single().episode)
        assertTrue("it opens the season, so it is a premiere", rows.single().isPremiere)
        assertTrue(
            "but nothing later is known, so it must not read as a finale",
            !rows.single().isFinale
        )
    }

    @Test
    fun `an announced episode the payload lists WITHOUT a date is still on its day`() {
        // Field report (2026-10-25): Bad Monkey's season 2 premiere is Dec 1 on
        // the Home Upcoming rail and nowhere on the calendar. The rail reads
        // TMDB's next_episode_to_air; the calendar reads the season payload,
        // and season 2 exists with its ten episodes but no dates - so a
        // presence-based duplicate check cancelled the pointer and the
        // payload then dropped every undated row. Presence is not a date.
        val december = YearMonth.of(2026, 12)
        val month = buildCalendarMonth(
            month = december,
            shows = listOf(
                CalendarShow(
                    showId = "tmdb:130853",
                    openId = "tt15203646",
                    title = "Bad Monkey",
                    seasons = mapOf(
                        2 to listOf(episode(1, null), episode(2, null), episode(3, null))
                    ),
                    nextEpisode = CalendarNextEpisode(
                        season = 2,
                        episode = episode(1, "2026-12-01", name = "Episode 1")
                    )
                )
            ),
            today = today
        )

        val rows = month.episodesOn(LocalDate.of(2026, 12, 1))
        assertEquals("the pointer's date is the only date there is", 1, rows.size)
        assertEquals(2, rows.single().season)
        assertEquals(1, rows.single().episode)
        assertTrue("it opens the season", rows.single().isPremiere)
    }

    @Test
    fun `a payload row that dates the episode keeps its day and is not doubled`() {
        // The other side of the same rule: once the payload knows a date, it
        // wins and the pointer adds nothing - one row, on the payload's day,
        // even when the pointer disagrees.
        val december = YearMonth.of(2026, 12)
        val month = buildCalendarMonth(
            month = december,
            shows = listOf(
                CalendarShow(
                    showId = "tmdb:130853",
                    openId = "tt15203646",
                    title = "Bad Monkey",
                    seasons = mapOf(2 to listOf(episode(1, "2026-12-08"))),
                    nextEpisode = CalendarNextEpisode(
                        season = 2,
                        episode = episode(1, "2026-12-01")
                    )
                )
            ),
            today = today
        )

        assertEquals(
            "nothing on the pointer's day",
            0,
            month.episodesOn(LocalDate.of(2026, 12, 1)).size
        )
        assertEquals(1, month.episodesOn(LocalDate.of(2026, 12, 8)).size)
    }

    @Test
    fun `an episode the payload already lists is never placed twice`() {
        val month = buildCalendarMonth(
            month = october,
            shows = listOf(
                CalendarShow(
                    showId = "tmdb:2",
                    openId = "tt2",
                    title = "Some Show",
                    seasons = mapOf(1 to listOf(episode(1, "2026-10-14"))),
                    nextEpisode = CalendarNextEpisode(season = 1, episode = episode(1, "2026-10-14"))
                )
            ),
            today = today
        )

        assertEquals(1, month.episodesOn(LocalDate.of(2026, 10, 14)).size)
    }

    /**
     * Reported from Sentry on the TV: `IllegalArgumentException: Key
     * "tmdb:214162|s5|e4" was already used` - the day list's LazyColumn keys
     * each row by show/season/episode, so one duplicated row crashes the pane.
     *
     * The duplicate came from the show set, not from the payload: a source can
     * name one show twice (Simkl hands over both its IMDb and its TMDB key),
     * and each candidate resolved to its own CalendarShow with the same
     * showId. Two shows in, one show's worth of rows out.
     */
    @Test
    fun `a show resolved under two candidate ids is still one row per episode`() {
        val airDate = "2026-10-14"
        val month = buildCalendarMonth(
            month = october,
            shows = listOf(
                CalendarShow(
                    showId = "tmdb:214162",
                    openId = "tt214162",
                    title = "Animal Control",
                    seasons = mapOf(5 to listOf(episode(4, airDate)))
                ),
                // The same show, reached by its other id.
                CalendarShow(
                    showId = "tmdb:214162",
                    openId = "tt214162",
                    title = "Animal Control",
                    seasons = mapOf(5 to listOf(episode(4, airDate)))
                )
            ),
            today = today
        )

        val rows = month.episodesOn(LocalDate.of(2026, 10, 14))
        assertEquals("one episode, one row - the day list's keys must not collide", 1, rows.size)
    }

    // ---- The candidate cut (reported: a show on Upcoming missing from the
    // calendar). Every one of these is pure, because the bug was a RULE, not a
    // date: the show was tracked, both placement paths dated it, and it was
    // never handed to the month builder at all.

    private fun simklItem(imdbId: String?, tmdbId: Int?) =
        SimklContinueWatchingItem(
            id = "caught-up-${imdbId ?: tmdbId}",
            imdbId = imdbId,
            tmdbId = tmdbId,
            simklId = null,
            title = imdbId ?: "tmdb:$tmdbId",
            year = null,
            posterUrl = null,
            lastWatchedAt = null,
            progress = null,
            upNextText = null,
            mediaType = "series",
            source = "watching"
        )

    @Test
    fun `priority shows come first, in priority order, and are not repeated by the tail`() {
        val order = calendarCandidates(
            sources = listOf(listOf("tt1", "tt2"), listOf("tmdb:10")),
            cap = 10,
            priority = listOf("tt900", "tt1")
        )

        assertEquals(
            "the priority show keeps its slot, and the interleave carries on from " +
                "where that source left off (a duplicate does not earn a second turn)",
            listOf("tt900", "tt1", "tmdb:10", "tt2"),
            order
        )
    }

    @Test
    fun `the cap bounds the tracked tail only, so a priority show past it is still resolved`() {
        // The field case (Bad Monkey S2E1, Dec 1): the show sat past the
        // round-robin cut, so the month builder never saw it, while the Home
        // Upcoming rail advertised it. With no priority the output must stay
        // byte-identical to today's 300-show cut; with a priority list the
        // output is that tail PLUS the priority shows.
        val tail = (1..400).map { "tt$it" }
        val priority = listOf("ttbadmin", "ttother")

        val withoutPriority = calendarCandidates(listOf(tail), cap = 300)
        assertEquals("today's cut is unchanged when nothing is prioritised", tail.take(300), withoutPriority)

        val withPriority = calendarCandidates(listOf(tail), cap = 300, priority = priority)
        assertEquals("300 tail shows + the 2 priority shows", 302, withPriority.size)
        assertEquals(priority, withPriority.take(2))
        assertEquals(tail.take(300), withPriority.drop(2))
    }

    @Test
    fun `a show past the round-robin cut is still a candidate when Upcoming carries it`() {
        // Bad Monkey: named by Simkl's caught-up list (so Upcoming advertises
        // its Dec 1 premiere) but sitting at position 350 of a 400-show tracked
        // list, i.e. past MAX_CALENDAR_SHOWS.
        val tracked = (1..400).map { "tt$it" }
        val badMonkey = "tt15203646"

        assertTrue(
            "without the priority list the show is cut, which is the bug",
            badMonkey !in calendarCandidates(listOf(tracked), cap = 300)
        )
        assertTrue(
            "on Upcoming must mean in the calendar, whatever the cap says",
            badMonkey in calendarCandidates(
                sources = listOf(tracked),
                cap = 300,
                priority = listOf(badMonkey)
            )
        )
    }

    @Test
    fun `a show named twice in the priority list spends one slot, not two`() {
        // Simkl hands over BOTH id forms, so a local/MDBList copy of the same
        // show must not consume a second priority slot under its other spelling.
        val ids = calendarPriorityIds(
            listOf(
                CalendarPriorityShow(
                    id = "tt15203646",
                    aliases = setOf("tt15203646", "tmdb:130853")
                ),
                CalendarPriorityShow(id = "tmdb:130853"),
                CalendarPriorityShow(id = "tt999")
            )
        )

        assertEquals(listOf("tt15203646", "tt999"), ids)
    }

    @Test
    fun `a Simkl caught-up item maps to an id the month builder can resolve`() = runBlocking {
        val shows = simklPriorityShows {
            listOf(
                simklItem(imdbId = "tt1", tmdbId = 11),
                simklItem(imdbId = null, tmdbId = 22),
                simklItem(imdbId = null, tmdbId = null)
            )
        }

        assertEquals(
            "IMDb first, then the tmdb: key, and nothing at all for an item " +
                "the month builder could never resolve (a simkl: id spends a slot " +
                "for nothing)",
            listOf("tt1", "tmdb:22"),
            calendarPriorityIds(shows)
        )
    }

    @Test
    fun `a Simkl priority read that throws leaves the tracked set intact`() = runBlocking {
        val priority = simklPriorityShows { throw IOException("simkl is down") }

        assertTrue("a failed read contributes no priority at all", priority.isEmpty())
        assertEquals(
            "and the calendar still fills to its cap - never an empty calendar",
            300,
            calendarCandidates(
                sources = listOf((1..400).map { "tt$it" }),
                cap = 300,
                priority = calendarPriorityIds(priority)
            ).size
        )
    }

    @Test
    fun `a day's rows are unique by their watched key`() {
        val one = CalendarEpisode(
            showId = "tmdb:214162",
            openId = "tt214162",
            showTitle = "Animal Control",
            poster = null,
            backdrop = null,
            season = 5,
            episode = 4,
            episodeTitle = "Sled Dogs and Kitty Litter",
            airDate = LocalDate.of(2026, 10, 14),
            isPremiere = false,
            isFinale = false
        )

        val sorted = calendarSortedDays(
            mapOf(one.airDate to listOf(one, one.copy(episodeTitle = "a second copy")))
        )

        val rows = sorted.getValue(one.airDate)
        assertEquals(1, rows.size)
        assertEquals(
            "the first copy wins, so the row keeps the payload's title",
            "Sled Dogs and Kitty Litter",
            rows.single().episodeTitle
        )
    }
}
