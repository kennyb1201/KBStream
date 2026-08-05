package com.kennyb1201.kbstream.ui.home

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.watched.preloadWatchedKeys
import com.kennyb1201.kbstream.data.addon.AddonManager
import com.kennyb1201.kbstream.data.addon.AddonRepository
import com.kennyb1201.kbstream.data.addon.MetaPreview
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.history.WatchHistoryEntity
import com.kennyb1201.kbstream.data.simkl.SimklRepository
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.watched.WatchedStatusRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.LocalDate
import java.time.temporal.ChronoUnit

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
    val startPositionMs: Long = 0L,
    val recencyTimestamp: Long = 0L
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AddonRepository()
    private val addonManager = AddonManager(application)
    private val historyDao = WatchHistoryDatabase.getInstance(application).watchHistoryDao()
    private val simklRepository = SimklRepository(application)
    private val tmdbRepository = TmdbRepository()
    private val tmdbLookupSemaphore = Semaphore(TMDB_MAX_CONCURRENT_LOOKUPS)


    private val _rails = MutableStateFlow<List<Rail>>(emptyList())
    val rails: StateFlow<List<Rail>> = _rails.asStateFlow()

    private val _watchedKeys = MutableStateFlow<Set<String>>(emptySet())
    val watchedKeys: StateFlow<Set<String>> = _watchedKeys.asStateFlow()

    private val _upNext = MutableStateFlow<List<UpNextItem>>(emptyList())
    val upNext: StateFlow<List<UpNextItem>> = _upNext.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _refreshTrigger = MutableStateFlow(0)

    init {
        Log.e("HOME_VM", "HomeViewModel init")
        loadRails()
        observeUpNext()
    }

    fun refreshUpNext() {
        _refreshTrigger.value += 1
    }

    fun refreshWatchedStatusForCurrentRails() {
        refreshWatchedStatus(_rails.value)
    }

    fun watchedKey(id: String, type: String): String = "$type::$id"

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
                                    else -> "Resume"
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

                                coroutineScope {
                                    raw.map { item ->
                                        async { buildSimklUpNextItem(item) }
                                    }.awaitAll().filterNotNull()
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

    private suspend fun buildSimklUpNextItem(
        item: com.kennyb1201.kbstream.data.simkl.SimklContinueWatchingItem
    ): UpNextItem? {
        val navigationId = item.imdbId
        if (navigationId == null) {
            Log.e("HOME_UPNEXT", "Dropping simkl item with no imdb id, title=${item.title}")
            return null
        }

        var posterUrl = item.posterUrl
        var badge = badgeFromSimkl(item.upNextText, item.source)

        val isNewBadgeCandidate = item.mediaType == "series" && item.source == "watching"
        val needsTmdbLookup = posterUrl.isNullOrBlank() || isNewBadgeCandidate

        if (needsTmdbLookup) {
            val detail = try {
                tmdbLookupSemaphore.withPermit {
                    tmdbRepository.fetchEnrichedMetaCached(navigationId, item.mediaType)
                }
            } catch (e: Exception) {
                Log.e("HOME_UPNEXT", "tmdb lookup failed for ${item.title}: ${e.message}")
                null
            }

            if (posterUrl.isNullOrBlank()) {
                posterUrl = detail?.posterPath?.let { "${TmdbRepository.POSTER_BASE}$it" }
            }

            if (isNewBadgeCandidate) {
                val lastAired = detail?.lastEpisodeToAir
                val airedRecently = lastAired?.airDate?.let { isWithinDays(it, NEW_RELEASE_WINDOW_DAYS) } ?: false

                badge = if (airedRecently) {
                    if ((lastAired?.episodeNumber ?: 0) <= 1) {
                        UpNextBadge.NEW_SEASON
                    } else {
                        UpNextBadge.NEW_EPISODE
                    }
                } else {
                    UpNextBadge.NEXT_UP
                }
            }
        }

        return UpNextItem(
            id = "simkl:${item.id}",
            title = item.title,
            poster = posterUrl,
            badge = badge,
            subtitle = item.upNextText,
            progressPercent = item.progress
                ?.takeIf { it > 0f }
                ?.let { (it / 100f).coerceIn(0f, 1f) },
            streamUrl = null,
            parentId = navigationId,
            parentType = item.mediaType,
            season = null,
            episode = null,
            episodeStreamId = null,
            startPositionMs = 0L
        )
    }

    private fun isWithinDays(dateStr: String, days: Int): Boolean {
        return try {
            val date = LocalDate.parse(dateStr)
            val today = LocalDate.now()
            val diff = ChronoUnit.DAYS.between(date, today)
            diff in 0..days.toLong()
        } catch (e: Exception) {
            false
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
                refreshWatchedStatus(result)
            } catch (e: Exception) {
                _error.value = "Failed to load: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun refreshWatchedStatus(rails: List<Rail>) {
    viewModelScope.launch {
        try {
            val metas = rails.flatMap { it.items }.filter { it.id.isNotBlank() }
            if (metas.isEmpty()) {
                _watchedKeys.value = emptySet()
                return@launch
            }

            val keys = preloadWatchedKeys(
                items = metas,
                typeSelector = { it.type },
                idSelector = { it.id }
            )

            _watchedKeys.value = keys
        } catch (e: Exception) {
            Log.e("HOME_WATCHED", "refreshWatchedStatus failed: ${e.message}", e)
            _watchedKeys.value = emptySet()
        }
    }
}
}

    companion object {
        private const val NEW_RELEASE_WINDOW_DAYS = 7
        private const val TMDB_MAX_CONCURRENT_LOOKUPS = 5
    }
}
