package com.kennyb1201.kbstream.ui.iptv

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.iptv.EpgMatchType
import com.kennyb1201.kbstream.data.iptv.db.EpgProgramRow
import com.kennyb1201.kbstream.data.iptv.IptvChannel
import com.kennyb1201.kbstream.data.iptv.IptvChannelWithEpg
import com.kennyb1201.kbstream.data.iptv.IptvPlaylist
import com.kennyb1201.kbstream.data.iptv.IptvRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class IptvViewModel(private val app: Application) : AndroidViewModel(app) {

    private val repository = IptvRepository(app.applicationContext)
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
            val hits = runCatching { repository.searchPrograms(query) }.getOrDefault(emptyList())
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

    private var catchupJob: Job? = null

    /**
     * Loads the catch-up (DVR) list for [channel]. An empty result means
     * the channel carries no catch-up attributes or has no EPG history —
     * the UI hides the section rather than showing dead entries.
     */
    fun loadCatchupPrograms(channel: IptvChannel) {
        catchupJob?.cancel()
        catchupJob = viewModelScope.launch {
            _catchupPrograms.value = try {
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
                found
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                Log.w(TAG, "CATCHUP LOAD FAILED channel=${channel.id}: ${t.message}")
                emptyList()
            }
        }
    }

    private val _guideRefreshTick = MutableStateFlow(0)

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
    private var loadJob: Job? = null
    private var importJob: Job? = null
    private var refreshJob: Job? = null

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
        _isImportingGuide,
        _pendingGuideChannelIds
    ) { playlistAndGuides, refreshTick, importingGuide, channelIds ->
        val (currentPlaylist, guideUrls) = playlistAndGuides
        GuideRequest(
            playlist = currentPlaylist,
            guideUrls = guideUrls,
            refreshTick = refreshTick,
            isImportingGuide = importingGuide,
            channelIds = channelIds
        )
    }.flatMapLatest { request ->
        val currentPlaylist = request.playlist

        when {
            currentPlaylist == null -> flowOf(emptyList())
            request.guideUrls.isEmpty() -> flowOf(emptyList())
            request.channelIds.isEmpty() -> flowOf(emptyList())
            // NOTE: no isImportingGuide guard here. While a stale-EPG
            // background refresh runs, the previous import's programmes are
            // still in the DB — blanking the lineup for the whole import made
            // the guide show "no program data" for minutes on entry (it only
            // recovered when the import finished). Query the cached data
            // right away; importGuideInternal() bumps the refresh tick when
            // done, which re-runs this query against the fresh import.
            else -> observeGuideRequest(currentPlaylist, request)
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
        val profile = com.kennyb1201.kbstream.data.sync.ProfileManager.activeProfile.value
        if (profile?.kidsMaxAge == null || !profile.kidsLockLiveTv) return channels
        return channels.filter { ch ->
            val group = ch.channel.groupTitle?.trim().orEmpty()
            val haystack = "${ch.channel.displayName} $group".lowercase()
            KID_TV_SIGNALS.any { haystack.contains(it) }
        }
    }

    init {
        viewModelScope.launch {
            lineupSource.collect { lineup ->
                if (lineup.isNotEmpty()) {
                    mergeGuideItems(lineup)
                    _pendingGuideChannelIds.value = emptySet()
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
                    importJob?.cancel()
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
     * a channel whose query ran at 10:00 keeps claiming the 10:00 programme
     * is "on now" well past its end. Re-queuing the already-loaded channel
     * ids through _pendingGuideChannelIds makes the flow re-run on the same
     * channels with a fresh clock, and mergeGuideItems() only touches channels
     * whose now/next actually changed, so unchanged schedules cause no churn.
     *
     * Periodic while the VM is alive (subscribers stop within STOP_TIMEOUT_MS
     * of leaving the guide, and the VM dies with it) plus one shot on start
     * so returning to the guide after a while shows current programmes
     * immediately instead of the last session's snapshot.
     */
    private fun startGuideClockRefresh() {
        viewModelScope.launch {
            // First pass: catch up after returning to the screen. Small delay
            // so the initial pipeline (restore + initial window request) gets
            // going first and this rides along after it.
            delay(GUIDE_CLOCK_FIRST_REFRESH_MS)
            bumpGuideClock()

            while (isActive) {
                delay(GUIDE_CLOCK_REFRESH_INTERVAL_MS)
                bumpGuideClock()
            }
        }
    }

    private fun bumpGuideClock() {
        val queued = _guideChannelIds.value
        if (queued.isEmpty()) return
        // Re-issue the currently loaded channel set as a fresh batch. Even if
        // the set is unchanged, this re-runs the lineup query with a new
        // nowUtcMillis/window; mergeGuideItems() diffs per channel.
        _pendingGuideChannelIds.value = queued
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

    fun setChannelHidden(channelId: String, hidden: Boolean) {
        if (hidden) hideChannel(channelId) else unhideChannel(channelId)
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
        _pendingGuideChannelIds.value = newIds
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
        Log.w(TAG, "CACHE RESTORE START source=$url")
        viewModelScope.launch {
            try {
                val cachedPlaylist = repository.loadCachedPlaylist(url, name)
                if (cachedPlaylist != null) {
                    // Extras must survive app restarts too — otherwise the
                    // cached restore shows only the primary playlist until
                    // the user manually reloads.
                    applyPlaylist(mergeWithExtraPlaylists(cachedPlaylist))
                    Log.w(TAG, "CACHE RESTORE HIT channels=${cachedPlaylist.channels.size} source=$url")
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
                    Log.w(TAG, "CACHE RESTORE MISS source=$url — auto-loading playlist")
                    load()
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _error.value = buildMessage(t)
                Log.e(TAG, "CACHE RESTORE FAILED source=$url", t)
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
                val loadedPlaylist = repository.loadPlaylist(url, name)
                applyPlaylist(mergeWithExtraPlaylists(loadedPlaylist))
                markUpdated(KEY_PLAYLIST_UPDATED_AT)
                Log.d(TAG, "PLAYLIST LOAD SUCCESS channels=${loadedPlaylist.channels.size} source=$url")
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _error.value = buildMessage(t)
                Log.e(TAG, "PLAYLIST LOAD FAILED source=$url", t)
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
                Log.w(TAG, "EXTRA PLAYLIST SKIPPED source=$extraUrl error=${t.message}")
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
        importJob?.cancel()
        importJob = viewModelScope.launch { importGuideInternal(allEpgUrls().firstOrNull().orEmpty()) }
    }

    private fun refreshIfNeeded() {
        if (refreshJob?.isActive == true) return
        val playlistNeedsRefresh = isStale(KEY_PLAYLIST_UPDATED_AT, PLAYLIST_REFRESH_MS)
        val guideNeedsRefresh = allEpgUrls().isNotEmpty() &&
            isStale(KEY_EPG_UPDATED_AT, EPG_REFRESH_MS)
        if (!playlistNeedsRefresh && !guideNeedsRefresh) return
        refreshJob = viewModelScope.launch {
            if (playlistNeedsRefresh) {
                runCatching { refreshPlaylistInBackground() }.onFailure { error ->
                    if (error is CancellationException) throw error
                    _error.value = buildMessage(error)
                    Log.e(TAG, "BACKGROUND PLAYLIST REFRESH FAILED", error)
                }
            }
            if (guideNeedsRefresh) importGuideInternal(allEpgUrls().firstOrNull().orEmpty())
        }
    }

    private suspend fun refreshPlaylistInBackground() {
        val url = _playlistUrl.value.trim()
        val name = _playlistName.value.trim().ifBlank { null }
        if (url.isBlank() || _isImportingGuide.value) return
        val refreshedPlaylist = mergeWithExtraPlaylists(repository.loadPlaylist(url, name))
        applyPlaylist(refreshedPlaylist)
        markUpdated(KEY_PLAYLIST_UPDATED_AT)
        Log.d(TAG, "BACKGROUND PLAYLIST REFRESH END channels=${refreshedPlaylist.channels.size}")
    }

    private suspend fun importGuideInternal(epgUrl: String) {
        if (epgUrl.isBlank()) return
        _isImportingGuide.value = true
        _guideError.value = null
        val urls = allEpgUrls()
        try {
            var lastError: Throwable? = null
            var imported = 0
            for (url in urls) {
                try {
                    repository.importGuide(url)
                    imported++
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    lastError = t
                    Log.e(TAG, "GUIDE IMPORT FAILED source=$url", t)
                }
            }
            if (imported == 0) {
                lastError?.let { throw it }
                return
            }
            if (lastError != null) {
                // At least one source made it in; surface the failures as a
                // non-fatal warning but keep the successful guide data.
                _guideError.value = "Some EPG sources failed: " + buildMessage(lastError)
            }
            val playlist = _playlist.value
            clearGuideMemory(
                buildGuideSourceKey(
                    playlist = playlist,
                    guideUrls = urls,
                    refreshTick = _guideRefreshTick.value + 1
                )
            )
            _guideRefreshTick.value += 1
            requestInitialGuideWindow()
            markUpdated(KEY_EPG_UPDATED_AT)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            _guideError.value = buildMessage(t)
            Log.e(TAG, "GUIDE IMPORT FAILED", t)
        } finally {
            _isImportingGuide.value = false
        }
    }

    private fun applyPlaylist(newPlaylist: IptvPlaylist) {
        _playlist.value = newPlaylist
        removeMissingHiddenChannelIds(newPlaylist)
        // Precompute the sourceKey using refreshTick + 1 BEFORE bumping the tick,
        // so loadedGuideSourceKey already matches once lineupSource re-evaluates.
        // This is what stops observeGuideRequest() from seeing a mismatch and
        // wiping guide state again right after we just populated it.
        clearGuideMemory(
            buildGuideSourceKey(
                playlist = newPlaylist,
                guideUrls = allEpgUrls(),
                refreshTick = _guideRefreshTick.value + 1
            )
        )
        _guideRefreshTick.value += 1
        requestInitialGuideWindow()
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

    private fun clearGuideMemory(sourceKey: String? = null) {
        _guideItemsByChannelId.value = emptyMap()
        _guideChannelIds.value = emptySet()
        _pendingGuideChannelIds.value = emptySet()
        loadedGuideSourceKey = sourceKey
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

    private fun buildMessage(t: Throwable): String = buildString {
        append(t::class.java.simpleName)
        t.message?.takeIf { it.isNotBlank() }?.let { append(": ").append(it) }
    }

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
        val isImportingGuide: Boolean,
        val channelIds: Set<String>
    )

    private companion object {
        const val TAG = "IptvViewModel"

        /** Substrings that mark a channel/group as plausibly kid-focused. */
        val KID_TV_SIGNALS = listOf(
            "kid", "child", "cartoon", "nick", "disney", "junior", "jr",
            "baby", "toddler", "family", "boomerang", "poko", "sprout",
            "animation", "anime kids", "cbeebies", "cbbc", "boing",
            "milk", "minimax", "gulli", "toon", "peppa", "paw"
        )
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
        const val STOP_TIMEOUT_MS = 5_000L
        const val INITIAL_GUIDE_WINDOW_SIZE = 80
        const val MAX_GUIDE_CHANNELS_PER_REQUEST = 80

        // Was 240 -- too small a shared budget for a full 80-channel guide
        // load. IptvRepository targets 12 programmes/channel, so a full
        // batch wants up to 80*12=960; anything less than that causes
        // loadProgramsChunked() to exhaust its budget partway through the
        // channel list and skip programmes for whichever channels come
        // later in the batch order, even though their own schedules have
        // plenty of programmes in the window. This covers a full request
        // with headroom.
        const val VISIBLE_GUIDE_PROGRAM_LIMIT = 960

        const val GUIDE_PAST_WINDOW_MS = 30 * 60 * 1000L

        // Was 2 hours -- too narrow for channels with longer programme
        // blocks (movies, sports coverage) to ever have 4 *future* entries
        // fall inside the window, even though their schedule is just as
        // full further out. 8 hours comfortably covers 4 back-to-back
        // ~2-hour blocks so the "coming up" row (which only shows 4) has
        // something to fill it regardless of how long each channel's
        // programme blocks run.
        const val GUIDE_FUTURE_WINDOW_MS = 8 * 60 * 60 * 1000L
        const val PLAYLIST_REFRESH_MS = 6 * 60 * 60 * 1000L
        const val EPG_REFRESH_MS = 12 * 60 * 60 * 1000L

        // First catch-up refresh after the guide pipeline starts, and the
        // recurring cadence for recomputing now/next with a fresh clock.
        const val GUIDE_CLOCK_FIRST_REFRESH_MS = 15_000L
        const val GUIDE_CLOCK_REFRESH_INTERVAL_MS = 2 * 60 * 1000L
    }
}
