package com.kennyb1201.kbstream.ui.library

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.airdates.TvmazeAirDateRepository
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.memory.MemoryPressure
import com.kennyb1201.kbstream.data.mdblist.MdbListClient
import com.kennyb1201.kbstream.data.mdblist.MdbListWatchedSnapshot
import com.kennyb1201.kbstream.data.simkl.SimklRepository
import com.kennyb1201.kbstream.data.sync.ProfileManager
import com.kennyb1201.kbstream.data.tmdb.ResolvedEpisode
import com.kennyb1201.kbstream.data.tmdb.TmdbDetail
import com.kennyb1201.kbstream.data.tmdb.TmdbEpisodeAirInfo
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.trakt.TraktIdKeys
import com.kennyb1201.kbstream.data.trakt.TraktRepository
import com.kennyb1201.kbstream.data.trakt.TraktWatchedSnapshot
import com.kennyb1201.kbstream.data.runCatchingCancellable
import com.kennyb1201.kbstream.ui.home.mdbListEpisodePairs
import java.time.LocalDate
import java.time.YearMonth
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Everything the Library calendar renders for one month.
 *
 * [watched] is a flat set of `calendarWatchedKey` strings rather than a
 * per-episode flag on [CalendarEpisode], because watched state changes for
 * reasons the SCHEDULE does not: a mark from Detail, a tracker sync, a
 * profile's own history catching up. Keeping it beside the month is what lets
 * a return to the pane re-tint the rows without rebuilding (or re-requesting)
 * a single air date (see [LibraryCalendarViewModel.refreshWatched]).
 */
data class LibraryCalendarUiState(
    val month: YearMonth = YearMonth.now(),
    val selected: LocalDate = LocalDate.now(),
    val days: Map<LocalDate, List<CalendarEpisode>> = emptyMap(),
    val watched: Set<String> = emptySet(),
    /**
     * True while nothing has landed for [month] yet.
     *
     * A month is no longer all-or-nothing: rows appear as each show resolves
     * (see [LibraryCalendarViewModel.loadMonth]), so this turns false with the
     * FIRST row rather than with the last.
     */
    val loading: Boolean = true,
    /** True when at least one source named a show; false draws the explanation. */
    val hasShows: Boolean = true
) {
    fun episodesOn(day: LocalDate): List<CalendarEpisode> = days[day].orEmpty()

    val monthIsEmpty: Boolean
        get() = days.isEmpty()

    fun isWatched(episode: CalendarEpisode): Boolean =
        calendarWatchedKey(episode.showId, episode.season, episode.episode) in watched
}

/**
 * The Library calendar: a full-year, month-by-month view of every episode of
 * every tracked show that has a date.
 *
 * Deliberately its own ViewModel rather than more state on [LibraryViewModel]:
 * this one fans out over TMDB season payloads for a hundred shows and holds
 * month slices for the session, while the Library's own model is about saved
 * titles. They share the screen, not a lifecycle concern.
 *
 * Nothing here runs on the Home or startup path. The pane calls [open] when it
 * is composed, which is the first and only trigger.
 */
class LibraryCalendarViewModel(
    application: Application
) : AndroidViewModel(application), MemoryPressure.Releasable {

    private val appContext = application.applicationContext

    private val tmdbRepository = TmdbRepository.getInstance(application)
    private val simklRepository = SimklRepository.getInstance(application)
    private val traktRepository = TraktRepository.getInstance(application)
    private val airDateRepository = TvmazeAirDateRepository.getInstance(application)

    private val _uiState = MutableStateFlow(LibraryCalendarUiState())
    val uiState: StateFlow<LibraryCalendarUiState> = _uiState.asStateFlow()

    /** The in-flight month build; paging cancels the one it replaces. */
    private var buildJob: Job? = null

    /**
     * The session's tracked shows, as the ids the sources name them by.
     *
     * Reloaded on [SHOW_SET_TTL_MS] and on a profile switch - the set is four
     * repository reads over cached blobs, so it is cheap, but it is not free
     * enough to redo for every month the viewer pages through.
     */
    private var showIds: List<String> = emptyList()
    private var showsLoadedAt = 0L

    /** TMDB details, by candidate id, for the session. The repository caches too. */
    private val details = HashMap<String, TmdbDetail?>()

    /** Built months, so paging back to a month the viewer already saw is instant. */
    private val monthCache = LinkedHashMap<YearMonth, CalendarMonth>()

    /**
     * Months assembled show-by-show, including the ones still building.
     *
     * The point of keeping this beside [monthCache] is that a build the viewer
     * INTERRUPTED (paging away cancels [buildJob]) keeps what it had already
     * fetched: the month opens on those rows next time and only the shows not
     * yet resolved are asked about again. Without it, a slow month could never
     * accumulate anything, and every visit restarted from nothing - which is
     * what made the first look at a future month feel like it would never load.
     */
    private class MonthProgress {
        val days = HashMap<LocalDate, MutableList<CalendarEpisode>>()
        val resolved = HashSet<String>()

        /**
         * The shows already folded into [days], by their resolved TMDB id.
         *
         * A candidate id is not a show: the sources name the same show by an
         * IMDb id and by a `tmdb:` key (Simkl hands over both, and the local
         * history may use the other spelling again), so two candidates can
         * resolve to one show. Folding both would draw every one of its
         * episodes twice on its day, and the day list keys its rows by
         * show/season/episode - a duplicate is a Compose crash ("Key was
         * already used"), which is exactly what was reported from the TV.
         */
        val shows = HashSet<String>()
        var lastPublishedAt = 0L
    }

    private val progress = LinkedHashMap<YearMonth, MonthProgress>()

    init {
        observeProfileSwitches()
        // The calendar is the one pane that walks a viewer through month after
        // month of a library, and each month it keeps is a month of resolved
        // shows. Registered like every other browsing cache, so a tight heap (or
        // the player opening) can take it back instead of it sitting on the
        // memory the next screen needs; the sizes are what a field report reads
        // in Diagnostics (see MemoryPressure).
        MemoryPressure.register(this)
    }

    /**
     * The pane was composed (or the viewer came back to it): build the month on
     * screen, or republish the cached one, and re-read watched state.
     *
     * The show set is re-read only when it is stale, and a cached month is
     * never rebuilt just because the pane reopened - the spec's TTL discipline,
     * the same shape the Upcoming rail uses.
     */
    fun open() {
        val state = _uiState.value
        val stale = System.currentTimeMillis() - showsLoadedAt >= SHOW_SET_TTL_MS

        if (stale || showIds.isEmpty()) {
            viewModelScope.launch {
                loadShowIds()
                loadMonth(state.month, landingDay = state.selected)
            }
            return
        }

        val cached = monthCache[state.month]
        if (cached != null) {
            publish(cached, landingDay = state.selected)
            viewModelScope.launch { refreshWatched() }
        } else {
            loadMonth(state.month, landingDay = state.selected)
        }
    }

    /** A day was focused or tapped: select it, so the list below follows. */
    fun select(day: LocalDate) {
        if (_uiState.value.selected == day) return
        _uiState.value = _uiState.value.copy(selected = day)
    }

    /** Prev/next month, carrying the December/January edge through [YearMonth]. */
    fun pageMonth(delta: Int) {
        val target = pageMonth(_uiState.value.month, delta)
        loadMonth(target, landingDay = null)
    }

    /** The Today control: back to the current month, on today. */
    fun goToToday() {
        loadMonth(YearMonth.now(), landingDay = LocalDate.now())
    }

    /**
     * Re-reads ONLY the watched union, for the shows on screen.
     *
     * Called on resume, so an episode marked watched from Detail is tinted by
     * the time the viewer gets back without the schedule being rebuilt: dates
     * did not change, watched state did.
     */
    suspend fun refreshWatched() {
        val state = _uiState.value
        val showIds = state.days.values.flatten().map { it.showId }.toSet()
        if (showIds.isEmpty()) return
        val watched = mergeWatched(showIds)
        _uiState.value = _uiState.value.copy(
            watched = watched.flatMapTo(HashSet()) { (showId, pairs) ->
                pairs.map { (season, episode) ->
                    calendarWatchedKey(showId, season, episode)
                }
            }
        )
    }

    /**
     * A profile switch invalidates everything: the show set, the details, every
     * built month and the watched union all belong to the profile that built
     * them. The calendar is activity-scoped, so without this a switch showed the
     * previous profile's episodes on the incoming one's calendar.
     */
    private fun observeProfileSwitches() {
        viewModelScope.launch {
            var first = true
            ProfileManager.activeProfile.collect {
                if (first) {
                    first = false
                    return@collect
                }
                forgetEverything()
                loadShowIds()
                loadMonth(_uiState.value.month, landingDay = null)
            }
        }
    }

    /**
     * Holds the session's built months to [MAX_CACHED_MONTHS].
     *
     * Paging through a year used to keep every month it had built, and a month
     * is a whole library's episodes. The cap is small on purpose: the month on
     * screen is in the UI state either way, and a month dropped here only costs
     * the TMDB/tracker re-read that a first visit to it costs - which the
     * per-show [MonthProgress] then makes incremental again.
     */
    private fun trimMonthCache(keep: YearMonth) {
        while (monthCache.size > MAX_CACHED_MONTHS) {
            val oldest = monthCache.keys.firstOrNull { it != keep } ?: return
            monthCache.remove(oldest)
        }
    }

    /**
     * Holds the session's INTERRUPTED builds to [MAX_CACHED_MONTHS] as well.
     *
     * These exist so a build the viewer paged away from resumes where it left
     * off, which is what made a future month's first look open on rows instead
     * of a blank grid. Keeping a year of half-built months made that the same
     * unbounded store as the month cache.
     */
    private fun trimProgress(keep: YearMonth) {
        while (progress.size > MAX_CACHED_MONTHS) {
            val oldest = progress.keys.firstOrNull { it != keep } ?: return
            progress.remove(oldest)
        }
    }

    /**
     * Gives the session's caches back: the month build behind the pane keeps its
     * published rows (they are in the UI state), so nothing on screen changes.
     */
    override fun releaseCaches() {
        details.clear()
        monthCache.clear()
        progress.clear()
    }

    /**
     * The calendar's caches against their caps, for the diagnostics dump.
     *
     * [details] is bounded by construction - one entry per candidate show, and
     * the candidate set itself is capped at [MAX_CALENDAR_SHOWS] - so its cap is
     * that number rather than a second constant to keep in step.
     */
    override fun cacheStats(): String =
        "calendar: months=${monthCache.size}/$MAX_CACHED_MONTHS" +
            " details=${details.size}/$MAX_CALENDAR_SHOWS" +
            " progress=${progress.size}/$MAX_CACHED_MONTHS"

    /** Drops every per-profile fact this session gathered. */
    private fun forgetEverything() {
        showIds = emptyList()
        showsLoadedAt = 0L
        details.clear()
        monthCache.clear()
        progress.clear()
    }

    /**
     * The tracked-show set, from all four sources, with no caught-up filter.
     *
     * This is the union the spec asks for: this profile's own history (its
     * completed series rows and its resume rows), Simkl's library (the completed
     * and partially-watched show sets the watched markers already load - NOT the
     * rail's unaired-flagged subset), MDBList's started shows, and Trakt's
     * watched shows. Every read fails soft: a tracker that is down contributes
     * nothing and the calendar shows the rest.
     *
     * The four lists are then interleaved by [calendarCandidates] rather than
     * concatenated, so a long local history cannot consume the whole resolution
     * allowance and leave the tracker-sourced - i.e. currently airing - shows
     * out of the calendar entirely.
     */
    private suspend fun loadShowIds() {
        val local = runCatchingCancellable {
            val dao = historyDao()
            (
                dao.getCompletedSeriesRows() + dao.getResumeRowsForLauncher()
                )
                .filterNot { it.type.lowercase().contains("movie") }
                .mapNotNull { it.parentId.takeIf { parent -> parent.isNotBlank() } }
        }.getOrNull().orEmpty()

        val simkl = if (simklRepository.isConfigured() && simklRepository.hasToken()) {
            runCatchingCancellable {
                buildList {
                    addAll(simklRepository.getCompletedShowImdbIds())
                    addAll(simklRepository.getPartiallyWatchedShowImdbIds())
                    addAll(simklRepository.getCompletedShowTmdbKeys())
                    addAll(simklRepository.getPartiallyWatchedShowTmdbKeys())
                }
            }.getOrElse { error ->
                Log.w(TAG, "simkl shows unavailable: ${error.message}")
                emptyList()
            }
        } else {
            emptyList()
        }

        val mdbList = runCatchingCancellable {
            MdbListClient.getWatchedSnapshot(appContext)
        }.getOrNull()
            ?.startedShowKeys
            ?.toList()
            .orEmpty()

        val trakt = if (traktRepository.isConfigured() && traktRepository.hasToken()) {
            runCatchingCancellable { traktRepository.watchedSnapshot() }
                .getOrNull()
                ?.shows
                ?.mapNotNull { row -> preferredId(row.idKeys) }
                .orEmpty()
        } else {
            emptyList()
        }

        showIds = calendarCandidates(
            sources = listOf(local, simkl, mdbList, trakt),
            cap = MAX_CALENDAR_SHOWS
        )
        showsLoadedAt = System.currentTimeMillis()
    }

    /**
     * This profile's watch history.
     *
     * Resolved PER READ, never captured in a field: every profile has its own
     * history file, and switching profiles retires (closes) the previous
     * instance a few seconds later. A DAO captured at construction therefore
     * answers every read after a switch with "attempt to re-open an
     * already-closed object", which the soft-failure wrapping below turns into
     * an empty read - so a profile's own shows silently vanished from its
     * calendar while the account-level trackers kept filling someone else's.
     */
    private fun historyDao() =
        WatchHistoryDatabase.getInstanceScoped(appContext).watchHistoryDao()

    /** The id a Trakt row is addressed by here: IMDb first, then TMDB. */
    private fun preferredId(idKeys: Set<String>): String? =
        idKeys.firstOrNull { it.startsWith("tt") }
            ?: idKeys.firstOrNull { it.startsWith("tmdb:") }

    /**
     * Builds (or republishes) one month.
     *
     * A cached month is published immediately and only its watched state is
     * re-read - that is what makes paging back instant while a mark made in the
     * meantime still tints. An uncached month shows whatever an earlier,
     * interrupted visit to it already resolved (see [MonthProgress]) and then
     * fills the rest in AS EACH SHOW LANDS: a hundred TMDB round trips no longer
     * stand between the viewer and an empty grid. The month being LEFT is
     * dropped at once (rather than staying up under a spinner) so a slow build
     * can never show October's episodes under a November header.
     */
    private fun loadMonth(month: YearMonth, landingDay: LocalDate?) {
        buildJob?.cancel()

        val cached = monthCache[month]
        if (cached != null) {
            publish(cached, landingDay)
            viewModelScope.launch { refreshWatched() }
            return
        }

        val build = progress.getOrPut(month) { MonthProgress() }
        trimProgress(keep = month)
        val selectedWhenStarted = _uiState.value.selected

        _uiState.value = _uiState.value.copy(
            month = month,
            days = calendarSortedDays(build.days),
            selected = landingDayFor(month, landingDay, build.days),
            loading = build.days.isEmpty(),
            hasShows = showIds.isNotEmpty()
        )

        buildJob = viewModelScope.launch {
            resolveMonth(month, build, landingDay, selectedWhenStarted)
        }
    }

    /**
     * Resolves one month, show by show, publishing as it goes.
     *
     * A cancelled build (the viewer paged away) leaves [MonthProgress] holding
     * everything it had resolved; the next visit picks up from there.
     */
    private suspend fun resolveMonth(
        month: YearMonth,
        build: MonthProgress,
        landingDay: LocalDate?,
        selectedWhenStarted: LocalDate
    ) {
        val remaining = showIds.filterNot { it in build.resolved }
        val semaphore = Semaphore(CALENDAR_CONCURRENCY)

        coroutineScope {
            remaining.map { id ->
                async {
                    semaphore.withPermit {
                        val show = runCatchingCancellable {
                            calendarShowFor(id, month)
                        }.getOrNull()
                        noteResolved(month, build, id, show)
                    }
                }
            }.awaitAll()
        }

        val built = CalendarMonth(
            month = month,
            days = calendarSortedDays(build.days)
        )
        monthCache[month] = built
        trimMonthCache(keep = month)
        progress.remove(month)

        // The viewer may have walked the grid while this was landing; their day
        // wins over the one the month would have chosen for them.
        val moved = _uiState.value.selected
            .takeIf { _uiState.value.month == month && it != selectedWhenStarted }
        publish(built, landingDay, keepSelection = moved)
        refreshWatched()
    }

    /**
     * Files one resolved show (or one that had nothing to say) and paints the
     * month if it gained anything - or at most every [PARTIAL_PUBLISH_MS], so a
     * hundred misses do not compose a hundred times.
     */
    private fun noteResolved(
        month: YearMonth,
        build: MonthProgress,
        candidateId: String,
        show: CalendarShow?
    ) {
        build.resolved += candidateId
        var gained = false
        // One show, folded once - however many candidate ids named it (see
        // MonthProgress.shows). A repeat is still marked resolved so it is not
        // asked about again.
        if (show != null && build.shows.add(show.showId)) {
            for ((day, episodes) in calendarEpisodesFor(month, show, LocalDate.now())) {
                build.days.getOrPut(day) { ArrayList() } += episodes
                gained = true
            }
        }
        if (_uiState.value.month != month) return

        val now = System.currentTimeMillis()
        if (!gained && now - build.lastPublishedAt < PARTIAL_PUBLISH_MS) return
        build.lastPublishedAt = now
        _uiState.value = _uiState.value.copy(
            days = calendarSortedDays(build.days),
            loading = false,
            hasShows = showIds.isNotEmpty()
        )
    }

    /** Puts a built month on screen, with the day the viewer should be on. */
    private fun publish(
        month: CalendarMonth,
        landingDay: LocalDate?,
        keepSelection: LocalDate? = null
    ) {
        _uiState.value = _uiState.value.copy(
            month = month.month,
            days = month.days,
            selected = landingDayFor(month.month, landingDay, month.days, keepSelection),
            hasShows = showIds.isNotEmpty(),
            loading = false
        )
    }

    /** Which day a month opens on: the viewer's own, else today, else the rule. */
    private fun landingDayFor(
        month: YearMonth,
        landingDay: LocalDate?,
        days: Map<LocalDate, List<CalendarEpisode>>,
        keepSelection: LocalDate? = null
    ): LocalDate {
        val today = LocalDate.now()
        return when {
            keepSelection != null && YearMonth.from(keepSelection) == month -> keepSelection
            landingDay != null && YearMonth.from(landingDay) == month -> landingDay
            landingDay != null && landingDay == today -> today
            else -> calendarLanding(month, days, today).day
        }
    }

    /**
     * Every tracked show that has something in [month], resolved for it.
     *
     * Shapes are resolved in parallel ([CALENDAR_CONCURRENCY]) and each show is
     * one detail lookup plus the season payloads [seasonsCoveringMonth] says can
     * matter - so opening October does not walk a show's whole run, and the
     * TMDB season cache carries the result into the next month that needs it.
     * Shows are de-duplicated by their TMDB id, because the same show reaches
     * here under an IMDb id from one source and a TMDB id from another.
     */
    private suspend fun calendarShowFor(
        candidateId: String,
        month: YearMonth
    ): CalendarShow? {
        val detail = detailFor(candidateId) ?: return null

        val imdbId = candidateId
            .takeIf { it.startsWith("tt", ignoreCase = true) }
            ?.lowercase()
            ?: runCatchingCancellable {
                tmdbRepository.resolveImdbId(detail.id, "series")
            }.getOrNull()

        val seasonNumbers = seasonsCoveringMonth(detail.seasons, month)

        val seasons = LinkedHashMap<Int, List<ResolvedEpisode>>()
        for (season in seasonNumbers) {
            val episodes = runCatchingCancellable {
                tmdbRepository.getSeasonEpisodes(detail.id, season, imdbId ?: candidateId)
            }.getOrNull().orEmpty()
            if (episodes.isNotEmpty()) seasons[season] = episodes
        }

        // The detail's next_episode_to_air is a second, independent statement
        // about the schedule - the one the Home Upcoming rail builds its card
        // from. A returning show's premiere is announced there before TMDB's
        // season payload DATES it, so without this the rail already knew a date
        // the calendar could not place. Always carried; the month builder skips
        // it only when a payload row could date that episode (see
        // CalendarShow.nextEpisode).
        val announced = announcedNextEpisode(detail.nextEpisodeToAir)

        if (seasons.isEmpty() && announced == null) return null

        // The second source corrects TMDB's dates exactly as the Upcoming rail
        // does; without it the calendar and the rail could label one episode
        // with two different days.
        val sourceDates = imdbId
            ?.let { id ->
                runCatchingCancellable { airDateRepository.episodeAirDates(id) }.getOrNull()
            }
            .orEmpty()

        return CalendarShow(
            showId = "tmdb:${detail.id}",
            // Detail is routed by the id the app already opens titles with: an
            // IMDb id when there is one, else the TMDB number.
            openId = imdbId ?: detail.id.toString(),
            title = detail.name?.takeIf { it.isNotBlank() }
                ?: detail.title?.takeIf { it.isNotBlank() }
                ?: candidateId,
            poster = detail.posterPath
                ?.takeIf { it.isNotBlank() }
                ?.let { TmdbRepository.POSTER_BASE + it },
            backdrop = detail.backdropPath
                ?.takeIf { it.isNotBlank() }
                ?.let { TmdbRepository.BACKDROP_BASE + it },
            seasons = seasons,
            sourceDates = sourceDates,
            nextEpisode = announced
        )
    }

    /**
     * The detail pointer as an episode to place, or null when there is nothing
     * to place: no pointer, or no episode number.
     *
     * The duplicate check is NOT here, because presence is the wrong test: a
     * season payload lists an announced episode long before it dates it (and a
     * season list cached before the dates went in keeps the undated row for a
     * week), so a payload that merely names the episode used to cancel the one
     * statement that knew the date - Bad Monkey's Dec 1 premiere. The pointer
     * is always carried and [calendarEpisodesFor] places it only when no
     * payload row could DATE that episode, which is where the dates are known.
     */
    private fun announcedNextEpisode(
        next: TmdbEpisodeAirInfo?
    ): CalendarNextEpisode? {
        val season = next?.seasonNumber?.takeIf { it > 0 } ?: return null
        val number = next.episodeNumber?.takeIf { it > 0 } ?: return null

        return CalendarNextEpisode(
            season = season,
            episode = ResolvedEpisode(
                // Never played from here - the calendar only needs the number,
                // the title and the date; the id is the season/episode pair so
                // it is at least a stable, readable value.
                streamId = "s${season}e$number",
                episodeNumber = number,
                name = next.name?.takeIf { it.isNotBlank() },
                overview = null,
                thumbnail = null,
                runtimeMinutes = null,
                airDate = next.airDate,
                voteAverage = null
            )
        )
    }

    /** One cached TMDB series detail; a miss (null) is remembered for the session. */
    private suspend fun detailFor(candidateId: String): TmdbDetail? {
        if (details.containsKey(candidateId)) return details[candidateId]

        val resolved = runCatchingCancellable {
            when {
                candidateId.startsWith("tmdb:", ignoreCase = true) ->
                    candidateId.substringAfter(':')
                        .toIntOrNull()
                        ?.let { tmdbRepository.getDetailByTmdbId(it, "series") }

                candidateId.startsWith("tt", ignoreCase = true) ->
                    tmdbRepository.fetchEnrichedMetaCached(candidateId, "series")

                candidateId.toIntOrNull() != null ->
                    tmdbRepository.getDetailByTmdbId(candidateId.toInt(), "series")

                else -> null
            }
        }.getOrNull()

        details[candidateId] = resolved
        return resolved
    }

    /**
     * The watched union for the shows on screen, per show.
     *
     * The same four sources the rails merge - this profile's completed rows,
     * Simkl, MDBList and Trakt - read through the same repositories, with every
     * read failing soft. Only the shows in the month are asked about, so a page
     * of the calendar costs a handful of cached reads rather than one per show in
     * the library.
     */
    private suspend fun mergeWatched(
        showIds: Set<String>
    ): Map<String, Set<Pair<Int, Int>>> {
        val local = localWatchedRows()

        val mdbList = runCatchingCancellable {
            MdbListClient.getWatchedSnapshot(appContext)
        }.getOrNull() ?: MdbListWatchedSnapshot()

        val trakt = if (
            traktRepository.isConfigured() &&
            traktRepository.hasToken()
        ) {
            runCatchingCancellable { traktRepository.watchedSnapshot() }
                .getOrNull()
                ?: TraktWatchedSnapshot()
        } else {
            TraktWatchedSnapshot()
        }

        val simklReady = simklRepository.isConfigured() && simklRepository.hasToken()
        val out = HashMap<String, Set<Pair<Int, Int>>>()

        for (showId in showIds) {
            val keys = keysFor(showId)
            val pairs = LinkedHashSet<Pair<Int, Int>>()

            keys.forEach { key ->
                local[key]?.let(pairs::addAll)
                mdbList.episodeKeys
                    .takeIf { it.isNotEmpty() }
                    ?.let { pairs += mdbListEpisodePairs(key, it) }
            }

            if (trakt.shows.isNotEmpty()) {
                pairs += trakt.watchedEpisodesFor(keys)
            }

            if (simklReady) {
                val imdb = keys.firstOrNull { it.startsWith("tt") }
                val tmdb = showId.substringAfter(':', "").toIntOrNull()
                if (imdb != null) {
                    runCatchingCancellable {
                        simklRepository.getWatchedEpisodesForShowByImdb(imdb, tmdb)
                    }.getOrNull()?.let(pairs::addAll)
                }
            }

            if (pairs.isNotEmpty()) out[showId] = pairs
        }

        return out
    }

    /** Every canonical id form one show answers to, for the source lookups. */
    private suspend fun keysFor(showId: String): Set<String> {
        val keys = linkedSetOf(showId.lowercase())
        val tmdbId = showId.substringAfter(':', "").toIntOrNull()
        if (tmdbId != null) {
            runCatchingCancellable {
                tmdbRepository.resolveImdbId(tmdbId, "series")
            }.getOrNull()
                ?.let { imdb -> TraktIdKeys.canonical(imdb)?.let(keys::add) }
        }
        return keys
    }

    /**
     * This profile's completed series rows, as canonical parent id -> pairs.
     *
     * Canonicalized through [TraktIdKeys] for the same reason the watched union
     * is: a row filed under a bare TMDB number and a candidate named by an IMDb
     * id are the same show, and only one spelling of each can be compared.
     */
    private suspend fun localWatchedRows(): Map<String, Set<Pair<Int, Int>>> {
        val rows = runCatchingCancellable { historyDao().getCompletedSeriesRows() }
            .getOrNull()
            .orEmpty()

        val out = HashMap<String, MutableSet<Pair<Int, Int>>>()
        for (row in rows) {
            if (row.type.lowercase().contains("movie")) continue
            val key = TraktIdKeys.canonical(row.parentId) ?: continue
            val season = row.season ?: continue
            val episode = com.kennyb1201.kbstream.data.namedEpisodeNumber(row.episode)
                ?: continue
            out.getOrPut(key) { HashSet() }.add(season to episode)
        }
        return out
    }

    private companion object {
        const val TAG = "KB_CALENDAR"

        /** How long the tracked-show set is trusted before it is re-read. */
        const val SHOW_SET_TTL_MS = 30L * 60L * 1000L

        /** Parallel TMDB/TVmaze resolutions per month build. */
        const val CALENDAR_CONCURRENCY = 4

        /**
         * Bound on how many shows one month build will resolve.
         *
         * A bound rather than a bug fix: the candidate order is source-fair
         * (see [calendarCandidates]), so this only ever trims a library with
         * hundreds of tracked shows, and pages after the first cost no new
         * detail lookups (they are cached for the session).
         */
        const val MAX_CALENDAR_SHOWS = 300

        /** Quiet gap between two partial month publishes, in milliseconds. */
        const val PARTIAL_PUBLISH_MS = 180L

        /** Built months (and interrupted builds) the session keeps. */
        const val MAX_CACHED_MONTHS = 4
    }
}
