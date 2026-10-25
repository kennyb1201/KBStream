package com.kennyb1201.kbstream.ui.library

import com.kennyb1201.kbstream.data.airdates.AirDateCorrection
import com.kennyb1201.kbstream.data.runCatchingCancellable
import com.kennyb1201.kbstream.data.simkl.SimklContinueWatchingItem
import com.kennyb1201.kbstream.data.tmdb.ResolvedEpisode
import com.kennyb1201.kbstream.data.tmdb.TmdbSeasonSummary
import com.kennyb1201.kbstream.ui.home.CaughtUpShowCandidate
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
    val sourceDates: Map<String, String> = emptyMap(),
    /**
     * The series detail's own `next_episode_to_air`, when the season payloads
     * did not list that episode.
     *
     * This is the half the Upcoming rail always had and the calendar did not:
     * the rail builds its card straight off the detail pointer, so a returning
     * show's announced premiere is on the rail the moment TMDB announces it -
     * while the calendar, which only ever walked season payloads, had nothing
     * to place until TMDB added the episode to a season. Reported as "Bad
     * Monkey's new season airs Dec 1, it shows in my upcoming rail on home but
     * not in calendar".
     *
     * Carried whenever the pointer names an episode. [calendarEpisodesFor]
     * places it only when no payload row could DATE it - presence is not
     * enough, because a season lists an announced episode before it dates it -
     * so a normal episode is never drawn twice. Null means "nothing extra to
     * place".
     */
    val nextEpisode: CalendarNextEpisode? = null
)

/**
 * One announced-but-unlisted episode: the season it belongs to and the
 * episode itself, as the detail pointer described it.
 */
internal data class CalendarNextEpisode(
    val season: Int,
    val episode: ResolvedEpisode
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
 * One show the calendar MUST carry, whatever [cap] says: a show Upcoming can
 * advertise, so the two screens cannot disagree about whether it exists.
 *
 * [aliases] are every id spelling this show is known by at this point. The
 * sources name one show in different flavours (Simkl hands over an IMDb id AND
 * a `tmdb:<n>` key, local history may use either), so a show named twice has to
 * spend one priority slot, not two: the first spelling claims them all, and a
 * later candidate carrying any of them is the same show.
 */
internal data class CalendarPriorityShow(
    val id: String,
    val aliases: Set<String> = emptySet()
)

/**
 * The priority list as ids, de-duplicated by show identity and kept in the
 * order the sources handed the shows over.
 *
 * Order is load-bearing: it is Upcoming's own order (Simkl's unaired-first, then
 * most recently watched, then the other halves), which is roughly "most likely
 * to have something coming", and it is the order the month builder resolves
 * them in. Blank ids and ids whose aliases are all claimed are dropped.
 */
internal fun calendarPriorityIds(
    shows: List<CalendarPriorityShow>
): List<String> {
    val claimed = HashSet<String>(shows.size * 2)
    val out = ArrayList<String>(shows.size)

    for (show in shows) {
        val id = show.id.trim()
        if (id.isEmpty()) continue

        val aliases = (show.aliases + id)
            .map { alias -> alias.trim().lowercase(Locale.ROOT) }
            .filter { alias -> alias.isNotEmpty() }
            .toSet()
        if (aliases.isEmpty() || aliases.any { alias -> alias in claimed }) continue

        claimed += aliases
        out += id
    }

    return out
}

/**
 * Simkl's caught-up shows as priority candidates - the half that caught the
 * field case (a show the viewer is caught up on, whose next episode Upcoming
 * advertises).
 *
 * Read through [read] so the fail-soft contract is testable: a Simkl read that
 * throws contributes NOTHING to the priority list (tier-2 still fills the
 * month), rather than emptying the calendar.
 *
 * Only ids the month builder can resolve are carried: an item with an IMDb id
 * maps to it, one with only a TMDB id maps to `tmdb:<n>`, and an item with
 * neither is dropped. Carrying anything else - a `simkl:` id, an empty string -
 * would spend a priority slot on a show `detailFor` can never resolve.
 */
internal suspend fun simklPriorityShows(
    read: suspend () -> List<SimklContinueWatchingItem>
): List<CalendarPriorityShow> =
    runCatchingCancellable { read() }
        .getOrNull()
        .orEmpty()
        .mapNotNull { item -> priorityShowForSimklItem(item) }

private fun priorityShowForSimklItem(
    item: SimklContinueWatchingItem
): CalendarPriorityShow? {
    val imdb = item.imdbId
        ?.trim()
        ?.takeIf { id -> id.startsWith("tt", ignoreCase = true) }
    val tmdb = item.tmdbId?.takeIf { id -> id > 0 }

    val id = imdb ?: tmdb?.let { number -> "tmdb:$number" } ?: return null

    return CalendarPriorityShow(
        id = id,
        // Both spellings the TMDB number can arrive under, so a local history
        // row filed as a bare "130853" is recognised as the same show.
        aliases = buildSet {
            imdb?.let { add(it) }
            tmdb?.let {
                add("tmdb:$it")
                add(it.toString())
            }
        }
    )
}

/**
 * The local / MDBList / Trakt caught-up candidates as priority candidates.
 *
 * Those builders already produce one candidate per show with the episodes each
 * source records watched (and the caps Upcoming itself resolves them under - see
 * [com.kennyb1201.kbstream.ui.home.localCaughtUpCandidates] and its siblings), so
 * this only maps their parent id and the spellings that id implies.
 */
internal fun caughtUpPriorityShows(
    candidates: List<CaughtUpShowCandidate>
): List<CalendarPriorityShow> =
    candidates.mapNotNull { candidate ->
        val id = candidate.parentId.trim()
        if (id.isEmpty()) return@mapNotNull null

        CalendarPriorityShow(id = id, aliases = idAliases(id))
    }

/**
 * The id spellings one parent id implies on its own: the id itself, plus the
 * TMDB number both a bare number and `tmdb:<n>` reduce to. An IMDb id implies
 * only itself - its TMDB twin needs a lookup, which is why a show named by
 * Simkl (that knows both) is the one that collapses the pair.
 */
private fun idAliases(id: String): Set<String> {
    val aliases = LinkedHashSet<String>(2)
    aliases += id

    val numeric = id.lowercase(Locale.ROOT)
        .removePrefix("tmdb:")
        .toIntOrNull()
    if (numeric != null && numeric > 0) {
        aliases += "tmdb:$numeric"
        aliases += numeric.toString()
    }

    return aliases
}

/**
 * The calendar's candidate shows, in a SOURCE-FAIR order, with [priority]
 * always present and the four tracked sources filling the rest up to [cap].
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
 *
 * [priority] is the set Upcoming can advertise (see [calendarPriorityIds]), and
 * it is EXEMPT from [cap]: that cap is what cut Bad Monkey's December premiere
 * off the calendar while the Home rail advertised it, because the show sat past
 * the round-robin cut. Capping the union is what caused that; capping only the
 * unbounded tail (every finished show ever tracked) preserves the bound's
 * purpose - a month build stays bounded - while making "on Upcoming, in the
 * calendar" true by construction. Do not "simplify" this back to one cap: the
 * next library past the number reproduces the bug. Priority ids do not repeat in
 * the tier-2 tail, and [cap] <= 0 therefore leaves just the priority list.
 */
internal fun calendarCandidates(
    sources: List<List<String>>,
    cap: Int,
    priority: List<String> = emptyList()
): List<String> {
    val out = LinkedHashSet<String>(
        priority.size + minOf(cap.coerceAtLeast(0), sources.sumOf { it.size })
    )

    priority.filterTo(out) { id -> id.isNotBlank() }

    if (cap > 0) {
        val cleaned = sources.map { source -> source.filter { it.isNotBlank() } }
        val longest = cleaned.maxOfOrNull { it.size } ?: 0

        for (index in 0 until longest) {
            for (source in cleaned) {
                val id = source.getOrNull(index) ?: continue
                if (!out.add(id)) continue
                if (out.size - priority.size >= cap) return out.toList()
            }
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

    // Every (season, episode) a payload actually DATES. NOT "listed": a season
    // TMDB has created but not scheduled yet carries all ten episodes with no
    // air date, and a season list cached before its dates went in carries the
    // same - so "the payload mentions it" is not the same as "the payload
    // knows when it airs". The announced pointer below is the other statement
    // about that episode and may well be the only one with a date; suppressing
    // it on presence alone is what still kept Bad Monkey's December premiere
    // off the calendar after the pointer was first wired in.
    val dated = HashSet<Pair<Int, Int>>()

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
            dated += season to episode.episodeNumber
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

    // The detail's announced next episode, placed AFTER the payloads so a row
    // the payload could date is never doubled - and only when no payload row
    // could date it, so a payload that merely lists an undated episode cannot
    // silence the one source that knows the date. It is a PREMIERE when it
    // opens its season and never a finale: nothing later in that season is
    // known yet, so a season whose only known episode is this one must not read
    // "FINALE".
    val announced = show.nextEpisode
    if (announced != null && (announced.season to announced.episode.episodeNumber) !in dated) {
        val date = correctedAirDate(
            episode = announced.episode,
            season = announced.season,
            sourceDates = show.sourceDates,
            today = today
        )
        if (date != null && YearMonth.from(date) == month) {
            days.getOrPut(date) { ArrayList() } += CalendarEpisode(
                showId = show.showId,
                openId = show.openId,
                showTitle = show.title,
                poster = show.poster,
                backdrop = show.backdrop,
                season = announced.season,
                episode = announced.episode.episodeNumber,
                episodeTitle = announced.episode.name?.takeIf { it.isNotBlank() },
                airDate = date,
                isPremiere = announced.episode.episodeNumber == 1,
                isFinale = false
            )
        }
    }

    return days
}

/**
 * [days] with each day's rows in [calendarEpisodeOrder], and one row per
 * episode.
 *
 * The de-duplication is what the day list needs to be drawable at all: its
 * LazyColumn keys each row by show/season/episode (the same identity
 * [calendarWatchedKey] carries), and Compose throws "Key was already used" on
 * a repeat. Nothing here is expected to repeat - one show's payload is one
 * list - but a show reachable under two ids is folded once per id by the
 * ViewModel's builder, and a payload that lists an episode twice (TMDB has
 * done it) would crash the pane rather than draw. Kept at this one funnel so
 * every path that publishes a month gets the same guarantee.
 */
internal fun calendarSortedDays(
    days: Map<LocalDate, Collection<CalendarEpisode>>
): Map<LocalDate, List<CalendarEpisode>> =
    days.mapValues { (_, episodes) ->
        episodes
            .distinctBy { calendarWatchedKey(it.showId, it.season, it.episode) }
            .sortedWith(calendarEpisodeOrder)
    }

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
