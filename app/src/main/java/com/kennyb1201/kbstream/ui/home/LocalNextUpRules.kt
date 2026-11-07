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
 * @param tmdbIdFor resolves a raw parent id to its TMDB series id, so two raw
 *   flavors of one show collapse into one candidate (see below). Null/absent
 *   keeps the raw-string grouping exactly as it was.
 * @return `(parentId, row)` pairs, newest completion first, one per show.
 */
internal fun selectLocalNextUpCandidates(
    completedRows: List<WatchHistoryEntity>,
    representedIdentifiers: Set<String>,
    max: Int,
    tmdbIdFor: (String) -> Int? = { null }
): List<Pair<String, WatchHistoryEntity>> {

    fun rawKey(row: WatchHistoryEntity): String =
        row.parentId.trim().ifBlank { row.id.trim() }

    fun showKey(parentId: String): String =
        tmdbIdFor(parentId)?.let { tmdbId -> "tmdb:$tmdbId" } ?: "raw:$parentId"

    fun newestRow(rows: List<WatchHistoryEntity>): WatchHistoryEntity? =
        rows.maxByOrNull { row -> row.completedAt ?: row.updatedAt }

    return completedRows
        // One group per raw flavor. A blank parentId falls back to the row's
        // own id so an unscoped row still dedupes against itself instead of
        // collapsing every such row into a single group.
        .groupBy(::rawKey)
        .map { (parentId, rows) -> Triple(showKey(parentId), parentId, rows) }
        // Then ONE candidate per SHOW: two raw flavors that resolve to the same
        // TMDB id are the same show (see the note on the returning pass below).
        .groupBy { (key, _, _) -> key }
        .mapNotNull { (key, members) ->
            val parentIds = members.map { (_, parentId, _) -> parentId }

            // Shows already on the rail (an episode paused part-way) keep their
            // resume card; a next-up twin for them is wasted work. Tested
            // against every flavor the group was filed under AND the resolved
            // TMDB id, so a `tt...` group also matches a `tmdb:<n>` resume card.
            val represented =
                parentIds.any { parentId ->
                    val id = upNextIdentifier(parentId)
                    id != null && id in representedIdentifiers
                } || (
                    key.startsWith("tmdb:") &&
                        key.removePrefix("tmdb:") in representedIdentifiers
                    )

            if (represented) return@mapNotNull null

            val rows = members.flatMap { (_, _, rows) -> rows }

            // The card's row is the newest completion across the merged
            // flavors: it is the fallback's floor when the resolver refuses to
            // walk, so "the stale E1 row" must never be the one carried.
            val row = newestRow(rows) ?: return@mapNotNull null

            // Same preference as the returning pass: a `tt...` flavor when the
            // show has one (it is the player's canonicalization), else the
            // newest row's flavor, else the group's own.
            val parentId =
                parentIds.firstOrNull { it.startsWith("tt", ignoreCase = true) }
                    ?: newestRow(rows)?.let(::rawKey)
                    ?: parentIds.first()

            parentId to row
        }
        // Newest completion first: an active show must win a slot.
        .sortedByDescending { (_, row) -> row.completedAt ?: row.updatedAt }
        .take(max)
}

/**
 * One returning-show candidate after the id-flavor collapse: the row list of
 * every raw parent-id group that resolved to the same show, and the single
 * parent id the card will be built from.
 */
internal data class ReturningShowGroup(
    /** Re-key: `tmdb:<id>` when the detail resolved, else `raw:<parentId>`. */
    val key: String,
    /** The flavor [HomeViewModel.buildLocalNextUpItem] should be called with. */
    val parentId: String,
    /** Union of the completed rows across every merged flavor. */
    val rows: List<WatchHistoryEntity>
)

/**
 * Re-key completed rows by resolved TMDB id before the returning-show pass
 * picks its candidates, so a show whose history landed under two id flavors
 * (`tt...` and `tmdb:<n>`) yields ONE candidate instead of a good card plus a
 * degraded twin.
 *
 * Both passes now collapse before they pick: [selectLocalNextUpCandidates] uses
 * the same resolution for the regular 25 (it was raw-string grouped, so a show
 * filed under two flavors produced two candidates - one of them built from an
 * older episode), and the returning pass's exclusion is by the same key.
 *
 * Here each raw group's key is resolved through [tmdbIdFor]. Two raw groups
 * that resolve to the same TMDB id merge into one group, so the furthest
 * watched episode is computed over the union of flavors (strictly more correct
 * than either alone); a group whose detail does not resolve keeps today's
 * exact behavior under a `raw:` key, so nothing is fabricated.
 *
 * @param completedRows every completed-episode row from the watch history.
 * @param excludedParentIds the regular 25's parent ids; each is mapped through
 *   the SAME [tmdbIdFor] so a `tt123` candidate excludes its `tmdb:456` twin.
 * @param tmdbIdFor resolves a raw parent id to its TMDB series id (null when
 *   the detail is unknown).
 * @return one [ReturningShowGroup] per surviving show. The surviving `parentId`
 *   prefers a `tt...` flavor when the merged group has one (matching the
 *   player's canonicalization), else the newest row's flavor.
 */
internal fun collapseReturningShowCandidates(
    completedRows: List<WatchHistoryEntity>,
    excludedParentIds: Set<String>,
    tmdbIdFor: (String) -> Int?
): List<ReturningShowGroup> {
    fun rawKey(row: WatchHistoryEntity): String =
        row.parentId.trim().ifBlank { row.id.trim() }

    fun keyFor(parentId: String): String =
        tmdbIdFor(parentId)?.let { "tmdb:$it" } ?: "raw:$parentId"

    val excludedKeys = excludedParentIds.mapTo(mutableSetOf(), ::keyFor)

    return completedRows
        .groupBy(::rawKey)
        .map { (parentId, rows) -> Triple(keyFor(parentId), parentId, rows) }
        .filterNot { (key, _, _) -> key in excludedKeys }
        .groupBy { (key, _, _) -> key }
        .map { (key, members) ->
            val rows = members.flatMap { (_, _, rows) -> rows }
            val ttFlavor =
                members.firstOrNull { (_, parentId, _) ->
                    parentId.startsWith("tt", ignoreCase = true)
                }?.second
            val newestFlavor =
                rows.maxByOrNull { it.completedAt ?: it.updatedAt }
                    ?.let(::rawKey)
            ReturningShowGroup(
                key = key,
                parentId = ttFlavor ?: newestFlavor ?: members.first().second,
                rows = rows
            )
        }
}

/**
 * Returning-show gate: does TMDB know of an AIRED episode later than the
 * furthest episode watched locally? Season-major comparison. Null on either
 * side means "cannot prove it" -> false (honest-unknown; never fabricate).
 * A season-0 (specials) last-aired never re-qualifies a show on its own:
 * (0, N) is not greater than any real season.
 *
 * This is what notices a show whose whole first season was finished long ago
 * and whose SECOND season has since aired: the normal candidate set is capped
 * on recency (a show completed a year ago never makes it in), so without this
 * gate its seasons are never re-walked and the new season stays invisible.
 *
 * @param furthestSeason the highest season number with a locally completed
 *   episode, or null when nothing about the show's position is known.
 * @param furthestEpisode the highest episode number within [furthestSeason].
 * @param lastAiredSeason TMDB's `last_episode_to_air` season number.
 * @param lastAiredEpisode TMDB's `last_episode_to_air` episode number.
 * @return true only when an aired episode lies strictly beyond what was
 *   watched - the re-qualification is provable, not assumed.
 */
internal fun hasUnwatchedAiredEpisodes(
    furthestSeason: Int?,
    furthestEpisode: Int?,
    lastAiredSeason: Int?,
    lastAiredEpisode: Int?
): Boolean {
    if (furthestSeason == null || furthestEpisode == null) return false
    if (lastAiredSeason == null || lastAiredEpisode == null) return false
    return lastAiredSeason > furthestSeason ||
        (lastAiredSeason == furthestSeason && lastAiredEpisode > furthestEpisode)
}

/**
 * Whether the show's LATEST AIRED episode is already watched - the strongest
 * statement the merged state can make, and the one that means "no card".
 *
 * Continue Watching only ever offers the next thing to watch after the furthest
 * point watched, so once that furthest point is the last episode that has
 * aired, there is nothing to print: a card for that episode is the finished
 * episode wearing a "next up" badge. The rules that withhold an unresolvable
 * ADVANCE card deliberately refuse to record caught-up (unknown evidence is
 * not "finished"), but this is not unknown evidence - it is a positive mark on
 * the latest aired episode, from a source that answered.
 *
 * A gap BEHIND the furthest watched episode cannot produce this card (the
 * walk starts after the furthest point), so it never needs saying here: a
 * viewer who watched E2 but not E1 is caught up in the rail's terms.
 *
 * @param latestSeason TMDB's `last_episode_to_air` season, null when the
 *   detail is stale or does not say - the caller then abstains.
 * @param mergedWatchedKeys the show's merged watched keys (see
 *   `WatchedEpisodeState.buildMergedWatchedKeys`): the local rows re-anchored
 *   to the card's parent id plus the trackers' pairs.
 * @param trackerWatchedEpisodes the raw tracker pairs, as belt and braces for
 *   any key the merge did not build.
 * @return true only when the latest aired pair is positively watched.
 */
internal fun latestAiredEpisodeWatched(
    latestSeason: Int?,
    latestEpisode: Int?,
    mergedWatchedKeys: Set<String>,
    trackerWatchedEpisodes: Set<Pair<Int, Int>>
): Boolean {
    if (latestSeason == null || latestEpisode == null) return false

    val latest =
        latestSeason to latestEpisode

    if (latest in trackerWatchedEpisodes) return true

    return mergedWatchedKeys.any { key ->
        parseEpisodeKey(key)
            ?.let { (_, season, episode) -> season to episode } == latest
    }
}

/**
 * Where the listing fallback starts walking from: the FURTHEST episode the
 * state proves, not the candidate row's own.
 *
 * The fallback ([HomeViewModel.resolveEpisodeAfterCompletedRow]) exists for the
 * case where the watched state cannot be trusted enough to name the next
 * episode. It used to start from the candidate ROW - the newest completion
 * under ONE id flavor - so with a tracker knowing E5, a local row at E3 and E7
 * aired, it printed E4: an episode the merged state already knew was watched.
 * The resolver's own pair is the furthest point it knows, which is exactly the
 * floor the answer needs. Null on either side of the resolved pair keeps the
 * row, so a pair-less answer (the incomplete short-circuit) behaves as before.
 *
 * @return the season-major maximum of the two pairs.
 */
internal fun fallbackEpisodeFloor(
    rowSeason: Int,
    rowEpisode: Int,
    resolvedSeason: Int?,
    resolvedEpisode: Int?
): Pair<Int, Int> {
    if (resolvedSeason == null || resolvedEpisode == null) {
        return rowSeason to rowEpisode
    }

    val resolvedIsAhead =
        resolvedSeason > rowSeason ||
            (resolvedSeason == rowSeason && resolvedEpisode > rowEpisode)

    return if (resolvedIsAhead) {
        resolvedSeason to resolvedEpisode
    } else {
        rowSeason to rowEpisode
    }
}

/**
 * Whether a LOCAL advance card must be withheld because it cannot be trusted.
 *
 * The reported ghost: a show the viewer had just finished came back on CORRECT
 * ENTRY (not in the same session) as "Next up" of the very episode that was
 * finished, and the ghost burned off after a few restarts. Both shapes reduce
 * to one print site - [HomeViewModel.buildLocalNextUpItem] turning the series
 * resolver's answer into a card - and two ways the answer can be a pair nobody
 * asked for:
 *
 * 1. The resolver refused to walk because the show's watched state could not be
 *    read completely (`watchedStateIncompleteShows`). It still returns the one
 *    pair it KNOWS - the tracker's episode or the furthest episode watched -
 *    which is right for the tracker callers and is exactly the finished
 *    episode here.
 * 2. The resolver walked against a watched set that silently missed the local
 *    rows (an IMDb twin that failed to resolve, so the "tt..."-filed row was
 *    never read), and the walk therefore stepped onto the finished episode
 *    itself.
 *
 * [evidenceIncomplete] closes the first: unknown evidence is not "nothing
 * watched", so no advance card is printed from it. The pair comparison closes
 * the second, and is the backstop for any future gap: an ADVANCE card's target
 * is by construction a different episode than the row it was built from, so a
 * target equal to the row is proof the walk was missing that row.
 *
 * Neither answer may be PRINTED, and neither is "caught up" - the caller must
 * not record the show as finished on the strength of this (see
 * [HomeViewModel]'s caught-up recording). It must not hide the show either: an
 * unreadable watched state says nothing about whether episodes are left, and
 * hiding a show with one episode to go was the worse half of the bug (field
 * 2026-10-30). The caller therefore falls back to the next AIRED episode after
 * the row, which the listing can prove without any watched state (see
 * `HomeViewModel.resolveEpisodeAfterCompletedRow`); only a show with nothing
 * aired left is omitted.
 *
 * A pair-less row cannot prove self-target (there is no episode to compare),
 * so the shape stays the caller's to handle - see the write-side residual.
 *
 * @param targetSeason the resolver's target season, null when unknown.
 * @param targetEpisode the resolver's target episode, null when unknown.
 * @param rowSeason the candidate history row's own season, null when absent.
 * @param rowEpisode the candidate history row's own episode, null when absent.
 * @param evidenceIncomplete the show's watched state could not be read fully.
 * @return true when this resolver answer may not be printed as an advance card
 *   (the caller falls back to the next listed episode after the row).
 */
internal fun shouldOmitLocalAdvanceCard(
    targetSeason: Int?,
    targetEpisode: Int?,
    rowSeason: Int?,
    rowEpisode: Int?,
    evidenceIncomplete: Boolean
): Boolean {
    if (evidenceIncomplete) return true
    if (targetSeason == null || targetEpisode == null) return false
    if (rowSeason == null || rowEpisode == null) return false
    return targetSeason == rowSeason && targetEpisode == rowEpisode
}

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
 * A SECOND, stronger input exists because a local completion row is not the
 * only way an episode becomes watched: a viewer who presses transport Next (or
 * backs out) in the closing minutes, before the end-of-episode card has been
 * raised, writes a resume row with a minute or two left while the tracker is
 * told the episode is watched. Nothing local ever supersedes that row — the
 * "later local completion" rule above cannot match an episode against ITSELF,
 * and there is no local completion at all — so the rail kept offering
 * "Resume 2 min left" for an episode the tracker, the Detail page's tick and
 * the viewer all call finished. [trackerWatchedByShow] supplies exactly that
 * evidence, and a same-episode tracker mark condemns the row on its own
 * without consulting any timestamp, because the row cannot be newer than a
 * mark that describes the same episode.
 *
 * @param resumeRows in-progress rows (`positionMs > 0`, `isCompleted = 0`).
 * @param completedRows completed episode rows across all shows (see
 *   `WatchHistoryDao.getCompletedSeriesRows`).
 * @param trackerWatchedByShow watch-tracker episodes per show, as
 *   `season to episode` pairs, keyed by [supersededResumeShowKey] so a show's
 *   rows group together whatever id flavor they arrived under.
 * @return the ids of the resume rows that must be ignored.
 */
internal fun supersededResumeRowIds(
    resumeRows: List<WatchHistoryEntity>,
    completedRows: List<WatchHistoryEntity>,
    trackerWatchedByShow: Map<String, Set<Pair<Int, Int>>> = emptyMap()
): Set<String> {
    if (resumeRows.isEmpty()) return emptySet()
    // Deliberately not `|| completedRows.isEmpty()`: a show the tracker has
    // marked watched can leave a local resume row behind with no local
    // completion anywhere, which is the whole reason the tracker set is an
    // input. The empty-completed case still short-circuits below.

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
    if (completedByShow.isEmpty() && trackerWatchedByShow.isEmpty()) return emptySet()

    return resumeRows
        .filter { row ->
            val season = row.season ?: return@filter false
            val episode = row.episode ?: return@filter false
            val showKey =
                showIdentifier(row) ?: return@filter false

            // The tracker says this very episode is watched, so the local row
            // is a leftover from the moment before the tracker caught up.
            // Checked before the timestamp comparison below and without it: a
            // same-episode mark is decisive on its own, and the row's
            // `updatedAt` is not evidence against it - a background touch or a
            // re-watch can move that timestamp without unwatching anything.
            if (
                trackerWatchedByShow[showKey]
                    ?.contains(season to episode) == true
            ) {
                return@filter true
            }

            val completed =
                completedByShow[showKey]
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

/**
 * The key [supersededResumeRowIds] expects a show's tracker watched set under.
 *
 * Exposed so the caller groups a show the same way the rule groups its rows: a
 * Continue Watching row can arrive as "tmdb:123", a bare "123" or a "tt..." id
 * for one and the same show, and a watched set keyed by a different flavor than
 * the row simply never matches - a silent no-op that looks exactly like the bug
 * this input exists to fix.
 */
internal fun supersededResumeShowKey(parentId: String): String? =
    parentId.trim().takeIf { it.isNotEmpty() }?.let(::upNextIdentifier)

/**
 * The tracker-marks snapshot the NEXT up-next cycle reads: [fresh] folded into
 * [old], never replacing it.
 *
 * The cycle order is "suppress first, enrich after": [supersededResumeRowIds]
 * runs at the top, then the enrichment pass refills the live map only for the
 * rows that SURVIVED it. A show the suppression dropped is therefore never
 * re-enriched, so if the snapshot were the working map alone, that show's marks
 * would vanish on the next refresh - the row would be kept, the card would come
 * back, enrichment would refill the marks, and the refresh after that would
 * drop it again. That is the Continue Watching flicker: the rows are never
 * deleted, the card oscillates read-side on every refresh.
 *
 * A union keeps a dropped show's last-known marks, which is what makes the drop
 * HOLD. A show that IS re-enriched still contributes its fresh set and, because
 * [fresh] wins the union on a key collision, an episode the tracker has since
 * unmarked stops suppressing on the next cycle that keeps the show.
 *
 * The accepted cost: a show that stays continuously dropped is never re-read,
 * so a tracker mark removed elsewhere leaves its card suppressed until process
 * death or a profile switch (the hard clear). No stop point is lost - the rows
 * are untouched; only the card stays hidden.
 */
internal fun mergeSuppressionMarks(
    old: Map<String, Set<Pair<Int, Int>>>,
    fresh: Map<String, Set<Pair<Int, Int>>>
): Map<String, Set<Pair<Int, Int>>> = old + fresh

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
