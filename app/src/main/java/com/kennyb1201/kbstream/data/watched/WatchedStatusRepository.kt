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

    // id -> (cachedAtMs, isWatched)
    private val cache = mutableMapOf<String, Pair<Long, Boolean>>()

    suspend fun preload(items: List<Pair<String, String>>) {
        val now = System.currentTimeMillis()

        val needsLookup = items
            .distinctBy { it.first }
            .filter { (id, _) ->
                id.isNotBlank() && run {
                    val cached = cache[id]
                    cached == null || now - cached.first >= CACHE_TTL_MS
                }
            }

        if (needsLookup.isEmpty()) return

        // Local signal: movies only, watched past ~90% counts as completed
        // even if it never made it into Simkl (e.g. offline playback).
        val localWatchedMovieIds = needsLookup
            .filter { it.second == "movie" }
            .mapNotNull { (id, _) ->
                val entry = try {
                    historyDao.getById(id)
                } catch (e: Exception) {
                    null
                }

                val isLocallyWatched = entry != null &&
                    entry.durationMs > 0L &&
                    entry.positionMs.toFloat() / entry.durationMs.toFloat() >= LOCAL_WATCHED_THRESHOLD

                if (isLocallyWatched) id else null
            }
            .toSet()

        // Simkl signal: movies + shows, only for ids that look like IMDb ids
        // (Stremio's convention, but not guaranteed for every third-party
        // addon -- ids that don't match are simply skipped rather than
        // risking a bad lookup).
        val simklWatchedIds: Set<String> =
            if (simklRepository.isConfigured() && simklRepository.hasToken()) {
                val movieIds = needsLookup
                    .filter { it.second == "movie" && it.first.startsWith("tt") }
                    .map { it.first }
                val showIds = needsLookup
                    .filter { it.second == "series" && it.first.startsWith("tt") }
                    .map { it.first }

                if (movieIds.isNotEmpty() || showIds.isNotEmpty()) {
                    try {
                        val result = simklRepository.getWatchedBulkImport(
                            movieImdbIds = movieIds,
                            showImdbIds = showIds
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

        needsLookup.forEach { (id, _) ->
            val watched = id in localWatchedMovieIds || id in simklWatchedIds
            cache[id] = now to watched
        }
    }

    fun isWatchedCached(id: String): Boolean {
        return cache[id]?.second ?: false
    }

    companion object {
        private const val CACHE_TTL_MS = 6L * 60L * 60L * 1000L // 6 hours
        private const val LOCAL_WATCHED_THRESHOLD = 0.9f
    }
}
