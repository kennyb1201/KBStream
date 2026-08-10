package com.kennyb1201.kbstream.ui.home

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.addon.AddonManager
import com.kennyb1201.kbstream.data.addon.AddonRepository
import com.kennyb1201.kbstream.data.addon.MetaPreview
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.simkl.SimklContinueWatchingItem
import com.kennyb1201.kbstream.data.simkl.SimklRepository
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.watched.WatchedEpisodeState
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
import java.time.OffsetDateTime
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

private data class ResolvedHomeSeriesTarget(
    val season: Int,
    val episode: Int,
    val streamId: String? = null,
    val startPositionMs: Long = 0L,
    val isResume: Boolean = false,
    val airDate: String? = null
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = AddonRepository()
    private val addonManager = AddonManager(application)
    private val historyDao = WatchHistoryDatabase.getInstance(application).watchHistoryDao()
    private val simklRepository = SimklRepository(application)
    private val tmdbRepository = TmdbRepository(application)
    private val watchedStatusRepository = WatchedStatusRepository(application)
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

    private val simklWatchedEpisodesByShow = mutableMapOf<String, Set<Pair<Int, Int>>>()
    private val watchedEpisodeKeysByShow = mutableMapOf<String, Set<String>>()

    init {
        Log.e("HOME_VM", "HomeViewModel init")
        loadRails()
        observeUpNext()
    }

    fun refreshUpNext() {
        simklWatchedEpisodesByShow.clear()
        watchedEpisodeKeysByShow.clear()
        _refreshTrigger.value += 1
    }

    fun refreshWatchedStatusForCurrentRails() {
        refreshWatchedStatus(_rails.value)
    }

    fun watchedKey(id: String, type: String): String {
        val normalizedType = when (type.lowercase()) {
            "movie" -> "movie"
            "series", "show", "tv" -> "series"
            else -> type.lowercase()
        }
        return "$normalizedType::$id"
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
                            val isEpisodePlayback = entry.season != null && entry.episode != null

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
                                subtitle = if (isEpisodePlayback) {
                                    "Resume • ${formatSeasonEpisode(entry.season, entry.episode)}"
                                } else {
                                    "Resume"
                                },
                                progressPercent = progressFromHistory(
                                    positionMs = entry.positionMs,
                                    durationMs = entry.durationMs
                                ),
                                streamUrl = entry.streamUrl,
                                parentId = entry.parentId.ifBlank { entry.id },
                                parentType = entry.type,
                                season = entry.season,
                                episode = entry.episode,
                                episodeStreamId = entry.episodeStreamId,
                                startPositionMs = entry.positionMs,
                                recencyTimestamp = entry.updatedAt
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

val merged = dedupeAndSortUpNext(localItems + simklItems)

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
        item: SimklContinueWatchingItem
    ): UpNextItem? {
        val navigationId = item.imdbId
        if (navigationId == null) {
            Log.e(
                "HOME_UPNEXT",
                "Dropping simkl item with no imdb id, title=${item.title}"
            )
            return null
        }

        var posterUrl = item.posterUrl
        val recencyTimestamp = parseTimestampMillis(item.lastWatchedAt)
        var badge = initialBadgeFromSimkl(item)
        var subtitle = buildSimklSubtitle(item)

        var resolvedSeason = item.season
        var resolvedEpisode = item.episode
        var resolvedStreamId: String? = null
        var resolvedStartPositionMs = 0L

        val isSeriesWatchingItem =
            item.mediaType == "series" && item.source == "watching"

        val needsTmdbLookup =
            posterUrl.isNullOrBlank() || isSeriesWatchingItem

        if (needsTmdbLookup) {
            val detail = try {
                tmdbLookupSemaphore.withPermit {
                    tmdbRepository.fetchEnrichedMetaCached(
                        navigationId,
                        item.mediaType
                    )
                }
            } catch (e: Exception) {
                Log.e(
                    "HOME_UPNEXT",
                    "tmdb lookup failed for ${item.title}: ${e.message}",
                    e
                )
                null
            }

            if (posterUrl.isNullOrBlank()) {
                posterUrl = detail?.posterPath
                    ?.let { "${TmdbRepository.POSTER_BASE}$it" }
            }

            if (item.mediaType == "series" && detail?.id != null) {
                preloadWatchedEpisodeStateForShow(
                    parentId = navigationId,
                    tmdbShowId = detail.id
                )

                val resolvedTarget = resolveSeriesTargetFromSharedWatchedState(
                    parentId = navigationId,
                    tmdbId = detail.id,
                    simklSeason = item.season,
                    simklEpisode = item.episode
                )

                if (resolvedTarget != null) {
                    resolvedSeason = resolvedTarget.season
                    resolvedEpisode = resolvedTarget.episode
                    resolvedStreamId = resolvedTarget.streamId
                    resolvedStartPositionMs = resolvedTarget.startPositionMs

                    val airedRecently = resolvedTarget.airDate?.let {
                        isWithinDays(it, NEW_RELEASE_WINDOW_DAYS)
                    } ?: false

                    badge = when {
                        resolvedTarget.isResume -> UpNextBadge.CONTINUE_WATCHING
                        airedRecently && resolvedEpisode <= 1 -> UpNextBadge.NEW_SEASON
                        airedRecently -> UpNextBadge.NEW_EPISODE
                        else -> UpNextBadge.NEXT_UP
                    }

                    subtitle = when {
                        resolvedTarget.isResume ->
                            "Resume • ${formatSeasonEpisode(resolvedSeason, resolvedEpisode)}"

                        badge == UpNextBadge.NEW_SEASON ->
                            "New season • ${formatSeasonEpisode(resolvedSeason, resolvedEpisode)}"

                        badge == UpNextBadge.NEW_EPISODE ->
                            "New episode • ${formatSeasonEpisode(resolvedSeason, resolvedEpisode)}"

                        else ->
                            "Up Next • ${formatSeasonEpisode(resolvedSeason, resolvedEpisode)}"
                    }
                }
            }
        }

        return UpNextItem(
            id = "simkl:${item.id}",
            title = item.title,
            poster = posterUrl,
            badge = badge,
            subtitle = subtitle,
            progressPercent = item.progress
                ?.takeIf { it > 0f }
                ?.let { (it / 100f).coerceIn(0f, 1f) },
            streamUrl = null,
            parentId = navigationId,
            parentType = item.mediaType,
            season = resolvedSeason,
            episode = resolvedEpisode,
            episodeStreamId = resolvedStreamId,
            startPositionMs = resolvedStartPositionMs,
            recencyTimestamp = recencyTimestamp
        )
    }

    private suspend fun preloadWatchedEpisodeStateForShow(
        parentId: String,
        tmdbShowId: Int
    ) {
        if (
            watchedEpisodeKeysByShow.containsKey(parentId) &&
            simklWatchedEpisodesByShow.containsKey(parentId)
        ) {
            return
        }

        val localCompletedEntries = try {
            historyDao.getCompletedForParent(parentId)
        } catch (e: Exception) {
            Log.e(
                "HOME_UPNEXT",
                "local completed preload failed for $parentId: ${e.message}",
                e
            )
            emptyList()
        }

        val simklCompletedEpisodes =
            if (simklRepository.isConfigured() && simklRepository.hasToken()) {
                try {
                    simklRepository.getWatchedEpisodesForShowByImdb(
                        imdbId = parentId,
                        tmdbId = tmdbShowId
                    )
                } catch (e: Exception) {
                    Log.e(
                        "HOME_UPNEXT",
                        "simkl watched preload failed for $parentId: ${e.message}",
                        e
                    )
                    emptySet()
                }
            } else {
                emptySet()
            }

        simklWatchedEpisodesByShow[parentId] = simklCompletedEpisodes

        watchedEpisodeKeysByShow[parentId] =
            WatchedEpisodeState.buildMergedWatchedKeys(
                parentId = parentId,
                localCompletedEntries = localCompletedEntries,
                simklCompletedEpisodes = simklCompletedEpisodes
            )
    }

    private suspend fun resolveSeriesTargetFromSharedWatchedState(
        parentId: String,
        tmdbId: Int,
        simklSeason: Int?,
        simklEpisode: Int?
    ): ResolvedHomeSeriesTarget? {
        val resume = try {
            historyDao.getResumeForParent(parentId)
        } catch (e: Exception) {
            Log.e(
                "HOME_UPNEXT",
                "resume lookup failed for $parentId: ${e.message}",
                e
            )
            null
        }

        if (
            resume != null &&
            resume.season != null &&
            resume.episode != null &&
            resume.positionMs > 0L
        ) {
            val resumeEpisodes = try {
                tmdbLookupSemaphore.withPermit {
                    tmdbRepository.getSeasonEpisodes(
                        tmdbId,
                        resume.season,
                        parentId
                    )
                }
            } catch (e: Exception) {
                Log.e(
                    "HOME_UPNEXT",
                    "resume episode lookup failed for $parentId: ${e.message}",
                    e
                )
                emptyList()
            }

            val matchedResumeEpisode = resumeEpisodes.firstOrNull { episode ->
                episode.episodeNumber == resume.episode
            }

            return ResolvedHomeSeriesTarget(
                season = resume.season,
                episode = matchedResumeEpisode?.episodeNumber ?: resume.episode,
                streamId = resume.episodeStreamId ?: matchedResumeEpisode?.streamId,
                startPositionMs = resume.positionMs,
                isResume = true,
                airDate = matchedResumeEpisode?.airDate
            )
        }

        val simklWatchedEpisodes = simklWatchedEpisodesByShow[parentId].orEmpty()
        val watchedEpisodeKeys = watchedEpisodeKeysByShow[parentId].orEmpty()

        if (simklSeason != null && simklEpisode != null) {
            val currentSeasonEpisodes = try {
                tmdbLookupSemaphore.withPermit {
                    tmdbRepository.getSeasonEpisodes(
                        tmdbId,
                        simklSeason,
                        parentId
                    )
                }
            } catch (e: Exception) {
                Log.e(
                    "HOME_UPNEXT",
                    "direct next probe failed for $parentId " +
                        "S${simklSeason}E${simklEpisode}: ${e.message}",
                    e
                )
                emptyList()
            }

            if (currentSeasonEpisodes.isNotEmpty()) {
                val directNextEpisode = currentSeasonEpisodes.firstOrNull { episode ->
                    episode.episodeNumber == simklEpisode + 1 &&
                        isAiredOrUnknown(episode.airDate)
                }

                if (directNextEpisode != null) {
                    Log.e(
                        "HOME_UPNEXT",
                        "Direct next probe resolved $parentId to " +
                            "S${simklSeason}E${directNextEpisode.episodeNumber}"
                    )

                    return ResolvedHomeSeriesTarget(
                        season = simklSeason,
                        episode = directNextEpisode.episodeNumber,
                        streamId = directNextEpisode.streamId,
                        airDate = directNextEpisode.airDate
                    )
                }
            }
        }

        if (simklSeason != null && simklEpisode != null) {
            val simklSeasonEpisodes = try {
                tmdbLookupSemaphore.withPermit {
                    tmdbRepository.getSeasonEpisodes(
                        tmdbId,
                        simklSeason,
                        parentId
                    )
                }
            } catch (e: Exception) {
                Log.e(
                    "HOME_UPNEXT",
                    "simkl fallback lookup failed for $parentId season=$simklSeason: ${e.message}",
                    e
                )
                emptyList()
            }

            val simklMatchedEpisode = simklSeasonEpisodes.firstOrNull { episode ->
                episode.episodeNumber == simklEpisode
            }

            if (simklMatchedEpisode != null) {
                return ResolvedHomeSeriesTarget(
                    season = simklSeason,
                    episode = simklEpisode,
                    streamId = simklMatchedEpisode.streamId,
                    airDate = simklMatchedEpisode.airDate
                )
            }

            if (simklSeasonEpisodes.isEmpty()) {
                return ResolvedHomeSeriesTarget(
                    season = simklSeason,
                    episode = simklEpisode
                )
            }
        }

        val knownWatchedSeasons = watchedEpisodeKeys
            .mapNotNull(::parseEpisodeKey)
            .map { (_, season, _) -> season }

        val highestKnownSeason = maxOf(
            simklSeason ?: 1,
            simklWatchedEpisodes.maxOfOrNull { (season, _) -> season } ?: 1,
            knownWatchedSeasons.maxOrNull() ?: 1
        )

        val firstSeasonToCheck = maxOf(
            1,
            simklSeason ?: knownWatchedSeasons.minOrNull() ?: 1
        )

        val lastSeasonToCheck = maxOf(
            highestKnownSeason + 2,
            firstSeasonToCheck + MAX_FORWARD_SEASON_LOOKAHEAD
        )

        for (season in firstSeasonToCheck..lastSeasonToCheck) {
            val seasonEpisodes = try {
                tmdbLookupSemaphore.withPermit {
                    tmdbRepository.getSeasonEpisodes(
                        tmdbId,
                        season,
                        parentId
                    )
                }
            } catch (e: Exception) {
                Log.e(
                    "HOME_UPNEXT",
                    "season lookup failed for $parentId season=$season: ${e.message}",
                    e
                )
                emptyList()
            }

            if (seasonEpisodes.isEmpty()) continue

            val watchedEpisodesForSeason =
                WatchedEpisodeState.effectiveWatchedEpisodesForSeason(
                    parentId = parentId,
                    season = season,
                    simklWatchedEpisodes = simklWatchedEpisodes,
                    watchedEpisodeKeys = watchedEpisodeKeys
                )

            val firstUnwatchedAired = seasonEpisodes.firstOrNull { episode ->
                episode.episodeNumber !in watchedEpisodesForSeason &&
                    isAiredOrUnknown(episode.airDate)
            }

            if (firstUnwatchedAired != null) {
                Log.e(
                    "HOME_UPNEXT",
                    "Resolved $parentId to S${season}E${firstUnwatchedAired.episodeNumber}; " +
                        "watched=$watchedEpisodesForSeason"
                )

                return ResolvedHomeSeriesTarget(
                    season = season,
                    episode = firstUnwatchedAired.episodeNumber,
                    streamId = firstUnwatchedAired.streamId,
                    airDate = firstUnwatchedAired.airDate
                )
            }
        }

        if (simklSeason != null && simklEpisode != null) {
            val simklSeasonEpisodes = try {
                tmdbLookupSemaphore.withPermit {
                    tmdbRepository.getSeasonEpisodes(
                        tmdbId,
                        simklSeason,
                        parentId
                    )
                }
            } catch (e: Exception) {
                Log.e(
                    "HOME_UPNEXT",
                    "simkl fallback lookup failed for $parentId season=$simklSeason: ${e.message}",
                    e
                )
                emptyList()
            }

            val simklMatchedEpisode = simklSeasonEpisodes.firstOrNull { episode ->
                episode.episodeNumber == simklEpisode
            }

            return ResolvedHomeSeriesTarget(
                season = simklSeason,
                episode = simklMatchedEpisode?.episodeNumber ?: simklEpisode,
                streamId = simklMatchedEpisode?.streamId,
                airDate = simklMatchedEpisode?.airDate
            )
        }

        return null
    }

    private fun initialBadgeFromSimkl(
        item: SimklContinueWatchingItem
    ): UpNextBadge {
        return if (item.source == "playback") {
            UpNextBadge.CONTINUE_WATCHING
        } else {
            UpNextBadge.NEXT_UP
        }
    }

    private fun buildSimklSubtitle(
        item: SimklContinueWatchingItem
    ): String {
        if (item.source == "playback") {
            return when {
                item.mediaType == "series" &&
                    item.season != null &&
                    item.episode != null ->
                    "Resume • ${formatSeasonEpisode(item.season, item.episode)}"

                item.mediaType == "movie" -> "Resume"
                else -> "Resume"
            }
        }

        return when {
            item.season != null && item.episode != null ->
                "Up Next • ${formatSeasonEpisode(item.season, item.episode)}"

            item.season != null ->
                "Up Next • S${item.season}"

            else -> "Up Next"
        }
    }

    private fun formatSeasonEpisode(
        season: Int?,
        episode: Int?
    ): String {
        return when {
            season != null && episode != null -> "S${season}E${episode}"
            season != null -> "S$season"
            episode != null -> "E$episode"
            else -> ""
        }
    }

    private fun parseTimestampMillis(value: String?): Long {
        return try {
            OffsetDateTime.parse(value).toInstant().toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }

    private fun isWithinDays(
        dateStr: String,
        days: Int
    ): Boolean {
        return try {
            val date = LocalDate.parse(dateStr)
            val today = LocalDate.now()
            val diff = ChronoUnit.DAYS.between(date, today)
            diff in 0..days.toLong()
        } catch (_: Exception) {
            false
        }
    }

    private fun isAiredOrUnknown(airDate: String?): Boolean {
        if (airDate.isNullOrBlank()) return true

        return try {
            !LocalDate.parse(airDate).isAfter(LocalDate.now())
        } catch (_: Exception) {
            true
        }
    }

    private fun badgePriority(badge: UpNextBadge): Int = when (badge) {
        UpNextBadge.CONTINUE_WATCHING -> 0
        UpNextBadge.NEW_SEASON -> 1
        UpNextBadge.NEW_EPISODE -> 2
        UpNextBadge.NEXT_UP -> 3
    }

    private fun progressFromHistory(
        positionMs: Long,
        durationMs: Long
    ): Float? {
        if (positionMs <= 0L || durationMs <= 0L) return null

        return (positionMs.toFloat() / durationMs.toFloat())
            .coerceIn(0.02f, 0.98f)
    }

    private fun parseEpisodeKey(key: String): Triple<String, Int, Int>? {
        val parts = key.split(":")
        if (parts.size < 3) return null

        val season = parts[parts.size - 2].toIntOrNull() ?: return null
        val episode = parts[parts.size - 1].toIntOrNull() ?: return null
        val showId = parts.dropLast(2).joinToString(":")

        if (showId.isBlank()) return null

        return Triple(showId, season, episode)
    }

    private fun dedupeKey(item: UpNextItem): String {
        val normalizedType = item.parentType
            ?.trim()
            ?.lowercase()
            ?.let {
                when (it) {
                    "tv", "show" -> "series"
                    else -> it
                }
            }
            ?: "unknown"

        val normalizedParentId = item.parentId
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val normalizedEpisodeStreamId = item.episodeStreamId
            ?.trim()
            ?.takeIf { it.isNotBlank() }

        val normalizedTitle = item.title
            .trim()
            .lowercase()

        return when {
            normalizedEpisodeStreamId != null -> {
                "episode-stream:$normalizedType:$normalizedEpisodeStreamId"
            }

            normalizedParentId != null && item.season != null && item.episode != null -> {
                "episode-target:$normalizedType:$normalizedParentId:s${item.season}:e${item.episode}"
            }

            normalizedParentId != null -> {
                "parent:$normalizedType:$normalizedParentId"
            }

            item.season != null && item.episode != null -> {
                "title-episode:$normalizedType:$normalizedTitle:s${item.season}:e${item.episode}"
            }

            else -> {
                "title:$normalizedType:$normalizedTitle"
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
                            val metas = repository.getCatalog(
                                baseUrl,
                                catalog.type,
                                catalog.id
                            )

                            if (metas.isNotEmpty()) {
                                result += Rail(
                                    addonName = addon.name,
                                    catalogName = catalog.name,
                                    type = catalog.type,
                                    items = metas
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
                val metas = rails
                    .flatMap { it.items }
                    .filter { it.id.isNotBlank() }

                Log.e("HOME_WATCHED", "metas count=${metas.size}")

                metas.take(10).forEach { meta ->
                    Log.e(
                        "HOME_WATCHED",
                        "meta title=${meta.name}, id=${meta.id}, " +
                            "type=${meta.type}, watchedKey=${watchedKey(meta.id, meta.type)}"
                    )
                }

                if (metas.isEmpty()) {
                    _watchedKeys.value = emptySet()
                    return@launch
                }

                val preloadItems = metas
                    .mapNotNull { meta ->
                        val normalizedType =
                            normalizeMediaType(meta.type) ?: return@mapNotNull null

                        val imdbId =
                            meta.id.takeIf { it.isNotBlank() } ?: return@mapNotNull null

                        imdbId to normalizedType
                    }
                    .distinct()
                    .take(100)

                if (preloadItems.isEmpty()) {
                    _watchedKeys.value = emptySet()
                    return@launch
                }

                watchedStatusRepository.preload(preloadItems)

                val watched = preloadItems
                    .filter { (imdbId, mediaType) ->
                        watchedStatusRepository.isWatchedCached(imdbId, mediaType)
                    }
                    .map { (imdbId, mediaType) ->
                        watchedKey(imdbId, mediaType)
                    }
                    .toSet()

                Log.e("HOME_WATCHED", "watched keys count=${watched.size}")

                watched.take(20).forEach { key ->
                    Log.e("HOME_WATCHED", "watched key=$key")
                }

                _watchedKeys.value = watched
            } catch (e: Exception) {
                Log.e(
                    "HOME_WATCHED",
                    "refreshWatchedStatus failed: ${e.message}",
                    e
                )
                _watchedKeys.value = emptySet()
            }
        }
    }

    private fun normalizeMediaType(type: String?): String? =
        when (type?.lowercase()) {
            "movie" -> "movie"
            "series", "show", "tv" -> "series"
            else -> null
        }

    companion object {
        private const val NEW_RELEASE_WINDOW_DAYS = 7
        private const val TMDB_MAX_CONCURRENT_LOOKUPS = 5
        private const val MAX_FORWARD_SEASON_LOOKAHEAD = 8
    }
}
