package com.kennyb1201.kbstream.ui.sports

import com.kennyb1201.kbstream.data.format.DateFormats
import com.kennyb1201.kbstream.data.sports.EspnGameSummary
import com.kennyb1201.kbstream.data.sports.GameState
import com.kennyb1201.kbstream.data.sports.SportsGame
import com.kennyb1201.kbstream.data.sports.TournamentEvent

/** One labelled line of the game-detail sheet. */
data class GameDetailRow(val label: String, val value: String)

/**
 * Everything the game detail sheet renders, as pure functions over an
 * already-loaded [SportsGame].
 *
 * The sheet fetches nothing - it is a view over the card's own game - so its
 * whole job is choosing what to draw and in what words, and that is exactly the
 * part worth pinning in a unit test. The status line is the card's own, shared
 * with it rather than re-spelled, so the sheet and the card can never disagree
 * about what "Final" or a kick-off time reads as.
 */
internal object SportsDetailRules {

    /**
     * The matchup, headline style: "Yankees at Red Sox", the feed's own event
     * name where it has one and the two sides otherwise.
     */
    fun matchupTitle(game: SportsGame): String =
        game.name.takeIf { it.isNotBlank() }
            ?: listOf(game.away.displayName, game.home.displayName)
                .filter { it.isNotBlank() }
                .joinToString(" at ")

    /**
     * Live clock / start time / "Final" - the same strings the card draws, from
     * the same helper.
     */
    fun statusLine(game: SportsGame): String = when (game.state) {
        GameState.LIVE -> game.statusDetail.ifBlank { "LIVE" }
        GameState.FINAL -> "Final"
        GameState.UPCOMING -> upcomingLabel(game.dateMs, game.statusDetail)
    }

    /**
     * A tournament's own status line - "Round 1 - Play Complete", the tee time,
     * "Final" - from the same strings the card draws. A tournament has no sides
     * and no score, so this is the whole of its state, and the sheet says it
     * exactly as the card does.
     */
    fun statusLine(event: TournamentEvent): String = when (event.state) {
        GameState.LIVE -> event.statusDetail.ifBlank { "LIVE" }
        GameState.FINAL -> "Final"
        GameState.UPCOMING -> upcomingLabel(event.dateMs, event.statusDetail)
    }

    /**
     * "3 - 2" once there is a score; "vs" before there is one, so an upcoming
     * fixture never reads as a 0-0.
     */
    fun scoreLine(game: SportsGame): String {
        val away = game.away.score?.takeIf { it.isNotBlank() }
        val home = game.home.score?.takeIf { it.isNotBlank() }
        if (away == null && home == null) return "vs"
        return "${away ?: "-"} - ${home ?: "-"}"
    }

    /**
     * The detail rows, in order, with every blank omitted entirely rather than
     * drawn as an empty line: venue, then broadcast, then the week/round line.
     * A game with none of the three yields an empty list and the sheet draws no
     * rows at all.
     */
    fun detailRows(game: SportsGame): List<GameDetailRow> = buildList {
        game.venue?.takeIf { it.isNotBlank() }?.let { add(GameDetailRow("Venue", it)) }
        game.broadcastNames.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let { names ->
            add(GameDetailRow("Broadcast", names.joinToString(", ")))
        }
        // The week where the feed numbers one (football), else ESPN's own note
        // as the round ("ALDS - Game 4"). Never both: the card prefers the week,
        // and the sheet tells the same story.
        val week = game.week?.takeIf { it.isNotBlank() }
        if (week != null) {
            add(GameDetailRow("Week", week))
        } else {
            game.note?.takeIf { it.isNotBlank() }?.let { add(GameDetailRow("Round", it)) }
        }
    }

    /**
     * A tournament's detail rows, from the same field rules the game sheet uses:
     * venue, then broadcast, then ESPN's own context line (a golf round's
     * "Round 2", an F1 weekend's session). Blank fields are omitted rather than
     * drawn as empty lines, so a stop with no venue and no note yields fewer
     * rows instead of a sheet of gaps.
     */
    fun detailRows(event: TournamentEvent): List<GameDetailRow> = buildList {
        event.venue?.takeIf { it.isNotBlank() }?.let { add(GameDetailRow("Venue", it)) }
        event.broadcastNames.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let { names ->
            add(GameDetailRow("Broadcast", names.joinToString(", ")))
        }
        event.note?.takeIf { it.isNotBlank() }?.let { add(GameDetailRow("Event", it)) }
    }

    /**
     * One line per leader, prefixed by the side they play for so the two lines
     * read as "one for each team": "LAL · LeBron James — 28 PTS, 11 REB". Empty
     * when the feed carried no leaders.
     */
    fun leaderLines(game: SportsGame): List<String> = game.leaders.map { leader ->
        val side = leader.teamAbbreviation.trim().takeIf { it.isNotEmpty() }?.let { "$it · " }.orEmpty()
        val summary = leader.summary.trim().takeIf { it.isNotEmpty() }?.let { " — $it" }.orEmpty()
        "$side${leader.name}$summary"
    }

    /**
     * Whether the sheet's LIVE STATS section is drawn at all.
     *
     * The section is skipped WHOLE when the summary has nothing in it - every
     * field null or empty - so a live game ESPN carries no stats for (or one
     * whose summary never arrived) looks exactly like today's sheet: no empty
     * heading, no placeholder rows, no "no stats" line. The three parts are
     * independent, so a soccer summary with stats and no win probability draws
     * its table and no bar.
     */
    fun hasLiveStats(summary: EspnGameSummary?): Boolean {
        if (summary == null) return false
        return summary.homeWinProbability != null ||
            summary.teamStats.isNotEmpty() ||
            !summary.lastPlay.isNullOrBlank()
    }

    /**
     * The Watch button's label: enabled, or the honest reason it is not.
     *
     * "Not in your playlist" is a claim about the viewer's own lineup, so it is
     * only made when there was a lineup to check against. A hub that could not
     * read one at all says that instead - the alternative is accusing every
     * game on the slate of missing when the fault is a playlist read.
     *
     * [lineupMissing] defaults to false, which is the state every other caller
     * is in: a lineup was read and simply does not carry this game. It is only
     * true once the hub KNOWS it has no lineup - a read still in flight is not
     * an answer and must not be worded as one.
     *
     * [matchingDone] defaults to true, the state of every caller that is looking
     * at an answer rather than a pass in progress. While it is false the hub is
     * still matching, so the button says so ("Finding channel…") rather than
     * claiming the game is not carried - the same honest loading state the card's
     * own channel line shows, so a tap during the 40-60s load explains itself.
     */
    fun watchLabel(
        hasChannel: Boolean,
        lineupMissing: Boolean = false,
        matchingDone: Boolean = true
    ): String = when {
        hasChannel -> "WATCH"
        !matchingDone -> "Finding channel…"
        lineupMissing -> "Lineup not loaded"
        else -> "Not in your playlist"
    }

    /** "Tue, 7:30 PM" in the device's zone, plus ESPN's own detail if it adds any. */
    private fun upcomingLabel(dateMs: Long, statusDetail: String): String {
        val when_ = if (dateMs > 0L) {
            DateFormats.time(dateMs, DateFormats.WEEKDAY_CLOCK_12H)
        } else {
            ""
        }
        val detail = statusDetail.trim()
        // ESPN's "7:30 PM ET" duplicates the clock we just formatted; a detail
        // that is only a time adds nothing, so it is only shown when it says
        // something else (a delayed start, a suspension).
        return when {
            when_.isBlank() -> detail.ifBlank { "Today" }
            detail.isBlank() || detail.contains("PM", ignoreCase = true) ||
                detail.contains("AM", ignoreCase = true) -> when_
            else -> "$when_ • $detail"
        }
    }
}
