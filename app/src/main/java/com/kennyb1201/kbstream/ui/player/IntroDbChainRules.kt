package com.kennyb1201.kbstream.ui.player

import com.kennyb1201.kbstream.data.anime.animeTitleMatches as animeTitleMatchesInDataLayer
import com.kennyb1201.kbstream.data.anime.animeUrlEncode
import com.kennyb1201.kbstream.data.anime.jikanAnimeSearchUrl
import com.kennyb1201.kbstream.data.anime.normalizeAnimeTitle as normalizeAnimeTitleInDataLayer

/**
 * The rules of the skip-source chain, with no network, no JSON and no Android.
 *
 * [fetchIntroDbStamps] asks four services in order and keeps, per segment kind,
 * the answer of the FIRST source that has that kind (see [mergeIntroDbStamps]).
 * Everything that decides the shape of that chain lives here so it can be
 * tested without a player: the merge and its precedence, which kinds keep the
 * chain running, how an anime title is matched against Jikan's answer, and the
 * exact URLs the two new sources are asked for.
 */

/**
 * The kinds whose absence keeps the chain running.
 *
 * `PostCredits` is deliberately not here: it is rare enough that requiring it
 * would make every lookup walk all four sources, and a source that happens to
 * carry it still contributes it (the merge keeps any kind not yet filled).
 */
internal val INTRO_DB_CORE_KINDS: List<IntroDbMarkerType> = listOf(
    IntroDbMarkerType.Intro,
    IntroDbMarkerType.Recap,
    IntroDbMarkerType.Outro,
    IntroDbMarkerType.Credits,
    IntroDbMarkerType.Preview
)

/**
 * Combined stamps after asking another source: [incoming] contributes only the
 * kinds [current] does not have yet.
 *
 * Precedence is per KIND, not per source - a source that answers with an intro
 * and a preview keeps both, and a later source's intro is dropped while its
 * credits are kept. Two sources are never mixed inside one kind: their times can
 * come from different releases, so an intro beginning at 43s and a credits row
 * from another service still describe one consistent segment each, while
 * "averaging" the two intros would describe nothing.
 *
 * Within the winning source the rows are kept exactly as its reader produced
 * them (including its own earliest-wins choice for array-shaped answers): the
 * merge never re-sorts or re-picks.
 */
internal fun mergeIntroDbStamps(
    current: List<IntroDbStamp>,
    incoming: List<IntroDbStamp>
): List<IntroDbStamp> {
    if (incoming.isEmpty()) return current

    val filled = current.mapTo(HashSet(current.size)) { it.type }

    return current + incoming.filter { stamp -> stamp.type !in filled }
}

/** Whether any core kind is still unfilled, i.e. whether the chain continues. */
internal fun introDbChainStillOpen(stamps: List<IntroDbStamp>): Boolean {
    if (stamps.isEmpty()) return true
    val present = stamps.mapTo(HashSet(stamps.size)) { it.type }
    return INTRO_DB_CORE_KINDS.any { kind -> kind !in present }
}

/**
 * A title reduced to what can be compared across services: lowercase, letters
 * and digits only.
 *
 * The rule itself lives in `data.anime.AnimeTitleRules`, beside the hub and the
 * tracker legs that also need it; this is the name the intro-skip chain and its
 * tests have always used.
 */
internal fun normalizeAnimeTitle(raw: String?): String = normalizeAnimeTitleInDataLayer(raw)

/**
 * Whether a service's name for a title is the one we asked about.
 *
 * See `data.anime.animeTitleMatches`: every name a result carries is compared
 * (romanized, English, `titles[]`), because KBStream's title may be either.
 */
internal fun animeTitleMatches(
    showTitle: String?,
    title: String?,
    titleEnglish: String?,
    titles: List<String?>
): Boolean = animeTitleMatchesInDataLayer(showTitle, title, titleEnglish, titles)

/** The id form SkipDB accepts: an IMDb id, never a TMDB or add-on id. */
internal fun isSkipDbImdbId(parentId: String): Boolean {
    val id = parentId.trim()
    return id.startsWith("tt", ignoreCase = true) &&
        id.length > 2 &&
        id.drop(2).all { it.isDigit() }
}

/**
 * SkipDB's segment query.
 *
 * `adjust=conservative` is sent explicitly rather than left to the default: when
 * the stream's duration is close but not identical, SkipDB shifts the timestamps
 * EARLIER only, so a shifted skip is early rather than late. A late button has
 * already played past the intro it was offering.
 *
 * `duration` is the stream length in whole seconds and is omitted when unknown -
 * the response then reports `match: "agnostic"` instead of shifting anything.
 * `type` is deliberately not sent: one call answers every segment kind.
 */
internal fun skipDbSegmentsUrl(
    parentId: String,
    season: Int?,
    episode: Int?,
    durationMs: Long?
): String {
    val id = parentId.trim()
    val hasEpisode = season != null && episode != null
    val durationSeconds = durationMs?.takeIf { it > 0L }?.let { it / 1_000L }

    val query = buildList {
        add("imdb_id=" + urlEncode(id))
        if (hasEpisode) {
            add("season=$season")
            add("episode=$episode")
        }
        if (durationSeconds != null && durationSeconds > 0L) add("duration=$durationSeconds")
        add("adjust=conservative")
    }

    return "https://api.skipdb.tv/api/segments?" + query.joinToString("&")
}

/**
 * AniSkip's skip-times query.
 *
 * The three types KBStream can use, and nothing else. `episodeLength` is the
 * episode's duration in SECONDS and is omitted when unknown.
 */
internal fun aniSkipTimesUrl(malId: Int, episode: Int, durationMs: Long?): String {
    val lengthSeconds = durationMs?.takeIf { it > 0L }?.let { it / 1_000L }
    val query = buildList {
        add("types[]=op")
        add("types[]=ed")
        add("types[]=recap")
        if (lengthSeconds != null && lengthSeconds > 0L) add("episodeLength=$lengthSeconds")
    }
    return "https://api.aniskip.com/v2/skip-times/$malId/$episode?" + query.joinToString("&")
}

/** Jikan's title search, which is how a MAL id is found for [showTitle]. */
internal fun jikanSearchUrl(showTitle: String): String = jikanAnimeSearchUrl(showTitle)

/**
 * Percent-encoding for a query VALUE, done in the anime layer so every URL
 * builder in the app shares one implementation (and stays JVM-testable).
 */
internal fun urlEncode(value: String): String = animeUrlEncode(value)

