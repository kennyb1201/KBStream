package com.kennyb1201.kbstream.ui.iptv

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.iptv.EpgMatchType
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

    private val _groups = MutableStateFlow<List<String>>(listOf(GROUP_ALL_CHANNELS))
    val groups: StateFlow<List<String>> = _groups.asStateFlow()

    private val _selectedGroup = MutableStateFlow(GROUP_ALL_CHANNELS)
    val selectedGroup: StateFlow<String> = _selectedGroup.asStateFlow()

    private val _selectedChannelIndex = MutableStateFlow(0)
    val selectedChannelIndex: StateFlow<Int> = _selectedChannelIndex.asStateFlow()

    private val _selectedChannel = MutableStateFlow<IptvChannelWithEpg?>(null)
    val selectedChannel: StateFlow<IptvChannelWithEpg?> = _selectedChannel.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _favoriteKeys = MutableStateFlow(loadFavoriteKeys())
    val favoriteKeys: StateFlow<Set<String>> = _favoriteKeys.asStateFlow()

    private val _recentChannelKeys = MutableStateFlow(loadRecentChannelKeys())
    val recentChannelKeys: StateFlow<List<String>> = _recentChannelKeys.asStateFlow()

    private val _epgDiagnostics = MutableStateFlow(EpgDiagnostics())
    val epgDiagnostics: StateFlow<EpgDiagnostics> = _epgDiagnostics.asStateFlow()

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
            _epgDiagnostics.value = EpgDiagnostics()
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

                val enrichedLineup = enrichLineup(loadedLineup)
                _lineup.value = enrichedLineup
                _epgDiagnostics.value = buildDiagnostics(enrichedLineup)

                _groups.value = buildGroups(enrichedLineup)
                _selectedGroup.value = GROUP_ALL_CHANNELS
                applyGroupFilter(GROUP_ALL_CHANNELS)
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
                _groups.value = listOf(GROUP_ALL_CHANNELS)
                _selectedGroup.value = GROUP_ALL_CHANNELS
                _epgDiagnostics.value = EpgDiagnostics()
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
        val selected = channels[safeIndex]
        if (_selectedChannelIndex.value == safeIndex && _selectedChannel.value?.channel?.id == selected.channel.id) {
            return
        }

        _selectedChannelIndex.value = safeIndex
        _selectedChannel.value = selected
        addRecentChannel(selected)
    }

    fun moveSelection(delta: Int) {
        selectChannel(_selectedChannelIndex.value + delta)
    }

    fun toggleFavorite(channel: IptvChannelWithEpg) {
        val updatedKeys = _favoriteKeys.value.toMutableSet().apply {
            if (!add(channel.channel.favoriteKey)) {
                remove(channel.channel.favoriteKey)
            }
        }

        _favoriteKeys.value = updatedKeys
        saveFavoriteKeys(updatedKeys)
        updateDerivedState()
    }

    fun clearError() {
        _error.value = null
    }

    fun reset() {
        loadJob?.cancel()
        loadJob = null
        _lineup.value = null
        _visibleChannels.value = emptyList()
        _groups.value = listOf(GROUP_ALL_CHANNELS)
        _selectedGroup.value = GROUP_ALL_CHANNELS
        _selectedChannelIndex.value = 0
        _selectedChannel.value = null
        _isLoading.value = false
        _error.value = null
        _epgDiagnostics.value = EpgDiagnostics()
        _playlistUrl.value = ""
        _epgUrl.value = ""
        _playlistName.value = ""
        saveInputs()
    }

    private fun applyGroupFilter(group: String) {
        val source = _lineup.value?.channels.orEmpty()
        val filtered = when (group) {
            GROUP_ALL_CHANNELS -> source
            GROUP_FAVORITES -> source.filter { it.isFavorite }
            GROUP_RECENT -> source.filter { it.isRecent }
            GROUP_UNMATCHED -> source.filter { it.epgMatchType == EpgMatchType.NO_MATCH }
            else -> source.filter { it.channel.groupTitle?.trim() == group }
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

    private fun updateDerivedState() {
        val currentLineup = _lineup.value ?: return
        val enrichedLineup = enrichLineup(currentLineup)
        _lineup.value = enrichedLineup
        _epgDiagnostics.value = buildDiagnostics(enrichedLineup)
        _groups.value = buildGroups(enrichedLineup)

        val selectedGroup = _selectedGroup.value.takeIf { it in _groups.value } ?: GROUP_ALL_CHANNELS
        _selectedGroup.value = selectedGroup
        applyGroupFilter(selectedGroup)
    }

    private fun enrichLineup(lineup: IptvLineup): IptvLineup {
        val favoriteKeys = _favoriteKeys.value
        val recentKeys = _recentChannelKeys.value.toSet()

        return lineup.copy(
            channels = lineup.channels.map { channelWithEpg ->
                channelWithEpg.copy(
                    isFavorite = channelWithEpg.channel.favoriteKey in favoriteKeys,
                    isRecent = channelWithEpg.channel.favoriteKey in recentKeys
                )
            }
        )
    }

    private fun buildGroups(lineup: IptvLineup): List<String> {
        return buildList {
            add(GROUP_ALL_CHANNELS)
            if (lineup.channels.any { it.isFavorite }) add(GROUP_FAVORITES)
            if (lineup.channels.any { it.isRecent }) add(GROUP_RECENT)
            if (lineup.channels.any { it.epgMatchType == EpgMatchType.NO_MATCH }) add(GROUP_UNMATCHED)
            addAll(
                lineup.channels
                    .mapNotNull { channelWithEpg ->
                        channelWithEpg.channel.groupTitle
                            ?.trim()
                            ?.takeIf(String::isNotBlank)
                    }
                    .distinct()
            )
        }
    }

    private fun buildDiagnostics(lineup: IptvLineup): EpgDiagnostics {
        val channels = lineup.channels
        return EpgDiagnostics(
            totalChannels = channels.size,
            favoriteChannels = channels.count { it.isFavorite },
            recentChannels = channels.count { it.isRecent },
            idMatches = channels.count { it.epgMatchType == EpgMatchType.ID_MATCH },
            nameMatches = channels.count { it.epgMatchType == EpgMatchType.NAME_MATCH },
            unmatchedChannels = channels.count { it.epgMatchType == EpgMatchType.NO_MATCH }
        )
    }

    private fun addRecentChannel(channel: IptvChannelWithEpg) {
        val key = channel.channel.favoriteKey
        val updated = buildList {
            add(key)
            addAll(_recentChannelKeys.value.filterNot { it == key })
        }.take(MAX_RECENT_CHANNELS)

        if (updated == _recentChannelKeys.value) return

        _recentChannelKeys.value = updated
        saveRecentChannelKeys(updated)
        updateDerivedState()
    }

    private fun saveInputs() {
        prefs.edit()
            .putString(KEY_PLAYLIST_URL, _playlistUrl.value)
            .putString(KEY_EPG_URL, _epgUrl.value)
            .putString(KEY_PLAYLIST_NAME, _playlistName.value)
            .apply()
    }

    private fun loadFavoriteKeys(): Set<String> {
        return prefs.getStringSet(KEY_FAVORITES, emptySet()).orEmpty()
    }

    private fun saveFavoriteKeys(keys: Set<String>) {
        prefs.edit()
            .putStringSet(KEY_FAVORITES, keys)
            .apply()
    }

    private fun loadRecentChannelKeys(): List<String> {
        return prefs.getString(KEY_RECENT_CHANNELS, "")
            .orEmpty()
            .split('|')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(MAX_RECENT_CHANNELS)
    }

    private fun saveRecentChannelKeys(keys: List<String>) {
        prefs.edit()
            .putString(KEY_RECENT_CHANNELS, keys.joinToString("|"))
            .apply()
    }

    data class EpgDiagnostics(
        val totalChannels: Int = 0,
        val favoriteChannels: Int = 0,
        val recentChannels: Int = 0,
        val idMatches: Int = 0,
        val nameMatches: Int = 0,
        val unmatchedChannels: Int = 0
    ) {
        val matchedChannels: Int
            get() = idMatches + nameMatches
    }

    private companion object {
        const val PREFS_NAME = "iptv_prefs"
        const val KEY_PLAYLIST_URL = "playlist_url"
        const val KEY_EPG_URL = "epg_url"
        const val KEY_PLAYLIST_NAME = "playlist_name"
        const val KEY_FAVORITES = "favorite_keys"
        const val KEY_RECENT_CHANNELS = "recent_channel_keys"
        const val GROUP_ALL_CHANNELS = "All channels"
        const val GROUP_FAVORITES = "Favorites"
        const val GROUP_RECENT = "Recent"
        const val GROUP_UNMATCHED = "Unmatched EPG"
        const val MAX_RECENT_CHANNELS = 12
    }
}
