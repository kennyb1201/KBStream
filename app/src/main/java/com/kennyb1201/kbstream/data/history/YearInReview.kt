package com.kennyb1201.kbstream.data.history

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * One title's line in a year's review.
 *
 * [episodes] is how many of its rows were COMPLETED in the year (1 for a movie,
 * which can only complete once), and [lastAtMs] is its newest completion - the
 * tie-break for the shows list and the whole ordering for the movies one, where
 * "most recent" is the only honest ranking a table with no play count supports.
 */
data class YearTitle(
    val parentId: String,
    val name: String,
    val poster: String?,
    /** The row's media type: what lets a movie be labelled a movie, not an episode. */
    val type: String,
    val episodes: Int,
    val lastAtMs: Long
)

/** One day of the year with its completed runtime, in the viewer's own days. */
data class YearDay(
    val date: LocalDate,
    val runtimeMs: Long
)

/**
 * A finished year, computed from the active profile's own history (spec
 * 2026-11-13).
 *
 * ## The hours figure is an APPROXIMATION, and says so
 *
 * History keeps ONE row per title/episode and updates it in place: there is no
 * per-session accumulation and no play counter, so a rewatch, a scrub or a
 * finished-then-rewound episode is still one row. "Hours watched" is therefore
 * the runtime of what was finished during the year (each completed row's own
 * duration, counted once) plus the saved position of what was left in progress
 * during it - not a stopwatch. The screen labels it "≈" and prints the reason
 * underneath; it must never be presented as exact.
 *
 * ## Marked rows are excluded
 *
 * A "Mark as Watched" row is a completion the viewer recorded, not a measured
 * watch (see [WatchHistoryEntity.markedWatched] and the same exclusion the
 * viewing-stats screen makes). Counting them would fabricate hundreds of
 * episodes at a 1 ms sentinel duration each, and would invent streak days. The
 * rows themselves are untouched - verdicts, Continue Watching and sync still
 * read them.
 *
 * Pure by construction: the screen feeds it rows and it answers numbers, so the
 * day boundaries, the streak rule and the rankings are pinned by unit tests
 * rather than by a device.
 */
data class YearReview(
    val year: Int,
    /** True when the profile has NO rows at all in the year - the honest empty state. */
    val empty: Boolean,
    /** Σ durationMs of completed rows + Σ positionMs of rows left in progress (approximate). */
    val approxRuntimeMs: Long,
    val episodesFinished: Int,
    val moviesFinished: Int,
    /** Shows whose latest episode in history was completed during this year. */
    val showsFinished: Int,
    val topShows: List<YearTitle>,
    /** Most recent movie completions - NOT "most watched": that is not computable. */
    val recentMovies: List<YearTitle>,
    val busiestDay: YearDay?,
    val longestStreakDays: Int,
    val firstFinished: YearTitle?,
    val lastFinished: YearTitle?
)

/** One completed episode row, as the "is this show finished" rule needs it. */
data class EpisodeStamp(
    val parentId: String,
    val season: Int?,
    val episode: Int?,
    val completedAt: Long
)

object YearInReview {

    /** How many titles each ranked list holds. */
    const val TOP_TITLES = 5

    /**
     * Computes the review for [year].
     *
     * [rows] are the rows whose relevant stamp falls inside the year: a completed
     * row by `completedAt`, an in-progress row by `updatedAt` (see
     * `WatchHistoryDao.rowsForYear`). [episodeStamps] are every completed episode
     * row the profile has, ANY year, because "this show is finished" is a
     * statement about the show's own latest episode - a show whose last episode
     * was completed in 2025 is not a 2026 finish even if an earlier episode was
     * replayed in 2026.
     */
    fun compute(
        year: Int,
        rows: List<WatchHistoryEntity>,
        episodeStamps: List<EpisodeStamp>,
        zone: ZoneId = ZoneId.systemDefault()
    ): YearReview {
        val watched = rows.filter { !it.markedWatched }
        val completed = watched.filter { row ->
            row.isCompleted && row.completedAt != null && yearOf(row.completedAt, zone) == year
        }
        val inProgress = watched.filter { row ->
            !row.isCompleted && row.positionMs > 0L && yearOf(row.updatedAt, zone) == year
        }

        val approxRuntime =
            completed.sumOf { it.durationMs.coerceAtLeast(0L) } +
                inProgress.sumOf { it.positionMs.coerceAtLeast(0L) }

        val completedEpisodes = completed.filter { !isMovieOrChannel(it.type) }
        val completedMovies = completed.filter { isMovieOrChannel(it.type) && isMovie(it.type) }

        val byParent = completedEpisodes.groupBy { it.parentId }
        val topShows = byParent.map { (parentId, episodes) ->
            val newest = episodes.maxByOrNull { it.completedAt ?: 0L } ?: episodes.first()
            YearTitle(
                parentId = parentId,
                name = newest.name,
                poster = newest.poster,
                type = newest.type,
                episodes = episodes.size,
                lastAtMs = newest.completedAt ?: newest.updatedAt
            )
        }.sortedWith(compareByDescending<YearTitle> { it.episodes }.thenByDescending { it.lastAtMs })
            .take(TOP_TITLES)

        val recentMovies = completedMovies
            .groupBy { it.parentId }
            .map { (parentId, movies) ->
                val newest = movies.maxByOrNull { it.completedAt ?: 0L } ?: movies.first()
                YearTitle(
                    parentId = parentId,
                    name = newest.name,
                    poster = newest.poster,
                    type = newest.type,
                    episodes = 1,
                    lastAtMs = newest.completedAt ?: newest.updatedAt
                )
            }
            .sortedByDescending { it.lastAtMs }
            .take(TOP_TITLES)

        // Busiest day: the most completed runtime on one calendar day. A tie goes
        // to the later day, because a later day is the more recent record and the
        // screen cannot show two.
        val busiestDay = completed
            .groupBy { day(it.completedAt, zone) }
            .map { (date, dayRows) -> YearDay(date, dayRows.sumOf { it.durationMs.coerceAtLeast(0L) }) }
            .filter { it.runtimeMs > 0L }
            .maxWithOrNull(compareBy<YearDay> { it.runtimeMs }.thenBy { it.date })

        val completionDays = completed
            .mapNotNull { it.completedAt }
            .map { day(it, zone).toEpochDay() }
            .distinct()
            .sorted()
        val longestStreak = longestRun(completionDays)

        val chronological = completed
            .sortedWith(compareBy({ it.completedAt ?: 0L }, { it.parentId }))
        val first = chronological.firstOrNull()?.toYearTitle()
        val last = chronological.lastOrNull()?.toYearTitle()

        val showsFinished = showsFinishedIn(
            year = year,
            episodeStamps = episodeStamps,
            zone = zone
        )

        return YearReview(
            year = year,
            empty = watched.isEmpty(),
            approxRuntimeMs = approxRuntime,
            episodesFinished = completedEpisodes.size,
            moviesFinished = completedMovies.size,
            showsFinished = showsFinished,
            topShows = topShows,
            recentMovies = recentMovies,
            busiestDay = busiestDay,
            longestStreakDays = longestStreak,
            firstFinished = first,
            lastFinished = last
        )
    }

    /**
     * How many shows had their latest episode completed during [year].
     *
     * "Latest" is the highest (season, episode) the profile has any completion
     * for, and a show counts only when THAT row's completion stamp falls in the
     * year. A rewatch of an early episode in 2026 therefore does not claim the
     * show as a 2026 finish, and a show whose finale was finished in 2025 is not
     * counted twice. It is what the local table can honestly say: the app has no
     * "series ended" flag of its own.
     */
    fun showsFinishedIn(
        year: Int,
        episodeStamps: List<EpisodeStamp>,
        zone: ZoneId = ZoneId.systemDefault()
    ): Int = episodeStamps
        .groupBy { it.parentId }
        .values
        .count { stamps ->
            val latest = stamps.maxWithOrNull(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))
                ?: return@count false
            yearOf(latest.completedAt, zone) == year
        }

    /**
     * The longest run of consecutive calendar days carrying a completion.
     *
     * [days] are epoch-day numbers (see [YearDay]) in any order. 0 when there is
     * none. Only the days in the list count, so a run that ends on Dec 31 is the
     * run inside this year - the review is a year, not a season of the viewer's
     * life.
     */
    fun longestRun(days: List<Long>): Int {
        if (days.isEmpty()) return 0
        val sorted = days.distinct().sorted()
        var best = 1
        var run = 1
        for (index in 1 until sorted.size) {
            run = if (sorted[index] == sorted[index - 1] + 1L) run + 1 else 1
            if (run > best) best = run
        }
        return best
    }

    /** Years with a completion in [times], newest first, plus [currentYear] itself. */
    fun yearsWithHistory(times: List<Long>, currentYear: Int, zone: ZoneId = ZoneId.systemDefault()): List<Int> {
        val years = times.mapNotNull { yearOf(it, zone) }.toMutableSet()
        years.add(currentYear)
        return years.sortedDescending()
    }

    /** The calendar year a timestamp falls in, in the viewer's zone. */
    fun yearOf(ms: Long?, zone: ZoneId = ZoneId.systemDefault()): Int? {
        val stamp = ms ?: return null
        return Instant.ofEpochMilli(stamp).atZone(zone).year
    }

    /** The local day a timestamp falls on. */
    fun day(ms: Long?, zone: ZoneId = ZoneId.systemDefault()): LocalDate {
        val stamp = ms ?: 0L
        return Instant.ofEpochMilli(stamp).atZone(zone).toLocalDate()
    }

    /** The opening instant of [year] in the viewer's zone: what the year query needs. */
    fun yearStartMs(year: Int, zone: ZoneId = ZoneId.systemDefault()): Long =
        LocalDate.of(year, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun WatchHistoryEntity.toYearTitle(): YearTitle = YearTitle(
        parentId = parentId,
        name = name,
        poster = poster,
        type = type,
        episodes = 1,
        lastAtMs = completedAt ?: updatedAt
    )

    private fun isMovie(type: String): Boolean = type.lowercase() == "movie"

    private fun isMovieOrChannel(type: String): Boolean =
        type.lowercase() in setOf("movie", "channel")
}
