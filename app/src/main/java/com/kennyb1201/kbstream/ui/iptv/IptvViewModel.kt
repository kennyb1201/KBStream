package com.kennyb1201.kbstream.ui.iptv

import android.app.Application
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

    private val _playlistUrl = MutableStateFlow("")
    val playlistUrl: StateFlow<String> = _playlistUrl.asStateFlow()

    private val _epgUrl = MutableStateFlow("")
    val epgUrl: StateFlow<String> = _epgUrl.asStateFlow()

    private val _playlistName = MutableStateFlow("")
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

    fun onPlaylistUrlChanged(value: String) {
        _playlistUrl.value = value
    }

    fun onEpgUrlChanged(value: String) {
        _epgUrl.value = value
    }

    fun onPlaylistNameChanged(value: String) {
        _playlistName.value = value
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
                _error.value = t.message ?: "Failed to load playlist"
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
    }

    private fun applyGroupFilter(group: String) {
        val source = _lineup.value?.channels.orEmpty()
        val filtered = if (group == "All channels") {
            source
        } else {
            source.filter { it.channel.groupTitle?.trim() == group }
        }

        _visibleChannels.value = filtered
        _selectedChannelIndex.value = 0
        _selectedChannel.value = filtered.firstOrNull()
    }
}
