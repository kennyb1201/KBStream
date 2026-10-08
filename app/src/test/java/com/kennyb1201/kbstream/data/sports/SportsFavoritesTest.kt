package com.kennyb1201.kbstream.data.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Following a team: the key it is stored under, and the rule that decides
 * whether a game is one of yours.
 *
 * Both live on the data model rather than in the hub, because "who do I follow"
 * has to mean the same thing on the FAVORITES tab, in the card's star and in
 * the follow dialog - and a favourite outlives the process, so the key it is
 * written under is a format, not a detail.
 */
class SportsFavoritesTest {

    private fun team(
        id: String? = null,
        displayName: String = "Tampa Bay Buccaneers",
        abbreviation: String = "TB",
    ) = SportsTeam(
        id = id,
        abbreviation = abbreviation,
        displayName = displayName,
        logoUrl = null,
        score = null,
        isHome = false,
        record = null,
    )

    private fun game(away: SportsTeam, home: SportsTeam) = SportsGame(
        id = "1",
        league = "football/nfl",
        name = "away at home",
        dateMs = 0L,
        state = GameState.UPCOMING,
        statusDetail = "",
        away = away,
        home = home,
        broadcastNames = emptyList(),
    )

    @Test
    fun `a team is stored under ESPN's own id`() {
        assertEquals("27", team(id = "27").favoriteKey)
    }

    @Test
    fun `a team the feed gives no id for is stored under its display name`() {
        // A person, on the days ESPN omits the id: the name is the only stable
        // thing left, and it is normalised so a favourite survives the feed
        // re-casing it.
        assertEquals(
            "carlos alcaraz",
            team(id = null, displayName = "  Carlos Alcaraz ").favoriteKey
        )
        assertEquals("carlos alcaraz", team(id = "  ", displayName = "Carlos Alcaraz").favoriteKey)
    }

    @Test
    fun `a game is a favourite when either side is followed`() {
        val away = team(id = "27", displayName = "Tampa Bay Buccaneers")
        val home = team(id = "18", displayName = "New Orleans Saints")
        val fixture = game(away = away, home = home)

        assertTrue(fixture.involvesFavorite(setOf("18")))
        assertTrue(fixture.involvesFavorite(setOf("27")))
        assertTrue(fixture.involvesFavorite(setOf("18", "27")))
    }

    @Test
    fun `a game between two strangers is not`() {
        val fixture = game(away = team(id = "27"), home = team(id = "18"))

        assertFalse(fixture.involvesFavorite(emptySet()))
        assertFalse(fixture.involvesFavorite(setOf("3", "12")))
        // The nickname is not the key: a favourite written under a name must not
        // match a team that has an id, or the star would appear on the wrong
        // side of the grid.
        assertFalse(fixture.involvesFavorite(setOf("tampa bay buccaneers")))
    }

    @Test
    fun `the favourites tab is not one of the leagues`() {
        // The tab is a view over the enabled leagues; if it ever leaked into the
        // catalog it would be listed in the leagues panel as a switchable league
        // and fetched from ESPN as a path that does not exist.
        assertTrue(SportsLeagues.ALL.none { it.path == SportsLeagues.FAVORITES_PATH })
        assertTrue(SportsLeagues.enabled(setOf(SportsLeagues.FAVORITES_PATH)).isEmpty())
        assertEquals("Favorites", SportsLeagues.FAVORITES.label)
    }
}
