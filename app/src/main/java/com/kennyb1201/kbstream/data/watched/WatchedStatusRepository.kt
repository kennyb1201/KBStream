package com.kennyb1201.kbstream.data.watched

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.simkl.SimklRepository

class WatchedStatusRepository(context: Context) {
    private val simklRepository = SimklRepository(context)
    private val historyDao = WatchHistoryDatabase.getInstance(context).watchHistoryDao()

    // typedKey -> (cachedAtMs, isWatched)
    private val cache = mutableMapOf<String, Pair<Long, Boolean>>()

    private var completedMovieImdbIds: Set<String> = emptySet()
    private var simklMovieSetFetchedAt: Long = 0L

    suspend fun preload(items: List<Pair<String, String>>) {
        val now = System.currentTimeMillis()

        val simklConfigured = simklRepository.isConfigured() && simklRepository.hasToken()
        val movieSetStale = !simklConfigured ||
            now - simklMovieSetFetchedAt >= CACHE_TTL_MS ||
            completedMovieImdbIds.isEmpty()

        val needsLookup = items
            .distinct()
            .filter { (id, type) ->
                if (id.isBlank()) return@filter false
                val key = cacheKey(id, type)
                val cached = cache[key]
                cached == null || now - cached.first >= CACHE_TTL_MS
            }

        Log.e(
            "WATCHED_REPO",
            "preload called with ${items.size} items, ${needsLookup.size} need lookup"
        )

        if (needsLookup.isEmpty()) return

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

        needsLookup.forEach { (id, type) ->
            val normalizedType = normalizeType(type)

            val watched = when (normalizedType) {
                "movie" -> isMovieLocallyWatched(id) || id in completedMovieImdbIds
                "series" -> {
                    if (!simklConfigured) {
                        false
                    } else {
                        try {
                            simklRepository.isShowWatchedByImdb(id)
                        } catch (e: Exception) {
                            Log.e("WATCHED_REPO", "isShowWatchedByImdb failed for $id: ${e.message}", e)
                            false
                        }
                    }
                }
                else -> false
            }

            cache[cacheKey(id, normalizedType)] = now to watched
        }

        Log.e("WATCHED_REPO", "cache updated for ${needsLookup.size} items")
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
        private const val LOCAL_WATCHED_THRESHOLD = 0.9f
    }
}
