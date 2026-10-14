package com.kennyb1201.kbstream.ui.detail

import com.kennyb1201.kbstream.data.tmdb.ResolvedEpisode
import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The season total the Detail screen builds its episode counts from.
 *
 * Reported 2026-10-14 (R.J. Decker): a show with two scheduled episodes left
 * read "14 of 16 aired episodes watched" and stayed in Continue Watching after
 * every AIRED episode was watched. The list of episodes the screen holds
 * deliberately INCLUDES unaired ones (their cards are drawn greyed, and the
 * Upcoming rail reads the same dates), so the list's own size is 16 while only
 * 14 episodes exist to watch - and that size is not just a caption: it is what
 * a resume row stores as its season total, so it also kept the show looking
 * unfinished.
 *
 * [airedEpisodeCount] is the one place that total is derived, so this pins the
 * rule (both date shapes, the today boundary, the missing-date direction and
 * the untrusted-date escape) and then pins the screen to it, because a caller
 * that quietly went back to `episodes.size` would restore the reported count
 * with every rule test still green.
 */
class AiredEpisodeCountTest {

    private val today = LocalDate.of(2026, 10, 14)

    // ── the rule ─────────────────────────────────────────────────────────

    @Test
    fun `unaired episodes are not part of the season total`() {
        val episodes = listOf(
            episode(1, "2026-09-01"),
            episode(2, "2026-09-08"),
            episode(3, "2026-10-01"),
            episode(4, "2026-10-08"),
            episode(5, "2026-10-21"),
            episode(6, "2026-10-28")
        )
        assertEquals(
            "the two scheduled episodes must not be counted",
            4,
            airedEpisodeCount(episodes, today = today)
        )
    }

    @Test
    fun `the date itself counts as aired`() {
        // TMDB dates are a calendar day with no time of day. An episode dated
        // today is offered by this screen rather than blocked, so the total has
        // to agree with the badge and count it.
        assertEquals(
            1,
            airedEpisodeCount(listOf(episode(1, "2026-10-14")), today = today)
        )
    }

    @Test
    fun `a missing or unreadable date is counted`() {
        // The rule is one-sided on purpose: an episode is only ever removed
        // when a date PROVES it has not aired, so a gap in TMDB's data cannot
        // hide an episode the viewer could have watched.
        val episodes = listOf(
            episode(1, null),
            episode(2, ""),
            episode(3, "   "),
            episode(4, "not-a-date")
        )
        assertEquals(4, airedEpisodeCount(episodes, today = today))
    }

    @Test
    fun `both stored air-date shapes are understood`() {
        // TMDB's plain calendar day, and the full ISO-8601 timestamp the
        // synthetic (add-on) episode list stores verbatim.
        val episodes = listOf(
            episode(1, "2026-09-30"),
            episode(2, "2026-09-30T21:00:00.000Z"),
            episode(3, "2026-11-01T00:00:00.000Z"),
            episode(4, "2026-12-01")
        )
        assertEquals(2, airedEpisodeCount(episodes, today = today))
    }

    @Test
    fun `untrusted dates leave the list alone instead of trusting the future date`() {
        // A season whose dates are contradicted elsewhere means the ROW is
        // stale, not the episode unaired - the season mark/unmark path makes
        // the same call, so the total must not silently shrink on that data.
        val episodes = listOf(
            episode(1, "2026-09-01"),
            episode(2, "2026-12-24")
        )
        assertEquals(
            "with the dates contradicted, the raw list stands",
            2,
            airedEpisodeCount(episodes, airDatesTrusted = false, today = today)
        )
    }

    @Test
    fun `an all-unaired season counts zero rather than the scheduled number`() {
        val episodes = listOf(
            episode(1, "2027-01-05"),
            episode(2, "2027-01-12")
        )
        assertEquals(0, airedEpisodeCount(episodes, today = today))
    }

    @Test
    fun `an empty season counts zero`() {
        assertEquals(0, airedEpisodeCount(emptyList(), today = today))
    }

    // ── the screen that must use it ──────────────────────────────────────

    @Test
    fun `every season total on the detail screen comes from the aired count`() {
        val src = source()
        assertFalse(
            "a total built from the raw episode list is the reported bug: " +
                "it counted the scheduled episodes",
            src.contains("totalEpisodesInSeason = episodes.size")
        )
        assertFalse(
            "nor may the long-press menu's number list be used as the total: it " +
                "carries the unaired episodes on purpose, because marking the " +
                "season watched must still reach them",
            src.contains("seasonEpisodeNumbers\n                                                    .size,")
        )
        assertEquals(
            "all four play paths (the Play button, an episode card, and the two " +
                "long-press menu entries) must hand the player the aired total",
            4,
            count(src, "totalEpisodesInSeason = airedEpisodesInSeason")
        )
    }

    @Test
    fun `the aired total is one value, so a caption and a stored row cannot disagree`() {
        val src = source()
        assertTrue(
            "the count must go through airedEpisodeCount",
            src.contains("airedEpisodeCount(\n            episodes = episodes,")
        )
        assertEquals(
            "defined once and used once, by that value",
            1,
            count(src, "val airedEpisodesInSeason = remember(")
        )
        assertTrue(
            "and it must take the stale-dates guard the card uses",
            src.contains("effectiveSeason == null || effectiveSeason !in seasonsWithStaleDates")
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun episode(
        number: Int,
        airDate: String?
    ) = ResolvedEpisode(
        streamId = "tt100:1:$number",
        episodeNumber = number,
        name = "Episode $number",
        overview = null,
        thumbnail = null,
        runtimeMinutes = 42,
        airDate = airDate,
        voteAverage = null
    )

    private fun count(
        text: String,
        needle: String
    ): Int {
        var from = 0
        var n = 0
        while (true) {
            val at = text.indexOf(needle, from)
            if (at < 0) return n
            n++
            from = at + needle.length
        }
    }

    private fun source(): String {
        val file = File(findSourceRoot(), SCREEN)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun findSourceRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private companion object {
        const val SCREEN = "com/kennyb1201/kbstream/ui/detail/DetailScreen.kt"
    }
}
