package com.kennyb1201.kbstream.data.bingecat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The watched feed folded for the Upcoming rail (spec F2a, T2).
 *
 * The feed is "newest first, episodes grouped per show and local day", so one
 * show reaches the fold on several rows - one per day it was watched - and the
 * rail needs the UNION of their episodes: its caught-up test asks whether the
 * last AIRED episode is among them. What is pinned here is that union, the
 * identity the rest of the app keys by (IMDb first, `tmdb:<n>` after), and the
 * three things the fold refuses to guess: a movie row, a media object with no
 * usable id, and a play with no placeable season/number.
 */
class BingeCatWatchedSnapshotTest {

    private fun media(imdb: String? = null, tmdb: Int? = null) =
        BingeCatMedia(title = "A Show", year = 2024, ids = BingeCatIds(imdb = imdb, tmdb = tmdb))

    private fun play(season: Int?, number: Int?) =
        BingeCatHistoryEpisodeEntry(season = season, number = number, title = "E$number")

    private fun showRow(
        imdb: String? = null,
        tmdb: Int? = null,
        vararg plays: BingeCatHistoryEpisodeEntry
    ) = BingeCatHistoryItem(
        id = "row",
        kind = "episodes",
        type = "show",
        show = media(imdb = imdb, tmdb = tmdb),
        episodes = plays.toList()
    )

    @Test
    fun `rows for one show union their episodes, in the feed's own order`() {
        val snapshot = bingeCatWatchedSnapshot(
            listOf(
                showRow(imdb = "tt13111078", plays = arrayOf(play(4, 4))),
                showRow(tmdb = 235357, plays = arrayOf(play(2, 8))),
                showRow(imdb = "tt13111078", plays = arrayOf(play(4, 3), play(4, 4)))
            )
        )

        assertEquals(
            "two ids, two shows - a feed row keyed by TMDB is not the IMDb one",
            2,
            snapshot.shows.size
        )
        assertEquals("tt13111078", snapshot.shows[0].parentId)
        assertEquals(
            "the same episode on two days is one pair",
            setOf(4 to 4, 4 to 3),
            snapshot.shows[0].episodes
        )
        assertEquals("tmdb:235357", snapshot.shows[1].parentId)
    }

    @Test
    fun `a show is keyed by its IMDb id first, then tmdb`() {
        assertEquals(
            "IMDb wins when the row carries both - the id local history uses",
            "tt13111078",
            bingeCatShowKey(media(imdb = "tt13111078", tmdb = 235357))
        )
        assertEquals(
            "and the TMDB form is the fallback",
            "tmdb:235357",
            bingeCatShowKey(media(tmdb = 235357))
        )
        assertNull(
            "no id this app can resolve is not a show it can key",
            bingeCatShowKey(media())
        )
    }

    @Test
    fun `movies, unkeyable media and unplaceable plays prove nothing`() {
        val movie = BingeCatHistoryItem(
            id = "m",
            kind = "movie",
            type = "movie",
            movie = BingeCatMedia(title = "A Film", ids = BingeCatIds(imdb = "tt1")),
            episodes = emptyList()
        )

        val snapshot = bingeCatWatchedSnapshot(
            listOf(
                movie,
                // A show row the account watched, but with no id this app knows.
                showRow(plays = arrayOf(play(1, 1))),
                // An unplaceable play: the server sent no season, or no number.
                showRow(imdb = "tt9", plays = arrayOf(play(null, 3), play(2, null))),
                showRow(imdb = "tt10", plays = arrayOf(play(1, 2)))
            )
        )

        assertEquals(
            "only the show with a placeable play survives",
            1,
            snapshot.shows.size
        )
        assertEquals("tt10", snapshot.shows[0].parentId)
    }

    @Test
    fun `a feed with nothing watched folds to an empty snapshot`() {
        val snapshot = bingeCatWatchedSnapshot(emptyList())
        assertTrue("no rows, no shows - the rail simply has one source less", snapshot.shows.isEmpty())
    }
}
