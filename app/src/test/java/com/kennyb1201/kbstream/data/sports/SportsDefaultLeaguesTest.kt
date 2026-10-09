package com.kennyb1201.kbstream.data.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hub ships with EVERY sport on, not a US-league starter set.
 *
 * The fault this pins: the default enabled set used to be the four US leagues,
 * so a viewer who watches soccer, tennis, UFC, golf, F1, the WNBA or college
 * ball found those tabs missing entirely - no fetch, no matching, no EPG lookup
 * - and the games their guide was carrying simply did not appear. The catalog
 * now defaults to itself, and these cases keep it that way: a league added to
 * [SportsLeagues.ALL] is on out of the box rather than invisible until someone
 * remembers to touch [SportsLeagues.DEFAULT_ENABLED] by hand.
 */
class SportsDefaultLeaguesTest {

    @Test
    fun `every league in the catalog is enabled by default`() {
        assertEquals(
            "the default must be the whole catalog, so no sport is hidden",
            SportsLeagues.ALL.size,
            SportsLeagues.DEFAULT_ENABLED.size
        )
        assertTrue(
            "and every catalog league must be in it",
            SportsLeagues.ALL.all { it.path in SportsLeagues.DEFAULT_ENABLED }
        )
    }

    @Test
    fun `the default is derived from the catalog rather than hand-written`() {
        assertEquals(
            "a new league is on by default, not added to a second list by hand",
            SportsLeagues.ALL.map { it.path }.toSet(),
            SportsLeagues.DEFAULT_ENABLED
        )
    }

    @Test
    fun `the favourites pseudo-league is never in the enabled set`() {
        assertTrue(
            "Favorites is a view over the leagues, not one of them",
            SportsLeagues.FAVORITES_PATH !in SportsLeagues.DEFAULT_ENABLED
        )
        assertTrue(
            "and the hub filters it out even if it were written into the store",
            SportsLeagues.enabled(SportsLeagues.DEFAULT_ENABLED + SportsLeagues.FAVORITES_PATH)
                .none { it.path == SportsLeagues.FAVORITES_PATH }
        )
    }

    @Test
    fun `the college leagues use ESPN's own long-form paths`() {
        // The shorthands `football/ncf` and `basketball/ncb` are not ESPN API
        // paths - the scoreboard endpoint answers HTTP 400 for both - so those
        // tabs could only ever show "Couldn't reach ESPN". Pinned here so a
        // "tidy-up" cannot quietly restore them.
        assertTrue(
            "college football must be football/college-football",
            "football/college-football" in SportsLeagues.DEFAULT_ENABLED
        )
        assertTrue(
            "college basketball must be basketball/mens-college-basketball",
            "basketball/mens-college-basketball" in SportsLeagues.DEFAULT_ENABLED
        )
        assertTrue(
            "and the 400-ing shorthands must be gone",
            listOf("football/ncf", "basketball/ncb")
                .none { it in SportsLeagues.DEFAULT_ENABLED }
        )
    }

    @Test
    fun `a fresh install renders a tab for every sport`() {
        assertEquals(
            "the default set must draw the whole catalog as tabs",
            SportsLeagues.ALL,
            SportsLeagues.enabled(SportsLeagues.DEFAULT_ENABLED)
        )
    }
}
