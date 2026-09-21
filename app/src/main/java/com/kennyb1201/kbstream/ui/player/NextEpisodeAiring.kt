package com.kennyb1201.kbstream.ui.player

import android.content.Context
import com.kennyb1201.kbstream.data.tmdb.ResolvedEpisode
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Air-date gate for the player's end-of-playback chain.
 *
 * `NativePlayerActivity.nextEpisodeTarget()` is purely arithmetic — `e + 1`,
 * or `(s + 1)` episode 1 when the episode was the season's last — and the
 * player carries no air-date awareness at all. So finishing S2E1 while S2E2
 * is still unaired offered S2E2 in the "Up next" panel, and PLAY NEXT /
 * autoplay then tried to resolve streams for an episode that does not exist
 * yet (and the "Because you watched" credits row, which shares that panel
 * slot, could never appear mid-series).
 *
 * This function answers the one question that decision was missing: is the
 * episode we are about to chain into actually out? `null` means "do not
 * chain" — the caller then falls through to the recommendations row, which
 * is the same thing it already does for a finished finale.
 *
 * The yes/no rule deliberately mirrors the catalog's own
 * (`DetailScreen.isEpisodeUnavailable`, which greys out the same episodes):
 * a parseable date in the future is unaired; a blank or unparseable date is
 * NOT, because TMDB does not carry an air date for every already-aired
 * episode and treating a gap as "not out yet" would dead-end chains that
 * work today.
 *
 * WIRED INTO the player — [airedNextEpisodeTarget] gates all three places
 * that chain forward:
 *
 *  - `NativePlayerActivity.onPlaybackEnded()`: a non-null target shows the
 *    Up next panel, null shows the because-you-watched row.
 *  - `advanceToNextEpisode()` (overlay Next button + media NEXT): it reports
 *    "Next episode hasn't aired yet" rather than resolving a stream that
 *    cannot exist.
 *  - the overlay's name/runtime prefetch, which no longer advertises an
 *    episode the Next button refuses to play.
 *
 * NOTE: those call sites sit past the edit window of the tooling used to
 * build this project (roughly the first 65 KB of the 287 KB file), which is
 * why the logic lives here instead of inline — change them with a diff
 * rather than an in-place edit.
 */
suspend fun airedNextEpisodeTarget(
    context: Context,
    target: Pair<Int, Int>?,
    tmdbId: Int?,
    showId: String
): Pair<Int, Int>? {
    if (target == null) return null

    // Nothing to ask TMDB about: keep the existing behaviour rather than
    // drop a chain we cannot check.
    if (tmdbId == null || tmdbId <= 0) return target

    val (season, episode) = target

    // A failed lookup is "unknown", not "unaired": `getSeasonEpisodes`
    // throws on a missing key / network failure, and answering false there
    // would turn a transport hiccup into a missing Up next panel. The call
    // itself is cached (memory + disk) by the repository, so a season the
    // detail page already loaded costs nothing.
    val seasonEpisodes = runCatching {
        withContext(Dispatchers.IO) {
            TmdbRepository.getInstance(context.applicationContext)
                .getSeasonEpisodes(tmdbId, season, showId)
        }
    }.getOrNull() ?: return target

    return if (isNextEpisodeOut(seasonEpisodes, episode)) target else null
}

/**
 * True when episode [episodeNumber] of the season we are chaining into is
 * playable: it exists in the season's episode list AND its air date is not
 * in the future.
 *
 * An episode the season does not list is NOT out. That covers both halves of
 * the report — a mid-season episode that has not aired yet (TMDB lists it
 * with a future date, which [isUnaired] rejects) and a jump to next season
 * episode 1 for a season that has not been listed at all (no entries).
 */
internal fun isNextEpisodeOut(
    seasonEpisodes: List<ResolvedEpisode>,
    episodeNumber: Int,
    today: LocalDate = LocalDate.now()
): Boolean {
    val next = seasonEpisodes.firstOrNull { it.episodeNumber == episodeNumber } ?: return false
    return !isUnaired(next.airDate, today)
}

/**
 * True when [airDate] is a parseable date still in the future. Blank or
 * unparseable dates are "aired" on purpose — see the file docs.
 */
internal fun isUnaired(
    airDate: String?,
    today: LocalDate = LocalDate.now()
): Boolean {
    val trimmed = airDate?.trim().orEmpty()
    if (trimmed.isEmpty()) return false
    val parsed = runCatching { LocalDate.parse(trimmed) }.getOrNull() ?: return false
    return parsed.isAfter(today)
}
