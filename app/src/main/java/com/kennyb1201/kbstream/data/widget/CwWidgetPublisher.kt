package com.kennyb1201.kbstream.data.widget

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.history.WatchHistoryRepository
import com.kennyb1201.kbstream.data.library.HiddenTitles
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import com.kennyb1201.kbstream.ui.home.UpNextItem
import com.kennyb1201.kbstream.ui.home.continueWatchingShowNames
import com.kennyb1201.kbstream.ui.home.instantContinueWatchingItem
import com.kennyb1201.kbstream.ui.home.upNextChipLabel
import com.kennyb1201.kbstream.ui.home.upNextContinueWatchingDecision
import com.kennyb1201.kbstream.ui.home.upNextIsResumeCard
import com.kennyb1201.kbstream.ui.home.upNextOwnEpisodeAirEpochDay
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
 * The rebuild MERGES rather than overwrites (spec 2026-11-16, F2): local history
 * can only re-derive the resume half, so the cards it cannot reproduce - the
 * next-up and tracker rows - are carried forward from the snapshot Home last
 * published, re-checked against the rail's two read-site rules. A rebuild
 * therefore stops DELETING the full rail; it still invents nothing.
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
        force: Boolean = false,
        /**
         * Keep the snapshot's non-resume rows beside the ones [items] produces.
         *
         * True only for [rebuild]: Home's publish already carries the whole rail,
         * while a rebuild can only re-derive the resume cards from local history.
         * The next-up, new-episode and tracker cards come from the last Home
         * publish and are kept until the next one refreshes them (spec
         * 2026-11-16, F2).
         */
        carryNonResume: Boolean = false
    ) {
        val application = context.applicationContext
        val profileId = runCatching { ProfileStorage.activeProfileId(application) }.getOrNull()

        // Built off the main thread: the hidden-titles read and the file write are
        // both disk work, and a publish can land while the rail is scrolling.
        val rows = withContext(Dispatchers.IO) {
            runCatching { rowsForPublish(items, showNames, application, carryNonResume) }
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

        // The TV launcher's own Continue Watching channel mirrors this same list
        // (spec 2026-11-16): one publish, two launcher surfaces, and the channel
        // is handed the rows that were just written rather than reading the
        // history again. A no-op off a television.
        runCatching { com.kennyb1201.kbstream.data.tv.TvPreviewChannelPublisher.sync(application, rows) }
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
     * What it does NOT do is delete them (spec 2026-11-16, F2). The rebuild used
     * to write the local half alone, so every round and every tap narrowed a full
     * rail down to resume-only until Home published again. The publish below runs
     * with [publish]'s `carryNonResume`, which keeps the snapshot's next-up and
     * tracker rows - re-checked against the same hidden and unaired rules - so a
     * profile showing resume cards plus two next-up cards still shows all of them
     * after the round.
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
            force = true,
            // The half local history cannot produce, kept from the last Home
            // publish (spec 2026-11-16, F2).
            carryNonResume = true
        )
    }

    /**
     * The rows this publish writes: the rail's own list, plus the carried rows a
     * rebuild is allowed to keep.
     */
    private fun rowsForPublish(
        items: List<UpNextItem>,
        showNames: Map<String, String>,
        context: Context,
        carryNonResume: Boolean
    ): List<CwWidgetRow> {
        val fresh = rows(items, showNames, context)
        if (!carryNonResume) return fresh
        return fresh + carriedForwardRows(fresh, showNames, context)
    }

    /**
     * The snapshot's non-resume rows, which local history cannot re-derive (spec
     * 2026-11-16, F2).
     *
     * Three rules, all of them the rail's:
     *
     *  - only NON-RESUME rows are carried: the resume half was just rebuilt from
     *    the live history, and a carried resume row could only be a stale copy of
     *    a card the viewer has since finished;
     *  - a show already represented by a fresh row contributes nothing, so the
     *    merge cannot double a card the rebuild did reproduce;
     *  - the hidden and unaired verdicts are asked again with today's inputs, so
     *    a title hidden (or an episode that turns out to be unaired) after Home's
     *    publish drops here exactly as it would on the rail.
     *
     * Nothing is invented: a card the snapshot never carried cannot appear here,
     * because this reads the snapshot and nothing else.
     */
    private fun carriedForwardRows(
        fresh: List<CwWidgetRow>,
        showNames: Map<String, String>,
        context: Context
    ): List<CwWidgetRow> {
        val existing = runCatching { CwWidgetStore.read(context).rows }.getOrDefault(emptyList())
        if (existing.isEmpty()) return emptyList()

        val hidden = runCatching { HiddenTitles.keys(context) }.getOrDefault(emptySet())
        val todayEpochDay = LocalDate.now(ZoneId.systemDefault()).toEpochDay()
        val freshParents = fresh.map { it.parentKey }.toSet()

        return existing.filter { row ->
            !row.isResume &&
                row.parentKey !in freshParents &&
                !row.droppedFromRail(hidden, showNames, todayEpochDay)
        }
    }

    /**
     * The rows the widget shows for [items]: the same verdicts the rail applies
     * and the same order - the rail's whole list, with no cap of the widget's own
     * (spec 2026-11-16, F1).
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
            // The rail's own resume verdict, rendered at publish time so the
            // widget draws (and taps) the card as what it is (spec 2026-11-16,
            // F3/F4). A next-up card is an offer, not a playhead.
            isResume = upNextIsResumeCard(item),
            // The identity spellings the carry-forward rules re-ask a rebuild:
            // the card's own id (tracker-sourced or not), its TMDB show id and
            // its history row, plus the air date when it proves one.
            cardId = item.id,
            tmdbId = item.tmdbId?.takeIf { it > 0 },
            historyRowId = item.historyRowId?.trim()?.takeIf { it.isNotBlank() },
            ownEpisodeAirEpochDay = upNextOwnEpisodeAirEpochDay(item),
            // The rail's own chip rule, run here, in the same publish that
            // renders the title and the progress: the launcher prints a string
            // it was handed, so it cannot print a chip the Home card does not
            // have (spec 2026-11-16, F1/F3).
            chip = upNextChipLabel(item),
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
