package com.kennyb1201.kbstream.data.notifications

import com.kennyb1201.kbstream.data.sports.GameState
import com.kennyb1201.kbstream.data.sports.SportsGame
import com.kennyb1201.kbstream.data.sports.SportsTeam
import com.kennyb1201.kbstream.data.sports.involvesFavorite

/**
 * "Your team plays in 15 minutes": which games are worth a reminder, what the
 * reminder says, and how a game is remembered so it is announced once.
 *
 * Pure, on purpose. The worker that runs this is a WorkManager round nobody can
 * watch in CI, and the three mistakes that actually matter here - announcing a
 * game that already started, re-announcing the same game on the next 30-minute
 * tick, and buzzing for a team nobody follows - are all decisions, not I/O, so
 * they live here where a unit test can pin them.
 */
internal object SportsGameReminderRules {

    /**
     * How far ahead a game counts as "starting soon". Match the worker's own
     * cadence: a game is announced on the first tick that sees it inside the
     * window, and the next tick is 30 minutes later, so a wider window would
     * just announce a game twice as far out as the reminder claims.
     */
    const val LEAD_WINDOW_MS = 30L * 60_000L

    /**
     * How long a game id is remembered after it was announced. A game has one
     * start time, so a day is generous; the point is only that the record does
     * not grow without bound.
     */
    const val DEDUPE_TTL_MS = 24L * 60L * 60_000L

    /**
     * Whether the reminder round should exist at all. Following is the opt-in:
     * no followed teams, no schedule.
     */
    fun shouldSchedule(favoriteKeys: Set<String>): Boolean = favoriteKeys.isNotEmpty()

    /**
     * The games to announce for [favoriteKeys] at [nowMs]: upcoming, involving a
     * followed team, and kicking off inside the lead window. Ordered by kick-off
     * so the soonest game is announced first.
     */
    fun dueGames(
        games: List<SportsGame>,
        favoriteKeys: Set<String>,
        nowMs: Long,
    ): List<SportsGame> =
        games.asSequence()
            .filter { it.state == GameState.UPCOMING }
            .filter { it.dateMs > nowMs && it.dateMs <= nowMs + LEAD_WINDOW_MS }
            .filter { it.involvesFavorite(favoriteKeys) }
            .sortedBy { it.dateMs }
            .toList()

    /** The notification's headline: "Yankees @ Red Sox". */
    fun reminderTitle(game: SportsGame): String =
        "${nickname(game.away)} @ ${nickname(game.home)}"

    /** The notification's body: "starts in 15 minutes", or "starting now". */
    fun reminderBody(game: SportsGame, nowMs: Long): String {
        val minutes = minutesUntil(game.dateMs, nowMs)
        return if (minutes <= 0) "starting now" else "starts in $minutes minutes"
    }

    /**
     * Whole minutes from [nowMs] to [dateMs], rounded up so a game 19m30s out
     * reads "20" rather than "19", and floored at 0 for one already at its time.
     */
    fun minutesUntil(dateMs: Long, nowMs: Long): Int {
        val delta = dateMs - nowMs
        if (delta <= 0L) return 0
        return ((delta + 59_999L) / 60_000L).toInt()
    }

    /**
     * A stable per-game notification id, namespaced so it cannot collide with
     * the new-episode and live-reminder alerts sharing the notification tray.
     */
    fun notificationId(gameId: String): Int =
        ("kbstream_sports_game:$gameId").hashCode()

    /**
     * True when [gameId] has not been announced within its TTL. [notifiedAtMs]
     * is the last time it was announced, or null if never.
     */
    fun shouldNotify(gameId: String, notifiedAtMs: Long?, nowMs: Long): Boolean {
        if (gameId.isBlank()) return false
        if (notifiedAtMs == null) return true
        return nowMs - notifiedAtMs >= DEDUPE_TTL_MS
    }

    /** Drops records older than the TTL, so the store cannot grow forever. */
    fun prune(notified: Map<String, Long>, nowMs: Long): Map<String, Long> =
        notified.filterValues { nowMs - it < DEDUPE_TTL_MS }

    /**
     * The short name a headline reads well with.
     *
     * ESPN's own short name first ("Yankees", "Red Sox") - it is the one place
     * the nickname is unambiguous, since taking the last word of the display
     * name turns Boston's into "Sox". Falling back to that last word where the
     * feed carries no short name, and to the code where there is no name at
     * all. Never blank.
     */
    private fun nickname(team: SportsTeam): String {
        team.shortName?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val last = team.displayName.trim().split(' ').lastOrNull { it.isNotBlank() }
        return last?.takeIf { it.length >= 2 } ?: team.abbreviation.ifBlank { team.displayName }
    }
}
