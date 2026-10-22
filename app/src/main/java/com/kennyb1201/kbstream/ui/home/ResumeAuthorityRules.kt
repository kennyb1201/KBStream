package com.kennyb1201.kbstream.ui.home

import com.kennyb1201.kbstream.data.settings.ResumeAuthority

/**
 * Which card in a title's cluster the Continue Watching rail shows.
 *
 * The rail merges every source it can reach (this device's history, Simkl,
 * MDBList, Trakt), so the same title can arrive several times with several
 * resume points. [chooser][resumeAuthorityWinner] makes one source's answer win
 * when the viewer has chosen an authority, while leaving the other sources in
 * the merge as the fallback for every title the authority does not know about.
 *
 * Two deliberate narrowings:
 *
 *  - **Only an actual resume point counts.** A tracker card of the same title
 *    may be a `NEXT_UP` card (the show finished on that account). Letting it
 *    win under authority would replace a real in-progress position with "next
 *    episode", so a card only speaks for its source when it carries a progress
 *    or position.
 *  - **[ResumeAuthority.LOCAL] is a real choice too.** This device's history
 *    carries the exact millisecond position, so its in-progress rows win when
 *    the viewer picks it - and when it has no in-progress row for a title, the
 *    cluster falls back to the best card the merge already found rather than
 *    dropping a resume the trackers reported.
 */
internal fun resumeAuthorityWinner(
    candidates: List<UpNextItem>,
    authority: ResumeAuthority
): UpNextItem? {
    if (candidates.isEmpty()) return null

    val source = authority.toResumeSource()
    val preferred = candidates.filter { candidate ->
        candidate.resumeSource == source && candidate.hasResumePoint()
    }
    val pool = preferred.ifEmpty { candidates }
    return (localTargetCandidates(pool) ?: pool).maxWithOrNull(upNextWinnerOrder)
}

/**
 * The cards to rank when a cluster's local and tracker copies name DIFFERENT
 * target episodes: this device's, and only this device's.
 *
 * Reported: after finishing an episode, Continue Watching kept offering it,
 * and a tracker twin could make the node point at the wrong episode of a show
 * whose local walk knew exactly where the viewer was. Local rows are this
 * profile's own writes, made on this device from what was actually played; a
 * tracker feed can lag them, never lead them. So when the two disagree about
 * which episode comes next, the local target is the answer.
 *
 * Narrow on purpose:
 *
 *  - A card with no episode pair cannot participate - there is no target to
 *    compare, and a movie/session card keeps the ordinary order.
 *  - Agreement changes nothing: the pool is returned untouched, so the
 *    existing order (a real resume position first) still decides which copy
 *    of the SAME episode the rail shows.
 *  - No local card, no override: a tracker-only show keeps its tracker card,
 *    which is the whole point of merging the feeds.
 *
 * Applied AFTER the authority filter, so a viewer who chose a tracker as the
 * authoritative resume source still gets its position when it has one: a
 * resume point is not a target disagreement.
 */
private fun localTargetCandidates(
    candidates: List<UpNextItem>
): List<UpNextItem>? {
    val withTarget =
        candidates.filter { candidate ->
            candidate.season != null && candidate.episode != null
        }
    val local =
        withTarget.filter { candidate ->
            candidate.resumeSource == ResumeSource.LOCAL
        }
    if (local.isEmpty()) return null

    val localTargets =
        local.mapTo(HashSet()) { candidate -> candidate.season to candidate.episode }

    val trackerDisagrees =
        withTarget.any { candidate ->
            candidate.resumeSource != ResumeSource.LOCAL &&
                (candidate.season to candidate.episode) !in localTargets
        }

    return if (trackerDisagrees) local else null
}

internal fun ResumeAuthority.toResumeSource(): ResumeSource =
    when (this) {
        ResumeAuthority.LOCAL -> ResumeSource.LOCAL
        ResumeAuthority.SIMKL -> ResumeSource.SIMKL
        ResumeAuthority.MDBLIST -> ResumeSource.MDBLIST
        ResumeAuthority.TRAKT -> ResumeSource.TRAKT
    }

/** What makes a card an actual resume rather than a next-episode pointer. */
internal fun UpNextItem.hasResumePoint(): Boolean =
    badge == UpNextBadge.CONTINUE_WATCHING ||
        startPositionMs > 0L ||
        (progressPercent ?: 0f) > 0f

/**
 * The order the rail has always used to pick a cluster's winner. Kept here so
 * the authority chooser and [dedupeAndSortUpNext] cannot drift apart.
 */
internal val upNextWinnerOrder =
    compareBy<UpNextItem> { winnerScore(it) }
        .thenByDescending { it.recencyTimestamp }
        .thenBy { targetPrecisionScore(it) }
        .thenBy { it.title.lowercase() }
