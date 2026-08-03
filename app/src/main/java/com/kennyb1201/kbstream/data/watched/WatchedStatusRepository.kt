package com.kennyb1201.kbstream.data.watched

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.simkl.SimklRepository

class WatchedStatusRepository(context: Context) {
    private val simklRepository = SimklRepository(context)
    private val historyDao = WatchHistoryDatabase.getInstance(context).watchHistoryDao()

    private val cache = mutableMapOf<String, Pair<Long, Boolean>>()

    private fun watchedKey(id: String, type: String): String = "$type::$id"

    suspend fun preload(items: List<Pair<String, String>>) {
        val now = System.currentTimeMillis()
        val needsLookup = items.distinct()

        cache.clear()

        Log.e(
            "WATCHED_REPO",
            "preload called with ${items.size} items, distinct=${needsLookup.size}, forcing fresh lookup, sample=${needsLookup.take(5)}"
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

        Log.e("WATCHED_REPO", "local watched movie ids=${
            localWatchedMovieIds.size
        }")

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
                    "eligible for simkl lookup: movies=${movieIds.size}, shows=${showIds.size}, nonImdbSkipped=${needsLookup.size - movieIds.size - showIds.size}"
                )

                if (movieIds.isNotEmpty() || showIds.isNotEmpty()) {
                    try {
                        val result = simklRepository.getWatchedBulkImport(
                            movieImdbIds = movieIds,
                            showImdbIds = showIds
                        )

                        Log.e(
                            "WATCHED_REPO",
                            "simkl bulk result: watchedMovies=${result.watchedMovieImdbIds.size}, watchedShows=${result.watchedShowImdbIds.size}"
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

            cache[watchedKey(id, type)] = now to watched
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
        private const val LOCAL_WATCHED_THRESHOLD = 0.9f
    }
}
