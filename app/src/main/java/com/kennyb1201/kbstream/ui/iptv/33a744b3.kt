package com.kennyb1201.kbstream.ui.iptv

import android.app.Application
import android.content.Context
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
        val guide = request.guideUrl

        if (currentPlaylist == null) {
            flowOf(emptyList())
        } else if (guide.isBlank() || request.isImportingGuide) {
            flowOf(buildPlaylistOnlyLineup(currentPlaylist))
        } else {
            val now = System.currentTimeMillis()
            repository.observeLineupWithGuide(
                playlist = currentPlaylist,
                epgUrl = guide,
                windowStart = now - 60 * 60 * 1000L,
                windowEnd = now + 12 * 60 * 60 * 1000L
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    val groups: StateFlow<List<String>> = lineupSource
        .map { lineup ->
            buildList {
                add(ALL_GROUPS)
                val seen = LinkedHashSet<String>()
                lineup.forEach { item ->
                    val group = item.channel.groupTitle?.trim().orEmpty()
                    if (group.isNotBlank() && seen.add(group)) {
                        add(group)
                    }
                }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = listOf(ALL_GROUPS)
        )

    val visibleChannels: StateFlow<List<IptvChannelWithEpg>> = combine(
        lineupSource,
        _selectedGroup
    ) { lineup, selectedGroup ->
        if (selectedGroup == ALL_GROUPS) {
            lineup
        } else {
            lineup.filter { it.channel.groupTitle?.trim().orEmpty() == selectedGroup }
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    init {
        if (_playlistUrl.value.isNotBlank()) {
            load()
        }
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

    fun load() {
        val playlistUrl = _playlistUrl.value.trim()
        val playlistName = _playlistName.value.trim().ifBlank { null }
        val epgUrl = _epgUrl.value.trim()

        if (playlistUrl.isBlank()) {
            _error.value = "Playlist URL is required"
            return
        }

        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            try {
                _playlist.value = repository.loadPlaylist(playlistUrl, playlistName)
                _selectedGroup.value = ALL_GROUPS
                _guideRefreshTick.value += 1
                if (epgUrl.isNotBlank()) {
                    importGuide()
                }
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _error.value = buildMessage(t)
                _playlist.value = null
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun importGuide() {
        val epgUrl = _epgUrl.value.trim()
        if (epgUrl.isBlank()) {
            _guideError.value = "EPG URL is required"
            return
        }

        importJob?.cancel()
        importJob = viewModelScope.launch {
            _isImportingGuide.value = true
            _guideError.value = null
            try {
                repository.importGuide(epgUrl)
                _guideRefreshTick.value += 1
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                _guideError.value = buildMessage(t)
            } finally {
                _isImportingGuide.value = false
            }
        }
    }

    private fun buildPlaylistOnlyLineup(playlist: IptvPlaylist): List<IptvChannelWithEpg> {
        return playlist.channels.map {
            IptvChannelWithEpg(
                channel = it,
                epgChannel = null,
                epgMatchType = EpgMatchType.NO_MATCH,
                now = null,
                next = null,
                upcoming = emptyList()
            )
        }
    }

    private fun buildMessage(t: Throwable): String {
        return buildString {
            append(t::class.java.simpleName)
            t.message?.takeIf { it.isNotBlank() }?.let {
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
    }

    private data class VisibleChannelsRequest(
        val playlist: IptvPlaylist?,
        val guideUrl: String,
        val isImportingGuide: Boolean
    )

    private companion object {
        const val PREFS_NAME = "iptv_prefs"
        const val KEY_PLAYLIST_URL = "playlist_url"
        const val KEY_EPG_URL = "epg_url"
        const val KEY_PLAYLIST_NAME = "playlist_name"
        const val ALL_GROUPS = "All"
    }
}
