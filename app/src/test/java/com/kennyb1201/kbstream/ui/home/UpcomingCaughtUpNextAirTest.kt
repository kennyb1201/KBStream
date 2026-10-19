package com.kennyb1201.kbstream.ui.home

import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stale-`next_episode_to_air` guard on the Upcoming rail's caught-up cards.
 *
 * Reported: after finishing Tulsa King S04E01 - the last episode that had aired -
 * the rail showed a "Next up" card for Tulsa King, S04E01, with no episode
 * title. The cached series detail had been fetched while S04E01 was still
 * unaired, so TMDB's `next_episode_to_air` named it; the viewer was caught up on
 * everything aired, so the card was built and shown. On a later launch the
 * detail cache had rolled the field over to null and the card was gone.
 *
 * What is pinned here is the two-sided guard, with `today` injected so the
 * boundary needs no clock: an episode already in the viewer's watched set is not
 * next no matter what the date says, a date in the PAST is not next, and
 * anything the guard cannot prove - no date at all - keeps today's behavior,
 * because an unknown date must never be a reason to hide a card.
 */
class UpcomingCaughtUpNextAirTest {

    private val today = LocalDate.of(2026, 10, 19)

    @Test
    fun `an episode already watched is not next`() {
        // The reported card: TMDB still names S04E01 as next, and this viewer
        // finished it moments ago. The watched set is the airtight half - it
        // does not care that the air date field is stale.
        assertFalse(
            nextAirStillUpcoming(
                seasonNumber = 4,
                episodeNumber = 1,
                airDate = null,
                watchedEpisodes = setOf(3 to 12, 4 to 1),
                today = today
            )
        )
    }

    @Test
    fun `an air date in the past is not next`() {
        // The half the watched set cannot do: the episode aired, but nothing
        // here recorded it watched. Yesterday proves it is behind us.
        assertFalse(
            nextAirStillUpcoming(
                seasonNumber = 4,
                episodeNumber = 2,
                airDate = today.minusDays(1).toString(),
                watchedEpisodes = emptySet(),
                today = today
            )
        )
    }

    @Test
    fun `an air date today is still next`() {
        // TMDB's episode dates are a calendar day with no time of day, so an
        // episode airing today may be hours away - and on that day the card is
        // the one the viewer wants. Today is kept; only a past date condemns.
        // This is the same side of the boundary as isAiredOrUnknown, and it
        // agrees with the schedule builder, which counts today as to come.
        assertTrue(
            nextAirStillUpcoming(
                seasonNumber = 4,
                episodeNumber = 2,
                airDate = today.toString(),
                watchedEpisodes = emptySet(),
                today = today
            )
        )
    }

    @Test
    fun `an episode that aired today and was watched today is not next`() {
        // The watched half still wins on air day: finishing an episode the
        // evening it drops must not leave a card for it in the morning.
        assertFalse(
            nextAirStillUpcoming(
                seasonNumber = 4,
                episodeNumber = 2,
                airDate = today.toString(),
                watchedEpisodes = setOf(4 to 2),
                today = today
            )
        )
    }

    @Test
    fun `a genuinely upcoming episode still cards`() {
        assertTrue(
            nextAirStillUpcoming(
                seasonNumber = 4,
                episodeNumber = 2,
                airDate = today.plusDays(1).toString(),
                watchedEpisodes = setOf(4 to 1),
                today = today
            )
        )
    }

    @Test
    fun `a null air date fails open`() {
        // The kdoc's schedule-builder authority, kept: a date this guard cannot
        // read is not evidence the episode has aired.
        assertTrue(
            nextAirStillUpcoming(
                seasonNumber = 4,
                episodeNumber = 2,
                airDate = null,
                watchedEpisodes = setOf(4 to 1),
                today = today
            )
        )
    }

    @Test
    fun `a blank or unparseable air date fails open`() {
        listOf("", "   ", "TBA", "2026-10-19T21:00:00Z").forEach { raw ->
            assertTrue(
                "kept for '$raw'",
                nextAirStillUpcoming(
                    seasonNumber = 4,
                    episodeNumber = 2,
                    airDate = raw,
                    watchedEpisodes = emptySet(),
                    today = today
                )
            )
        }
    }

    @Test
    fun `an episode with no numbering is judged by its date alone`() {
        // A detail whose next episode carries no season/episode pair cannot be
        // matched against the watched set; only the date can condemn it.
        assertFalse(
            nextAirStillUpcoming(
                seasonNumber = null,
                episodeNumber = null,
                airDate = today.minusDays(3).toString(),
                watchedEpisodes = setOf(4 to 1),
                today = today
            )
        )
        assertTrue(
            nextAirStillUpcoming(
                seasonNumber = null,
                episodeNumber = null,
                airDate = today.plusDays(3).toString(),
                watchedEpisodes = setOf(4 to 1),
                today = today
            )
        )
    }
}
