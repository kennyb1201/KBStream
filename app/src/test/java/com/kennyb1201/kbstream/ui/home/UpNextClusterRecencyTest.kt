package com.kennyb1201.kbstream.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The merged card must keep the viewer's most recent touch of the title.
 *
 * Reported bug: a movie stopped midway appeared on the rail's first render
 * (the local-history seed) and then, once the tracker feeds merged in, it and
 * another half-watched movie moved to the very LAST spots of Continue
 * Watching — behind every card that merely had a timestamp. The rail orders
 * by `recencyTimestamp`, and the cluster winner is chosen for what it will
 * DISPLAY (badge and precision). A paused tracker session can win that
 * comparison while carrying no time at all (MDBList's session `updated_at` is
 * optional, and a miss reads as 0), which dropped the local twin's fresh
 * timestamp and sank the card to the end.
 */
class UpNextClusterRecencyTest {

    /** A local resume row for a movie: fresh time, but an unknown runtime. */
    private fun localMovie(
        parentId: String = "tt1234567",
        title: String = "The Movie",
        watchedAt: Long = 9_000L
    ) = UpNextItem(
        id = "history:$parentId",
        title = title,
        poster = "https://image.tmdb.org/t/p/w342/poster.jpg",
        badge = UpNextBadge.CONTINUE_WATCHING,
        parentId = parentId,
        parentType = "movie",
        startPositionMs = 5L * 60L * 1000L,
        progressPercent = 0.4f,
        remainingMinutes = null,
        recencyTimestamp = watchedAt,
        historyRowId = parentId
    )

    /**
     * A paused tracker session for the same movie. Its richer progress fields
     * give it a HIGHER `winnerScore` than the local row, so it wins the
     * cluster even when its timestamp is missing.
     */
    private fun trackerMovie(
        parentId: String = "tt1234567",
        title: String = "The Movie",
        watchedAt: Long = 0L
    ) = UpNextItem(
        id = "mdblist:5",
        title = title,
        poster = "https://image.tmdb.org/t/p/w342/poster.jpg",
        badge = UpNextBadge.CONTINUE_WATCHING,
        parentId = parentId,
        parentType = "movie",
        startPositionMs = 1L,
        progressPercent = 0.4f,
        remainingMinutes = 12,
        recencyTimestamp = watchedAt
    )

    @Test
    fun `a tracker twin with no timestamp cannot sink the just-watched movie`() {
        val result = dedupeAndSortUpNext(listOf(trackerMovie(), localMovie()))

        assertEquals(1, result.size)
        // The richer twin still wins the card, but it carries the viewer's
        // own last touch so it orders by it.
        assertEquals("mdblist:5", result.single().id)
        assertEquals(9_000L, result.single().recencyTimestamp)
    }

    @Test
    fun `a stale twin timestamp does not bury the fresh local one`() {
        val result = dedupeAndSortUpNext(
            listOf(trackerMovie(watchedAt = 1_000L), localMovie(watchedAt = 9_000L))
        )

        assertEquals(1, result.size)
        assertEquals(9_000L, result.single().recencyTimestamp)
    }

    @Test
    fun `a movie with no local twin keeps its own timestamp`() {
        val result = dedupeAndSortUpNext(listOf(trackerMovie(watchedAt = 5_000L)))

        assertEquals(1, result.size)
        assertEquals(5_000L, result.single().recencyTimestamp)
    }

    @Test
    fun `the just-watched movie leads a rail of older tracker cards`() {
        val justWatched = dedupeAndSortUpNext(
            listOf(trackerMovie(watchedAt = 0L), localMovie(watchedAt = 9_000L))
        )

        val result =
            dedupeAndSortUpNext(
                justWatched +
                    listOf(
                        trackerMovie(
                            parentId = "tt0000001",
                            title = "Older A",
                            watchedAt = 4_000L
                        ),
                        trackerMovie(
                            parentId = "tt0000002",
                            title = "Older B",
                            watchedAt = 2_000L
                        )
                    )
            )

        assertEquals(
            listOf("The Movie", "Older A", "Older B"),
            result.map { it.title }
        )
    }

    @Test
    fun `two different titles are not merged`() {
        val result = dedupeAndSortUpNext(
            listOf(
                localMovie(),
                trackerMovie(
                    parentId = "tt0000002",
                    title = "Another Movie",
                    watchedAt = 1_000L
                )
            )
        )

        assertEquals(2, result.size)
        assertEquals(
            listOf("The Movie", "Another Movie"),
            result.map { it.title }
        )
    }
}
