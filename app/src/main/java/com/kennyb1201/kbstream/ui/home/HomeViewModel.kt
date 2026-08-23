package com.kennyb1201.kbstream.ui.home

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.addon.AddonManager
import com.kennyb1201.kbstream.data.addon.AddonRepository
import com.kennyb1201.kbstream.data.addon.Meta
import com.kennyb1201.kbstream.data.addon.MetaPreview
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.history.WatchHistoryRepository
import com.kennyb1201.kbstream.data.simkl.SimklContinueWatchingItem
import com.kennyb1201.kbstream.data.simkl.SimklRepository
import com.kennyb1201.kbstream.data.tmdb.TmdbHeroArtworkRepository
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.data.watched.WatchStateBus
import com.kennyb1201.kbstream.data.watched.WatchedEpisodeState
import com.kennyb1201.kbstream.data.watched.WatchedStatusRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

data class Rail(
    val addonName: String,
    val catalogName: String,
    val type: String,
    val items: List<MetaPreview>,
    val catalogId: String? = null,
    val baseUrl: String? = null
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

private sealed interface SimklUpNextResult {

    data class Success(
        val items: List<UpNextItem>
    ) : SimklUpNextResult

    data object NotConfigured : SimklUpNextResult

    data class Failed(
        val error: Throwable
    ) : SimklUpNextResult
}

class HomeViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val repository =
        AddonRepository()

    // FIXED: was AddonManager(application) — bypassed the singleton so
    // Home held a stale copy of installed addons/catalogs whenever the
    // Addons screen changed something. Now shares the same instance.
    private val addonManager =
        AddonManager.getInstance(application)

    private val watchHistoryRepository =
        WatchHistoryRepository(application)

    private val historyDao =
        WatchHistoryDatabase
            .getInstance(application)
            .watchHistoryDao()

    private val simklRepository =
        SimklRepository(application)

    private val tmdbRepository =
        TmdbRepository(application)

    private val tmdbHeroArtworkRepository =
        TmdbHeroArtworkRepository()

    private val watchedStatusRepository =
        WatchedStatusRepository(application)

    private val tmdbLookupSemaphore =
        Semaphore(
            TMDB_MAX_CONCURRENT_LOOKUPS
        )

    private val watchedStateMutex =
        Mutex()

    private val upNextRequestMutex =
        Mutex()

    private val railsRefreshMutex =
        Mutex()

    private val catalogRequestSemaphore =
        Semaphore(
            MAX_CONCURRENT_CATALOG_REQUESTS
        )

    private val watchedRefreshMutex =
        Mutex()

    private val simklWatchedEpisodesByShow =
        mutableMapOf<String, Set<Pair<Int, Int>>>()

    private val watchedEpisodeKeysByShow =
        mutableMapOf<String, Set<String>>()

    private val watchedStatePreloadInFlight =
        mutableSetOf<String>()

    private var upNextRequestVersion = 0L

    private var watchedRefreshVersion = 0L

    private var periodicRefreshJob: Job? = null

    private val _rails =
        MutableStateFlow<List<Rail>>(
            emptyList()
        )

    val rails: StateFlow<List<Rail>> =
        _rails.asStateFlow()

    private val _watchedKeys =
        MutableStateFlow<Set<String>>(
            emptySet()
        )

    val watchedKeys: StateFlow<Set<String>> =
        _watchedKeys.asStateFlow()

    private val _upNext =
        MutableStateFlow<List<UpNextItem>>(
            emptyList()
        )

    val upNext: StateFlow<List<UpNextItem>> =
        _upNext.asStateFlow()

    private val _isLoading =
        MutableStateFlow(true)

    val isLoading: StateFlow<Boolean> =
        _isLoading.asStateFlow()

    private val _error =
        MutableStateFlow<String?>(null)

    val error: StateFlow<String?> =
        _error.asStateFlow()

    private val _heroMeta =
        MutableStateFlow<Meta?>(null)

    val heroMeta: StateFlow<Meta?> =
        _heroMeta.asStateFlow()

    private val _heroBackdropUrl =
        MutableStateFlow<String?>(null)

    val heroBackdropUrl: StateFlow<String?> =
        _heroBackdropUrl.asStateFlow()

    private val _heroTrailerKey =
        MutableStateFlow<String?>(null)

    val heroTrailerKey: StateFlow<String?> =
        _heroTrailerKey.asStateFlow()

    private var heroResolveJob: Job? = null

    fun resolveHeroMeta(
        item: MetaPreview?,
        baseUrl: String? = null
    ) {
        heroResolveJob?.cancel()

        if (item == null) {
            _heroMeta.value = null
            _heroBackdropUrl.value = null
            _heroTrailerKey.value = null
            return
        }

        val requestedId = item.id
        val requestedType = item.type

        _heroMeta.value = _heroMeta.value?.takeIf {
            it.id == requestedId &&
                it.type.equals(requestedType, ignoreCase = true)
        }
        _heroBackdropUrl.value = null
        _heroTrailerKey.value = null

        heroResolveJob = viewModelScope.launch {
            try {
                coroutineScope {
                    val addonMetaDeferred = async {
                        val resolvedBaseUrl =
                            baseUrl?.takeIf { it.isNotBlank() }
                                ?: findBaseUrlForMeta(item)

                        if (resolvedBaseUrl.isNullOrBlank()) {
                            null
                        } else {
                            runCatching {
                                repository.getMeta(
                                    baseUrl = resolvedBaseUrl,
                                    type = requestedType,
                                    id = requestedId
                                )
                            }.getOrNull()
                        }
                    }

                    val tmdbDetailDeferred = async {
                        runCatching {
                            when {
                                requestedId.trim().startsWith("tmdb:", ignoreCase = true) -> {
                                    requestedId.trim()
                                        .substringAfter(":")
                                        .toIntOrNull()
                                        ?.let {
                                            tmdbRepository.getDetailByTmdbId(
                                                it,
                                                requestedType
                                            )
                                        }
                                }

                                requestedId.trim().startsWith("tt", ignoreCase = true) -> {
                                    tmdbRepository.fetchEnrichedMetaCached(
                                        requestedId.trim(),
                                        requestedType
                                    )
                                }

                                else -> null
                            }
                        }.getOrNull()
                    }

                    val resolvedAddonMeta = addonMetaDeferred.await()
                    val resolvedTmdbDetail = tmdbDetailDeferred.await()

                    val resolvedTmdbId =
                        when {
                            requestedId.trim().startsWith("tmdb:", ignoreCase = true) -> {
                                requestedId.trim()
                                    .substringAfter(":")
                                    .toIntOrNull()
                            }

                            else -> resolvedTmdbDetail?.id
                        }

                    val heroArtwork =
                        resolvedTmdbId?.takeIf { it > 0 }?.let { tmdbId ->
                            runCatching {
                                tmdbHeroArtworkRepository.resolve(
                                    id = requestedId,
                                    type = requestedType,
                                    tmdbId = tmdbId
                                )
                            }.getOrNull()
                        }

                    val resolvedLogo =
                        heroArtwork?.logoUrl?.takeIf { it.isNotBlank() }
                            ?: resolvedAddonMeta?.logo?.takeIf { it.isNotBlank() }
                            ?: item.logo?.takeIf { it.isNotBlank() }

                    val resolvedBackdrop =
                        heroArtwork?.backdropUrl?.takeIf { it.isNotBlank() }
                            ?: resolvedTmdbDetail?.backdropPath
                                ?.takeIf { it.isNotBlank() }
                                ?.let { TmdbRepository.BACKDROP_BASE + it }
                            ?: resolvedAddonMeta?.background?.takeIf { it.isNotBlank() }
                            ?: item.background?.takeIf { it.isNotBlank() }
                            ?: resolvedAddonMeta?.poster?.takeIf { it.isNotBlank() }
                            ?: item.poster?.takeIf { it.isNotBlank() }

                    val finalMeta = resolvedAddonMeta?.copy(
                        logo = resolvedLogo ?: resolvedAddonMeta.logo,
                        background = resolvedBackdrop ?: resolvedAddonMeta.background
                    )

                    _heroMeta.value = finalMeta
                    _heroBackdropUrl.value = resolvedBackdrop
                    _heroTrailerKey.value = resolvedTmdbDetail?.videos?.results
                        ?.asSequence()
                        ?.filter { video ->
                            video.site.equals("YouTube", ignoreCase = true) &&
                                video.type.equals("Trailer", ignoreCase = true) &&
                                video.key.isNotBlank()
                        }
                        ?.firstOrNull()
                        ?.key

                    Log.d(
                        "HOME_HERO",
                        "Hero resolved: title=${item.name}, id=$requestedId, type=$requestedType, tmdbId=$resolvedTmdbId, logo=${resolvedLogo != null}, backdrop=${resolvedBackdrop != null}, trailer=${_heroTrailerKey.value != null}"
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(
                    "HOME_HERO",
                    "Hero enrichment failed for ${item.name}: ${e.message}",
                    e
                )
                _heroMeta.value = null
                _heroBackdropUrl.value = null
                _heroTrailerKey.value = null
            }
        }
    }

    private fun findBaseUrlForMeta(item: MetaPreview): String {
        return _rails.value.firstOrNull { rail ->
            rail.type.equals(item.type, ignoreCase = true) &&
                rail.items.any { it.id == item.id }
        }?.baseUrl
            ?: _rails.value.firstOrNull { rail ->
                rail.items.any { it.id == item.id }
            }?.baseUrl
            ?: ""
    }

    private val _refreshTrigger =
        MutableStateFlow(0)

    init {

        Log.e(
            "HOME_VM",
            "HomeViewModel init"
        )

        observeAddonChanges()

        loadRails()

        observeUpNext()

        startPeriodicSimklRefresh()

        viewModelScope.launch {

            WatchStateBus.updates.collect {
                (key, isWatched) ->

                val current =
                    _watchedKeys.value
                        .toMutableSet()

                if (isWatched) {
                    current.add(key)
                } else {
                    current.remove(key)
                }

                _watchedKeys.value =
                    current
            }
        }
    }

    private fun observeAddonChanges() {
        viewModelScope.launch {
            addonManager.installedAddons.collectLatest {
                loadRailsInternal(forceRefresh = true)
            }
        }
    }

    fun refreshUpNext() {

        viewModelScope.launch {

            clearWatchedStateCaches()

            _refreshTrigger.value += 1
        }
    }

    fun refreshAllHomeData() {

        viewModelScope.launch {

            clearWatchedStateCaches()

            _refreshTrigger.value += 1

            loadRailsInternal(
                forceRefresh = true
            )
        }
    }

    fun refreshRailsOnly() {

        loadRails(
            forceRefresh = true
        )
    }

    fun refreshWatchedStatusForCurrentRails() {

        refreshWatchedStatus(
            _rails.value
        )
    }

    fun watchedKey(
        id: String,
        type: String
    ): String {

        val normalizedType =
            when (type.lowercase()) {

                "movie" ->
                    "movie"

                "series",
                "show",
                "tv" ->
                    "series"

                else ->
                    type.lowercase()
            }

        return "$normalizedType::$id"
    }

    private fun startPeriodicSimklRefresh() {

        if (
            periodicRefreshJob?.isActive == true
        ) {
            return
        }

        periodicRefreshJob =
            viewModelScope.launch {

                while (true) {

                    delay(
                        PERIODIC_SIMKL_REFRESH_MS
                    )

                    try {

                        if (
                            simklRepository.isConfigured() &&
                            simklRepository.hasToken()
                        ) {

                            Log.e(
                                "HOME_REFRESH",
                                "periodic Simkl refresh tick"
                            )

                            clearWatchedStateCaches()

                            _refreshTrigger.value += 1

                            refreshWatchedStatus(
                                _rails.value
                            )
                        }

                    } catch (e: Exception) {

                        Log.e(
                            "HOME_REFRESH",
                            "periodic refresh failed: ${e.message}",
                            e
                        )
                    }
                }
            }
    }

    private suspend fun clearWatchedStateCaches() {

        watchedStateMutex.withLock {

            simklWatchedEpisodesByShow.clear()

            watchedEpisodeKeysByShow.clear()

            watchedStatePreloadInFlight.clear()
        }
    }

    private fun observeUpNext() {

        viewModelScope.launch {

            combine(
                watchHistoryRepository.continueWatchingParents,
                _refreshTrigger
            ) { history, _ ->
                history
            }
                .debounce(
                    UP_NEXT_DEBOUNCE_MS
                )
                .collect { history ->

                    val requestVersion =
                        nextUpNextRequestVersion()

                    try {

                        val localItems =
                            history.map { entry ->

                                val isEpisodePlayback =
                                    entry.season != null &&
                                        entry.episode != null

                                UpNextItem(

                                    id = buildString {

                                        append("history:")
                                        append(entry.id)

                                        entry.season?.let {
                                            append(":s$it")
                                        }

                                        entry.episode?.let {
                                            append(":e$it")
                                        }
                                    },

                                    title =
                                        entry.name,

                                    poster =
                                        entry.poster,

                                    badge =
                                        UpNextBadge.CONTINUE_WATCHING,

                                    subtitle =
                                        if (
                                            isEpisodePlayback
                                        ) {

                                            "Resume - ${
                                                formatSeasonEpisode(
                                                    entry.season,
                                                    entry.episode
                                                )
                                            }"

                                        } else {

                                            "Resume"
                                        },

                                    progressPercent =
                                        progressFromHistory(
                                            positionMs =
                                                entry.positionMs,

                                            durationMs =
                                                entry.durationMs
                                        ),

                                    streamUrl =
                                        entry.streamUrl,

                                    parentId =
                                        entry.parentId
                                            .ifBlank {
                                                entry.id
                                            },

                                    parentType =
                                        entry.type,

                                    season =
                                        entry.season,

                                    episode =
                                        entry.episode,

                                    episodeStreamId =
                                        entry.episodeStreamId,

                                    startPositionMs =
                                        entry.positionMs,

                                    recencyTimestamp =
                                        entry.updatedAt
                                )
                            }

                        val simklResult =
                            loadSimklUpNextItems()

                        if (
                            simklResult is
                                SimklUpNextResult.Failed
                        ) {

                            Log.w(
                                "HOME_UPNEXT",
                                "SIMKL failed; preserving existing Up Next data",
                                simklResult.error
                            )

                            if (
                                localItems.isNotEmpty() &&
                                isLatestUpNextRequest(
                                    requestVersion
                                )
                            ) {

                                _upNext.value =
                                    dedupeAndSortUpNext(
                                        localItems
                                    )
                            }

                            return@collect
                        }

                        val simklItems =
                            when (simklResult) {

                                is SimklUpNextResult.Success ->
                                    simklResult.items

                                SimklUpNextResult.NotConfigured ->
                                    emptyList()

                                is SimklUpNextResult.Failed ->
                                    emptyList()
                            }

                        val merged =
                            dedupeAndSortUpNext(
                                localItems + simklItems
                            )

                        if (
                            !isLatestUpNextRequest(
                                requestVersion
                            )
                        ) {
                            return@collect
                        }

                        if (
                            merged.isNotEmpty()
                        ) {

                            _upNext.value =
                                merged

                        } else if (
                            localItems.isNotEmpty()
                        ) {

                            _upNext.value =
                                dedupeAndSortUpNext(
                                    localItems
                                )

                        } else {

                            _upNext.value =
                                emptyList()
                        }

                    } catch (
                        e: kotlinx.coroutines.CancellationException
                    ) {

                        throw e

                    } catch (e: Exception) {

                        Log.e(
                            "HOME_UPNEXT",
                            "observeUpNext failed: ${e.message}",
                            e
                        )
                    }
                }
        }
    }

    private suspend fun nextUpNextRequestVersion(): Long {

        return upNextRequestMutex.withLock {

            upNextRequestVersion += 1

            upNextRequestVersion
        }
    }

    private suspend fun isLatestUpNextRequest(
        requestVersion: Long
    ): Boolean {

        return upNextRequestMutex.withLock {

            requestVersion ==
                upNextRequestVersion
        }
    }

    private suspend fun loadSimklUpNextItems():
        SimklUpNextResult {

        if (
            !simklRepository.isConfigured() ||
            !simklRepository.hasToken()
        ) {

            return SimklUpNextResult.NotConfigured
        }

        return try {

            val raw =
                simklRepository
                    .getContinueWatching()
                    .take(
                        MAX_SIMKL_UP_NEXT_ITEMS
                    )

            val lookupSemaphore =
                Semaphore(
                    MAX_CONCURRENT_SIMKL_UP_NEXT_LOOKUPS
                )

            val items =
                coroutineScope {

                    raw.map { item ->

                        async {

                            lookupSemaphore.withPermit {

                                buildSimklUpNextItem(
                                    item
                                )
                            }
                        }
                    }
                        .awaitAll()
                        .filterNotNull()
                }

            Log.d(
                "HOME_UPNEXT",
                "SIMKL load succeeded: " +
                    "raw=${raw.size}, " +
                    "resolved=${items.size}"
            )

            SimklUpNextResult.Success(
                items
            )

        } catch (
            e: kotlinx.coroutines.CancellationException
        ) {

            throw e

        } catch (e: Exception) {

            Log.e(
                "HOME_UPNEXT",
                "SIMKL load failed: ${e.message}",
                e
            )

            SimklUpNextResult.Failed(
                e
            )
        }
    }

    private suspend fun buildSimklUpNextItem(
        item: SimklContinueWatchingItem
    ): UpNextItem? {

        val navigationId =
            item.imdbId
                ?: item.tmdbId?.let {
                    "tmdb:$it"
                }
                ?: item.simklId?.let {
                    "simkl:$it"
                }

        if (
            navigationId.isNullOrBlank()
        ) {
            return null
        }

        var posterUrl =
            item.posterUrl

        val recencyTimestamp =
            parseTimestampMillis(
                item.lastWatchedAt
            )

        val isExplicitResume =
            item.source == "playback" &&
                (item.progress ?: 0f) > 0f

        var badge =
            if (isExplicitResume) {
                UpNextBadge.CONTINUE_WATCHING
            } else {
                UpNextBadge.NEXT_UP
            }

        var subtitle =
            buildSimklSubtitle(
                item,
                isExplicitResume
            )

        var resolvedSeason =
            item.season

        var resolvedEpisode =
            item.episode

        var resolvedStreamId:
            String? = null

        var resolvedStartPositionMs =
            0L

        val needsTmdbLookup =
            posterUrl.isNullOrBlank() ||
                item.mediaType == "series"

        if (needsTmdbLookup) {

            val detail =
                try {

                    tmdbLookupSemaphore
                        .withPermit {

                            tmdbRepository
                                .fetchEnrichedMetaCached(
                                    navigationId,
                                    item.mediaType
                                )
                        }

                } catch (_: Exception) {
                    null
                }

            if (
                posterUrl.isNullOrBlank()
            ) {

                posterUrl =
                    detail
                        ?.posterPath
                        ?.let {
                            "${TmdbRepository.POSTER_BASE}$it"
                        }
            }

            if (
                item.mediaType == "series" &&
                (
                    detail?.id != null ||
                        !item.imdbId.isNullOrBlank()
                    )
            ) {

                val showLookupKey =
                    item.imdbId
                        ?: navigationId

                val numericTmdbId =
                    detail?.id ?: 0

                preloadWatchedEpisodeStateForShow(
                    parentId =
                        showLookupKey,

                    tmdbShowId =
                        numericTmdbId
                )

                val resolvedTarget =
                    resolveSeriesTargetFromSharedWatchedState(
                        parentId =
                            showLookupKey,

                        tmdbId =
                            numericTmdbId,

                        simklSeason =
                            item.season,

                        simklEpisode =
                            item.episode
                    )

                if (
                    resolvedTarget != null
                ) {

                    resolvedSeason =
                        resolvedTarget.season

                    resolvedEpisode =
                        resolvedTarget.episode

                    resolvedStreamId =
                        resolvedTarget.streamId

                    resolvedStartPositionMs =
                        resolvedTarget.startPositionMs

                    val airedRecently =
                        resolvedTarget.airDate
                            ?.let {
                                isWithinDays(
                                    it,
                                    NEW_RELEASE_WINDOW_DAYS
                                )
                            }
                            ?: false

                    badge =
                        when {

                            resolvedTarget.isResume ||
                                isExplicitResume -> {

                                UpNextBadge.CONTINUE_WATCHING
                            }

                            airedRecently &&
                                resolvedEpisode <= 1 -> {

                                UpNextBadge.NEW_SEASON
                            }

                            airedRecently -> {

                                UpNextBadge.NEW_EPISODE
                            }

                            else -> {

                                UpNextBadge.NEXT_UP
                            }
                        }

                    subtitle =
                        when {

                            badge ==
                                UpNextBadge.CONTINUE_WATCHING -> {

                                "Resume - ${
                                    formatSeasonEpisode(
                                        resolvedSeason,
                                        resolvedEpisode
                                    )
                                }"
                            }

                            badge ==
                                UpNextBadge.NEW_SEASON -> {

                                "New Season - ${
                                    formatSeasonEpisode(
                                        resolvedSeason,
                                        resolvedEpisode
                                    )
                                }"
                            }

                            badge ==
                                UpNextBadge.NEW_EPISODE -> {

                                "New Episode - ${
                                    formatSeasonEpisode(
                                        resolvedSeason,
                                        resolvedEpisode
                                    )
                                }"
                            }

                            else -> {

                                "Up Next - ${
                                    formatSeasonEpisode(
                                        resolvedSeason,
                                        resolvedEpisode
                                    )
                                }"
                            }
                        }
                }
            }
        }

        if (
            posterUrl?.isBlank() == true
        ) {
            posterUrl = null
        }

        return UpNextItem(

            id =
                "simkl:${item.id}",

            title =
                item.title,

            poster =
                posterUrl,

            badge =
                badge,

            subtitle =
                subtitle,

            progressPercent =
                if (item.source == "playback") {

                    item.progress
                        ?.takeIf {
                            it > 0f
                        }
                        ?.let {
                            (it / 100f)
                                .coerceIn(
                                    0f,
                                    1f
                                )
                        }

                } else {
                    null
                },

            streamUrl =
                null,

            parentId =
                navigationId,

            parentType =
                item.mediaType,

            season =
                resolvedSeason,

            episode =
                resolvedEpisode,

            episodeStreamId =
                resolvedStreamId,

            startPositionMs =
                resolvedStartPositionMs,

            recencyTimestamp =
                recencyTimestamp
        )
    }

    private suspend fun preloadWatchedEpisodeStateForShow(
        parentId: String,
        tmdbShowId: Int
    ) {

        while (true) {

            val shouldLoad =
                watchedStateMutex.withLock {

                    val alreadyLoaded =
                        watchedEpisodeKeysByShow
                            .containsKey(parentId) &&
                            simklWatchedEpisodesByShow
                                .containsKey(parentId)

                    val alreadyLoading =
                        parentId in
                            watchedStatePreloadInFlight

                    if (alreadyLoaded) {

                        false

                    } else if (alreadyLoading) {

                        null

                    } else {

                        watchedStatePreloadInFlight +=
                            parentId

                        true
                    }
                }

            if (shouldLoad == null) {

                delay(50)

                continue

            } else if (!shouldLoad) {

                return

            } else {

                break
            }
        }

        try {

            val localCompletedEntries =
                try {

                    historyDao
                        .getCompletedForParent(
                            parentId
                        )

                } catch (_: Exception) {
                    emptyList()
                }

            val simklCompletedEpisodes =
                if (
                    simklRepository.isConfigured() &&
                    simklRepository.hasToken()
                ) {

                    try {

                        simklRepository
                            .getWatchedEpisodesForShowByImdb(
                                imdbId =
                                    parentId,

                                tmdbId =
                                    tmdbShowId
                            )

                    } catch (_: Exception) {
                        emptySet()
                    }

                } else {
                    emptySet()
                }

            val mergedWatchedKeys =
                WatchedEpisodeState
                    .buildMergedWatchedKeys(
                        parentId =
                            parentId,

                        localCompletedEntries =
                            localCompletedEntries,

                        simklCompletedEpisodes =
                            simklCompletedEpisodes
                    )

            watchedStateMutex.withLock {

                simklWatchedEpisodesByShow[
                    parentId
                ] =
                    simklCompletedEpisodes

                watchedEpisodeKeysByShow[
                    parentId
                ] =
                    mergedWatchedKeys
            }

        } finally {

            watchedStateMutex.withLock {

                watchedStatePreloadInFlight
                    .remove(parentId)
            }
        }
    }

    private suspend fun resolveSeriesTargetFromSharedWatchedState(
        parentId: String,
        tmdbId: Int,
        simklSeason: Int?,
        simklEpisode: Int?
    ): ResolvedHomeSeriesTarget? {

        val resume =
            try {

                historyDao
                    .getResumeForParent(
                        parentId
                    )

            } catch (_: Exception) {
                null
            }

        if (
            resume != null &&
            resume.season != null &&
            resume.episode != null &&
            resume.positionMs > 0L
        ) {

            val resumeEpisodes =
                try {

                    tmdbLookupSemaphore
                        .withPermit {

                            tmdbRepository
                                .getSeasonEpisodes(
                                    tmdbId,
                                    resume.season,
                                    parentId
                                )
                        }

                } catch (_: Exception) {
                    emptyList()
                }

            val matchedResumeEpisode =
                resumeEpisodes.firstOrNull {
                    it.episodeNumber ==
                        resume.episode
                }

            if (
                matchedResumeEpisode != null
            ) {

                return ResolvedHomeSeriesTarget(

                    season =
                        resume.season,

                    episode =
                        matchedResumeEpisode
                            .episodeNumber,

                    streamId =
                        resume.episodeStreamId
                            ?: matchedResumeEpisode
                                .streamId,

                    startPositionMs =
                        resume.positionMs,

                    isResume =
                        true,

                    airDate =
                        matchedResumeEpisode
                            .airDate
                )
            }
        }

        val (
            simklWatchedEpisodes,
            watchedEpisodeKeys
        ) =
            watchedStateMutex.withLock {

                Pair(
                    simklWatchedEpisodesByShow[
                        parentId
                    ].orEmpty(),

                    watchedEpisodeKeysByShow[
                        parentId
                    ].orEmpty()
                )
            }

        val startingSeason =
            simklSeason ?: 1

        val startingEpisode =
            simklEpisode ?: 1

        val currentSeasonEpisodes =
            try {

                tmdbLookupSemaphore
                    .withPermit {

                        tmdbRepository
                            .getSeasonEpisodes(
                                tmdbId,
                                startingSeason,
                                parentId
                            )
                    }

            } catch (_: Exception) {
                emptyList()
            }

        val watchedEpisodesForCurrentSeason =
            WatchedEpisodeState
                .effectiveWatchedEpisodesForSeason(

                    parentId =
                        parentId,

                    season =
                        startingSeason,

                    simklWatchedEpisodes =
                        simklWatchedEpisodes,

                    watchedEpisodeKeys =
                        watchedEpisodeKeys
                )

        val nextUnwatchedInSeason =
            currentSeasonEpisodes
                .firstOrNull { episode ->

                    episode.episodeNumber >=
                        startingEpisode &&

                        episode.episodeNumber !in
                            watchedEpisodesForCurrentSeason &&

                        isAiredOrUnknown(
                            episode.airDate
                        )
                }

        if (
            nextUnwatchedInSeason != null
        ) {

            return ResolvedHomeSeriesTarget(

                season =
                    startingSeason,

                episode =
                    nextUnwatchedInSeason
                        .episodeNumber,

                streamId =
                    nextUnwatchedInSeason
                        .streamId,

                airDate =
                    nextUnwatchedInSeason
                        .airDate
            )
        }

        val knownWatchedSeasons =
            watchedEpisodeKeys
                .mapNotNull(
                    ::parseEpisodeKey
                )
                .map {
                    (_, season, _) ->
                    season
                }

        val highestKnownSeason =
            maxOf(

                startingSeason,

                simklWatchedEpisodes
                    .maxOfOrNull {
                        (season, _) ->
                        season
                    }
                    ?: startingSeason,

                knownWatchedSeasons
                    .maxOfOrNull {
                        it
                    }
                    ?: startingSeason
            )

        val lastSeasonToCheck =
            maxOf(

                highestKnownSeason + 2,

                startingSeason +
                    MAX_FORWARD_SEASON_LOOKAHEAD
            )

        for (
            season in
            (startingSeason + 1)..lastSeasonToCheck
        ) {

            val seasonEpisodes =
                try {

                    tmdbLookupSemaphore
                        .withPermit {

                            tmdbRepository
                                .getSeasonEpisodes(
                                    tmdbId,
                                    season,
                                    parentId
                                )
                        }

                } catch (_: Exception) {
                    emptyList()
                }

            if (
                seasonEpisodes.isEmpty()
            ) {
                continue
            }

            val watchedEpisodesForSeason =
                WatchedEpisodeState
                    .effectiveWatchedEpisodesForSeason(

                        parentId =
                            parentId,

                        season =
                            season,

                        simklWatchedEpisodes =
                            simklWatchedEpisodes,

                        watchedEpisodeKeys =
                            watchedEpisodeKeys
                    )

            val firstUnwatchedAired =
                seasonEpisodes.firstOrNull {
                    episode ->

                    episode.episodeNumber !in
                        watchedEpisodesForSeason &&

                        isAiredOrUnknown(
                            episode.airDate
                        )
                }

            if (
                firstUnwatchedAired != null
            ) {

                return ResolvedHomeSeriesTarget(

                    season =
                        season,

                    episode =
                        firstUnwatchedAired
                            .episodeNumber,

                    streamId =
                        firstUnwatchedAired
                            .streamId,

                    airDate =
                        firstUnwatchedAired
                            .airDate
                )
            }
        }

        return ResolvedHomeSeriesTarget(

            season =
                startingSeason,

            episode =
                startingEpisode,

            airDate =
                null
        )
    }

    private fun buildSimklSubtitle(
        item: SimklContinueWatchingItem,
        isExplicitResume: Boolean
    ): String {

        return when {

            item.mediaType == "series" &&
                item.season != null &&
                item.episode != null -> {

                if (isExplicitResume) {

                    "Resume - ${
                        formatSeasonEpisode(
                            item.season,
                            item.episode
                        )
                    }"

                } else {

                    "Up Next - ${
                        formatSeasonEpisode(
                            item.season,
                            item.episode
                        )
                    }"
                }
            }

            item.mediaType == "series" &&
                item.season != null -> {

                if (isExplicitResume) {
                    "Resume - S${item.season}"
                } else {
                    "Up Next - S${item.season}"
                }
            }

            isExplicitResume ->
                "Resume"

            else ->
                "Up Next"
        }
    }

    private fun formatSeasonEpisode(
        season: Int?,
        episode: Int?
    ): String {

        return when {

            season != null &&
                episode != null ->
                "S${season}E${episode}"

            season != null ->
                "S$season"

            episode != null ->
                "E$episode"

            else ->
                ""
        }
    }

    private fun parseTimestampMillis(
        value: String?
    ): Long {

        return try {

            OffsetDateTime
                .parse(value)
                .toInstant()
                .toEpochMilli()

        } catch (_: Exception) {
            0L
        }
    }

    private fun isWithinDays(
        dateStr: String,
        days: Int
    ): Boolean {

        return try {

            val date =
                LocalDate.parse(
                    dateStr
                )

            val today =
                LocalDate.now()

            val diff =
                ChronoUnit.DAYS.between(
                    date,
                    today
                )

            diff in 0..days.toLong()

        } catch (_: Exception) {
            false
        }
    }

    private fun isAiredOrUnknown(
        airDate: String?
    ): Boolean {

        if (
            airDate.isNullOrBlank()
        ) {
            return true
        }

        return try {

            !LocalDate
                .parse(airDate)
                .isAfter(
                    LocalDate.now()
                )

        } catch (_: Exception) {
            true
        }
    }

    private fun badgePriority(
        badge: UpNextBadge
    ): Int =

        when (badge) {

            UpNextBadge.NEW_SEASON ->
                0

            UpNextBadge.NEW_EPISODE ->
                1

            UpNextBadge.CONTINUE_WATCHING ->
                2

            UpNextBadge.NEXT_UP ->
                3
        }

    private fun progressFromHistory(
        positionMs: Long,
        durationMs: Long
    ): Float? {

        if (
            positionMs <= 0L ||
            durationMs <= 0L
        ) {
            return null
        }

        return (
            positionMs.toFloat() /
                durationMs.toFloat()
            ).coerceIn(
                0.005f,
                0.99f
            )
    }

    private fun parseEpisodeKey(
        key: String
    ): Triple<String, Int, Int>? {

        val match =
            Regex(
            """^(.+?)(?::[sS]?(\d+))(?::[eE]?(\d+))$"""
            ).find(
                key.trim()
            )
                ?: return null

        val showId =
            match.groupValues[1]
                .trim()

        val season =
            match.groupValues[2]
                .toIntOrNull()
                ?: return null

        val episode =
            match.groupValues[3]
                .toIntOrNull()
                ?: return null

        if (
            showId.isBlank() ||
            season < 0 ||
            episode < 0
        ) {
            return null
        }

        return Triple(
            showId,
            season,
            episode
        )
    }

    private fun normalizeIdentifier(
        rawId: String?
    ): String? {

        if (
            rawId.isNullOrBlank()
        ) {
            return null
        }

        val trimmed =
            rawId
                .trim()
                .lowercase()

        return when {

            trimmed.startsWith("tt") ->
                trimmed

            trimmed.startsWith("tmdb:") ->
                trimmed.removePrefix(
                    "tmdb:"
                )

            trimmed.startsWith("simkl:") ->
                trimmed.removePrefix(
                    "simkl:"
                )

            else ->
                trimmed
        }
    }

    private fun dedupeAndSortUpNext(
        items: List<UpNextItem>
    ): List<UpNextItem> {

        return items
            .groupBy(
                ::showDedupeKey
            )
            .values
            .mapNotNull { candidates ->

                candidates.maxWithOrNull(

                    compareBy<UpNextItem> {
                        winnerScore(it)
                    }

                        .thenByDescending {
                            it.recencyTimestamp
                        }

                        .thenBy {
                            targetPrecisionScore(it)
                        }

                        .thenBy {
                            it.title.lowercase()
                        }
                )
            }
            .sortedWith(

                compareBy<UpNextItem> {
                    badgePriority(it.badge)
                }

                    .thenByDescending {
                        it.recencyTimestamp
                    }

                    .thenBy {
                        it.title.lowercase()
                    }
            )
    }

    private fun showDedupeKey(
        item: UpNextItem
    ): String {

        val normalizedType =
            normalizeMediaType(
                item.parentType
            ) ?: "unknown"

        val normalizedParentId =
            normalizeIdentifier(
                item.parentId
            )

        if (
            normalizedParentId != null
        ) {

            return "parent:$normalizedType:$normalizedParentId"
        }

        val normalizedTitle =
            item.title
                .trim()
                .lowercase()

        return "title:$normalizedType:$normalizedTitle"
    }

    private fun winnerScore(
        item: UpNextItem
    ): Int {

        var score =
            0

        if (
            item.badge ==
                UpNextBadge.CONTINUE_WATCHING
        ) {
            score += 5_000
        }

        if (
            item.startPositionMs > 0L ||
            (item.progressPercent ?: 0f) > 0f
        ) {
            score += 2_500
        }

        if (
            !item.episodeStreamId
                .isNullOrBlank()
        ) {
            score += 500
        }

        if (
            item.season != null &&
            item.episode != null
        ) {
            score += 250
        }

        return score
    }

    private fun targetPrecisionScore(
        item: UpNextItem
    ): Int {

        var score =
            0

        if (
            !item.episodeStreamId
                .isNullOrBlank()
        ) {
            score += 3
        }

        if (
            valueOrDefault(
                item.season,
                0
            ) != 0
        ) {
            score += 2
        }

        if (
            item.episode != null
        ) {
            score += 2
        }

        if (
            !item.streamUrl
                .isNullOrBlank()
        ) {
            score += 1
        }

        if (
            !item.poster
                .isNullOrBlank()
        ) {
            score += 1
        }

        return score
    }

    private fun valueOrDefault(
        value: Int?,
        default: Int
    ): Int =
        value ?: default

    fun loadRails(
        forceRefresh: Boolean = false
    ) {

        viewModelScope.launch {

            loadRailsInternal(
                forceRefresh =
                    forceRefresh
            )
        }
    }

    private data class PendingCatalogLoad(
        val addonName: String,
        val baseUrl: String,
        val catalogId: String,
        val catalogType: String,
        val catalogRawName: String
    )

    private suspend fun loadRailsInternal(
        forceRefresh: Boolean
    ) {

        if (
            !forceRefresh &&
            _rails.value.isNotEmpty()
        ) {
            return
        }

        _isLoading.value =
            _rails.value.isEmpty()

        _error.value =
            null

        if (forceRefresh) {
            repository.clearCatalogCache()
        }

        try {

            railsRefreshMutex.withLock {

                val pinned =
                    mutableListOf<Rail>()

                loadPinnedTopTodayRails(
                    pinned
                )

                val addonsById =
                    addonManager
                        .installedAddons
                        .value
                        .associateBy { it.id }

                val pendingCatalogs =
                    addonManager
                        .getHomeCatalogConfigurations()
                        .asSequence()
                        .mapNotNull { configuration ->

                            val addon =
                                addonsById[configuration.addonId]
                                    ?: return@mapNotNull null

                            if (
                                "catalog" !in addon.resources ||
                                addon.manifestUrl == TOP_TODAY_MANIFEST_URL
                            ) {
                                return@mapNotNull null
                            }

                            val baseUrl =
                                addon.manifestUrl
                                    .removeSuffix("manifest.json")
                                    .removeSuffix("/")

                            PendingCatalogLoad(
                                addonName = addon.displayName,
                                baseUrl = baseUrl,
                                catalogId = configuration.catalog.id,
                                catalogType = configuration.catalog.type,
                                catalogRawName = configuration.catalog.name
                            )
                        }
                        .toList()

                val loadedRails =
                    coroutineScope {

                        pendingCatalogs
                            .map { pending ->

                                async {

                                    loadCatalogRail(
                                        pending
                                    )
                                }
                            }
                            .awaitAll()
                            .filterNotNull()
                    }

                val finalRails =
                    pinned + loadedRails

                _rails.value =
                    finalRails

                _isLoading.value =
                    false

                refreshWatchedStatus(
                    finalRails
                )

                Log.d(
                    "HOME_RAILS",
                    "rail load complete: " +
                        "pinned=${pinned.size}, " +
                        "catalogs=${pendingCatalogs.size}, " +
                        "rails=${finalRails.size}"
                )
            }

        } catch (
            e: kotlinx.coroutines.CancellationException
        ) {

            throw e

        } catch (e: Exception) {

            Log.e(
                "HOME_RAILS",
                "loadRails failed: ${e.message}",
                e
            )

            if (
                _rails.value.isEmpty()
            ) {

                _error.value =
                    "Failed to load: ${e.message}"
            }

        } finally {

            _isLoading.value =
                false
        }
    }

    private suspend fun loadCatalogRail(
        pending: PendingCatalogLoad
    ): Rail? {

        return try {

            val metas =
                fetchCatalogThrottled(

                    baseUrl =
                        pending.baseUrl,

                    type =
                        pending.catalogType,

                    catalogId =
                        pending.catalogId
                )

            if (
                metas.isEmpty()
            ) {
                return null
            }

            Rail(

                addonName =
                    pending.addonName,

                catalogName =
                    formatCatalogName(
                        pending.catalogRawName
                    ),

                type =
                    pending.catalogType,

                items =
                    metas,

                catalogId =
                    pending.catalogId,

                baseUrl =
                    pending.baseUrl
            )

        } catch (e: Exception) {

            Log.e(
                "HOME_RAILS",
                "catalog load failed " +
                    "addon=${pending.addonName}, " +
                    "catalog=${pending.catalogId}: " +
                    e.message,
                e
            )

            null
        }
    }

    private suspend fun fetchCatalogThrottled(
        baseUrl: String,
        type: String,
        catalogId: String
    ): List<MetaPreview> {

        return catalogRequestSemaphore
            .withPermit {

                repository.getCatalog(

                    baseUrl =
                        baseUrl,

                    type =
                        type,

                    catalogId =
                        catalogId
                )
            }
    }

    private fun formatCatalogName(
        name: String
    ): String {

        return name
            .replace(
                "_",
                " "
            )
            .split(" ")
            .joinToString(" ") { word ->

                word.lowercase()
                    .replaceFirstChar {
                        it.uppercase()
                    }
            }
    }

    private suspend fun loadPinnedTopTodayRails(
        result: MutableList<Rail>
    ) {

        val baseUrl =
            TOP_TODAY_MANIFEST_URL
                .removeSuffix(
                    "/manifest.json"
                )
                .removeSuffix("/")

        TOP_TODAY_CATALOGS
            .forEach {

                (
                    catalogId,
                    type,
                    catalogName
                ) ->

                try {

                    val metas =
                        fetchCatalogThrottled(

                            baseUrl =
                                baseUrl,

                            type =
                                type,

                            catalogId =
                                catalogId
                        )

                    if (
                        metas.isNotEmpty()
                    ) {

                        result +=
                            Rail(

                                addonName =
                                    TOP_TODAY_ADDON_NAME,

                                catalogName =
                                    formatCatalogName(
                                        catalogName
                                    ),

                                type =
                                    type,

                                items =
                                    metas,

                                catalogId =
                                    catalogId,

                                baseUrl =
                                    baseUrl
                            )
                    }

                } catch (e: Exception) {

                    Log.e(
                        "HOME_RAILS",
                        "pinned Top Today load failed " +
                            "catalog=$catalogId: " +
                            e.message,
                        e
                    )
                }
            }
    }

    private fun refreshWatchedStatus(
        rails: List<Rail>
    ) {

        viewModelScope.launch {

            val requestVersion =
                watchedRefreshMutex.withLock {

                    watchedRefreshVersion += 1

                    watchedRefreshVersion
                }

            try {

                val preloadItems =
                    rails
                        .asSequence()
                        .flatMap { rail ->
                            rail.items.asSequence()
                        }
                        .mapNotNull { meta ->

                            val imdbId =
                                meta.id
                                    .trim()
                                    .takeIf {
                                        it.startsWith(
                                            "tt"
                                        )
                                    }
                                    ?: return@mapNotNull null

                            val mediaType =
                                normalizeMediaType(
                                    meta.type
                                )
                                    ?: return@mapNotNull null

                            imdbId to mediaType
                        }
                        .distinct()
                        .toList()

                if (
                    preloadItems.isEmpty()
                ) {

                    val isCurrent =
                        watchedRefreshMutex
                            .withLock {

                                requestVersion ==
                                    watchedRefreshVersion
                            }

                    if (isCurrent) {

                        _watchedKeys.value =
                            emptySet()
                    }

                    return@launch
                }

                val resolvedWatchedKeys =
                    watchedStatusRepository
                        .preloadAndGetWatchedKeys(
                            preloadItems
                        )

                val isCurrent =
                    watchedRefreshMutex
                        .withLock {

                            requestVersion ==
                                watchedRefreshVersion
                        }

                if (
                    !isCurrent
                ) {
                    return@launch
                }

                _watchedKeys.value =
                    resolvedWatchedKeys

                Log.d(
                    "HOME_WATCHED",
                    "marker refresh complete: " +
                        "input=${preloadItems.size}, " +
                        "watched=${resolvedWatchedKeys.size}, " +
                        "rails=${rails.size}"
                )

            } catch (
                e: kotlinx.coroutines.CancellationException
            ) {

                throw e

            } catch (e: Exception) {

                Log.e(
                    "HOME_WATCHED",
                    "marker refresh failed: ${e.message}",
                    e
                )
            }
        }
    }

    private fun normalizeMediaType(
        type: String?
    ): String? =

        when (
            type?.lowercase()
        ) {

            "movie" ->
                "movie"

            "series",
            "show",
            "tv" ->
                "series"

            else ->
                null
        }

    override fun onCleared() {

        periodicRefreshJob?.cancel()

        super.onCleared()
    }

    companion object {

        private const val NEW_RELEASE_WINDOW_DAYS =
            7

        private const val TMDB_MAX_CONCURRENT_LOOKUPS =
            5

        private const val MAX_CONCURRENT_CATALOG_REQUESTS =
            2

        private const val UP_NEXT_DEBOUNCE_MS =
            100L

        private const val MAX_FORWARD_SEASON_LOOKAHEAD =
            8

        private const val MAX_SIMKL_UP_NEXT_ITEMS =
            30

        private const val MAX_CONCURRENT_SIMKL_UP_NEXT_LOOKUPS =
            3

        private const val PERIODIC_SIMKL_REFRESH_MS =
            15 * 60 * 1000L

        private const val TOP_TODAY_ADDON_NAME =
            "TMDB Top Today"

        private const val TOP_TODAY_MANIFEST_URL =
            "https://toptoday.llamayu.com/landscapeTags=true|landscapeLogos=false|landscapeRanked=false|portraitTags=true|portraitLogos=false|portraitRanked=true|posterLang=en|digitalOnly=true|listLang=en/manifest.json"

        private val TOP_TODAY_CATALOGS =
            listOf(

                Triple(
                    "top_movies_today",
                    "movie",
                    "Top Movies Today"
                ),

                Triple(
                    "top_shows_today",
                    "series",
                    "Top Shows Today"
                )
            )
    }
}
