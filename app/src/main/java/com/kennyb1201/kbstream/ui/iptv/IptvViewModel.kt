package com.kennyb1201.kbstream.ui.iptv

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.db.retryOnDatabaseSwap
import com.kennyb1201.kbstream.data.db.DB_SWAP_RETRY_LONG_ATTEMPTS
import com.kennyb1201.kbstream.data.db.isTransientDatabaseError
import com.kennyb1201.kbstream.data.db.withDatabaseSwapRetry
import com.kennyb1201.kbstream.data.iptv.EpgMatchType
import com.kennyb1201.kbstream.data.iptv.db.EpgProgramRow
import com.kennyb1201.kbstream.data.iptv.IptvChannel
import com.kennyb1201.kbstream.data.iptv.IptvChannelWithEpg
import com.kennyb1201.kbstream.data.iptv.IptvPlaylist
import com.kennyb1201.kbstream.data.iptv.EpgImportWatchdog
import com.kennyb1201.kbstream.data.iptv.EpgStalledException
import com.kennyb1201.kbstream.data.iptv.EpgWindow
import com.kennyb1201.kbstream.data.iptv.GuideImportOutcome
import com.kennyb1201.kbstream.data.iptv.GuideImportProgress
import com.kennyb1201.kbstream.data.iptv.epgEntryImportCoolingDown
import com.kennyb1201.kbstream.data.iptv.GuideImportState
import com.kennyb1201.kbstream.data.iptv.IptvRepository
import com.kennyb1201.kbstream.data.iptv.guideWindowFingerprint
import com.kennyb1201.kbstream.data.reporting.Redaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.kennyb1201.kbstream.data.runCatchingCancellable

class IptvViewModel(private val app: Application) : AndroidViewModel(app) {

    // Shared with the player: the in-player guide resolves matches against the
    // same memoized guide snapshot this screen reads, instead of building a
    // second copy of the whole guide (see IptvRepository.shared).
    private val repository = IptvRepository.shared(app)
    private val prefs
        get() = app.getSharedPreferences(
            com.kennyb1201.kbstream.data.sync.ProfileStorage.prefsName(
                app, PREFS_NAME
            ),
            Context.MODE_PRIVATE
        )

    private val _playlistUrl = MutableStateFlow(prefs.getString(KEY_PLAYLIST_URL, "").orEmpty())
    val playlistUrl: StateFlow<String> = _playlistUrl.asStateFlow()

    private val _epgUrl = MutableStateFlow(prefs.getString(KEY_EPG_URL, "").orEmpty())
    val epgUrl: StateFlow<String> = _epgUrl.asStateFlow()

    /**
     * Secondary EPG sources (newline-separated URLs) merged with the
     * primary guide. Channels missing from one source can still match the
     * other; matching keys off each source's own URL in the cache tables,
     * so any number of sources coexist without clobbering each other.
     */
    private val _extraEpgUrls = MutableStateFlow(
        prefs.getString(KEY_EXTRA_EPG_URLS, "").orEmpty()
    )
    val extraEpgUrls: StateFlow<String> = _extraEpgUrls.asStateFlow()

    /** Primary + extras, in order, trimmed, no blanks. */
    private fun allEpgUrls(): List<String> =
        (_epgUrl.value.trim().takeIf(String::isNotEmpty)?.let(::listOf).orEmpty() +
            extraEpgUrlList()).distinct()

    private val _playlistName = MutableStateFlow(prefs.getString(KEY_PLAYLIST_NAME, "").orEmpty())
    val playlistName: StateFlow<String> = _playlistName.asStateFlow()

    /**
     * Additional M3U sources (newline-separated URLs) merged into the
     * lineup alongside the primary playlist. Each entry may also carry a
     * "|name" suffix ("<url>|<display name>"). EPG matching for merged
     * channels keys off each playlist's own URL in the cache tables.
     */
    private val _extraPlaylistUrls = MutableStateFlow(
        prefs.getString(KEY_EXTRA_PLAYLIST_URLS, "").orEmpty()
    )
    val extraPlaylistUrls: StateFlow<String> = _extraPlaylistUrls.asStateFlow()

    // ── Guide-wide program search ─────────────────────────────────
    // Debounced: typing on a TV remote fires a keystroke per D-pad press, and
    // each one would otherwise run a LIKE scan over the whole EPG table.
    private val _programSearchResults = MutableStateFlow<List<EpgProgramRow>>(emptyList())
    val programSearchResults: StateFlow<List<EpgProgramRow>> =
        _programSearchResults.asStateFlow()
    private var programSearchJob: Job? = null

    fun searchPrograms(query: String) {
        programSearchJob?.cancel()
        if (query.trim().length < 2) {
            _programSearchResults.value = emptyList()
            return
        }
        programSearchJob = viewModelScope.launch {
            delay(250)
            val hits = runCatchingCancellable { repository.searchPrograms(query) }.getOrDefault(emptyList())
            _programSearchResults.value = hits
        }
    }

    fun onExtraPlaylistUrlsChanged(value: String) {
        _extraPlaylistUrls.value = value
        saveInputs()
    }

    private val _playlist = MutableStateFlow<IptvPlaylist?>(null)
    val playlist: StateFlow<IptvPlaylist?> = _playlist.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isImportingGuide = MutableStateFlow(false)
    val isImportingGuide: StateFlow<Boolean> = _isImportingGuide.asStateFlow()

    /**
     * How far the guide import has got, for the setup screen's status line.
     * Null whenever nothing is importing — see [GuideImportProgress].
     */
    val guideImport: StateFlow<GuideImportState?> = GuideImportProgress.state

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _guideError = MutableStateFlow<String?>(null)
    val guideError: StateFlow<String?> = _guideError.asStateFlow()

    private val _hiddenChannelIds = MutableStateFlow(
        prefs.getStringSet(KEY_HIDDEN_CHANNEL_IDS, emptySet()).orEmpty().toSet()
    )
    val hiddenChannelIds: StateFlow<Set<String>> = _hiddenChannelIds.asStateFlow()

    /**
     * Re-reads the hidden set when it changes on disk. The sync applier writes
     * this key straight from the cloud, and without a listener the ViewModel
     * keeps the set it was constructed with — so a synced hide never shows up,
     * and the next local edit saves that stale set back and pushes it, which
     * erases the hidden channels on the other device too.
     */
    private val hiddenIdsPrefListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key != KEY_HIDDEN_CHANNEL_IDS) return@OnSharedPreferenceChangeListener
            val stored = prefs.getStringSet(KEY_HIDDEN_CHANNEL_IDS, emptySet()).orEmpty().toSet()
            if (stored != _hiddenChannelIds.value) _hiddenChannelIds.value = stored
        }

    /** Store the listener is bound to; re-bound when the profile changes. */
    private var hiddenIdsPrefs: SharedPreferences? = null

    private fun observeHiddenChannelIdsPref() {
        val current = prefs
        if (hiddenIdsPrefs === current) return
        hiddenIdsPrefs?.unregisterOnSharedPreferenceChangeListener(hiddenIdsPrefListener)
        current.registerOnSharedPreferenceChangeListener(hiddenIdsPrefListener)
        hiddenIdsPrefs = current
    }

    override fun onCleared() {
        hiddenIdsPrefs?.unregisterOnSharedPreferenceChangeListener(hiddenIdsPrefListener)
        hiddenIdsPrefs = null
        super.onCleared()
    }

    /** Past programs with playable DVR URLs for the selected channel. */
    private val _catchupPrograms = MutableStateFlow<List<com.kennyb1201.kbstream.data.iptv.CatchupProgram>>(emptyList())
    val catchupPrograms: StateFlow<List<com.kennyb1201.kbstream.data.iptv.CatchupProgram>> =
        _catchupPrograms.asStateFlow()

    /** True while a catch-up list is loading, so the dialog does not read the
     *  empty (cleared) list as "this channel has no catch-up". */
    private val _catchupLoading = MutableStateFlow(false)
    val catchupLoading: StateFlow<Boolean> = _catchupLoading.asStateFlow()

    private var catchupJob: Job? = null

    /**
     * Bumped by every catch-up load. A cancelled load's `finally` still runs -
     * AFTER its replacement has set the flag true - so without this the old job
     * cleared the new one's loading flag and the dialog showed "No recent
     * programs available" until the real results landed (see HD-P2-7).
     */
    private var catchupGeneration = 0

    /**
     * Loads the catch-up (DVR) list for [channel]. An empty result means
     * the channel carries no catch-up attributes or has no EPG history —
     * the UI hides the section rather than showing dead entries.
     */
    fun loadCatchupPrograms(channel: IptvChannel) {
        catchupJob?.cancel()
        val generation = ++catchupGeneration
        // Drop the previous channel's list and flag the load: the dialog reads
        // this state the instant it opens, and leaving it populated flashed the
        // last channel's programs before the new ones landed.
        _catchupPrograms.value = emptyList()
        _catchupLoading.value = true
        catchupJob = viewModelScope.launch {
            try {
                // Guide data may live under any of the configured EPG
                // sources; the first one that yields programs wins.
                var found: List<com.kennyb1201.kbstream.data.iptv.CatchupProgram> = emptyList()
                for (url in allEpgUrls()) {
                    val rows = repository.getRecentCatchupPrograms(
                        channel = channel,
                        epgUrl = url
                    )
                    if (rows.isNotEmpty()) {
                        found = rows
                        break
                    }
                }
                _catchupPrograms.value = found
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.w(TAG, "CATCHUP LOAD FAILED channel=${channel.id}: ${t.message}")
                _catchupPrograms.value = emptyList()
            } finally {
                // Only the CURRENT load may clear the flag (see
                // [catchupGeneration]).
                if (generation == catchupGeneration) _catchupLoading.value = false
            }
        }
    }

    private val _guideRefreshTick = MutableStateFlow(0)

    // Bumped by the periodic now/next refresh WHEN that refresh has to go back
    // to the database. It is part of the query request (so a clock refresh can
    // never be conflated away by distinctUntilChanged) but deliberately NOT part
    // of the guide source key: the loaded programs are still valid, only "now"
    // moved. A tick the loaded programs can answer in memory leaves the request
    // untouched, so distinctUntilChanged() conflates it away and nothing runs.
    private val _guideClockTick = MutableStateFlow(0)

    // Persistent "already requested/cached" set. NEVER fed into combine() below —
    // that was the bug. Only clearGuideMemory() resets this, and it does so without
    // being observed by the flow that reads it, so resetting it can't self-trigger
    // a cancel-and-restart loop.
    private val _guideChannelIds = MutableStateFlow<Set<String>>(emptySet())

    // The actual combine() trigger: just the newly-queued batch for this request.
    // Cleared back to emptySet() only after a successful merge, not as a side
    // effect of clearing the cache above.
    private val _pendingGuideChannelIds = MutableStateFlow<Set<String>>(emptySet())

    private val _guideItemsByChannelId =
        MutableStateFlow<Map<String, IptvChannelWithEpg>>(emptyMap())

    private var loadedGuideSourceKey: String? = null

    /**
     * Channels whose loaded programs have run out and whose re-query already
     * came back with nothing better.
     *
     * A provider whose listing stops at midnight leaves every channel in that
     * state for hours, and asking again on the clock's cadence is exactly the
     * cost [bumpGuideClock] exists to remove. A channel leaves this set as soon
     * as its rows can answer the clock again - a fresh import clears the set,
     * and a re-query that lands with a longer listing drops out of it by itself.
     */
    private var exhaustedGuideChannelIds: Set<String> = emptySet()

    /**
     * [guideWindowFingerprint] of the playlist slice the loaded guide was built
     * from, so a playlist refresh that changed nothing guide-relevant can keep
     * what is already on screen instead of re-querying every program batch.
     */
    private var loadedGuideFingerprint: String? = null
    private var loadJob: Job? = null

    /**
     * The ONE guide import, whichever path started it.
     *
     * Both launch sites - the IMPORT EPG button and the entry auto-refresh -
     * go through [launchGuideImport], which is what makes "IMPORT EPG always
     * stops the running import" true for an auto-refresh import too. It used to
     * be two handles: the button cancelled its own private one while the entry
     * import ran on `refreshJob`, so a stuck auto-import could not be cancelled
     * from the button at all - the manual import merely joined it through
     * [IptvRepository]'s coalescing and sat at the same 0 programs.
     */
    private var guideImportJob: Job? = null

    private var refreshJob: Job? = null

    /**
     * Aborts a source that stops sending data, so a wedged download is a visible
     * error in three minutes rather than twenty minutes at "0 programs" (see
     * [EpgImportWatchdog]).
     */
    private val epgImportWatchdog = EpgImportWatchdog()

    private val playlistOnlyLineup: StateFlow<List<IptvChannelWithEpg>> = _playlist
        .map { currentPlaylist ->
            currentPlaylist?.let(::buildPlaylistOnlyLineup).orEmpty()
        }
        .flowOn(Dispatchers.Default)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            emptyList()
        )

    // flatMapLatest is still a @ExperimentalCoroutinesApi API; opting in here
    // rather than at file scope keeps the acceptance to this one property.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val lineupSource: StateFlow<List<IptvChannelWithEpg>> = combine(
        combine(
            _playlist,
            _epgUrl,
            _extraEpgUrls
        ) { playlist, epgUrl, extraEpgRaw ->
            // Pair the playlist with its guide-source list (primary first,
            // then extras). Inner combine's typed overload handles 3 flows;
            // the outer one would exceed the 5-flow typed limit otherwise.
            val guideUrls = (epgUrl.trim().takeIf(String::isNotEmpty)
                ?.let(::listOf).orEmpty() +
                extraEpgRaw.split('\n', ';')
                    .mapNotNull { it.trim().takeIf(String::isNotEmpty) })
                .distinct()
            playlist to guideUrls
        },
        _guideRefreshTick,
        _pendingGuideChannelIds,
        _guideClockTick
    ) { playlistAndGuides, refreshTick, channelIds, clockTick ->
        val (currentPlaylist, guideUrls) = playlistAndGuides
        GuideRequest(
            playlist = currentPlaylist,
            guideUrls = guideUrls,
            refreshTick = refreshTick,
            clockTick = clockTick,
            channelIds = channelIds
        )
    }
        // The request carries more than the query reads: _playlist changes for
        // reasons that cannot move a single program row (a logo URL, a
        // channel outside the guide window), and the import flag used to flip
        // twice per EPG import — each flip canceling and re-running the whole
        // lineup query for identical data. The refresh and clock ticks stay
        // explicit request inputs, so real refreshes still land.
        .distinctUntilChanged()
        .flatMapLatest { request ->
        val currentPlaylist = request.playlist

        when {
            currentPlaylist == null -> flowOf(emptyList())
            request.guideUrls.isEmpty() -> flowOf(emptyList())
            request.channelIds.isEmpty() -> flowOf(emptyList())
            // NOTE: no isImportingGuide guard here. While a stale-EPG
            // background refresh runs, the previous import's programs are
            // still in the DB — blanking the lineup for the whole import made
            // the guide show "no program data" for minutes on entry (it only
            // recovered when the import finished). Query the cached data
            // right away; importGuideInternal() bumps the refresh tick when
            // done, which re-runs this query against the fresh import.
            // A guide read can lose its database mid-query: the guide file is
            // per-profile and its instance is retired across a profile change
            // (see data/db/DatabaseSwapRetry.kt). Retrying HERE, inside the
            // request, re-runs the query through a freshly resolved DAO and
            // keeps this flow alive. Without it the exception took the whole
            // lineup flow down — and the lineup flow is what the guide screen
            // shows, so it stayed empty for the rest of the screen's life.
            else -> observeGuideRequest(currentPlaylist, request)
                .retryOnDatabaseSwap { attempt, error ->
                    Log.w(
                        TAG,
                        "GUIDE QUERY RETRY $attempt after database swap",
                        error
                    )
                }
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        emptyList()
    )

    val allChannels: StateFlow<List<IptvChannelWithEpg>> = combine(
        playlistOnlyLineup,
        _guideItemsByChannelId
    ) { playlistOnly, guideByChannelId ->
        mergePlaylistWithGuide(playlistOnly, guideByChannelId)
    }.flowOn(Dispatchers.Default)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            emptyList()
        )

    val visibleChannels: StateFlow<List<IptvChannelWithEpg>> = combine(
        allChannels,
        _hiddenChannelIds
    ) { channels, hiddenIds ->
        val base =
            if (hiddenIds.isEmpty()) channels else channels.filterNot { it.channel.id in hiddenIds }
        kidsFilterChannels(base)
    }.flowOn(Dispatchers.Default)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            emptyList()
        )

    /**
     * Channel ids whose guide row has already been RESOLVED by a lineup
     * query — the keys of the guide cache above.
     *
     * A channel missing from here has simply not been queried yet: the
     * guide window only covers the channels the user has actually scrolled
     * past, so every group switch swaps in a screenful of channels whose
     * EPG is still on its way. That is a different state from a resolved
     * channel that matched no XMLTV channel at all, and the guide must not
     * confuse the two — without the distinction the new group rendered a
     * false "No program data" on every row for the second the first query
     * for it took to land. The UI renders the first state as a quiet
     * loading placeholder and only the second as "No program data".
     */
    val resolvedGuideChannelIds: StateFlow<Set<String>> = _guideItemsByChannelId
        .map { it.keys }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptySet())

    /**
     * The guide rows resolved so far, keyed by playlist channel id: the guide's
     * visible rows plus anything [updateGuideChannels] has been asked for.
     *
     * Read by the search overlay so a channel hit can say what is on now and
     * next before it is opened, and without a second query - the rows the
     * search asked for are already the ones the guide loaded.
     */
    val loadedGuideItems: StateFlow<Map<String, IptvChannelWithEpg>> =
        _guideItemsByChannelId.asStateFlow()

    /**
     * Kids Mode Live TV filter (profile toggle "Kid-safe Live TV"). An
     * M3U guide can't be rated, so when the active kids profile opts in,
     * only channel groups that LOOK kid-focused survive — group titles
     * are matched loosely (case, spaces, separators) against kid brand
     * words. Groups with no recognizable kid signal are hidden: for a
     * curated playlist a parent can turn the toggle off; for a random
     * playlist the safe direction is hiding the unclassifiable.
     */
    private fun kidsFilterChannels(
        channels: List<IptvChannelWithEpg>
    ): List<IptvChannelWithEpg> {
        // The rule itself moved to KidsLiveTvFilter so the SPORTS hub's WATCH
        // can ask the same question (audit C7): it was the second door into the
        // live player, and it used the raw playlist.
        val profile = com.kennyb1201.kbstream.data.sync.ProfileManager.activeProfile.value
        if (profile?.kidsMaxAge == null || !profile.kidsLockLiveTv) return channels
        return channels.filter { ch ->
            com.kennyb1201.kbstream.data.iptv.KidsLiveTvFilter.keep(
                profile,
                ch.channel.displayName,
                ch.channel.groupTitle
            )
        }
    }

    init {
        viewModelScope.launch {
            lineupSource.collect { lineup ->
                if (lineup.isNotEmpty()) {
                    mergeGuideItems(lineup)
                    // Drop only the ids this merge answered. A superseded
                    // request never emits, so its ids are still outstanding
                    // and must survive to be re-issued; clearing the whole set
                    // here is what left those channels pending forever.
                    _pendingGuideChannelIds.value = GuideRequestQueue.clearAnswered(
                        _pendingGuideChannelIds.value,
                        _guideItemsByChannelId.value.keys
                    )
                }
            }
        }
        observeHiddenChannelIdsPref()
        startGuideClockRefresh()
        restoreCachedPlaylist()
        observeProfileSwitches()
    }

    /**
     * Profile-switch reset: the ViewModel is created once (activity store)
     * and survives switches, but its source config is per-profile (scoped
     * prefs) and its in-memory playlist/EPG state belongs to whichever
     * profile loaded it. Without this, after a switch the guide shows the
     * previous profile's channels and actions persist into the wrong
     * profile's prefs.
     */
    private fun observeProfileSwitches() {
        viewModelScope.launch {
            var first = true
            com.kennyb1201.kbstream.data.sync.ProfileManager.activeProfile
                .collect {
                    if (first) {
                        first = false
                        return@collect
                    }
                    loadJob?.cancel()
                    guideImportJob?.cancel()
                    refreshJob?.cancel()

                    // Re-read the incoming profile's scoped config.
                    _playlistUrl.value = prefs.getString(KEY_PLAYLIST_URL, "").orEmpty()
                    _epgUrl.value = prefs.getString(KEY_EPG_URL, "").orEmpty()
                    _playlistName.value = prefs.getString(KEY_PLAYLIST_NAME, "").orEmpty()
                    _extraPlaylistUrls.value = prefs.getString(KEY_EXTRA_PLAYLIST_URLS, "").orEmpty()
                    _extraEpgUrls.value = prefs.getString(KEY_EXTRA_EPG_URLS, "").orEmpty()
                    _hiddenChannelIds.value =
                        prefs.getStringSet(KEY_HIDDEN_CHANNEL_IDS, emptySet()).orEmpty().toSet()
                    // Re-bind: the listener has to follow the new profile's store.
                    observeHiddenChannelIdsPref()

                    // Drop the previous profile's in-memory content.
                    _playlist.value = null
                    _guideItemsByChannelId.value = emptyMap()
                    _guideChannelIds.value = emptySet()
                    _pendingGuideChannelIds.value = emptySet()
                    exhaustedGuideChannelIds = emptySet()
                    loadedGuideSourceKey = null
                    _guideRefreshTick.value += 1

                    restoreCachedPlaylist()
                }
        }
    }

    /**
     * Keeps NOW/NEXT/Upcoming from going stale while the guide is open. The
     * lineup flow computes nowUtcMillis and the query window once per run and
     * only re-runs when playlist/EPG/tick/channel-batch changes -- left alone,
     * a channel whose query ran at 10:00 keeps claiming the 10:00 program
     * is "on now" well past its end.
     *
     * Each tick advances the loaded programs in memory ([bumpGuideClock]), and
     * only the channels it cannot advance any further are re-queued through
     * _pendingGuideChannelIds for a fresh query -- so a tick costs a walk over
     * the rows already on screen, and the flow itself does not run at all.
     *
     * Periodic while the VM is alive (subscribers stop within STOP_TIMEOUT_MS
     * of leaving the guide, and the VM dies with it) plus one shot on start
     * so returning to the guide after a while shows current programs
     * immediately instead of the last session's snapshot.
     *
     * The same loop is where guide FRESHNESS gets re-checked, on a slow
     * multiple of the clock cadence. Staleness used to be evaluated exactly
     * once, on the cache-restore path at construction, so an app left running
     * (a TV box can stay open for days) never revisited the question and its
     * guide aged out from under it - showing yesterday's schedule until the
     * process was restarted.
     */
    private fun startGuideClockRefresh() {
        viewModelScope.launch {
            // First pass: catch up after returning to the screen. Small delay
            // so the initial pipeline (restore + initial window request) gets
            // going first and this rides along after it.
            delay(GUIDE_CLOCK_FIRST_REFRESH_MS)
            bumpGuideClock()

            var ticks = 0
            while (isActive) {
                delay(GUIDE_CLOCK_REFRESH_INTERVAL_MS)
                bumpGuideClock()
                if (++ticks % GUIDE_STALENESS_CHECK_EVERY_TICKS == 0) refreshIfNeeded()
            }
        }
    }

    /**
     * One clock tick: move NOW/NEXT forward from the programs the guide has
     * already loaded, and go back to the database ONLY for the channels whose
     * loaded programs have run out.
     *
     * This used to re-issue the whole loaded batch — a lineup query for up to 80
     * channels x 960 programs every two minutes, for as long as the guide was
     * open, which was the heaviest thing the app repeated. It was also almost
     * entirely wasted: the programs are already in memory, and the clock moving
     * only changes which of them is "on now". The database is needed exactly
     * when a channel's next program has already ended, which is a schedule
     * boundary rather than a two-minute grid (see [GuideClockAdvance]).
     */
    private fun bumpGuideClock() {
        val loaded = _guideItemsByChannelId.value
        val now = System.currentTimeMillis()

        // Forget every channel that can answer the clock again — a fresh import
        // landing, or a re-query that brought back a longer listing — and every
        // channel that is no longer loaded at all.
        if (exhaustedGuideChannelIds.isNotEmpty()) {
            exhaustedGuideChannelIds = exhaustedGuideChannelIds
                .filter { channelId ->
                    loaded[channelId]?.let { !GuideClockAdvance.canAnswer(it, now) } == true
                }
                .toSet()
        }

        if (loaded.isEmpty()) return

        val step = GuideClockAdvance.step(loaded, now)

        if (step.advanced.isNotEmpty()) {
            val updated = HashMap(loaded)
            step.advanced.forEach { (channelId, item) -> updated[channelId] = item }
            _guideItemsByChannelId.value = updated
        }

        // A row that has run out is re-queried once, not once per tick.
        val toRequery = step.expiredIds - exhaustedGuideChannelIds
        if (toRequery.isEmpty()) return

        exhaustedGuideChannelIds = exhaustedGuideChannelIds + toRequery
        // ADD to the outstanding set, for the same reason updateGuideChannels()
        // does: flatMapLatest cancels an in-flight query the moment a newer
        // batch arrives, and a cancelled query never emits, so replacing the set
        // would strand whatever was already being answered.
        _pendingGuideChannelIds.value =
            GuideRequestQueue.enqueue(_pendingGuideChannelIds.value, toRequery)
        // The re-issued batch can be the same ids it was last time, so the tick
        // is what makes the request distinct — without it distinctUntilChanged()
        // would swallow it and the channel would keep its last known program.
        _guideClockTick.value += 1
    }

    fun onPlaylistUrlChanged(value: String) {
        _playlistUrl.value = value
        saveInputs()
    }

    fun onEpgUrlChanged(value: String) {
        _epgUrl.value = value
        saveInputs()

        val playlist = _playlist.value
        if (playlist != null) {
            clearGuideMemory(
                buildGuideSourceKey(
                    playlist = playlist,
                    guideUrls = allEpgUrls(),
                    refreshTick = _guideRefreshTick.value + 1
                )
            )
        } else {
            clearGuideMemory()
        }
        _guideRefreshTick.value += 1
    }

    /** Parsed extra-EPG textarea: trimmed, one URL per line or ';'. */
    private fun extraEpgUrlList(): List<String> =
        _extraEpgUrls.value
            .split('\n', ';')
            .mapNotNull { it.trim().takeIf(String::isNotEmpty) }

    fun onExtraEpgUrlsChanged(value: String) {
        _extraEpgUrls.value = value
        saveInputs()

        val playlist = _playlist.value
        if (playlist != null) {
            clearGuideMemory(
                buildGuideSourceKey(
                    playlist = playlist,
                    guideUrls = allEpgUrls(),
                    refreshTick = _guideRefreshTick.value + 1
                )
            )
        } else {
            clearGuideMemory()
        }
        _guideRefreshTick.value += 1
    }

    fun onPlaylistNameChanged(value: String) {
        _playlistName.value = value
        saveInputs()
    }

    fun hideChannel(channelId: String) {
        if (channelId.isBlank() || channelId in _hiddenChannelIds.value) return
        _hiddenChannelIds.value = _hiddenChannelIds.value + channelId
        saveHiddenChannelIds()
        _guideChannelIds.value = _guideChannelIds.value - channelId
        _pendingGuideChannelIds.value = _pendingGuideChannelIds.value - channelId
        _guideItemsByChannelId.value = _guideItemsByChannelId.value - channelId
    }

    fun unhideChannel(channelId: String) {
        if (channelId.isBlank() || channelId !in _hiddenChannelIds.value) return
        _hiddenChannelIds.value = _hiddenChannelIds.value - channelId
        saveHiddenChannelIds()
    }

    fun unhideAllChannels() {
        if (_hiddenChannelIds.value.isEmpty()) return
        _hiddenChannelIds.value = emptySet()
        saveHiddenChannelIds()
    }

    fun updateGuideChannels(visibleChannelIds: List<String>) {
        if (visibleChannelIds.isEmpty()) return
        val playlist = _playlist.value ?: return
        val hiddenIds = _hiddenChannelIds.value
        val queuedIds = _guideChannelIds.value
        val knownIds = playlist.channels.asSequence().map { it.id }.toHashSet()
        val newIds = visibleChannelIds.asSequence()
            .filter { it in knownIds && it !in hiddenIds && it !in queuedIds }
            .take(MAX_GUIDE_CHANNELS_PER_REQUEST)
            .toSet()
        if (newIds.isEmpty()) return
        _guideChannelIds.value = queuedIds + newIds
        // ADD to the outstanding set, don't replace it. The lineup flow
        // cancels an in-flight query the moment a newer batch arrives, and a
        // cancelled query never emits, so the batch it was working on would
        // otherwise vanish from here while staying in _guideChannelIds - no
        // longer pending (never re-issued) and never resolved, which is the
        // row stuck on "Loading guide...".
        _pendingGuideChannelIds.value = GuideRequestQueue.enqueue(
            _pendingGuideChannelIds.value,
            newIds
        )
        Log.d(TAG, "GUIDE CHANNELS QUEUED total=${_guideChannelIds.value.size} added=${newIds.size}")
    }

    private fun observeGuideRequest(
        currentPlaylist: IptvPlaylist,
        request: GuideRequest
    ): Flow<List<IptvChannelWithEpg>> {
        val sourceKey = buildGuideSourceKey(
            currentPlaylist, request.guideUrls, request.refreshTick
        )

        // NOTE: this branch should rarely fire in practice now, because applyPlaylist()/
        // onEpgUrlChanged()/importGuideInternal() precompute and set loadedGuideSourceKey
        // BEFORE bumping _guideRefreshTick. It stays here only as a safety net for a
        // source-key mismatch we didn't anticipate. It intentionally does NOT touch
        // _guideChannelIds/_pendingGuideChannelIds (those aren't part of the key), so
        // it can't create the old self-clearing feedback loop.
        if (loadedGuideSourceKey != sourceKey) {
            loadedGuideSourceKey = sourceKey
            _guideItemsByChannelId.value = emptyMap()
        }

        val channelsById = currentPlaylist.channels.associateBy { it.id }
        val channelsToLoad = request.channelIds.asSequence()
            .mapNotNull(channelsById::get)
            .take(MAX_GUIDE_CHANNELS_PER_REQUEST)
            .toList()

        Log.w(TAG, "GUIDE QUERY requested=${request.channelIds.size} channels=${channelsToLoad.size}")
        if (channelsToLoad.isEmpty()) return flowOf(emptyList())

        val now = System.currentTimeMillis()
        return repository.observeLineupWithGuides(
            playlist = currentPlaylist.copy(channels = channelsToLoad),
            epgUrls = request.guideUrls,
            windowStart = now - GUIDE_PAST_WINDOW_MS,
            windowEnd = now + GUIDE_FUTURE_WINDOW_MS,
            limit = VISIBLE_GUIDE_PROGRAM_LIMIT
        )
    }

    private fun requestInitialGuideWindow() {
        val hiddenIds = _hiddenChannelIds.value
        val initialChannelIds = _playlist.value?.channels?.asSequence()
            ?.filterNot { it.id in hiddenIds }
            ?.take(INITIAL_GUIDE_WINDOW_SIZE)
            ?.map { it.id }
            ?.toList()
            .orEmpty()
        updateGuideChannels(initialChannelIds)
    }

    private fun restoreCachedPlaylist() {
        val url = _playlistUrl.value.trim()
        val name = _playlistName.value.trim().ifBlank { null }
        if (url.isBlank()) {
            Log.d(TAG, "CACHE RESTORE SKIPPED playlist URL is blank")
            return
        }
        _isLoading.value = true
        // Playlist URLs go through Redaction.url: Xtream-style providers put
        // the account username and password in the query string.
        Log.w(TAG, "CACHE RESTORE START source=${Redaction.url(url)}")
        viewModelScope.launch {
            try {
                val cachedPlaylist = repository.loadCachedPlaylist(url, name)
                if (cachedPlaylist != null) {
                    // Extras must survive app restarts too — otherwise the
                    // cached restore shows only the primary playlist until
                    // the user manually reloads (applyPlaylist merges them).
                    applyPlaylist(cachedPlaylist)
                    Log.w(TAG, "CACHE RESTORE HIT channels=${cachedPlaylist.channels.size} source=${Redaction.url(url)}")
                    refreshIfNeeded()
                } else {
                    // URL is configured but nothing cached (first entry after
                    // clearing app storage, a provider purge, or a profile
                    // switch before the first successful load). Falling back
                    // to showing the setup screen with a dead action row made
                    // the guide LOOK broken; a configured URL is an explicit
                    // instruction to load, so fetch it now. The UI keeps the
                    // guide usable meanwhile: channels appear the moment the
                    // fetch + cache write finish.
                    Log.w(TAG, "CACHE RESTORE MISS source=${Redaction.url(url)} — auto-loading playlist")
                    load()
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                reportFailure(_error, t)
                Log.e(TAG, "CACHE RESTORE FAILED source=${Redaction.url(url)}", Redaction.throwable(t))
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun load() {
        val url = _playlistUrl.value.trim()
        val name = _playlistName.value.trim().ifBlank { null }
        if (url.isBlank()) {
            _error.value = "Playlist URL is required"
            return
        }
        if (_isImportingGuide.value) {
            _error.value = "EPG import is in progress. Please wait before loading the playlist."
            return
        }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val hasPlaylist = _playlist.value != null
            _isLoading.value = !hasPlaylist
            _error.value = null
            try {
                val loadedPlaylist = loadPlaylistResilient(url, name)
                applyPlaylist(loadedPlaylist)
                markUpdated(KEY_PLAYLIST_UPDATED_AT)
                Log.d(TAG, "PLAYLIST LOAD SUCCESS channels=${loadedPlaylist.channels.size} source=${Redaction.url(url)}")
                // The cache-MISS path (fresh install, cleared storage, a
                // profile switch before the first load) ends up here, and the
                // synced EPG URLs are already configured — so opening the
                // Guide used to show channels with "No program data" until
                // IMPORT EPG was pressed. The cache-HIT path above already
                // asks, and this is the same ask after the fresh channel list
                // is in place. refreshIfNeeded's own guards make it safe: it
                // no-ops with no EPG URL, with a fresh guide, while an import
                // is in flight, and during the stall cooldown.
                refreshIfNeeded()
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                reportFailure(_error, t)
                Log.e(TAG, "PLAYLIST LOAD FAILED source=${Redaction.url(url)}", Redaction.throwable(t))
                if (!hasPlaylist) _playlist.value = null
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Parses the extra-playlist textarea and merges every additional M3U
     * into [primary]'s channel list. Failures are non-fatal: a merged
     * source that cannot load logs and is skipped so the primary playlist
     * still comes up. Channel ids are namespaced by source URL to keep
     * dedupe/EPG-match keys stable across providers.
     */
    private suspend fun mergeWithExtraPlaylists(primary: IptvPlaylist): IptvPlaylist {
        val entries = _extraPlaylistUrls.value
            .split('\n', ';')
            .mapNotNull { raw ->
                val trimmed = raw.trim()
                if (trimmed.isBlank()) null else trimmed
            }
        if (entries.isEmpty()) return primary

        var added = 0
        val extraChannelLists = mutableListOf<List<IptvChannel>>()
        for (entry in entries) {
            val parts = entry.split('|', limit = 2)
            val extraUrl = parts[0].trim()
            val extraName = parts.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
            if (extraUrl.isBlank() || extraUrl.equals(primary.sourceUrl?.trim(), ignoreCase = true)) {
                continue
            }
            try {
                val extra = repository.loadPlaylist(extraUrl, extraName)
                extraChannelLists += extra.channels.map { ch ->
                    // Prefix ids with the source hash so two providers that
                    // both emit "channel_1" don't collide (dedupe + EPG keys).
                    val ns = (extraUrl.hashCode().toUInt() and 0xFFFFFFu).toString(16)
                    if (ch.id.isBlank()) ch else ch.copy(id = "x$ns:${ch.id}")
                }
                added += extra.channels.size
            } catch (t: Throwable) {
                // Failures are non-fatal: the primary playlist still loads.
                if (t is CancellationException) throw t
                Log.w(
                    TAG,
                    "EXTRA PLAYLIST SKIPPED source=${Redaction.url(extraUrl)} " +
                        "error=${Redaction.text(t.message)}"
                )
            }
        }
        if (added == 0) return primary

        Log.d(TAG, "EXTRA PLAYLIST MERGE added=$added sources=${extraChannelLists.size}")
        return primary.copy(
            channels = (extraChannelLists + listOf(primary.channels))
                .flatten()
                .distinctBy { it.id },
            name = primary.name
        )
    }

    fun importGuide() {
        if (allEpgUrls().isEmpty()) {
            _guideError.value = "EPG URL is required"
            return
        }
        if (_isLoading.value) {
            _guideError.value = "Playlist loading is in progress. Please wait before importing EPG."
            return
        }
        // Always forces the attempt: this is the button the viewer presses
        // BECAUSE the last one wedged, so it ignores the stall cooldown and
        // cancels whatever pass is running, whichever path started it.
        //
        // And it forces a FRESH fetch (see launchGuideImport): a recovery press
        // that goes out with the stored validators gets the same `304` the
        // automatic refresh already got, which is the two-second no-op the
        // field report came from.
        launchGuideImport(forceFresh = true)
    }

    /**
     * Starts the one guide import, after stopping any pass already running.
     *
     * The cancelled pass is JOINED before the replacement starts rather than
     * left to unwind underneath it: its `finally` closes the shared
     * [GuideImportProgress] pass (which would blank the new pass's status line)
     * and its repository entry is only dropped once its fetch has actually let
     * go, so a replacement that started first would join an import that is
     * already dead and inherit its cancellation.
     */
    private fun launchGuideImport(forceFresh: Boolean = false) {
        val previous = guideImportJob
        previous?.cancel()
        guideImportJob = viewModelScope.launch {
            previous?.join()
            importGuideInternal(
                epgUrl = allEpgUrls().firstOrNull().orEmpty(),
                forceFresh = forceFresh
            )
        }
    }

    /**
     * Imports one EPG source, re-running it while the failure is the
     * profile-scoped guide database being swapped underneath the import.
     *
     * A running import holds a database connection for minutes, and the guide
     * database retires an instance across a profile change (or a tombstone
     * re-request). When that lands mid-import the batch write dies with Room's
     * "connection pool has been closed" / "already-closed object" / SQLITE_BUSY,
     * which surfaced as a permanent error banner and no guide. Re-running is
     * safe: the import stages every row under its own source key and only
     * promotes on a complete parse, and by a later attempt the database layer
     * has settled on one instance, so the attempt finishes the import.
     *
     * It used to retry exactly ONCE, which is not enough for a source whose
     * import is long enough to outlive more than one swap: the second failure
     * became the banner the user was left holding, with the guide still empty
     * on every entry. Only swap symptoms are retried, and only a bounded
     * number of times — a real failure (network, malformed guide) still fails
     * loudly on its first attempt.
     */
    private suspend fun importGuideSource(
        url: String,
        freshConnection: Boolean = false,
        forceFresh: Boolean = false
    ): GuideImportOutcome {
        return withDatabaseSwapRetry(
            attempts = DB_SWAP_RETRY_LONG_ATTEMPTS,
            onRetry = { attempt, error ->
                Log.w(
                    TAG,
                    "GUIDE IMPORT RETRY $attempt after database swap " +
                        "source=${Redaction.url(url)}",
                    Redaction.throwable(error)
                )
            }
        ) {
            // A first attempt shares whatever import is already running for
            // this URL (the worker's); the watchdog's retry must not, because
            // the attempt it replaces is the one it just aborted.
            repository.importGuide(
                epgUrl = url,
                joinActiveImport = !freshConnection,
                forceFresh = forceFresh
            )
        }
    }

    /**
     * A playlist load wrapped for the profile-scoped database swap.
     *
     * The load WRITES: it replaces every cached channel row in one transaction
     * ([IptvRepository.loadPlaylist] -> `replaceCachedPlaylistChannels`), so it
     * dies exactly the way the guide import used to when its instance is
     * retired underneath it - "attempt to re-open an already-closed object".
     * The guide import got a retry wrapper for that; the playlist paths did
     * not, which is why an automatic refresh could leave the setup panel
     * holding that exception while the cached playlist and the guide both kept
     * working. Same treatment, same over-sized budget.
     */
    private suspend fun loadPlaylistResilient(
        url: String,
        name: String?
    ): IptvPlaylist = withDatabaseSwapRetry(
        attempts = DB_SWAP_RETRY_LONG_ATTEMPTS,
        onRetry = { attempt, error ->
            Log.w(
                TAG,
                "PLAYLIST LOAD RETRY $attempt after database swap " +
                    "source=${Redaction.url(url)}",
                Redaction.throwable(error)
            )
        }
    ) {
        repository.loadPlaylist(url, name)
    }

    /**
     * Publishes a failed operation to its banner - unless the failure is a
     * database-swap symptom.
     *
     * A swap is the guide/playlist database being retired under running work,
     * not something the user can act on: the next attempt resolves a fresh
     * instance (see [withDatabaseSwapRetry]) and the cached playlist and guide
     * stay live. Reported anyway it read as
     *
     *   IllegalStateException: attempt to re-open an already-closed object:
     *     SQLiteDatabase: /data/.../<profile>.iptv_epg.db
     *
     * - a banner the user had to clear by hand while everything on the screen
     * worked. It is logged instead, so the race stays visible in logcat.
     */
    private fun reportFailure(
        target: MutableStateFlow<String?>,
        error: Throwable,
        prefix: String = ""
    ) {
        if (isTransientDatabaseError(error)) {
            Log.w(TAG, "IGNORED DATABASE SWAP FAILURE", error)
            return
        }
        target.value = prefix + buildMessage(error)
    }

    private fun refreshIfNeeded() {
        if (refreshJob?.isActive == true) return
        val playlistNeedsRefresh = isStale(KEY_PLAYLIST_UPDATED_AT, PLAYLIST_REFRESH_MS)
        val guideNeedsRefresh = allEpgUrls().isNotEmpty() &&
            (isStale(KEY_EPG_UPDATED_AT, EPG_REFRESH_MS) || guideFileMoved())
        // A source the watchdog just failed stays quiet on ENTRY: the failed
        // run could not write KEY_EPG_UPDATED_AT, so every later entry saw a
        // stale guide and started another import, which wedged again. The
        // banner from that failure is what the viewer keeps seeing; the manual
        // button is how they ask for another attempt, and it skips this.
        val guideCoolingDown = guideNeedsRefresh && guideStalledRecently()
        // An import already in flight is not restarted by a staleness tick: the
        // guide is stale precisely BECAUSE that import has not finished, and
        // replacing it would restart a multi-minute download every half hour.
        // (This used to be implicit - the import shared the refresh job's
        // lifetime - and stays explicit now that it has a handle of its own.)
        val guideBusy = guideImportJob?.isActive == true
        val canImportGuide = guideNeedsRefresh && !guideCoolingDown && !guideBusy
        if (!playlistNeedsRefresh && !canImportGuide) return
        refreshJob = viewModelScope.launch {
            if (playlistNeedsRefresh) {
                runCatchingCancellable { refreshPlaylistInBackground() }.onFailure { error ->
                    if (error is CancellationException) throw error
                    reportFailure(_error, error)
                    Log.e(TAG, "BACKGROUND PLAYLIST REFRESH FAILED", error)
                }
            }
            // On the shared guide handle, so the button can stop it - the
            // playlist half above keeps running on this job either way.
            if (canImportGuide) launchGuideImport()
        }
    }

    private fun guideStalledRecently(): Boolean =
        epgEntryImportCoolingDown(
            prefs.getLong(KEY_EPG_LAST_STALL_AT, 0L),
            System.currentTimeMillis()
        )

    private suspend fun refreshPlaylistInBackground() {
        val url = _playlistUrl.value.trim()
        val name = _playlistName.value.trim().ifBlank { null }
        if (url.isBlank() || _isImportingGuide.value) return
        val refreshedPlaylist = loadPlaylistResilient(url, name)
        applyPlaylist(refreshedPlaylist)
        markUpdated(KEY_PLAYLIST_UPDATED_AT)
        // applyPlaylist merged the extras in, so report what was applied.
        Log.d(
            TAG,
            "BACKGROUND PLAYLIST REFRESH END channels=" +
                "${_playlist.value?.channels?.size ?: refreshedPlaylist.channels.size}"
        )
    }

    private suspend fun importGuideInternal(
        epgUrl: String,
        forceFresh: Boolean = false
    ) {
        if (epgUrl.isBlank()) return
        _isImportingGuide.value = true
        _guideError.value = null
        val urls = allEpgUrls()
        GuideImportProgress.begin(sourceCount = urls.size)
        // What this pass will say when it ends (see GuideImportProgress.finish):
        // "Imported N programs", "Guide unchanged on server" or the failure.
        // Null means the pass never got a usable answer to report, and the
        // status line clears exactly as before.
        var outcome: GuideImportOutcome? = null
        // Where the live guide's schedule actually stops, published with the
        // outcome: a finished import that says nothing landed must also say the
        // guide itself is frozen (see GuideImportProgress.withStaleness).
        var guideEndsAtMs: Long? = null
        try {
            var lastError: Throwable? = null
            var imported = 0
            val outcomes = mutableListOf<GuideImportOutcome>()
            for (url in urls) {
                GuideImportProgress.startSource(imported + 1)
                try {
                    // Watched: a source that stops sending data is aborted and
                    // re-opened once on a fresh connection, and a second stall
                    // is thrown as EpgStalledException - a banner the viewer
                    // can act on instead of a silent twenty minutes at zero.
                    // The URL is handed over so the watchdog can tell "queued
                    // behind someone else's import" from "reading": a queued
                    // source publishes nothing, and its clock must not run
                    // while it waits.
                    outcomes += epgImportWatchdog.runWithStallRetry(url) { attempt ->
                        importGuideSource(
                            url = url,
                            freshConnection = attempt > 0,
                            // The button's whole point survives the watchdog's
                            // retry: a retried recovery press is still a press.
                            forceFresh = forceFresh
                        )
                    }
                    imported++
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    if (t is EpgStalledException) {
                        // Read by refreshIfNeeded(): the entry stops
                        // re-attempting this provider for the cooldown window.
                        markUpdated(KEY_EPG_LAST_STALL_AT)
                    }
                    lastError = t
                    Log.e(TAG, "GUIDE IMPORT FAILED source=${Redaction.url(url)}", Redaction.throwable(t))
                }
            }
            if (imported == 0) {
                lastError?.let { throw it }
                return
            }
            if (lastError != null) {
                // At least one source made it in; surface the failures as a
                // non-fatal warning but keep the successful guide data.
                reportFailure(
                    _guideError,
                    lastError,
                    prefix = "Some EPG sources failed: "
                )
                outcome = GuideImportOutcome.Failed(guideFailureLabel(lastError))
            } else {
                outcome = mergeOutcomes(outcomes)
            }
            // The freshness marker is stamped only when EVERY source imported.
            // It was unconditional ("at least one made it"), so one source
            // timing out left the failed source marked fresh for the whole
            // 12 h refresh window - its channels carried no programs until the
            // next scheduled pass, and `refreshIfNeeded` would not retry. A
            // partial run therefore leaves the marker alone: the next pass
            // re-imports the set, which costs a re-download but is the only way
            // the missing source gets another answer.
            if (lastError == null) {
                markUpdated(KEY_EPG_UPDATED_AT)
                markGuideFile()
            } else {
                Log.w(
                    TAG,
                    "GUIDE IMPORT PARTIAL: freshness marker left alone so the failed source retries"
                )
            }
            guideEndsAtMs = guideDataEndsAt()
            val playlist = _playlist.value
            clearGuideMemory(
                buildGuideSourceKey(
                    playlist = playlist,
                    guideUrls = urls,
                    refreshTick = _guideRefreshTick.value + 1
                ),
                // The import swapped its rows in atomically (see
                // XmltvImporter), so what is on screen is still a real guide -
                // keep it while the window is re-requested rather than
                // blanking every row.
                keepItems = true
            )
            _guideRefreshTick.value += 1
            requestInitialGuideWindow()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            reportFailure(_guideError, t)
            outcome = GuideImportOutcome.Failed(guideFailureLabel(t))
            guideEndsAtMs = guideDataEndsAt()
            Log.e(TAG, "GUIDE IMPORT FAILED", t)
        } finally {
            _isImportingGuide.value = false
            GuideImportProgress.finish(outcome, guideEndsAtMs)
        }
    }

    /**
     * The newest program end the live guide holds, or null when it holds none
     * (or the read itself fails - the status line then simply omits the fact).
     */
    private suspend fun guideDataEndsAt(): Long? =
        runCatching { repository.newestGuideProgramEndMs() }.getOrNull()

    /**
     * N sources' outcomes as the one line the status shows.
     *
     * A pass is one action to the viewer, so anything that actually imported
     * wins (with the total it stored); a pass where every source answered
     * `304` reports that instead, with the counts that satisfied them; and a
     * pass whose only answers were empty feeds says so - "unchanged" would be
     * a lie there, because the server's answer was a new, empty body.
     */
    private fun mergeOutcomes(outcomes: List<GuideImportOutcome>): GuideImportOutcome? {
        val imported = outcomes.filterIsInstance<GuideImportOutcome.Imported>()
        if (imported.isNotEmpty()) {
            return GuideImportOutcome.Imported(imported.sumOf { it.programs })
        }
        val unchanged = outcomes.filterIsInstance<GuideImportOutcome.Unchanged>()
        if (unchanged.isNotEmpty()) {
            return GuideImportOutcome.Unchanged(
                channels = unchanged.sumOf { it.channels },
                programsInWindow = unchanged.sumOf { it.programsInWindow }
            )
        }
        if (outcomes.any { it is GuideImportOutcome.EmptyFeedKeptLive }) {
            return GuideImportOutcome.EmptyFeedKeptLive
        }
        return null
    }

    /**
     * The failure in the viewer's words.
     *
     * The HTTP code when there is one - `Guide download failed: HTTP 404` is
     * the field case that started this (a provider answering 404 for any URL
     * but the exact signed one), and it is the only part of the provider's
     * answer the viewer can act on. Anything else keeps the message, so a stall
     * or a parse failure still reads as itself rather than as "failed".
     */
    private fun guideFailureLabel(error: Throwable): String {
        val message = error.message.orEmpty()
        val httpCode = Regex("HTTP (\\d{3})").find(message)?.value
        return when {
            httpCode != null -> "Guide download failed: $httpCode"
            message.isNotBlank() -> "Guide download failed: " + message.take(120)
            else -> "Guide download failed: " + error.javaClass.simpleName
        }
    }

    /**
     * Applies a freshly obtained playlist to the guide.
     *
     * Runs on [Dispatchers.Default] deliberately. The extra-playlist merge is
     * a network load plus a list concat, and the two fingerprint comparisons
     * below walk the whole channel list — a provider playlist is a 10k+ channel
     * corpus of strings. This used to run on the caller's dispatcher, which
     * through viewModelScope means the MAIN thread, so a cached restore during
     * app startup blocked frames for the duration of both passes.
     */
    private suspend fun applyPlaylist(newPlaylist: IptvPlaylist) =
        withContext(Dispatchers.Default) {
            // Merged here rather than at the call sites so the concat (and the
            // extra playlists' own loads) happen off the main thread too.
            val merged = mergeWithExtraPlaylists(newPlaylist)
            val guideUrls = allEpgUrls()
            // A background playlist refresh usually returns the same channels, and
            // resetting the guide for it re-ran the entire lineup query (snapshot
            // rebuild + every program batch) for rows that could not have differed.
            // Compare the slice the query actually reads before deciding.
            // The SAME fixed slice requestInitialGuideWindow() asks for - not
            // _guideChannelIds, which only ever GROWS as the viewer scrolls
            // (updateGuideChannels adds ids). A fingerprint stored at load time
            // (the initial 80 ids) could therefore never match one computed
            // later with a few hundred, so after any scrolling the "playlist
            // unchanged" fast path below was dead: every 6 h background refresh
            // paid the full guide reset and lineup re-query it exists to avoid.
            val fingerprintIds = merged.channels.asSequence()
                .filterNot { it.id in _hiddenChannelIds.value }
                .take(INITIAL_GUIDE_WINDOW_SIZE)
                .map { it.id }
                .toSet()
            val fingerprint = guideWindowFingerprint(
                sourceUrl = merged.sourceUrl,
                guideUrls = guideUrls,
                channelIds = fingerprintIds,
                channels = merged.channels
            )
            val guideStillValid = fingerprint != null && fingerprint == loadedGuideFingerprint

            _playlist.value = merged
            removeMissingHiddenChannelIds(merged)

            if (guideStillValid) {
                Log.w(TAG, "PLAYLIST APPLIED keeping loaded guide window (channels unchanged)")
                return@withContext
            }

            // Precompute the sourceKey using refreshTick + 1 BEFORE bumping the tick,
            // so loadedGuideSourceKey already matches once lineupSource re-evaluates.
            // This is what stops observeGuideRequest() from seeing a mismatch and
            // wiping guide state again right after we just populated it.
            clearGuideMemory(
                buildGuideSourceKey(
                    playlist = merged,
                    guideUrls = guideUrls,
                    refreshTick = _guideRefreshTick.value + 1
                ),
                // A background playlist reload lands here. The channels the
                // guide already shows are overwhelmingly the ones it will show
                // again, so blanking them first is what turned a silent refresh
                // into a visible "back to loading" stall.
                keepItems = true
            )
            _guideRefreshTick.value += 1
            requestInitialGuideWindow()
            loadedGuideFingerprint = guideWindowFingerprint(
                sourceUrl = merged.sourceUrl,
                guideUrls = guideUrls,
                // Same fixed slice as above, or the two reads can never agree.
                channelIds = fingerprintIds,
                channels = merged.channels
            )
        }

    private fun mergeGuideItems(lineup: List<IptvChannelWithEpg>) {
        val existing = _guideItemsByChannelId.value
        val updated = HashMap(existing)
        var changed = false
        lineup.forEach { item ->
            if (updated[item.channel.id] != item) {
                updated[item.channel.id] = item
                changed = true
            }
        }
        if (changed) _guideItemsByChannelId.value = updated
    }

    /**
     * Drops the guide's request bookkeeping, and - unless [keepItems] - the
     * loaded rows too.
     *
     * [keepItems] is for the REFRESH paths (a background playlist reload, an
     * EPG import finishing). Those re-request the window and overwrite the rows
     * as the new query lands, so dropping the rows first only blanked a guide
     * that was already correct: every row flipped back to "Loading guide…" for
     * as long as the rebuild took, which on a large guide is minutes - exactly
     * the "it is populated, then it goes back to loading for a minute" report.
     * The rows for channels the new playlist no longer carries simply stop
     * being drawn (mergePlaylistWithGuide reads the live playlist), so keeping
     * them can only ever show a program that is about to be replaced.
     *
     * The HARD paths (a changed source URL, a profile switch) still drop the
     * rows: there the old programs belong to a different guide entirely and
     * must not be shown for a moment.
     */
    private fun clearGuideMemory(sourceKey: String? = null, keepItems: Boolean = false) {
        if (!keepItems) _guideItemsByChannelId.value = emptyMap()
        _guideChannelIds.value = emptySet()
        _pendingGuideChannelIds.value = emptySet()
        // A new source, import or playlist can hold programs a run-out channel
        // did not have, so nothing stays written off as exhausted.
        exhaustedGuideChannelIds = emptySet()
        loadedGuideSourceKey = sourceKey
        // Nothing is loaded any more, so there is no fingerprint to compare a
        // subsequent playlist refresh against (null forces the full reset).
        loadedGuideFingerprint = null
    }

    private fun removeMissingHiddenChannelIds(playlist: IptvPlaylist) {
        val validChannelIds = playlist.channels.asSequence().map { it.id }.toSet()
        // Never prune against a playlist that carries no channels: an empty or
        // half-parsed load would otherwise delete every hidden channel locally
        // and push that deletion to the other devices.
        if (validChannelIds.isEmpty()) return
        val cleanedIds = _hiddenChannelIds.value.intersect(validChannelIds)
        if (cleanedIds != _hiddenChannelIds.value) {
            _hiddenChannelIds.value = cleanedIds
            saveHiddenChannelIds()
        }
    }

    private fun saveHiddenChannelIds() {
        prefs.edit().putStringSet(KEY_HIDDEN_CHANNEL_IDS, _hiddenChannelIds.value).apply()
        // Sync push: hiding channels must cross devices immediately instead
        // of riding the next unrelated iptv_config flush.
        val appCtx = app.applicationContext
        com.kennyb1201.kbstream.data.sync.SupabaseSync.enqueuePrefs(
            appCtx,
            com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder.KEY_IPTV,
            com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder.buildIptv(appCtx)
        )
    }

    private fun buildGuideSourceKey(
        playlist: IptvPlaylist?,
        guideUrls: List<String>,
        refreshTick: Int
    ): String =
        "${playlist?.sourceUrl}|${guideUrls.joinToString(",")}|$refreshTick"

    /**
     * True when the guide the app would read is not the one the freshness marker
     * was written about.
     *
     * A profile whose playlist is shared with another profile reads a guide
     * named after the playlist instead of after itself (see
     * [com.kennyb1201.kbstream.data.iptv.GuideFiles]), and the file that name
     * points at is created empty the first time it is opened — so without this
     * the marker would keep saying the guide is fresh while the guide has no
     * rows in it. The cost is the one-time re-import that sharing a guide file
     * is documented to cost, paid on the next visit to Live TV instead of up to
     * a refresh interval later; after that import the marker records the file it
     * wrote and this stops firing.
     */
    private fun guideFileMoved(): Boolean =
        !com.kennyb1201.kbstream.data.iptv.markerMatchesGuideFile(
            recordedName = prefs.getString(KEY_EPG_DB_NAME, null),
            activeName =
                com.kennyb1201.kbstream.data.iptv.db.IptvDatabase.activeFileName(app),
            profileId = com.kennyb1201.kbstream.data.sync.ProfileStorage.activeProfileId(app)
        )

    /**
     * Records the guide file [markUpdated] has just marked fresh, so a later
     * check can tell whether the marker is about the file being read
     * ([guideFileMoved]).
     */
    private fun markGuideFile() {
        prefs.edit()
            .putString(
                KEY_EPG_DB_NAME,
                com.kennyb1201.kbstream.data.iptv.db.IptvDatabase.activeFileName(app)
            )            .apply()
    }

    private fun isStale(key: String, maxAgeMs: Long): Boolean {
        val updatedAt = prefs.getLong(key, 0L)
        return updatedAt == 0L || System.currentTimeMillis() - updatedAt >= maxAgeMs
    }

    private fun markUpdated(key: String) {
        prefs.edit().putLong(key, System.currentTimeMillis()).apply()
    }

    private fun buildPlaylistOnlyLineup(playlist: IptvPlaylist): List<IptvChannelWithEpg> =
        playlist.channels.map(::playlistOnlyItem)

    private fun mergePlaylistWithGuide(
        playlistOnly: List<IptvChannelWithEpg>,
        guideByChannelId: Map<String, IptvChannelWithEpg>
    ): List<IptvChannelWithEpg> = playlistOnly.map { item ->
        guideByChannelId[item.channel.id] ?: item
    }

    private fun playlistOnlyItem(channel: IptvChannel): IptvChannelWithEpg =
        IptvChannelWithEpg(
            channel = channel,
            epgChannel = null,
            epgMatchType = EpgMatchType.NO_MATCH,
            now = null,
            next = null,
            upcoming = emptyList()
        )

    /**
     * A message for the SCREEN, not a log header.
     *
     * This used to lead with the exception's class name, so the stall chip the
     * viewer reads on the TV said "EpgStalledException: EPG download stalled: no
     * data for 3 minutes" — the failure's own text is already written for a
     * viewer, and the class name told them nothing they could act on. Only a
     * throwable with nothing to say falls back to its type.
     */
    private fun buildMessage(t: Throwable): String =
        t.message?.takeIf { it.isNotBlank() } ?: t::class.java.simpleName

    private fun saveInputs() {
        prefs.edit()
            .putString(KEY_PLAYLIST_URL, _playlistUrl.value)
            .putString(KEY_EPG_URL, _epgUrl.value)
            .putString(KEY_EXTRA_EPG_URLS, _extraEpgUrls.value)
            .putString(KEY_PLAYLIST_NAME, _playlistName.value)
            .putString(KEY_EXTRA_PLAYLIST_URLS, _extraPlaylistUrls.value)
            .apply()

        // Cross-device sync: share the IPTV source config.
        com.kennyb1201.kbstream.data.addon.AppContextHolder.appContext?.let { appContext ->
            com.kennyb1201.kbstream.data.sync.SupabaseSync.enqueuePrefs(
                appContext,
                com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder.KEY_IPTV,
                com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder.buildIptv(appContext)
            )
        }
    }

    private data class GuideRequest(
        val playlist: IptvPlaylist?,
        val guideUrls: List<String>,
        val refreshTick: Int,
        val clockTick: Int,
        val channelIds: Set<String>
    )

    private companion object {
        const val TAG = "IptvViewModel"

        /** Substrings that mark a channel/group as plausibly kid-focused. */
        const val PREFS_NAME = "iptv_prefs"
        const val KEY_PLAYLIST_URL = "playlist_url"
        const val KEY_EPG_URL = "epg_url"
        const val KEY_EXTRA_EPG_URLS = "extra_epg_urls"
        const val KEY_PLAYLIST_NAME = "playlist_name"
        const val KEY_EXTRA_PLAYLIST_URLS = "extra_playlist_urls"
        const val KEY_PLAYLIST_APPLIED_URL = "playlist_applied_url"
        const val KEY_HIDDEN_CHANNEL_IDS = "hidden_channel_ids"
        const val KEY_PLAYLIST_UPDATED_AT = "playlist_updated_at"
        const val KEY_EPG_UPDATED_AT = "epg_updated_at"

        /**
         * When the stall watchdog last gave up on a guide source. Written only
         * on a loud stall failure (see [EpgStalledException]) and read only by
         * [guideStalledRecently] - profile-scoped, in the same store as the
         * freshness marker it stands in for while the provider is broken.
         */
        const val KEY_EPG_LAST_STALL_AT = "epg_last_stall_at"

        /**
         * The guide FILE [KEY_EPG_UPDATED_AT] was written about. Read only
         * together with [guideFileMoved]: a marker about a different file than
         * the app would open is a guide that has not been re-imported under its
         * new name yet.
         */
        const val KEY_EPG_DB_NAME = "epg_db_name"
        const val STOP_TIMEOUT_MS = 5_000L
        const val INITIAL_GUIDE_WINDOW_SIZE = 80
        const val MAX_GUIDE_CHANNELS_PER_REQUEST = 80

        // Was 240 -- too small a shared budget for a full 80-channel guide
        // load. IptvRepository targets 12 programs/channel, so a full
        // batch wants up to 80*12=960; anything less than that causes
        // loadProgramsChunked() to exhaust its budget partway through the
        // channel list and skip programs for whichever channels come
        // later in the batch order, even though their own schedules have
        // plenty of programs in the window. This covers a full request
        // with headroom.
        const val VISIBLE_GUIDE_PROGRAM_LIMIT = 960

        // Both of these are the READ half of [EpgWindow]'s contract: what the
        // grid, the detail panel and the guide search ask for. They live
        // there now so the import window cannot drift away from them again —
        // it was storing six times this window, in every profile's own guide.
        const val GUIDE_PAST_WINDOW_MS = EpgWindow.READ_PAST_MS

        // The future half. 8 hours comfortably covers 4 back-to-back ~2-hour
        // blocks so the "coming up" row (which only shows 4) has something to
        // fill it regardless of how long each channel's program blocks run.
        // This was once 2 hours, which was too narrow for exactly that; going
        // the other way (storing far beyond it) is what the guide database
        // paid for.
        const val GUIDE_FUTURE_WINDOW_MS = EpgWindow.READ_FUTURE_MS
        const val PLAYLIST_REFRESH_MS = 6 * 60 * 60 * 1000L
        const val EPG_REFRESH_MS = 12 * 60 * 60 * 1000L

        // First catch-up refresh after the guide pipeline starts, and the
        // recurring cadence for recomputing now/next with a fresh clock.
        const val GUIDE_CLOCK_FIRST_REFRESH_MS = 15_000L
        const val GUIDE_CLOCK_REFRESH_INTERVAL_MS = 2 * 60 * 1000L

        /**
         * Guide freshness is re-checked every 15th clock tick, i.e. every
         * 30 minutes. The check is a pref read that no-ops until the guide is
         * actually older than [EPG_REFRESH_MS], and the worker normally
         * refreshes it first, so this fires roughly never - it is the safety
         * net for a process that stays up (and for a worker Android deferred).
         */
        const val GUIDE_STALENESS_CHECK_EVERY_TICKS = 15
    }
}
