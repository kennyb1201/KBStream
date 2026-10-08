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
import com.kennyb1201.kbstream.data.sports.TournamentEvent
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    /**
     * The channel carrying each game/event, keyed by ESPN event id.
     *
     * An id ABSENT from the map is the honest "not in your playlist" the cards
     * show; the app never substitutes a guess, because a wrong game playing on
     * a wrong channel is worse than nothing playing at all.
     */
    private val _matches = MutableStateFlow<Map<String, IptvChannel>>(emptyMap())
    val matches: StateFlow<Map<String, IptvChannel>> = _matches.asStateFlow()

    private var refreshJob: Job? = null

    /** The merged lineup, read once per hub visit (a paged 10k-row DB read). */
    private var playlistChannels: List<IptvChannel>? = null

    init {
        refresh()
    }

    // ── The leagues the profile turned on ────────────────────────────

    /**
     * The enabled leagues, in catalog order - the order the tabs are drawn in.
     */
    val leagues: List<SportsLeague>
        get() = SportsLeagues.enabled(_enabledLeagues.value)

    fun selectLeague(path: String) {
        _selectedLeaguePath.value = path
    }

    /**
     * Turns a league on or off and refetches.
     *
     * Persisted per profile, so a profile with a small playlist can keep the
     * hub to the leagues it actually has channels for.
     */
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
        }
    }

    /**
     * The 30-second live tick, while the hub is on screen.
     *
     * Only leagues with something in play are refetched: an upcoming game's
     * kick-off time does not change between two ticks, and the ESPN cache would
     * answer most of those from memory anyway - so this is about scores and the
     * clock, which only move for a live game.
     */
    fun refreshLive() {
        val live = _sections.value.filter { section ->
            section.games.any { it.state == GameState.LIVE } ||
                section.tournaments.any { it.state == GameState.LIVE }
        }
        if (live.isEmpty()) return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val updated = _sections.value.map { section ->
                val fresh = live.firstOrNull { it.league.path == section.league.path }
                    ?: return@map section
                fetchSection(fresh.league, fresh)
            }
            _sections.value = updated
            resolveMatches(updated)
        }
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
        val games = sections.flatMap { it.games }
        val events = sections.flatMap { it.tournaments }
        if (games.isEmpty() && events.isEmpty()) {
            _matches.value = emptyMap()
            return
        }
        val channels = channelsOrEmpty()
        if (channels.isEmpty()) {
            _matches.value = emptyMap()
            return
        }
        // Off the main thread: building the guide index is one small object per
        // channel per guide source, and a provider playlist runs to five
        // figures - enough that doing it in the composition's own dispatcher
        // would be a visible frame hit on the leagues a viewer actually has.
        val matches = withContext(Dispatchers.Default) {
            val guideIndex = runCatchingCancellable { guideChannelIndex(channels) }
                .getOrDefault(emptyMap())
            val programs = if (guideIndex.isEmpty()) {
                emptyList()
            } else {
                runCatchingCancellable { epgCandidates(games, guideIndex) }.getOrDefault(emptyList())
            }
            val found = HashMap<String, IptvChannel>()
            games.forEach { game ->
                SportsChannelMatcher.match(game, channels, programs)?.let { found[game.id] = it }
            }
            events.forEach { event ->
                SportsChannelMatcher.match(event, channels, programs)?.let { found[event.id] = it }
            }
            Log.d(
                TAG,
                "SPORTS MATCHES cards=${games.size + events.size} matched=${found.size} " +
                    "programs=${programs.size}"
            )
            found
        }
        _matches.value = matches
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
