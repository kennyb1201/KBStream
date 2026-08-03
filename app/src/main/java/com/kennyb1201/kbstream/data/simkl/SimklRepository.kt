package com.kennyb1201.kbstream.data.simkl

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SimklRepository(
    context: Context
) {
    private val prefs = SimklPrefs(context)

    suspend fun isConfigured(): Boolean = withContext(Dispatchers.IO) {
        true
    }

    suspend fun hasToken(): Boolean = withContext(Dispatchers.IO) {
        true
    }

    suspend fun getWatchedBulkImport(
        movieImdbIds: List<String>,
        showImdbIds: List<String>
    ): SimklWatchedImport = withContext(Dispatchers.IO) {
        Log.e(
            "SIMKL_REPO",
            "temporary fallback repository active; watched bulk lookup not wired yet, movies=${movieImdbIds.size}, shows=${showImdbIds.size}"
        )

        SimklWatchedImport(
            watchedMovieImdbIds = emptySet(),
            watchedMovieSimklIds = emptySet(),
            watchedShowImdbIds = emptySet(),
            watchedShowSimklIds = emptySet(),
            watchedEpisodesByShowKey = emptyMap()
        )
    }
}
