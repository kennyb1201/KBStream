package com.kennyb1201.kbstream.ui.iptv

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.iptv.IptvChannelWithEpg
import com.kennyb1201.kbstream.data.iptv.IptvLineup
import com.kennyb1201.kbstream.data.iptv.IptvRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class IptvViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = IptvRepository()
    private val prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _playlistUrl = MutableStateFlow(prefs.getString(KEY_PLAYLIST_URL, "").orEmpty())
    val playlistUrl: StateFlow<String> = _playlistUrl.asStateFlow()

    private val _epgUrl = MutableStateFlow(prefs.getString(KEY_EPG_URL, "").orEmpty())
    val epgUrl: StateFlow<String> = _epgUrl.asStateFlow()

    private val _playlistName = MutableStateFlow(prefs.getString(KEY_PLAYLIST_NAME, "").orEmpty())
    val playlistName: StateFlow<String> = _playlistName.asStateFlow()

    private val _lineup = MutableStateFlow<IptvLineup?>(null)
    val lineup: StateFlow<IptvLineup?> = _lineup.asStateFlow()

    private val _visibleChannels = MutableStateFlow<List<IptvChannelWithEpg>>(emptyList())
    val visibleChannels: StateFlow<List<IptvChannelWithEpg>> = _visibleChannels.asStateFlow()

    private val _groups = MutableStateFlow<List<String>>(listOf("All channels"))
    val groups: StateFlow<List<String>> = _groups.asStateFlow()

    private val _selectedGroup = MutableStateFlow("All channels")
    val selectedGroup: StateFlow<String> = _selectedGroup.asStateFlow()

    private val _selectedChannelIndex = MutableStateFlow(0)
    val selectedChannelIndex: StateFlow<Int> = _selectedChannelIndex.asStateFlow()

    private val _selectedChannel = MutableStateFlow<IptvChannelWithEpg?>(null)
    val selectedChannel: StateFlow<IptvChannelWithEpg?> = _selectedChannel.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var loadJob: Job? = null

    init {
        if (_playlistUrl.value.isNotBlank()) {
            load(
                playlistUrl = _playlistUrl.value,
                epgUrlOverride = _epgUrl.value,
                playlistName = _playlistName.value
            )
        }
    }

    fun onPlaylistUrlChanged(value: String) {
        _playlistUrl.value = value
        saveInputs()
    }

    fun onEpgUrlChanged(value: String) {
        _epgUrl.value = value
        saveInputs()
    }

    fun onPlaylistNameChanged(value: String) {
        _playlistName.value = value
        saveInputs()
    }

    fun load(
        playlistUrl: String = _playlistUrl.value,
        epgUrlOverride: String = _epgUrl.value,
        playlistName: String = _playlistName.value
    ) {
        val normalizedPlaylistUrl = playlistUrl.trim()
        val normalizedEpgUrl = epgUrlOverride.trim().ifBlank { "" }
        val normalizedPlaylistName = playlistName.trim().ifBlank { "" }

        if (normalizedPlaylistUrl.isBlank()) {
            _error.value = "Playlist URL is required"
            _lineup.value = null
            _visibleChannels.value = emptyList()
            _selectedChannel.value = null
            return
        }

        _playlistUrl.value = normalizedPlaylistUrl
        _epgUrl.value = normalizedEpgUrl
        _playlistName.value = normalizedPlaylistName
        saveInputs()

        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            try {
                val loadedLineup = repository.loadLineup(
                    playlistUrl = normalizedPlaylistUrl,
                    epgUrlOverride = normalizedEpgUrl.ifBlank { null },
                    playlistName = normalizedPlaylistName.ifBlank { null }
                )

                _lineup.value = loadedLineup

                val derivedGroups = buildList {
                    add("All channels")
                    addAll(
                        loadedLineup.channels
                            .mapNotNull { it.channel.groupTitle?.trim()?.takeIf(String::isNotBlank) }
                            .distinct()
                            .sortedBy { it.lowercase() }
                    )
                }

                _groups.value = derivedGroups
                _selectedGroup.value = "All channels"
                applyGroupFilter("All channels")
            } catch (t: Throwable) {
                _error.value = buildString {
                    append(t::class.java.simpleName)
                    t.message?.takeIf { it.isNotBlank() }?.let {
                        append(": ")
                        append(it)
                    }
                }
                _lineup.value = null
                _visibleChannels.value = emptyList()
                _selectedChannel.value = null
                _selectedChannelIndex.value = 0
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun refresh() {
        load(
            playlistUrl = _playlistUrl.value,
            epgUrlOverride = _epgUrl.value,
            playlistName = _playlistName.value
        )
    }

    fun selectGroup(group: String) {
        _selectedGroup.value = group
        applyGroupFilter(group)
    }

    fun selectChannel(index: Int) {
        val channels = _visibleChannels.value
        if (channels.isEmpty()) {
            _selectedChannelIndex.value = 0
            _selectedChannel.value = null
            return
        }

        val safeIndex = index.coerceIn(0, channels.lastIndex)
        if (_selectedChannelIndex.value == safeIndex && _selectedChannel.value?.channel?.id == channels[safeIndex].channel.id) {
            return
        }

        _selectedChannelIndex.value = safeIndex
        _selectedChannel.value = channels[safeIndex]
    }

    fun moveSelection(delta: Int) {
        selectChannel(_selectedChannelIndex.value + delta)
    }

    fun clearError() {
        _error.value = null
    }

    fun reset() {
        loadJob?.cancel()
        loadJob = null
        _lineup.value = null
        _visibleChannels.value = emptyList()
        _groups.value = listOf("All channels")
        _selectedGroup.value = "All channels"
        _selectedChannelIndex.value = 0
        _selectedChannel.value = null
        _isLoading.value = false
        _error.value = null
        _playlistUrl.value = ""
        _epgUrl.value = ""
        _playlistName.value = ""
        saveInputs()
    }

    private fun applyGroupFilter(group: String) {
        val source = _lineup.value?.channels.orEmpty()
        val filtered = if (group == "All channels") {
            source
        } else {
            source.filter { it.channel.groupTitle?.trim() == group }
        }

        _visibleChannels.value = filtered

        val currentSelectedId = _selectedChannel.value?.channel?.id
        val existingIndex = filtered.indexOfFirst { it.channel.id == currentSelectedId }
        val safeIndex = when {
            filtered.isEmpty() -> -1
            existingIndex >= 0 -> existingIndex
            else -> _selectedChannelIndex.value.coerceIn(0, filtered.lastIndex)
        }

        if (safeIndex >= 0) {
            _selectedChannelIndex.value = safeIndex
            _selectedChannel.value = filtered[safeIndex]
        } else {
            _selectedChannelIndex.value = 0
            _selectedChannel.value = null
        }
    }

    private fun saveInputs() {
        prefs.edit()
            .putString(KEY_PLAYLIST_URL, _playlistUrl.value)
            .putString(KEY_EPG_URL, _epgUrl.value)
            .putString(KEY_PLAYLIST_NAME, _playlistName.value)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "iptv_prefs"
        const val KEY_PLAYLIST_URL = "playlist_url"
        const val KEY_EPG_URL = "epg_url"
        const val KEY_PLAYLIST_NAME = "playlist_name"
    }
}
