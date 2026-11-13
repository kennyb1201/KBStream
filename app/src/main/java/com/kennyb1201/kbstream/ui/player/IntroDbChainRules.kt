package com.kennyb1201.kbstream.ui.player

import com.kennyb1201.kbstream.data.anime.animeTitleMatches as animeTitleMatchesInDataLayer
import com.kennyb1201.kbstream.data.anime.animeUrlEncode
import com.kennyb1201.kbstream.data.anime.jikanAnimeSearchUrl
import com.kennyb1201.kbstream.data.anime.normalizeAnimeTitle as normalizeAnimeTitleInDataLayer
import java.util.Locale
import kotlin.math.roundToLong

/**
 * The rules of the skip-source chain, with no network, no JSON and no Android.
 *
 * [fetchIntroDbStamps] asks five services in order and keeps, per segment kind,
 * the answer of the FIRST source that has that kind (see [mergeIntroDbStamps]).
 * Everything that decides the shape of that chain lives here so it can be
 * tested without a player: the merge and its precedence, which kinds keep the
 * chain running, how an anime title is matched against Jikan's answer, how
 * Anime Skip's point-shaped timestamps become segments, and the exact URLs and
 * GraphQL documents the sources are asked for.
 */

/**
 * The kinds whose absence keeps the chain running.
 *
 * `PostCredits` is deliberately not here: it is rare enough that requiring it
 * would make every lookup walk all five sources, and a source that happens to
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

// ── Anime Skip: points, not intervals (spec 2026-11-13) ───────────────────

/**
 * Anime Skip's only read surface. GraphQL, and the client id is a header.
 *
 * The web README pins this exact URL as `NUXT_PUBLIC_API_URL`, and there is no
 * REST endpoint, so this is the whole API for the chain's fifth leg.
 */
internal const val ANIME_SKIP_GRAPHQL_URL = "https://api.anime-skip.com/graphql"

/**
 * One Anime Skip timestamp: a POINT on the timeline, in SECONDS, with the name
 * of its type.
 *
 * This is the modelling fact the leg exists on. AniSkip hands over intervals
 * (`startTime`/`endTime`), so its rows are used as given; Anime Skip hands over
 * the moments a segment BEGINS, and the segment runs until the next timestamp
 * of any type. [pairAnimeSkipTimestamps] is what turns those points back into
 * segments, which is also why the non-skippable types (Canon, Filler, Transition,
 * Title Card, Branding) matter: they are the bounds that END a skip.
 */
internal data class AnimeSkipPoint(val atSeconds: Double, val typeName: String?)

/** One `GqlEpisode`, reduced to the id and the three fields the match reads. */
internal data class AnimeSkipEpisode(
    val id: String?,
    val season: String?,
    val number: String?,
    val absoluteNumber: String?
)

/**
 * The Anime Skip timestamp type names this app can offer, and the kind each one
 * is.
 *
 * The names are the live `allTimestampTypes` vocabulary, not a guess: the API
 * answers with exactly Branding, Canon, Credits, Filler, Intro, Mixed Credits,
 * Mixed Intro, Must Watch, New Credits, New Intro, Preview, Recap, Title Card,
 * Transition and Unknown. "New" is the first airing of an opening and "Mixed"
 * is one overlaid with plot - both are the same skip for the viewer. Everything
 * else deliberately maps to null: Canon/Filler/Must Watch are content, and
 * Branding/Title Card/Transition are too short to be worth a button. They still
 * bound the preceding segment (see [pairAnimeSkipTimestamps]).
 */
internal fun animeSkipKind(typeName: String?): IntroDbMarkerType? =
    when (typeName?.trim()?.lowercase(Locale.ROOT)) {
        "intro", "new intro", "mixed intro" -> IntroDbMarkerType.Intro
        "recap" -> IntroDbMarkerType.Recap
        "credits", "new credits", "mixed credits" -> IntroDbMarkerType.Credits
        "preview" -> IntroDbMarkerType.Preview
        else -> null
    }

/**
 * Turns Anime Skip's points into segments, then keeps the skippable ones.
 *
 * Sort by [AnimeSkipPoint.atSeconds], then each point's segment runs to the NEXT
 * point's time, whatever its type. The last point has no next point, so its
 * segment runs to the end of the file - which [IntroDbStamp.endMs] already
 * records as null, and which the player resolves against the duration it knows.
 *
 * The edge cases are the whole reason this is a function rather than three
 * lines inside the fetch:
 *
 *  - a skippable point followed by another skippable point ends at that one's
 *    time (Intro at 0s then Recap at 90s is an Intro of [0, 90));
 *  - a point followed by a non-skippable one ends at the non-skippable time
 *    (Intro at 0s then Canon at 90s is an Intro of [0, 90));
 *  - two points at the same time produce no stamp at all (a zero-length
 *    segment has nothing to skip);
 *  - a known duration clamps the end and drops a segment that starts at or past
 *    it, because a point after the file has ended cannot be acted on;
 *  - a type this app does not offer emits nothing, but still ends the segment
 *    before it - an unknown type must never swallow the bound.
 *
 * Times are seconds in, milliseconds out, the same unit change AniSkip's reader
 * makes.
 */
internal fun pairAnimeSkipTimestamps(
    points: List<AnimeSkipPoint>,
    durationMs: Long?
): List<IntroDbStamp> {
    if (points.isEmpty()) return emptyList()

    val sorted = points
        .filter { it.atSeconds.isFinite() && it.atSeconds >= 0.0 }
        .sortedBy { it.atSeconds }

    return sorted.mapIndexedNotNull { index, point ->
        val startMs = (point.atSeconds * 1_000.0).roundToLong()
        if (durationMs != null && durationMs > 0L && startMs >= durationMs) return@mapIndexedNotNull null

        val next = sorted.getOrNull(index + 1)
        val endMs = next
            ?.let { (it.atSeconds * 1_000.0).roundToLong() }
            ?.let { if (durationMs != null && durationMs > 0L) it.coerceAtMost(durationMs) else it }
            ?: durationMs?.takeIf { it > 0L }

        val kind = animeSkipKind(point.typeName) ?: return@mapIndexedNotNull null
        if (endMs != null && endMs <= startMs) return@mapIndexedNotNull null

        IntroDbStamp(
            startMs = startMs,
            endMs = endMs,
            type = kind,
            confidence = null,
            source = IntroDbSource.AnimeSkip
        )
    }
}

/** "5", "05" and " 5 " are all episode five. */
private fun normalizeEpisodeNumber(raw: String?): String? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null
    return text.toIntOrNull()?.toString() ?: text.lowercase(Locale.ROOT)
}

/**
 * Which of the show's episodes is the one being played, or null when none is.
 *
 * `number` is the season-local number and `absoluteNumber` the running total, so
 * both are tried: a long-running show's entry is often keyed by the absolute
 * number, and a season entry by the local one. Within each pass a matching
 * season wins over a bare number match, because a show can list the same local
 * number in every season - and returning the first such episode would put
 * season one's intro on season three.
 */
internal fun selectAnimeSkipEpisode(
    episodes: List<AnimeSkipEpisode>,
    season: Int?,
    episode: Int
): Int? {
    val playing = episode.toString()
    val playingSeason = season?.toString()

    fun pick(matches: (AnimeSkipEpisode) -> Boolean): Int? {
        val candidates = episodes.indices.filter { matches(episodes[it]) }
        if (candidates.isEmpty()) return null
        return candidates.firstOrNull { normalizeEpisodeNumber(episodes[it].season) == playingSeason }
            ?: candidates.first()
    }

    return pick { normalizeEpisodeNumber(it.number) == playing }
        ?: pick { normalizeEpisodeNumber(it.absoluteNumber) == playing }
}

/**
 * A GraphQL request body: one JSON string member, `query`.
 *
 * Escaped by hand rather than through `org.json` so these builders stay in the
 * Android-free half of the chain and can be asserted on the JVM - the same
 * reason the URL builders are here.
 */
internal fun animeSkipQueryBody(query: String): String =
    "{\"query\":\"" + animeSkipJsonEscape(query) + "\"}"

private fun animeSkipJsonEscape(raw: String): String {
    val out = StringBuilder(raw.length + 16)
    for (ch in raw) {
        when (ch) {
            '\\' -> out.append("\\\\")
            '"' -> out.append("\\\"")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            else -> if (ch.code < 0x20) out.append("\\u%04x".format(ch.code)) else out.append(ch)
        }
    }
    return out.toString()
}

/**
 * The show lookup by AniList id - the authoritative path.
 *
 * The live schema HAS an external-id lookup (`findShowsByExternalId`), and its
 * only service is `ANILIST`, so the id it takes is the AniList id the anime ID
 * hub already resolved for this title. That is a real id match, not a title
 * guess: no fuzzy search, and nothing to gate afterwards.
 *
 * `externalLinks` is asked for because the query matches loosely - a one-digit
 * `serviceId` comes back with every show whose link contains it (AniList id 1
 * answered 691 shows) - so the reader keeps only the show that names this exact
 * id.
 */
internal fun animeSkipShowsByAniListIdQuery(anilistId: Int): String =
    animeSkipQueryBody(
        "{ findShowsByExternalId(service: ANILIST, serviceId: \"" + anilistId + "\") " +
            "{ id name externalLinks { service serviceId } } }"
    )

/** The fuzzy title search, used only when the hub has no AniList id. */
internal fun animeSkipShowSearchQuery(showTitle: String, limit: Int = 5): String =
    animeSkipQueryBody(
        "{ searchShows(search: \"" + animeSkipGraphqlString(showTitle) + "\", limit: " +
            limit.coerceIn(1, 20) + ") { id name originalName } }"
    )

/** A show's episodes, with the three fields the match reads. */
internal fun animeSkipEpisodesQuery(showId: String): String =
    animeSkipQueryBody(
        "{ findEpisodesByShowId(showId: \"" + animeSkipGraphqlString(showId) + "\") " +
            "{ id season number absoluteNumber } }"
    )

/** One episode's timestamps: the point, the type name, and nothing else. */
internal fun animeSkipTimestampsQuery(episodeId: String): String =
    animeSkipQueryBody(
        "{ findTimestampsByEpisodeId(episodeId: \"" + animeSkipGraphqlString(episodeId) + "\") " +
            "{ at type { name } } }"
    )

/**
 * Escapes a value for a GraphQL string literal.
 *
 * The title is whatever TMDB called the show, so it can carry quotes (and the
 * backslash that follows one). GraphQL's string syntax is JSON's for these two
 * characters, so the JSON escaper is the right one.
 */
private fun animeSkipGraphqlString(raw: String): String {
    val out = StringBuilder(raw.length + 8)
    for (ch in raw) {
        when (ch) {
            '\\' -> out.append("\\\\")
            '"' -> out.append("\\\"")
            '\n', '\r', '\t' -> out.append(' ')
            else -> out.append(ch)
        }
    }
    return out.toString()
}

