package com.kennyb1201.kbstream.ui.player

import com.kennyb1201.kbstream.data.tmdb.ResolvedEpisode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The air-date rule behind the end-of-playback chain: watching S2E1 while
 * S2E2 is still unaired must NOT offer S2E2 in the Up next panel (which then
 * fails to resolve a stream); it has to fall through to the recommendations
 * row like a finished finale does.
 */
class NextEpisodeAiringTest {

    private val today = LocalDate.of(2026, 9, 21)

    private fun episode(
        number: Int,
        airDate: String?
    ): ResolvedEpisode = ResolvedEpisode(
        streamId = "tt1000000:2:$number",
        episodeNumber = number,
        name = "Episode $number",
        overview = null,
        thumbnail = null,
        runtimeMinutes = 45,
        airDate = airDate,
        voteAverage = null
    )

    @Test
    fun `unaired next episode is not chainable`() {
        val season = listOf(
            episode(1, "2026-09-14"),
            episode(2, "2026-09-28")
        )

        assertTrue("the aired episode we watched", isNextEpisodeOut(season, 1, today))
        assertFalse("E2 airs in a week", isNextEpisodeOut(season, 2, today))
    }

    @Test
    fun `an episode that airs today is chainable`() {
        val season = listOf(episode(2, "2026-09-21"))

        assertTrue(isNextEpisodeOut(season, 2, today))
    }

    @Test
    fun `a missing air date counts as aired`() {
        // TMDB leaves the date off plenty of already-aired episodes; treating
        // that as "not out yet" would dead-end chains that work today.
        val season = listOf(episode(2, null), episode(3, ""), episode(4, "   "), episode(5, "not-a-date"))

        assertTrue(isNextEpisodeOut(season, 2, today))
        assertTrue(isNextEpisodeOut(season, 3, today))
        assertTrue(isNextEpisodeOut(season, 4, today))
        assertTrue(isNextEpisodeOut(season, 5, today))
    }

    @Test
    fun `a season that does not list the episode is not chainable`() {
        // The last aired episode of a season chains into "(s + 1) episode 1".
        // If that season has aired, TMDB lists it; if it has not been listed
        // yet, there is nothing to play.
        val nextSeasonAired = listOf(episode(1, "2026-09-10"))
        assertTrue(isNextEpisodeOut(nextSeasonAired, 1, today))

        val nextSeasonNotListed = emptyList<ResolvedEpisode>()
        assertFalse(isNextEpisodeOut(nextSeasonNotListed, 1, today))

        val seasonMissingTheEpisode = listOf(episode(1, "2026-09-10"), episode(2, "2026-09-17"))
        assertFalse(isNextEpisodeOut(seasonMissingTheEpisode, 9, today))
    }

    @Test
    fun `air date parsing is lenient about whitespace`() {
        assertFalse("a padded past date is aired", isUnaired(" 2026-09-14 ", today))
        assertTrue("a padded future date is still unaired", isUnaired(" 2026-09-28 ", today))
        assertFalse("an unparseable date is aired", isUnaired("not-a-date", today))
        assertFalse("today is out", isUnaired("2026-09-21", today))
        assertFalse(isUnaired(null, today))
    }

    @Test
    fun `the reported scenario end to end`() {
        // Watched S2E1, S2E2 is not out yet: the player must not chain.
        val seasonTwo = listOf(episode(1, "2026-09-14"), episode(2, "2026-10-05"))
        assertFalse(
            "S2E2 is unaired, so the panel should fall back to Because you watched",
            isNextEpisodeOut(seasonTwo, 2, today)
        )

        // A week later the same episode is out and chaining works again.
        val later = LocalDate.of(2026, 10, 6)
        assertTrue(isNextEpisodeOut(seasonTwo, 2, later))
    }
}
