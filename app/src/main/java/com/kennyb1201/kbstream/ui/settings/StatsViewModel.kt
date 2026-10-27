package com.kennyb1201.kbstream.ui.settings

import android.app.Application
import android.util.Log
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
 *
 * It does NOT load itself. The screen owns the trigger - on entry and again
 * whenever the active profile changes - because this view model is
 * activity-scoped: a load started in `init` runs once per PROCESS, so a screen
 * reopened after another episode was finished, or after switching profiles,
 * kept showing the numbers that one load produced, the previous profile's
 * included (stats C1). See [load].
 */
class StatsViewModel(application: Application) : AndroidViewModel(application) {

    data class UiState(
        val loading: Boolean = true,
        /**
         * Set when the history read threw. The screen names the failure and
         * offers a retry rather than sitting on "Reading history…" forever
         * (stats C6).
         */
        val error: String? = null,
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

    /**
     * Re-reads every figure for the profile that is active NOW.
     *
     * Called by the screen on entry and whenever the active profile changes.
     * The state is reset to [UiState.loading] before the read starts, so a
     * reload never leaves the previous profile's numbers on screen under the
     * "this profile's history" subtitle. A read that throws is carried as
     * [UiState.error] (logged, and with the retry the screen draws) instead of
     * killing the coroutine and stranding the screen in its loading state.
     */
    fun load() {
        _state.value = UiState()
        viewModelScope.launch {
            val next = withContext(Dispatchers.IO) {
                runCatching {
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
            }
            _state.value = next.getOrElse { failure ->
                Log.w(TAG, "viewing-stats read failed", failure)
                UiState(loading = false, error = READ_FAILED)
            }
        }
    }

    companion object {
        private const val TAG = "StatsViewModel"

        /** What the screen shows in place of the numbers when a read threw. */
        const val READ_FAILED = "Could not read this profile's watch history."
    }
}
