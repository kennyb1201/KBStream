package com.kennyb1201.kbstream.data.watched

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.cache.WatchedStatusEntity
import com.kennyb1201.kbstream.data.cache.WatchedStatusDao
import com.kennyb1201.kbstream.data.history.WatchHistoryDao
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.mdblist.MdbListClient
import com.kennyb1201.kbstream.data.simkl.SimklRepository
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
            Log.d(
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

        Log.d(
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
            Log.d(
                "WATCHED_REPO",
                "Skipping SIMKL watched preload: " +
                    "not authenticated"
            )

            // Still resolve + persist: with only an MDBList key set (no
            // Simkl auth) this is the ONLY remote badge source.
            if (profileChanged()) {
                Log.d(
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

            Log.d(
                "WATCHED_REPO",
                "SIMKL marker sets refreshed: " +
                    "movies=${refreshedMovieKeys.size}, " +
                    "series=${refreshedShowImdbIds.size}"
            )
        }

        val resolvedEntities =
            if (profileChanged()) {
                Log.d(
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

        Log.d(
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

        Log.d(
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

        val updatedKeys =
            (localWatchedOverrideKeys()
                .toMutableSet()
                .apply {
                    add(key)
                })

        overridesPrefs
            .edit()
            .putStringSet(
                KEY_WATCHED_OVERRIDES,
                updatedKeys
            )
            .apply()

        cacheMutex.withLock {
            cache[key] =
                now to WatchedCacheEntry(
                    isWatched = true,
                    isPartiallyWatched = false
                )
        }

        _watchedStateVersion.value =
            now

        WatchStateBus.notifyChanged(
            key,
            true
        )

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

        Log.d(
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

        // Mirror whole-title marks to MDBList when a key is set. Movies
        // push directly; whole-show marks need per-season data MDBList's
        // ids-only body can't express, so series rely on the per-episode
        // pushes from the player completion path instead.
        com.kennyb1201.kbstream.data.addon.AppContextHolder.appContext
            ?.let { mdbListContext ->
                if (
                    MdbListClient.isConfigured(mdbListContext) &&
                    normalizedType == "movie"
                ) {
                    try {
                        MdbListClient.pushWatched(
                            mdbListContext,
                            mediaType = "movie",
                            imdbId = normalizedId.takeIf { it.startsWith("tt") },
                            tmdbId = normalizedId.removePrefix("tmdb:").toIntOrNull()
                                ?.takeIf { normalizedId.startsWith("tmdb:") }
                        )
                    } catch (e: Exception) {
                        Log.e(
                            "WATCHED_REPO",
                            "MDBList mark-watched push failed for $key",
                            e
                        )
                    }
                }
            }
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
        // wrote). Leaving it in place would make every later resolution
        // flip the badge straight back on.
        val updatedKeys =
            (localWatchedOverrideKeys()
                .toMutableSet()
                .apply {
                    remove(key)
                })

        overridesPrefs
            .edit()
            .putStringSet(
                KEY_WATCHED_OVERRIDES,
                updatedKeys
            )
            .apply()

        // 2. Force the in-memory cache to false AND scrub the id out of the
        // in-memory SIMKL completed sets. If the id stayed in those sets, a
        // later remote-activity refresh could recompute this title as watched
        // again from the stale snapshot before Simkl's server state is
        // re-fetched.
        cacheMutex.withLock {
            cache[key] =
                now to WatchedCacheEntry(
                    isWatched = false,
                    isPartiallyWatched = false
                )

            when (
                normalizedType
            ) {
                "movie" ->
                    completedMovieKeys =
                        completedMovieKeys
                            .filterNot {
                                it == "imdb:$normalizedId" ||
                                    it == normalizedId
                            }
                            .toSet()

                "series" -> {
                    completedShowImdbIds =
                        completedShowImdbIds - normalizedId

                    partialShowImdbIds =
                        partialShowImdbIds - normalizedId

                    // Same scrub for the TMDB-keyed twins, or a later remote
                    // refresh would recompute this show as watched again.
                    completedShowTmdbKeys =
                        completedShowTmdbKeys - normalizedId

                    partialShowTmdbKeys =
                        partialShowTmdbKeys - normalizedId
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

        Log.d(
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
            Log.d(
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

        Log.d(
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

        val localOverrideKeys =
            localWatchedOverrideKeys()

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
