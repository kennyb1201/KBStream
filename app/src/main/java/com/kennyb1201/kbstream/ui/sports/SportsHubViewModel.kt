package com.kennyb1201.kbstream.ui.sports

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.iptv.GuideMatchQuery
import com.kennyb1201.kbstream.data.iptv.IptvChannel
import com.kennyb1201.kbstream.data.iptv.IptvRepository
import com.kennyb1201.kbstream.data.iptv.db.EpgProgramRow
import com.kennyb1201.kbstream.data.iptv.epgProgramChannelKey
import com.kennyb1201.kbstream.data.iptv.playlistUrlsOf
import com.kennyb1201.kbstream.data.reporting.PerfTrace
import com.kennyb1201.kbstream.data.runCatchingCancellable
import com.kennyb1201.kbstream.data.settings.AppPreferences
import com.kennyb1201.kbstream.data.sports.EspnGameSummary
import com.kennyb1201.kbstream.data.sports.EspnSportsRepository
import com.kennyb1201.kbstream.data.sports.EspnSummaryRules
import com.kennyb1201.kbstream.data.sports.GameState
import com.kennyb1201.kbstream.data.sports.MatcherProgram
import com.kennyb1201.kbstream.data.sports.SportsChannelMatcher
import com.kennyb1201.kbstream.data.sports.SportsChannelMemory
import com.kennyb1201.kbstream.data.sports.SportsGame
import com.kennyb1201.kbstream.data.sports.SportsKind
import com.kennyb1201.kbstream.data.sports.SportsLeague
import com.kennyb1201.kbstream.data.sports.SportsLeagues
import com.kennyb1201.kbstream.data.sports.SportsTeam
import com.kennyb1201.kbstream.data.sports.StandingGroup
import com.kennyb1201.kbstream.data.sports.TeamVariants
import com.kennyb1201.kbstream.data.sports.TournamentEvent
import com.kennyb1201.kbstream.data.sports.involvesFavorite
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import com.kennyb1201.kbstream.work.SportsNotificationWorker
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Everything one league tab holds.
 *
 * A league that is enabled but has nothing on today is NOT a failure - in
 * July the NHL is simply between seasons - so emptiness and [failed] are
 * separate facts, and the screen says "No games today" or "Couldn't reach
 * ESPN" accordingly.
 */
data class LeagueSection(
    val league: SportsLeague,
    val games: List<SportsGame> = emptyList(),
    val tournaments: List<TournamentEvent> = emptyList(),
    val failed: Boolean = false,
)

/**
 * Which body the hub shows for the league that is selected: its schedule, or
 * its standings table.
 */
enum class LeagueView { GAMES, STANDINGS }

/**
 * Whether the hub has a lineup to match games against.
 *
 * The distinction the hub could not make before, and the reason a hub with an
 * unread playlist looked EXACTLY like a hub whose playlist carries none of
 * today's games: both answered "not in your playlist" on every card. [MISSING]
 * is the hub's own state, not the game's, and the screen says so in those words.
 */
enum class LineupStatus {
    /** Nothing read yet - the first lineup read is still in flight. */
    LOADING,

    /** A lineup was read: a card that matched nothing genuinely is not carried. */
    READY,

    /** No playlist is configured, or the cached one is empty and unreadable. */
    MISSING,
}

/**
 * The sports hub's state holder: ESPN scoreboards in, playlist channels out.
 *
 * The interesting part is the middle - the bridge from "the Lakers are on
 * ESPN" to the channel in the viewer's own M3U that is carrying it - and that
 * work is delegated to [SportsChannelMatcher], which is pure and unit tested.
 * This class only supplies it with facts: the leagues the profile enabled, the
 * scoreboards for those leagues, the playlist's channels, and the EPG rows
 * near each game.
 */
class SportsHubViewModel(app: Application) : AndroidViewModel(app) {

    private val iptv = IptvRepository.shared(app)
    private val espn = EspnSportsRepository.shared()
    private val prefs: SharedPreferences = app.getSharedPreferences(
        ProfileStorage.prefsName(app, GuideFilesPrefs),
        Context.MODE_PRIVATE
    )

    private val _enabledLeagues = MutableStateFlow(AppPreferences.getSportsEnabledLeagues(app))
    val enabledLeagues: StateFlow<Set<String>> = _enabledLeagues.asStateFlow()

    /**
     * The viewer's own league order, as a run of league paths. Empty means
     * catalog order; see [moveLeague].
     */
    private val _leagueOrder = MutableStateFlow(AppPreferences.getSportsLeagueOrder(app))
    val leagueOrder: StateFlow<List<String>> = _leagueOrder.asStateFlow()

    private val _selectedLeaguePath = MutableStateFlow<String?>(null)
    val selectedLeaguePath: StateFlow<String?> = _selectedLeaguePath.asStateFlow()

    private val _sections = MutableStateFlow<List<LeagueSection>>(emptyList())
    val sections: StateFlow<List<LeagueSection>> = _sections.asStateFlow()

    /**
     * The team keys the FAVORITES tab follows, as
     * [SportsTeam.favoriteKey] strings.
     */
    private val _favoriteTeamKeys = MutableStateFlow(
        AppPreferences.getSportsFavoriteTeams(app)
    )
    val favoriteTeamKeys: StateFlow<Set<String>> = _favoriteTeamKeys.asStateFlow()

    /**
     * The FAVORITES tab's own section: every game in the enabled leagues that
     * one of the viewer's teams is playing.
     *
     * Built from the sections the hub already fetched rather than from a fetch
     * of its own: [refresh] loads every enabled league up front, so this tab
     * costs a filter rather than a round trip, and a game it shows is a game
     * whose channel has already been resolved.
     *
     * Tournaments are deliberately absent. A golf field or a Grand Prix grid is
     * a list of people, not two teams, so there is nothing in it to follow.
     */
    val favoritesSection: StateFlow<LeagueSection> =
        combine(_sections, _favoriteTeamKeys) { sections, keys ->
            LeagueSection(
                league = SportsLeagues.FAVORITES,
                games = if (keys.isEmpty()) {
                    emptyList()
                } else {
                    sections.flatMap { it.games }.filter { it.involvesFavorite(keys) }
                }
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.Eagerly,
            initialValue = LeagueSection(SportsLeagues.FAVORITES)
        )

    /**
     * Which body the hub is showing for the selected league.
     *
     * A toggle within the league rather than a top-level STANDINGS tab (the
     * spec allows either - one pattern only): the standings are a property of
     * the league already selected, and a tab row that mixed leagues with a view
     * would make "which league am I reading?" a question the row cannot answer.
     * A league the feed has no table for never offers the toggle at all.
     */
    private val _view = MutableStateFlow(LeagueView.GAMES)
    val view: StateFlow<LeagueView> = _view.asStateFlow()

    /** Standings by league path; absent until the league's table is fetched. */
    private val _standings = MutableStateFlow<Map<String, List<StandingGroup>>>(emptyMap())
    val standings: StateFlow<Map<String, List<StandingGroup>>> = _standings.asStateFlow()

    private val _standingsLoading = MutableStateFlow<Set<String>>(emptySet())
    val standingsLoading: StateFlow<Set<String>> = _standingsLoading.asStateFlow()

    private val _standingsFailed = MutableStateFlow<Set<String>>(emptySet())
    val standingsFailed: StateFlow<Set<String>> = _standingsFailed.asStateFlow()

    /**
     * Whether followed teams get a "game starts soon" reminder.
     *
     * Default ON, because following a team is itself the opt-in - and the round
     * is only ever armed while at least one team is followed, so this costs
     * nothing to a viewer who never used the hub.
     */
    private val _gameReminders = MutableStateFlow(AppPreferences.getSportsGameReminders(app))
    val gameReminders: StateFlow<Boolean> = _gameReminders.asStateFlow()

    /** Writes the reminder toggle and re-arms (or cancels) the periodic round. */
    fun setGameReminders(enabled: Boolean) {
        AppPreferences.setSportsGameReminders(getApplication(), enabled)
        _gameReminders.value = enabled
        SportsNotificationWorker.syncScheduleForPrefs(getApplication())
    }

    /**
     * The open detail sheet's game summary - team stats, win probability, the
     * last play - or null when there is none to draw.
     *
     * Null is the ordinary state, not a failure: an upcoming or final game
     * never fetches one, a league ESPN does not summarise has nothing to parse,
     * and a request that fails leaves the sheet exactly as it is today. The
     * sheet therefore needs no error state and no spinner - it draws the stats
     * section when this is non-empty and skips it otherwise.
     */
    private val _detailSummary = MutableStateFlow<EspnGameSummary?>(null)
    val detailSummary: StateFlow<EspnGameSummary?> = _detailSummary.asStateFlow()

    /**
     * The sheet's own game: the league path and event id its summary is read
     * by, or null when no sheet is open.
     *
     * Held apart from [_detailSummary] because the refresh needs to know WHICH
     * game the sheet is showing even while the summary it has is stale - and
     * because clearing this is what stops the tick re-reading a summary for a
     * sheet the viewer has closed.
     */
    private var detailRequest: Pair<String, String>? = null

    private var detailJob: Job? = null

    /**
     * The detail sheet opened on [game]: the summary is fetched LAZILY, here and
     * nowhere else, and only for a live game (see [EspnSummaryRules.shouldFetch]).
     *
     * Cards never call this - a card tap raises the sheet, and the sheet is what
     * asks - so scrolling a board of twenty games costs no summary requests at
     * all. A previous summary is dropped first, so the sheet never draws the
     * last game's stats under this game's header while the new ones are on the
     * way.
     */
    fun openDetail(game: SportsGame) {
        detailRequest = game.league to game.id
        detailJob?.cancel()
        _detailSummary.value = null
        if (!EspnSummaryRules.shouldFetch(game.state)) return
        detailJob = viewModelScope.launch { loadDetailSummary() }
    }

    /** The sheet closed: nothing left for the live tick to keep fresh. */
    fun closeDetail() {
        detailRequest = null
        detailJob?.cancel()
        detailJob = null
        _detailSummary.value = null
    }

    private suspend fun loadDetailSummary() {
        val (leaguePath, eventId) = detailRequest ?: return
        _detailSummary.value = espn.gameSummary(leaguePath, eventId)
    }

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /**
     * The feeds carrying each game/event, keyed by ESPN event id, strongest
     * first.
     *
     * An id ABSENT from the map is the honest "not in your playlist" the cards
     * show; the app never substitutes a guess, because a wrong game playing on
     * a wrong channel is worse than nothing playing at all. The head of a list
     * is the one-tap feed a card has always played; the rest are the backups a
     * provider's second feed gives the viewer when the first one is dark.
     */
    private val _matches = MutableStateFlow<Map<String, List<IptvChannel>>>(emptyMap())
    val matches: StateFlow<Map<String, List<IptvChannel>>> = _matches.asStateFlow()

    /**
     * Whether [resolveMatches] has finished its current pass.
     *
     * The whole reason this exists: matching reads the paged lineup, builds a
     * guide index over five figures of channels and runs an EPG query, which on
     * a large provider is 40-60 seconds. For all of that time every card's
     * channel line used to read "Not in your playlist" - indistinguishable from
     * a genuine no-match, and how a slow hub gets read as a broken one. While
     * this is false the cards say they are still looking; only when it flips true
     * may they claim a game is not carried.
     *
     * Set false at the top of every [resolveMatches] (so a `refresh()` or the
     * live tick that re-runs matching starts looking again) and true on every way
     * out of it, including the early returns.
     */
    private val _matchingDone = MutableStateFlow(false)
    val matchingDone: StateFlow<Boolean> = _matchingDone.asStateFlow()

    private var refreshJob: Job? = null

    /**
     * The 30-second live tick, owned here rather than in the composition.
     *
     * While the tab in front of the viewer has at least one game in play, this
     * re-fetches JUST that tab's league(s) every 30 seconds; when nothing is in
     * play - a finished slate, or a league the poll was never for - there is no
     * job at all, so a hub left open on NFL does not ask ESPN a question a
     * minute about the NHL. It lives in the ViewModel's own scope, so it is tied
     * to the screen's lifetime: [onCleared] cancels it, and nothing polls once
     * the hub is gone.
     */
    private var livePollJob: Job? = null

    /**
     * Whether the hub currently has a lineup to match against. See
     * [LineupStatus]: this is what stops an unread playlist from being reported
     * as twenty games the viewer's playlist does not carry.
     */
    private val _lineupStatus = MutableStateFlow(LineupStatus.LOADING)
    val lineupStatus: StateFlow<LineupStatus> = _lineupStatus.asStateFlow()

    /** The merged lineup, read once per hub visit (a paged 10k-row DB read). */
    private var playlistChannels: List<IptvChannel>? = null

    /**
     * The lineup read itself, started as early as the ViewModel exists.
     *
     * Stage 1 of matching is the one stage that cannot be made cheaper: it is a
     * paged read of every channel in every configured playlist, and on a large
     * provider it is the bulk of the 40-60s pass. The only win available is to
     * START it earlier - in parallel with the ESPN scoreboard fetch the screen
     * would otherwise wait for before touching the playlist at all - so [init]
     * launches it and every later pass awaits this one job instead of reading
     * the same rows again. Cancelled and dropped by [retryLineup], which is what
     * makes the NO LINEUP notice's retry a real re-read - and what stops the
     * replaced read from publishing its stale result (see [lineupGeneration]).
     */
    private var channelsJob: Deferred<List<IptvChannel>>? = null

    /**
     * Which lineup read is the newest, bumped whenever one starts.
     *
     * See [readPlaylistChannels], which writes its result straight into
     * [playlistChannels], and [retryLineup], which REPLACES an in-flight read
     * rather than awaiting it. Cancelling the old read covers the ordinary case,
     * but a coroutine already past its last suspension point cannot be stopped: it
     * would then finish AFTER the fresh read and put the pre-retry lineup back in
     * the field - the exact channels the viewer hit retry to be rid of. So every
     * read carries the generation it was started in, and only the newest one is
     * allowed to publish.
     *
     * Volatile, and incremented only on the caller's thread: matching passes run
     * on the main dispatcher, while the guarded read happens on `Dispatchers.IO`,
     * so the write has to be visible across threads. The increment itself never
     * races - every bump is a call to [startLineupRead] from the main thread.
     */
    @Volatile
    private var lineupGeneration = 0

    /**
     * The guide index built for [playlistChannels], kept for the ViewModel's
     * lifetime.
     *
     * It depends only on the lineup and the guide URLs, neither of which changes
     * while the hub is open, so rebuilding it on every live tick was pure cost:
     * one query per channel per guide source, over five figures of channels,
     * repeated every 30 seconds for the tab that is in play. Held alongside the
     * cached lineup and dropped with it when the lineup is re-read.
     */
    private var guideIndexCache: Map<String, IptvChannel>? = null

    init {
        // The lineup read goes out NOW, alongside the refresh below rather than
        // behind it: the two are independent (a playlist cache and an ESPN
        // scoreboard), and serializing them added the lineup's whole read to the
        // time before the first card could be matched. See [channelsJob].
        prewarmLineup()
        refresh()
    }

    // ── The leagues the profile turned on ────────────────────────────

    /**
     * The enabled leagues, in the viewer's own order - the order the tabs are
     * drawn in and the order [refresh] fetches them in.
     */
    val leagues: List<SportsLeague>
        get() = SportsLeagues.enabled(_enabledLeagues.value, _leagueOrder.value)

    /**
     * Selects a league tab.
     *
     * Also re-aims the live tick, because the poll belongs to the tab in front
     * of the viewer: switching leagues cancels the previous league's loop and
     * starts one only when the newly selected league has something in play. A
     * league with no table forgoes the STANDINGS view, since its toggle is not
     * drawn.
     */
    fun selectLeague(path: String) {
        _selectedLeaguePath.value = path
        if (SportsLeagues.byPath(path)?.hasStandings != true) _view.value = LeagueView.GAMES
        if (_view.value == LeagueView.STANDINGS) ensureStandings()
        syncLivePoll()
    }

    /**
     * Shows the standings body for the selected league.
     *
     * Fetches on first use only, so flipping between a league's games and its
     * table does not refetch the (heavy) standings document - and a fetch
     * failure leaves the last table up rather than blanking it.
     */
    fun setView(view: LeagueView) {
        if (view == LeagueView.STANDINGS && !selectedLeagueHasStandings()) return
        _view.value = view
        if (view == LeagueView.STANDINGS) ensureStandings()
    }

    private fun selectedLeagueHasStandings(): Boolean =
        _selectedLeaguePath.value?.let(SportsLeagues::byPath)?.hasStandings == true

    /**
     * Re-reads the lineup and re-matches every card - the NO LINEUP notice's
     * action.
     *
     * A full [refresh] rather than a lineup-only path: the cache read is not
     * held when it comes back empty, so this genuinely asks the playlist again,
     * and a hub with a lineup that arrived after the first read (the guide
     * loading it in the background, a profile switch) starts matching on the
     * same pass. Deliberately NOT an automatic playlist download: the hub reads
     * the guide's cache, and a silent multi-megabyte fetch behind a screen the
     * viewer opened to read scores is not this button's business.
     */
    fun retryLineup() {
        playlistChannels = null
        guideIndexCache = null
        // The in-flight (or finished) prewarm read is dropped with the cache it
        // filled, so the refresh below genuinely asks the playlist again rather
        // than awaiting the same empty answer. It is re-started here rather than
        // left to the matching pass so the retry is already in flight by the
        // time the sections land.
        //
        // CANCELLED before it is dropped: an orphaned read keeps running on
        // Dispatchers.IO and writes its stale result into [playlistChannels] when
        // it finishes (see [readPlaylistChannels]), which on a large provider is
        // the pre-retry lineup arriving after the fresh one - the retry would
        // look like it worked while showing the channels the viewer was trying to
        // get rid of. A not-yet-stoppable completion is caught by the generation
        // guard on the write instead.
        channelsJob?.cancel()
        channelsJob = null
        prewarmLineup()
        refresh()
    }

    /**
     * Drops the selected league's held table and asks for it again - the
     * standings error state's retry. A failed fetch is never cached by the
     * repository, so the refetch is a real network attempt.
     */
    fun retryStandings() {
        val path = _selectedLeaguePath.value ?: return
        _standings.value = _standings.value - path
        _standingsFailed.value = _standingsFailed.value - path
        ensureStandings()
    }

    /**
     * Fetches the selected league's standings unless they are already held or
     * in flight. Deliberately separate from [refresh]: standings never drive
     * game-card logic, so they are fetched on their own and never on the
     * scoreboard's clock.
     */
    private fun ensureStandings() {
        val path = _selectedLeaguePath.value ?: return
        if (!selectedLeagueHasStandings()) return
        if (_standings.value.containsKey(path) || path in _standingsLoading.value) return
        _standingsLoading.value = _standingsLoading.value + path
        viewModelScope.launch {
            // The repository already holds a 6-hour cache; this is a network hop
            // only when that cache is cold.
            val groups = withContext(Dispatchers.IO) { espn.standings(path) }
            _standings.value = _standings.value + (path to groups)
            _standingsFailed.value = if (groups.isEmpty() && espn.lastStandingsFetchFailed(path)) {
                _standingsFailed.value + path
            } else {
                _standingsFailed.value - path
            }
            _standingsLoading.value = _standingsLoading.value - path
        }
    }

    /**
     * Turns a league on or off and refetches.
     *
     * Persisted per profile, so a profile with a small playlist can keep the
     * hub to the leagues it actually has channels for.
     */
    /**
     * Follows or unfollows one team, and keeps the tab in step.
     *
     * The screen hands back the set the store returned rather than re-reading
     * it: a toggle is a delta against whatever was there, and two quick presses
     * on different teams must not both start from the same snapshot.
     */
    fun setTeamFavorite(team: SportsTeam, favorite: Boolean) {
        _favoriteTeamKeys.value = AppPreferences.setSportsTeamFavorite(
            getApplication(),
            team.favoriteKey,
            favorite
        )
        // Following a team IS the reminder opt-in, and unfollowing the last team
        // is what cancels the schedule - so the round is re-armed on every
        // toggle rather than only from the panel's own switch.
        SportsNotificationWorker.syncScheduleForPrefs(getApplication())
    }

    fun setLeagueEnabled(path: String, enabled: Boolean) {
        AppPreferences.setSportsLeagueEnabled(getApplication(), path, enabled)
        _enabledLeagues.value = AppPreferences.getSportsEnabledLeagues(getApplication())
        if (enabled) _selectedLeaguePath.value = path
        refresh()
    }

    /**
     * Moves one league one step in the viewer's own order.
     *
     * [delta] is -1 for up and +1 for down; a move off either end is a no-op.
     * The whole order is materialized and written on the first move, so every
     * later move is a swap on a COMPLETE list - which is what stops a league
     * the panel never touched from jumping when the stored order was partial.
     *
     * No refetch: the sections are looked up by league path, so re-ordering is
     * a redraw rather than a round trip, and the hub must not spend a fetch on
     * a preference.
     */
    fun moveLeague(path: String, delta: Int) {
        val full = SportsLeagues.ordered(_leagueOrder.value).map { it.path }.toMutableList()
        val from = full.indexOf(path)
        if (from < 0) return
        val to = (from + delta).coerceIn(0, full.lastIndex)
        if (to == from) return
        full.removeAt(from)
        full.add(to, path)
        AppPreferences.setSportsLeagueOrder(getApplication(), full)
        _leagueOrder.value = full
    }

    /**
     * Records the viewer's own channel choice for [game], for BOTH teams.
     *
     * The write half of the correction memory: the sheet calls this when a game
     * is played on a channel other than the matched one, so the next game
     * involving either team opens there instead of on the same wrong guess.
     * Nothing on screen changes now - the memory is read at the start of the
     * next [resolveMatches] - so there is no state to republish.
     */
    fun rememberChannel(game: SportsGame, channelId: String) {
        SportsChannelMemory.rememberPick(getApplication(), game, channelId)
    }

    /**
     * Records the viewer's own channel choice for [event].
     *
     * The tournament twin of [rememberChannel], and the missing half of the fix
     * that threads the memory into the tournament matcher: reading the memory is
     * worth nothing unless something writes it, so a viewer who moved a golf
     * tournament to Golf Channel would have had the correction evaporate by the
     * next round. Keyed by the event's own stable name (see
     * [TournamentEvent.favoriteKey]); nothing on screen changes now, because the
     * memory is read at the start of the next [resolveMatches].
     */
    fun rememberTournamentChannel(event: TournamentEvent, channelId: String) {
        SportsChannelMemory.rememberTournamentPick(getApplication(), event, channelId)
    }

    /** Drops every remembered team -> channel mapping. The settings panel's row. */
    fun clearChannelMemory() {
        SportsChannelMemory.clear(getApplication())
    }

    // ── Fetching ─────────────────────────────────────────────────────

    /** Fetches every enabled league. What the screen's retry runs. */
    fun refresh() {
        refreshJob?.cancel()
        val leagues = leagues
        if (leagues.isEmpty()) {
            _sections.value = emptyList()
            _isLoading.value = false
            _matches.value = emptyMap()
            livePollJob?.cancel()
            livePollJob = null
            return
        }
        // Full-screen spinner only when there is nothing to show at all; a
        // refresh of a populated hub keeps its cards up and updates them.
        _isLoading.value = _sections.value.isEmpty()
        refreshJob = viewModelScope.launch {
            val fetched = fetchSections(leagues)
            _sections.value = fetched
            _isLoading.value = false
            resolveMatches(fetched)
            // The selection's live set may have moved with this refresh, so the
            // tick is re-aimed from the fresh sections.
            syncLivePoll()
        }
    }

    /**
     * (Re)arms the live tick for the tab the viewer is on, or clears it.
     *
     * One job, replaced every time the selection or the live set changes, so
     * there is never more than one loop in flight and a tab with nothing in play
     * is never polled at all.
     */
    private fun syncLivePoll() {
        livePollJob?.cancel()
        livePollJob = null
        val paths = SportsLivePollRules.liveLeagues(
            sections = _sections.value,
            selectedPath = _selectedLeaguePath.value,
            favoriteKeys = _favoriteTeamKeys.value,
        )
        if (paths.isEmpty()) return
        livePollJob = viewModelScope.launch {
            while (isActive) {
                delay(SportsLivePollRules.POLL_INTERVAL_MS)
                // A failed poll keeps the last good scores (the repository
                // hands back its cache) and simply tries again on the next
                // beat - never an error card from a background poll. The loop
                // ends the moment nothing in play remains, which is what stops
                // the tick without waiting for the hub to close.
                if (!refreshLeagues(paths)) break
            }
        }
    }

    /**
     * Refetches [paths] and merges them into the sections in place, leaving every
     * other league's cards untouched (no flash - the current code already
     * updates cards without clearing them). Returns whether anything in [paths]
     * is still in play; false is the caller's signal to stop polling.
     */
    private suspend fun refreshLeagues(paths: List<String>): Boolean {
        val fresh = HashMap<String, LeagueSection>()
        paths.forEach { path ->
            val league = SportsLeagues.byPath(path) ?: return@forEach
            val previous = _sections.value.firstOrNull { it.league.path == path }
            fresh[path] = fetchSection(league, previous)
        }
        if (fresh.isEmpty()) return false
        val updated = _sections.value.map { fresh[it.league.path] ?: it }
        _sections.value = updated
        resolveMatches(updated)
        refreshDetailSummary()
        return paths.any { path ->
            SportsLivePollRules.hasLive(updated.firstOrNull { it.league.path == path })
        }
    }

    /**
     * Keeps the open sheet's stats moving on the hub's OWN beat.
     *
     * No new timer: this rides [refreshLeagues], which the existing 30s live
     * tick already runs for the tab in front of the viewer, so a live game's
     * stats and its score are refreshed by the same loop. The repository's 60s
     * summary TTL is what keeps every other beat free (see `gameSummary`), and
     * this does nothing at all when no sheet is open - [detailRequest] is null
     * the rest of the time.
     */
    private fun refreshDetailSummary() {
        if (detailRequest == null) return
        detailJob?.cancel()
        detailJob = viewModelScope.launch { loadDetailSummary() }
    }

    override fun onCleared() {
        livePollJob?.cancel()
        detailJob?.cancel()
        super.onCleared()
    }

    /**
     * Fetches every enabled league AT ONCE.
     *
     * With the whole catalog on by default that is sixteen independent
     * scoreboards, and a sequential `map` put all sixteen round trips end to
     * end before the first tab could draw. The fetches share nothing, and the
     * repository answers a repeat one from its per-league cache, so running
     * them together is the whole win: the screen is bound by the slowest single
     * feed rather than by their sum.
     */
    private suspend fun fetchSections(leagues: List<SportsLeague>): List<LeagueSection> =
        withContext(Dispatchers.IO) {
            leagues.map { league ->
                async {
                    val previous = _sections.value.firstOrNull { it.league.path == league.path }
                    fetchSection(league, previous)
                }
            }.awaitAll()
        }

    private suspend fun fetchSection(
        league: SportsLeague,
        previous: LeagueSection?
    ): LeagueSection = when (league.kind) {
        SportsKind.HEAD_TO_HEAD -> {
            val games = espn.scoreboard(league.path)
            LeagueSection(
                league = league,
                games = games,
                failed = games.isEmpty() && espn.lastFetchFailed(league.path),
            )
        }
        SportsKind.TOURNAMENT -> {
            val events = espn.tournamentEvents(league.path)
            LeagueSection(
                league = league,
                tournaments = events,
                failed = events.isEmpty() && espn.lastFetchFailed(league.path),
            )
        }
    }.let { section ->
        // Keep the previous cards on screen while a refresh is in flight rather
        // than blanking a populated tab for the round trip.
        if (section.games.isEmpty() && section.tournaments.isEmpty() && section.failed) {
            section.copy(
                games = previous?.games.orEmpty(),
                tournaments = previous?.tournaments.orEmpty(),
            )
        } else {
            section
        }
    }

    // ── The bridge to the playlist ───────────────────────────────────

    /**
     * Resolves the channel for every card in [sections].
     *
     * Failure of any part degrades to "no match found" - the cards say the game
     * is not in the playlist - rather than to an error: a hub with scores but
     * no playable channel is still a useful hub.
     */
    private suspend fun resolveMatches(sections: List<LeagueSection>) {
        // Every pass starts by saying it is looking: the cards read "Finding
        // channel…" until the end of this function, which is what stops the
        // 40-60s match from looking exactly like a slate the playlist does not
        // carry. Set false here so a `refresh()` - or the live tick - that
        // re-runs matching starts the loading state over.
        _matchingDone.value = false
        val games = sections.flatMap { it.games }
        val events = sections.flatMap { it.tournaments }
        if (games.isEmpty() && events.isEmpty()) {
            _matches.value = emptyMap()
            _matchingDone.value = true
            return
        }
        val channelsStarted = SystemClock.elapsedRealtime()
        val channels = channelsOrEmpty()
        val channelsMs = SystemClock.elapsedRealtime() - channelsStarted
        // One measurement, two sinks: the SPORTS PERF line below and this
        // PerfTrace sample are the same number, so the report and the live log
        // can never disagree about which stage was slow.
        PerfTrace.record("sports.match.channels", channelsMs)
        Log.d(TAG, "SPORTS PERF channels=${channels.size} took=${channelsMs}ms")
        if (channels.isEmpty()) {
            // The same line shape as the one below, so a logcat grep for
            // `SPORTS DIAG` answers "is the lineup even loaded?" first: a zero
            // here means the hub matched against nothing at all, and every
            // card's "not in your playlist" is about the cache, not the game.
            Log.w(TAG, "SPORTS DIAG channels=0 guideIndex=0 programs=0")
            _lineupStatus.value = LineupStatus.MISSING
            _matches.value = emptyMap()
            _matchingDone.value = true
            return
        }
        _lineupStatus.value = LineupStatus.READY
        // Off the main thread: building the guide index is one small object per
        // channel per guide source, and a provider playlist runs to five
        // figures - enough that doing it in the composition's own dispatcher
        // would be a visible frame hit on the leagues a viewer actually has.
        val matches = withContext(Dispatchers.Default) {
            val guideStarted = SystemClock.elapsedRealtime()
            val guideIndex = cachedGuideIndex(channels)
            val guideMs = SystemClock.elapsedRealtime() - guideStarted
            // ~0 on every pass after the first, which is the cached index doing
            // its job: the guide index is a pure function of the lineup and the
            // guide URLs, both fixed while the hub is open.
            PerfTrace.record("sports.match.guide_index", guideMs)
            Log.d(TAG, "SPORTS PERF guideIndex=${guideIndex.size} took=${guideMs}ms")
            val epgStarted = SystemClock.elapsedRealtime()
            // Games and tournaments draw from ONE ceiling over the one index
            // (see [EpgLookups]); the tournament rows are the fix for a golf
            // card reading "not in your playlist" while Golf Channel carries it
            // - the index was never asked about a tournament at all (see
            // [epgTournamentCandidates]).
            val budget = EpgLookups(MAX_EPG_LOOKUPS)
            val programs = if (guideIndex.isEmpty()) {
                emptyList()
            } else {
                runCatchingCancellable {
                    epgCandidates(games, guideIndex, budget) +
                        epgTournamentCandidates(events, guideIndex, budget)
                }.getOrDefault(emptyList())
            }
            val epgMs = SystemClock.elapsedRealtime() - epgStarted
            PerfTrace.record("sports.match.epg_query", epgMs)
            Log.d(TAG, "SPORTS PERF programs=${programs.size} took=${epgMs}ms")
            // One greppable line, and the three counts say which stage came back
            // empty without a debugger: channels=N guideIndex=0 is a lineup with
            // no EPG URLs, channels=N guideIndex=M programs=0 is a guide with
            // nothing to say about today's games (which leaves tiers 2 and 3 to
            // carry them), and channels=0 is the lineup the line above reports.
            Log.d(
                TAG,
                "SPORTS DIAG channels=${channels.size} guideIndex=${guideIndex.size} " +
                    "programs=${programs.size}"
            )
            val matchStarted = SystemClock.elapsedRealtime()
            // The viewer's own corrections, resolved once for the whole pass:
            // the memory is keyed by team, so every game's two teams are read
            // against the same channel list. A remembered channel beats all
            // three tiers; see [SportsChannelMemory].
            //
            // TEMP (remove with the SPORTS PERF game= logs): the accumulating
            // recall total lets each card's log report its own recall cost,
            // which is the difference the tiers-vs-IO question turns on.
            var recallNanos = 0L
            val remembered: (String) -> IptvChannel? = { key ->
                val recallStartedAt = System.nanoTime()
                val channel = SportsChannelMemory.recall(getApplication(), key, channels)
                recallNanos += System.nanoTime() - recallStartedAt
                channel
            }
            // Built ONCE for the whole pass, not once per game or per program:
            // the channel list is fixed under the pass, and a team's name forms
            // do not change between programs. Rebuilding both per program is what
            // made this loop take 82s on a full slate (see [TeamVariants]).
            val channelById = channels.associateBy { it.id }
            // And the playlist's own NAMES, normalized once for the same reason
            // (see [SportsChannelMatcher.ChannelIndex]). This is the one the
            // field log was measuring: the `net=` stage of every per-card line is
            // the broadcast/RSN tier, which is a scan of the in-memory channel
            // list and not a request - and it took 1-3s a card because every card
            // re-normalized all five figures of channel names before comparing
            // any of them. Hoisted, the tier only compares the strings it is
            // handed, and a network is scanned for once a pass however many cards
            // name it.
            val channelIndex = SportsChannelMatcher.ChannelIndex.of(channels)
            val gameVariants: Map<String, TeamVariants> = games.associate { game ->
                game.id to TeamVariants(
                    away = SportsChannelMatcher.strongVariants(game.away),
                    home = SportsChannelMatcher.strongVariants(game.home),
                )
            }
            val found = HashMap<String, List<IptvChannel>>()
            games.forEach { game ->
                ensureActive()
                // A superseded pass - the live tick or a profile switch cancelled
                // it - dies here rather than running out its whole 82 seconds in
                // the background.

                // Verbose, with the feed's own broadcast names beside the id:
                // a card that matched nothing can then be read against the very
                // strings tiers 2 and 3 match on.
                Log.v(TAG, "SPORTS DIAG game=${game.id} broadcasts=${game.broadcastNames}")
                var epgNanos = 0L
                var netNanos = 0L
                val recallBefore = recallNanos
                val matchStartedAt = System.nanoTime()
                val matchedFeeds = SportsChannelMatcher.matches(game, channels, programs, remembered, channelById, gameVariants.getValue(game.id), channelIndex) { tier, nanos ->
                    when (tier) {
                        "epg" -> epgNanos += nanos
                        "broadcast" -> netNanos += nanos
                    }
                }
                // TEMP per-game split of the match loop (remove once the slow
                // stage is found): recall is the per-team SharedPreferences
                // decode inside SportsChannelMemory.recall, epg is the program
                // scan, and net is the broadcast/RSN tier - a scan of the
                // IN-MEMORY channel list, with no I/O anywhere in [matches]; it
                // read as the whole bottleneck once, and it was, but as name
                // normalization rather than as a request. Log.w, because release
                // builds strip Log.d (see proguard-rules.pro).
                Log.w(
                    TAG,
                    "SPORTS PERF game=${game.id} took=${(System.nanoTime() - matchStartedAt) / 1_000_000}ms " +
                        "recall=${(recallNanos - recallBefore) / 1_000_000}ms " +
                        "epg=${epgNanos / 1_000_000}ms net=${netNanos / 1_000_000}ms"
                )
                matchedFeeds.takeIf { it.isNotEmpty() }
                    ?.let { feeds ->
                        found[game.id] = feeds
                        // Progressive publish: the card flips from "Finding
                        // channel…" to the channel name the moment ITS match
                        // lands, instead of every card waiting out the whole
                        // slate. Position is untouched - a card's place comes
                        // from the section it was built in and nothing here can
                        // reorder anything; only its own channel line changes.
                        //
                        // An entry is ADDED on top of whatever is published,
                        // never cleared first: on the 30-second live tick that
                        // leaves a settled card showing its channel while the
                        // rest of the pass re-matches, instead of blinking every
                        // card back to "Finding channel…". The publish below is
                        // what reconciles the map exactly, including dropping a
                        // game whose match is gone.
                        _matches.update { it + (game.id to feeds) }
                    }
            }
            events.forEach { event ->
                ensureActive()
                Log.v(TAG, "SPORTS DIAG event=${event.id} broadcasts=${event.broadcastNames}")
                // The tournament path takes the SAME correction memory, keyed by
                // the event's own stable name (see [TournamentEvent.favoriteKey]):
                // a viewer who moved a golf tournament to Golf Channel gets that
                // back on the next pass instead of the matcher's guess.
                var epgNanos = 0L
                var netNanos = 0L
                val recallBefore = recallNanos
                val matchStartedAt = System.nanoTime()
                val matchedFeeds = SportsChannelMatcher.matches(event, channels, programs, remembered, channelById, channelIndex) { tier, nanos ->
                    when (tier) {
                        "epg" -> epgNanos += nanos
                        "broadcast" -> netNanos += nanos
                    }
                }
                Log.w(
                    TAG,
                    "SPORTS PERF event=${event.id} took=${(System.nanoTime() - matchStartedAt) / 1_000_000}ms " +
                        "recall=${(recallNanos - recallBefore) / 1_000_000}ms " +
                        "epg=${epgNanos / 1_000_000}ms net=${netNanos / 1_000_000}ms"
                )
                matchedFeeds.takeIf { it.isNotEmpty() }
                    ?.let { feeds ->
                        found[event.id] = feeds
                        _matches.update { it + (event.id to feeds) }
                    }
            }
            // Per-stage timing, at debug, in the order the stages run: which of
            // the four is the slow one is a grep rather than a guess. The guide
            // number is ~0 on every pass after the first, which is the cached
            // index doing its job. `SPORTS PERF` is the live view; the same
            // numbers are also recorded into PerfTrace, which is what the
            // one-tap diagnostics report carries (see Diagnostics.sportsLine) -
            // two sinks, one measurement each (taken just above).
            val matchMs = SystemClock.elapsedRealtime() - matchStarted
            PerfTrace.record("sports.match.match_loop", matchMs)
            Log.d(TAG, "SPORTS PERF matched=${found.size} took=${matchMs}ms")
            Log.d(
                TAG,
                "SPORTS MATCHES cards=${games.size + events.size} matched=${found.size} " +
                    "programs=${programs.size} feeds=${found.values.sumOf { it.size }}"
            )
            // Counts, not durations: [PerfTrace.recordCount] keeps a card count
            // in the thousands from being forwarded to Sentry as a multi-second
            // sample, while still being what the report line reads them as.
            PerfTrace.recordCount("sports.match.cards", (games.size + events.size).toLong())
            PerfTrace.recordCount("sports.match.matched", found.size.toLong())
            PerfTrace.recordCount("sports.match.programs", programs.size.toLong())
            found
        }
        _matches.value = matches
        _matchingDone.value = true
    }

    /**
     * The guide index, built once per lineup and reused for the ViewModel's
     * life.
     *
     * See [guideIndexCache]: the index is a pure function of the lineup and the
     * guide URLs, both fixed while the hub is open, so the live tick (every 30
     * seconds, same lineup) reuses the first build instead of walking five
     * figures of channels again. An empty build is deliberately not cached - a
     * lineup with no guide URL yet, or a read that failed, is worth retrying on
     * the next pass - and [retryLineup] drops the cache with the lineup.
     */
    private suspend fun cachedGuideIndex(channels: List<IptvChannel>): Map<String, IptvChannel> {
        guideIndexCache?.let { return it }
        val built = runCatchingCancellable { guideChannelIndex(channels) }
            .getOrDefault(emptyMap())
        if (built.isNotEmpty()) guideIndexCache = built
        return built
    }

    /**
     * The profile's lineup: every configured playlist, from the CACHE.
     *
     * From the cache, not the network, for the same reason the guide's own
     * restore is: the hub is opened on a whim from Live TV, and a screen that
     * spends a minute downloading an M3U before it can tell you the Yankees are
     * on is worse than one that says "not in your playlist" until the next
     * guide load refreshes the cache.
     *
     * Read once per ViewModel and kept: on a large provider playlist this is a
     * paged read of tens of thousands of rows.
     */
    private suspend fun channelsOrEmpty(): List<IptvChannel> {
        playlistChannels?.let { return it }
        // The read caches its own result (see [readPlaylistChannels]) - which is
        // what makes the prewarm share it with the matching pass instead of
        // reading the playlist twice.
        return (channelsJob ?: startLineupRead()).await()
    }

    /** Starts the lineup read if none is in flight, and returns that read. */
    private fun startLineupRead(): Deferred<List<IptvChannel>> {
        // Stamped HERE, on the caller's thread rather than inside the coroutine:
        // the order two reads are STARTED in is what the write guard orders them
        // by, and an `async` body does not run until its dispatcher does.
        val generation = ++lineupGeneration
        return viewModelScope.async(Dispatchers.IO) {
            // A failed read degrades to an empty lineup, exactly as the
            // synchronous version did: the hub then says it has no lineup to
            // match against (see [LineupStatus.MISSING]) rather than failing the
            // pass. It is NOT cached, so the next pass reads again - and a read
            // that was CANCELLED (retryLineup) is not a failure at all:
            // [runCatchingCancellable] rethrows the cancellation, so it never
            // reaches the degrade path.
            runCatchingCancellable { readPlaylistChannels(generation) }
                .getOrDefault(emptyList())
        }.also { channelsJob = it }
    }

    /**
     * Starts the lineup read early. See [channelsJob]; a no-op when one is
     * already in flight or the playlist is already in hand.
     */
    private fun prewarmLineup() {
        if (channelsJob != null || playlistChannels != null) return
        startLineupRead()
    }

    /**
     * The paged lineup read: the cache, not the network, and merged in the order
     * the playlists are configured. See [channelsOrEmpty] for why it comes from
     * the cache, and the guide's own loading for why extra playlists get their
     * channel ids namespaced.
     */
    private suspend fun readPlaylistChannels(generation: Int): List<IptvChannel> {
        val entries = playlistUrlsOf(
            playlistUrl = prefs.getString(KEY_PLAYLIST_URL, ""),
            extraPlaylistUrls = prefs.getString(KEY_EXTRA_PLAYLIST_URLS, "")
        )
        if (entries.isEmpty()) return emptyList()

        val loaded = withContext(Dispatchers.IO) {
            entries.mapNotNull { entry ->
                val parts = entry.split('|', limit = 2)
                val url = parts[0].trim()
                val name = parts.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
                if (url.isBlank()) return@mapNotNull null
                val playlist = runCatchingCancellable {
                    iptv.loadCachedPlaylist(url, name)
                }.getOrNull() ?: return@mapNotNull null
                url to playlist
            }
        }
        if (loaded.isEmpty()) return emptyList()

        // Extra playlists get their channel ids namespaced exactly as the guide
        // merges them (see IptvViewModel), so two providers both emitting
        // "channel_1" cannot collide - and a channel the hub launches carries
        // the same id the guide would have launched it with.
        val merged = ArrayList<IptvChannel>()
        loaded.forEachIndexed { index, (url, playlist) ->
            if (index == 0) {
                merged += playlist.channels
            } else {
                val ns = (url.hashCode().toUInt() and 0xFFFFFFu).toString(16)
                merged += playlist.channels.map { channel ->
                    if (channel.id.isBlank()) channel else channel.copy(id = "x$ns:${channel.id}")
                }
            }
        }
        val distinct = merged.distinctBy { it.id }
        // Only the NEWEST read may publish. [retryLineup] cancels the read it
        // replaces, but a coroutine already past its last suspension point still
        // runs to this line: without the generation check it would write the
        // pre-retry lineup AFTER the fresh read had written its own, which is the
        // clobber the retry exists to avoid (see [lineupGeneration]).
        if (generation == lineupGeneration && distinct.isNotEmpty()) {
            playlistChannels = distinct
        }
        Log.d(TAG, "SPORTS LINEUP channels=${distinct.size} playlists=${loaded.size}")
        return distinct
    }

    private suspend fun guideChannelIndex(
        channels: List<IptvChannel>
    ): Map<String, IptvChannel> {
        val epgUrls = allEpgUrls()
        if (epgUrls.isEmpty()) return emptyMap()

        // One query per channel per guide source, with the SAME candidates the
        // guide screen's own matcher uses (tvg-id then provider id; tvg-name,
        // display name, name) - so a channel resolves to the same guide channel
        // on this screen as it does on the guide.
        val queries = ArrayList<GuideMatchQuery>(channels.size)
        channels.forEach { channel ->
            if (channel.tvgId.isNullOrBlank() &&
                channel.providerChannelId.isNullOrBlank() &&
                channel.tvgName.isNullOrBlank() &&
                channel.name.isBlank()
            ) {
                return@forEach
            }
            epgUrls.forEach { source ->
                queries += GuideMatchQuery(
                    key = channel.id,
                    epgUrl = source,
                    idCandidates = listOf(channel.tvgId, channel.providerChannelId),
                    nameCandidates = listOf(channel.tvgName, channel.displayName, channel.name),
                )
            }
        }
        if (queries.isEmpty()) return emptyMap()

        val resolved = iptv.resolveGuideChannelIds(queries)
        if (resolved.isEmpty()) return emptyMap()

        val byChannelId = channels.associateBy { it.id }
        val byGuideKey = LinkedHashMap<String, IptvChannel>(resolved.size)
        resolved.forEach { (channelId, guideId) ->
            val channel = byChannelId[channelId] ?: return@forEach
            // Prefer a real channel over an earlier empty placeholder for the
            // same guide row: providers do list a channel name twice (once as
            // the HD feed's own entry).
            byGuideKey.putIfAbsent(epgProgramChannelKey(guideId), channel)
        }
        Log.d(TAG, "SPORTS GUIDE INDEX channels=${byGuideKey.size}")
        return byGuideKey
    }

    /**
     * The EPG rows worth handing the matcher, for EVERY game that can still be
     * watched.
     *
     * The whole reason this is a search rather than a window read: a playlist
     * can carry ten thousand channels, and reading every program of every one
     * of them to find twenty games is minutes of database work. The indexed
     * lookup below asks for each game's two teams TOGETHER, so the index
     * itself excludes every title that does not name both - which is precisely
     * tier 1's rule - and the rows come back already narrowed to the ones that
     * could be the game.
     *
     * Every game, not the first league's worth of them. This pass used to stop
     * after [MAX_EPG_LOOKUPS]'s predecessor (24) games, taken in league order
     * from every enabled league's slate - and because the hub matches all of
     * them at once (see [resolveMatches]), a viewer with all the sports on got
     * EPG candidates for the first league or two and NOTHING for every league
     * behind it. Those games could only ever match on the feed's own broadcast
     * names - which for a regional or streaming game name no channel in an
     * IPTV lineup - so they read "Not in your playlist" while the guide was
     * carrying the game on half a dozen channels. That is the reported
     * symptom, and the fix is coverage: a lookup per matchup, for the whole
     * slate, with the count bounded by the slate rather than by an arbitrary
     * constant.
     *
     * Two games between the same teams ask the same question, so a matchup is
     * looked up once (a doubleheader, a home-and-home).
     *
     * A guide that titles a game by ABBREVIATION ("MIN @ TB", "LAL vs BOS") -
     * which plenty do, especially for the leagues a provider gives a bare
     * "NHL Hockey" channel - names neither team's full name, so the nickname
     * lookup cannot see the row at all. When it comes back empty the same
     * matchup is asked again on the two abbreviations, and the matcher's own
     * name check (which already accepts an abbreviation as a strong name) does
     * the rest.
     *
     * Final games are skipped: the index only reaches programs that have not
     * ended yet, and a game that is over is not on air to be matched.
     */
    private suspend fun epgCandidates(
        games: List<SportsGame>,
        guideIndex: Map<String, IptvChannel>,
        budget: EpgLookups,
    ): List<MatcherProgram> {
        val out = LinkedHashMap<String, MatcherProgram>()
        val asked = HashSet<String>()
        games.asSequence()
            .filter { it.state != GameState.FINAL }
            .forEach { game ->
                val terms = listOf(game.away, game.home).mapNotNull(::titleSearchTerm)
                if (terms.size < 2) return@forEach
                if (!asked.add(terms.sorted().joinToString("|"))) return@forEach
                if (!budget.take()) return@forEach
                val rows = searchTerms(terms)
                    .ifEmpty { searchTerms(abbreviationSearchTerms(game)) }
                rows.forEach { row ->
                    val channel = guideIndex[row.channelId] ?: return@forEach
                    out.putIfAbsent(
                        "${channel.id}|${row.startUtcMillis}|${row.title}",
                        MatcherProgram(
                            channelId = channel.id,
                            title = row.title,
                            startMs = row.startUtcMillis,
                            endMs = row.endUtcMillis,
                            // The guide's synopsis travels with the row so tier
                            // 1 can fall back to it when the title names no
                            // team; see [SportsChannelMatcher.epgHits].
                            description = row.description,
                        )
                    )
                }
            }
        return out.values.toList()
    }

    /**
     * The EPG rows worth handing the matcher, for every tournament on the slate.
     *
     * The tournament twin of [epgCandidates], and the fix for a golf card
     * reading "not in your playlist" with Golf Channel carrying it: the index
     * was only ever asked about a GAME's two team names, so a tournament's rows
     * were never in the candidate list at all - `epgNameHits` searched a pool
     * built for other games and, when ESPN's payload also carried no broadcast
     * names, had nothing to answer with. A tournament has no teams, so its OWN
     * name is the lookup: "The Players Championship" asks the index for
     * ["the", "players", "championship"], and the matcher still requires the
     * whole name in the row before a card may play it, so a broad term cannot
     * invent a match.
     *
     * Final tournaments are skipped for the same reason a final game is, and the
     * whole pass shares ONE [MAX_EPG_LOOKUPS] ceiling with the games above (see
     * [EpgLookups]) - it is one index, so it is one budget.
     */
    private suspend fun epgTournamentCandidates(
        events: List<TournamentEvent>,
        guideIndex: Map<String, IptvChannel>,
        budget: EpgLookups,
    ): List<MatcherProgram> {
        val out = LinkedHashMap<String, MatcherProgram>()
        val asked = HashSet<String>()
        events.asSequence()
            .filter { it.state != GameState.FINAL }
            .forEach { event ->
                val terms = SportsChannelMatcher.words(event.name)
                if (terms.isEmpty()) return@forEach
                if (!asked.add(terms.joinToString("|"))) return@forEach
                if (!budget.take()) return@forEach
                val rows = runCatchingCancellable {
                    iptv.searchProgramsByTitleTerms(terms, EPG_SEARCH_LIMIT)
                }.getOrDefault(emptyList())
                // SPORTS DIAG: the tournament's own terms and what the guide
                // answered with, on one greppable line. `hits` counts the rows
                // THIS event's lookup returned, which is the question - a
                // tournament whose card says "not in your playlist" is either a
                // lookup that found nothing (terms wrong: ESPN's name for the
                // event is not the guide's title) or one that found rows the
                // matcher then refused (its whole name must appear in the row),
                // and the two need opposite fixes. Log.w, not Log.d, because
                // release builds strip Log.d (see proguard-rules.pro) and this
                // line exists to be read off a viewer's own capture.
                Log.w(
                    TAG,
                    "SPORTS DIAG tournament=${event.id} name=\"${event.name}\" " +
                        "terms=${terms} hits=${rows.size}"
                )
                rows.forEach { row ->
                    val channel = guideIndex[row.channelId] ?: return@forEach
                    out.putIfAbsent(
                        "${channel.id}|${row.startUtcMillis}|${row.title}",
                        MatcherProgram(
                            channelId = channel.id,
                            title = row.title,
                            startMs = row.startUtcMillis,
                            endMs = row.endUtcMillis,
                            description = row.description,
                        )
                    )
                }
            }
        return out.values.toList()
    }

    /**
     * One indexed title/description lookup for a pair of teams.
     *
     * Never throws and never fails the pass: a lookup is an optimization over
     * the tiers below it, so a guide that is unreadable or an index that is not
     * there degrades to "no EPG signal for these two teams" and the cards fall
     * through to the network tiers - exactly as they did before tier 1 existed.
     */
    private suspend fun searchTerms(terms: List<String>): List<EpgProgramRow> {
        if (terms.size < 2) return emptyList()
        return runCatchingCancellable {
            iptv.searchProgramsByTitleTerms(terms, EPG_SEARCH_LIMIT)
        }.getOrDefault(emptyList())
    }

    /**
     * The two teams' abbreviations, for the guides that title a game by them.
     *
     * Both are required, and the pair is what makes a two-letter term safe: the
     * search asks for the two TOGETHER, so "tb" alone never pulls the guide's
     * back catalog into the candidate pool - and the matcher still has to find
     * both sides in the row before a card may play it.
     */
    private fun abbreviationSearchTerms(game: SportsGame): List<String> =
        listOf(game.away, game.home).mapNotNull { team ->
            team.abbreviation.trim().lowercase()
                .takeIf { it.length >= MIN_ABBREV_SEARCH_TERM_LENGTH }
        }

    /**
     * The one word to search a team by.
     *
     * The feed's OWN short name first, because that is the name a guide titles a
     * game with: "Washington" for the Washington Huskies (the full name's last
     * word is "Huskies"), "Leeds" for Leeds United ("United"), "White Sox" for
     * the Chicago White Sox. Reading only the last word of the full name left
     * whole sports - college football and basketball, every soccer league - with
     * NO EPG candidates at all, so those games could only ever match on the
     * feed's own broadcast name and read "Not in your playlist" over a guide
     * that was carrying them on half a dozen channels.
     *
     * The LAST token of the short name, not the whole string: the lookup is an
     * AND across the two teams, so every extra word narrows it, and the last
     * token is the distinctive one ("White Sox" -> "sox", "Iowa State" ->
     * "state", "Aston Villa" -> "villa"). A broad term costs nothing but a
     * wider candidate set - the matcher still has to find BOTH sides in the row
     * before a card may play it.
     *
     * Falls back to the last word of the full name - which is what every US
     * league's short name is anyway - and then to the abbreviation, for a feed
     * (or an athlete) that carries no short name at all. Null when there is
     * nothing worth searching for.
     */
    private fun titleSearchTerm(team: SportsTeam): String? {
        val shortToken = team.shortName
            .orEmpty()
            .trim()
            .split(' ')
            .lastOrNull { it.length >= MIN_SEARCH_TERM_LENGTH }
        if (shortToken != null) return shortToken

        val lastWord = team.displayName
            .trim()
            .split(' ')
            .lastOrNull { it.isNotBlank() }
            .orEmpty()
            .trim()
        return when {
            lastWord.length >= MIN_SEARCH_TERM_LENGTH -> lastWord
            team.abbreviation.length >= MIN_SEARCH_TERM_LENGTH -> team.abbreviation
            else -> null
        }
    }

    private fun allEpgUrls(): List<String> =
        buildList {
            listOf(KEY_EPG_URL, KEY_EXTRA_EPG_URLS).forEach { key ->
                prefs.getString(key, "").orEmpty()
                    .split('\n', ';')
                    .mapNotNull { it.trim().takeIf(String::isNotEmpty) }
                    .forEach(::add)
            }
        }.distinct()

    /**
     * The shared ceiling on indexed EPG lookups for one matching pass.
     *
     * Games and tournaments draw from the SAME counter: it is one index, so it is
     * one budget, and a tournament question is the same kind of work as a game's.
     * A safety net rather than an allowance (see [MAX_EPG_LOOKUPS]) - reaching it
     * means a slate bigger than any single day's.
     */
    private class EpgLookups(private val max: Int) {
        private var used = 0

        /** Reserves a lookup when one is left; false means the ceiling is reached. */
        fun take(): Boolean {
            if (used >= max) return false
            used++
            return true
        }
    }

    private companion object {
        const val TAG = "SPORTS_HUB"

        /** The IPTV store the playlist and guide URLs live in. */
        const val GuideFilesPrefs = "iptv_prefs"

        const val KEY_PLAYLIST_URL = "playlist_url"
        const val KEY_EXTRA_PLAYLIST_URLS = "extra_playlist_urls"
        const val KEY_EPG_URL = "epg_url"
        const val KEY_EXTRA_EPG_URLS = "extra_epg_urls"

        /** Rows per matchup from the indexed title search. */
        const val EPG_SEARCH_LIMIT = 60

        /**
         * Ceiling on the indexed EPG lookups one pass makes.
         *
         * Sized for a full slate with every league enabled (a Saturday of
         * college football plus the pros and the soccer), because a cap below
         * the slate is how whole leagues used to be left with no EPG signal at
         * all - see [epgCandidates]. Each lookup is an indexed FTS query the
         * size of a handful of rows, and a matchup is asked once however many
         * cards it covers, so this is a safety net rather than a budget:
         * reaching it means a slate bigger than any single day's.
         */
        const val MAX_EPG_LOOKUPS = 200

        /** A term shorter than this matches too much of the guide to be useful. */
        const val MIN_SEARCH_TERM_LENGTH = 3

        /**
         * The floor for an ABBREVIATION used as a search term.
         *
         * Two, not [MIN_SEARCH_TERM_LENGTH]: "TB" and "LA" are exactly the
         * forms a guide uses in a title like "MIN @ TB", and they are only
         * ever searched as a two-team pair - the matcher still has to find
         * both of them in the row.
         */
        const val MIN_ABBREV_SEARCH_TERM_LENGTH = 2
    }
}
