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

    private var completedMovieImdbIds: Set<String> = emptySet()
    private var simklMovieSetFetchedAt: Long = 0L

    suspend fun preload(items: List<Pair<String, String>>) {
        val now = System.currentTimeMillis()
        val distinctItems = items
            .distinct()
            .mapNotNull { (id, type) ->
                if (id.isBlank()) null else id to normalizeType(type)
            }

        if (distinctItems.isEmpty()) return

        val keys = distinctItems.map { (id, type) -> cacheKey(id, type) }

        val missingFromMemory = distinctItems.filter { (id, type) ->
            val cached = cache[cacheKey(id, type)]
            cached == null || now - cached.first >= CACHE_TTL_MS
        }

        if (missingFromMemory.isEmpty()) {
            Log.d("WATCHED_REPO", "all watched statuses served from memory cache")
            return
        }

        val diskEntries = try {
            watchedStatusDao.getByKeys(missingFromMemory.map { (id, type) -> cacheKey(id, type) })
        } catch (e: Exception) {
            Log.e("WATCHED_REPO", "disk cache lookup failed: ${e.message}", e)
            emptyList()
        }

        diskEntries.forEach { entry ->
            if (now - entry.updatedAt < CACHE_TTL_MS) {
                cache[entry.key] = entry.updatedAt to entry.isWatched
            }
        }

        val needsLookup = missingFromMemory.filter { (id, type) ->
            val cached = cache[cacheKey(id, type)]
            cached == null || now - cached.first >= CACHE_TTL_MS
        }

        Log.e(
            "WATCHED_REPO",
            "preload called with ${items.size} items, ${needsLookup.size} need remote lookup"
        )

        if (needsLookup.isEmpty()) return

        val simklConfigured = simklRepository.isConfigured() && simklRepository.hasToken()
        val movieSetStale = !simklConfigured ||
            now - simklMovieSetFetchedAt >= CACHE_TTL_MS ||
            completedMovieImdbIds.isEmpty()

        Log.e("WATCHED_REPO", "simkl configured+authed = $simklConfigured")

        if (simklConfigured && movieSetStale) {
            completedMovieImdbIds = try {
                simklRepository.getCompletedMovieImdbIds()
            } catch (e: Exception) {
                Log.e("WATCHED_REPO", "getCompletedMovieImdbIds failed: ${e.message}", e)
                emptySet()
            }

            simklMovieSetFetchedAt = now

            Log.e(
                "WATCHED_REPO",
                "simkl movie set refreshed: completedMovies=${completedMovieImdbIds.size}"
            )
        }

        val resolvedEntities = needsLookup.map { (id, normalizedType) ->
            val watched = when (normalizedType) {
                "movie" -> isMovieLocallyWatched(id) || id in completedMovieImdbIds
                "series" -> {
                    if (!simklConfigured) {
                        false
                    } else {
                        try {
                            simklRepository.isShowWatchedByImdb(id)
                        } catch (e: Exception) {
                            Log.e(
                                "WATCHED_REPO",
                                "isShowWatchedByImdb failed for $id: ${e.message}",
                                e
                            )
                            false
                        }
                    }
                }
                else -> false
            }

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

        Log.e("WATCHED_REPO", "cache updated for ${resolvedEntities.size} items")
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
        return cache[cacheKey(id, type)]?.second ?: false
    }

    private fun cacheKey(id: String, type: String): String {
        return "${normalizeType(type)}::$id"
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
        private const val MAX_DISK_AGE_MS = 14L * 24L * 60L * 60L * 1000L
        private const val LOCAL_WATCHED_THRESHOLD = 0.9f
    }
}
