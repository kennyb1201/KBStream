package com.kennyb1201.kbstream.ui.sports

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.iptv.GuideMatchQuery
import com.kennyb1201.kbstream.data.iptv.IptvChannel
import com.kennyb1201.kbstream.data.iptv.IptvRepository
import com.kennyb1201.kbstream.data.iptv.epgProgramChannelKey
import com.kennyb1201.kbstream.data.iptv.playlistUrlsOf
import com.kennyb1201.kbstream.data.runCatchingCancellable
import com.kennyb1201.kbstream.data.settings.AppPreferences
import com.kennyb1201.kbstream.data.sports.EspnSportsRepository
import com.kennyb1201.kbstream.data.sports.GameState
import com.kennyb1201.kbstream.data.sports.MatcherProgram
import com.kennyb1201.kbstream.data.sports.SportsChannelMatcher
import com.kennyb1201.kbstream.data.sports.SportsGame
import com.kennyb1201.kbstream.data.sports.SportsKind
import com.kennyb1201.kbstream.data.sports.SportsLeague
import com.kennyb1201.kbstream.data.sports.SportsLeagues
import com.kennyb1201.kbstream.data.sports.SportsTeam
import com.kennyb1201.kbstream.data.sports.StandingGroup
import com.kennyb1201.kbstream.data.sports.TournamentEvent
import com.kennyb1201.kbstream.data.sports.involvesFavorite
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import com.kennyb1201.kbstream.work.SportsNotificationWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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
        refresh()
    }

    // ── The leagues the profile turned on ────────────────────────────

    /**
     * The enabled leagues, in catalog order - the order the tabs are drawn in.
     */
    val leagues: List<SportsLeague>
        get() = SportsLeagues.enabled(_enabledLeagues.value)

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
        return paths.any { path ->
            SportsLivePollRules.hasLive(updated.firstOrNull { it.league.path == path })
        }
    }

    override fun onCleared() {
        livePollJob?.cancel()
        super.onCleared()
    }

    private suspend fun fetchSections(leagues: List<SportsLeague>): List<LeagueSection> =
        withContext(Dispatchers.IO) {
            leagues.map { league ->
                val previous = _sections.value.firstOrNull { it.league.path == league.path }
                fetchSection(league, previous)
            }
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
        val channelsStarted = System.currentTimeMillis()
        val channels = channelsOrEmpty()
        val channelsMs = System.currentTimeMillis() - channelsStarted
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
            val guideStarted = System.currentTimeMillis()
            val guideIndex = cachedGuideIndex(channels)
            val guideMs = System.currentTimeMillis() - guideStarted
            val epgStarted = System.currentTimeMillis()
            val programs = if (guideIndex.isEmpty()) {
                emptyList()
            } else {
                runCatchingCancellable { epgCandidates(games, guideIndex) }.getOrDefault(emptyList())
            }
            val epgMs = System.currentTimeMillis() - epgStarted
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
            val matchStarted = System.currentTimeMillis()
            val found = HashMap<String, List<IptvChannel>>()
            games.forEach { game ->
                // Verbose, with the feed's own broadcast names beside the id:
                // a card that matched nothing can then be read against the very
                // strings tiers 2 and 3 match on.
                Log.v(TAG, "SPORTS DIAG game=${game.id} broadcasts=${game.broadcastNames}")
                SportsChannelMatcher.matches(game, channels, programs)
                    .takeIf { it.isNotEmpty() }
                    ?.let { found[game.id] = it }
            }
            events.forEach { event ->
                Log.v(TAG, "SPORTS DIAG event=${event.id} broadcasts=${event.broadcastNames}")
                SportsChannelMatcher.matches(event, channels, programs)
                    .takeIf { it.isNotEmpty() }
                    ?.let { found[event.id] = it }
            }
            // Per-stage timing, at debug, in the order the stages run: which of
            // the four is the slow one is a grep rather than a guess. The guide
            // number is ~0 on every pass after the first, which is the cached
            // index doing its job.
            Log.d(
                TAG,
                "SPORTS TIMING channels=${channelsMs}ms guide=${guideMs}ms epg=${epgMs}ms " +
                    "match=${System.currentTimeMillis() - matchStarted}ms"
            )
            Log.d(
                TAG,
                "SPORTS MATCHES cards=${games.size + events.size} matched=${found.size} " +
                    "programs=${programs.size} feeds=${found.values.sumOf { it.size }}"
            )
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
        playlistChannels = distinct
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
     * The EPG rows worth handing the matcher, for the games that can still be
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
     * Final games are skipped: the index only reaches programs that have not
     * ended yet, and a game that is over is not on air to be matched.
     */
    private suspend fun epgCandidates(
        games: List<SportsGame>,
        guideIndex: Map<String, IptvChannel>
    ): List<MatcherProgram> {
        val out = LinkedHashMap<String, MatcherProgram>()
        games.asSequence()
            .filter { it.state != GameState.FINAL }
            .take(MAX_EPG_GAMES)
            .forEach { game ->
                val terms = listOf(game.away, game.home).mapNotNull(::titleSearchTerm)
                if (terms.size < 2) return@forEach
                val rows = runCatchingCancellable {
                    iptv.searchProgramsByTitleTerms(terms, EPG_SEARCH_LIMIT)
                }.getOrDefault(emptyList())
                rows.forEach { row ->
                    val channel = guideIndex[row.channelId] ?: return@forEach
                    out.putIfAbsent(
                        "${channel.id}|${row.startUtcMillis}|${row.title}",
                        MatcherProgram(
                            channelId = channel.id,
                            title = row.title,
                            startMs = row.startUtcMillis,
                            endMs = row.endUtcMillis,
                        )
                    )
                }
            }
        return out.values.toList()
    }

    /**
     * The one word to search a team by.
     *
     * The nickname ("Yankees") where the feed gives a full name, because it is
     * what broadcasters actually put in a program title, and the abbreviation
     * otherwise (a tennis player's name has no nickname - the feed's display
     * name is already the shortest thing that identifies them). Null when there
     * is nothing worth searching for.
     */
    private fun titleSearchTerm(team: SportsTeam): String? {
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

    private companion object {
        const val TAG = "SPORTS_HUB"

        /** The IPTV store the playlist and guide URLs live in. */
        const val GuideFilesPrefs = "iptv_prefs"

        const val KEY_PLAYLIST_URL = "playlist_url"
        const val KEY_EXTRA_PLAYLIST_URLS = "extra_playlist_urls"
        const val KEY_EPG_URL = "epg_url"
        const val KEY_EXTRA_EPG_URLS = "extra_epg_urls"

        /** Rows per game from the indexed title search. */
        const val EPG_SEARCH_LIMIT = 40

        /**
         * How many games one refresh spends indexed lookups on. The hub shows a
         * single league at a time and a full schedule is a couple of dozen
         * cards; past this the marginal game is a final whose program has
         * already ended anyway.
         */
        const val MAX_EPG_GAMES = 24

        /** A term shorter than this matches too much of the guide to be useful. */
        const val MIN_SEARCH_TERM_LENGTH = 3
    }
}
