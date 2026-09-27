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

/**
 * Ids of in-progress (resume) rows a LATER completed episode has already
 * overtaken — the "Continue S1E5 while the user is actually on S3E15" report.
 *
 * Continue Watching follows the furthest point watched, the way Simkl and
 * MDBList do. A resume row is only worth surfacing while it is the show's
 * furthest progress: a show whose episode 5 was paused but whose episode 15
 * (a later season) has since been watched must resume from 15 onward, not
 * snap back to the abandoned row. Without this, marking newer episodes
 * watched left the old partial row as the only in-progress one, so it won the
 * rail every single time.
 *
 * Only a LATER completed episode that was watched *after* the resume row was
 * last touched counts. A user re-watching a finished show from the beginning
 * writes a fresh resume row whose timestamp is newer than every completion,
 * so it stays; a long-abandoned row cannot outweigh episodes watched since.
 *
 * @param resumeRows in-progress rows (`positionMs > 0`, `isCompleted = 0`).
 * @param completedRows completed episode rows across all shows (see
 *   `WatchHistoryDao.getCompletedSeriesRows`).
 * @return the ids of the resume rows that must be ignored.
 */
internal fun supersededResumeRowIds(
    resumeRows: List<WatchHistoryEntity>,
    completedRows: List<WatchHistoryEntity>
): Set<String> {
    if (resumeRows.isEmpty() || completedRows.isEmpty()) return emptySet()

    // Completed episodes per show, keyed the way the rail dedupes shows so a
    // "tmdb:123" row and a "123" row are the same show.
    val completedByShow = mutableMapOf<String, MutableList<CompletedEpisode>>()
    for (row in completedRows) {
        val season = row.season ?: continue
        val episode = row.episode ?: continue
        val key = showIdentifier(row) ?: continue
        completedByShow
            .getOrPut(key) { mutableListOf() }
            .add(
                CompletedEpisode(
                    season = season,
                    episode = episode,
                    watchedAt = row.completedAt ?: row.updatedAt
                )
            )
    }
    if (completedByShow.isEmpty()) return emptySet()

    return resumeRows
        .filter { row ->
            val season = row.season ?: return@filter false
            val episode = row.episode ?: return@filter false
            val completed =
                showIdentifier(row)?.let(completedByShow::get)
                    ?: return@filter false
            val resumeTouchedAt = row.updatedAt
            completed.any { done ->
                val isLater =
                    done.season > season ||
                        (done.season == season && done.episode > episode)
                isLater && done.watchedAt > resumeTouchedAt
            }
        }
        .map { it.id }
        .toSet()
}

/** One completed episode, with the time it was watched. */
private data class CompletedEpisode(
    val season: Int,
    val episode: Int,
    val watchedAt: Long
)

/**
 * Normalized show key for [supersededResumeRowIds]. A blank parent id falls
 * back to the row's own id so an unscoped row still groups with itself instead
 * of collapsing every such row into one group.
 */
private fun showIdentifier(row: WatchHistoryEntity): String? {
    val raw = row.parentId.trim().ifBlank { row.id.trim() }
    return upNextIdentifier(raw)
}
