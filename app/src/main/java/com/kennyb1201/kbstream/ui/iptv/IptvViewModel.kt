package com.kennyb1201.kbstream.ui.iptv

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.iptv.EpgMatchType
import com.kennyb1201.kbstream.data.iptv.EpgRefreshScheduler
import com.kennyb1201.kbstream.data.iptv.IptvChannel
import com.kennyb1201.kbstream.data.iptv.IptvChannelWithEpg
import com.kennyb1201.kbstream.data.iptv.IptvPlaylist
import com.kennyb1201.kbstream.data.iptv.IptvRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class IptvViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = IptvRepository(application.applicationContext)
    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _playlistUrl =
        MutableStateFlow(prefs.getString(KEY_PLAYLIST_URL, "").orEmpty())
    val playlistUrl: StateFlow<String> = _playlistUrl.asStateFlow()

    private val _epgUrl =
        MutableStateFlow(prefs.getString(KEY_EPG_URL, "").orEmpty())
    val epgUrl: StateFlow<String> = _epgUrl.asStateFlow()

    private val _playlistName =
        MutableStateFlow(prefs.getString(KEY_PLAYLIST_NAME, "").orEmpty())
    val playlistName: StateFlow<String> = _playlistName.asStateFlow()

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

    private val _guideRefreshTick = MutableStateFlow(0)
    private val _guideChannelIds = MutableStateFlow<Set<String>>(emptySet())
    private val _guideItemsByChannelId =
        MutableStateFlow<Map<String, IptvChannelWithEpg>>(emptyMap())

    private var loadedGuideSourceKey: String? = null
    private var loadJob: Job? = null
    private var importJob: Job? = null
    private var refreshJob: Job? = null

    private val playlistOnlyLineup = _playlist
        .map { it?.let(::buildPlaylistOnlyLineup).orEmpty() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = emptyList()
        )

    private val lineupSource = combine(
        _playlist,
        _epgUrl,
        _guideRefreshTick,
        _isImportingGuide,
        _guideChannelIds
    ) { currentPlaylist, guideUrl, refreshTick, importingGuide, channelIds ->
        GuideRequest(
            playlist = currentPlaylist,
            guideUrl = guideUrl.trim(),
            refreshTick = refreshTick,
            isImportingGuide = importingGuide,
            channelIds = channelIds
        )
    }.flatMapLatest { request ->
        val currentPlaylist = request.playlist

        when {
            currentPlaylist == null ||
                request.guideUrl.isBlank() ||
                request.isImportingGuide ||
                request.channelIds.isEmpty() -> {
                flowOf(emptyList())
            }

            else -> {
                val sourceKey = buildGuideSourceKey(
                    playlist = currentPlaylist,
                    guideUrl = request.guideUrl,
                    refreshTick = request.refreshTick
                )

                if (loadedGuideSourceKey != sourceKey) {
                    clearGuideMemory(sourceKey)
                }

                val channelsById = currentPlaylist.channels.associateBy { it.id }
                val channelsToLoad = request.channelIds
                    .asSequence()
                    .mapNotNull(channelsById::get)
                    .take(MAX_GUIDE_CHANNEL_REQUEST_SIZE)
                    .toList()

                Log.w(
                    TAG,
                    "GUIDE QUERY requested=${request.channelIds.size} " +
                        "channels=${channelsToLoad.size}"
                )

                if (channelsToLoad.isEmpty()) {
                    flowOf(emptyList())
                } else {
                    val now = System.currentTimeMillis()

                    repository.observeLineupWithGuide(
                        playlist = currentPlaylist.copy(channels = channelsToLoad),
                        epgUrl = request.guideUrl,
                        windowStart = now - GUIDE_PAST_WINDOW_MS,
                        windowEnd = now + GUIDE_FUTURE_WINDOW_MS,
                        limit = VISIBLE_GUIDE_PROGRAM_LIMIT
                    )
                }
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = emptyList()
    )

    val allChannels = combine(
        playlistOnlyLineup,
        _guideItemsByChannelId
    ) { playlistOnly, guideByChannelId ->
        playlistOnly.map { item ->
            guideByChannelId[item.channel.id]?.let { match ->
                item.copy(
                    epgChannel = match.epgChannel,
                    epgMatchType = match.epgMatchType,
                    now = match.now,
                    next = match.next,
                    upcoming = match.upcoming
                )
            } ?: item
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = emptyList()
    )

    val visibleChannels = combine(
        allChannels,
        _hiddenChannelIds
    ) { channels, hiddenIds ->
        channels.filterNot { it.channel.id in hiddenIds }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = emptyList()
    )

    init {
        viewModelScope.launch {
            lineupSource.collect { lineup ->
                if (lineup.isNotEmpty()) {
                    mergeGuideItems(lineup)
                }
            }
        }

        restoreCachedPlaylist()
    }

    fun onPlaylistUrlChanged(value: String) {
        _playlistUrl.value = value
        saveInputs()
    }

    fun onEpgUrlChanged(value: String) {
        _epgUrl.value = value
        saveInputs()
        clearGuideMemory()
        _guideRefreshTick.value += 1
    }

    fun onPlaylistNameChanged(value: String) {
        _playlistName.value = value
        saveInputs()
    }

    fun hideChannel(channelId: String) {
        if (channelId.isBlank() || channelId in _hiddenChannelIds.value) return

        _hiddenChannelIds.value += channelId
        saveHiddenChannelIds()

        _guideChannelIds.value -= channelId
        _guideItemsByChannelId.value -= channelId
    }

    fun unhideChannel(channelId: String) {
        if (channelId.isBlank() || channelId !in _hiddenChannelIds.value) return

        _hiddenChannelIds.value -= channelId
        saveHiddenChannelIds()
    }

    fun setChannelHidden(channelId: String, hidden: Boolean) {
        if (hidden) {
            hideChannel(channelId)
        } else {
            unhideChannel(channelId)
        }
    }

    fun unhideAllChannels() {
        if (_hiddenChannelIds.value.isEmpty()) return

        _hiddenChannelIds.value = emptySet()
        saveHiddenChannelIds()
    }

    fun updateGuideChannels(visibleChannelIds: List<String>) {
        if (visibleChannelIds.isEmpty()) return

        val playlistChannels = _playlist.value?.channels.orEmpty()
        if (playlistChannels.isEmpty()) return

        val hiddenIds = _hiddenChannelIds.value
        val validIds = playlistChannels
            .asSequence()
            .map { it.id }
            .filter { it !in hiddenIds }
            .toHashSet()

        val visibleIds = visibleChannelIds
            .asSequence()
            .filter { it in validIds }
            .distinct()
            .toList()

        if (visibleIds.isEmpty()) return

        val visibleIdSet = visibleIds.toHashSet()
        val firstVisibleIndex = playlistChannels.indexOfFirst { it.id in visibleIdSet }
        val lastVisibleIndex = playlistChannels.indexOfLast { it.id in visibleIdSet }

        val nearbyIds = if (firstVisibleIndex >= 0 && lastVisibleIndex >= 0) {
            val startIndex = (firstVisibleIndex - GUIDE_PREFETCH_BEFORE_COUNT).coerceAtLeast(0)
            val endExclusive = (lastVisibleIndex + 1 + GUIDE_PREFETCH_AFTER_COUNT)
                .coerceAtMost(playlistChannels.size)

            playlistChannels
                .subList(startIndex, endExclusive)
                .asSequence()
                .filter { it.id !in hiddenIds }
                .map { it.id }
                .distinct()
                .toList()
        } else {
            visibleIds
        }

        val requestedIds = nearbyIds
            .take(MAX_GUIDE_CHANNEL_REQUEST_SIZE)
            .toSet()

        if (requestedIds == _guideChannelIds.value) return

        _guideChannelIds.value = requestedIds

        Log.d(
            TAG,
            "GUIDE CHANNELS WINDOW size=${requestedIds.size} " +
                "visible=${visibleIds.size} firstIndex=$firstVisibleIndex " +
                "lastIndex=$lastVisibleIndex"
        )
    }

    private fun requestInitialGuideWindow() {
        val hiddenIds = _hiddenChannelIds.value

        val initialIds = _playlist.value
            ?.channels
            ?.asSequence()
            ?.filterNot { it.id in hiddenIds }
            ?.take(INITIAL_GUIDE_WINDOW_SIZE)
            ?.map { it.id }
            ?.toList()
            .orEmpty()

        updateGuideChannels(initialIds)
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
                val cached = repository.loadCachedPlaylist(url, name)

                if (cached != null) {
                    _playlist.value = cached
                    removeMissingHiddenChannelIds(cached)
                    clearGuideMemory()
                    _guideRefreshTick.value += 1
                    requestInitialGuideWindow()

                    Log.w(
                        TAG,
                        "CACHE RESTORE HIT channels=${cached.channels.size} source=$url"
                    )

                    refreshIfNeeded()
                } else {
                    Log.w(TAG, "CACHE RESTORE MISS source=$url")
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
            _error.value =
                "EPG import is in progress. Please wait before loading the playlist."
            return
        }

        loadJob?.cancel()

        loadJob = viewModelScope.launch {
            val hadPlaylist = _playlist.value != null

            _isLoading.value = !hadPlaylist
            _error.value = null

            try {
                val loaded = repository.loadPlaylist(url, name)

                _playlist.value = loaded
                removeMissingHiddenChannelIds(loaded)
                clearGuideMemory()
                _guideRefreshTick.value += 1
                requestInitialGuideWindow()
                markUpdated(KEY_PLAYLIST_UPDATED_AT)

                Log.d(
                    TAG,
                    "PLAYLIST LOAD SUCCESS channels=${loaded.channels.size} source=$url"
                )
            } catch (t: Throwable) {
                if (t is CancellationException) throw t

                _error.value = buildMessage(t)
                Log.e(TAG, "PLAYLIST LOAD FAILED source=$url", t)

                if (!hadPlaylist) {
                    _playlist.value = null
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun importGuide() {
        val url = _epgUrl.value.trim()

        if (url.isBlank()) {
            _guideError.value = "EPG URL is required"
            return
        }

        if (_isLoading.value) {
            _guideError.value =
                "Playlist loading is in progress. Please wait before importing EPG."
            return
        }

        importJob?.cancel()

        importJob = viewModelScope.launch {
            importGuideInternal(url)
        }
    }

    private fun refreshIfNeeded() {
        if (refreshJob?.isActive == true) return

        val playlistNeeds =
            isStale(KEY_PLAYLIST_UPDATED_AT, PLAYLIST_REFRESH_MS)

        val guideNeeds =
            _epgUrl.value.isNotBlank() &&
                isStale(KEY_EPG_UPDATED_AT, EPG_REFRESH_MS)

        if (!playlistNeeds && !guideNeeds) return

        refreshJob = viewModelScope.launch {
            if (playlistNeeds) {
                runCatching {
                    refreshPlaylistInBackground()
                }.onFailure {
                    if (it is CancellationException) throw it

                    _error.value = buildMessage(it)
                    Log.e(TAG, "BACKGROUND PLAYLIST REFRESH FAILED", it)
                }
            }

            if (guideNeeds) {
                importGuideInternal(_epgUrl.value.trim())
            }
        }
    }

    private suspend fun refreshPlaylistInBackground() {
        val url = _playlistUrl.value.trim()
        val name = _playlistName.value.trim().ifBlank { null }

        if (url.isBlank() || _isImportingGuide.value) return

        val refreshed = repository.loadPlaylist(url, name)

        _playlist.value = refreshed
        removeMissingHiddenChannelIds(refreshed)
        clearGuideMemory()
        _guideRefreshTick.value += 1
        requestInitialGuideWindow()
        markUpdated(KEY_PLAYLIST_UPDATED_AT)
    }

    private suspend fun importGuideInternal(url: String) {
        if (url.isBlank()) return

        _isImportingGuide.value = true
        _guideError.value = null

        try {
            repository.importGuide(url)

            clearGuideMemory()
            _guideRefreshTick.value += 1
            requestInitialGuideWindow()
            markUpdated(KEY_EPG_UPDATED_AT)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t

            _guideError.value = buildMessage(t)
            Log.e(TAG, "GUIDE IMPORT FAILED source=$url", t)
        } finally {
            _isImportingGuide.value = false
        }
    }

    private fun mergeGuideItems(lineup: List<IptvChannelWithEpg>) {
        _guideItemsByChannelId.value += lineup.associateBy { it.channel.id }
    }

    private fun clearGuideMemory(sourceKey: String? = null) {
        _guideItemsByChannelId.value = emptyMap()
        _guideChannelIds.value = emptySet()
        loadedGuideSourceKey = sourceKey
    }

    private fun removeMissingHiddenChannelIds(playlist: IptvPlaylist) {
        val validIds = playlist.channels.asSequence().map { it.id }.toSet()
        val cleanedIds = _hiddenChannelIds.value.intersect(validIds)

        if (cleanedIds != _hiddenChannelIds.value) {
            _hiddenChannelIds.value = cleanedIds
            saveHiddenChannelIds()
        }
    }

    private fun saveHiddenChannelIds() {
        prefs.edit()
            .putStringSet(KEY_HIDDEN_CHANNEL_IDS, _hiddenChannelIds.value)
            .apply()
    }

    private fun buildGuideSourceKey(
        playlist: IptvPlaylist,
        guideUrl: String,
        refreshTick: Int
    ): String = "${playlist.sourceUrl}|$guideUrl|$refreshTick"

    private fun isStale(key: String, maxAgeMs: Long): Boolean {
        val updatedAt = prefs.getLong(key, 0L)

        return updatedAt == 0L ||
            System.currentTimeMillis() - updatedAt >= maxAgeMs
    }

    private fun markUpdated(key: String) {
        prefs.edit()
            .putLong(key, System.currentTimeMillis())
            .apply()
    }

    private fun buildPlaylistOnlyLineup(
        playlist: IptvPlaylist
    ): List<IptvChannelWithEpg> = playlist.channels.map(::playlistOnlyItem)

    private fun playlistOnlyItem(channel: IptvChannel): IptvChannelWithEpg {
        return IptvChannelWithEpg(
            channel = channel,
            epgChannel = null,
            epgMatchType = EpgMatchType.NO_MATCH,
            isFavorite = false,
            isRecent = false,
            now = null,
            next = null,
            upcoming = emptyList()
        )
    }

    private fun buildMessage(t: Throwable): String {
        return buildString {
            append(t::class.java.simpleName)

            t.message
                ?.takeIf { it.isNotBlank() }
                ?.let {
                    append(": ")
                    append(it)
                }
        }
    }

    private fun saveInputs() {
        prefs.edit()
            .putString(KEY_PLAYLIST_URL, _playlistUrl.value)
            .putString(KEY_EPG_URL, _epgUrl.value)
            .putString(KEY_PLAYLIST_NAME, _playlistName.value)
            .apply()

        if (_epgUrl.value.isNotBlank()) {
            EpgRefreshScheduler.schedule(getApplication())
        }
    }

    private data class GuideRequest(
        val playlist: IptvPlaylist?,
        val guideUrl: String,
        val refreshTick: Int,
        val isImportingGuide: Boolean,
        val channelIds: Set<String>
    )

    private companion object {
        const val TAG = "IptvViewModel"

        const val PREFS_NAME = "iptv_prefs"
        const val KEY_PLAYLIST_URL = "playlist_url"
        const val KEY_EPG_URL = "epg_url"
        const val KEY_PLAYLIST_NAME = "playlist_name"
        const val KEY_HIDDEN_CHANNEL_IDS = "hidden_channel_ids"
        const val KEY_PLAYLIST_UPDATED_AT = "playlist_updated_at"
        const val KEY_EPG_UPDATED_AT = "epg_updated_at"

        const val STOP_TIMEOUT_MS = 5_000L

        const val INITIAL_GUIDE_WINDOW_SIZE = 24
        const val GUIDE_PREFETCH_BEFORE_COUNT = 6
        const val GUIDE_PREFETCH_AFTER_COUNT = 18
        const val MAX_GUIDE_CHANNEL_REQUEST_SIZE = 32
        const val VISIBLE_GUIDE_PROGRAM_LIMIT = 240

        const val GUIDE_PAST_WINDOW_MS = 30 * 60 * 1000L
        const val GUIDE_FUTURE_WINDOW_MS = 2 * 60 * 60 * 1000L

        const val PLAYLIST_REFRESH_MS = 6 * 60 * 60 * 1000L
        const val EPG_REFRESH_MS = 12 * 60 * 60 * 1000L
    }
}
