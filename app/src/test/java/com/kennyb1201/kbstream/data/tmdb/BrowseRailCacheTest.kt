package com.kennyb1201.kbstream.data.tmdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session memo behind a browse page's return trip.
 *
 * A browse page is screen-scoped, so popping a genre, keyword, network, studio
 * or decade page threw its rails away and every return re-ran all six discover
 * rails behind a fresh skeleton. The memo that fixes it has to live above the
 * page (see BrowseRailCache) - and what it has to get right is narrow but
 * load-bearing: keep each rail at its DECLARED index so a refresh cannot
 * reshuffle the page, hand out a copy so a streaming refresh cannot corrupt the
 * entry it is filling, never remember "this page has nothing" as an answer, and
 * drop the page the viewer stopped using before the one they keep coming back
 * to.
 */
class BrowseRailCacheTest {

    // ── the memo itself ────────────────────────────────────────────────────

    @Test
    fun `a page a session loaded comes back to the next entry`() {
        val cache = BrowseRailSessionCache()

        cache.store("genre|12", slots("MOVIES · RECENT" to 0, "SERIES · POPULAR" to 3))

        assertEquals(
            "the same rails, at the same declared indices",
            slots("MOVIES · RECENT" to 0, "SERIES · POPULAR" to 3),
            cache.rails("genre|12")
        )
    }

    @Test
    fun `the copy a hit hands out cannot corrupt the stored page`() {
        val cache = BrowseRailSessionCache()
        cache.store("genre|12", slots("MOVIES · RECENT" to 0))

        // This is what the ViewModel does: fill the returned map as the refresh
        // streams in, rail by rail.
        val handedOut = requireNotNull(cache.rails("genre|12"))
        handedOut[1] = section("SERIES · RECENT", 1)

        assertEquals(
            "the cache still holds what the page was loaded with",
            setOf(0),
            requireNotNull(cache.rails("genre|12")).keys
        )
    }

    @Test
    fun `a page is stored under its own key only`() {
        val cache = BrowseRailSessionCache()
        cache.store("genre|12", slots("MOVIES · RECENT" to 0))

        assertNull(
            "the keyword with the same number is a different page",
            cache.rails("keyword|12")
        )
        assertNull(cache.rails("genre|13"))
    }

    @Test
    fun `a fresh load replaces the stored page`() {
        val cache = BrowseRailSessionCache()
        cache.store("genre|12", slots("MOVIES · RECENT" to 0))

        cache.store("genre|12", slots("SERIES · TOP RATED" to 0))

        assertEquals(
            setOf("SERIES · TOP RATED"),
            requireNotNull(cache.rails("genre|12")).values.map { it.title }.toSet()
        )
    }

    @Test
    fun `an empty page is never stored as an answer`() {
        val cache = BrowseRailSessionCache()

        cache.store("genre|12", emptyMap())

        assertNull(
            "a page whose rails all failed must never serve a cached empty to " +
                "the retry that follows it",
            cache.rails("genre|12")
        )
    }

    @Test
    fun `the cap drops the page the viewer stopped using, not the one they return to`() {
        val cache = BrowseRailSessionCache(maxPages = 2)

        cache.store("a", slots("MOVIES · RECENT" to 0))
        cache.store("b", slots("MOVIES · RECENT" to 0))

        // Re-entering "a" is what keeps it: the memo is access-ordered, so the
        // page behind the return trip is the last one out.
        cache.rails("a")

        cache.store("c", slots("MOVIES · RECENT" to 0))

        assertNotNull(cache.rails("a"))
        assertNull("b was the least recently used page", cache.rails("b"))
        assertNotNull(cache.rails("c"))
        assertEquals(2, cache.pageCount())
    }

    @Test
    fun `clearing drops every page, for a profile switch`() {
        val cache = BrowseRailSessionCache()
        cache.store("a", slots("MOVIES · RECENT" to 0))
        cache.store("b", slots("MOVIES · RECENT" to 0))

        cache.clear()

        assertNull(cache.rails("a"))
        assertNull(cache.rails("b"))
        assertEquals(0, cache.pageCount())
    }

    @Test
    fun `a key names the page type and every id the route carries`() {
        assertNotEquals(
            "genre 12 and keyword 12 are two pages",
            BrowseRailCache.keyOf("genre", 12),
            BrowseRailCache.keyOf("keyword", 12)
        )
        assertNotEquals(
            "a network and a company can share a number",
            BrowseRailCache.keyOf("studio", 12, true),
            BrowseRailCache.keyOf("studio", 12, false)
        )
        assertNotEquals(
            "the same brand without a chip and with one are two pages",
            BrowseRailCache.keyOf("studio", 12, true, null, null, false, null, "HBO", null),
            BrowseRailCache.keyOf("studio", 12, true, null, null, false, null, "HBO", 18)
        )
        assertNotEquals(
            "a decade and a decade narrowed by a chip are two pages",
            BrowseRailCache.keyOf("decade", 1990, null),
            BrowseRailCache.keyOf("decade", 1990, 18)
        )
        assertEquals(
            "the same route answers the same key every time",
            BrowseRailCache.keyOf("genre", 12),
            BrowseRailCache.keyOf("genre", 12)
        )
    }

    // ── the rules the pages publish through ────────────────────────────────

    @Test
    fun `slots publish in declared index order, whatever order they arrived in`() {
        val slots =
            mapOf(
                2 to section("SERIES · TOP RATED", 3),
                0 to section("MOVIES · RECENT", 0),
                1 to section("MOVIES · POPULAR", 1)
            )

        assertEquals(
            listOf("MOVIES · RECENT", "MOVIES · POPULAR", "SERIES · TOP RATED"),
            sectionsInRailOrder(slots).map { it.title }
        )
    }

    @Test
    fun `a refresh drops a rail it no longer has and keeps the others at their index`() {
        val cached =
            slots(
                "MOVIES · RECENT" to 0,
                "MOVIES · POPULAR" to 1,
                "SERIES · RECENT" to 2
            )

        // The refresh came back with the first and third rails only: the middle
        // one has no rows any more, and an empty rail is never published (so it
        // would otherwise leave its cached copy behind). The first rail grew,
        // and the stream wrote it into its slot before this runs.
        val grown = section("MOVIES · RECENT", 0, "grown")
        val live = cached.toMutableMap()
        live[0] = grown

        val refreshed =
            refreshedSlots(
                live,
                listOf(
                    grown,
                    section("SERIES · RECENT", 2)
                )
            )

        assertEquals(
            "the dropped rail is gone, and the survivor keeps its declared index " +
                "so the page cannot reshuffle",
            setOf(0, 2),
            refreshed.keys
        )
        assertEquals(
            "SERIES · RECENT" to 2,
            refreshed.getValue(2).title to 2
        )
        assertTrue(
            "the rail that came back with more rows is the fresh one",
            refreshed.getValue(0).items.single().item.title!!.endsWith("grown")
        )
    }

    @Test
    fun `a refresh that answers nothing stores nothing`() {
        assertEquals(
            emptyMap<Int, StudioSection>(),
            refreshedSlots(slots("MOVIES · RECENT" to 0), emptyList())
        )
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private fun slots(vararg at: Pair<String, Int>): Map<Int, StudioSection> =
        at.associate { (title, index) -> index to section(title, index) }

    private fun section(
        title: String,
        index: Int,
        marker: String = "row"
    ): StudioSection = StudioSection(
        title,
        listOf(
            StudioItem(
                TmdbDiscoverItem(id = index * 10 + 1, title = "$title $marker"),
                "movie"
            )
        )
    )
}
