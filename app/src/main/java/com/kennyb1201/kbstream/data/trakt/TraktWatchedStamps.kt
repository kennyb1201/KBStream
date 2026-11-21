package com.kennyb1201.kbstream.data.trakt

/**
 * The two stamps a [TraktWatchedSnapshot] is built from, and the only thing
 * needed to answer "did anything change?" (F1, spec 2026-11-20).
 *
 * `/sync/watched/shows` is episode activity rolled up per show, so the shows
 * side is the newer of the `episodes` and `shows` sections Trakt reports.
 * `/sync/watched/movies` has its own.
 *
 * A stamp that is missing is NOT "unchanged": a response without the section
 * (or a failed activities call, which yields no stamps at all) has to fall back
 * to fetching, or a quiet failure would freeze badges for the session.
 *
 * Kept free of Moshi and Android so the comparison - the whole rule - is pinned
 * by a plain JVM test.
 */
internal data class TraktWatchedStamps(
    val movies: String?,
    val series: String?
) {

    /** False when a section is unknown, which means "cannot prove unchanged". */
    val isComplete: Boolean get() = movies != null && series != null

    fun moviesMovedSince(previous: TraktWatchedStamps?): Boolean =
        previous == null || movies == null || previous.movies != movies

    fun seriesMovedSince(previous: TraktWatchedStamps?): Boolean =
        previous == null || series == null || previous.series != series

    /** One stable string, for the disk cache. `|` never appears in a timestamp. */
    fun wire(): String = "${movies.orEmpty()}|${series.orEmpty()}"

    companion object {

        /** The inverse of [wire]; null when the string is not one we wrote. */
        fun parse(raw: String?): TraktWatchedStamps? {
            val text = raw ?: return null
            val parts = text.split('|')
            if (parts.size != 2) return null
            return TraktWatchedStamps(
                movies = parts[0].takeIf { it.isNotBlank() },
                series = parts[1].takeIf { it.isNotBlank() }
            )
        }
    }
}
