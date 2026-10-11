package com.kennyb1201.kbstream.data.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The adoption of the league-scoped favourite key: rewriting a store written
 * before the scope existed against the slate the hub just loaded.
 *
 * The keys used to be a team's bare ESPN id. The scope is what stops one
 * league's favourite from starring another league's team, but it also means an
 * old key would never match again - so without this rewrite a viewer's teams
 * would go quiet instead of following them onto the new format. What is pinned
 * here is therefore the two halves of that: a key that names a side on the
 * slate migrates to that side's scoped key (both of them when an id means a
 * team in two leagues), and a key that names nothing is handed back rather than
 * dropped.
 */
class SportsFavoriteKeysTest {

    private fun team(
        id: String? = null,
        displayName: String = "Los Angeles Lakers",
        league: String = "basketball/nba",
    ) = SportsTeam(
        id = id,
        abbreviation = "LAL",
        displayName = displayName,
        logoUrl = null,
        score = null,
        isHome = false,
        record = null,
        league = league,
    )

    private fun game(league: String, away: SportsTeam, home: SportsTeam) = SportsGame(
        id = "g-$league",
        league = league,
        name = "${away.displayName} at ${home.displayName}",
        dateMs = 0L,
        state = GameState.UPCOMING,
        statusDetail = "",
        away = away,
        home = home,
        broadcastNames = emptyList(),
    )

    private val lakersGame = game(
        league = "basketball/nba",
        away = team(id = "13"),
        home = team(id = "2", displayName = "Boston Celtics"),
    )

    private val raidersGame = game(
        league = "football/nfl",
        away = team(id = "13", displayName = "Las Vegas Raiders", league = "football/nfl"),
        home = team(id = "12", displayName = "Kansas City Chiefs", league = "football/nfl"),
    )

    @Test
    fun `a legacy bare id is rewritten to the scoped key of the side it names`() {
        assertEquals(
            setOf("basketball/nba:13"),
            SportsFavoriteKeys.migrate(setOf("13"), listOf(lakersGame))
        )
    }

    @Test
    fun `an id that means a team in two leagues expands to both, not to one of them`() {
        // The old store was ambiguous, and the rewrite resolves the ambiguity in
        // the viewer's favour: it keeps exactly the set they had, now spelled
        // out per league, rather than silently picking one team to keep.
        assertEquals(
            setOf("basketball/nba:13", "football/nfl:13"),
            SportsFavoriteKeys.migrate(setOf("13"), listOf(lakersGame, raidersGame))
        )
    }

    @Test
    fun `a legacy display-name key is scoped too`() {
        val tennis = game(
            league = "tennis/atp",
            away = team(id = null, displayName = "Carlos Alcaraz", league = "tennis/atp"),
            home = team(id = "900", displayName = "Alexander Zverev", league = "tennis/atp"),
        )

        assertEquals(
            setOf("tennis/atp:carlos alcaraz"),
            SportsFavoriteKeys.migrate(setOf("carlos alcaraz"), listOf(tennis))
        )
    }

    @Test
    fun `an already-scoped store is handed back, the same instance`() {
        // The ordinary case on every refresh after the first: the identity
        // return is what lets the caller skip the write entirely.
        val stored = setOf("basketball/nba:13")

        assertSame(stored, SportsFavoriteKeys.migrate(stored, listOf(lakersGame)))
    }

    @Test
    fun `a key naming nothing on the slate is kept, not dropped`() {
        // A team whose game is not on today's board must not lose its follow -
        // the rewrite runs on every refresh, so the key migrates the first time
        // its team appears.
        val stored = setOf("999")

        assertSame(stored, SportsFavoriteKeys.migrate(stored, listOf(lakersGame, raidersGame)))
    }

    @Test
    fun `nothing to rewrite is nothing to say`() {
        val nobodyFollowed = emptySet<String>()
        assertSame(nobodyFollowed, SportsFavoriteKeys.migrate(nobodyFollowed, listOf(lakersGame)))

        // No slate yet is the ordinary case on a hub whose fetch has not landed:
        // it must neither rewrite a key nor drop one.
        val legacy = setOf("13")
        assertSame(legacy, SportsFavoriteKeys.migrate(legacy, emptyList()))
    }

    @Test
    fun `the rewrite is idempotent, so a second refresh is a no-op`() {
        val once = SportsFavoriteKeys.migrate(setOf("13"), listOf(lakersGame, raidersGame))

        assertSame(once, SportsFavoriteKeys.migrate(once, listOf(lakersGame, raidersGame)))
    }
}
