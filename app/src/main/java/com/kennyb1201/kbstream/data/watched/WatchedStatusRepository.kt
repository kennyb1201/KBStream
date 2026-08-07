package com.kennyb1201.kbstream.data.watched

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.cache.WatchedStatusEntity
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.simkl.SimklRepository

class WatchedStatusRepository(context: Context) {
    private val simklRepository = SimklRepository(context)
    private val database = WatchHistoryDatabase.getInstance(context)
    private val historyDao = database.watchHistoryDao()
    private val watchedStatusDao = database.watchedStatusDao()

    private val cache = mutableMapOf<String, Pair<Long, Boolean>>()

    private var completedMovieKeys: Set<String> = emptySet()
    private var simklMovieSetFetchedAt: Long = 0L

    suspend fun preload(items: List<Pair<String, String>>) {
        preload(items = items, forceRemoteRefresh = false)
    }

    suspend fun preload(
        items: List<Pair<String, String>>,
        forceRemoteRefresh: Boolean
    ) {
        val now = System.currentTimeMillis()

        val distinctItems = items
            .distinct()
            .mapNotNull { (id, type) ->
                val normalizedId = id.trim()
                if (normalizedId.isBlank()) {
                    null
                } else {
                    normalizedId to normalizeType(type)
                }
            }

        if (distinctItems.isEmpty()) return

        val missingFromMemory = distinctItems.filter { (id, type) ->
            val cached = cache[cacheKey(id, type)]
            cached == null || now - cached.first >= CACHE_TTL_MS
        }

        if (missingFromMemory.isEmpty() && !forceRemoteRefresh) {
            Log.d("WATCHED_REPO", "all watched statuses served from memory cache")
            return
        }

        val diskEntries = try {
            watchedStatusDao.getByKeys(
                missingFromMemory.map { (id, type) -> cacheKey(id, type) }
            )
        } catch (e: Exception) {
            Log.e("WATCHED_REPO", "disk cache lookup failed: ${e.message}", e)
            emptyList()
        }

        diskEntries.forEach { entry ->
            if (now - entry.updatedAt < CACHE_TTL_MS) {
                cache[entry.key] = entry.updatedAt to entry.isWatched
            }
        }

        val needsLookup = distinctItems.filter { (id, type) ->
            val cached = cache[cacheKey(id, type)]
            forceRemoteRefresh || cached == null || now - cached.first >= CACHE_TTL_MS
        }

        Log.e(
            "WATCHED_REPO",
            "preload called with ${items.size} items, ${needsLookup.size} need evaluation, forceRemoteRefresh=$forceRemoteRefresh"
        )

        if (needsLookup.isEmpty()) return

        val simklConfigured = simklRepository.isConfigured() && simklRepository.hasToken()
        Log.e("WATCHED_REPO", "simkl configured+authed = $simklConfigured")

        var shouldRefreshRemote = forceRemoteRefresh
        var activityChanged = false

        if (simklConfigured && !forceRemoteRefresh) {
            activityChanged = try {
                simklRepository.hasWatchedActivityChanged()
            } catch (e: Exception) {
                Log.e("WATCHED_REPO", "hasWatchedActivityChanged failed: ${e.message}", e)
                false
            }

            shouldRefreshRemote = activityChanged
        }

        val hasColdMovieSet = completedMovieKeys.isEmpty()
        val movieSetStale = now - simklMovieSetFetchedAt >= REMOTE_MOVIE_SET_TTL_MS

        val shouldRefreshMovieSet =
            simklConfigured && (forceRemoteRefresh || activityChanged || hasColdMovieSet || movieSetStale)

        if (shouldRefreshMovieSet) {
            completedMovieKeys = try {
                simklRepository.getCompletedMovieKeys()
            } catch (e: Exception) {
                Log.e("WATCHED_REPO", "getCompletedMovieKeys failed: ${e.message}", e)
                emptySet()
            }

            simklMovieSetFetchedAt = now

            Log.e(
                "WATCHED_REPO",
                "simkl movie set refreshed: completedMovieKeys=${completedMovieKeys.size}"
            )
        }

        val resolvedEntities = needsLookup.map { (id, normalizedType) ->
            val watched = when (normalizedType) {
                "movie" -> {
                    val localWatched = isMovieLocallyWatched(id)
                    val simklWatched = simklConfigured && "imdb:$id" in completedMovieKeys
                    localWatched || simklWatched
                }

                "series" -> {
                    if (!simklConfigured) {
                        false
                    } else {
                        val cachedValue = cache[cacheKey(id, normalizedType)]?.second
                        val canReuseCachedSeries =
                            !forceRemoteRefresh &&
                                !activityChanged &&
                                cachedValue != null

                        if (canReuseCachedSeries) {
                            cachedValue
                        } else {
                            try {
                                simklRepository.isShowWatchedByImdb(id)
                            } catch (e: Exception) {
                                Log.e(
                                    "WATCHED_REPO",
                                    "isShowWatchedByImdb failed for $id: ${e.message}",
                                    e
                                )
                                cachedValue ?: false
                            }
                        }
                    }
                }

                else -> false
            }

            Log.e(
                "WATCHED_REPO",
                "resolved id=$id type=$normalizedType watched=$watched"
            )

            val key = cacheKey(id, normalizedType)
            cache[key] = now to watched

            WatchedStatusEntity(
                key = key,
                imdbId = id,
                mediaType = normalizedType,
                isWatched = watched,
                updatedAt = now
            )
        }

        try {
            watchedStatusDao.upsertAll(resolvedEntities)
            watchedStatusDao.deleteOlderThan(now - MAX_DISK_AGE_MS)
        } catch (e: Exception) {
            Log.e("WATCHED_REPO", "disk cache write failed: ${e.message}", e)
        }

        if (simklConfigured && (forceRemoteRefresh || activityChanged)) {
            try {
                simklRepository.markWatchedActivitySynced()
            } catch (e: Exception) {
                Log.e("WATCHED_REPO", "markWatchedActivitySynced failed: ${e.message}", e)
            }
        }

        Log.e("WATCHED_REPO", "cache updated for ${resolvedEntities.size} items")
    }

    suspend fun preloadAndGetWatchedKeys(items: List<Pair<String, String>>): Set<String> {
        preload(items)

        return items
            .distinct()
            .mapNotNull { (id, type) ->
                val normalizedId = id.trim()
                if (normalizedId.isBlank()) return@mapNotNull null

                val normalizedType = normalizeType(type)
                val key = cacheKey(normalizedId, normalizedType)

                if (cache[key]?.second == true) key else null
            }
            .toSet()
    }

    suspend fun forceRefresh(items: List<Pair<String, String>>): Set<String> {
        preload(items = items, forceRemoteRefresh = true)
        return items
            .distinct()
            .mapNotNull { (id, type) ->
                val normalizedId = id.trim()
                if (normalizedId.isBlank()) return@mapNotNull null

                val normalizedType = normalizeType(type)
                val key = cacheKey(normalizedId, normalizedType)

                if (cache[key]?.second == true) key else null
            }
            .toSet()
    }

    fun clearRemoteSyncCheckpoint() {
        simklRepository.forceClearWatchedActivitySync()
    }

    private suspend fun isMovieLocallyWatched(id: String): Boolean {
        val entry = try {
            historyDao.getById(id)
        } catch (e: Exception) {
            null
        }

        return entry != null &&
            entry.durationMs > 0L &&
            entry.positionMs.toFloat() / entry.durationMs.toFloat() >= LOCAL_WATCHED_THRESHOLD
    }

    fun isWatchedCached(id: String, type: String): Boolean {
        val normalizedId = id.trim()
        if (normalizedId.isBlank()) return false
        return cache[cacheKey(normalizedId, type)]?.second ?: false
    }

    private fun cacheKey(id: String, type: String): String {
        return "${normalizeType(type)}::${id.trim()}"
    }

    private fun normalizeType(type: String): String {
        return when (type.lowercase()) {
            "movie" -> "movie"
            "series", "show", "tv" -> "series"
            else -> type.lowercase()
        }
    }

    companion object {
        private const val CACHE_TTL_MS = 6L * 60L * 60L * 1000L
        private const val REMOTE_MOVIE_SET_TTL_MS = 15L * 60L * 1000L
        private const val MAX_DISK_AGE_MS = 14L * 24L * 60L * 60L * 1000L
        private const val LOCAL_WATCHED_THRESHOLD = 0.9f
    }
}