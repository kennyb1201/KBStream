package com.kennyb1201.kbstream.ui.home

import com.kennyb1201.kbstream.data.history.WatchHistoryEntity
import com.kennyb1201.kbstream.data.trakt.TraktWatchedSnapshot
import com.kennyb1201.kbstream.data.namedEpisodeNumber
import java.time.LocalDate

/**
 * The Upcoming rail's caught-up candidates, from the two sources that do not
 * need Simkl: this profile's own watch history, and MDBList's account-wide
 * record of what has been watched.
 *
 * The rail only advertises a next unaired episode for a show the viewer is
 * caught up on (see [isCaughtUpForUpcoming]), and the only source of caught-up
 * shows used to be Simkl's followed-shows library. A caught-up show has nothing
 * to resume, so it is on no rail at all - which left a local-only or
 * MDBList-only viewer with an empty Upcoming rail the moment the gate landed.
 * These candidates close that: shows this account has watched every aired
 * episode of, which is exactly the set whose next episode is news.
 *
 * The pieces here are pure (rows / key sets in, candidates out) so the
 * selection - one candidate per show, newest first, capped - can be pinned by a
 * test. The work behind a candidate (a cached TMDB detail, the caught-up rule)
 * stays in HomeViewModel.
 */

/**
 * Ceiling on how many locally-watched shows one Upcoming refresh looks up on
 * TMDB, mirroring [MAX_CAUGHT_UP_UPCOMING_ITEMS] for the Simkl half. Candidates
 * are ordered newest completion first, so the cap trims the tail of a long
 * history rather than an arbitrary slice of it.
 */
internal const val MAX_LOCAL_CAUGHT_UP_UPCOMING_ITEMS =
    25

/**
 * Ceiling on the MDBList half. The tracker's watched snapshot carries no
 * ordering of its own - it is a set of ids - so this only bounds the work one
 * refresh can cost; the cards it produces are still de-duplicated per show by
 * the schedule builder.
 */
internal const val MAX_MDBLIST_CAUGHT_UP_UPCOMING_ITEMS =
    25

/**
 * Ceiling on the Trakt half, mirroring [MAX_MDBLIST_CAUGHT_UP_UPCOMING_ITEMS].
 *
 * Trakt's snapshot IS ordered (most recently watched first), so unlike MDBList
 * this cap also trims the least recently active shows rather than an arbitrary
 * slice - but it is still only a work bound, and the schedule builder collapses
 * the per-show duplicates that survive it.
 */
internal const val MAX_TRAKT_CAUGHT_UP_UPCOMING_ITEMS =
    25

/** Parallel TMDB resolutions while building the caught-up Upcoming cards. */
internal const val CAUGHT_UP_UPCOMING_CONCURRENCY =
    4

/**
 * One show that may be caught up, and the episodes counted as watched for it.
 *
 * [watchedEpisodes] is the evidence the caught-up rule reads
 * (LocalSeriesProgress.isCaughtUp): every episode of every season up to the
 * last AIRED one has to be in here. The display fields are what the local half
 * already knows (the watch-history row's own name and artwork); the MDBList half
 * leaves them null and the card falls back to what TMDB resolves.
 */
internal data class CaughtUpShowCandidate(
    val parentId: String,
    val watchedEpisodes: Set<Pair<Int, Int>>,
    val title: String? = null,
    val poster: String? = null,
    val backdrop: String? = null,
    val clearLogo: String? = null,
    /**
     * True when the candidate came from a tracker ACCOUNT rather than this
     * profile's own history. The card is then built as a tracker card, so the
     * account-wide-feed rules apply to it - a title another profile owns must
     * not surface here either (see [trackerCardOwnedByAnotherProfile]).
     */
    val fromTracker: Boolean = false
)

/**
 * Whether the episode TMDB names as `next_episode_to_air` is genuinely still
 * ahead of this viewer - the guard that keeps a stale cached detail from
 * building a phantom "Next up" card.
 *
 * Reported: after finishing the last aired episode of a show, the Upcoming rail
 * showed "Next up" for the episode that had just been watched, with no episode
 * title. The cached series detail had been fetched before that episode aired,
 * so TMDB's `next_episode_to_air` still named it; the viewer was caught up on
 * everything aired, so the card was built and shown - and on the next launch,
 * once the detail cache had rolled over, it vanished on its own. A "next"
 * episode that has already been watched, or whose air date is already behind
 * us, is not next, and the builder must not emit it.
 *
 * Two checks, deliberately different in strength:
 *  - the watched-set check is airtight and comes from the parser's own
 *    [CaughtUpShowCandidate.watchedEpisodes] (no extra fetch), because it does
 *    not depend on TMDB's data being fresh; and
 *  - the air-date check covers the case the watched set cannot see - an
 *    episode that has aired but was never recorded watched here.
 *
 * Only a date in the PAST condemns the episode. TMDB's episode dates are a
 * calendar day with no time of day, so a date that merely equals today can be
 * fifteen hours away - the schedule builder counts today as still to come, and
 * an air-day card is the one the viewer most wants. This mirrors
 * [isAiredOrUnknown], which also only treats a past date as aired.
 *
 * A missing or unparseable air date fails OPEN (the episode is kept), matching
 * the rail's existing authority for "is this upcoming": the schedule builder's
 * second-source air-date correction drops a date it cannot place, and an
 * unknown date is never on its own a reason to hide a card.
 *
 * [today] is injectable so the boundary can be tested without a clock; every
 * caller in the app takes the default.
 */
internal fun nextAirStillUpcoming(
    seasonNumber: Int?,
    episodeNumber: Int?,
    airDate: String?,
    watchedEpisodes: Set<Pair<Int, Int>>,
    today: LocalDate = LocalDate.now()
): Boolean {

    if (
        seasonNumber != null &&
        episodeNumber != null &&
        watchedEpisodes.contains(seasonNumber to episodeNumber)
    ) {
        return false
    }

    val date =
        airDate?.let { raw ->
            runCatching { LocalDate.parse(raw) }.getOrNull()
        }

    if (date != null && date.isBefore(today)) return false

    return true
}

/**
 * One candidate per show with completed episodes, most recently completed
 * first, capped at [max].
 *
 * The rows are the ones [com.kennyb1201.kbstream.data.history.WatchHistoryDao
 * .getCompletedSeriesRows] returns: completed, non-movie, ordered by completion
 * time. A show's newest row is therefore its first appearance, and the map's
 * insertion order is the completion order of the shows.
 *
 * Movies are dropped (there is no "next episode" to wait for) along with any
 * row that names no real episode: a completed row without a season/episode pair
 * proves nothing about progress, and counting it as watched would let a show
 * read as caught up on evidence that does not exist.
 */
internal fun localCaughtUpCandidates(
    rows: List<WatchHistoryEntity>,
    max: Int
): List<CaughtUpShowCandidate> {
    if (max <= 0) return emptyList()

    val byParent = LinkedHashMap<String, MutableList<WatchHistoryEntity>>()

    rows.forEach { row ->
        val parentId = row.parentId.trim().takeIf { it.isNotBlank() } ?: return@forEach
        if (upNextMediaType(row.type) == "movie") return@forEach
        byParent.getOrPut(parentId) { mutableListOf() }.add(row)
    }

    return byParent.entries
        .asSequence()
        .mapNotNull { (parentId, showRows) ->
            val watched = completedEpisodePairs(showRows)
            if (watched.isEmpty()) return@mapNotNull null

            val newest = showRows.first()

            CaughtUpShowCandidate(
                parentId = parentId,
                watchedEpisodes = watched,
                title = newest.name?.takeIf { it.isNotBlank() },
                poster = newest.poster?.takeIf { it.isNotBlank() },
                backdrop = newest.backdropUrl?.takeIf { it.isNotBlank() },
                clearLogo = newest.clearLogo?.takeIf { it.isNotBlank() }
            )
        }
        .take(max)
        .toList()
}

/**
 * The (season, episode) pairs a set of completed rows proves watched. An
 * episode numbered 0 is no episode at all (see namedEpisodeNumber), and a row
 * with no season cannot be placed in the run the rule walks.
 */
internal fun completedEpisodePairs(
    rows: List<WatchHistoryEntity>
): Set<Pair<Int, Int>> =
    rows.mapNotNull { row ->
        val season = row.season
        val episode = namedEpisodeNumber(row.episode)
        if (season != null && season > 0 && episode != null) {
            season to episode
        } else {
            null
        }
    }.toSet()

/**
 * MDBList's caught-up candidates: the shows its watched snapshot knows progress
 * for, with the episodes that snapshot lists watched.
 *
 * The snapshot carries two id forms per show - `"tt1234567"` and `"tmdb:456"`
 * (see MdbListClient.addKey) - and the same doubling on every episode key
 * (`"tt1234567:2:5"`). Both forms are kept: dropping either would lose shows
 * the tracker knows by only one id, and the schedule builder already collapses
 * two rows for one show, because both resolve to the same TMDB id (see
 * selectUpcomingPerShow). A show with no episode keys is skipped rather than
 * guessed at: "started" is not "watched the aired run".
 */
internal fun mdbListCaughtUpCandidates(
    startedShowKeys: Set<String>,
    episodeKeys: Set<String>,
    max: Int
): List<CaughtUpShowCandidate> {
    if (max <= 0) return emptyList()

    val out = ArrayList<CaughtUpShowCandidate>()

    for (key in startedShowKeys.map { it.trim() }.filter { it.isNotBlank() }.sorted()) {
        val watched = mdbListEpisodePairs(key, episodeKeys)
        if (watched.isEmpty()) continue

        out += CaughtUpShowCandidate(
            parentId = key,
            watchedEpisodes = watched,
            fromTracker = true
        )

        if (out.size >= max) break
    }

    return out
}

/**
 * Trakt's caught-up candidates: the shows its watched snapshot holds a play
 * for, with the episodes that snapshot lists watched.
 *
 * Trakt was evidence rather than a source: its watched episodes were already
 * unioned into the per-show watched set, so a show tracked on Trakt alone was
 * provably caught up and still had nowhere to announce its next episode. This
 * is that half, built the way the MDBList one is - from the snapshot the app
 * already fetches and caches (`TraktRepository.watchedSnapshot()`), so
 * contributing it costs a lookup, never a request.
 *
 * Order is the snapshot's own: `/sync/watched/shows` answers most recently
 * watched first, so [max] trims the shows this account has not touched in the
 * longest time. That is the only ordering Trakt gives us and the one the rail
 * wants, so it is kept rather than re-sorted.
 *
 * Identity: every row carries all of its id forms, and the IMDb flavour is
 * preferred because that is how the other halves key (local history parents are
 * IMDb ids, and MDBList prefers its `tt…` key) - so the same show arriving from
 * two sources collapses in the schedule builder instead of becoming two cards.
 * A `tmdb:<n>` key is the fallback, and any other canonical form after it. A
 * row with no watched episode is skipped for the MDBList half's reason:
 * "started" is not "watched the aired run".
 */
internal fun traktCaughtUpCandidates(
    snapshot: TraktWatchedSnapshot,
    max: Int
): List<CaughtUpShowCandidate> {
    if (max <= 0) return emptyList()

    val out = ArrayList<CaughtUpShowCandidate>()

    for (row in snapshot.shows) {
        if (row.episodes.isEmpty()) continue

        val parentId = row.idKeys.firstOrNull { it.startsWith("tt") }
            ?: row.idKeys.firstOrNull { it.startsWith("tmdb:") }
            ?: row.idKeys.firstOrNull()
            ?: continue

        out += CaughtUpShowCandidate(
            parentId = parentId,
            watchedEpisodes = row.episodes,
            fromTracker = true
        )

        if (out.size >= max) break
    }

    return out
}

/**
 * The (season, episode) pairs the snapshot lists for one show key. Keys are
 * `"<showId>:<season>:<episode>"`, and the id is matched with its `:` appended
 * so `"tt123"` cannot swallow `"tt1234"`'s episodes.
 */
internal fun mdbListEpisodePairs(
    showKey: String,
    episodeKeys: Set<String>
): Set<Pair<Int, Int>> {
    val prefix = "$showKey:"

    return episodeKeys
        .asSequence()
        .filter { it.startsWith(prefix) }
        .mapNotNull { key ->
            val parts = key.removePrefix(prefix).split(':')
            val season = parts.getOrNull(0)?.toIntOrNull()
            val episode = parts.getOrNull(1)?.toIntOrNull()
            if (season != null && season > 0 && episode != null && episode > 0) {
                season to episode
            } else {
                null
            }
        }
        .toSet()
}
