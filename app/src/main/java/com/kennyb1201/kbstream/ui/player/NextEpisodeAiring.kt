package com.kennyb1201.kbstream.ui.player

import android.content.Context
import com.kennyb1201.kbstream.data.tmdb.ResolvedEpisode
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import com.kennyb1201.kbstream.data.runCatchingCancellable

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
 * (`DetailScreen.isEpisodeUnavailable`, which grays out the same episodes):
 * a parseable date in the future is unaired; a blank or unparseable date is
 * NOT, because TMDB does not carry an air date for every already-aired
 * episode and treating a gap as "not out yet" would dead-end chains that
 * work today.
 *
 * THE SEASON-END JUMP is the one target the player INVENTS, and it used to be
 * offered without checking that the season exists. The reported case
 * (2026-10-13): finishing S18E10 of a show that has no S19 raised
 * "Play next: Season 19 Episode 1" instead of the recommendations row. That
 * target is `nextEpisodeTarget()`'s `(s + 1) to 1` branch, and this gate —
 * which does know how to check a season — let it through: TMDB answers a
 * season that does not exist with a 404, `getSeasonEpisodes` THROWS on it, and
 * the "unknown is not unaired" rule below reads an unreadable listing as
 * "cannot check, keep the chain". Two different situations were sharing one
 * answer. They are separated now: an unreadable listing still keeps a
 * MID-SEASON chain (unchanged - a transport hiccup must not lose the panel),
 * while a target in a season AFTER the session's own, which is only ever the
 * invented jump, has to be CONFIRMED. No id to ask with, or a listing that
 * will not load, answers null there - the because-you-watched row. A missing
 * "Play next" is a far better answer than an episode that does not exist, and
 * the viewer can still reach a real season by hand.
 *
 * THE SEASON BOUNDARY is also decided here, because the player cannot decide
 * it alone. Its own `(s + 1) episode 1` branch needs
 * `totalEpisodesInSeason`, and that count only reaches the player through the
 * launch extras — a finale launched from a route that never set it (the
 * detail screen's own `StreamsTarget` builders are the ones that do not)
 * asked for `(s, e + 1)`: an episode the season does not list, which this
 * gate read as "nothing to play" and answered with recommendations. The
 * missing count must not decide whether a season can be entered, so an
 * episode ABSENT from its season's listing is now read as "that was the
 * finale" and the next season's E1 is checked before giving up (see
 * [airedChainTarget]). The arithmetic branch stays: with the count known it
 * is still the fast path, and both routes end at the same decision.
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
    showId: String,
    sessionSeason: Int? = null,
    /**
     * True when [target] came out of a LISTING rather than out of the season
     * scheme - a random (or previous) pick, which is an episode the show has
     * listed. Such a target is never the invented jump, whatever season it
     * names.
     *
     * The distinction is not cosmetic: a random pick that landed in a LATER
     * season than the one playing was classified as the fabricated `(s + 1) to
     * 1`, so a look-up hiccup answered null and the Up next panel disappeared
     * for an episode the listing had just offered (a random-chain regression,
     * because a scheme target in a later season IS only ever that jump).
     */
    fromListing: Boolean = false
): Pair<Int, Int>? {
    if (target == null) return null

    // Is this the invented jump? See [isSeasonBoundaryTarget]: the caller's own
    // season is what tells a target in an unlisted season from any other one,
    // and it is the difference between "cannot check" and "nothing to check".
    // A target that came out of a listing is excluded outright - the listing is
    // what invented it, and it is checkable.
    val boundary = !fromListing && isSeasonBoundaryTarget(target, sessionSeason)

    // Nothing to ask TMDB about: keep the existing behavior rather than drop a
    // chain we cannot check — except a boundary target, which is the one thing
    // this may not assume, and an id the player could not resolve is one of the
    // two ways the fabricated S19E1 reached the panel.
    if (tmdbId == null || tmdbId <= 0) return unverifiableTarget(target, boundary)

    val (season, episode) = target

    // A failed lookup is "unknown", not "unaired": `getSeasonEpisodes`
    // throws on a missing key / network failure, and answering false there
    // would turn a transport hiccup into a missing Up next panel. The call
    // itself is cached (memory + disk) by the repository, so a season the
    // detail page already loaded costs nothing. [airedChainTarget] decides
    // what that unreadable listing means for THIS target.
    val seasonEpisodes = seasonEpisodesOrNull(context, tmdbId, season, showId)

    // The second lookup is owed only at a season boundary — when the episode
    // the arithmetic asked for is not in this season's listing at all (or when
    // there is no listing to ask). A listed episode decides on its own, so the
    // ordinary mid-season chain still makes exactly one request.
    val nextSeasonEpisodes =
        if (seasonEpisodes == null || seasonEpisodes.any { it.episodeNumber == episode }) {
            null
        } else {
            seasonEpisodesOrNull(context, tmdbId, season + 1, showId)
        }
    return airedChainTarget(target, seasonEpisodes, nextSeasonEpisodes, boundary = boundary)
}

/**
 * True when [target] is in a season the player has not listed: a season LATER
 * than [sessionSeason], the session's own.
 *
 * Forward is the only direction the player invents. `nextEpisodeTarget()`
 * derives every mid-season target from the scheme's own arithmetic over the
 * season that is playing, and the one it makes up out of nothing is the
 * season-end jump, `(s + 1)` episode 1 - the target nobody has listed and the
 * reason this gate exists. A target in an EARLIER season is not invented at
 * all: random mode and PREVIOUS pick from listings they have already read, so
 * an unreadable lookup for one of those is a hiccup like any other (see
 * [airedChainTarget]).
 *
 * [sessionSeason] is a parameter rather than something read from the target
 * because the target cannot say which season it came from: `(19, 1)` is the
 * fabricated jump for a session in S18 and an ordinary random pick for a
 * session in S1. That is exactly why [airedNextEpisodeTarget] takes
 * `fromListing`: on its own this test cannot tell the two apart, and only the
 * caller knows which season the target came out of.
 */
internal fun isSeasonBoundaryTarget(target: Pair<Int, Int>, sessionSeason: Int?): Boolean =
    sessionSeason != null && target.first > sessionSeason

/**
 * What the gate answers when it cannot check [target] at all — no TMDB id to
 * ask with, or a listing that would not load.
 *
 * A MID-SEASON chain is kept: unknown is not unaired, and a transport hiccup
 * must not take the panel away from a viewer who has an episode to watch. A
 * [boundary] target is dropped: it is the one target nothing has confirmed the
 * existence of, so "cannot check" and "does not exist" are the same answer to
 * the only question that matters — is there an episode there to play.
 */
internal fun unverifiableTarget(target: Pair<Int, Int>, boundary: Boolean): Pair<Int, Int>? =
    if (boundary) null else target

/** One season's listing, or null when the lookup failed (see [airedChainTarget]). */
private suspend fun seasonEpisodesOrNull(
    context: Context,
    tmdbId: Int,
    season: Int,
    showId: String
): List<ResolvedEpisode>? = runCatchingCancellable {
    withContext(Dispatchers.IO) {
        TmdbRepository.getInstance(context.applicationContext)
            .getSeasonEpisodes(tmdbId, season, showId)
    }
}.getOrNull()

/**
 * The gate's whole decision, as a value: [target] is the arithmetic next
 * episode the player asked for, [seasonEpisodes] is that season's TMDB
 * listing (null when the lookup FAILED), and [nextSeasonEpisodes] is the
 * season after it — fetched only where [seasonEpisodes] does not name the
 * episode at all, so null means both "not needed" and "failed".
 *
 * Two questions, in this order:
 *
 *  1. Does the season we are chaining into list this episode? Then the
 *     existing rule decides alone — aired gives the target, unaired gives
 *     null. This is the mid-season chain, byte-identical to before.
 *  2. It does not list it at all, so the arithmetic target does not exist
 *     there: that is what a season finale looks like when the sender could
 *     not tell us how long the season was. A finale chains into the next
 *     season instead — `(season + 1)` episode 1, when that season lists an
 *     aired E1.
 *
 * Order matters for the invariant: a listed-but-UNAIRED episode is answered
 * by question 1 and never reaches the fallback, so "not out yet" still means
 * recommendations rather than skipping straight to an episode nobody can
 * watch.
 */
internal fun airedChainTarget(
    target: Pair<Int, Int>,
    seasonEpisodes: List<ResolvedEpisode>?,
    nextSeasonEpisodes: List<ResolvedEpisode>?,
    today: LocalDate = LocalDate.now(),
    boundary: Boolean = false
): Pair<Int, Int>? {
    // A failed lookup is "unknown", not "unaired" for a chain the scheme
    // produced (unchanged): keep it rather than turn a transport hiccup into a
    // missing panel. For the invented season-end jump this IS the fabricated
    // case — TMDB 404s a season that does not exist, so "the lookup failed" is
    // what a missing season looks like from here — and it answers null (see
    // [unverifiableTarget]).
    if (seasonEpisodes == null) return unverifiableTarget(target, boundary)
    val (season, episode) = target
    val exact = seasonEpisodes.firstOrNull { it.episodeNumber == episode }
    if (exact != null) return if (!isUnaired(exact.airDate, today)) target else null
    // Not listed here at all: a season boundary. A failed next-season lookup
    // answers null — the same thing this unlisted target answered before the
    // fallback existed, so no new failure mode is introduced.
    if (nextSeasonEpisodes == null) return null
    return if (isNextEpisodeOut(nextSeasonEpisodes, 1, today)) (season + 1) to 1 else null
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
