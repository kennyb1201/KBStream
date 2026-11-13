package com.kennyb1201.kbstream.data.anime

import com.kennyb1201.kbstream.data.format.DateFormats
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * The two AniList facts the anime Detail header shows (spec 2026-11-13, F3).
 *
 * Both are pure on purpose: the header is assembled from a list of label parts,
 * and these decide whether a part exists at all. A missing score is not a zero
 * and a missing next episode is not a placeholder - the line simply does not
 * carry them.
 */

/**
 * AniList's `averageScore` (0-100) as the `/10` figure viewers know, or null.
 *
 * Rounded to one decimal - 84 becomes `8.4` - and never rendered as `0.0`: an
 * unscored title has no score to show, and a zero would read as "terrible"
 * rather than "unrated".
 */
internal fun aniListScoreLabel(averageScore: Int?): String? {
    val score = averageScore?.takeIf { it > 0 } ?: return null
    // A 0-100 score is already in tenths: 85 IS 8.5, so the label is the digit
    // split, not a rounding.
    val whole = score / 10
    val tenth = score % 10
    return "AniList $whole.$tenth"
}

/**
 * AniList's next airing episode as a header part (`Next E9 · 2026-11-20`), or
 * null.
 *
 * TMDB WINS when it knows the next episode: the header must never carry two
 * competing next-episode lines, and TMDB is the metadata the rest of the screen
 * is built from. So this is only asked for when TMDB has none.
 *
 * The date is AniList's `airingAt` (epoch SECONDS) in the viewer's zone; a date
 * that is already past is not offered either - it is not a "next" episode.
 */
internal fun aniListNextAiringLabel(
    episode: Int?,
    airingAtSeconds: Long?,
    tmdbHasNextEpisode: Boolean,
    today: LocalDate = LocalDate.now(ZoneId.systemDefault()),
    zone: ZoneId = ZoneId.systemDefault()
): String? {
    if (tmdbHasNextEpisode) return null

    val episodeNumber = episode?.takeIf { it > 0 }
    val at = airingAtSeconds?.takeIf { it > 0L }
    if (episodeNumber == null || at == null) return null

    val date = runCatching { Instant.ofEpochSecond(at).atZone(zone).toLocalDate() }
        .getOrNull() ?: return null
    if (date.isBefore(today)) return null

    return "Next E$episodeNumber \u00b7 " + date.format(DateFormats.AIR_DATE)
}
