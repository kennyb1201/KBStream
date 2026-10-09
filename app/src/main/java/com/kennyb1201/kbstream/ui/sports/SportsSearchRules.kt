package com.kennyb1201.kbstream.ui.sports

import com.kennyb1201.kbstream.data.sports.SportsChannelMatcher
import com.kennyb1201.kbstream.data.sports.SportsGame
import com.kennyb1201.kbstream.data.sports.SportsKind
import com.kennyb1201.kbstream.data.sports.SportsLeague
import com.kennyb1201.kbstream.data.sports.TournamentEvent

/**
 * The hub's search, as one pure rule: what the query matches in the sections
 * the hub has ALREADY loaded.
 *
 * Deliberately a filter and nothing else. The spec is explicit that searching
 * must never fetch - "if a league hasn't loaded yet, it's skipped (not
 * fetched)" - and the only way to be sure of that is for the search to have no
 * way to ask for anything: this file takes sections and returns a view of them,
 * with no repository, no coroutine and no I/O in sight (see
 * [SportsSearchContractTest]).
 *
 * The matching itself is [SportsChannelMatcher.matchesQuery], not a second copy
 * of the team-name rules: search and the EPG tier must agree about what a team
 * is called, or a viewer would find a game by a name the matcher does not know.
 */
internal object SportsSearchRules {

    /**
     * The synthetic league a combined result row is labelled with.
     *
     * Not an ESPN path - no league is being viewed - so it is spelled where no
     * real path can collide, and it never reaches [SportsLeagues.enabled] or the
     * tab row: the results are a view over the loaded leagues, not one of them.
     */
    val RESULTS_LEAGUE: SportsLeague =
        SportsLeague("sports:search", "Search", SportsKind.HEAD_TO_HEAD)

    /** Whether [query] is a search at all (blank is \"not searching\"). */
    fun isActive(query: String): Boolean = query.isNotBlank()

    /** The games of [section] that the [query] names, in their own order. */
    fun games(section: LeagueSection, query: String): List<SportsGame> =
        if (!isActive(query)) emptyList()
        else section.games.filter { SportsChannelMatcher.matchesQuery(it, query) }

    /** The tournaments of [section] that the [query] names. */
    fun tournaments(section: LeagueSection, query: String): List<TournamentEvent> =
        if (!isActive(query)) emptyList()
        else section.tournaments.filter { SportsChannelMatcher.matchesQuery(it, query) }

    /**
     * The one section the hub draws for [query], or null when there is nothing
     * to draw - either because the query is blank (the hub draws its ordinary
     * view, unchanged) or because nothing matched (the hub says "No games
     * found").
     *
     * Every section in [sections] is read together, which is the spec's one
     * behavior: searching covers all enabled leagues rather than only the tab
     * in front of the viewer, because \"which tab was that game on?\" is the
     * question the field exists to avoid. A league the hub has not loaded yet
     * simply is not in [sections] and cannot match, and no fetch is made to
     * find out.
     *
     * Order is [sections]' own: results keep the leagues' (and the tab row's)
     * order, and within a league its games keep the order the hub built them
     * in.
     */
    fun results(
        sections: List<LeagueSection>,
        query: String,
        league: SportsLeague = RESULTS_LEAGUE
    ): LeagueSection? {
        if (!isActive(query)) return null
        val matchedGames = sections.flatMap { games(it, query) }
        val matchedEvents = sections.flatMap { tournaments(it, query) }
        if (matchedGames.isEmpty() && matchedEvents.isEmpty()) return null
        return LeagueSection(
            league = league,
            games = matchedGames,
            tournaments = matchedEvents
        )
    }

    /**
     * How many of [sections] hold a hit - the second figure on the results line
     * (\"12 results · 4 leagues\"), which is what tells a viewer whether the
     * thing they searched for is everywhere today or in one place.
     */
    fun leaguesWithHits(sections: List<LeagueSection>, query: String): Int =
        if (!isActive(query)) {
            0
        } else {
            sections.count { games(it, query).isNotEmpty() || tournaments(it, query).isNotEmpty() }
        }
}
