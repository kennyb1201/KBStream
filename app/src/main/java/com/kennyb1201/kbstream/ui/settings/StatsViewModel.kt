package com.kennyb1201.kbstream.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.history.TopShow
import com.kennyb1201.kbstream.data.history.ViewingStats
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Reads the viewing-stats numbers off the active profile's history database.
 *
 * Read-only and profile-scoped by construction: it resolves the scoped DAO on
 * every load (the same rule HomeViewModel follows, so a profile switch is
 * picked up instead of reading a closed database), runs the aggregate queries,
 * and computes the streak from the completion timestamps in Kotlin.
 */
class StatsViewModel(application: Application) : AndroidViewModel(application) {

    data class UiState(
        val loading: Boolean = true,
        val finishedTitles: Int = 0,
        val finishedEpisodes: Int = 0,
        val finishedMovies: Int = 0,
        /** Σ durationMs over completed rows. */
        val finishedRuntimeMs: Long = 0L,
        val streakDays: Int = 0,
        val topShows: List<TopShow> = emptyList()
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            val state = withContext(Dispatchers.IO) {
                val dao = WatchHistoryDatabase
                    .getInstanceScoped(getApplication())
                    .watchHistoryDao()
                UiState(
                    loading = false,
                    finishedTitles = dao.finishedTitleCount(),
                    finishedEpisodes = dao.completedEpisodeCount(),
                    finishedMovies = dao.completedMovieCount(),
                    finishedRuntimeMs = dao.finishedRuntimeMs(),
                    streakDays = ViewingStats.streakDays(
                        dao.completionTimes(),
                        System.currentTimeMillis()
                    ),
                    topShows = dao.topShows()
                )
            }
            _state.value = state
        }
    }
}
