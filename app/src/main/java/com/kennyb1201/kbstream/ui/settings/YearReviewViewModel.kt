package com.kennyb1201.kbstream.ui.settings

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kennyb1201.kbstream.data.history.EpisodeStamp
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.history.YearReview
import com.kennyb1201.kbstream.data.history.YearInReview
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One profile's year in review, read on demand from its own history (spec
 * 2026-11-13, F1).
 *
 * ## Nothing runs until the screen opens
 *
 * There is no init load and no background job: [load] is called by the screen on
 * entry and when the viewer picks another year. A library of ten thousand rows
 * is never touched by this class unless someone is looking at the review.
 *
 * ## Cached per (profile, year) until history changes
 *
 * Every completed entry carries the `MAX(updatedAt)` it was computed against, so
 * a reopen whose token still matches repaints the numbers instantly and a write
 * in between (an episode finished, a resume point moved) invalidates exactly the
 * years it touched - the token is global, so one write re-reads once, which is
 * the safe side of the trade. The profile id is part of the key: this view model
 * is activity-scoped, and a profile switch must not show the previous profile's
 * year.
 *
 * ## Strictly per profile
 *
 * The DAO is resolved from the SCOPED database on every load (the same rule
 * StatsViewModel follows), so a kids profile reads only its own rows.
 */
class YearReviewViewModel(application: Application) : AndroidViewModel(application) {

    data class UiState(
        val loading: Boolean = true,
        /** Set when the history read threw; the screen offers a retry. */
        val error: String? = null,
        val year: Int = LocalDate.now().year,
        /** Every year this profile has something in, newest first, plus this year. */
        val years: List<Int> = listOf(LocalDate.now().year),
        val review: YearReview? = null
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private data class CacheEntry(val token: Long, val review: YearReview)

    /** (profileId, year) -> the numbers and the history token they were read at. */
    private val cache = HashMap<String, CacheEntry>()

    /** Reads [year] for the profile that is active NOW. */
    fun load(year: Int? = null) {
        val wanted = year ?: _state.value.year
        _state.value = _state.value.copy(
            loading = true,
            error = null,
            year = wanted,
            review = null
        )

        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val dao = WatchHistoryDatabase
                        .getInstanceScoped(getApplication())
                        .watchHistoryDao()
                    val profileId = ProfileStorage.activeProfileId(getApplication()) ?: "default"
                    val zone = ZoneId.systemDefault()
                    val currentYear = LocalDate.now().year
                    val years = YearInReview.yearsWithHistory(
                        dao.completionTimes(),
                        currentYear,
                        zone
                    )
                    val token = dao.latestUpdateAt()
                    val key = "$profileId|$wanted"
                    val cached = cache[key]
                    val review = if (cached != null && cached.token == token) {
                        cached.review
                    } else {
                        val from = YearInReview.yearStartMs(wanted, zone)
                        val to = YearInReview.yearStartMs(wanted + 1, zone)
                        val rows = dao.rowsForYear(from, to)
                        val stamps: List<EpisodeStamp> = dao.completedEpisodeStamps()
                        YearInReview.compute(
                            year = wanted,
                            rows = rows,
                            episodeStamps = stamps,
                            zone = zone
                        ).also { cache[key] = CacheEntry(token, it) }
                    }
                    UiState(
                        loading = false,
                        year = wanted,
                        years = years,
                        review = review
                    )
                }
            }
            _state.value = result.getOrElse { failure ->
                Log.w(TAG, "year review read failed for $wanted", failure)
                UiState(
                    loading = false,
                    error = READ_FAILED,
                    year = wanted,
                    years = _state.value.years
                )
            }
        }
    }

    /**
     * Forgets one year's cached numbers.
     *
     * Not needed for correctness (the token does that), but the share card and a
     * retry both want a guaranteed fresh read, and a test can pin that a write
     * changes the numbers without a new process.
     */
    internal fun invalidate(year: Int) {
        val profileId = ProfileStorage.activeProfileId(getApplication()) ?: "default"
        cache.remove("$profileId|$year")
    }

    companion object {
        private const val TAG = "YearReviewViewModel"

        /** What the screen shows in place of the numbers when a read threw. */
        const val READ_FAILED = "Could not read this profile's watch history."
    }
}
