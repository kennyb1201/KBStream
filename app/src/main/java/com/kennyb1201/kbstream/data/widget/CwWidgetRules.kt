package com.kennyb1201.kbstream.data.widget

/**
 * The Continue Watching widget's arithmetic, with no Android in it (spec
 * 2026-11-13).
 *
 * The widget is a surface, not a second list: it shows what Home's Continue
 * Watching rail shows, capped at the rows a launcher grid can read. Everything
 * a row SAYS - how far through it is, how much is left, which episode it is - is
 * computed here so a test can pin it without a launcher.
 */
internal object CwWidgetRules {

    /**
     * How many rows the widget will ever publish.
     *
     * Eight is the most a 4x5 launcher cell can show without turning the widget
     * into a scrolling list nobody reads; the launcher grants fewer cells than
     * that more often than not, and the ListView simply shows the ones that fit.
     */
    const val MAX_ROWS = 8

    /**
     * A snapshot older than this is drawn with a stale marker.
     *
     * Nothing polls in the background, so a device whose app has not been opened
     * for days still has a list to draw - it is just not a claim about "now".
     * Seven days matches the app's other disk-cache horizons and is long enough
     * that a normal week of use never shows the marker.
     */
    const val STALE_AFTER_MS = 7L * 24L * 60L * 60L * 1000L

    /** Clamped fraction played, or 0 when the duration is unknown. */
    fun progress(positionMs: Long, durationMs: Long): Float {
        if (positionMs <= 0L || durationMs <= 0L) return 0f
        return (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }

    /** Minutes left, rounded up so "1 min left" is never "0 min left" while playing. */
    fun remainingMinutes(positionMs: Long, durationMs: Long): Int? {
        if (durationMs <= 0L) return null
        val left = durationMs - positionMs
        if (left <= 0L) return 0
        return ((left + 59_999L) / 60_000L).toInt()
    }

    /**
     * The row's second line: `S2E5 · The Name`, or the name alone for a film or a
     * card with no numbering.
     *
     * The numbering is TMDB's, which is what the history row behind the card is
     * filed under; the widget does not renumber (see data/tvdb - the order
     * changes display numbers on Detail, and the widget is a resume surface, not
     * an episode browser).
     */
    fun subtitle(season: Int?, episode: Int?, episodeTitle: String?): String {
        val name = episodeTitle?.trim().orEmpty()
        val numbered = if (season != null && episode != null && episode > 0) {
            "S" + season.coerceAtLeast(0) + "E" + episode
        } else {
            ""
        }
        return when {
            numbered.isEmpty() -> name
            name.isEmpty() -> numbered
            else -> "$numbered · $name"
        }
    }

    /** `1h 12m left`, `12m left`, or blank when the duration is unknown. */
    fun timeLeftLabel(remainingMinutes: Int?): String {
        val minutes = remainingMinutes?.takeIf { it >= 0 } ?: return ""
        if (minutes == 0) return ""
        if (minutes < 60) return "${minutes}m left"
        val hours = minutes / 60
        val rest = minutes % 60
        return if (rest == 0) "${hours}h left" else "${hours}h ${rest}m left"
    }

    /** True when a snapshot is old enough to be described as stale. */
    fun isStale(writtenAtMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean =
        writtenAtMs <= 0L || nowMs - writtenAtMs > STALE_AFTER_MS

    /** The header's freshness line: `Updated 5m ago`, or the stale marker. */
    fun freshnessLabel(writtenAtMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        if (writtenAtMs <= 0L) return "Open the app to refresh"
        if (isStale(writtenAtMs, nowMs)) return "Stale · open the app to refresh"
        val minutes = ((nowMs - writtenAtMs).coerceAtLeast(0L)) / 60_000L
        return when {
            minutes < 1L -> "Updated just now"
            minutes < 60L -> "Updated ${minutes}m ago"
            minutes < 24L * 60L -> "Updated ${minutes / 60L}h ago"
            else -> "Updated ${minutes / (24L * 60L)}d ago"
        }
    }
}
