package com.kennyb1201.kbstream.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

/**
 * The chip on a rail card, for all three card sources at once.
 *
 * Reported as part of the non-Simkl gaps: the Simkl builder read the arrival
 * window, the local builder handled only the season-PREMIERE case, and the
 * MDBList builder hardcoded "continue watching". So a viewer without a Simkl
 * account never saw a "New Episode" chip, and a viewer whose tracker was
 * MDBList saw no arrival chip anywhere, however recently the episode had aired.
 *
 * [upNextArrivalBadge] is now the single answer, and [upNextBadgePrefix] is the
 * line under the title that has to agree with it.
 */
class UpNextArrivalBadgeTest {

    private fun daysAgo(days: Long): String =
        LocalDate.now().minusDays(days).toString()

    // ── the resume wins ────────────────────────────────────────────────

    @Test
    fun `a card with progress to resume is a resume, whatever aired`() {
        // The viewer is mid-episode: the arrival chip belongs on the card for
        // the episode they have not started, not on this one.
        assertEquals(
            UpNextBadge.CONTINUE_WATCHING,
            upNextArrivalBadge(
                isResume = true,
                airDate = daysAgo(0),
                episode = 1
            )
        )
    }

    // ── the arrival ─────────────────────────────────────────────────────

    @Test
    fun `a premiere that aired inside the window is a new season`() {
        assertEquals(
            UpNextBadge.NEW_SEASON,
            upNextArrivalBadge(
                isResume = false,
                airDate = daysAgo(0),
                episode = 1
            )
        )

        assertEquals(
            UpNextBadge.NEW_SEASON,
            upNextArrivalBadge(
                isResume = false,
                airDate = daysAgo(7),
                episode = 1
            )
        )
    }

    @Test
    fun `a mid-season episode that aired inside the window is a new episode`() {
        assertEquals(
            UpNextBadge.NEW_EPISODE,
            upNextArrivalBadge(
                isResume = false,
                airDate = daysAgo(2),
                episode = 5
            )
        )
    }

    @Test
    fun `an episode with no number is an episode, not a premiere`() {
        // The tracker could not place it, so E01 must not be invented - the
        // same rule the label follows.
        assertEquals(
            UpNextBadge.NEW_EPISODE,
            upNextArrivalBadge(
                isResume = false,
                airDate = daysAgo(1),
                episode = null
            )
        )
    }

    // ── the quiet cases ─────────────────────────────────────────────────

    @Test
    fun `an episode older than the window is just up next`() {
        assertEquals(
            UpNextBadge.NEXT_UP,
            upNextArrivalBadge(
                isResume = false,
                airDate = daysAgo(8),
                episode = 5
            )
        )
    }

    @Test
    fun `an episode that has not aired yet is not news`() {
        // A future date is not an arrival - it is the Upcoming rail's story.
        assertEquals(
            UpNextBadge.NEXT_UP,
            upNextArrivalBadge(
                isResume = false,
                airDate = LocalDate.now().plusDays(2).toString(),
                episode = 5
            )
        )
    }

    @Test
    fun `an unknown or unusable air date is not news`() {
        assertEquals(
            UpNextBadge.NEXT_UP,
            upNextArrivalBadge(isResume = false, airDate = null, episode = 5)
        )
        assertEquals(
            UpNextBadge.NEXT_UP,
            upNextArrivalBadge(isResume = false, airDate = "  ", episode = 5)
        )
        assertEquals(
            UpNextBadge.NEXT_UP,
            upNextArrivalBadge(isResume = false, airDate = "not-a-date", episode = 5)
        )
    }

    // ── the subtitle contract ───────────────────────────────────────────

    @Test
    fun `every badge has an action word of its own`() {
        assertEquals("Resume", upNextBadgePrefix(UpNextBadge.CONTINUE_WATCHING))
        assertEquals("New Season", upNextBadgePrefix(UpNextBadge.NEW_SEASON))
        assertEquals("New Episode", upNextBadgePrefix(UpNextBadge.NEW_EPISODE))
        assertEquals("Up Next", upNextBadgePrefix(UpNextBadge.NEXT_UP))
    }

    @Test
    fun `the action word and the pair read as one line`() {
        // The card prints these together, so the pair pins the shape the rail
        // renders (and the MDBList card's old "NEW SEASON over Resume - S2E1"
        // is what this stops).
        assertEquals(
            "New Episode - S2E5",
            upNextTrackerSubtitle(
                prefix = upNextBadgePrefix(UpNextBadge.NEW_EPISODE),
                season = 2,
                episode = 5,
                fallback = "Paused 40%"
            )
        )
    }
}
