package com.kennyb1201.kbstream.data.reporting

/**
 * One add-on's cost in the search wave (spec 2026-11-17, search addon timeout
 * F4, and F1's cap).
 *
 * The `search:` line only knew the TOTAL add-on leg (`addon=31041ms`), so a 31s
 * paste could not say WHICH add-on held the wave. The wave is parallel at both
 * levels, so that total is the slowest single request in it - naming each
 * add-on (and its cut requests) is what turns the next paste into an answer
 * instead of a guess.
 */
internal data class SearchAddonTiming(
    val name: String,
    val ms: Long,
    val timedOut: Int = 0
)

/** How many add-ons the `search:` line names; the rest are only counted. */
internal const val SEARCH_ADDON_BREAKDOWN_LIMIT = 3

/**
 * The `search:` line's add-on breakdown, slowest first, or null when no add-on
 * ran.
 *
 * Format: `addons=Penguplay:8001ms*,FlixStreams:412ms timeouts=1`. A `*` marks
 * an add-on that had at least one request cut at the cap, `(+N)` counts the
 * add-ons past [limit] so a long list never crowds the line out, and
 * `timeouts` is the wave's total of cut requests. Ties keep install order
 * (the sort is stable), so two equally slow add-ons read the same way twice.
 *
 * An add-on that never answered still appears with the whole wave's time:
 * that is exactly the one worth naming.
 */
internal fun searchAddonBreakdown(
    timings: List<SearchAddonTiming>,
    limit: Int = SEARCH_ADDON_BREAKDOWN_LIMIT
): String? {
    if (timings.isEmpty()) return null
    val ordered = timings.sortedByDescending { it.ms }
    val shown = ordered.take(limit)
    val parts = shown.joinToString(",") { timing ->
        buildString {
            append(timing.name.replace(UNSAFE_NAME, "_").trim('_').ifEmpty { "addon" })
            append(':').append(timing.ms).append("ms")
            if (timing.timedOut > 0) append('*')
        }
    }
    val hidden = ordered.size - shown.size
    return buildString {
        append("addons=").append(parts)
        if (hidden > 0) append("(+").append(hidden).append(')')
        append(" timeouts=").append(ordered.sumOf { it.timedOut })
    }
}

/**
 * The characters that would break the `key=value` line: the comma separates
 * add-ons, `=`/space separate fields, and `*` is the cut marker. Add-on names
 * are user-supplied manifest strings, so they are not assumed clean.
 */
private val UNSAFE_NAME = Regex("[\\s,=*]+")
