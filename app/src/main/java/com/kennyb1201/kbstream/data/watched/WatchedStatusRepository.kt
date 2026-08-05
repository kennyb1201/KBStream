package com.kennyb1201.kbstream.data.watched

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.simkl.SimklRepository

/**
 * Central "is this watched" lookup, meant to back a checkmark indicator on
 * posters anywhere in the app -- Home rails, search, detail recommendations,
 * etc. Combines two signals per the app's "either signal" rule:
 *   - Movies: watched >=90% locally (WatchHistoryEntity) OR present in
 *     Simkl's completed movies list.
 *   - Shows: watched per Simkl lookup for that individual IMDb id.
 *
 * Local per-episode completion for shows isn't tracked (WatchHistoryEntity
 * only keeps one row per show, overwritten per episode watched), so shows
 * rely on Simkl.
 */
class WatchedStatusRepository(context: Context) {
    private val simklRepository = SimklRepository(context)
    private val historyDao = WatchHistoryDatabase.getInstance(context).watchHistoryDao()

    // id -> (cachedAtMs, isWatched)
    private val cache = mutableMapOf<String, Pair<Long, Boolean>>()

    // Simkl-wide movie set, still worth caching because the endpoint is fine
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
            .filter { (id, _) ->
                if (id.isBlank()) return@filter false

                val cached = cache[id]
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
            val watched = when (type.lowercase()) {
                "movie" -> isMovieLocallyWatched(id) || id in completedMovieImdbIds
                "series", "show" -> {
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

            cache[id] = now to watched
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

    fun isWatchedCached(id: String): Boolean {
        return cache[id]?.second ?: false
    }

    companion object {
        private const val CACHE_TTL_MS = 6L * 60L * 60L * 1000L // 6 hours
        private const val LOCAL_WATCHED_THRESHOLD = 0.9f
    }
}
