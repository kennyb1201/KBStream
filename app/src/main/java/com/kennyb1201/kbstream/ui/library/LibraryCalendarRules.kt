package com.kennyb1201.kbstream.ui.library

import com.kennyb1201.kbstream.data.airdates.AirDateCorrection
import com.kennyb1201.kbstream.data.tmdb.ResolvedEpisode
import com.kennyb1201.kbstream.data.tmdb.TmdbSeasonSummary
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The Library calendar's month model, as pure functions.
 *
 * The calendar answers a different question from the Upcoming rail: the rail
 * advertises ONE next unaired episode per caught-up show, while this is the
 * whole year of a viewer's tracked shows - every announced episode, past and
 * future, including the ones they have already watched and the ones they
 * missed. So it shares the rail's DATE rule ([AirDateCorrection], applied
 * through [correctedAirDate]) and none of its selection rules, and the two
 * screens can never disagree about when an episode airs.
 *
 * Everything here takes data in and answers data out - no repository, no
 * clock beyond the injectable `today` - so the month layout, the season
 * selection and the episode indexing are pinned by JVM tests rather than by a
 * device.
 */

/**
 * One episode on the calendar: the show it belongs to, its numbers and title,
 * the corrected date it lands on, and whether it opens or closes its season.
 *
 * [showId] is the TMDB id every source can be resolved against ("tmdb:<n>"),
 * so a show known by an IMDb id on one tracker and a TMDB id on another is one
 * row here. [openId] is what the Detail route wants (an IMDb id when the show
 * has one, else the bare TMDB number), computed by the ViewModel where the ids
 * actually are - the row itself never guesses.
 */
data class CalendarEpisode(
    val showId: String,
    val openId: String,
    val showTitle: String,
    val poster: String?,
    val backdrop: String?,
    val season: Int,
    val episode: Int,
    val episodeTitle: String?,
    val airDate: LocalDate,
    val isPremiere: Boolean,
    val isFinale: Boolean
)

/**
 * One tracked show, resolved far enough to place its episodes in a month: its
 * identity and artwork, the season payloads the calendar needed, and the
 * second source's dates for it (see [AirDateCorrection]).
 */
internal data class CalendarShow(
    val showId: String,
    val openId: String,
    val title: String,
    val poster: String? = null,
    val backdrop: String? = null,
    val seasons: Map<Int, List<ResolvedEpisode>> = emptyMap(),
    val sourceDates: Map<String, String> = emptyMap()
)

/** Where a calendar's focus and selection start for a month. */
internal data class CalendarLanding(
    val day: LocalDate,
    val hasEpisodes: Boolean
)

/**
 * The whole month, indexed by day, as the pane renders it.
 *
 * A day with nothing on it is simply absent from [days] - the grid draws the
 * cell either way, and the selected-day list reads "Nothing airing" for an
 * absent key. Days are keyed by [LocalDate] rather than by day-of-month so a
 * month that pages across a year boundary cannot collide with itself.
 */
internal data class CalendarMonth(
    val month: YearMonth,
    val days: Map<LocalDate, List<CalendarEpisode>>
) {
    val episodeCount: Int
        get() = days.values.sumOf { it.size }

    fun episodesOn(day: LocalDate): List<CalendarEpisode> = days[day].orEmpty()

    val isEmpty: Boolean
        get() = days.isEmpty()

    companion object {
        fun empty(month: YearMonth): CalendarMonth = CalendarMonth(month, emptyMap())
    }
}

/**
 * The calendar's candidate shows, in a SOURCE-FAIR order, capped at [cap].
 *
 * The tracked set arrives as four independent lists (this profile's history,
 * Simkl, MDBList, Trakt) and the calendar resolves a bounded number of shows
 * per month - so the order decides which shows get looked at at all. Taking
 * them source by source let one long history fill the whole allowance and drop
 * every tracker-only show (the ones that usually carry the UNAIRED episodes,
 * because they are the shows being followed right now). Round-robin instead:
 * one show from each source, then the next from each, so every source is
 * represented before any source repeats. Duplicate ids across sources are
 * collapsed on the way, keeping the earliest source that named them.
 */
internal fun calendarCandidates(
    sources: List<List<String>>,
    cap: Int
): List<String> {
    if (cap <= 0) return emptyList()
    val cleaned = sources.map { source -> source.filter { it.isNotBlank() } }
    val longest = cleaned.maxOfOrNull { it.size } ?: 0
    val out = LinkedHashSet<String>(minOf(cap, cleaned.sumOf { it.size }))

    for (index in 0 until longest) {
        for (source in cleaned) {
            val id = source.getOrNull(index) ?: continue
            out += id
            if (out.size >= cap) return out.toList()
        }
    }
    return out.toList()
}

/** "October 2026" - the month header's label. */
internal fun calendarMonthLabel(month: YearMonth): String =
    month.atDay(1).format(
        DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())
    )

/**
 * The grid's cells: one entry per day of [month], preceded by `null` for the
 * padding that puts the 1st under its weekday, and followed by `null` padding
 * to a whole number of weeks.
 *
 * Rows are always complete weeks so the seven columns line up on every day,
 * which is also what makes D-pad movement inside the grid predictable: a cell's
 * index in this list identifies its column and its row.
 *
 * The week starts on Monday, matching the ISO weekdays the rest of the app's
 * date handling implies and the way a European viewer reads a calendar; the
 * first cell of the row is therefore Monday.
 */
internal fun calendarCells(
    month: YearMonth,
    weekStart: DayOfWeek = DayOfWeek.MONDAY
): List<LocalDate?> {
    val first = month.atDay(1)
    val leading = (first.dayOfWeek.value - weekStart.value + 7) % 7
    val cells = ArrayList<LocalDate?>(42)
    repeat(leading) { cells += null }
    for (day in 1..month.lengthOfMonth()) cells += month.atDay(day)
    while (cells.size % 7 != 0) cells += null
    // A month that fills five rows exactly (28-31 days from the right weekday)
    // still gets a sixth only when it needs one; padding never adds a blank
    // week just to make the grid rectangular beyond its content.
    return cells
}

/** The weekday headings that sit above [calendarCells]' columns. */
internal fun calendarWeekdayLabels(
    weekStart: DayOfWeek = DayOfWeek.MONDAY
): List<String> = (0..6).map { offset ->
    DayOfWeek.of(((weekStart.value - 1 + offset) % 7) + 1)
        .getDisplayName(java.time.format.TextStyle.NARROW, Locale.getDefault())
}

/**
 * Which seasons of a show can hold an episode of [month].
 *
 * The calendar cannot ask TMDB "what airs in October" - the season list is all
 * it has, and a season is one request. A season's window is therefore
 * approximated from the only dates the summary carries: it starts on its own
 * `air_date` and ends where the NEXT season starts. A season with no date, or
 * one with no later season to bound it, is always included, because a wrong
 * exclusion would silently lose episodes while a wrong inclusion only costs one
 * cached request.
 *
 * Season 0 (specials) is never included: it is not part of a show's run, its
 * episodes carry no reliable dates, and the season browser already refuses to
 * show it.
 */
internal fun seasonsCoveringMonth(
    seasons: List<TmdbSeasonSummary>,
    month: YearMonth
): List<Int> {
    val ordered = seasons
        .filter { it.seasonNumber > 0 }
        .sortedBy { it.seasonNumber }
    if (ordered.isEmpty()) return emptyList()

    val monthStart = month.atDay(1)
    val monthEnd = month.atEndOfMonth()
    val included = ArrayList<Int>(ordered.size)

    ordered.forEachIndexed { index, summary ->
        val start = AirDateCorrection.parse(summary.airDate)
        val nextStart = ordered.getOrNull(index + 1)
            ?.let { AirDateCorrection.parse(it.airDate) }

        val startsAfterMonth = start != null && start.isAfter(monthEnd)
        val endsBeforeMonth = nextStart != null && nextStart.isBefore(monthStart)

        if (!startsAfterMonth && !endsBeforeMonth) included += summary.seasonNumber
    }

    return included
}

/**
 * One episode's date, corrected by the second source exactly as the Upcoming
 * rail corrects its own (see [AirDateCorrection]). Kept here so the calendar
 * and the rail call the same function with the same arguments rather than each
 * spelling out the rule.
 */
internal fun correctedAirDate(
    episode: ResolvedEpisode,
    season: Int,
    sourceDates: Map<String, String>,
    today: LocalDate
): LocalDate? {
    val text = AirDateCorrection.correctAirDate(
        primary = episode.airDate,
        secondary = sourceDates[AirDateCorrection.episodeKey(season, episode.episodeNumber)],
        today = today
    )
    return AirDateCorrection.parse(text)
}

/**
 * One day's rows, in the order the calendar draws them.
 *
 * Ordered by show, then season, then episode - a stable order that does not
 * depend on which show's TMDB call answered first, which matters more once the
 * month is built show-by-show (see [calendarEpisodesFor]).
 */
internal val calendarEpisodeOrder: Comparator<CalendarEpisode> =
    compareBy<CalendarEpisode> { it.showTitle.lowercase(Locale.getDefault()) }
        .thenBy { it.season }
        .thenBy { it.episode }

/**
 * ONE show's episodes that land in [month], indexed by day.
 *
 * Split out of [buildCalendarMonth] so a month can be assembled a show at a
 * time: the pane paints each show as it resolves instead of holding the whole
 * month behind the slowest TMDB call (see LibraryCalendarViewModel). The
 * per-show and whole-month paths share this one function, so the two can never
 * place an episode on different days.
 *
 * Premiere and finale are properties of the SEASON PAYLOAD, not of the month:
 * the first and last named episode of that season, so an episode that opened a
 * season in September still reads as a premiere when the calendar shows
 * October. That is the same first/last rule the Detail screen's season list
 * implies, applied once here so both the badge and any future summary agree.
 */
internal fun calendarEpisodesFor(
    month: YearMonth,
    show: CalendarShow,
    today: LocalDate
): Map<LocalDate, List<CalendarEpisode>> {
    val days = HashMap<LocalDate, MutableList<CalendarEpisode>>()

    for ((season, episodes) in show.seasons) {
        if (episodes.isEmpty()) continue
        val premiere = episodes.first().episodeNumber
        val finale = episodes.last().episodeNumber

        for (episode in episodes) {
            val date = correctedAirDate(
                episode = episode,
                season = season,
                sourceDates = show.sourceDates,
                today = today
            ) ?: continue
            if (YearMonth.from(date) != month) continue

            days.getOrPut(date) { ArrayList() } += CalendarEpisode(
                showId = show.showId,
                openId = show.openId,
                showTitle = show.title,
                poster = show.poster,
                backdrop = show.backdrop,
                season = season,
                episode = episode.episodeNumber,
                episodeTitle = episode.name?.takeIf { it.isNotBlank() },
                airDate = date,
                isPremiere = episode.episodeNumber == premiere,
                isFinale = episode.episodeNumber == finale
            )
        }
    }

    return days
}

/** [days] with each day's rows in [calendarEpisodeOrder]. */
internal fun calendarSortedDays(
    days: Map<LocalDate, Collection<CalendarEpisode>>
): Map<LocalDate, List<CalendarEpisode>> =
    days.mapValues { (_, episodes) -> episodes.sortedWith(calendarEpisodeOrder) }

/**
 * Every episode of [shows] that lands in [month], indexed by day.
 *
 * A thin fold over [calendarEpisodesFor], kept for the tests and for any caller
 * that has the whole set in hand at once.
 */
internal fun buildCalendarMonth(
    month: YearMonth,
    shows: List<CalendarShow>,
    today: LocalDate
): CalendarMonth {
    val days = HashMap<LocalDate, MutableList<CalendarEpisode>>()

    for (show in shows) {
        for ((day, episodes) in calendarEpisodesFor(month, show, today)) {
            days.getOrPut(day) { ArrayList() } += episodes
        }
    }

    if (days.isEmpty()) return CalendarMonth.empty(month)

    return CalendarMonth(month = month, days = calendarSortedDays(days))
}

/**
 * The day the calendar selects and, when it is handed the D-pad, focuses on
 * opening a month.
 *
 * Today wins whenever it is in the displayed month - that is the day the
 * viewer is standing in. Otherwise the month's first day with episodes is the
 * useful answer (a month the viewer paged to is usually a month with something
 * in it), and a month with nothing at all falls back to its 1st so the grid is
 * still navigable.
 */
internal fun calendarLanding(
    month: YearMonth,
    days: Map<LocalDate, List<CalendarEpisode>>,
    today: LocalDate
): CalendarLanding {
    if (YearMonth.from(today) == month) {
        return CalendarLanding(today, days[today].orEmpty().isNotEmpty())
    }

    val firstWithEpisodes = days.keys.minOrNull()
    return if (firstWithEpisodes != null) {
        CalendarLanding(firstWithEpisodes, true)
    } else {
        CalendarLanding(month.atDay(1), false)
    }
}

/**
 * The calendar's navigation step: [delta] months from [month], landing on the
 * same day-of-month where that day exists and on the last day of the target
 * month where it does not (31 January -> 28/29 February).
 *
 * Paging is month paging, so December -> January is simply the same step - the
 * year is carried in [YearMonth] and never tweaked by hand.
 */
internal fun pageMonth(month: YearMonth, delta: Int): YearMonth =
    month.plusMonths(delta.toLong())

/**
 * The key one episode's watched state is carried under.
 *
 * Watched state is a separate set rather than a field on [CalendarEpisode] (see
 * [LibraryCalendarUiState.watched]), so it travels between the ViewModel and the
 * pane as strings keyed by the show's stable "tmdb:<n>" identity plus the
 * numbers - a '|' separator, because the show id already contains a ':'.
 */
internal fun calendarWatchedKey(showId: String, season: Int, episode: Int): String =
    "$showId|s$season|e$episode"
