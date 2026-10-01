package com.kennyb1201.kbstream.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which watched mark a Continue Watching card resolves to.
 *
 * "Mark as Watched" has to mean different things for a movie and for one
 * episode of a show: a movie is a whole-title mark, while an episode must
 * leave the rest of the show alone or a single press would finish the whole
 * series.
 */
class UpNextWatchedMarkTest {

    private fun card(
        id: String = "history:row-1",
        title: String = "Some Title",
        parentId: String? = "tt1234567",
        parentType: String? = "movie",
        season: Int? = null,
        episode: Int? = null,
        episodeStreamId: String? = null,
        showTitle: String? = null,
        tmdbId: Int? = null,
        poster: String? = null
    ) = UpNextItem(
        id = id,
        title = title,
        poster = poster,
        badge = UpNextBadge.CONTINUE_WATCHING,
        parentId = parentId,
        parentType = parentType,
        season = season,
        episode = episode,
        episodeStreamId = episodeStreamId,
        showTitle = showTitle,
        tmdbId = tmdbId
    )

    @Test
    fun `a movie card marks the whole title as a movie`() {
        val target = upNextWatchedTarget(card())

        assertEquals("tt1234567", (target as UpNextWatchedTarget.WholeTitle).parentId)
        assertEquals("movie", target.type)
    }

    @Test
    fun `a series card naming an episode marks just that episode`() {
        val target =
            upNextWatchedTarget(
                card(
                    parentId = "tt7654321",
                    parentType = "series",
                    season = 2,
                    episode = 5,
                    episodeStreamId = "stream-abc",
                    showTitle = "Some Show",
                    tmdbId = 42,
                    poster = "https://example/poster.jpg"
                )
            ) as UpNextWatchedTarget.Episode

        assertEquals("tt7654321", target.parentId)
        assertEquals("Some Show", target.title)
        assertEquals(2, target.season)
        assertEquals(5, target.episode)
        assertEquals("stream-abc", target.episodeStreamId)
        assertEquals(42, target.tmdbId)
        assertEquals("https://example/poster.jpg", target.poster)
    }

    @Test
    fun `a series card with no episode number falls back to a whole-series mark`() {
        val target =
            upNextWatchedTarget(
                card(parentType = "series", season = 2, episode = null)
            ) as UpNextWatchedTarget.WholeTitle

        assertEquals("series", target.type)
    }

    @Test
    fun `a specials episode is still an episode`() {
        val target =
            upNextWatchedTarget(
                card(parentType = "series", season = 0, episode = 1)
            )

        assertTrue(target is UpNextWatchedTarget.Episode)
        assertEquals(0, (target as UpNextWatchedTarget.Episode).season)
    }

    @Test
    fun `an episode zero is not a usable episode`() {
        val target =
            upNextWatchedTarget(
                card(parentType = "series", season = 1, episode = 0)
            )

        assertTrue(target is UpNextWatchedTarget.WholeTitle)
    }

    @Test
    fun `a blank episode stream id is dropped`() {
        val target =
            upNextWatchedTarget(
                card(parentType = "series", season = 1, episode = 3, episodeStreamId = "  ")
            ) as UpNextWatchedTarget.Episode

        assertNull(target.episodeStreamId)
    }

    @Test
    fun `a card with no parent id has nothing to mark`() {
        assertNull(upNextWatchedTarget(card(parentId = null)))
        assertNull(upNextWatchedTarget(card(parentId = "   ")))
    }

    @Test
    fun `a show title is preferred over the row title`() {
        val target =
            upNextWatchedTarget(
                card(
                    title = "Episode One",
                    showTitle = "The Show",
                    parentType = "series",
                    season = 1,
                    episode = 1
                )
            )

        assertEquals("The Show", target?.title)
    }
}
