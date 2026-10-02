package com.kennyb1201.kbstream.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Continue Watching's order.
 *
 * The rail is assembled from local history, a tracker feed and TMDB, but the
 * order a viewer sees is this comparator alone — and the complaint behind it
 * was that a new season or a freshly aired episode sank under every title the
 * viewer merely had a position in, however long ago they had paused it.
 */
class UpNextRailOrderTest {

    private fun card(
        id: String,
        badge: UpNextBadge,
        watchedAt: Long
    ) = UpNextItem(
        id = id,
        title = id,
        poster = null,
        badge = badge,
        recencyTimestamp = watchedAt
    )

    @Test
    fun `a fresh arrival leads the rail however long ago the show was watched`() {
        val result = listOf(
            card("new-episode-1", UpNextBadge.NEW_EPISODE, watchedAt = 5_000),
            card("new-season", UpNextBadge.NEW_SEASON, watchedAt = 4_000),
            card("watched-minutes-ago", UpNextBadge.NEXT_UP, watchedAt = 1_000),
            card("new-episode-2", UpNextBadge.NEW_EPISODE, watchedAt = 3_000)
        ).sortedWith(upNextRailComparator)

        assertEquals(
            listOf(
                "new-episode-1",
                "new-season",
                "new-episode-2",
                "watched-minutes-ago"
            ),
            result.map { it.id }
        )
    }

    @Test
    fun `the watching badges share a tier, so recency decides between them`() {
        val result = listOf(
            card("paused-a-week-ago", UpNextBadge.CONTINUE_WATCHING, watchedAt = 1_000),
            card("finished-minutes-ago", UpNextBadge.NEXT_UP, watchedAt = 9_000)
        ).sortedWith(upNextRailComparator)

        assertEquals(
            listOf("finished-minutes-ago", "paused-a-week-ago"),
            result.map { it.id }
        )
    }

    @Test
    fun `a fresh arrival leads the rail over a title being watched`() {
        val result = listOf(
            card("news-today", UpNextBadge.NEW_EPISODE, watchedAt = 9_000),
            card("stale-resume", UpNextBadge.CONTINUE_WATCHING, watchedAt = 1)
        ).sortedWith(upNextRailComparator)

        assertEquals(listOf("news-today", "stale-resume"), result.map { it.id })
    }

    @Test
    fun `news cards keep their own recency order`() {
        val result = listOf(
            card("older", UpNextBadge.NEW_SEASON, watchedAt = 1_000),
            card("newer", UpNextBadge.NEW_EPISODE, watchedAt = 8_000)
        ).sortedWith(upNextRailComparator)

        assertEquals(listOf("newer", "older"), result.map { it.id })
    }

    @Test
    fun `a card with no recency timestamp sinks within its tier`() {
        val result = listOf(
            card("unknown-when", UpNextBadge.NEW_EPISODE, watchedAt = 0),
            card("watched-once", UpNextBadge.NEW_EPISODE, watchedAt = 1)
        ).sortedWith(upNextRailComparator)

        assertEquals(listOf("watched-once", "unknown-when"), result.map { it.id })
    }

    @Test
    fun `equal timestamps fall back to the title`() {
        val result = listOf(
            card("Zulu", UpNextBadge.NEXT_UP, watchedAt = 100),
            card("alpha", UpNextBadge.NEXT_UP, watchedAt = 100)
        ).sortedWith(upNextRailComparator)

        assertEquals(listOf("alpha", "Zulu"), result.map { it.id })
    }

    @Test
    fun `the tiers are the news pair and the watching pair`() {
        assertEquals(0, upNextRailTier(UpNextBadge.NEW_SEASON))
        assertEquals(0, upNextRailTier(UpNextBadge.NEW_EPISODE))
        assertEquals(1, upNextRailTier(UpNextBadge.CONTINUE_WATCHING))
        assertEquals(1, upNextRailTier(UpNextBadge.NEXT_UP))
    }
}
