package com.kennyb1201.kbstream.ui.player

import com.kennyb1201.kbstream.data.tmdb.ResolvedEpisode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        airDate: String?,
        season: Int = 2
    ): ResolvedEpisode = ResolvedEpisode(
        streamId = "tt1000000:$season:$number",
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

    // ── the season boundary ────────────────────────────────────────────────
    //
    // An arithmetic next episode ABSENT from its own season's listing is a
    // finale, whichever launch route it came from - and a finale whose next
    // season has aired must chain into it rather than fall through to the
    // recommendations. The reported case: Animal Control's S4 finale showed
    // Because-you-watched while S5E1/E2 were already out, from the routes
    // that never carried the season's episode count.

    /** A season that aired in full, 10 episodes. */
    private fun airedSeason(season: Int, count: Int = 10): List<ResolvedEpisode> =
        (1..count).map { episode(it, "2026-08-10", season = season) }

    /** S5's first two episodes, both aired before [today]. */
    private val airedSeasonFive = listOf(
        episode(1, "2026-09-14", season = 5),
        episode(2, "2026-09-21", season = 5)
    )

    @Test
    fun `a finale chains into the next season when its opener has aired`() {
        assertEquals(
            "(4, 11) is not in S4 at all, so the S4 finale chains into S5E1",
            5 to 1,
            airedChainTarget(4 to 11, airedSeason(4), airedSeasonFive, today)
        )
    }

    @Test
    fun `a finale does not chain into a season that has not been listed`() {
        assertNull(
            "nothing of S5 exists to play, so this stays a finished-series finale",
            airedChainTarget(4 to 11, airedSeason(4), emptyList(), today)
        )
    }

    @Test
    fun `a listed but unaired next episode is never jumped over`() {
        // The invariant the fallback must not break: S4E5 exists but airs in a
        // fortnight. The answer is recommendations - NOT S5E1, even though S5
        // is listed and aired. "Not out yet" is not "the season is over".
        val s4 = airedSeason(4, count = 4) + episode(5, "2026-10-05", season = 4)
        assertNull(
            "S4E5 is unaired, so nothing chains",
            airedChainTarget(4 to 5, s4, airedSeasonFive, today)
        )
    }

    @Test
    fun `a failed season lookup keeps the arithmetic target`() {
        // Unknown != unaired: a transport hiccup must not lose the panel.
        assertEquals(
            "a failed listing is not evidence the episode is unaired",
            4 to 11,
            airedChainTarget(4 to 11, null, airedSeasonFive, today)
        )
        assertEquals(
            "...even when the next-season lookup failed too",
            4 to 11,
            airedChainTarget(4 to 11, null, null, today)
        )
    }

    @Test
    fun `a failed next-season lookup answers null like the unlisted case`() {
        // Before the fallback existed, an unlisted target always answered null.
        // A lookup that fails at that same boundary must not invent a chain.
        assertNull(
            "no new failure mode: an unlisted target with no S+1 answer stays null",
            airedChainTarget(4 to 11, airedSeason(4), null, today)
        )
    }

    @Test
    fun `a mid-season chain is untouched by the fallback`() {
        // Listed and aired: the target itself, byte-identical to the rule the
        // gate had before the season boundary was folded in.
        assertEquals(
            4 to 5,
            airedChainTarget(4 to 5, airedSeason(4), null, today)
        )
        assertNull(
            "and a listed episode airing later still answers null",
            airedChainTarget(4 to 5, airedSeason(4, count = 4) + episode(5, "2026-09-28", season = 4), null, today)
        )
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
