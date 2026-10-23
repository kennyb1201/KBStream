package com.kennyb1201.kbstream.ui.library

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.airdates.TvmazeAirDateRepository
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.mdblist.MdbListClient
import com.kennyb1201.kbstream.data.mdblist.MdbListWatchedSnapshot
import com.kennyb1201.kbstream.data.simkl.SimklRepository
import com.kennyb1201.kbstream.data.sync.ProfileManager
import com.kennyb1201.kbstream.data.tmdb.ResolvedEpisode
import com.kennyb1201.kbstream.data.tmdb.TmdbDetail
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
    /** True while the first build for [month] is in flight. */
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
) : AndroidViewModel(application) {

    private val appContext = application.applicationContext

    private val tmdbRepository = TmdbRepository.getInstance(application)
    private val simklRepository = SimklRepository.getInstance(application)
    private val traktRepository = TraktRepository.getInstance(application)
    private val airDateRepository = TvmazeAirDateRepository.getInstance(application)
    private val historyDao = WatchHistoryDatabase.getInstance(application).watchHistoryDao()

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

    init {
        observeProfileSwitches()
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
                showIds = emptyList()
                showsLoadedAt = 0L
                details.clear()
                monthCache.clear()
                loadShowIds()
                loadMonth(_uiState.value.month, landingDay = null)
            }
        }
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
     */
    private suspend fun loadShowIds() {
        val ids = LinkedHashSet<String>()

        val localRows = runCatchingCancellable { historyDao.getCompletedSeriesRows() }
            .getOrNull()
            .orEmpty() +
            runCatchingCancellable { historyDao.getResumeRowsForLauncher() }
                .getOrNull()
                .orEmpty()

        localRows.forEach { row ->
            if (row.type.lowercase().contains("movie")) return@forEach
            row.parentId.takeIf { it.isNotBlank() }?.let(ids::add)
        }

        if (simklRepository.isConfigured() && simklRepository.hasToken()) {
            val simkl = runCatchingCancellable {
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
            ids += simkl.filter { it.isNotBlank() }
        }

        runCatchingCancellable { MdbListClient.getWatchedSnapshot(appContext) }
            .getOrNull()
            ?.startedShowKeys
            ?.let { ids += it }

        if (traktRepository.isConfigured() && traktRepository.hasToken()) {
            runCatchingCancellable { traktRepository.watchedSnapshot() }
                .getOrNull()
                ?.shows
                ?.forEach { row ->
                    preferredId(row.idKeys)?.let(ids::add)
                }
        }

        showIds = ids.toList()
        showsLoadedAt = System.currentTimeMillis()
    }

    /** The id a Trakt row is addressed by here: IMDb first, then TMDB. */
    private fun preferredId(idKeys: Set<String>): String? =
        idKeys.firstOrNull { it.startsWith("tt") }
            ?: idKeys.firstOrNull { it.startsWith("tmdb:") }

    /**
     * Builds (or republishes) one month.
     *
     * A cached month is published immediately and only its watched state is
     * re-read - that is what makes paging back instant while a mark made in the
     * meantime still tints. An uncached month is built off the main thread and
     * replaces the state when it lands; the month being LEFT is dropped from the
     * grid at once (rather than staying up under a spinner) so a slow build can
     * never show October's episodes under a November header.
     */
    private fun loadMonth(month: YearMonth, landingDay: LocalDate?) {
        buildJob?.cancel()

        val cached = monthCache[month]
        if (cached != null) {
            publish(cached, landingDay)
            viewModelScope.launch { refreshWatched() }
            return
        }

        _uiState.value = _uiState.value.copy(
            month = month,
            days = emptyMap(),
            loading = true
        )

        buildJob = viewModelScope.launch {
            val shows = calendarShowsFor(month)
            val built = buildCalendarMonth(month, shows, LocalDate.now())
            monthCache[month] = built
            publish(built, landingDay)
            refreshWatched()
        }
    }

    /** Puts a built month on screen, with the day the viewer should be on. */
    private fun publish(month: CalendarMonth, landingDay: LocalDate?) {
        val today = LocalDate.now()
        val selected = when {
            landingDay != null && YearMonth.from(landingDay) == month.month -> landingDay
            landingDay != null && landingDay == today -> today
            else -> calendarLanding(month.month, month.days, today).day
        }

        _uiState.value = _uiState.value.copy(
            month = month.month,
            days = month.days,
            selected = selected,
            hasShows = showIds.isNotEmpty(),
            loading = false
        )
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
    private suspend fun calendarShowsFor(month: YearMonth): List<CalendarShow> {
        val candidates = showIds.take(MAX_CALENDAR_SHOWS)
        if (candidates.isEmpty()) return emptyList()

        val semaphore = Semaphore(CALENDAR_CONCURRENCY)
        val resolved = coroutineScope {
            candidates.map { id ->
                async {
                    semaphore.withPermit {
                        runCatchingCancellable { calendarShowFor(id, month) }.getOrNull()
                    }
                }
            }.awaitAll()
        }

        val byShow = LinkedHashMap<String, CalendarShow>()
        resolved.filterNotNull().forEach { show ->
            byShow.putIfAbsent(show.showId, show)
        }
        return byShow.values.toList()
    }

    /** One show's month-relevant seasons, artwork and source dates. */
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
        if (seasonNumbers.isEmpty()) return null

        val seasons = LinkedHashMap<Int, List<ResolvedEpisode>>()
        for (season in seasonNumbers) {
            val episodes = runCatchingCancellable {
                tmdbRepository.getSeasonEpisodes(detail.id, season, imdbId ?: candidateId)
            }.getOrNull().orEmpty()
            if (episodes.isNotEmpty()) seasons[season] = episodes
        }
        if (seasons.isEmpty()) return null

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
            sourceDates = sourceDates
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
        val rows = runCatchingCancellable { historyDao.getCompletedSeriesRows() }
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

        /** Bound on how many shows one month build will resolve. */
        const val MAX_CALENDAR_SHOWS = 200
    }
}
