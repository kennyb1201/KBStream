package com.kennyb1201.kbstream.ui.detail

import java.io.File
import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Detail screen's episode card marks an episode that has not been released
 * yet with an UNAVAILABLE badge in its top-right corner, and refuses to play
 * it. That verdict reads [com.kennyb1201.kbstream.data.tmdb.ResolvedEpisode.airDate],
 * which arrives in one of two shapes: TMDB's plain `yyyy-MM-dd`, and - for a
 * title TMDB has no record for, whose seasons are synthesized from the add-on's
 * own `Meta.videos` - Stremio's full ISO-8601 timestamp
 * (`2026-11-01T00:00:00.000Z`).
 *
 * The timestamp shape used to read as "aired", because the check parsed only
 * `yyyy-MM-dd`: an unreleased add-on episode showed no badge and its card was
 * still clickable, so playing it resolved streams for an episode that does not
 * exist. These tests pin the parse (both shapes) and the card's own badge and
 * guards, so neither half can regress silently.
 */
class EpisodeUnavailableBadgeContractTest {

    private val today = LocalDate.of(2026, 10, 8)

    // ── the rule, for both date shapes ──────────────────────────────────

    @Test
    fun `a plain TMDB date in the future is unavailable`() {
        assertTrue(isEpisodeUnavailable("2026-11-01", today))
    }

    @Test
    fun `a plain TMDB date in the past is available`() {
        assertFalse(isEpisodeUnavailable("2026-09-30", today))
    }

    @Test
    fun `the air date itself is not future enough to be unavailable`() {
        // TMDB dates are a calendar day; an episode airing today may still be
        // hours away, but this path has no time of day to test, so it stays
        // offered rather than blocked for the whole day.
        assertFalse(isEpisodeUnavailable("2026-10-08", today))
    }

    @Test
    fun `a missing or unreadable date is available`() {
        // Failing open: TMDB does not carry a date for every aired episode, and
        // a gap must never read as "not out yet".
        assertFalse(isEpisodeUnavailable(null, today))
        assertFalse(isEpisodeUnavailable("", today))
        assertFalse(isEpisodeUnavailable("   ", today))
        assertFalse(isEpisodeUnavailable("not-a-date", today))
    }

    @Test
    fun `an add-on ISO timestamp in the future is unavailable`() {
        // The shape Stremio's Meta.videos[].released carries, stored verbatim
        // in ResolvedEpisode.airDate by the synthetic episode list.
        assertTrue(isEpisodeUnavailable("2026-11-01T00:00:00.000Z", today))
        assertTrue(isEpisodeUnavailable("2026-11-01T20:30:00+00:00", today))
        // A timestamp with no offset at all is still a readable day.
        assertTrue(isEpisodeUnavailable("2026-11-01T00:00:00", today))
    }

    @Test
    fun `an add-on ISO timestamp in the past is available`() {
        assertFalse(isEpisodeUnavailable("2026-09-30T00:00:00.000Z", today))
        assertFalse(isEpisodeUnavailable("2026-09-30T21:00:00", today))
    }

    // ── the card that draws the verdict ─────────────────────────────────

    @Test
    fun `the badge is drawn in the card's top-right corner`() {
        val card = episodeCardSlice()
        assertTrue(
            "the card must say UNAVAILABLE",
            card.contains("\"UNAVAILABLE\"")
        )
        assertTrue(
            "and place it at the top-right",
            card.contains(".align(Alignment.TopEnd)")
        )
    }

    @Test
    fun `an unavailable episode is not playable`() {
        val card = episodeCardSlice()
        assertTrue(
            "a click on an unreleased episode must not resolve streams",
            card.contains("onClick = { if (!isUnavailable) onClick() }")
        )
        assertTrue(
            "nor may its long-press menu offer mark-watched",
            card.contains("onLongClick = if (isUnavailable) null else onLongClick")
        )
    }

    @Test
    fun `the verdict comes from the air date and the trusted-date flag`() {
        val card = episodeCardSlice()
        assertTrue(
            "an episode is unavailable only when its season's dates are trusted",
            card.contains("airDatesTrusted && isEpisodeUnavailable(ep.airDate)")
        )
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun episodeCardSlice(): String {
        val src = source()
        val start = src.indexOf("private fun EpisodeCard(")
        assertTrue("EpisodeCard missing", start >= 0)
        val end = src.indexOf("private fun GenreChip(", start)
        assertTrue("the slice end marker is missing", end > start)
        return src.substring(start, end)
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
