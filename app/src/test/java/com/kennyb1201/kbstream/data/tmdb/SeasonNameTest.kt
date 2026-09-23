package com.kennyb1201.kbstream.data.tmdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The season name the detail screen shows instead of the word "EPISODES".
 *
 * Reported problem: anthology shows (American Horror Story, Monster) name their
 * seasons, so a heading that only says EPISODES leaves the viewer matching the
 * chip's number against the season they remember by name.
 */
class SeasonNameTest {

    private fun show(vararg seasons: Pair<Int, String?>) = TmdbDetail(
        id = 1,
        name = "American Horror Story",
        seasons = seasons.map { (number, name) ->
            TmdbSeasonSummary(seasonNumber = number, name = name)
        }
    )

    @Test
    fun `a named season comes back as its name`() {
        val detail = show(1 to "Murder House", 3 to "Coven")

        assertEquals("Coven", detail.displaySeasonName(3))
        assertEquals("Murder House", detail.displaySeasonName(1))
    }

    @Test
    fun `a name TMDB only numbers is not a name`() {
        val detail = show(1 to "Season 1", 2 to "Season 2", 3 to "SEASON 3")

        assertNull(detail.displaySeasonName(1))
        assertNull(detail.displaySeasonName(2))
        // The generic check is case insensitive: TMDB's own casing varies.
        assertNull(detail.displaySeasonName(3))
    }

    @Test
    fun `specials are not a name`() {
        assertNull(show(0 to "Specials", 1 to "Season 1").displaySeasonName(0))
    }

    @Test
    fun `a season the metadata does not describe has no name`() {
        assertNull(show(1 to "Murder House").displaySeasonName(9))
        assertNull(show().displaySeasonName(1))
        assertNull(show(1 to null, 2 to "   ").displaySeasonName(1))
        assertNull(show(2 to "   ").displaySeasonName(2))
    }

    @Test
    fun `the name is trimmed`() {
        assertEquals("Coven", show(3 to "  Coven  ").displaySeasonName(3))
    }

    @Test
    fun `a real name that only looks numbered is kept`() {
        // A show may legitimately name a season something TMDB's placeholder
        // check must not swallow.
        assertEquals("Season Zero", show(0 to "Season Zero").displaySeasonName(0))
        assertEquals("Specials: Webisodes", show(0 to "Specials: Webisodes").displaySeasonName(0))
    }
}
