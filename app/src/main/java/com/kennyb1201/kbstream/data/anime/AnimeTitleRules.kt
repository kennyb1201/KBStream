package com.kennyb1201.kbstream.data.anime

import java.util.Locale

/**
 * How an anime title is compared across services.
 *
 * Every id the anime features need comes from a title: AniList and Kitsu are
 * searched by name, and Jikan only knows how to search by name, so a title match
 * is the gate that stops "a live-action show that resembles an anime" from
 * inheriting that anime's ids. The rule is deliberately exact after normalizing,
 * never a prefix or a fuzzy score: "Attack on Titan" and "Attack on Titan: Junior
 * High" are different shows, and a subtitle is a different title.
 *
 * Lives in the data layer because the hub and both tracker legs use it, and
 * because it has no Android dependency at all.
 */
internal fun normalizeAnimeTitle(raw: String?): String =
    raw.orEmpty()
        .trim()
        .lowercase(Locale.ROOT)
        .filter { ch -> ch.isLetterOrDigit() }

/**
 * Whether one service's name for a title is the one we asked about.
 *
 * An anime's romanized title and its English title are different fields, and
 * KBStream's title (from TMDB) may be either - so every name a result carries is
 * compared, not just the first.
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

/**
 * Percent-encoding for a query value, done here rather than through
 * `android.net.Uri` so every URL builder in the anime layer stays JVM-testable.
 *
 * Only what actually appears in these values is escaped; unreserved characters
 * (`A-Z a-z 0-9 - _ . ~`) are kept and everything else becomes `%XX`.
 */
internal fun animeUrlEncode(value: String): String {
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

/** Jikan's title search, the hub's last resort for a MyAnimeList id. */
internal fun jikanAnimeSearchUrl(showTitle: String): String =
    "https://api.jikan.moe/v4/anime?q=" + animeUrlEncode(showTitle.trim()) + "&limit=1"

private const val HEX = "0123456789ABCDEF"
