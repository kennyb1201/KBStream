package com.kennyb1201.kbstream.ui.home

import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The proof that keeps a caught-up show's Upcoming card (spec 2026-11-12, F1).
 *
 * Reported on build 253: MobLand, Tulsa King, Eternally Yours and The
 * Unbelievable with Dan Aykroyd had a next episode airing the following day and
 * were missing from Upcoming entirely. Finishing the last aired episode removes
 * the local resume row, so the caught-up card from the tracker leg is the only
 * route to that rail - and `trackerCardJustCompletedByProfile` dropped it,
 * because a card naming no episode was treated as the episode just finished.
 *
 * The exemption is an EPISODE-level fact, not a show-level one: an episode that
 * has not aired cannot be the one completed. That is all this proves, and it
 * has to stay that narrow - `nextEpisodeAir` is the show's next airing, so a
 * card for the episode just finished carries the same field, and a wider rule
 * would put that stale card back on Continue Watching.
 */
class UpcomingUnairedEpisodeProofTest {

    private val today: LocalDate = LocalDate.of(2026, 11, 13)

    @Test
    fun `the day before a next episode airs, its card is proven unaired`() {
        // The reported case: caught up on S2E8, S2E9 tomorrow.
        assertTrue(
            episodeProvablyUnaired(
                cardSeason = 2,
                cardEpisode = 9,
                airSeason = 2,
                airEpisode = 9,
                airDate = "2026-11-13",
                today = today
            )
        )
    }

    @Test
    fun `airing today still counts as unaired`() {
        // The schedule builder keeps a TODAY air date on purpose (an episode
        // later today has not aired yet), so the proof agrees with it.
        assertTrue(
            episodeProvablyUnaired(2, 9, 2, 9, "2026-11-13", today)
        )
    }

    @Test
    fun `a card pointing at the episode just finished is not exempt`() {
        // S2E8 was watched; the air record names S2E9 (the SHOW's next
        // airing). Nothing here proves the card is unaired - it names an
        // episode that has aired - so the just-completed rule still drops it.
        assertFalse(
            episodeProvablyUnaired(
                cardSeason = 2,
                cardEpisode = 8,
                airSeason = 2,
                airEpisode = 9,
                airDate = "2026-11-13",
                today = today
            )
        )
    }

    @Test
    fun `a past air date is not a proof`() {
        assertFalse(
            episodeProvablyUnaired(2, 9, 2, 9, "2026-11-06", today)
        )
    }

    @Test
    fun `a card with no episode identity cannot be proven either way`() {
        // The shape the spec names as today's caught-up card: no pair at all.
        // The show-level air info says nothing about an unnamed target, so the
        // Upcoming call site is what exempts this card (see the contract test).
        assertFalse(episodeProvablyUnaired(null, null, 2, 9, "2026-11-13", today))
        assertFalse(episodeProvablyUnaired(2, null, 2, 9, "2026-11-13", today))
        assertFalse(episodeProvablyUnaired(null, 9, 2, 9, "2026-11-13", today))
    }

    @Test
    fun `a missing or unreadable date is not a proof`() {
        assertFalse(episodeProvablyUnaired(2, 9, 2, 9, null, today))
        assertFalse(episodeProvablyUnaired(2, 9, 2, 9, "", today))
        assertFalse(episodeProvablyUnaired(2, 9, 2, 9, "next friday", today))
    }

    @Test
    fun `a zero season or episode is not an identity`() {
        // Tracker rows use 0 for "unset"; it must not read as S0E0.
        assertFalse(episodeProvablyUnaired(0, 0, 0, 0, "2026-11-13", today))
    }
}
