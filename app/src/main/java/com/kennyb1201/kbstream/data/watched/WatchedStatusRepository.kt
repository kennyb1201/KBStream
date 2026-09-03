package com.kennyb1201.kbstream.data.watched

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.cache.WatchedStatusEntity
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
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
    context: Context
) {

    private val simklRepository =
        SimklRepository.getInstance(context)

    private val database =
        WatchHistoryDatabase.getInstance(
            context
        )

    private val historyDao =
        database.watchHistoryDao()

    private val watchedStatusDao =
        database.watchedStatusDao()

    private val repositoryScope =
        CoroutineScope(
            Dispatchers.Default +
                SupervisorJob()
        )

    private val cache =
        mutableMapOf<
            String,
            Pair<Long, Boolean>
            >()

    private val cacheMutex =
        Mutex()

    /*
     * Persistent local "Mark as Watched" overrides, stored as a
     * SharedPreferences string set of watched keys ("movie::tt123456").
     * These win over the remote-derived SIMKL state so a title the user
     * manually marked stays marked even after remote refreshes, and they
     * survive app restarts without a database migration.
     */
    private val overridesPrefs =
        context.getSharedPreferences(
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

    private var simklSetsFetchedAt =
        0L

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
                    ]?.second ?: false
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

        if (cacheEpochObserved != globalCacheEpoch) {
            cacheMutex.withLock {
                cache.clear()

                completedMovieKeys =
                    emptySet()

                completedShowImdbIds =
                    emptySet()

                simklSetsFetchedAt =
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
                        CACHE_TTL_MS
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
                    CACHE_TTL_MS
                ) {
                    cache[entry.key] =
                        entry.updatedAt to
                            entry.isWatched
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
                        CACHE_TTL_MS
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

        if (
            !simklConfigured
        ) {
            Log.d(
                "WATCHED_REPO",
                "Skipping SIMKL watched preload: " +
                    "not authenticated"
            )

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

            cacheMutex.withLock {
                completedMovieKeys =
                    refreshedMovieKeys

                completedShowImdbIds =
                    refreshedShowImdbIds

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

        val remoteSnapshot =
            cacheMutex.withLock {
                completedMovieKeys to
                    completedShowImdbIds
            }

        val movieKeys =
            remoteSnapshot.first

        val showImdbIds =
            remoteSnapshot.second

        val localOverrideKeys =
            localWatchedOverrideKeys()

        val resolvedEntities =
            needsLookup.map {
                (id, normalizedType) ->

                val key =
                    cacheKey(
                        id,
                        normalizedType
                    )

                val manuallyWatched =
                    key in localOverrideKeys

                val watched =
                    when (
                        normalizedType
                    ) {

                        "movie" -> {
                            val localWatched =
                                isMovieLocallyWatched(
                                    id
                                )

                            val simklWatched =
                                id in movieKeys ||
                                    "imdb:$id" in
                                    movieKeys

                            manuallyWatched ||
                                localWatched ||
                                simklWatched
                        }

                        "series" -> {
                            /*
                             * Membership-only lookup:
                             * no individual network call.
                             */
                            manuallyWatched ||
                                id in showImdbIds
                        }

                        else ->
                            false
                    }

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

                    updatedAt =
                        now
                )
            }

        cacheMutex.withLock {
            resolvedEntities.forEach { entity ->
                cache[entity.key] =
                    entity.updatedAt to
                        entity.isWatched
            }
        }

        try {
            // Room's generated INSERT OR REPLACE also binds one "?" per
            // column per row, so a large batch can hit the same 999-limit
            // as the SELECT above. Chunk this write the same way.
            resolvedEntities
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

        /*
         * Clear the in-memory, Room, and SIMKL
         * auth/cache state together. This prevents
         * markers from a previously linked account
         * appearing after account changes.
         */
        preloadMutex.withLock {

            cacheMutex.withLock {
                cache.clear()

                completedMovieKeys =
                    emptySet()

                completedShowImdbIds =
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

            simklRepository.clearAuth()

            _watchedStateVersion.value =
                System.currentTimeMillis()
        }

        Log.d(
            "WATCHED_REPO",
            "Cleared local and SIMKL watch state"
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
                        cache[key]?.second == true
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
            ]?.second ?: false
        }
    }

    private fun cacheKey(
        id: String,
        type: String
    ): String {
        return "${normalizeType(type)}::${id.trim()}"
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
                now to true
        }

        _watchedStateVersion.value =
            now

        WatchStateBus.notifyChanged(
            key,
            true
        )

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
                now to false

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

                "series" ->
                    completedShowImdbIds =
                        completedShowImdbIds - normalizedId
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

    companion object {

        private const val KEY_WATCHED_OVERRIDES =
            "watched_overrides"

        private const val CACHE_TTL_MS =
            6L * 60L * 60L * 1000L

        private const val REMOTE_SET_TTL_MS =
            15L * 60L * 1000L

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
