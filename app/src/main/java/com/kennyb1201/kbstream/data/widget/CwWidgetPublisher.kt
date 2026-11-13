package com.kennyb1201.kbstream.data.widget

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.library.HiddenTitles
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import com.kennyb1201.kbstream.ui.home.UpNextItem
import com.kennyb1201.kbstream.ui.home.upNextContinueWatchingDecision
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turns Home's Continue Watching list into the widget's snapshot (spec
 * 2026-11-13, F3).
 *
 * This is the ONLY writer. It is handed the exact list Home's rail publishes -
 * already ordered, already dismissed-filtered, already tracker-merged - and it
 * runs the same two read-site rules the rail runs ([upNextContinueWatchingDecision]:
 * hidden titles and the not-yet-aired tracker card) with the same inputs, so
 * the widget cannot invent an order, a card or a rule of its own. Nothing here
 * reads the history database or calls a tracker.
 *
 * Writes are change-gated in memory: a publish that produces the same rows as the
 * last one is skipped, so the tracker arrivals that re-publish the rail several
 * times a session cost one small file write, not several.
 */
internal object CwWidgetPublisher {

    private const val TAG = "CwWidgetPublisher"

    /** The last rows written, by profile - the change gate. */
    private val lastRows = ConcurrentHashMap<String, List<CwWidgetRow>>()

    /**
     * Writes the snapshot for [items] and tells the launcher.
     *
     * [items] is Home's published Continue Watching list; [showNames] is the
     * ViewModel's own map of every CW parent's show name, which the hidden-titles
     * rule needs for a card that arrived without one.
     */
    suspend fun publish(
        context: Context,
        items: List<UpNextItem>,
        showNames: Map<String, String>
    ) {
        val application = context.applicationContext
        val profileId = runCatching { ProfileStorage.activeProfileId(application) }.getOrNull()

        // Built off the main thread: the hidden-titles read and the file write are
        // both disk work, and a publish can land while the rail is scrolling.
        val rows = withContext(Dispatchers.IO) {
            runCatching { rows(items, showNames, application) }
                .onFailure { Log.w(TAG, "could not build rows: ${it.javaClass.simpleName}") }
                .getOrDefault(emptyList())
        }

        val gate = profileId ?: ""
        if (lastRows[gate] == rows) return
        lastRows[gate] = rows

        withContext(Dispatchers.IO) {
            CwWidgetStore.write(
                application,
                CwWidgetSnapshot(
                    profileId = profileId,
                    writtenAtMs = System.currentTimeMillis(),
                    rows = rows
                )
            )
        }
        CwWidgetUpdater.refresh(application)
    }

    /**
     * The rows the widget shows for [items]: the same verdicts the rail applies,
     * the same order, capped at [CwWidgetRules.MAX_ROWS].
     *
     * A card with no parent id cannot be resumed and is not drawn - it would be a
     * row that does nothing when tapped.
     */
    private fun rows(
        items: List<UpNextItem>,
        showNames: Map<String, String>,
        context: Context
    ): List<CwWidgetRow> {
        if (items.isEmpty()) return emptyList()

        val hidden = runCatching { HiddenTitles.keys(context) }.getOrDefault(emptySet())
        val today = LocalDate.now(ZoneId.systemDefault())

        return items.asSequence()
            .filter { item ->
                val decision = upNextContinueWatchingDecision(item, hidden, showNames, today)
                !decision.hidden && !decision.dropped
            }
            .mapNotNull(::rowOf)
            .take(CwWidgetRules.MAX_ROWS)
            .toList()
    }

    private fun rowOf(item: UpNextItem): CwWidgetRow? {
        val parentId = item.parentId?.trim()?.takeIf { it.isNotBlank() }
            ?: item.historyRowId?.trim()?.takeIf { it.isNotBlank() }
            ?: return null

        val title = item.showTitle?.trim()?.takeIf { it.isNotBlank() }
            ?: item.title.trim().takeIf { it.isNotBlank() }
            ?: return null

        return CwWidgetRow(
            parentType = item.parentType?.trim()?.takeIf { it.isNotBlank() } ?: "series",
            parentId = parentId,
            streamId = item.episodeStreamId?.trim()?.takeIf { it.isNotBlank() },
            showTitle = title,
            episodeTitle = item.episodeTitle?.trim()?.takeIf { it.isNotBlank() },
            season = item.season,
            episode = item.episode,
            posterUrl = (item.poster ?: item.episodeThumbnail)
                ?.trim()
                ?.takeIf { it.isNotBlank() },
            positionMs = item.startPositionMs.coerceAtLeast(0L),
            progressPercent = item.progressPercent,
            remainingMinutes = item.remainingMinutes,
            durationMs = (item.runtimeMinutes?.takeIf { it > 0 }?.toLong() ?: 0L) * 60_000L,
            updatedAtMs = item.recencyTimestamp
        )
    }
}
