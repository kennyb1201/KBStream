package com.kennyb1201.kbstream.ui.sports

import com.kennyb1201.kbstream.data.format.DateFormats
import com.kennyb1201.kbstream.data.sports.GameState
import com.kennyb1201.kbstream.data.sports.SportsGame

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
        GameState.UPCOMING -> upcomingLabel(game)
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
     * One line per leader, prefixed by the side they play for so the two lines
     * read as "one for each team": "LAL · LeBron James — 28 PTS, 11 REB". Empty
     * when the feed carried no leaders.
     */
    fun leaderLines(game: SportsGame): List<String> = game.leaders.map { leader ->
        val side = leader.teamAbbreviation.trim().takeIf { it.isNotEmpty() }?.let { "$it · " }.orEmpty()
        val summary = leader.summary.trim().takeIf { it.isNotEmpty() }?.let { " — $it" }.orEmpty()
        "$side${leader.name}$summary"
    }

    /** The Watch button's label: enabled, or the honest "not in your playlist". */
    fun watchLabel(hasChannel: Boolean): String =
        if (hasChannel) "WATCH" else "Not in your playlist"

    /** "Tue, 7:30 PM" in the device's zone, plus ESPN's own detail if it adds any. */
    private fun upcomingLabel(game: SportsGame): String {
        val when_ = if (game.dateMs > 0L) {
            DateFormats.time(game.dateMs, DateFormats.WEEKDAY_CLOCK_12H)
        } else {
            ""
        }
        val detail = game.statusDetail.trim()
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
