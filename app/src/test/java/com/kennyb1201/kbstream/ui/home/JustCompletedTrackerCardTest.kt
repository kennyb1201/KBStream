package com.kennyb1201.kbstream.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A title this device just FINISHED leaves Continue Watching at once, without
 * waiting for the tracker feed to agree.
 *
 * Reported problem: after watching something, its card stayed on the rail for
 * around forty-five seconds. The local side was never the problem - the
 * finished row leaves the rail's own query the moment the player writes it -
 * but the tracker feed the card came from still listed the title, and the rail
 * only re-read that feed on a coarse schedule. These tests pin the rule that
 * makes the local completion authoritative instead: a tracker card pointing at
 * something this profile has already completed is stale.
 */
class JustCompletedTrackerCardTest {

    private fun trackerCard(
        id: String = "simkl:show-12345",
        parentId: String? = "tmdb:97546",
        tmdbId: Int? = 97546,
        title: String = "The Gentlemen",
        parentType: String = "series",
        season: Int? = 2,
        episode: Int? = 8
    ): UpNextItem = UpNextItem(
        id = id,
        title = title,
        poster = null,
        badge = UpNextBadge.NEXT_UP,
        subtitle = "Up Next",
        parentId = parentId,
        parentType = parentType,
        season = season,
        episode = episode,
        tmdbId = tmdbId,
        recencyTimestamp = 2_000L
    )

    private fun movieTrackerCard(
        parentId: String? = "tt1160419",
        tmdbId: Int? = 438631,
        title: String = "Dune"
    ): UpNextItem = UpNextItem(
        id = "simkl:movie-9",
        title = title,
        poster = null,
        badge = UpNextBadge.NEXT_UP,
        subtitle = "Paused 34%",
        parentId = parentId,
        parentType = "movie",
        tmdbId = tmdbId,
        recencyTimestamp = 2_000L
    )

    private fun localCard(): UpNextItem = UpNextItem(
        id = "history:tt10986410:2:8",
        title = "The Gentlemen",
        poster = null,
        badge = UpNextBadge.CONTINUE_WATCHING,
        subtitle = "Resume - S2E8",
        parentId = "tt10986410",
        parentType = "series",
        season = 2,
        episode = 8,
        historyRowId = "tt10986410:2:8",
        recencyTimestamp = 1_000L
    )

    private fun completedMovie(): CompletedTitleMark =
        completedTitleMark(
            parentId = "tt1160419",
            parentType = "movie",
            tmdbId = null,
            season = null,
            episode = null,
            title = "Dune"
        )!!

    @Test
    fun `a tracker card for a movie this profile finished is dropped`() {
        assertTrue(
            trackerCardJustCompletedByProfile(
                movieTrackerCard(),
                listOf(completedMovie())
            )
        )
    }

    @Test
    fun `the movie's id flavors do not matter`() {
        // The player filed the completion under the imdb id; the tracker card
        // carries a different one.
        assertTrue(
            trackerCardJustCompletedByProfile(
                movieTrackerCard(parentId = "tmdb:438631", tmdbId = 438631),
                listOf(completedMovie())
            )
        )
    }

    @Test
    fun `a tracker card for another title survives`() {
        assertFalse(
            trackerCardJustCompletedByProfile(
                movieTrackerCard(
                    parentId = "tt0111161",
                    tmdbId = 278,
                    title = "The Shawshank Redemption"
                ),
                listOf(completedMovie())
            )
        )
    }

    @Test
    fun `a tracker card for the episode just finished is dropped`() {
        val mark = completedTitleMark(
            parentId = "tt10986410",
            parentType = "series",
            tmdbId = null,
            season = 2,
            episode = 8,
            title = "The Gentlemen"
        )!!

        assertTrue(trackerCardJustCompletedByProfile(trackerCard(), listOf(mark)))
    }

    @Test
    fun `a tracker card for a LATER episode of the same show survives`() {
        // The tracker has moved on to the next episode. That is exactly the
        // card the rail should show, so the rule must not touch it.
        val mark = completedTitleMark(
            parentId = "tt10986410",
            parentType = "series",
            tmdbId = null,
            season = 2,
            episode = 8,
            title = "The Gentlemen"
        )!!

        assertFalse(
            trackerCardJustCompletedByProfile(
                trackerCard(season = 2, episode = 9),
                listOf(mark)
            )
        )
    }

    @Test
    fun `a card that names no episode is dropped for the finished show`() {
        // The tracker's own show-level card cannot be told apart from the
        // episode just finished; the local pass owns that show's next card.
        val mark = completedTitleMark(
            parentId = "tt10986410",
            parentType = "series",
            tmdbId = null,
            season = 2,
            episode = 8,
            title = "The Gentlemen"
        )!!

        assertTrue(
            trackerCardJustCompletedByProfile(
                trackerCard(season = null, episode = null),
                listOf(mark)
            )
        )
    }

    @Test
    fun `a local card is never dropped by this rule`() {
        val mark = completedTitleMark(
            parentId = "tt10986410",
            parentType = "series",
            tmdbId = null,
            season = 2,
            episode = 8,
            title = "The Gentlemen"
        )!!

        assertFalse(trackerCardJustCompletedByProfile(localCard(), listOf(mark)))
    }

    @Test
    fun `an mdblist card is dropped too`() {
        assertTrue(
            trackerCardJustCompletedByProfile(
                movieTrackerCard().copy(id = "mdblist:movie-438631"),
                listOf(completedMovie())
            )
        )
    }

    @Test
    fun `nothing is dropped when nothing was completed`() {
        assertFalse(trackerCardJustCompletedByProfile(trackerCard(), emptyList()))
        assertFalse(trackerCardJustCompletedByProfile(movieTrackerCard(), emptyList()))
    }

    @Test
    fun `a completed row with no id and no title is not a mark`() {
        assertTrue(
            completedTitleMark(
                parentId = null,
                parentType = "movie",
                tmdbId = null,
                season = null,
                episode = null,
                title = "   "
            ) == null
        )
    }

    @Test
    fun `an episode zero in the row is not an episode`() {
        // A source that wrote 0 where it meant "no episode" (see
        // EpisodeNumbering) must not pin the rule to "episode 0".
        val mark = completedTitleMark(
            parentId = "tt1160419",
            parentType = "movie",
            tmdbId = null,
            season = 0,
            episode = 0,
            title = "Dune"
        )!!

        assertTrue(mark.season == null && mark.episode == null)
        assertTrue(
            trackerCardJustCompletedByProfile(movieTrackerCard(), listOf(mark))
        )
    }
}
