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
 *     Simkl's sync/all-items/movies/completed list.
 *   - Shows: fully watched per Simkl's sync/all-items/shows/watching data
 *     (either explicit "completed" status or every aired episode watched --
 *     see SimklRepository.isShowFullyWatched).
 *
 * Local per-episode completion for shows isn't tracked (WatchHistoryEntity
 * only keeps one row per show, overwritten per episode watched), so shows
 * rely on Simkl alone.
 *
 * Unlike a per-id bulk lookup, this fetches the user's full completed-movies
 * and watching-shows lists ONCE per cache window, then does local Set
 * membership checks per poster -- two network calls total instead of one
 * per screen's worth of items.
 */
class WatchedStatusRepository(context: Context) {
    private val simklRepository = SimklRepository(context)
    private val historyDao = WatchHistoryDatabase.getInstance(context).watchHistoryDao()

    // id -> (cachedAtMs, isWatched), for the per-item local-movie check
    private val cache = mutableMapOf<String, Pair<Long, Boolean>>()

    // The two Simkl-wide sets, refreshed together on the same TTL
    private var completedMovieImdbIds: Set<String> = emptySet()
    private var completedShowImdbIds: Set<String> = emptySet()
    private var simklSetsFetchedAt: Long = 0L

    suspend fun preload(items: List<Pair<String, String>>) {
    val now = System.currentTimeMillis()

    val simklConfigured = simklRepository.isConfigured() && simklRepository.hasToken()
    val simklSetsStale = !simklConfigured || now - simklSetsFetchedAt >= CACHE_TTL_MS ||
        (completedMovieImdbIds.isEmpty() && completedShowImdbIds.isEmpty())

    val needsLookup = items
        .distinctBy { it.first }
        .filter { (id, _) ->
            if (id.isBlank()) return@filter false

            val cached = cache[id]
            val itemCacheStale = cached == null || now - cached.first >= CACHE_TTL_MS

            itemCacheStale || (simklConfigured && simklSetsStale)
        }

    Log.e(
        "WATCHED_REPO",
        "preload called with ${items.size} items, ${needsLookup.size} need lookup"
    )

    if (needsLookup.isEmpty()) return

    Log.e("WATCHED_REPO", "simkl configured+authed = $simklConfigured")

    if (simklConfigured && simklSetsStale) {
        completedMovieImdbIds = try {
            simklRepository.getCompletedMovieImdbIds()
        } catch (e: Exception) {
            Log.e("WATCHED_REPO", "getCompletedMovieImdbIds failed: ${e.message}", e)
            emptySet()
        }

        completedShowImdbIds = try {
            simklRepository.getCompletedShowImdbIds()
        } catch (e: Exception) {
            Log.e("WATCHED_REPO", "getCompletedShowImdbIds failed: ${e.message}", e)
            emptySet()
        }

        simklSetsFetchedAt = now

        Log.e(
            "WATCHED_REPO",
            "simkl sets refreshed: completedMovies=${completedMovieImdbIds.size}, " +
                "completedShows=${completedShowImdbIds.size}"
        )
    }

    needsLookup.forEach { (id, type) ->
        val watched = when (type) {
            "movie" -> isMovieLocallyWatched(id) || id in completedMovieImdbIds
            "series" -> id in completedShowImdbIds
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
