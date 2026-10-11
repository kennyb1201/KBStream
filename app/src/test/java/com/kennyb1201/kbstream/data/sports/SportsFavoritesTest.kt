package com.kennyb1201.kbstream.data.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Following a team: the key it is stored under, and the rule that decides
 * whether a game is one of yours.
 *
 * Both live on the data model rather than in the hub, because "who do I follow"
 * has to mean the same thing on the FAVORITES tab, in the card's star and in the
 * follow dialog - and a favourite outlives the process, so the key it is
 * written under is a format, not a detail.
 *
 * The format carries a SCOPE, and the tests that matter here are the ones that
 * pin it. ESPN's ids are unique within a league and nowhere else: id 13 is the
 * NBA's Lakers AND the NFL's Raiders, so a favourite stored as a bare id is a
 * favourite of every team sharing it - the reported "there's random teams I
 * didn't mark as favorite favorited", and the other half of "favorites aren't
 * always showing up first", since the wrong league's game floats to the top
 * with the star on it.
 */
class SportsFavoritesTest {

    private fun team(
        id: String? = null,
        displayName: String = "Tampa Bay Buccaneers",
        abbreviation: String = "TB",
        league: String? = "football/nfl",
    ) = SportsTeam(
        id = id,
        abbreviation = abbreviation,
        displayName = displayName,
        logoUrl = null,
        score = null,
        isHome = false,
        record = null,
        league = league,
    )

    private fun game(
        away: SportsTeam,
        home: SportsTeam,
        league: String = "football/nfl",
    ) = SportsGame(
        id = "1",
        league = league,
        name = "away at home",
        dateMs = 0L,
        state = GameState.UPCOMING,
        statusDetail = "",
        away = away,
        home = home,
        broadcastNames = emptyList(),
    )

    @Test
    fun `a team is stored under ESPN's own id, scoped to its league`() {
        assertEquals("football/nfl:27", team(id = "27").favoriteKey)
    }

    @Test
    fun `the same id in another league is a different favourite`() {
        // The reported bug, at the rule that causes it: id 13 is both the NBA's
        // Lakers and the NFL's Raiders, so the bare id cannot be the key - the
        // star would appear on a side the viewer never picked and its game would
        // lead the slate.
        val lakers = team(id = "13", displayName = "Los Angeles Lakers", league = "basketball/nba")
        val raiders = team(id = "13", displayName = "Las Vegas Raiders", league = "football/nfl")

        assertNotEquals(lakers.favoriteKey, raiders.favoriteKey)

        val lakersGame = game(
            away = lakers,
            home = team(id = "2", displayName = "Boston Celtics", league = "basketball/nba"),
            league = "basketball/nba",
        )
        val raidersGame = game(
            away = raiders,
            home = team(id = "12", displayName = "Kansas City Chiefs"),
        )

        assertTrue(lakersGame.involvesFavorite(setOf(lakers.favoriteKey)))
        assertFalse(
            "following the Lakers must not follow the Raiders",
            raidersGame.involvesFavorite(setOf(lakers.favoriteKey))
        )
        assertFalse(
            "nor must the bare id an older build wrote match either league",
            raidersGame.involvesFavorite(setOf("13"))
        )
    }

    @Test
    fun `a team the feed gives no id for is stored under its display name`() {
        // A person, on the days ESPN omits the id: the name is the only stable
        // thing left, and it is normalised so a favourite survives the feed
        // re-casing it - and scoped the same way an id is.
        assertEquals(
            "tennis/atp:carlos alcaraz",
            team(id = null, displayName = "  Carlos Alcaraz ", league = "tennis/atp").favoriteKey
        )
        assertEquals(
            "tennis/atp:carlos alcaraz",
            team(id = "  ", displayName = "Carlos Alcaraz", league = "tennis/atp").favoriteKey
        )
    }

    @Test
    fun `a side with no league is keyed bare, the pre-scope shape`() {
        // Only a hand-built side does this - the parser always carries the
        // league (see espnTeamOf). Pinned so the fallback stays deliberate
        // rather than becoming the shape a real feed silently falls back to.
        assertEquals("27", team(id = "27", league = null).favoriteKey)
    }

    @Test
    fun `a game is a favourite when either side is followed`() {
        val away = team(id = "27", displayName = "Tampa Bay Buccaneers")
        val home = team(id = "18", displayName = "New Orleans Saints")
        val fixture = game(away = away, home = home)

        assertTrue(fixture.involvesFavorite(setOf("football/nfl:18")))
        assertTrue(fixture.involvesFavorite(setOf("football/nfl:27")))
        assertTrue(fixture.involvesFavorite(setOf("football/nfl:18", "football/nfl:27")))
    }

    @Test
    fun `a game between two strangers is not`() {
        val fixture = game(away = team(id = "27"), home = team(id = "18"))

        assertFalse(fixture.involvesFavorite(emptySet()))
        assertFalse(fixture.involvesFavorite(setOf("football/nfl:3", "football/nfl:12")))
        // The nickname is not the key, and neither is the bare id: a favourite
        // written under either must not match a scoped team, or the star would
        // appear on the wrong side of the grid.
        assertFalse(fixture.involvesFavorite(setOf("tampa bay buccaneers")))
        assertFalse(fixture.involvesFavorite(setOf("27")))
    }

    @Test
    fun `a tournament is keyed by its own name, not its per-round id`() {
        // The inversion of the team rule, on purpose: a golf tournament's id is
        // per-round on some feeds, so a pick keyed on it would be forgotten
        // between days, while the name is the same string all week.
        val event = TournamentEvent(
            id = "401",
            league = "golf/pga",
            name = "  Baycurrent Classic ",
            dateMs = 0L,
            state = GameState.LIVE,
            statusDetail = "",
            leaders = emptyList(),
            broadcastNames = emptyList(),
        )

        assertEquals("baycurrent classic", event.favoriteKey)
    }

    @Test
    fun `a tournament the feed gives no name falls back to its id`() {
        val event = TournamentEvent(
            id = "401",
            league = "golf/pga",
            name = "   ",
            dateMs = 0L,
            state = GameState.LIVE,
            statusDetail = "",
            leaders = emptyList(),
            broadcastNames = emptyList(),
        )

        assertEquals("401", event.favoriteKey)
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
