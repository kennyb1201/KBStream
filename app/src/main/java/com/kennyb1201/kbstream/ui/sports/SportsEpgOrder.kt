package com.kennyb1201.kbstream.ui.sports

import com.kennyb1201.kbstream.data.sports.SportsGame

/**
 * The order the EPG pass asks about matchups in.
 *
 * The pass gives the guide one indexed question per matchup - "is there a
 * program naming both of these teams?" - and stops at its ceiling ([SportsHubViewModel]'s
 * MAX_EPG_LOOKUPS), because a lookup is a database query and a slate is
 * unbounded. Walking the slate in slate order made the ceiling unfair: the games
 * arrive league by league, so on a college-football Saturday the early games of
 * the first league filled it and the LAST leagues - the ones the viewer just
 * switched tabs to - got no guide match at all, reading "Not in your playlist"
 * for a game the guide was carrying (deep dive F2, 2026-10-26).
 *
 * Round-robin across leagues instead: every league's first matchup is asked
 * before any league's second, so a reached ceiling costs each league its DEPTH
 * rather than costing the last leagues everything. The leagues keep the feed's
 * own order (see the section builder), and each league's matchups keep their
 * slate order, so the ordinary case - every league well under the ceiling -
 * is answered exactly as before; only the order of the questions changed.
 */
internal fun epgMatchupOrder(games: List<SportsGame>): List<SportsGame> {
    if (games.size < 2) return games
    val byLeague = LinkedHashMap<String, MutableList<SportsGame>>()
    games.forEach { game -> byLeague.getOrPut(game.league) { mutableListOf() }.add(game) }
    if (byLeague.size < 2) return games
    val out = ArrayList<SportsGame>(games.size)
    var depth = 0
    while (out.size < games.size) {
        var taken = false
        byLeague.values.forEach { league ->
            league.getOrNull(depth)?.let { game ->
                out.add(game)
                taken = true
            }
        }
        if (!taken) break
        depth++
    }
    return out
}
