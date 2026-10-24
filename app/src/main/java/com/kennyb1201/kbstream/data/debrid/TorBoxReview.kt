package com.kennyb1201.kbstream.data.debrid

import android.content.Context
import com.kennyb1201.kbstream.data.library.LibraryItem
import com.kennyb1201.kbstream.data.library.LibraryOrigin
import com.kennyb1201.kbstream.data.library.LocalLibraryStore

/**
 * One row of the "unowned cloud titles" review, resolved against the list as it
 * stands right now.
 *
 * [preSelected] is the one piece of attribution the data does carry: a row the
 * sync itself stamped ([LibraryOrigin.TORBOX_SYNC]) was definitely written by
 * the sync, while an unmarked row may have been added by hand. The review
 * therefore opens with the sync's own rows ticked and the viewer's possible
 * hand-adds clear, which is the difference between "confirm this cleanup" and
 * "decide from scratch, N times".
 */
internal data class TorBoxReviewRow(
    val key: String,
    val item: LibraryItem,
    val preSelected: Boolean
)

/**
 * The review queue's whole logic, shared by both Settings panes so the TV and
 * the phone cannot resolve, pre-select or remove differently.
 *
 * The queue itself is a set of de-dupe keys filed by the last completed round
 * (see [TorBoxCloudStore.reviewCandidates]). Titles, years and types are read
 * from the CURRENT My List each time it is opened: a row removed since the round
 * - here, on a tracker, or by a cleanup - simply stops resolving rather than
 * being listed as something that no longer exists.
 */
internal object TorBoxReview {

    /**
     * The queue resolved against [entries], in My List order.
     *
     * Pure, so the pre-selection rule and the "keys that no longer resolve are
     * dropped" rule are pinned by a JVM test rather than by a device.
     */
    fun rows(entries: List<LibraryItem>, keys: Set<String>): List<TorBoxReviewRow> {
        if (keys.isEmpty()) return emptyList()
        return entries
            .map { entry ->
                TorBoxReviewRow(
                    key = LocalLibraryStore.dedupeKey(entry),
                    item = entry,
                    preSelected = entry.origin == LibraryOrigin.TORBOX_SYNC
                )
            }
            .filter { it.key in keys }
    }

    /** The queue resolved against this profile's current My List. */
    fun rows(context: Context, store: TorBoxCloudStore = TorBoxCloudStore(context)): List<TorBoxReviewRow> =
        rows(LocalLibraryStore.myList(context), store.reviewCandidates())

    /**
     * Removes the confirmed rows through the same tombstoning path as the
     * cleanup, and drops them from the queue.
     *
     * Tombstones are what make a decision stick: a later round - the schedule,
     * a Resync, a TV pull - skips a tombstoned title, and the only way back is
     * the viewer adding it again by hand, which clears the record. Rows the
     * viewer left unticked stay queued, because they are still rows no rule can
     * attribute and hiding them is the silence this queue exists to end.
     *
     * Returns how many rows actually left the list.
     */
    fun remove(context: Context, store: TorBoxCloudStore, rows: List<TorBoxReviewRow>): Int {
        if (rows.isEmpty()) return 0
        val removed = LocalLibraryStore.removeMyListEntries(context, rows.map { it.item })
        store.forgetReviewCandidates(rows.map { it.key }.toSet())
        // The removal is news to a pane that is already open (and to the status
        // line's pending count), exactly like a filed round.
        TorBoxSyncStatus.publish()
        return removed
    }
}
