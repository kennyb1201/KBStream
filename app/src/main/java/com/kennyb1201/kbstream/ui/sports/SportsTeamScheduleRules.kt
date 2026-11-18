package com.kennyb1201.kbstream.ui.sports

import com.kennyb1201.kbstream.data.format.DateFormats
import com.kennyb1201.kbstream.data.sports.GameState
import com.kennyb1201.kbstream.data.sports.SportsGame
import com.kennyb1201.kbstream.data.sports.SportsTeam

/**
 * One team's season, grouped (spec audit 2026-11-18, Sports M2's second half).
 *
 * The hub had no team-centric view at all beyond the Favorites tab's copy of
 * today's slate: a followed team's next fixture, or the result from last week,
 * was nowhere in the app. The schedule endpoint answers the whole season, and
 * this is the one decision made over it - how a season reads.
 *
 * The pieces are the hub's own, with two deliberate departures:
 *
 *  - **No final window.** [SportsFinalWindowRules] ages a FINAL out of the
 *    SCOREBOARD after seven days, because a scoreboard is "what is on now". A
 *    season schedule is the opposite question - the results are the content - so
 *    every final the feed carries is kept.
 *  - **A real date order.** The scoreboard keeps the feed's own order inside its
 *    groups; a season is only readable chronologically, so upcoming games run
 *    soonest-first and finals newest-first, which is how a fan reads a schedule.
 *
 * Pure and free of project UI imports, so a JVM test can pin both orders and the
 * no-window rule without a feed, a page or a device.
 */
internal object SportsTeamScheduleRules {

    /** A season, split the way the team sheet draws it. */
    data class Groups(
        val live: List<SportsGame> = emptyList(),
        val upcoming: List<SportsGame> = emptyList(),
        val final: List<SportsGame> = emptyList(),
    ) {
        val isEmpty: Boolean get() = live.isEmpty() && upcoming.isEmpty() && final.isEmpty()

        val total: Int get() = live.size + upcoming.size + final.size
    }

    /**
     * [games] split into the three groups, in the order a schedule reads.
     *
     * Deduped by game id first: a season body occasionally repeats a fixture
     * (a rescheduled game appears on both its old and new date while ESPN's feed
     * catches up), and one game drawn twice in a schedule reads as two games.
     *
     * [nowMs] is taken for the signature's sake and left unused on purpose -
     * STATE decides the group, not the clock, exactly as it does on the hub's own
     * cards. A game ESPN has dated in the past but still calls UPCOMING is
     * upcoming; second-guessing the feed here would put the same game in two
     * groups on two surfaces.
     */
    fun groups(games: List<SportsGame>, nowMs: Long = System.currentTimeMillis()): Groups {
        val unique = games.distinctBy { it.id }
        return Groups(
            live = unique.filter { it.state == GameState.LIVE },
            // Soonest first: "when do they play next" is the question.
            upcoming = unique.filter { it.state == GameState.UPCOMING }
                .sortedWith(compareBy({ it.dateMs <= 0L }, { it.dateMs })),
            // Newest first: "what was the last result" is the question.
            final = unique.filter { it.state == GameState.FINAL }
                .sortedByDescending { it.dateMs }
        )
    }

    /**
     * Whether a team has what a schedule read needs.
     *
     * `teams/{id}/schedule` is keyed by ESPN's id, and the league path is half of
     * the request - a PERSON (a tennis player, a fighter) is keyed by name and
     * carries no id at all, so their side of a card must not offer a season it
     * could never fetch.
     */
    fun canOpen(team: SportsTeam): Boolean =
        !team.league.isNullOrBlank() && !team.id.isNullOrBlank()

    /**
     * A row's date: "Nov 22, 7:30 PM", or TBD for an undated fixture.
     *
     * The month and day, not the hub card's weekday: a card is read against
     * today ("Sat, 7:30 PM"), a season against the calendar, where the same
     * weekday comes round every week and names nothing.
     */
    fun dateLine(game: SportsGame): String =
        if (game.dateMs > 0L) {
            DateFormats.time(game.dateMs, DateFormats.MONTH_DAY_CLOCK_12H)
        } else {
            "TBD"
        }

    /**
     * "NYY @ BOS" - the matchup in the width a season row has.
     *
     * Abbreviations where the feed carries them, display names where it does
     * not (a soccer club, a college side with no code), and never both: the row
     * already has the date and the result on the same line.
     */
    fun matchupLine(game: SportsGame): String {
        val away = game.away.abbreviation.trim().ifBlank { game.away.displayName.trim() }
        val home = game.home.abbreviation.trim().ifBlank { game.home.displayName.trim() }
        return "$away @ $home"
    }

    /**
     * The line the empty state shows, or null when there is a season to draw.
     *
     * A team with no fixtures is a real answer in the offseason, and it is the
     * only reason a schedule sheet is empty that this app can name - the failure
     * case is the sheet's own error state, not a sentence here.
     */
    fun emptyLine(groups: Groups): String? =
        if (groups.isEmpty) "No games on this team's schedule yet." else null
}
