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
 */
class LocallyFinishedTrackerCardTest {

    private fun trackerCard(
        parentId: String = "tmdb:97546",
        tmdbId: Int? = 97546,
        title: String = "The Gentlemen",
        parentType: String = "series"
    ): UpNextItem = UpNextItem(
        id = "simkl:show-12345",
        title = title,
        poster = null,
        badge = UpNextBadge.NEXT_UP,
        subtitle = "Up Next",
        parentId = parentId,
        parentType = parentType,
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
        val finished = upNextShowParentKeys("tt10986410", "series", 97546)

        assertTrue(trackerCardLocallyFinished(trackerCard(), finished))
    }

    @Test
    fun `the id flavors do not matter`() {
        // The local pass proved the show caught up under its imdb id; the
        // tracker card carries the tmdb id. Both resolve the same TMDB number,
        // so they still pair.
        val finished = upNextShowParentKeys("tt10986410", "series", 97546)

        assertTrue(
            trackerCardLocallyFinished(
                trackerCard(parentId = "tmdb:97546", tmdbId = 97546),
                finished
            )
        )
    }

    @Test
    fun `another show's tracker card survives`() {
        val finished = upNextShowParentKeys("tt10986410", "series", 97546)

        assertFalse(
            trackerCardLocallyFinished(
                trackerCard(
                    parentId = "tmdb:22222",
                    tmdbId = 22222,
                    title = "Slow Horses"
                ),
                finished
            )
        )
    }

    @Test
    fun `a local card is never dropped by this rule`() {
        // The rule is about the tracker keeping a finished title alive; a
        // card built from this profile's own history is not its business.
        val finished = upNextShowParentKeys("tt10986410", "series", 97546)

        assertFalse(trackerCardLocallyFinished(localCard(), finished))
    }

    @Test
    fun `nothing is dropped when no show is caught up`() {
        assertFalse(
            trackerCardLocallyFinished(
                trackerCard(),
                emptySet()
            )
        )
    }

    @Test
    fun `an mdblist card is dropped too`() {
        val finished = upNextShowParentKeys("tt10986410", "series", 97546)
        val mdblist = trackerCard().copy(id = "mdblist:show-97546")

        assertTrue(trackerCardLocallyFinished(mdblist, finished))
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
