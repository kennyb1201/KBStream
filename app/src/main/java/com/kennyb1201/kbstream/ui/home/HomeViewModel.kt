package com.kennyb1201.kbstream.ui.home

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.addon.AddonManager
import com.kennyb1201.kbstream.data.addon.AddonRepository
import com.kennyb1201.kbstream.data.addon.MetaPreview
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.history.WatchHistoryEntity
import com.kennyb1201.kbstream.data.simkl.SimklRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

data class Rail(
    val addonName: String,
    val catalogName: String,
    val type: String,
    val items: List<MetaPreview>
)

enum class UpNextBadge {
    CONTINUE_WATCHING,
    NEXT_UP,
    NEW_EPISODE,
    NEW_SEASON
}

data class UpNextItem(
    val id: String,
    val title: String,
    val poster: String?,
    val badge: UpNextBadge,
    val subtitle: String? = null,
    val progressPercent: Float? = null,
    val streamUrl: String? = null,
    val parentId: String? = null,
    val parentType: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val episodeStreamId: String? = null,
    val startPositionMs: Long = 0L
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AddonRepository()
    private val addonManager = AddonManager(application)
    private val historyDao = WatchHistoryDatabase.getInstance(application).watchHistoryDao()
    private val simklRepository = SimklRepository(application)

    private val _rails = MutableStateFlow<List<Rail>>(emptyList())
    val rails: StateFlow<List<Rail>> = _rails.asStateFlow()

    private val _upNext = MutableStateFlow<List<UpNextItem>>(emptyList())
    val upNext: StateFlow<List<UpNextItem>> = _upNext.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // Bumped whenever something outside the local Room history changes that should
    // trigger an Up Next re-evaluation (e.g. Simkl auth completing, screen resuming).
    // observeRecent() alone only fires when the local watch_history table changes,
    // so without this, a fresh Simkl token is never picked up until local history
    // happens to change too.
    private val _refreshTrigger = MutableStateFlow(0)

    init {
        Log.e("HOME_VM", "HomeViewModel init")
        loadRails()
        observeUpNext()
    }

    fun refreshUpNext() {
        _refreshTrigger.value += 1
    }

    private fun observeUpNext() {
        viewModelScope.launch {
            combine(
                historyDao.observeRecent(),
                _refreshTrigger
            ) { history, _ -> history }
                .collect { history ->
                    try {
                        Log.e("HOME_UPNEXT", "observeRecent emitted history count = ${history.size}")

                        val localItems = history.map { entry ->
                            UpNextItem(
                                id = buildString {
                                    append("history:")
                                    append(entry.id)
                                    entry.season?.let { append(":s$it") }
                                    entry.episode?.let { append(":e$it") }
                                },
                                title = entry.name,
                                poster = entry.poster,
                                badge = UpNextBadge.CONTINUE_WATCHING,
                                subtitle = when {
                                    entry.season != null && entry.episode != null ->
                                        "Resume • S${entry.season}E${entry.episode}"
                                    else ->
                                        "Resume"
                                },
                                progressPercent = progressFromHistory(
                                    positionMs = entry.positionMs,
                                    durationMs = entry.durationMs
                                ),
                                streamUrl = entry.streamUrl,
                                parentId = entry.id,
                                parentType = entry.type,
                                season = entry.season,
                                episode = entry.episode,
                                episodeStreamId = entry.episodeStreamId,
                                startPositionMs = entry.positionMs
                            )
                        }

                        val simklItems = try {
                            if (simklRepository.isConfigured() && simklRepository.hasToken()) {
                                val raw = simklRepository.getContinueWatching()
                                Log.e("HOME_UPNEXT", "simkl raw count = ${raw.size}")

                                raw.map { item ->
                                    UpNextItem(
                                        id = "simkl:${item.id}",
                                        title = item.title,
                                        poster = item.posterUrl,
                                        badge = badgeFromSimkl(item.upNextText, item.source),
                                        subtitle = item.upNextText,
                                        progressPercent = item.progress
                                            ?.takeIf { it > 0f }
                                            ?.let { (it / 100f).coerceIn(0f, 1f) },
                                        streamUrl = null,
                                        parentId = item.id,
                                        parentType = item.mediaType,
                                        season = null,
                                        episode = null,
                                        episodeStreamId = null,
                                        startPositionMs = 0L
                                    )
                                }
                            } else {
                                emptyList()
                            }
                        } catch (e: Exception) {
                            Log.e("HOME_UPNEXT", "simkl load failed: ${e.message}", e)
                            emptyList()
                        }

                        val merged = (localItems + simklItems)
                            .distinctBy { dedupeKey(it) }
                            .sortedWith(
                                compareBy<UpNextItem> { badgePriority(it.badge) }
                                    .thenByDescending { upNextRecencyScore(it, history) }
                                    .thenBy { it.title.lowercase() }
                            )

                        Log.e("HOME_UPNEXT", "merged count = ${merged.size}")
                        _upNext.value = merged
                    } catch (e: Exception) {
                        Log.e("HOME_UPNEXT", "observeUpNext failed: ${e.message}", e)
                        _upNext.value = emptyList()
                    }
                }
        }
    }

    private fun badgeFromSimkl(
        upNextText: String?,
        source: String
    ): UpNextBadge {
        val text = upNextText.orEmpty().lowercase()

        return when {
            source == "playback" -> UpNextBadge.CONTINUE_WATCHING
            text.startsWith("new season") -> UpNextBadge.NEW_SEASON
            text.startsWith("next episode") -> UpNextBadge.NEW_EPISODE
            text.startsWith("up next") -> UpNextBadge.NEXT_UP
            else -> UpNextBadge.NEXT_UP
        }
    }

    private fun badgePriority(badge: UpNextBadge): Int = when (badge) {
        UpNextBadge.CONTINUE_WATCHING -> 0
        UpNextBadge.NEXT_UP -> 1
        UpNextBadge.NEW_EPISODE -> 2
        UpNextBadge.NEW_SEASON -> 3
    }

    private fun progressFromHistory(positionMs: Long, durationMs: Long): Float? {
        if (positionMs <= 0L || durationMs <= 0L) return null
        return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0.02f, 0.98f)
    }

    private fun upNextRecencyScore(
        item: UpNextItem,
        history: List<WatchHistoryEntity>
    ): Long {
        if (!item.id.startsWith("history:")) return 0L

        val rawId = item.id.removePrefix("history:")
        val match = history.firstOrNull { historyItem ->
            buildString {
                append(historyItem.id)
                historyItem.season?.let { append(":s$it") }
                historyItem.episode?.let { append(":e$it") }
            } == rawId
        }

        return match?.updatedAt ?: 0L
    }

    private fun dedupeKey(item: UpNextItem): String {
        return buildString {
            append(item.parentType ?: "unknown")
            append(":")
            append(item.title.trim().lowercase())
            item.season?.let {
                append(":s")
                append(it)
            }
            item.episode?.let {
                append(":e")
                append(it)
            }
        }
    }

    fun loadRails() {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null
            val result = mutableListOf<Rail>()

            try {
                val addons = addonManager.getInstalledAddons()
                for (addon in addons) {
                    if (!addon.resources.contains("catalog")) continue

                    val baseUrl = addon.manifestUrl.removeSuffix("/manifest.json")
                    for (catalog in addon.catalogs) {
                        try {
                            val metas = repository.getCatalog(baseUrl, catalog.type, catalog.id)
                            if (metas.isNotEmpty()) {
                                result.add(
                                    Rail(
                                        addonName = addon.name,
                                        catalogName = catalog.name,
                                        type = catalog.type,
                                        items = metas
                                    )
                                )
                            }
                        } catch (_: Exception) {
                        }
                    }
                }

                _rails.value = result
            } catch (e: Exception) {
                _error.value = "Failed to load: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
}
