package com.kennyb1201.kbstream.data.history

import com.kennyb1201.kbstream.ui.home.YearInReviewCard
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The year-in-review arithmetic, pinned without a device.
 *
 * The numbers are the whole feature (spec 2026-11-13, F1) and every one of them
 * is a rule a viewer can check against their own Detail pages, so each rule gets
 * a test: the year's boundaries, the approximate hours, the ranked lists and
 * their tie-breaks, the streak, the busiest day, and the exclusions that keep a
 * one-tap "Mark as Watched" from inventing a year of viewing.
 */
class YearInReviewTest {

    private val zone = ZoneId.of("Europe/London")

    /** A fixed local timestamp, so every assertion reads as a wall clock. */
    private fun at(year: Int, month: Int, day: Int, hour: Int = 20, minute: Int = 0): Long =
        LocalDate.of(year, month, day)
            .atTime(hour, minute)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    private fun episode(
        id: String,
        parentId: String = "show-$id",
        name: String = "Show $id",
        season: Int = 1,
        episode: Int = 1,
        completedAt: Long? = null,
        durationMs: Long = 30 * 60_000L,
        type: String = "series",
        inProgress: Boolean = false,
        positionMs: Long = 0L,
        marked: Boolean = false
    ) = WatchHistoryEntity(
        id = id,
        parentId = parentId,
        type = type,
        name = name,
        poster = null,
        streamUrl = null,
        season = season,
        episode = episode,
        positionMs = if (inProgress) positionMs else 0L,
        durationMs = durationMs,
        updatedAt = completedAt ?: 0L,
        isCompleted = completedAt != null,
        completedAt = if (inProgress) null else completedAt,
        markedWatched = marked
    )

    @Test
    fun `the year is the viewer's own year, not the previous one`() {
        // Dec 31 23:30 local is this year; Jan 1 00:30 is the next. Both are the
        // same evening in UTC, which is exactly why the query is a range built
        // from the local zone rather than a strftime('%Y') on the column.
        val rows = listOf(
            episode("dec31", completedAt = at(2025, 12, 31, 23, 30)),
            episode("jan1", completedAt = at(2026, 1, 1, 0, 30))
        )
        val review = YearInReview.compute(2025, rows, emptyList(), zone)
        assertEquals(1, review.episodesFinished)
        assertEquals(30 * 60_000L, review.approxRuntimeMs)

        val next = YearInReview.compute(2026, rows, emptyList(), zone)
        assertEquals(1, next.episodesFinished)
    }

    @Test
    fun `hours are finished runtime plus the saved position of what was left mid-episode`() {
        // Two completed hours plus 15 minutes of an episode that is still open:
        // the approximate figure the screen labels, nothing more.
        val rows = listOf(
            episode("a", completedAt = at(2026, 3, 1), durationMs = 60 * 60_000L),
            episode("b", completedAt = at(2026, 3, 2), durationMs = 60 * 60_000L),
            episode(
                "c",
                inProgress = true,
                positionMs = 15 * 60_000L,
                durationMs = 45 * 60_000L
            ).copy(updatedAt = at(2026, 3, 3))
        )
        val review = YearInReview.compute(2026, rows, emptyList(), zone)
        assertEquals(135 * 60_000L, review.approxRuntimeMs)
    }

    @Test
    fun `a marked-watched row is a verdict, not a watch, and never counts`() {
        // The sentinel shape the marker writes: 1 ms duration, position 0. If
        // this counted, a bulk-marked season would report a hundred finishes
        // and a fabricated streak.
        val rows = listOf(
            episode("mark", completedAt = at(2026, 4, 1), durationMs = 1L, marked = true),
            episode("real", completedAt = at(2026, 4, 2))
        )
        val review = YearInReview.compute(2026, rows, emptyList(), zone)
        assertEquals(1, review.episodesFinished)
        assertEquals(30 * 60_000L, review.approxRuntimeMs)
        assertEquals(1, review.longestStreakDays)
    }

    @Test
    fun `an in-progress row with no position is not a watch`() {
        val rows = listOf(
            episode("open", inProgress = true, positionMs = 0L).copy(updatedAt = at(2026, 5, 1))
        )
        val review = YearInReview.compute(2026, rows, emptyList(), zone)
        assertEquals(0L, review.approxRuntimeMs)
        // It is still a row in the year, so the year is not presented as empty.
        assertFalse(review.empty)
    }

    @Test
    fun `movies and episodes are counted separately`() {
        val rows = listOf(
            episode("m1", parentId = "movie-1", type = "movie", completedAt = at(2026, 6, 1)),
            episode("m2", parentId = "movie-2", type = "movie", completedAt = at(2026, 6, 2)),
            episode("e1", completedAt = at(2026, 6, 3)),
            episode("e2", completedAt = at(2026, 6, 4))
        )
        val review = YearInReview.compute(2026, rows, emptyList(), zone)
        assertEquals(2, review.moviesFinished)
        assertEquals(2, review.episodesFinished)
    }

    @Test
    fun `a year with no rows at all is the honest empty state`() {
        val review = YearInReview.compute(2025, emptyList(), emptyList(), zone)
        assertTrue(review.empty)
        assertEquals(0L, review.approxRuntimeMs)
        assertEquals(0, review.episodesFinished)
        assertTrue(review.topShows.isEmpty())
        assertTrue(review.recentMovies.isEmpty())
        assertNull(review.busiestDay)
        assertEquals(0, review.longestStreakDays)
        assertNull(review.firstFinished)
        assertNull(review.lastFinished)
    }

    @Test
    fun `a year with history but no completions reports what it has`() {
        val rows = listOf(
            episode("open", inProgress = true, positionMs = 10 * 60_000L)
                .copy(updatedAt = at(2026, 7, 1))
        )
        val review = YearInReview.compute(2026, rows, emptyList(), zone)
        assertFalse(review.empty)
        assertEquals(10 * 60_000L, review.approxRuntimeMs)
        assertEquals(0, review.episodesFinished)
        assertEquals(0, review.moviesFinished)
        assertNull(review.firstFinished)
        assertNull(review.busiestDay)
    }

    @Test
    fun `top shows rank by episodes finished and break ties by the newest completion`() {
        val rows = listOf(
            episode("a1", parentId = "a", name = "Alpha", season = 1, episode = 1, completedAt = at(2026, 2, 1)),
            episode("a2", parentId = "a", name = "Alpha", season = 1, episode = 2, completedAt = at(2026, 2, 2)),
            episode("b1", parentId = "b", name = "Beta", season = 1, episode = 1, completedAt = at(2026, 3, 1)),
            episode("b2", parentId = "b", name = "Beta", season = 1, episode = 2, completedAt = at(2026, 3, 2)),
            episode("c1", parentId = "c", name = "Gamma", season = 1, episode = 1, completedAt = at(2026, 4, 1))
        )
        val review = YearInReview.compute(2026, rows, emptyList(), zone)
        assertEquals(listOf("Beta", "Alpha", "Gamma"), review.topShows.map { it.name })
        assertEquals(listOf(2, 2, 1), review.topShows.map { it.episodes })
    }

    @Test
    fun `top shows stop at five`() {
        val rows = (1..8).map { index ->
            episode(
                "e$index",
                parentId = "show$index",
                name = "Show $index",
                completedAt = at(2026, 1, index)
            )
        }
        val review = YearInReview.compute(2026, rows, emptyList(), zone)
        assertEquals(YearInReview.TOP_TITLES, review.topShows.size)
        // Newest completion first among equal counts.
        assertEquals("Show 8", review.topShows.first().name)
    }

    @Test
    fun `movies are listed by most recent completion, and say so`() {
        // A movie completes once, so there is no count to rank by - the order is
        // recency, which is what the screen's header promises.
        val rows = listOf(
            episode("m1", parentId = "m1", name = "Older", type = "movie", completedAt = at(2026, 5, 1)),
            episode("m2", parentId = "m2", name = "Newest", type = "movie", completedAt = at(2026, 9, 1)),
            episode("m3", parentId = "m3", name = "Middle", type = "movie", completedAt = at(2026, 7, 1))
        )
        val review = YearInReview.compute(2026, rows, emptyList(), zone)
        assertEquals(listOf("Newest", "Middle", "Older"), review.recentMovies.map { it.name })
    }

    @Test
    fun `the busiest day is the most completed runtime, and a tie goes to the later day`() {
        val rows = listOf(
            episode("a", completedAt = at(2026, 8, 1), durationMs = 60 * 60_000L),
            episode("b", completedAt = at(2026, 8, 2), durationMs = 50 * 60_000L),
            episode("c", completedAt = at(2026, 8, 2, 22), durationMs = 50 * 60_000L),
            episode("d", completedAt = at(2026, 8, 3), durationMs = 100 * 60_000L)
        )
        val review = YearInReview.compute(2026, rows, emptyList(), zone)
        assertEquals(LocalDate.of(2026, 8, 3), review.busiestDay?.date)
        assertEquals(100 * 60_000L, review.busiestDay?.runtimeMs)
    }

    @Test
    fun `the busiest day ignores a title with no runtime`() {
        val rows = listOf(
            episode("a", completedAt = at(2026, 8, 1), durationMs = 0L),
            episode("b", completedAt = at(2026, 8, 2), durationMs = 30 * 60_000L)
        )
        val review = YearInReview.compute(2026, rows, emptyList(), zone)
        assertEquals(LocalDate.of(2026, 8, 2), review.busiestDay?.date)
    }

    @Test
    fun `the streak counts consecutive days with a completion`() {
        val days = listOf(
            LocalDate.of(2026, 1, 1),
            LocalDate.of(2026, 1, 2),
            LocalDate.of(2026, 1, 3),
            LocalDate.of(2026, 1, 6),
            LocalDate.of(2026, 1, 7)
        )
        val rows = days.mapIndexed { index, day ->
            episode(
                "e$index",
                completedAt = day.atTime(21, 0).atZone(zone).toInstant().toEpochMilli()
            )
        }
        val review = YearInReview.compute(2026, rows, emptyList(), zone)
        assertEquals(3, review.longestStreakDays)
    }

    @Test
    fun `a streak counts a day once, however many episodes that day held`() {
        val rows = listOf(
            episode("a", completedAt = at(2026, 2, 1, 10)),
            episode("b", completedAt = at(2026, 2, 1, 22)),
            episode("c", completedAt = at(2026, 2, 2, 10))
        )
        val review = YearInReview.compute(2026, rows, emptyList(), zone)
        assertEquals(2, review.longestStreakDays)
    }

    @Test
    fun `first and last finished name the year's endpoints`() {
        val rows = listOf(
            episode("a", name = "Winter", completedAt = at(2026, 1, 5)),
            episode("b", name = "Spring", completedAt = at(2026, 4, 5)),
            episode("c", name = "Autumn", completedAt = at(2026, 10, 5))
        )
        val review = YearInReview.compute(2026, rows, emptyList(), zone)
        assertEquals("Winter", review.firstFinished?.name)
        assertEquals("Autumn", review.lastFinished?.name)
        assertEquals(at(2026, 10, 5), review.lastFinished?.lastAtMs)
    }

    @Test
    fun `a show counts as finished when its latest episode was completed that year`() {
        val stamps = listOf(
            EpisodeStamp("show-a", 1, 1, at(2025, 11, 1)),
            EpisodeStamp("show-a", 1, 2, at(2026, 2, 1)),
            EpisodeStamp("show-b", 1, 10, at(2025, 12, 20)),
            EpisodeStamp("show-c", 2, 1, at(2026, 3, 1))
        )
        // 2026: show-a's latest (S1E2) and show-c's (S2E1) both landed in it.
        assertEquals(2, YearInReview.showsFinishedIn(2026, stamps, zone))
        // 2025: only show-b, whose latest episode was finished that December -
        // show-a's latest moved on into 2026.
        assertEquals(1, YearInReview.showsFinishedIn(2025, stamps, zone))
    }

    @Test
    fun `a rewatch of an early episode does not claim a finished show`() {
        // The finale was finished in 2025 and episode one was replayed in 2026:
        // the show's latest episode is still the 2025 one, so 2026 owes it
        // nothing.
        val stamps = listOf(
            EpisodeStamp("show-a", 1, 1, at(2026, 6, 1)),
            EpisodeStamp("show-a", 1, 10, at(2025, 12, 20))
        )
        assertEquals(0, YearInReview.showsFinishedIn(2026, stamps, zone))
        assertEquals(1, YearInReview.showsFinishedIn(2025, stamps, zone))
    }

    @Test
    fun `the longest run helper handles no days, one day and unsorted input`() {
        assertEquals(0, YearInReview.longestRun(emptyList()))
        assertEquals(1, YearInReview.longestRun(listOf(10L)))
        // 1..5 is one run of five, however the days arrive.
        assertEquals(5, YearInReview.longestRun(listOf(5L, 1L, 2L, 3L, 4L, 9L)))
        // Duplicates are a day, not two.
        assertEquals(2, YearInReview.longestRun(listOf(1L, 1L, 2L)))
    }

    @Test
    fun `the year picker offers the years with history plus this one, newest first`() {
        val times = listOf(
            at(2024, 6, 1),
            at(2024, 7, 1),
            at(2026, 1, 2)
        )
        assertEquals(listOf(2026, 2024, 2023), YearInReview.yearsWithHistory(times, 2023, zone))
    }

    @Test
    fun `the year query bounds are local midnights`() {
        val start = YearInReview.yearStartMs(2026, zone)
        val end = YearInReview.yearStartMs(2027, zone)
        assertEquals(at(2026, 1, 1, 0, 0), start)
        assertEquals(at(2027, 1, 1, 0, 0), end)
        assertTrue(start < end)
    }

    @Test
    fun `the home card is offered in December and January only`() {
        assertTrue(YearInReviewCard.inSeason(LocalDate.of(2026, 12, 1)))
        assertTrue(YearInReviewCard.inSeason(LocalDate.of(2026, 12, 31)))
        assertTrue(YearInReviewCard.inSeason(LocalDate.of(2027, 1, 1)))
        assertTrue(YearInReviewCard.inSeason(LocalDate.of(2027, 1, 31)))
        assertFalse(YearInReviewCard.inSeason(LocalDate.of(2027, 2, 1)))
        assertFalse(YearInReviewCard.inSeason(LocalDate.of(2026, 11, 30)))
        assertFalse(YearInReviewCard.inSeason(LocalDate.of(2026, 6, 1)))
    }

    @Test
    fun `the home card names the year that ended in January`() {
        assertEquals(2026, YearInReviewCard.yearFor(LocalDate.of(2026, 12, 14)))
        assertEquals(2026, YearInReviewCard.yearFor(LocalDate.of(2027, 1, 5)))
        assertEquals("Your 2026 in review", YearInReviewCard.title(2026))
    }
}
