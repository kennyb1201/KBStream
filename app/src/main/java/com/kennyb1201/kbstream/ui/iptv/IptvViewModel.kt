package com.kennyb1201.kbstream.ui.iptv

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.iptv.EpgMatchType
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

    private val _playlistUrl = MutableStateFlow(prefs.getString(KEY_PLAYLIST_URL, "").orEmpty())
    val playlistUrl: StateFlow<String> = _playlistUrl.asStateFlow()

    private val _epgUrl = MutableStateFlow(prefs.getString(KEY_EPG_URL, "").orEmpty())
    val epgUrl: StateFlow<String> = _epgUrl.asStateFlow()

    private val _playlistName = MutableStateFlow(prefs.getString(KEY_PLAYLIST_NAME, "").orEmpty())
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

    private val _guideRefreshTick = MutableStateFlow(0)
    private val _selectedGroup = MutableStateFlow(ALL_GROUPS)
    val selectedGroup: StateFlow<String> = _selectedGroup.asStateFlow()

    private val _guideWindow = MutableStateFlow(
        GuideWindow(
            firstIndex = 0,
            lastIndex = INITIAL_GUIDE_WINDOW_SIZE - 1
        )
    )

    private val _guideItemsByChannelId =
        MutableStateFlow<Map<String, IptvChannelWithEpg>>(emptyMap())

    private val loadedGuideWindows = mutableListOf<GuideWindow>()
    private var loadedGuideSourceKey: String? = null

    private var loadJob: Job? = null
    private var importJob: Job? = null
    private var refreshJob: Job? = null

    private val playlistOnlyLineup: StateFlow<List<IptvChannelWithEpg>> = _playlist
        .map { playlist ->
            playlist?.let(::buildPlaylistOnlyLineup).orEmpty()
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = emptyList()
        )

    private val lineupSource: StateFlow<List<IptvChannelWithEpg>> = combine(
        _playlist,
        _epgUrl,
        _guideRefreshTick,
        _isImportingGuide,
        _guideWindow
    ) { currentPlaylist, guideUrl, refreshTick, isImportingGuide, guideWindow ->
        GuideRequest(
            playlist = currentPlaylist,
            guideUrl = guideUrl.trim(),
            refreshTick = refreshTick,
            isImportingGuide = isImportingGuide,
            guideWindow = guideWindow
        )
    }.flatMapLatest { request ->
        val currentPlaylist = request.playlist
        val guideUrl = request.guideUrl

        when {
            currentPlaylist == null -> flowOf(emptyList())

            guideUrl.isBlank() || request.isImportingGuide -> {
                flowOf(emptyList())
            }

            else -> {
                val sourceKey = buildGuideSourceKey(
                    playlist = currentPlaylist,
                    guideUrl = guideUrl,
                    refreshTick = request.refreshTick
                )

                if (loadedGuideSourceKey != sourceKey) {
                    clearGuideMemory(sourceKey)
                }

                if (isWindowAlreadyLoaded(request.guideWindow)) {
                    flowOf(emptyList())
                } else {
                    val channelStart = request.guideWindow.firstIndex
                        .coerceIn(0, currentPlaylist.channels.size)

                    val channelEndExclusive = (request.guideWindow.lastIndex + 1)
                        .coerceIn(channelStart, currentPlaylist.channels.size)

                    val boundedChannels = currentPlaylist.channels.subList(
                        channelStart,
                        channelEndExclusive
                    )

                    if (boundedChannels.isEmpty()) {
                        flowOf(emptyList())
                    } else {
                        val now = System.currentTimeMillis()

                        repository.observeLineupWithGuide(
                            playlist = currentPlaylist.copy(channels = boundedChannels),
                            epgUrl = guideUrl,
                            windowStart = now - GUIDE_PAST_WINDOW_MS,
                            windowEnd = now + GUIDE_FUTURE_WINDOW_MS,
                            limit = VISIBLE_GUIDE_PROGRAM_LIMIT
                        )
                    }
                }
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = emptyList()
    )

    val groups: StateFlow<List<String>> = _playlist
        .map { playlist ->
            buildList {
                add(ALL_GROUPS)
                val seen = LinkedHashSet<String>()

                playlist?.channels?.forEach { channel ->
                    val group = channel.groupTitle?.trim().orEmpty()

                    if (group.isNotBlank() && seen.add(group)) {
                        add(group)
                    }
                }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = listOf(ALL_GROUPS)
        )

    val visibleChannels: StateFlow<List<IptvChannelWithEpg>> = combine(
        playlistOnlyLineup,
        _guideItemsByChannelId,
        _selectedGroup
    ) { playlistOnly, guideByChannelId, selectedGroup ->
        playlistOnly
            .asSequence()
            .filter { item ->
                selectedGroup == ALL_GROUPS ||
                    item.channel.groupTitle?.trim().orEmpty() == selectedGroup
            }
            .map { item ->
                guideByChannelId[item.channel.id] ?: item
            }
            .toList()
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
                    markGuideWindowLoaded(_guideWindow.value)
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

    fun selectGroup(group: String) {
        _selectedGroup.value = group.ifBlank { ALL_GROUPS }

        resetGuideWindow()
    }

    fun updateGuideWindow(
        firstVisibleIndex: Int,
        lastVisibleIndex: Int
    ) {
        if (firstVisibleIndex < 0 || lastVisibleIndex < firstVisibleIndex) return

        val firstIndex = (firstVisibleIndex - GUIDE_WINDOW_OVERSCAN)
            .coerceAtLeast(0)

        val lastIndex = lastVisibleIndex + GUIDE_WINDOW_OVERSCAN

        val nextWindow = GuideWindow(
            firstIndex = firstIndex,
            lastIndex = lastIndex
        )

        if (_guideWindow.value != nextWindow) {
            _guideWindow.value = nextWindow
        }
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
                val cachedPlaylist = repository.loadCachedPlaylist(
                    playlistUrl = url,
                    playlistName = name
                )

                if (cachedPlaylist != null) {
                    _playlist.value = cachedPlaylist
                    _selectedGroup.value = ALL_GROUPS
                    clearGuideMemory()
                    resetGuideWindow()
                    _guideRefreshTick.value += 1

                    Log.w(
                        TAG,
                        "CACHE RESTORE HIT channels=${cachedPlaylist.channels.size} source=$url"
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

        loadJob?.cancel()

        loadJob = viewModelScope.launch {
            val hasPlaylist = _playlist.value != null
            _isLoading.value = !hasPlaylist
            _error.value = null

            Log.w(TAG, "PLAYLIST LOAD REQUEST source=$url cached=$hasPlaylist")

            try {
                val loadedPlaylist = repository.loadPlaylist(
                    playlistUrl = url,
                    playlistName = name
                )

                _playlist.value = loadedPlaylist
                _selectedGroup.value = ALL_GROUPS
                clearGuideMemory()
                resetGuideWindow()
                _guideRefreshTick.value += 1
                markUpdated(KEY_PLAYLIST_UPDATED_AT)

                Log.d(
                    TAG,
                    "PLAYLIST LOAD SUCCESS channels=${loadedPlaylist.channels.size} source=$url"
                )
            } catch (t: Throwable) {
                if (t is CancellationException) throw t

                _error.value = buildMessage(t)
                Log.e(TAG, "PLAYLIST LOAD FAILED source=$url", t)

                if (!hasPlaylist) {
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

        importJob?.cancel()

        importJob = viewModelScope.launch {
            importGuideInternal(url)
        }
    }

    private fun refreshIfNeeded() {
        if (refreshJob?.isActive == true) return

        val playlistNeedsRefresh = isStale(
            key = KEY_PLAYLIST_UPDATED_AT,
            maxAgeMs = PLAYLIST_REFRESH_MS
        )

        val guideNeedsRefresh = _epgUrl.value.isNotBlank() &&
            isStale(
                key = KEY_EPG_UPDATED_AT,
                maxAgeMs = EPG_REFRESH_MS
            )

        if (!playlistNeedsRefresh && !guideNeedsRefresh) return

        refreshJob = viewModelScope.launch {
            if (playlistNeedsRefresh) {
                runCatching { refreshPlaylistInBackground() }
                    .onFailure { error ->
                        if (error !is CancellationException) {
                            _error.value = buildMessage(error)
                            Log.e(TAG, "BACKGROUND PLAYLIST REFRESH FAILED", error)
                        } else {
                            throw error
                        }
                    }
            }

            if (guideNeedsRefresh) {
                importGuideInternal(_epgUrl.value.trim())
            }
        }
    }

    private suspend fun refreshPlaylistInBackground() {
        val url = _playlistUrl.value.trim()
        val name = _playlistName.value.trim().ifBlank { null }

        if (url.isBlank()) return

        Log.d(TAG, "BACKGROUND PLAYLIST REFRESH START source=$url")

        val refreshedPlaylist = repository.loadPlaylist(url, name)

        _playlist.value = refreshedPlaylist
        _selectedGroup.value = ALL_GROUPS
        clearGuideMemory()
        resetGuideWindow()
        _guideRefreshTick.value += 1
        markUpdated(KEY_PLAYLIST_UPDATED_AT)

        Log.d(
            TAG,
            "BACKGROUND PLAYLIST REFRESH END channels=${refreshedPlaylist.channels.size}"
        )
    }

    private suspend fun importGuideInternal(epgUrl: String) {
        if (epgUrl.isBlank()) return

        _isImportingGuide.value = true
        _guideError.value = null

        try {
            repository.importGuide(epgUrl)
            _selectedGroup.value = ALL_GROUPS
            clearGuideMemory()
            resetGuideWindow()
            _guideRefreshTick.value += 1
            markUpdated(KEY_EPG_UPDATED_AT)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            _guideError.value = buildMessage(t)
            Log.e(TAG, "GUIDE IMPORT FAILED source=$epgUrl", t)
        } finally {
            _isImportingGuide.value = false
        }
    }

    private fun mergeGuideItems(lineup: List<IptvChannelWithEpg>) {
        _guideItemsByChannelId.value = buildMap {
            putAll(_guideItemsByChannelId.value)

            lineup.forEach { item ->
                put(item.channel.id, item)
            }
        }
    }

    private fun clearGuideMemory(sourceKey: String? = null) {
        _guideItemsByChannelId.value = emptyMap()
        loadedGuideWindows.clear()
        loadedGuideSourceKey = sourceKey
    }

    private fun isWindowAlreadyLoaded(window: GuideWindow): Boolean =
        loadedGuideWindows.any { loaded ->
            loaded.firstIndex <= window.firstIndex &&
                loaded.lastIndex >= window.lastIndex
        }

    private fun markGuideWindowLoaded(window: GuideWindow) {
        if (!isWindowAlreadyLoaded(window)) {
            loadedGuideWindows += window
        }
    }

    private fun resetGuideWindow() {
        _guideWindow.value = GuideWindow(
            firstIndex = 0,
            lastIndex = INITIAL_GUIDE_WINDOW_SIZE - 1
        )
    }

    private fun buildGuideSourceKey(
        playlist: IptvPlaylist,
        guideUrl: String,
        refreshTick: Int
    ): String =
        "${playlist.sourceUrl}|$guideUrl|$refreshTick"

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
    ): List<IptvChannelWithEpg> =
        playlist.channels.map(::playlistOnlyItem)

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

        t.message?.takeIf { it.isNotBlank() }?.let {
            append(": ")
            append(it)
        }
    }

    private fun saveInputs() {
        prefs.edit()
            .putString(KEY_PLAYLIST_URL, _playlistUrl.value)
            .putString(KEY_EPG_URL, _epgUrl.value)
            .putString(KEY_PLAYLIST_NAME, _playlistName.value)
            .apply()
    }

    private data class GuideRequest(
        val playlist: IptvPlaylist?,
        val guideUrl: String,
        val refreshTick: Int,
        val isImportingGuide: Boolean,
        val guideWindow: GuideWindow
    )

    private data class GuideWindow(
        val firstIndex: Int,
        val lastIndex: Int
    )

    private companion object {
        const val TAG = "IptvViewModel"

        const val PREFS_NAME = "iptv_prefs"
        const val KEY_PLAYLIST_URL = "playlist_url"
        const val KEY_EPG_URL = "epg_url"
        const val KEY_PLAYLIST_NAME = "playlist_name"
        const val KEY_PLAYLIST_UPDATED_AT = "playlist_updated_at"
        const val KEY_EPG_UPDATED_AT = "epg_updated_at"
        const val ALL_GROUPS = "All"

        const val STOP_TIMEOUT_MS = 5_000L

        const val INITIAL_GUIDE_WINDOW_SIZE = 80
        const val GUIDE_WINDOW_OVERSCAN = 40
        const val VISIBLE_GUIDE_PROGRAM_LIMIT = 240

        const val GUIDE_PAST_WINDOW_MS = 30 * 60 * 1000L
        const val GUIDE_FUTURE_WINDOW_MS = 2 * 60 * 60 * 1000L
        const val PLAYLIST_REFRESH_MS = 6 * 60 * 60 * 1000L
        const val EPG_REFRESH_MS = 12 * 60 * 60 * 1000L
    }
}
