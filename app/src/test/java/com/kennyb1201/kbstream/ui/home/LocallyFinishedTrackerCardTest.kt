package com.kennyb1201.kbstream.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A title the device has FINISHED locally leaves Continue Watching at once,
 * without waiting for the tracker feed to agree.
 *
 * Reported problem: finishing the last episode of a show and exiting the
 * player left the card on the rail for as long as the completion push and the
 * tracker's own feed took to catch up. The local pass already proves the show
 * is caught up (nothing left to resume), so its tracker card can be dropped on
 * that evidence alone.
 *
 * Slice B adds the other half: that verdict is only authoritative about the
 * EPISODES IT COUNTED. A verdict formed against a month-old detail must not
 * suppress a tracker card pointing at the episode that aired since - that card
 * is the card the viewer is owed (see [LocallyFinishedShow]).
 */
class LocallyFinishedTrackerCardTest {

    /** The map the CW merge builds: one entry, indexed by every show key. */
    private fun finished(
        parentId: String = "tt10986410",
        tmdbId: Int? = 97546,
        furthestWatched: Pair<Int, Int>? = null
    ): Map<String, LocallyFinishedShow> {
        val entry =
            LocallyFinishedShow(
                keys = upNextShowParentKeys(parentId, "series", tmdbId),
                furthestWatched = furthestWatched
            )
        return entry.keys.associateWith { entry }
    }

    private fun trackerCard(
        parentId: String = "tmdb:97546",
        tmdbId: Int? = 97546,
        title: String = "The Gentlemen",
        parentType: String = "series",
        season: Int? = null,
        episode: Int? = null
    ): UpNextItem = UpNextItem(
        id = "simkl:show-12345",
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

    private fun localCard(
        parentId: String = "tt10986410",
        tmdbId: Int? = 97546,
        title: String = "The Gentlemen"
    ): UpNextItem = UpNextItem(
        id = "history:$parentId:2:8",
        title = title,
        poster = null,
        badge = UpNextBadge.CONTINUE_WATCHING,
        subtitle = "Resume - S2E8",
        parentId = parentId,
        parentType = "series",
        season = 2,
        episode = 8,
        tmdbId = tmdbId,
        historyRowId = "$parentId:2:8",
        recencyTimestamp = 1_000L
    )

    @Test
    fun `a tracker card for a locally caught-up show is dropped`() {
        assertTrue(trackerCardLocallyFinished(trackerCard(), finished()))
    }

    @Test
    fun `the id flavors do not matter`() {
        // The local pass proved the show caught up under its imdb id; the
        // tracker card carries the tmdb id. Both resolve the same TMDB number,
        // so they still pair.
        assertTrue(
            trackerCardLocallyFinished(
                trackerCard(parentId = "tmdb:97546", tmdbId = 97546),
                finished()
            )
        )
    }

    @Test
    fun `another show's tracker card survives`() {
        assertFalse(
            trackerCardLocallyFinished(
                trackerCard(
                    parentId = "tmdb:22222",
                    tmdbId = 22222,
                    title = "Slow Horses"
                ),
                finished()
            )
        )
    }

    @Test
    fun `a local card is never dropped by this rule`() {
        // The rule is about the tracker keeping a finished title alive; a
        // card built from this profile's own history is not its business.
        assertFalse(trackerCardLocallyFinished(localCard(), finished()))
    }

    @Test
    fun `nothing is dropped when no show is caught up`() {
        assertFalse(
            trackerCardLocallyFinished(
                trackerCard(),
                emptyMap()
            )
        )
    }

    @Test
    fun `an mdblist card is dropped too`() {
        val mdblist = trackerCard().copy(id = "mdblist:show-97546")

        assertTrue(trackerCardLocallyFinished(mdblist, finished()))
    }

    // ── Slice B: the verdict is about the episodes it counted ─────────────

    @Test
    fun `a card for the episode just finished is still dropped`() {
        assertTrue(
            "the stale twin the rule exists for",
            trackerCardLocallyFinished(
                trackerCard(season = 1, episode = 8),
                finished(furthestWatched = 1 to 8)
            )
        )
    }

    @Test
    fun `a card for an earlier episode is still dropped`() {
        assertTrue(
            "the viewer watched it; whatever the detail says",
            trackerCardLocallyFinished(
                trackerCard(season = 1, episode = 7),
                finished(furthestWatched = 1 to 8)
            )
        )
    }

    @Test
    fun `a card for the NEXT episode survives a stale finished verdict`() {
        // The Nov 8 case: the local verdict was formed on a detail that predates
        // the airing, so its furthest watched pair is the episode before the
        // card's. The card must survive - it is the only card the viewer has.
        assertFalse(
            trackerCardLocallyFinished(
                trackerCard(season = 1, episode = 9),
                finished(furthestWatched = 1 to 8)
            )
        )
    }

    @Test
    fun `a card for a later SEASON survives too`() {
        // Comparison is season-major: a new season's premiere is past a finished
        // run of the season before, whatever the episode numbers say.
        assertFalse(
            trackerCardLocallyFinished(
                trackerCard(season = 2, episode = 1),
                finished(furthestWatched = 1 to 12)
            )
        )
    }

    @Test
    fun `a card that names no episode is suppressed when the pair is known`() {
        // It cannot be told apart from the episode just finished - the same
        // conservative call the just-completed rule makes.
        assertTrue(
            trackerCardLocallyFinished(
                trackerCard(season = null, episode = null),
                finished(furthestWatched = 1 to 8)
            )
        )
    }

    @Test
    fun `a show recorded with no pair suppresses every card, as before`() {
        // Unknown evidence is not a licence to re-admit a card the old rule
        // dropped, and a later episode cannot be proved against nothing.
        assertTrue(
            trackerCardLocallyFinished(
                trackerCard(season = 2, episode = 1),
                finished(furthestWatched = null)
            )
        )
    }

    @Test
    fun `the episode comparison pairs across id flavors`() {
        // Recorded under the imdb flavor, the card carries the tmdb one; the
        // LATER episode still wins on the same record.
        assertFalse(
            trackerCardLocallyFinished(
                trackerCard(parentId = "tmdb:97546", tmdbId = 97546, season = 3, episode = 4),
                finished(parentId = "tt10986410", tmdbId = 97546, furthestWatched = 3 to 3)
            )
        )
    }

    @Test
    fun `the caught-up keys name every id form the show carries`() {
        val keys = upNextShowParentKeys("tmdb:97546", "series", 97546)

        assertEquals(
            setOf("parent:series:97546"),
            keys
        )
    }

    @Test
    fun `a show with only an imdb id is still nameable`() {
        val keys = upNextShowParentKeys("tt10986410", "series", null)

        assertEquals(
            setOf("parent:series:tt10986410"),
            keys
        )
    }
}
