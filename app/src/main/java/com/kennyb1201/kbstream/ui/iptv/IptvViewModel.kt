package com.kennyb1201.kbstream.ui.iptv

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.iptv.EpgMatchType
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

    private var loadJob: Job? = null
    private var importJob: Job? = null
    private var refreshJob: Job? = null

    private val lineupSource: StateFlow<List<IptvChannelWithEpg>> = combine(
        _playlist,
        _epgUrl,
        _guideRefreshTick,
        _isImportingGuide
    ) { currentPlaylist, guideUrl, _, isImportingGuide ->
        VisibleChannelsRequest(
            playlist = currentPlaylist,
            guideUrl = guideUrl.trim(),
            isImportingGuide = isImportingGuide
        )
    }.flatMapLatest { request ->
        val currentPlaylist = request.playlist
        val guideUrl = request.guideUrl

        when {
            currentPlaylist == null -> flowOf(emptyList())
            guideUrl.isBlank() || request.isImportingGuide -> {
                flowOf(buildPlaylistOnlyLineup(currentPlaylist))
            }
            else -> {
                val now = System.currentTimeMillis()
                repository.observeLineupWithGuide(
                    playlist = currentPlaylist,
                    epgUrl = guideUrl,
                    windowStart = now - GUIDE_PAST_WINDOW_MS,
                    windowEnd = now + GUIDE_FUTURE_WINDOW_MS
                )
            }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = emptyList()
    )

    val groups: StateFlow<List<String>> = lineupSource
        .map { lineup ->
            buildList {
                add(ALL_GROUPS)
                val seen = LinkedHashSet<String>()
                lineup.forEach { item ->
                    val group = item.channel.groupTitle?.trim().orEmpty()
                    if (group.isNotBlank() && seen.add(group)) add(group)
                }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = listOf(ALL_GROUPS)
        )

    val visibleChannels: StateFlow<List<IptvChannelWithEpg>> = combine(
        lineupSource,
        _selectedGroup
    ) { lineup, selectedGroup ->
        if (selectedGroup == ALL_GROUPS) lineup
        else lineup.filter { it.channel.groupTitle?.trim().orEmpty() == selectedGroup }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = emptyList()
    )

    init {
        restoreCachedPlaylist()
    }

    fun onPlaylistUrlChanged(value: String) {
        _playlistUrl.value = value
        saveInputs()
    }

    fun onEpgUrlChanged(value: String) {
        _epgUrl.value = value
        saveInputs()
        _guideRefreshTick.value += 1
    }

    fun onPlaylistNameChanged(value: String) {
        _playlistName.value = value
        saveInputs()
    }

    fun selectGroup(group: String) {
        _selectedGroup.value = group.ifBlank { ALL_GROUPS }
    }

    private fun restoreCachedPlaylist() {
        val url = _playlistUrl.value.trim()
        val name = _playlistName.value.trim().ifBlank { null }

        if (url.isBlank()) {
            Log.d(TAG, "CACHE RESTORE SKIPPED playlist URL is blank")
            return
        }

        _isLoading.value = true
        Log.d(TAG, "CACHE RESTORE START source=$url")

        viewModelScope.launch {
            try {
                val cachedPlaylist = repository.loadCachedPlaylist(
                    playlistUrl = url,
                    playlistName = name
                )

                if (cachedPlaylist != null) {
                    _playlist.value = cachedPlaylist
                    _selectedGroup.value = ALL_GROUPS
                    _guideRefreshTick.value += 1
                    Log.d(TAG, "CACHE RESTORE HIT channels=${cachedPlaylist.channels.size} source=$url")
                    refreshIfNeeded()
                } else {
                    Log.d(TAG, "CACHE RESTORE MISS source=$url")
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
            Log.d(TAG, "PLAYLIST LOAD REQUEST source=$url cached=$hasPlaylist")

            try {
                val loadedPlaylist = repository.loadPlaylist(
                    playlistUrl = url,
                    playlistName = name
                )
                _playlist.value = loadedPlaylist
                _selectedGroup.value = ALL_GROUPS
                _guideRefreshTick.value += 1
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

        val playlistNeedsRefresh = isStale(KEY_PLAYLIST_UPDATED_AT, PLAYLIST_REFRESH_MS)
        val guideNeedsRefresh = _epgUrl.value.isNotBlank() &&
            isStale(KEY_EPG_UPDATED_AT, EPG_REFRESH_MS)

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
        _guideRefreshTick.value += 1
        markUpdated(KEY_PLAYLIST_UPDATED_AT)
        Log.d(TAG, "BACKGROUND PLAYLIST REFRESH END channels=${refreshedPlaylist.channels.size}")
    }

    private suspend fun importGuideInternal(epgUrl: String) {
        if (epgUrl.isBlank()) return

        _isImportingGuide.value = true
        _guideError.value = null

        try {
            repository.importGuide(epgUrl)
            _selectedGroup.value = ALL_GROUPS
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

    private fun isStale(key: String, maxAgeMs: Long): Boolean {
        val updatedAt = prefs.getLong(key, 0L)
        return updatedAt == 0L || System.currentTimeMillis() - updatedAt >= maxAgeMs
    }

    private fun markUpdated(key: String) {
        prefs.edit().putLong(key, System.currentTimeMillis()).apply()
    }

    private fun buildPlaylistOnlyLineup(playlist: IptvPlaylist): List<IptvChannelWithEpg> =
        playlist.channels.map { channel ->
            IptvChannelWithEpg(
                channel = channel,
                epgChannel = null,
                epgMatchType = EpgMatchType.NO_MATCH,
                now = null,
                next = null,
                upcoming = emptyList()
            )
        }

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

    private data class VisibleChannelsRequest(
        val playlist: IptvPlaylist?,
        val guideUrl: String,
        val isImportingGuide: Boolean
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
        const val GUIDE_PAST_WINDOW_MS = 30 * 60 * 1000L
        const val GUIDE_FUTURE_WINDOW_MS = 2 * 60 * 60 * 1000L
        const val PLAYLIST_REFRESH_MS = 6 * 60 * 60 * 1000L
        const val EPG_REFRESH_MS = 12 * 60 * 60 * 1000L
    }
}
