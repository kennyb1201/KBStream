package com.kennyb1201.kbstream.ui.home

import com.kennyb1201.kbstream.data.history.WatchHistoryEntity

/**
 * The pure selection half of Continue Watching's local "next up" rail: which
 * locally-watched shows get a card, and in what order.
 *
 * The rail itself is built one show at a time (`buildLocalNextUpItem`, which
 * does the TMDB lookup and episode resolution). This function decides the
 * candidate SET first, so the expensive per-show work only ever runs for
 * shows that can actually produce a card — and so the rule is unit testable
 * without a database, a network, or a HomeViewModel.
 *
 * @param completedRows every completed-episode row from the watch history,
 *   across all shows (see WatchHistoryDao.getCompletedSeriesRows).
 * @param representedIdentifiers dedupe ids of shows already on the rail (a
 *   paused episode's resume card), which must not also get a next-up twin.
 * @param max cap on how many candidates are returned, newest completion first
 *   so an active show wins a slot when the tail is trimmed.
 * @return `(parentId, row)` pairs, newest completion first, one per show.
 */
internal fun selectLocalNextUpCandidates(
    completedRows: List<WatchHistoryEntity>,
    representedIdentifiers: Set<String>,
    max: Int
): List<Pair<String, WatchHistoryEntity>> =
    completedRows
        // One candidate per show. A blank parentId falls back to the row's own
        // id so an unscoped row still dedupes against itself instead of
        // collapsing every such row into a single group.
        .groupBy { row ->
            row.parentId.trim().ifBlank { row.id.trim() }
        }
        .mapNotNull { (parentId, rows) ->
            rows
                .maxByOrNull { row -> row.completedAt ?: row.updatedAt }
                ?.let { row -> parentId to row }
        }
        // Newest completion first: an active show must win a slot.
        .sortedByDescending { (_, row) -> row.completedAt ?: row.updatedAt }
        // Shows already on the rail (an episode paused part-way) keep their
        // resume card; a next-up twin for them is wasted work.
        .filterNot { (parentId, _) ->
            val id = upNextIdentifier(parentId)
            id != null && id in representedIdentifiers
        }
        .take(max)
