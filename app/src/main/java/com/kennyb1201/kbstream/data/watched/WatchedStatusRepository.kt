package com.kennyb1201.kbstream.data.watched

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.cache.WatchedStatusEntity
import com.kennyb1201.kbstream.data.cache.WatchedStatusDao
import com.kennyb1201.kbstream.data.history.WatchHistoryDao
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.mdblist.MdbListClient
import com.kennyb1201.kbstream.data.simkl.SimklRepository
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class WatchedStatusRepository(
    private val context: Context
) {

    private val simklRepository =
        SimklRepository.getInstance(context)

    // Resolved per access (see WatchHistoryRepository for the full story):
    // the scoped Room instance is bound to the ACTIVE profile's DB file, so
    // captured DAOs kept serving the previous profile after a switch or a
    // first-profile creation closed the scoped instance.
    private val historyDao: WatchHistoryDao
        get() = WatchHistoryDatabase
            .getInstanceScoped(context)
            .watchHistoryDao()

    private val watchedStatusDao: WatchedStatusDao
        get() = WatchHistoryDatabase
            .getInstanceScoped(context)
            .watchedStatusDao()

    private val repositoryScope =
        CoroutineScope(
            Dispatchers.Default +
                SupervisorJob()
        )

    private val cache =
        mutableMapOf<
            String,
            Pair<Long, WatchedCacheEntry>
            >()

    /**
     * In-memory watched state per cache key, paired with the resolve time.
     * [isPartiallyWatched] marks shows started-but-not-finished (the eye
     * badge); it only ever resolves for "series" keys.
     */
    private data class WatchedCacheEntry(
        val isWatched: Boolean,
        val isPartiallyWatched: Boolean
    )

    private val cacheMutex =
        Mutex()

    /*
     * Persistent local "Mark as Watched" overrides, stored as a
     * SharedPreferences string set of watched keys ("movie::tt123456").
     * These win over the remote-derived SIMKL state so a title the user
     * manually marked stays marked even after remote refreshes, and they
     * survive app restarts without a database migration.
     */
    private val overridesPrefs
        get() = com.kennyb1201.kbstream.data.addon.AppContextHolder.appContext
            ?.let { appContext ->
                appContext.getSharedPreferences(
                    com.kennyb1201.kbstream.data.sync.ProfileStorage.prefsName(
                        appContext,
                        "kbstream_watched_overrides"
                    ),
                    Context.MODE_PRIVATE
                )
            }
                ?: context.getSharedPreferences(
                    "kbstream_watched_overrides",
                    Context.MODE_PRIVATE
                )

    /*
     * Only one preload may refresh SIMKL sets at a time. This avoids:
     * - duplicate full-library SIMKL fetches
     * - stale completion overwriting newer marker state
     * - simultaneous Room writes for the same poster set
     */
    private val preloadMutex =
        Mutex()

    private var completedMovieKeys:
        Set<String> =
        emptySet()

    private var completedShowImdbIds:
        Set<String> =
        emptySet()

    /*
     * Shows the user has STARTED on Simkl but not finished (watched episode
     * count > 0, not fully watched). Resolved from the same cached all-shows
     * response as the completed set, so it costs no extra network call.
     */
    private var partialShowImdbIds:
        Set<String> =
        emptySet()

    /*
     * TMDB forms of the two Simkl show sets above ("tmdb:<n>"), read from
     * the same cached all-shows response. Plenty of rails carry TMDB ids
     * rather than IMDb ones — every TMDB-discover rail, and therefore all of
     * the hardcoded kids rails — and an imdb-only set can never match them,
     * which is why those posters stayed bare even though Simkl knew the
     * show. Both forms are checked, so neither rail style misses a badge.
     */
    private var completedShowTmdbKeys:
        Set<String> =
        emptySet()

    private var partialShowTmdbKeys:
        Set<String> =
        emptySet()

    private var simklSetsFetchedAt =
        0L

    /*
     * MDBList watch-history snapshot (GET /sync/watched), fetched alongside
     * the Simkl sets so a title watched through another client (or marked
     * on MDBList directly) shows the same watched badge here. Movies are
     * completed; show entries only prove progress; episode entries carry
     * the real per-episode watch state ("tt123:1:2" / "tmdb:456:1:2").
     */
    private var mdbListMovieKeys:
        Set<String> =
        emptySet()

    private var mdbListEpisodeKeys:
        Set<String> =
        emptySet()

    private var mdbListStartedShowKeys:
        Set<String> =
        emptySet()

    private var mdbListFetchedAt = 0L

    /*
     * Backup restore replaces the Room watched tables while repository
     * instances may still hold in-memory snapshots. This tracks the global
     * invalidation epoch so stale memory state is dropped on the next
     * preload instead of lingering until the memory TTL expires.
     */
    @Volatile
    private var cacheEpochObserved: Long =
        globalCacheEpoch

    private val _watchedStateVersion =
        MutableStateFlow(0L)

    val watchedStateVersion:
        StateFlow<Long> =
        _watchedStateVersion.asStateFlow()

    data class BackgroundRefreshResult(
        val attempted: Boolean,
        val changed: Boolean,
        val refreshedCount: Int,
        val success: Boolean,
        val errorMessage: String? =
            null
    )

    fun observeWatchUpdates():
        StateFlow<Long> =
        _watchedStateVersion

    fun observeIsWatched(
        imdbId: String,
        mediaType: String
    ): StateFlow<Boolean> {

        val normalizedId =
            imdbId.trim()

        val normalizedType =
            normalizeType(
                mediaType
            )

        return _watchedStateVersion
            .map {
                cacheMutex.withLock {
                    cache[
                        cacheKey(
                            normalizedId,
                            normalizedType
                        )
                    ]?.second?.isWatched ?: false
                }
            }
            .stateIn(
                scope =
                    repositoryScope,

                started =
                    SharingStarted
                        .WhileSubscribed(
                            5_000L
                        ),

                initialValue =
                    false
            )
    }

    suspend fun preload(
        items: List<Pair<String, String>>
    ) {
        preload(
            items =
                items,

            forceRemoteRefresh =
                false
        )
    }

    suspend fun preload(
        items: List<Pair<String, String>>,
        forceRemoteRefresh: Boolean
    ) {
        preloadMutex.withLock {
            preloadLocked(
                items =
                    items,

                forceRemoteRefresh =
                    forceRemoteRefresh
            )
        }
    }

    private suspend fun preloadLocked(
        items: List<Pair<String, String>>,
        forceRemoteRefresh: Boolean
    ) {

        val now =
            System.currentTimeMillis()

        val appContext =
            com.kennyb1201.kbstream.data.addon.AppContextHolder.appContext

        /*
         * Profile capture for switch-safety: this preload can take seconds
         * (Simkl + MDBList network round-trips). If the user switches
         * profiles mid-flight, every write below (memory cache + the
         * PROFILE-SCOPED Room watched table) would resolve against the NEW
         * profile — landing the OLD profile's watched resolutions in the
         * new profile's stores as phantom checkmarks (with clean Simkl/
         * MDBList dashboards, because the data never came from them).
         * Everything after the fetches bails when the active profile no
         * longer matches this capture.
         */
        val profileAtStart =
            com.kennyb1201.kbstream.data.sync.ProfileManager.activeProfile.value?.id

        fun profileChanged(): Boolean =
            com.kennyb1201.kbstream.data.sync.ProfileManager.activeProfile.value?.id !=
                profileAtStart

        if (cacheEpochObserved != globalCacheEpoch) {
            cacheMutex.withLock {
                cache.clear()

                completedMovieKeys =
                    emptySet()

                completedShowImdbIds =
                    emptySet()

                partialShowImdbIds =
                    emptySet()

                completedShowTmdbKeys =
                    emptySet()

                partialShowTmdbKeys =
                    emptySet()

                simklSetsFetchedAt =
                    0L

                mdbListMovieKeys =
                    emptySet()

                mdbListEpisodeKeys =
                    emptySet()

                mdbListStartedShowKeys =
                    emptySet()

                mdbListFetchedAt =
                    0L
            }

            cacheEpochObserved =
                globalCacheEpoch
        }

        val distinctItems =
            items
                .asSequence()
                .mapNotNull { (id, type) ->

                    val normalizedId =
                        id.trim()

                    if (
                        normalizedId.isBlank()
                    ) {
                        null
                    } else {
                        normalizedId to
                            normalizeType(
                                type
                            )
                    }
                }
                .distinct()
                .toList()

        if (
            distinctItems.isEmpty()
        ) {
            return
        }

        val missingFromMemory =
            cacheMutex.withLock {
                distinctItems.filter {
                    (id, type) ->

                    val cached =
                        cache[
                            cacheKey(
                                id,
                                type
                            )
                        ]

                    cached == null ||
                        now - cached.first >=
                        ttlMsFor(
                            cached.second.isWatched,
                            cached.second.isPartiallyWatched
                        )
                }
            }

        if (
            missingFromMemory.isEmpty() &&
            !forceRemoteRefresh
        ) {
            Log.i(
                "WATCHED_REPO",
                "all watched statuses " +
                    "served from memory cache"
            )

            return
        }

        // SQLite (and therefore Room's generated "IN (?,?,...)" query)
        // caps a single statement at 999 bound variables. A large
        // library can easily exceed that in one preload call, which
        // previously failed the whole lookup outright (SQLITE_ERROR) and
        // silently fell back to "nothing cached on disk" for every key
        // in that batch. Chunking keeps each query under the limit and
        // merges the results back together.
        val diskEntries =
            try {
                missingFromMemory
                    .map { (id, type) ->
                        cacheKey(
                            id,
                            type
                        )
                    }
                    .chunked(
                        SQLITE_MAX_QUERY_VARIABLES
                    )
                    .flatMap { chunk ->
                        watchedStatusDao.getByKeys(
                            chunk
                        )
                    }
            } catch (e: Exception) {
                Log.e(
                    "WATCHED_REPO",
                    "disk cache lookup failed: " +
                        e.message,
                    e
                )

                emptyList()
            }

        cacheMutex.withLock {
            diskEntries.forEach { entry ->

                if (
                    now - entry.updatedAt <
                    ttlMsFor(
                        entry.isWatched,
                        entry.isPartiallyWatched
                    )
                ) {
                    cache[entry.key] =
                        entry.updatedAt to
                            WatchedCacheEntry(
                                entry.isWatched,
                                entry.isPartiallyWatched
                            )
                }
            }
        }

        val needsLookup =
            cacheMutex.withLock {
                distinctItems.filter {
                    (id, type) ->

                    val cached =
                        cache[
                            cacheKey(
                                id,
                                type
                            )
                        ]

                    forceRemoteRefresh ||
                        cached == null ||
                        now - cached.first >=
                        ttlMsFor(
                            cached.second.isWatched,
                            cached.second.isPartiallyWatched
                        )
                }
            }

        Log.i(
            "WATCHED_REPO",
            "preload items=${items.size}, " +
                "needsLookup=${needsLookup.size}, " +
                "force=$forceRemoteRefresh"
        )

        if (
            needsLookup.isEmpty()
        ) {
            return
        }

        val simklConfigured =
            simklRepository.isConfigured() &&
                simklRepository.hasToken()

        // MDBList watched snapshot: refreshed on the same cadence as the
        // Simkl sets so badges merge both trackers. Fetched BEFORE the
        // Simkl early-return below — a user with only an MDBList key set
        // (no Simkl auth) must still get MDBList-backed badges.
        refreshMdbListSetsIfNeeded(
            appContext = appContext,
            now = now,
            force = forceRemoteRefresh,
            profileAtStart = profileAtStart
        )

        if (
            !simklConfigured
        ) {
            Log.i(
                "WATCHED_REPO",
                "Skipping SIMKL watched preload: " +
                    "not authenticated"
            )

            // Still resolve + persist: with only an MDBList key set (no
            // Simkl auth) this is the ONLY remote badge source.
            if (profileChanged()) {
                Log.i(
                    "WATCHED_REPO",
                    "preload aborted: profile switched mid-flight"
                )
                return
            }

            val mdbListOnlyEntities =
                resolveWithMergedRemoteSets(
                    items = needsLookup,
                    now = now
                )

            persistResolvedEntities(
                entities = mdbListOnlyEntities,
                now = now
            )

            _watchedStateVersion.value =
                System.currentTimeMillis()

            return
        }

        val activityChanged =
            if (
                forceRemoteRefresh
            ) {
                true
            } else {
                try {
                    simklRepository
                        .hasWatchedActivityChanged()
                } catch (e: Exception) {
                    Log.e(
                        "WATCHED_REPO",
                        "hasWatchedActivityChanged failed: " +
                            e.message,
                        e
                    )

                    false
                }
            }

        val currentRemoteState =
            cacheMutex.withLock {
                Triple(
                    completedMovieKeys,
                    completedShowImdbIds,
                    simklSetsFetchedAt
                )
            }

        val remoteSetsCold =
            currentRemoteState.first.isEmpty() ||
                currentRemoteState.second.isEmpty()

        val remoteSetsStale =
            now - currentRemoteState.third >=
                REMOTE_SET_TTL_MS

        val shouldRefreshRemoteSets =
            forceRemoteRefresh ||
                activityChanged ||
                remoteSetsCold ||
                remoteSetsStale

        if (
            shouldRefreshRemoteSets
        ) {
            val refreshedMovieKeys =
                try {
                    simklRepository
                        .getCompletedMovieKeys()
                } catch (e: Exception) {
                    Log.e(
                        "WATCHED_REPO",
                        "getCompletedMovieKeys failed: " +
                            e.message,
                        e
                    )

                    emptySet()
                }

            val refreshedShowImdbIds =
                try {
                    /*
                     * This reads one cached/all-show
                     * SIMKL response, not one request
                     * for each poster.
                     */
                    simklRepository
                        .getCompletedShowImdbIds()
                } catch (e: Exception) {
                    Log.e(
                        "WATCHED_REPO",
                        "getCompletedShowImdbIds failed: " +
                            e.message,
                        e
                    )

                    emptySet()
                }

            // Same cached all-shows response as the completed set, so this
            // resolves the eye-badge set for free alongside the checkmarks.
            val refreshedPartialShowImdbIds =
                try {
                    simklRepository
                        .getPartiallyWatchedShowImdbIds()
                } catch (e: Exception) {
                    Log.e(
                        "WATCHED_REPO",
                        "getPartiallyWatchedShowImdbIds failed: " +
                            e.message,
                        e
                    )

                    emptySet()
                }

            // TMDB-id twins of the two show sets. Same cached response, so
            // these add no network round-trip — they only close the gap for
            // rails whose items carry "tmdb:<n>" ids instead of "tt…".
            val refreshedShowTmdbKeys =
                try {
                    simklRepository
                        .getCompletedShowTmdbKeys()
                } catch (e: Exception) {
                    Log.e(
                        "WATCHED_REPO",
                        "getCompletedShowTmdbKeys failed: " +
                            e.message,
                        e
                    )

                    emptySet()
                }

            val refreshedPartialShowTmdbKeys =
                try {
                    simklRepository
                        .getPartiallyWatchedShowTmdbKeys()
                } catch (e: Exception) {
                    Log.e(
                        "WATCHED_REPO",
                        "getPartiallyWatchedShowTmdbKeys failed: " +
                            e.message,
                        e
                    )

                    emptySet()
                }

            cacheMutex.withLock {
                completedMovieKeys =
                    refreshedMovieKeys

                completedShowImdbIds =
                    refreshedShowImdbIds

                partialShowImdbIds =
                    refreshedPartialShowImdbIds

                completedShowTmdbKeys =
                    refreshedShowTmdbKeys

                partialShowTmdbKeys =
                    refreshedPartialShowTmdbKeys

                simklSetsFetchedAt =
                    now
            }

            Log.i(
                "WATCHED_REPO",
                "SIMKL marker sets refreshed: " +
                    "movies=${refreshedMovieKeys.size}, " +
                    "series=${refreshedShowImdbIds.size}"
            )
        }

        val resolvedEntities =
            if (profileChanged()) {
                Log.i(
                    "WATCHED_REPO",
                    "preload aborted before persist: profile switched mid-flight"
                )
                return
            } else {
                resolveWithMergedRemoteSets(
                    items = needsLookup,
                    now = now
                )
            }

        persistResolvedEntities(
            entities = resolvedEntities,
            now = now
        )

        if (
            forceRemoteRefresh ||
            activityChanged
        ) {
            try {
                simklRepository
                    .markWatchedActivitySynced()

            } catch (e: Exception) {
                Log.e(
                    "WATCHED_REPO",
                    "markWatchedActivitySynced failed: " +
                        e.message,
                    e
                )
            }
        }

        _watchedStateVersion.value =
            System.currentTimeMillis()

        Log.i(
            "WATCHED_REPO",
            "cache updated for " +
                "${resolvedEntities.size} items"
        )
    }

    suspend fun preloadAndGetWatchedKeys(
        items: List<Pair<String, String>>
    ): Set<String> {

        preload(
            items
        )

        return watchedKeysFromCache(
            items
        )
    }

    /**
     * Eye-badge twin of [preloadAndGetWatchedKeys]: preloads the batch, then
     * returns the keys resolved as started-but-not-finished. Callers subtract
     * the fully-watched keys themselves so the checkmark wins the corner.
     */
    suspend fun preloadAndGetPartiallyWatchedKeys(
        items: List<Pair<String, String>>
    ): Set<String> {

        preload(
            items
        )

        return cacheMutex.withLock {
            items
                .asSequence()
                .mapNotNull { (id, type) ->

                    val normalizedId =
                        id.trim()

                    if (
                        normalizedId.isBlank()
                    ) {
                        return@mapNotNull null
                    }

                    val normalizedType =
                        normalizeType(
                            type
                        )

                    val key =
                        cacheKey(
                            normalizedId,
                            normalizedType
                        )

                    if (
                        cache[key]?.second?.isPartiallyWatched == true
                    ) {
                        key
                    } else {
                        null
                    }
                }
                .toSet()
        }
    }

    /**
     * Marks this device's cached Simkl marker sets as stale so the next
     * preload re-reads them from the server instead of answering from the
     * snapshot taken before a watched-state write.
     *
     * The write that unmarks part of a series rewrites the tracker record - 
     * and clears Simkl's own caches - but this repository keeps its OWN copy
     * of the completed/partial show sets for [REMOTE_SET_TTL_MS]. Left in
     * place, that copy kept resolving the show as fully watched, so the
     * poster stayed on the completed checkmark and the eye never appeared,
     * for up to 15 minutes after the unmark.
     */
    suspend fun invalidateRemoteWatchSets() {
        cacheMutex.withLock {
            simklSetsFetchedAt = 0L
        }

        _watchedStateVersion.value =
            System.currentTimeMillis()
    }

    suspend fun forceRefresh(
        items: List<Pair<String, String>>
    ): Set<String> {

        preload(
            items =
                items,

            forceRemoteRefresh =
                true
        )

        return watchedKeysFromCache(
            items
        )
    }

    suspend fun refreshRemoteWatchStateIfNeeded(
        items: List<Pair<String, String>>
    ): BackgroundRefreshResult {

        val normalizedItems =
            items
                .asSequence()
                .mapNotNull {
                    (id, type) ->

                    val normalizedId =
                        id.trim()

                    if (
                        normalizedId.isBlank()
                    ) {
                        null
                    } else {
                        normalizedId to
                            normalizeType(
                                type
                            )
                    }
                }
                .distinct()
                .toList()

        if (
            normalizedItems.isEmpty()
        ) {
            return BackgroundRefreshResult(
                attempted =
                    false,

                changed =
                    false,

                refreshedCount =
                    0,

                success =
                    true
            )
        }

        val refreshProbe =
            try {
                simklRepository
                    .refreshWatchedActivity()

            } catch (e: Exception) {
                Log.e(
                    "WATCHED_REPO",
                    "refreshWatchedActivity failed: " +
                        e.message,
                    e
                )

                return BackgroundRefreshResult(
                    attempted =
                        true,

                    changed =
                        false,

                    refreshedCount =
                        0,

                    success =
                        false,

                    errorMessage =
                        e.message
                )
            }

        if (
            !refreshProbe.attempted
        ) {
            return BackgroundRefreshResult(
                attempted =
                    false,

                changed =
                    false,

                refreshedCount =
                    0,

                success =
                    true,

                errorMessage =
                    refreshProbe.errorMessage
            )
        }

        if (
            !refreshProbe.changed
        ) {
            return BackgroundRefreshResult(
                attempted =
                    true,

                changed =
                    false,

                refreshedCount =
                    0,

                success =
                    true
            )
        }

        return try {
            preload(
                items =
                    normalizedItems,

                forceRemoteRefresh =
                    true
            )

            BackgroundRefreshResult(
                attempted =
                    true,

                changed =
                    true,

                refreshedCount =
                    normalizedItems.size,

                success =
                    true
            )

        } catch (e: Exception) {
            Log.e(
                "WATCHED_REPO",
                "remote watched refresh failed: " +
                    e.message,
                e
            )

            BackgroundRefreshResult(
                attempted =
                    true,

                changed =
                    true,

                refreshedCount =
                    0,

                success =
                    false,

                errorMessage =
                    e.message
            )
        }
    }

    fun clearRemoteSyncCheckpoint() {
        simklRepository
            .forceClearWatchedActivitySync()
    }

    suspend fun clearAllWatchState() {
        clearLocalWatchState(clearSimklAuth = true)
    }

    /**
     * Clear local watch state (memory cache + Room). When [clearSimklAuth]
     * is true the Simkl link itself is dropped too (account-switch path).
     * The Settings "Clear Continue Watching" action passes false so scrobble
     * history on Simkl's side stays untouched and the account stays linked.
     */
    suspend fun clearLocalWatchState(clearSimklAuth: Boolean) {
        preloadMutex.withLock {

            cacheMutex.withLock {
                cache.clear()

                completedMovieKeys =
                    emptySet()

                completedShowImdbIds =
                    emptySet()

                partialShowImdbIds =
                    emptySet()

                completedShowTmdbKeys =
                    emptySet()

                partialShowTmdbKeys =
                    emptySet()

                simklSetsFetchedAt =
                    0L
            }

            try {
                watchedStatusDao.clearAll()
            } catch (e: Exception) {
                Log.e(
                    "WATCHED_REPO",
                    "Failed to clear watched-status " +
                        "cache: ${e.message}",
                    e
                )
            }

            if (clearSimklAuth) {
                simklRepository.clearAuth()
            }

            _watchedStateVersion.value =
                System.currentTimeMillis()
        }

        Log.i(
            "WATCHED_REPO",
            "Cleared local watch state (simklAuthCleared=$clearSimklAuth)"
        )
    }

    private suspend fun watchedKeysFromCache(
        items: List<Pair<String, String>>
    ): Set<String> {

        return cacheMutex.withLock {
            items
                .asSequence()
                .mapNotNull {
                    (id, type) ->

                    val normalizedId =
                        id.trim()

                    if (
                        normalizedId.isBlank()
                    ) {
                        return@mapNotNull null
                    }

                    val normalizedType =
                        normalizeType(
                            type
                        )

                    val key =
                        cacheKey(
                            normalizedId,
                            normalizedType
                        )

                    if (
                        cache[key]?.second?.isWatched == true
                    ) {
                        key
                    } else {
                        null
                    }
                }
                .toSet()
        }
    }

    private suspend fun isMovieLocallyWatched(
        id: String
    ): Boolean {

        val entry =
            try {
                historyDao.getById(
                    id
                )
            } catch (e: Exception) {
                null
            }

        return entry != null &&
            entry.durationMs > 0L &&
            entry.positionMs
                .toFloat() /
                entry.durationMs
                    .toFloat() >=
            LOCAL_WATCHED_THRESHOLD
    }

    /**
     * Local "started but not finished" signal for a series: an in-progress
     * watch-history row (resume position saved, episode not completed).
     * Used when Simkl has no entry for the show so the eye badge still
     * reflects purely-local viewing.
     */
    private suspend fun hasLocalInProgressEpisode(
        id: String
    ): Boolean {

        return try {
            historyDao.getResumeForParent(id) != null
        } catch (e: Exception) {
            false
        }
    }

    suspend fun isWatchedCached(
        id: String,
        type: String
    ): Boolean {

        val normalizedId =
            id.trim()

        if (
            normalizedId.isBlank()
        ) {
            return false
        }

        return cacheMutex.withLock {
            cache[
                cacheKey(
                    normalizedId,
                    type
                )
            ]?.second?.isWatched ?: false
        }
    }

    /**
     * True when the cached state marks this title as started-but-not-
     * finished (the eye badge). Only ever true for series keys; movies
     * resolve watched-or-not, nothing in between.
     */
    suspend fun isPartiallyWatchedCached(
        id: String,
        type: String
    ): Boolean {

        val normalizedId =
            id.trim()

        if (
            normalizedId.isBlank()
        ) {
            return false
        }

        return cacheMutex.withLock {
            cache[
                cacheKey(
                    normalizedId,
                    type
                )
            ]?.second?.isPartiallyWatched ?: false
        }
    }

    private fun cacheKey(
        id: String,
        type: String
    ): String {
        return "${normalizeType(type)}::${id.trim()}"
    }

    private val tmdbRepository: TmdbRepository by lazy {
        TmdbRepository.getInstance(context)
    }

    /**
     * Writes [keys] into the persistent manual-override set (or removes them
     * when [watched] is false), mirrors the result into the in-memory cache so
     * every badge repaints at once, and wakes the watch-state listeners.
     *
     * Lives in one place because a mark and an unmark have to touch the same
     * keys under the same rule: the id the user pressed is applied instantly,
     * the title's twin flavor follows (see [watchedIdForms]).
     */
    private suspend fun applyOverrideKeys(
        keys: Set<String>,
        now: Long,
        watched: Boolean
    ) {
        if (keys.isEmpty()) return

        val updatedKeys =
            (localWatchedOverrideKeys()
                .toMutableSet()
                .apply {
                    if (watched) {
                        addAll(keys)
                    } else {
                        removeAll(keys)
                    }
                })

        overridesPrefs
            .edit()
            .putStringSet(
                KEY_WATCHED_OVERRIDES,
                updatedKeys
            )
            .apply()

        cacheMutex.withLock {
            keys.forEach { target ->
                if (watched) {
                    cache[target] =
                        now to WatchedCacheEntry(
                            isWatched = true,
                            isPartiallyWatched = false
                        )
                } else {
                    cache[target] =
                        now to WatchedCacheEntry(
                            isWatched = false,
                            isPartiallyWatched = false
                        )
                }
            }
        }

        _watchedStateVersion.value =
            now

        keys.forEach { target ->
            WatchStateBus.notifyChanged(
                target,
                watched
            )
        }
    }

    /**
     * The other id flavor that names the SAME title as [id].
     *
     * A title is reachable as "tt..." (add-on catalogs, Continue Watching)
     * and as "tmdb:<n>" (TMDB search rows, the hardcoded kids rails), but a
     * manual "Mark as Watched" override and a playback row are stored under
     * one flavor only. Without the twin, marking from one surface left the
     * other surface's copy unmarked - and unmarking it there removed a key
     * that was never written, so the badge stayed on.
     *
     * IMDB -> TMDB reads the local resolution table first and only then asks
     * TMDB (result cached both ways); TMDB -> IMDB goes through the shared
     * [TmdbRepository.resolveImdbId] cache.
     */
    private suspend fun twinIdFor(
        id: String,
        normalizedType: String
    ): String? {
        val raw = id.trim()
        if (raw.isBlank()) return null

        return try {
            when {
                raw.startsWith("tt") ->
                    tmdbRepository.resolveTmdbId(raw, normalizedType)
                        ?.takeIf { it > 0 }
                        ?.let { "tmdb:$it" }

                raw.startsWith("tmdb:") || raw.all(Char::isDigit) -> run {
                    val tmdbId = raw.removePrefix("tmdb:").toIntOrNull() ?: return@run null
                    tmdbRepository.resolveImdbId(tmdbId, normalizedType)
                        ?.trim()
                        ?.takeIf { it.startsWith("tt") }
                }

                else -> null
            }
        } catch (e: Exception) {
            Log.w("WATCHED_REPO", "twin resolution failed for $raw: ${e.message}")
            null
        }
    }

    /** Every id form this title should be marked/cleared under. */
    private suspend fun watchedIdForms(
        id: String,
        normalizedType: String
    ): Set<String> {
        val forms = linkedSetOf(id.trim())
        twinIdFor(id, normalizedType)?.let { forms += it }
        return forms
    }

    /**
     * Offline expansion of stored override keys with their twin flavor, read
     * from the resolution table only - the preload path runs for every
     * visible poster and must never issue a request per item. Marks made by
     * this build already store both flavors; this is for overrides written
     * before it existed.
     */
    /** Memo for [expandOverrideTwins]: the raw override set it was derived
     *  from and the expanded result. Self-invalidating — a changed override
     *  set never matches, so no explicit invalidation is needed. */
    @Volatile
    private var overrideTwinCache: Pair<Set<String>, Set<String>>? = null

    private suspend fun expandOverrideTwins(
        keys: Set<String>
    ): Set<String> {
        if (keys.isEmpty()) return keys

        overrideTwinCache?.let { (cachedFor, expanded) ->
            if (cachedFor == keys) return expanded
        }

        val expanded = keys.toMutableSet()
        val dao = try {
            WatchHistoryDatabase.getInstanceScoped(context).imdbResolutionDao()
        } catch (e: Exception) {
            null
        } ?: return expanded

        for (entry in keys) {
            val separator = entry.indexOf("::")
            if (separator <= 0) continue

            val normalizedType = entry.substring(0, separator)
            val id = entry.substring(separator + 2).trim()
            if (id.isBlank()) continue

            val twin = try {
                when {
                    id.startsWith("tmdb:") -> {
                        val tmdbId = id.removePrefix("tmdb:").toIntOrNull()
                        if (tmdbId == null) null
                        else dao.getByKey("$normalizedType::$tmdbId")
                            ?.imdbId
                            ?.takeIf { it.startsWith("tt") }
                    }

                    id.startsWith("tt") ->
                        dao.getByImdbId(id, normalizedType)
                            ?.tmdbId
                            ?.takeIf { it > 0 }
                            ?.let { "tmdb:$it" }

                    else -> null
                }
            } catch (e: Exception) {
                null
            }

            if (twin != null) {
                expanded += "$normalizedType::$twin"
            }
        }

        overrideTwinCache = keys to expanded
        return expanded
    }

    /**
     * How long a resolved row may answer for a key before it is recomputed.
     *
     * A POSITIVE row (watched, or started-but-unfinished) is the expensive
     * answer, the one that draws the badge, and the one that only changes
     * when the user finishes something — it keeps the long TTL. A NEGATIVE
     * row is the cheap "no watched state found" answer, and its trusted
     * source can appear seconds later: the other TV's mark arrives, Simkl
     * catches up, a history row lands, a synced row is applied (which lands
     * the flag correctly now, but a row written by an older build still
     * carries only one of the two flags). Holding a negative for the full
     * positive TTL is what turned one bad sync into "the badges are wrong on
     * this TV all evening", so negatives age out on the short TTL and the
     * badge repaints itself a couple of minutes later.
     */
    private fun ttlMsFor(
        isWatched: Boolean,
        isPartiallyWatched: Boolean
    ): Long =
        if (
            isWatched ||
            isPartiallyWatched
        ) {
            CACHE_TTL_MS
        } else {
            NEGATIVE_CACHE_TTL_MS
        }

    private fun localWatchedOverrideKeys():
        Set<String> =
        overridesPrefs
            .getStringSet(
                KEY_WATCHED_OVERRIDES,
                emptySet()
            )
            .orEmpty()

    /**
     * Long-press "Mark as Watched": writes a persistent local override so
     * the poster badge shows immediately and survives restarts/remote
     * refreshes. Key format matches the rest of the watched pipeline
     * ("{normalizedType}::{id}"). When SIMKL is connected, the mark is
     * ALSO pushed to the user's Simkl history — movies directly, series
     * as a whole show (Simkl auto-fills every episode). Push failures
     * are logged and never block the local mark.
     */
    suspend fun markWatchedLocal(
        id: String,
        type: String
    ) {
        val normalizedId =
            id.trim()

        if (
            normalizedId.isBlank()
        ) {
            return
        }

        val normalizedType =
            normalizeType(
                type
            )

        val key =
            cacheKey(
                normalizedId,
                normalizedType
            )

        val now =
            System.currentTimeMillis()

        // 1. Instant: the id the user pressed, stored before any twin
        // resolution so the badge flips without waiting on a request.
        applyOverrideKeys(setOf(key), now, watched = true)

        // 2. Best-effort: the same title's OTHER id flavor. A TMDB-keyed badge
        // and an IMDB-keyed badge for one title used to disagree - the mark
        // only "took" on the surface it was made from - and unmarking it on
        // the other surface removed a key that was never written.
        val formKeys = runCatching {
            watchedIdForms(normalizedId, normalizedType)
                .map { form -> cacheKey(form, normalizedType) }
                .filter { formKey -> formKey != key }
        }.getOrDefault(emptyList()).toSet()

        applyOverrideKeys(formKeys, now, watched = true)

        // Cross-device sync: push the mark immediately (last-write-wins).
        com.kennyb1201.kbstream.data.addon.AppContextHolder.appContext?.let { appContext ->
            com.kennyb1201.kbstream.data.sync.SupabaseSync.enqueueWatched(
                com.kennyb1201.kbstream.data.cache.WatchedStatusEntity(
                    key = key,
                    imdbId = normalizedId,
                    mediaType = normalizedType,
                    isWatched = true,
                    updatedAt = now
                )
            )
            com.kennyb1201.kbstream.data.sync.SupabaseSync.enqueuePrefs(
                appContext,
                com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder.KEY_WATCHED_OVERRIDES,
                com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder.buildWatchedOverrides(appContext)
            )
        }

        Log.i(
            "WATCHED_REPO",
            "Local watched override added: $key"
        )

        // Mirror to SIMKL when connected. pushWatchedMovie / pushWatchedShow
        // already swallow their own failures; the extra guard keeps a
        // surprise throw from ever undoing the local mark above.
        if (
            simklRepository.isConfigured() &&
            simklRepository.hasToken()
        ) {
            try {
                when (
                    normalizedType
                ) {
                    "movie" ->
                        simklRepository.pushWatchedMovie(
                            normalizedId
                        )

                    "series" ->
                        simklRepository.pushWatchedShow(
                            normalizedId
                        )
                }
            } catch (e: Exception) {
                Log.e(
                    "WATCHED_REPO",
                    "SIMKL mark-watched push failed for $key",
                    e
                )
            }
        }

        // Mirror whole-title marks to MDBList when a key is set. Movies push
        // as a completed movie and series as a whole-show entry — MDBList
        // expands that ids-only show across every episode server-side, the
        // same shape its /sync/watched/remove already accepts, so both media
        // types reach the account from one long-press. (Series used to be
        // skipped here on the belief that ids alone could not express a
        // whole-show mark, which left MDBList showing nothing after a mark
        // that Simkl recorded fine.)
        com.kennyb1201.kbstream.data.addon.AppContextHolder.appContext
            ?.let { mdbListContext ->
                if (MdbListClient.isConfigured(mdbListContext)) {
                    try {
                        when (normalizedType) {
                            "movie" -> MdbListClient.pushWatched(
                                mdbListContext,
                                mediaType = "movie",
                                imdbId = normalizedId.takeIf { it.startsWith("tt") },
                                tmdbId = normalizedId.removePrefix("tmdb:").toIntOrNull()
                                    ?.takeIf { normalizedId.startsWith("tmdb:") }
                            )

                            "series" -> MdbListClient.pushWatchedShow(
                                mdbListContext,
                                imdbId = normalizedId.takeIf { it.startsWith("tt") },
                                tmdbId = normalizedId.removePrefix("tmdb:").toIntOrNull()
                                    ?.takeIf { normalizedId.startsWith("tmdb:") }
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(
                            "WATCHED_REPO",
                            "MDBList mark-watched push failed for $key",
                            e
                        )
                    }
                }
            }

        // The cached Simkl marker sets still describe the state BEFORE this
        // mark. Without dropping them a preload in the next 15 minutes keeps
        // resolving the title from that snapshot, and the tracker push above
        // (which rewrote the title's record) is exactly what makes the
        // snapshot wrong. Same reasoning for the unmark / partial paths.
        invalidateRemoteWatchSets()
    }

    /**
     * After PART of a series is unmarked: the title is no longer completed but
     * still has watched episodes, so it must resolve as started-but-unfinished
     * - the eye badge - instead of keeping the completed checkmark.
     *
     * Why a dedicated call: the checkmark came from a manual whole-show
     * override PLUS Simkl's cached "completed shows" snapshot. Clearing the
     * override alone left both the snapshot and the on-disk per-key row still
     * saying "watched", so the poster kept its checkmark until the remote-set
     * TTL expired - and the eye never appeared, because nothing had ever
     * recorded "started". This drops every completed trace for the title across
     * both id flavors, records the partial state in memory AND on disk, and
     * wakes the badge listeners.
     */
    suspend fun markPartiallyWatchedLocal(
        id: String,
        type: String
    ) {
        val normalizedId = id.trim()
        if (normalizedId.isBlank()) return

        val normalizedType = normalizeType(type)
        if (normalizedType != "series") return

        val key = cacheKey(normalizedId, normalizedType)
        val now = System.currentTimeMillis()

        val idForms = runCatching { watchedIdForms(normalizedId, normalizedType) }
            .getOrDefault(setOf(normalizedId))
        val formKeys = (idForms.map { form -> cacheKey(form, normalizedType) } + key).toSet()
        val scrubForms = idForms + normalizedId

        // 1. The manual "Mark as Watched" override is what painted the
        // checkmark; leaving it behind would flip the badge straight back on.
        overridesPrefs
            .edit()
            .putStringSet(
                KEY_WATCHED_OVERRIDES,
                localWatchedOverrideKeys()
                    .toMutableSet()
                    .apply { removeAll(formKeys) }
            )
            .apply()

        // 2. In memory: not completed, but started - and scrub every trace of
        // "completed" so the stale snapshot cannot resurrect the checkmark.
        val partialImdbForms = scrubForms.filter { form -> form.startsWith("tt") }.toSet()
        val partialTmdbForms = scrubForms.filter { form -> form.startsWith("tmdb:") }.toSet()

        cacheMutex.withLock {
            formKeys.forEach { formKey ->
                cache[formKey] =
                    now to WatchedCacheEntry(
                        isWatched = false,
                        isPartiallyWatched = true
                    )
            }

            completedMovieKeys =
                completedMovieKeys
                    .filterNot { entry ->
                        scrubForms.any { form -> entry == form || entry == "imdb:$form" }
                    }
                    .toSet()

            completedShowImdbIds = completedShowImdbIds - scrubForms
            completedShowTmdbKeys = completedShowTmdbKeys - scrubForms

            partialShowImdbIds = partialShowImdbIds + partialImdbForms
            partialShowTmdbKeys = partialShowTmdbKeys + partialTmdbForms
        }

        // 3. On disk: the per-key row a cold start reads. The old completed row
        // would otherwise still be inside its TTL and repaint the checkmark.
        val diskRows = formKeys.map { formKey ->
            WatchedStatusEntity(
                key = formKey,
                imdbId = formKey.substringAfter("::"),
                mediaType = normalizedType,
                isWatched = false,
                isPartiallyWatched = true,
                updatedAt = now
            )
        }

        try {
            watchedStatusDao.upsertAll(diskRows)
        } catch (e: Exception) {
            Log.e("WATCHED_REPO", "partial state write failed for $key", e)
        }

        _watchedStateVersion.value = now

        formKeys.forEach { formKey ->
            // Resolved state is "eye", not "unwatched": announcing plain
            // false here made every collector drop the partial flag and the
            // poster lost the eye the unmark had just given it.
            WatchStateBus.notifyChanged(
                watchedKey = formKey,
                isWatched = false,
                isPartiallyWatched = true
            )
        }

        // 4. Cross-device: the partial flag travels with the watched rows, and
        // the override removal with the prefs blob.
        com.kennyb1201.kbstream.data.addon.AppContextHolder.appContext?.let { appContext ->
            diskRows.forEach { row ->
                com.kennyb1201.kbstream.data.sync.SupabaseSync.enqueueWatched(row)
            }
            com.kennyb1201.kbstream.data.sync.SupabaseSync.enqueuePrefs(
                appContext,
                com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder.KEY_WATCHED_OVERRIDES,
                com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder.buildWatchedOverrides(appContext)
            )
        }

        Log.i("WATCHED_REPO", "Local partially-watched state set: $key")

        // The sets this path scrubbed in memory are re-read from the server
        // on the next refresh - but only if the cached copy is marked stale;
        // otherwise the completed set it was scrubbed from comes straight
        // back and the checkmark with it.
        invalidateRemoteWatchSets()
    }

    /**
     * Long-press "Mark as Unwatched": the mirror of [markWatchedLocal].
     * Removes the local watched override so the badge clears immediately and
     * stays cleared, wipes every local cache that could re-seed the watched
     * state, and - when SIMKL is connected - DELETEs the title from the
     * user's Simkl history (movies directly, series as a whole show). Delete
     * failures are logged and never block the local unmark.
     */
    suspend fun markUnwatchedLocal(
        id: String,
        type: String
    ) {
        val normalizedId =
            id.trim()

        if (
            normalizedId.isBlank()
        ) {
            return
        }

        val normalizedType =
            normalizeType(
                type
            )

        val key =
            cacheKey(
                normalizedId,
                normalizedType
            )

        val now =
            System.currentTimeMillis()

        // 1. Drop the manual watched override (the thing "Mark as Watched"
        // wrote). Leaving it in place would make every later resolution flip
        // the badge straight back on. Applied first, for the id the user
        // pressed, so the badge clears without waiting on twin resolution.
        applyOverrideKeys(setOf(key), now, watched = false)

        // 2. Then every other id flavor this title is reachable by -
        // unmarking from one surface used to remove a key that was never
        // written and leave the badge on for the other.
        val idForms = runCatching {
            watchedIdForms(normalizedId, normalizedType)
        }.getOrDefault(setOf(normalizedId))
        val formKeys = (idForms.map { form -> cacheKey(form, normalizedType) } + key).toSet()

        applyOverrideKeys(formKeys, now, watched = false)

        // 2. Force the in-memory cache to false AND scrub the id out of the
        // in-memory SIMKL completed sets. If the id stayed in those sets, a
        // later remote-activity refresh could recompute this title as watched
        // again from the stale snapshot before Simkl's server state is
        // re-fetched.
        cacheMutex.withLock {
            formKeys.forEach { formKey ->
                cache[formKey] =
                    now to WatchedCacheEntry(
                        isWatched = false,
                        isPartiallyWatched = false
                    )
            }

            // Every id form of the title, not just the one that was passed in:
            // the remote sets carry both flavors, so an unmark by IMDB used to
            // leave the "tmdb:<n>" entry behind and a TMDB-keyed rail kept
            // painting the checkmark until the next refresh.
            val scrubForms = idForms + normalizedId

            when (
                normalizedType
            ) {
                "movie" ->
                    completedMovieKeys =
                        completedMovieKeys
                            .filterNot { entry ->
                                scrubForms.any { form ->
                                    entry == form || entry == "imdb:$form"
                                }
                            }
                            .toSet()

                "series" -> {
                    completedShowImdbIds =
                        completedShowImdbIds - scrubForms

                    partialShowImdbIds =
                        partialShowImdbIds - scrubForms

                    // Same scrub for the TMDB-keyed twins, or a later remote
                    // refresh would recompute this show as watched again.
                    completedShowTmdbKeys =
                        completedShowTmdbKeys - scrubForms

                    partialShowTmdbKeys =
                        partialShowTmdbKeys - scrubForms
                }
            }
        }

        // 3. Delete the persisted Room cache row so a cold start / disk read
        // cannot re-seed the watched state from before the unmark.
        try {
            watchedStatusDao.deleteByKey(
                key
            )
        } catch (e: Exception) {
            Log.e(
                "WATCHED_REPO",
                "Failed to delete watched cache row for $key",
                e
            )
        }

        _watchedStateVersion.value =
            now

        WatchStateBus.notifyChanged(
            key,
            false
        )

        // Cross-device sync: push the unmark (isWatched=false wins by ts).
        com.kennyb1201.kbstream.data.addon.AppContextHolder.appContext?.let { appContext ->
            com.kennyb1201.kbstream.data.sync.SupabaseSync.enqueueWatched(
                com.kennyb1201.kbstream.data.cache.WatchedStatusEntity(
                    key = key,
                    imdbId = normalizedId,
                    mediaType = normalizedType,
                    isWatched = false,
                    updatedAt = now
                )
            )
            com.kennyb1201.kbstream.data.sync.SupabaseSync.enqueuePrefs(
                appContext,
                com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder.KEY_WATCHED_OVERRIDES,
                com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder.buildWatchedOverrides(appContext)
            )
        }

        Log.i(
            "WATCHED_REPO",
            "Local watched override removed: $key"
        )

        // 4. Mirror the removal to SIMKL when connected (DELETE history).
        // removeWatchedMovie / removeWatchedShow already swallow their own
        // failures; the extra guard keeps a surprise throw from ever undoing
        // the local unmark above.
        if (
            simklRepository.isConfigured() &&
            simklRepository.hasToken()
        ) {
            try {
                when (
                    normalizedType
                ) {
                    "movie" ->
                        simklRepository.removeWatchedMovie(
                            normalizedId
                        )

                    "series" ->
                        simklRepository.removeWatchedShow(
                            normalizedId
                        )
                }
            } catch (e: Exception) {
                Log.e(
                    "WATCHED_REPO",
                    "SIMKL remove-watched push failed for $key",
                    e
                )
            }
        }

        // Mirror the removal to MDBList when a key is set (movies and
        // whole shows — its /sync/watched/remove accepts ids-only entries
        // for both).
        com.kennyb1201.kbstream.data.addon.AppContextHolder.appContext
            ?.let { mdbListContext ->
                if (MdbListClient.isConfigured(mdbListContext)) {
                    try {
                        when (normalizedType) {
                            "movie" -> MdbListClient.removeWatched(
                                mdbListContext,
                                mediaType = "movie",
                                imdbId = normalizedId.takeIf { it.startsWith("tt") },
                                tmdbId = normalizedId.removePrefix("tmdb:").toIntOrNull()
                                    ?.takeIf { normalizedId.startsWith("tmdb:") }
                            )

                            "series" -> MdbListClient.removeWatchedShow(
                                mdbListContext,
                                imdbId = normalizedId.takeIf { it.startsWith("tt") },
                                tmdbId = normalizedId.removePrefix("tmdb:").toIntOrNull()
                                    ?.takeIf { normalizedId.startsWith("tmdb:") }
                            )
                        }
                    } catch (e: Exception) {
                        Log.e(
                            "WATCHED_REPO",
                            "MDBList remove-watched push failed for $key",
                            e
                        )
                    }
                }
            }

        // Drop the cached Simkl marker sets so the next preload re-reads them
        // instead of resurrecting this title from the pre-unmark snapshot
        // (which is how an unmarked show kept its completed checkmark).
        invalidateRemoteWatchSets()
    }

    /**
     * Drops the manual whole-title watched override WITHOUT touching the
     * trackers, and reports whether one was actually stored.
     *
     * Called when part of a manually marked title is unmarked (one episode,
     * one season): the user is saying the title is not fully watched, so a
     * leftover override would keep resolving the poster as watched forever.
     * The return value doubles as the signal that the trackers hold a
     * whole-title record for this title — the shape a poster "Mark as
     * Watched" writes — which an episode-level removal cannot clear (see the
     * rewrite in the detail screen's unmark path).
     */
    suspend fun clearWatchedOverride(
        id: String,
        type: String
    ): Boolean {

        val normalizedId =
            id.trim()

        if (
            normalizedId.isBlank()
        ) {
            return false
        }

        val normalizedType =
            normalizeType(
                type
            )

        val key =
            cacheKey(
                normalizedId,
                normalizedType
            )

        // Both id flavors of the title, so an override written from the other
        // surface (or by an older build) is cleared too.
        val idForms = watchedIdForms(normalizedId, normalizedType)
        val formKeys = (idForms.map { form -> cacheKey(form, normalizedType) } + key).toSet()

        val overrideKeys =
            localWatchedOverrideKeys()

        if (
            formKeys.none { formKey -> formKey in overrideKeys }
        ) {
            return false
        }

        val now =
            System.currentTimeMillis()

        overridesPrefs
            .edit()
            .putStringSet(
                KEY_WATCHED_OVERRIDES,
                overrideKeys
                    .toMutableSet()
                    .apply {
                        removeAll(formKeys)
                    }
            )
            .apply()

        // The manual mark is gone, so the cached row must go with it: left
        // in place it would keep answering "watched" until its TTL expires.
        cacheMutex.withLock {
            formKeys.forEach { formKey ->
                cache[formKey] =
                    now to WatchedCacheEntry(
                        isWatched = false,
                        isPartiallyWatched = false
                    )
            }
        }

        _watchedStateVersion.value =
            now

        formKeys.forEach { formKey ->
            WatchStateBus.notifyChanged(
                formKey,
                false
            )
        }

        // The override set is profile-synced, so the other devices have to
        // drop it as well or they keep painting the checkmark.
        com.kennyb1201.kbstream.data.addon.AppContextHolder.appContext?.let { appContext ->
            com.kennyb1201.kbstream.data.sync.SupabaseSync.enqueueWatched(
                com.kennyb1201.kbstream.data.cache.WatchedStatusEntity(
                    key = key,
                    imdbId = normalizedId,
                    mediaType = normalizedType,
                    isWatched = false,
                    updatedAt = now
                )
            )
            com.kennyb1201.kbstream.data.sync.SupabaseSync.enqueuePrefs(
                appContext,
                com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder.KEY_WATCHED_OVERRIDES,
                com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder.buildWatchedOverrides(appContext)
            )
        }

        Log.i(
            "WATCHED_REPO",
            "Local watched override cleared: $key"
        )

        // Same as the partial path: the cached remote sets must not answer
        // for this title any more.
        invalidateRemoteWatchSets()

        return true
    }

    private fun normalizeType(
        type: String
    ): String {
        return when (
            type.lowercase()
        ) {
            "movie" ->
                "movie"

            "series",
            "show",
            "tv" ->
                "series"

            else ->
                type.lowercase()
        }
    }

    /**
     * Fetches (or reuses) the MDBList watched snapshot on the same TTL
     * cadence as the Simkl sets, merging it into [mdbListMovieKeys],
     * [mdbListEpisodeKeys] and [mdbListStartedShowKeys]. Fails soft: any
     * network/parse problem keeps the previous snapshot and only advances
     * the timestamp after a successful fetch. No-op when no key is set.
     */
    private suspend fun refreshMdbListSetsIfNeeded(
        appContext: Context?,
        now: Long,
        force: Boolean,
        profileAtStart: String?
    ) {
        if (appContext == null) {
            return
        }

        if (!MdbListClient.isConfigured(appContext)) {
            return
        }

        val stale = now - mdbListFetchedAt >= REMOTE_SET_TTL_MS

        if (!force && !stale) {
            return
        }

        val snapshot =
            try {
                MdbListClient.getWatchedSnapshot(appContext)
            } catch (e: Exception) {
                Log.e(
                    "WATCHED_REPO",
                    "MDBList watched snapshot fetch failed: " +
                        e.message,
                    e
                )

                null
            }

        if (snapshot == null || snapshot.isEmpty) {
            return
        }

        // Drop the result if the profile switched during the fetch: the
        // snapshot was pulled with the OLD profile's MDBList key, and
        // adopting it here would mark the NEW profile's titles watched for
        // the whole 15-minute set TTL (no refetch happens while fetchedAt
        // is fresh).
        if (com.kennyb1201.kbstream.data.sync.ProfileManager.activeProfile.value?.id !=
            profileAtStart
        ) {
            Log.i(
                "WATCHED_REPO",
                "MDBList snapshot dropped: profile switched mid-fetch"
            )
            return
        }

        cacheMutex.withLock {
            mdbListMovieKeys = snapshot.movieKeys
            mdbListEpisodeKeys = snapshot.episodeKeys
            mdbListStartedShowKeys = snapshot.startedShowKeys
            mdbListFetchedAt = now
        }

        Log.i(
            "WATCHED_REPO",
            "MDBList marker sets refreshed: " +
                "movies=${snapshot.movieKeys.size}, " +
                "episodes=${snapshot.episodeKeys.size}, " +
                "shows=${snapshot.startedShowKeys.size}"
        )
    }

    /**
     * Shared resolver for the watched badges: local state first (manual
     * overrides, Room history), then the Simkl sets, then the MDBList
     * snapshot. Runs on every [preload] path — including the
     * Simkl-not-authenticated early return, where MDBList is the only
     * remote source.
     */
    private suspend fun resolveWithMergedRemoteSets(
        items: List<Pair<String, String>>,
        now: Long
    ): List<WatchedStatusEntity> {

        val remoteSnapshot =
            cacheMutex.withLock {
                MergedRemoteSets(
                    completedMovieKeys,
                    completedShowImdbIds,
                    partialShowImdbIds,
                    completedShowTmdbKeys,
                    partialShowTmdbKeys,
                    mdbListMovieKeys,
                    mdbListEpisodeKeys,
                    mdbListStartedShowKeys
                )
            }

        // Twin the stored overrides too, so an override written before this
        // build (single flavor) still resolves for the title's other id.
        val localOverrideKeys =
            expandOverrideTwins(localWatchedOverrideKeys())

        return items.map { (id, normalizedType) ->

            val key =
                cacheKey(
                    id,
                    normalizedType
                )

            val manuallyWatched =
                key in localOverrideKeys

            val watched =
                when (normalizedType) {

                    "movie" -> {
                        val localWatched =
                            isMovieLocallyWatched(
                                id
                            )

                        val simklWatched =
                            id in remoteSnapshot.simklMovieKeys ||
                                "imdb:$id" in
                                remoteSnapshot.simklMovieKeys

                        val mdbListWatched =
                            id in remoteSnapshot.mdbListMovieKeys

                        manuallyWatched ||
                            localWatched ||
                            simklWatched ||
                            mdbListWatched
                    }

                    "series" -> {
                        /*
                         * Membership-only lookup:
                         * no individual network call.
                         *
                         * MDBList's /sync/watched "shows" entries are not
                         * proof of completion (see MdbListWatchedSnapshot —
                         * they only say the show was STARTED), so they must
                         * not land in this completed set: doing so painted
                         * the completed checkmark over in-progress shows and
                         * hid the eye badge. They stay in the partial set
                         * below, which is exactly what "started" means.
                         */
                        manuallyWatched ||
                            id in remoteSnapshot.simklShowKeys ||
                            id in remoteSnapshot.simklShowTmdbKeys
                    }

                    else ->
                        false
                }

            /*
             * Eye badge: a series the user has started but not finished.
             * Simkl side first (free membership lookup in the all-shows
             * snapshot); when Simkl has no entry, fall back to local
             * history — any in-progress episode row (resume position
             * saved, not completed) means "in the middle of it". Movies
             * resolve watched-or-not, nothing in between.
             */
            val partialShow =
                normalizedType == "series" &&
                    !watched &&
                    (
                        id in remoteSnapshot.simklPartialShowKeys ||
                            id in remoteSnapshot.simklPartialShowTmdbKeys ||
                            id in remoteSnapshot.mdbListStartedShowKeys ||
                            hasLocalInProgressEpisode(id)
                        )

            WatchedStatusEntity(
                key =
                    cacheKey(
                        id,
                        normalizedType
                    ),

                imdbId =
                    id,

                mediaType =
                    normalizedType,

                isWatched =
                    watched,

                isPartiallyWatched =
                    partialShow,

                updatedAt =
                    now
            )
        }
    }

    /**
     * Writes resolved entities into the memory cache and the Room disk
     * cache (chunked under SQLite's bind-variable cap).
     */
    private suspend fun persistResolvedEntities(
        entities: List<WatchedStatusEntity>,
        now: Long
    ) {
        cacheMutex.withLock {
            entities.forEach { entity ->
                cache[entity.key] =
                    entity.updatedAt to
                        WatchedCacheEntry(
                            entity.isWatched,
                            entity.isPartiallyWatched
                        )
            }
        }

        try {
            // Room's generated INSERT OR REPLACE also binds one "?" per
            // column per row, so a large batch can hit the same 999-limit
            // as the SELECT above. Chunk this write the same way.
            entities
                .chunked(
                    SQLITE_MAX_UPSERT_ROWS
                )
                .forEach { chunk ->
                    watchedStatusDao.upsertAll(
                        chunk
                    )
                }

            watchedStatusDao.deleteOlderThan(
                now - MAX_DISK_AGE_MS
            )
        } catch (e: Exception) {
            Log.e(
                "WATCHED_REPO",
                "disk cache write failed: " +
                    e.message,
                e
            )
        }
    }

    /**
     * Immutable snapshot of every remote watched set read under one
     * [cacheMutex] acquisition, so the resolver works from a coherent
     * Simkl + MDBList state even if a background refresh lands mid-loop.
     */
    private data class MergedRemoteSets(
        val simklMovieKeys: Set<String>,
        val simklShowKeys: Set<String>,
        val simklPartialShowKeys: Set<String>,
        // "tmdb:<n>" forms of the same two show sets (see the field docs).
        val simklShowTmdbKeys: Set<String>,
        val simklPartialShowTmdbKeys: Set<String>,
        val mdbListMovieKeys: Set<String>,
        val mdbListEpisodeKeys: Set<String>,
        val mdbListStartedShowKeys: Set<String>
    )

    companion object {

        private const val KEY_WATCHED_OVERRIDES =
            "watched_overrides"

        private const val CACHE_TTL_MS =
            6L * 60L * 60L * 1000L

        private const val REMOTE_SET_TTL_MS =
            15L * 60L * 1000L

        /*
         * TTL for NEGATIVE results only ("nothing watched here"), the row
         * every preloaded rail item gets. Re-deriving one is cheap (an
         * in-memory set lookup plus one indexed local history read), and a
         * stale negative is exactly what hides a badge that should be there
         * — so these age out in minutes while positive rows keep [CACHE_TTL_MS].
         * See [ttlMsFor].
         */
        private const val NEGATIVE_CACHE_TTL_MS =
            2L * 60L * 1000L

        private const val MAX_DISK_AGE_MS =
            14L * 24L * 60L * 60L * 1000L

        private const val LOCAL_WATCHED_THRESHOLD =
            0.9f

        // SQLite's hard cap is 999 bound parameters per statement.
        // Staying comfortably under that (rather than exactly at it)
        // leaves room for whatever else Room's generated query binds.
        private const val SQLITE_MAX_QUERY_VARIABLES =
            900

        // WatchedStatusEntity has 5 columns, so each upserted row binds
        // 5 variables - keep total bound params per statement under the
        // same 999 ceiling.
        private const val SQLITE_MAX_UPSERT_ROWS =
            150

        /*
         * Bumped by a backup restore so every live WatchedStatusRepository
         * drops its in-memory snapshots on the next preload. Reads and
         * writes of a single volatile Long are atomic, and there is only one
         * writer (the restore path), so the increment is safe.
         */
        @Volatile
        private var globalCacheEpoch: Long = 0L

        fun invalidateAllCaches() {
            globalCacheEpoch += 1L
        }
    }
}
