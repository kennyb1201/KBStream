package com.kennyb1201.kbstream.data.widget

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.history.WatchHistoryRepository
import com.kennyb1201.kbstream.data.library.HiddenTitles
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import com.kennyb1201.kbstream.ui.home.UpNextItem
import com.kennyb1201.kbstream.ui.home.continueWatchingShowNames
import com.kennyb1201.kbstream.ui.home.instantContinueWatchingItem
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
 * the widget cannot invent an order, a card or a rule of its own.
 *
 * Since spec 2026-11-16 it also has the second half: [rebuild] re-derives that
 * list from the phone's own watch history when the app is not there to publish
 * it - the header's button, and the half-hourly round. It reads the LOCAL history
 * database and nothing else (no network, no tracker), and it builds its cards with
 * Home's own builder ([instantContinueWatchingItem]), so "a second Continue
 * Watching" is still something this file cannot produce.
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
        showNames: Map<String, String>,
        /**
         * Write even when the rows are the ones already in the snapshot.
         *
         * True only for [rebuild]: the freshness line is read from the write's
         * own timestamp, so a round or a tap that found the same rows must still
         * move it - otherwise the widget would sit there saying "Updated 5h ago"
         * after the viewer watched it rebuild (spec 2026-11-16, F2/F3). A
         * publish from Home keeps the gate: it fires on every tracker arrival,
         * and most of those change nothing.
         */
        force: Boolean = false
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
        if (!force && lastRows[gate] == rows) return
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
     * Re-derives the snapshot from this phone's own watch history, writes it and
     * repaints (spec 2026-11-16, F1).
     *
     * Until now the app's Home view model was the only thing that could move the
     * widget, so a phone that was not showing Home went on drawing whatever the
     * snapshot said - twelve hours of it, in the report that started this. The
     * header's button and the half-hourly round both ask for this, and it answers
     * from the same local source and the same card builder Home's instant seed
     * uses ([instantContinueWatchingItem]), so the widget shows the rail's own
     * reading of the history rather than a second one.
     *
     * LOCAL ONLY: the history database, no network, no tracker, no add-on call.
     * The tracker cards a running Home merges in are not reproducible from disk,
     * and inventing them here would be a second Continue Watching.
     *
     * The write is forced past the change gate, and that is the point: the
     * freshness line is the snapshot's own `writtenAtMs`, so a round or a tap that
     * found the same rows still has to move it, or the button would go on doing
     * visibly nothing.
     *
     * Fail-soft, never blank: a read that throws, and a history with nothing in it
     * (a cold start, a profile with no progress) both leave the last good snapshot
     * exactly where it is. A failed rebuild is not worth a retry storm; the next
     * round and the next tap cover it.
     */
    suspend fun rebuild(context: Context) {
        val application = context.applicationContext
        // No widget placed, nothing runs: the round's own rule, kept for the tap
        // (spec 2026-11-16).
        if (!CwWidgetUpdater.hasInstances(application)) return

        val rows = withContext(Dispatchers.IO) {
            runCatching {
                WatchHistoryRepository(application).getContinueWatchingParentsSnapshot()
            }
                .onFailure {
                    Log.w(TAG, "history read for a rebuild failed: ${it.javaClass.simpleName}")
                }
                .getOrNull()
        } ?: return

        // An empty history is not an empty Continue Watching the viewer asked to
        // see: it is a cold profile or a read that answered nothing, and blanking a
        // widget that is showing the last good list would be worse than waiting for
        // the next round.
        if (rows.isEmpty()) return

        publish(
            application,
            rows.mapNotNull(::instantContinueWatchingItem),
            continueWatchingShowNames(rows),
            force = true
        )
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
