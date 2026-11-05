package com.kennyb1201.kbstream.ui.home

import com.kennyb1201.kbstream.data.addon.MetaPreview
import com.kennyb1201.kbstream.data.settings.AppPreferences
import com.kennyb1201.kbstream.data.settings.BillboardTrailerMode
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The billboard's feed rules, as behaviour rather than as source text.
 *
 * Everything here decides WHICH titles the hero cycles through, and every one
 * of those decisions is a pure function on purpose (see BillboardRules.kt): the
 * merge order is the viewer's pick order, a broken catalog is a missing page and
 * not a broken billboard, and "the default feed" is a SOURCE, so it can be
 * merged with a catalog rather than replacing the selection.
 *
 * The de-dupe key is the title's id AND type. TMDB numbers movies and shows in
 * separate spaces, so keying on the id alone would collapse a film and a series
 * that happen to share a number - which is exactly the collision two merged
 * catalogs create.
 */
class BillboardFeedRulesTest {

    private fun item(
        id: String,
        type: String = "movie",
        name: String = id
    ) = MetaPreview(id = id, type = type, name = name)

    // ── the trending walk ───────────────────────────────────────────────

    /**
     * The walk's two stops, as numbers.
     *
     * One page of trending is 20 movies and 20 shows and the ceiling decides
     * every one of them, so a strict ceiling can keep one survivor - a pool a
     * hero cannot swipe through. The walk asks for more pages until the pool is
     * full, and the bound is what keeps a pathological day from hammering TMDB
     * from a Home visit.
     */
    @Test
    fun `the walk stops when the pool is full or the bound is reached`() {
        assertTrue(
            "a page that left the pool short is worth another fetch",
            billboardShouldWalkNextPage(pagesWalked = 1, poolSize = 1)
        )
        assertTrue(
            "and so is the last page the bound allows",
            billboardShouldWalkNextPage(pagesWalked = 4, poolSize = 9)
        )
        assertFalse(
            "a full pool gains nothing from walking on",
            billboardShouldWalkNextPage(pagesWalked = 1, poolSize = BILLBOARD_MAX_PAGES)
        )
        assertFalse(
            "the bound is the guard against hammering the feed",
            billboardShouldWalkNextPage(pagesWalked = 5, poolSize = 0)
        )
        assertEquals(
            "five pages: page 1 plus four more, 200 titles considered",
            5,
            BILLBOARD_TRENDING_MAX_WALK_PAGES
        )
    }

    // ── the default feed: movie #1, TV #1, movie #2, TV #2, ... ──────────

    @Test
    fun `day feeds interleave by rank`() {
        val pages = billboardInterleaveFeeds(
            movies = listOf(item("m1"), item("m2"), item("m3")),
            shows = listOf(item("s1", "series"), item("s2", "series"))
        )

        assertEquals(
            listOf("m1", "s1", "m2", "s2", "m3"),
            pages.map { it.id }
        )
    }

    @Test
    fun `an exhausted feed lets the other finish in its own order`() {
        val pages = billboardInterleaveFeeds(
            movies = listOf(item("m1")),
            shows = listOf(item("s1", "series"), item("s2", "series"), item("s3", "series"))
        )

        assertEquals(listOf("m1", "s1", "s2", "s3"), pages.map { it.id })
    }

    @Test
    fun `a title in both day feeds is one page`() {
        val pages = billboardInterleaveFeeds(
            movies = listOf(item("tmdb:7")),
            // Same id, same rail spelling: the same title seen twice.
            shows = listOf(item("tmdb:7", "movie"))
        )

        assertEquals(1, pages.size)
    }

    @Test
    fun `the interleave is capped`() {
        val pages = billboardInterleaveFeeds(
            movies = (1..20).map { item("m$it") },
            shows = (1..20).map { item("s$it", "series") }
        )

        assertEquals(BILLBOARD_MAX_PAGES, pages.size)
        assertEquals(10, BILLBOARD_MAX_PAGES)
    }

    // ── chosen catalogs: concatenated in picker order ────────────────────

    @Test
    fun `chosen catalogs concatenate in the order they were picked`() {
        val merged = billboardMergeFeeds(
            listOf(
                listOf(item("a1"), item("a2")),
                listOf(item("b1")),
                listOf(item("c1"), item("c2"))
            )
        )

        assertEquals(listOf("a1", "a2", "b1", "c1", "c2"), merged.map { it.id })
    }

    @Test
    fun `a title in two chosen catalogs appears once`() {
        val merged = billboardMergeFeeds(
            listOf(
                listOf(item("tt1"), item("tt2")),
                listOf(item("tt2"), item("tt3"))
            )
        )

        assertEquals(listOf("tt1", "tt2", "tt3"), merged.map { it.id })
    }

    @Test
    fun `an empty catalog contributes nothing`() {
        val merged = billboardMergeFeeds(
            listOf(
                emptyList(),
                listOf(item("a1")),
                emptyList()
            )
        )

        assertEquals(listOf("a1"), merged.map { it.id })
    }

    @Test
    fun `one movie and one series with the same number are two pages`() {
        val merged = billboardMergeFeeds(
            listOf(
                listOf(item("tmdb:1399", "movie")),
                listOf(item("tmdb:1399", "series"))
            )
        )

        assertEquals(2, merged.size)
        assertEquals(
            listOf("movie:tmdb:1399", "series:tmdb:1399"),
            merged.map { billboardPageKey(it) }
        )
    }

    @Test
    fun `chosen catalogs are capped too`() {
        val merged = billboardMergeFeeds(
            listOf((1..25).map { item("x$it") })
        )

        assertEquals(BILLBOARD_MAX_PAGES, merged.size)
    }

    // ── the selection, and what an empty one means ───────────────────────

    @Test
    fun `the default entry alone is the day feed`() {
        val day = listOf(item("d1"), item("d2", "series"))

        val chosen = billboardChosenFeed(
            keys = listOf(AppPreferences.BILLBOARD_TRENDING_DAY_FEED),
            sources = emptyMap(),
            defaultFeed = day
        )

        assertEquals(day, chosen)
    }

    @Test
    fun `the default entry merges with a catalog in the picked order`() {
        val day = listOf(item("d1"), item("d2", "series"))
        val sources = mapOf("addon:x" to listOf(item("c1")))

        val dayFirst = billboardChosenFeed(
            keys = listOf(AppPreferences.BILLBOARD_TRENDING_DAY_FEED, "addon:x"),
            sources = sources,
            defaultFeed = day
        )
        val catalogFirst = billboardChosenFeed(
            keys = listOf("addon:x", AppPreferences.BILLBOARD_TRENDING_DAY_FEED),
            sources = sources,
            defaultFeed = day
        )

        assertEquals(listOf("d1", "d2", "c1"), dayFirst?.map { it.id })
        assertEquals(listOf("c1", "d1", "d2"), catalogFirst?.map { it.id })
    }

    @Test
    fun `an empty selection is the default selection, never an empty feed`() {
        val day = listOf(item("d1"))

        val chosen = billboardChosenFeed(
            keys = emptyList(),
            sources = emptyMap(),
            defaultFeed = day
        )

        assertEquals(day, chosen)
    }

    // ── the draw: what "cycle through all of them" means ─────────────────

    @Test
    fun `a pool bigger than the pager is drawn at random, not sliced`() {
        val pool = (1..25).map { item("p$it") }
        val sources = mapOf("addon:x" to pool)

        val first = billboardChosenFeed(
            keys = listOf("addon:x"),
            sources = sources,
            defaultFeed = emptyList(),
            random = Random(1)
        )
        val second = billboardChosenFeed(
            keys = listOf("addon:x"),
            sources = sources,
            defaultFeed = emptyList(),
            random = Random(2)
        )

        assertEquals("the cap still bounds the pager", BILLBOARD_MAX_PAGES, first?.size)
        assertEquals(
            "one visit cannot repeat a page",
            first?.map { it.id }?.distinct()?.size,
            first?.size
        )
        assertTrue("every page came from the pool", first?.all { it in pool } == true)
        assertNotEquals(
            "two visits draw different titles - the rest of a selected rail is " +
                "reachable, which is what cycling through all of them means",
            first?.map { it.id },
            second?.map { it.id }
        )
    }

    @Test
    fun `a pool no bigger than the pager keeps the order its sources gave`() {
        val pool = listOf(item("a"), item("b"), item("c"))

        val chosen = billboardChosenFeed(
            keys = listOf("addon:x"),
            sources = mapOf("addon:x" to pool),
            defaultFeed = emptyList(),
            random = Random(7)
        )

        assertEquals(
            "nothing to cycle through: the day feed's interleave is a RANK, and a " +
                "small rail is shown as it came",
            pool,
            chosen
        )
    }

    @Test
    fun `the same title in two chosen rails is one page and does not eat a slot`() {
        val shared = item("shared")
        val first = listOf(shared) + (1..15).map { item("a$it") }
        val second = listOf(shared) + (1..15).map { item("b$it") }

        val chosen = billboardChosenFeed(
            keys = listOf("addon:a", "addon:b"),
            sources = mapOf("addon:a" to first, "addon:b" to second),
            defaultFeed = emptyList(),
            random = Random(3)
        )

        assertEquals("a merged duplicate must not leave the pager one page short", BILLBOARD_MAX_PAGES, chosen?.size)
        assertEquals(
            "and no page appears twice",
            chosen?.map { it.id }?.distinct()?.size,
            chosen?.size
        )
    }

    @Test
    fun `every chosen catalog empty answers null so the caller falls back`() {
        val chosen = billboardChosenFeed(
            keys = listOf("addon:offline"),
            sources = mapOf("addon:offline" to emptyList()),
            defaultFeed = listOf(item("d1"))
        )

        assertNull(chosen)
    }

    @Test
    fun `a chosen catalog the screen does not know contributes nothing`() {
        val chosen = billboardChosenFeed(
            keys = listOf("kb:collection-1", "addon:known"),
            sources = mapOf("addon:known" to listOf(item("c1"))),
            defaultFeed = emptyList()
        )

        assertEquals(listOf("c1"), chosen?.map { it.id })
    }

    // ── prefs: defaults, storage form, snapping ──────────────────────────

    @Test
    fun `an install starts on trending today, six seconds, no trailer`() {
        assertEquals(
            listOf(AppPreferences.BILLBOARD_TRENDING_DAY_FEED),
            AppPreferences.DEFAULT_BILLBOARD_FEEDS
        )
        assertEquals(6_000L, AppPreferences.DEFAULT_BILLBOARD_CYCLE_MS)
        assertEquals(BillboardTrailerMode.OFF, BillboardTrailerMode.fromStorage(null))
        assertEquals(BillboardTrailerMode.OFF, BillboardTrailerMode.fromStorage(0))
    }

    @Test
    fun `feeds survive a round trip, in order and without duplicates`() {
        val stored = AppPreferences.encodeBillboardFeeds(
            listOf("b", "a", "b", "  ", " c ")
        )

        assertEquals("b\na\nc", stored)
        assertEquals(
            listOf("b", "a", "c"),
            AppPreferences.billboardFeedsFromStored(stored)
        )
    }

    @Test
    fun `a stored empty or blank selection reads as the default`() {
        assertEquals(
            AppPreferences.DEFAULT_BILLBOARD_FEEDS,
            AppPreferences.billboardFeedsFromStored(null)
        )
        assertEquals(
            AppPreferences.DEFAULT_BILLBOARD_FEEDS,
            AppPreferences.billboardFeedsFromStored("")
        )
        assertEquals(
            AppPreferences.DEFAULT_BILLBOARD_FEEDS,
            AppPreferences.billboardFeedsFromStored(" \n \n")
        )
    }

    @Test
    fun `a stored hold snaps to the closest option`() {
        assertEquals(0L, AppPreferences.billboardCycleFromStored(400L))
        assertEquals(3_000L, AppPreferences.billboardCycleFromStored(2_500L))
        assertEquals(6_000L, AppPreferences.billboardCycleFromStored(AppPreferences.DEFAULT_BILLBOARD_CYCLE_MS))
        assertEquals(15_000L, AppPreferences.billboardCycleFromStored(600_000L))
    }

    @Test
    fun `an unknown stored trailer mode is off rather than a guess`() {
        assertEquals(BillboardTrailerMode.OFF, BillboardTrailerMode.fromStorage(99))
        assertEquals(BillboardTrailerMode.AUTO, BillboardTrailerMode.fromStorage(1))
        assertEquals(BillboardTrailerMode.MANUAL, BillboardTrailerMode.fromStorage(2))
    }

    @Test
    fun `the chips read the way the spec words them`() {
        assertEquals("Manual only", billboardCycleLabel(0L))
        assertEquals("6s", billboardCycleLabel(6_000L))
        assertEquals("Off", billboardTrailerModeLabel(BillboardTrailerMode.OFF))
        assertEquals("Auto-play", billboardTrailerModeLabel(BillboardTrailerMode.AUTO))
        assertEquals("Manual", billboardTrailerModeLabel(BillboardTrailerMode.MANUAL))
        assertEquals("Trending today", BILLBOARD_DEFAULT_FEED_LABEL)
    }

    @Test
    fun `only the one sentinel is the default feed`() {
        assertTrue(isBillboardDefaultFeed(AppPreferences.BILLBOARD_TRENDING_DAY_FEED))
        assertTrue(!isBillboardDefaultFeed("trending_week"))
        assertTrue(!isBillboardDefaultFeed("addon:x"))
    }
}
