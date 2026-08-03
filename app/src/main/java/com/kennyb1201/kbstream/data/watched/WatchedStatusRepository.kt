package com.kennyb1201.kbstream.data.watched

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.simkl.SimklRepository

/**
 * Central "is this watched" lookup, meant to back a checkmark indicator on
 * posters anywhere in the app -- Home rails, search, detail recommendations,
 * etc. Combines two signals per the app's "either signal" rule:
 *   - Movies: watched >=90% locally (WatchHistoryEntity) OR marked watched
 *     in Simkl's bulk sync/watched data.
 *   - Shows: fully watched per Simkl (its own "watched" flag from
 *     sync/watched, which reflects either an explicit "completed" status or
 *     every aired episode being watched).
 *
 * Local per-episode completion for shows isn't tracked (WatchHistoryEntity
 * only keeps one row per show, overwritten per episode watched), so shows
 * rely on Simkl alone.
 *
 * Usage: call preload() once per screen with all currently visible
 * (id, type) pairs, then isWatchedCached() for cheap synchronous lookups
 * while rendering. Results are cached in memory for CACHE_TTL_MS so repeat
 * screen visits don't re-hit Simkl every time.
 */
class WatchedStatusRepository(context: Context) {
    private val simklRepository = SimklRepository(context)
    private val historyDao = WatchHistoryDatabase.getInstance(context).watchHistoryDao()

    // key(type::id) -> (cachedAtMs, isWatched)
    private val cache = mutableMapOf<String, Pair<Long, Boolean>>()

    private fun watchedKey(id: String, type: String): String = "$type::$id"

    suspend fun preload(items: List<Pair<String, String>>) {
        val now = System.currentTimeMillis()

        val distinctItems = items.distinct()

        val needsLookup = distinctItems.filter { (id, type) ->
            if (id.isBlank() || type.isBlank()) return@filter false
            val key = watchedKey(id, type)
            val cached = cache[key]
            cached == null || now - cached.first >= CACHE_TTL_MS
        }

        Log.e(
            "WATCHED_REPO",
            "preload called with ${items.size} items, distinct=${distinctItems.size}, need lookup=${needsLookup.size}, " +
                "sample keys=${needsLookup.take(5).map { watchedKey(it.first, it.second) }}"
        )

        if (needsLookup.isEmpty()) return

        val localWatchedMovieIds = needsLookup
            .filter { (_, type) -> type == "movie" }
            .mapNotNull { (id, _) ->
                val entry = try {
                    historyDao.getById(id)
                } catch (_: Exception) {
                    null
                }

                val isLocallyWatched = entry != null &&
                    entry.durationMs > 0L &&
                    entry.positionMs.toFloat() / entry.durationMs.toFloat() >= LOCAL_WATCHED_THRESHOLD

                if (isLocallyWatched) id else null
            }
            .toSet()

        Log.e("WATCHED_REPO", "local watched movie ids=${localWatchedMovieIds.size}")

        val isConfigured = simklRepository.isConfigured()
        val hasToken = simklRepository.hasToken()
        val simklConfigured = isConfigured && hasToken

        Log.e("WATCHED_REPO", "simkl isConfigured=$isConfigured, hasToken=$hasToken")

        val simklWatchedIds: Set<String> =
            if (simklConfigured) {
                val movieIds = needsLookup
                    .filter { (id, type) -> type == "movie" && id.startsWith("tt") }
                    .map { (id, _) -> id }

                val showIds = needsLookup
                    .filter { (id, type) -> type == "series" && id.startsWith("tt") }
                    .map { (id, _) -> id }

                Log.e(
                    "WATCHED_REPO",
                    "eligible for simkl lookup: movies=${movieIds.size}, shows=${showIds.size}, " +
                        "nonImdbSkipped=${needsLookup.size - movieIds.size - showIds.size}"
                )

                if (movieIds.isNotEmpty() || showIds.isNotEmpty()) {
                    try {
                        val result = simklRepository.getWatchedBulkImport(
                            movieImdbIds = movieIds,
                            showImdbIds = showIds
                        )

                        Log.e(
                            "WATCHED_REPO",
                            "simkl bulk result: watchedMovies=${result.watchedMovieImdbIds.size}, " +
                                "watchedShows=${result.watchedShowImdbIds.size}"
                        )

                        result.watchedMovieImdbIds + result.watchedShowImdbIds
                    } catch (e: Exception) {
                        Log.e("WATCHED_REPO", "simkl bulk lookup failed: ${e.message}", e)
                        emptySet()
                    }
                } else {
                    emptySet()
                }
            } else {
                emptySet()
            }

        needsLookup.forEach { (id, type) ->
            val watched = when (type) {
                "movie" -> id in localWatchedMovieIds || id in simklWatchedIds
                "series" -> id in simklWatchedIds
                else -> id in simklWatchedIds
            }

            val key = watchedKey(id, type)
            cache[key] = now to watched
        }

        Log.e(
            "WATCHED_REPO",
            "cache updated for ${needsLookup.size} items, watched=${needsLookup.count { (id, type) -> cache[watchedKey(id, type)]?.second == true }}"
        )
    }

    fun isWatchedCached(id: String, type: String): Boolean {
        return cache[watchedKey(id, type)]?.second ?: false
    }

    companion object {
        private const val CACHE_TTL_MS = 6L * 60L * 60L * 1000L
        private const val LOCAL_WATCHED_THRESHOLD = 0.9f
    }
}
