package com.kennyb1201.kbstream.ui.detail

import com.kennyb1201.kbstream.data.history.WatchHistoryEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How an in-progress row is filed so an episode card can find its progress bar.
 *
 * The card looks up `inProgressByStreamId[ep.streamId]`, where the stream id is
 * the route-flavored `"<parentId>:<season>:<episode>"` - but a row written while
 * the same title was open under its other id flavor carries a different stream
 * id. Filing each row under both its own stream id and the route-flavored pair
 * is what makes the bar survive a flavor change, and iterating oldest-first over
 * one map is what makes the newest row win when an episode has more than one.
 */
class IndexInProgressByStreamIdTest {

    private fun row(
        id: String,
        season: Int? = null,
        episode: Int? = null,
        episodeStreamId: String? = null,
        positionMs: Long = 1_000L,
        updatedAt: Long = 0L
    ) = WatchHistoryEntity(
        id = id,
        parentId = "tt123",
        type = "series",
        name = "Show",
        poster = null,
        streamUrl = null,
        season = season,
        episode = episode,
        episodeStreamId = episodeStreamId,
        positionMs = positionMs,
        durationMs = 10_000L,
        updatedAt = updatedAt,
        isCompleted = false
    )

    @Test
    fun `no rows index to nothing`() {
        assertTrue(indexInProgressByStreamId("tmdb:456", emptyList()).isEmpty())
    }

    @Test
    fun `a row is filed under its own stream id`() {
        val map = indexInProgressByStreamId("tt123", listOf(row("r", episodeStreamId = "tt123:2:5")))

        assertEquals(1, map.size)
        assertEquals(1_000L, map.getValue("tt123:2:5").positionMs)
    }

    @Test
    fun `a row is also filed under the route-flavored pair`() {
        // The row above was written under the title's other flavor, so its own
        // stream id is not what this screen's cards compare against; the
        // season/episode pair is.
        val map =
            indexInProgressByStreamId(
                parentId = "tmdb:456",
                rows = listOf(row("r", season = 2, episode = 5, episodeStreamId = "tt123:2:5"))
            )

        assertEquals(2, map.size)
        assertTrue(map.containsKey("tt123:2:5"))
        assertTrue(map.containsKey("tmdb:456:2:5"))
    }

    @Test
    fun `the newest row wins per key`() {
        // Newest-first input, as the queries return them. The newer save is
        // what the bar should show.
        val map =
            indexInProgressByStreamId(
                parentId = "tt123",
                rows = listOf(
                    row("new", episodeStreamId = "tt123:2:5", positionMs = 9_000L),
                    row("old", episodeStreamId = "tt123:2:5", positionMs = 3_000L)
                )
            )

        assertEquals(9_000L, map.getValue("tt123:2:5").positionMs)
    }

    @Test
    fun `the route-flavored key also resolves to the newest row`() {
        val map =
            indexInProgressByStreamId(
                parentId = "tt123",
                rows = listOf(
                    row("new", season = 1, episode = 1, positionMs = 8_000L),
                    row("old", season = 1, episode = 1, positionMs = 2_000L)
                )
            )

        assertEquals(8_000L, map.getValue("tt123:1:1").positionMs)
    }

    @Test
    fun `a row with no stream id is only reachable by its pair`() {
        val map =
            indexInProgressByStreamId(
                parentId = "tt123",
                rows = listOf(row("r", season = 3, episode = 2, episodeStreamId = null))
            )

        assertEquals(setOf("tt123:3:2"), map.keys)
    }

    @Test
    fun `a blank stream id is never a key`() {
        // A blank key would be looked up by nothing and would let two unrelated
        // rows collide on it.
        val map =
            indexInProgressByStreamId(
                parentId = "tt123",
                rows = listOf(row("r", episodeStreamId = "   "))
            )

        assertTrue(map.isEmpty())
    }

    @Test
    fun `a movie-like row with no numbers is filed under its stream id only`() {
        val map =
            indexInProgressByStreamId(
                parentId = "tt123",
                rows = listOf(row("r", episodeStreamId = "tt123"))
            )

        assertEquals(setOf("tt123"), map.keys)
    }
}
