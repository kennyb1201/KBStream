package com.kennyb1201.kbstream.ui.library

import com.kennyb1201.kbstream.data.tmdb.ResolvedEpisode
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The 2026-11-04 raise of the month build's permit count: `CALENDAR_CONCURRENCY`
 * 4 -> 8, which is the deferral the October speedup spec deliberately left open
 * ("if a later field pass shows cold builds still permit-starved, raise it then,
 * with the diagnostics `calendar:` line as the before/after evidence").
 *
 * One number moved. So what is pinned here is the three things that could drift
 * around it: the number itself (T1), that the number changes only WHEN a month
 * lands and never what it holds (T2), and that the wider fan-out adds
 * simultaneity rather than requests (T3) - a month build that asked TMDB one
 * extra question per show would spend the rate budget the October caution was
 * about, which is the one thing this change must not do.
 *
 * As with [LibraryCalendarSpeedupContractTest]: there is no device in CI and the
 * ViewModel is an `AndroidViewModel`, so the wiring is pinned in the source where
 * it is decided, and the contents are pinned through the same pure functions the
 * build itself publishes through.
 */
class LibraryCalendarConcurrencyContractTest {

    private val month = YearMonth.of(2026, 11)
    private val today = LocalDate.of(2026, 11, 4)

    // ── T1: the constant ───────────────────────────────────────────────────

    @Test
    fun `a month build runs eight shows at a time`() {
        val source = source(VIEW_MODEL)

        assertTrue(
            "CALENDAR_CONCURRENCY is 8 (was 4). Raising it to 12 is the " +
                "documented follow-up and needs its own field evidence - edit " +
                "this pin and the constant's kdoc together when that happens.",
            source.contains("const val CALENDAR_CONCURRENCY = 8")
        )
        assertTrue(
            "and it is still spent at exactly one place: the month build's semaphore",
            source.contains("val semaphore = Semaphore(CALENDAR_CONCURRENCY)")
        )
    }

    // ── T2: same month, wider fan-out ──────────────────────────────────────

    @Test
    fun `the permit count changes when a month lands, never what it holds`() {
        val shows = cannedShows()

        // 4 permits: completions roughly follow input order, ONE show's episodes
        // folded in at a time. 8 permits: eight shows are in flight, so the same
        // month is folded from an interleaved completion order instead.
        val inOrder = fold(shows, shows.map { it.showId })
        val interleaved = fold(shows, shows.map { it.showId }.shuffled(Random(8)))
        assertNotEquals(
            "the two arrival orders have to fold differently BEFORE the sort, or " +
                "this test would pass without the order-independence it claims",
            inOrder,
            interleaved
        )

        val fourPermits = calendarSortedDays(inOrder)
        val eightPermits = calendarSortedDays(interleaved)

        assertTrue(
            "the canned month is worth comparing: several days carry more than " +
                "one show, or arrival order could not matter here at all",
            fourPermits.count { (_, rows) -> rows.isNotEmpty() } >= 3 &&
                fourPermits.keys.any { day ->
                    fourPermits.getValue(day).map { it.showId }.distinct().size > 1
                }
        )
        assertEquals(
            "same days, whatever order the shows answered in",
            fourPermits.keys,
            eightPermits.keys
        )
        assertEquals(
            "same shows, same days, same episode placement - and the rows are " +
                "sorted by show, not left in arrival order",
            fourPermits,
            eightPermits
        )
        assertEquals(
            "and the same month the single-pass builder folds from the whole set",
            buildCalendarMonth(month, shows, today).days,
            eightPermits
        )
    }

    // ── T3: simultaneity, not volume ───────────────────────────────────────

    @Test
    fun `the wider fan-out adds simultaneity, not requests`() {
        val source = source(VIEW_MODEL)
        val show = section(
            source,
            "private suspend fun calendarShowFor(",
            "private fun announcedNextEpisode("
        )

        // The per-show sequence, in this order: detail, optional IMDb resolve,
        // one payload per covering season, then the TVmaze correction. Eight
        // permits run that same sequence eight times; they do not add a step.
        val sequence = listOf(
            "detailFor(candidateId)" to "the one TMDB detail lookup",
            "tmdbRepository.resolveImdbId(detail.id, \"series\")" to
                "the optional IMDb resolve",
            "tmdbRepository.getSeasonEpisodes(detail.id, season, imdbId ?: candidateId)" to
                "one payload per covering season",
            "airDateRepository.episodeAirDates(id)" to "the TVmaze correction"
        )
        var previous = -1
        for ((call, what) in sequence) {
            val at = show.indexOf(call)
            assertTrue("missing per-show call site: $what ($call)", at > previous)
            previous = at
        }

        val build = section(
            source,
            "private suspend fun resolveMonth(",
            "private fun noteResolved("
        )
        assertEquals(
            "resolveMonth asks each show exactly one question",
            1,
            Regex("calendarShowFor\\(").findAll(build).count()
        )
        assertTrue(
            "and does no repository work of its own - the wider fan-out must not " +
                "become a second set of calls around the same show",
            !build.contains("tmdbRepository.") &&
                !build.contains("airDateRepository.")
        )
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /**
     * Folds a month show by show, in the order the shows answered - the same
     * append `MonthProgress.days` does under the build's semaphore, before the
     * publish funnel sorts it.
     */
    private fun fold(
        shows: List<CalendarShow>,
        arrival: List<String>
    ): Map<LocalDate, List<CalendarEpisode>> {
        val byId = shows.associateBy { it.showId }
        val days = HashMap<LocalDate, MutableList<CalendarEpisode>>()

        for (id in arrival) {
            val show = requireNotNull(byId[id]) { "unknown show in arrival order: $id" }
            for ((day, episodes) in calendarEpisodesFor(month, show, today)) {
                days.getOrPut(day) { ArrayList() } += episodes
            }
        }

        return days
    }

    /**
     * A month worth comparing: overlapping premiere blocks (two shows on one
     * night, one show with a double episode), a season that spans the month
     * boundary, and a corrected date from the second source.
     */
    private fun cannedShows(): List<CalendarShow> = listOf(
        show(
            "Alpha Night",
            listOf(episode(1, "2026-11-10"), episode(2, "2026-11-10")),
            extraSeasons = mapOf(
                2 to listOf(episode(1, "2026-11-10", "s2e1"), episode(2, "2026-11-17", "s2e2"))
            )
        ),
        show(
            "Beta Block",
            listOf(episode(1, "2026-11-10"), episode(2, "2026-11-24"))
        ),
        show(
            "Gamma Return",
            listOf(episode(1, "2026-10-31"), episode(2, "2026-11-01")),
            sourceDates = mapOf("s1e1" to "2026-11-03")
        ),
        show(
            "Delta Week",
            listOf(
                episode(1, "2026-11-17"),
                episode(2, "2026-11-17"),
                episode(3, "2026-11-17")
            )
        ),
        show(
            "Epsilon Solo",
            listOf(episode(1, "2026-11-05"), episode(2, "2026-11-26"))
        ),
        show(
            "Zeta Double",
            listOf(episode(1, "2026-11-24"), episode(2, "2026-11-24"))
        )
    )

    private fun episode(
        number: Int,
        airDate: String?,
        stream: String = "s1e$number",
        name: String = "Episode $number"
    ) = ResolvedEpisode(
        streamId = stream,
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
        firstSeason: List<ResolvedEpisode>,
        extraSeasons: Map<Int, List<ResolvedEpisode>> = emptyMap(),
        sourceDates: Map<String, String> = emptyMap()
    ) = CalendarShow(
        showId = "tmdb:${title.hashCode()}",
        openId = "tt${title.hashCode()}",
        title = title,
        seasons = mapOf(1 to firstSeason) + extraSeasons,
        sourceDates = sourceDates
    )

    private fun section(src: String, from: String, to: String): String {
        val start = src.indexOf(from)
        assertTrue("missing landmark: $from", start >= 0)
        val rest = src.substring(start + from.length)
        val end = rest.indexOf(to)
        assertTrue("missing landmark after $from", end >= 0)
        return rest.substring(0, end)
    }

    private fun source(path: String): String {
        val file = File(findSourceRoot(), path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun findSourceRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private companion object {
        const val VIEW_MODEL =
            "com/kennyb1201/kbstream/ui/library/LibraryCalendarViewModel.kt"
    }
}
