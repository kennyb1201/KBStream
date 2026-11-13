package com.kennyb1201.kbstream.ui.player

import java.util.Locale

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
 * Jikan answers a fuzzy search, so "Attack on Titan" and "attackontitan" are
 * the same title while "Attack on Titan" and "Attack on Titan: Junior High" are
 * not - punctuation and spacing carry no identity here, and case never does.
 */
internal fun normalizeAnimeTitle(raw: String?): String =
    raw.orEmpty()
        .trim()
        .lowercase(Locale.ROOT)
        .filter { ch -> ch.isLetterOrDigit() }

/**
 * Whether a Jikan search result names the show we asked about.
 *
 * An anime's romanized title and its English title are different fields, and
 * KBStream's title may be either - so every name the result carries
 * ([titles] is the result's whole `titles[]` array) is compared, not just
 * `title`. This gate is what stops a live-action show that merely resembles an
 * anime in a fuzzy search from being given that anime's OP/ED times.
 */
internal fun animeTitleMatches(
    showTitle: String?,
    title: String?,
    titleEnglish: String?,
    titles: List<String?>
): Boolean {
    val wanted = normalizeAnimeTitle(showTitle)
    if (wanted.isEmpty()) return false

    return (listOf(title, titleEnglish) + titles).any { candidate ->
        normalizeAnimeTitle(candidate) == wanted
    }
}

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
internal fun jikanSearchUrl(showTitle: String): String =
    "https://api.jikan.moe/v4/anime?q=" + urlEncode(showTitle.trim()) + "&limit=1"

/**
 * Percent-encoding for a query VALUE, done here rather than through
 * `android.net.Uri` so the URL builders stay JVM-testable.
 *
 * Only what actually appears in these values is escaped (space, `&`, `=`, `#`,
 * `?`, `+`, `%` and non-ASCII); an id or a duration never contains more.
 */
internal fun urlEncode(value: String): String {
    val out = StringBuilder(value.length + 8)
    for (byte in value.toByteArray(Charsets.UTF_8)) {
        val ch = byte.toInt().toChar()
        if (
            ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' ||
            ch == '-' || ch == '_' || ch == '.' || ch == '~'
        ) {
            out.append(ch)
        } else {
            out.append('%')
            out.append(HEX[(byte.toInt() shr 4) and 0xF])
            out.append(HEX[byte.toInt() and 0xF])
        }
    }
    return out.toString()
}

private const val HEX = "0123456789ABCDEF"
