package com.kennyb1201.kbstream.ui.components

/**
 * The app's one spelling of a duration: the short `1h 42m` / `42m` form the
 * stream picker, the episode cards and the continue-watching rows all use, so
 * no two screens disagree about how long something is.
 */
fun formatRuntimeMinutes(minutes: Int): String {
    val hours = minutes / 60
    val mins = minutes % 60
    return when {
        hours > 0 && mins > 0 -> "${hours}h ${mins}m"
        hours > 0 -> "${hours}h"
        else -> "${mins}m"
    }
}

/**
 * A run of digits that is nothing but a number of minutes, either bare ("96")
 * or carrying its own minutes unit ("96 min", "96m"). Anything else an add-on
 * sends -- "1h 36m", "1 hr 36 min" -- fails to match and is left alone.
 */
private val MINUTES_ONLY =
    Regex(
        """^(\d{1,4})\s*(?:min|mins|minute|minutes|m)?$""",
        RegexOption.IGNORE_CASE
    )

/**
 * The detail screen's meta-line runtime, from whichever source has one.
 *
 * TMDB hands over a number of minutes and always renders in the short form. An
 * add-on's `runtime` is a free-form string instead, and most send bare minutes
 * ("96"), which used to reach the meta line unlabelled and read as a mystery
 * count next to the year and the rating. Those get the short form too, so the
 * line always says how long the thing is. A runtime the add-on spells out some
 * other way ("1h 36m") is passed through exactly as it wrote it -- there is no
 * reason to second-guess a duration that already reads as one.
 */
fun formatRuntimeLabel(
    tmdbMinutes: Int?,
    addonRuntime: String?
): String? {
    tmdbMinutes?.takeIf { it > 0 }?.let { return formatRuntimeMinutes(it) }
    val raw = addonRuntime?.trim().orEmpty()
    val minutes =
        MINUTES_ONLY.find(raw)
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
    return when {
        minutes != null && minutes > 0 -> formatRuntimeMinutes(minutes)
        // A "0" or a "0 min" is an absent runtime, not a zero-length one, so
        // it drops out of the line rather than rendering as "0m".
        minutes != null -> null
        else -> raw.takeIf { it.isNotEmpty() }
    }
}
