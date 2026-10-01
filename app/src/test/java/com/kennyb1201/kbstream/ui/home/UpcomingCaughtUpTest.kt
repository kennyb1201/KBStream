package com.kennyb1201.kbstream.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who gets to advertise a next unaired episode.
 *
 * Reported: "stuff I'm not caught up to is showing in the Upcoming because it
 * has new aired episodes out. I like the new season notifications though so keep
 * those, but only shows I'm caught up to should be seeing the new unaired
 * episodes."
 *
 * The Upcoming rail is fed from Continue Watching - which by construction holds
 * the shows with something left to watch - so every show with a backlog and a
 * future air date landed on it. [isCaughtUpForUpcoming] is the gate: aired
 * episodes watched, nothing outstanding. Continue Watching itself is untouched,
 * badges and all, because that is where "an episode has arrived" is announced.
 *
 * The two exclusions matter as much as the rule: a show we could not count
 * (offline) must not be hidden, and a tracker pointing at an episode TMDB has
 * not aired yet is a caught-up show, not a backlog.
 */
class UpcomingCaughtUpTest {

    private fun card(
        episodesWatched: Int? = null,
        episodesTotal: Int? = null,
        badge: UpNextBadge = UpNextBadge.CONTINUE_WATCHING
    ) = UpNextItem(
        id = "history:tt123:s1:e1",
        title = "Some Show",
        poster = null,
        badge = badge,
        parentId = "tt123",
        parentType = "series",
        season = 1,
        episode = 1,
        episodesWatched = episodesWatched,
        episodesTotal = episodesTotal
    )

    @Test
    fun `every aired episode watched is caught up`() {
        assertTrue(isCaughtUpForUpcoming(card(episodesWatched = 26, episodesTotal = 26)))
    }

    @Test
    fun `a stale tally above the aired total is still caught up`() {
        // Watched on another device against a shorter episode list.
        assertTrue(isCaughtUpForUpcoming(card(episodesWatched = 27, episodesTotal = 26)))
    }

    @Test
    fun `one unwatched aired episode is a backlog, not caught up`() {
        // The reported case at its smallest: the newest aired episode is still
        // waiting, so the one after it is not news yet.
        assertFalse(isCaughtUpForUpcoming(card(episodesWatched = 25, episodesTotal = 26)))
    }

    @Test
    fun `a show behind by seasons is not caught up`() {
        assertFalse(isCaughtUpForUpcoming(card(episodesWatched = 4, episodesTotal = 40)))
    }

    @Test
    fun `a show with nothing completed yet is not caught up`() {
        // Mid-episode 1: the local builder stores the watched count with
        // `takeIf { it > 0 }`, so "started, nothing finished" arrives as a total
        // and no count at all - and that is not caught up on anything.
        assertFalse(isCaughtUpForUpcoming(card(episodesWatched = null, episodesTotal = 12)))
        assertFalse(isCaughtUpForUpcoming(card(episodesWatched = 0, episodesTotal = 12)))
    }

    @Test
    fun `the badge does not decide it`() {
        // A NEW SEASON card is the alert that episodes ARRIVED, which is exactly
        // what a show with a backlog has. The catch-up state is the gate; the
        // badge only says how the arrival reads.
        assertFalse(
            isCaughtUpForUpcoming(
                card(episodesWatched = 5, episodesTotal = 12, badge = UpNextBadge.NEW_SEASON)
            )
        )
        assertTrue(
            isCaughtUpForUpcoming(
                card(episodesWatched = 12, episodesTotal = 12, badge = UpNextBadge.NEW_SEASON)
            )
        )
    }

    @Test
    fun `an uncounted show is never hidden`() {
        // Every season lookup failed - offline, or TMDB hiccuped. An offline
        // device cannot prove a backlog, and hiding a caught-up show's next
        // episode is worse than keeping a card for a show the viewer may be
        // behind on. Same reasoning as hasNothingLeftToWatch.
        assertTrue(isCaughtUpForUpcoming(card(episodesWatched = null, episodesTotal = null)))
        assertTrue(isCaughtUpForUpcoming(card(episodesWatched = 3, episodesTotal = null)))
        assertTrue(isCaughtUpForUpcoming(card(episodesWatched = 3, episodesTotal = 0)))
    }
}
