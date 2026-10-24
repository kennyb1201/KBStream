package com.kennyb1201.kbstream.ui.sports

import com.kennyb1201.kbstream.data.sports.GameState
import com.kennyb1201.kbstream.data.sports.SportsLeagues
import com.kennyb1201.kbstream.data.sports.involvesFavorite

/**
 * Which leagues one live poll must refetch, and when there is nothing left to
 * poll for.
 *
 * Pulled out of the ViewModel and made pure so the rule the viewer feels - the
 * score on the game they are watching keeps moving, and the hub stops asking
 * ESPN the moment nothing is in play - is testable without an Android
 * Application, a network or a clock. The ViewModel owns the Job and the 30s
 * beat; this decides whether that Job should exist at all and what it fetches.
 *
 * Only the tab in front of the viewer is polled. A hub left open on NFL must not
 * spend a request a minute re-reading the NHL's board, which is the whole
 * reason the poll is keyed on the selection rather than on every enabled
 * league.
 */
internal object SportsLivePollRules {

    /**
     * The poll's beat. ESPN's own site refreshes at a similar cadence; going
     * faster risks throttling and buys the viewer nothing, since a score does
     * not change between two 30-second ticks.
     */
    const val POLL_INTERVAL_MS = 30_000L

    /**
     * How far ahead of a start time the tick arms for a league that has nothing
     * in play yet.
     *
     * A hub left open on a league whose games are all still UPCOMING used to be
     * polled by nobody - the live set was empty, so the tick was never armed -
     * and kickoff passed unnoticed: the card kept reading UPCOMING (with no
     * score) until the viewer hit REFRESH or left and re-entered the hub. Five
     * minutes is a handful of 30s beats before the first snap, and the arm
     * releases the moment the game is LIVE (which is [hasLive]'s job from then
     * on).
     */
    const val KICKOFF_ARM_MS = 5 * 60_000L

    /** True when a section has anything in play right now. */
    fun hasLive(section: LeagueSection?): Boolean {
        if (section == null) return false
        return section.games.any { it.state == GameState.LIVE } ||
            section.tournaments.any { it.state == GameState.LIVE }
    }

    /**
     * True when [section] has a game or tournament that is starting - or that
     * ESPN still reports as UPCOMING even though its start has passed, which is
     * exactly the state a hub sitting on the upcoming slate lands in at kickoff.
     *
     * An undated item (`dateMs <= 0`) cannot arm anything: there is no number to
     * compare, and treating "no date" as "now" would poll every TBD fixture for
     * ever. `nowMs <= 0` disables the arm entirely - it means "the caller has no
     * clock", and is what keeps every other use of these rules identical.
     */
    fun hasKickoffArmed(section: LeagueSection?, nowMs: Long): Boolean {
        if (section == null || nowMs <= 0L) return false
        return section.games.any { isKickoffArmed(it.state, it.dateMs, nowMs) } ||
            section.tournaments.any { isKickoffArmed(it.state, it.dateMs, nowMs) }
    }

    /** The one-item form of [hasKickoffArmed], for the favourites filter. */
    private fun isKickoffArmed(state: GameState, dateMs: Long, nowMs: Long): Boolean =
        state == GameState.UPCOMING && dateMs > 0L && dateMs <= nowMs + KICKOFF_ARM_MS

    /**
     * How long the poll should sleep, when nothing in its tab is live, until
     * that tab's next scheduled start - or null when the tab has none left.
     *
     * The loop used to simply END the first beat that found nothing live and no
     * kickoff inside [KICKOFF_ARM_MS], and that is the doubleheader bug reported
     * on 2026-10-23: game 1 goes final, game 2 tips off forty minutes later, and
     * a hub left open on that tab never polls again - game 2 sits there reading
     * UPCOMING while it is being played, until a manual refresh or a tab switch.
     * Waiting for the next start is the smallest correct answer: the tick wakes
     * exactly when there is something new to see, and stays asleep for the gap.
     *
     * Undated items cannot arm anything, exactly as in [hasKickoffArmed]. The
     * wait is clamped to [MAX_REARM_MS] so a game two days out is re-checked
     * rather than slept through (an ESPN date can move), and floored at the
     * poll interval so a start that has just passed cannot spin the loop.
     */
    fun delayUntilNextStartMs(
        sections: List<LeagueSection>,
        paths: List<String>,
        nowMs: Long,
    ): Long? {
        if (nowMs <= 0L) return null
        val nextStart = paths
            .mapNotNull { path ->
                val section = sections.firstOrNull { it.league.path == path }
                    ?: return@mapNotNull null
                val games = section.games
                    .filter { it.state == GameState.UPCOMING && it.dateMs > nowMs }
                    .minOfOrNull { it.dateMs } ?: Long.MAX_VALUE
                val tournaments = section.tournaments
                    .filter { it.state == GameState.UPCOMING && it.dateMs > nowMs }
                    .minOfOrNull { it.dateMs } ?: Long.MAX_VALUE
                minOf(games, tournaments).takeIf { it != Long.MAX_VALUE }
            }
            .minOrNull()
            ?: return null
        return (nextStart - nowMs).coerceIn(POLL_INTERVAL_MS, MAX_REARM_MS)
    }

    /**
     * The furthest ahead the tick will sleep for one unplayed start. Six hours:
     * long enough to cover a doubleheader's gap or an evening's break, short
     * enough that a date ESPN moved is noticed the same day.
     */
    const val MAX_REARM_MS = 6L * 60L * 60_000L

    /**
     * The leagues one poll must refetch for the tab the viewer is on, in the
     * order the sections are in. Empty means "do not poll at all".
     *
     * The FAVORITES tab is a view over the enabled leagues, so its live set is
     * every section holding a live game that involves a followed team. Games
     * only, deliberately: a tournament field is not a team and cannot be
     * followed, so a live Grand Prix has nothing to do with this tab.
     */
    fun liveLeagues(
        sections: List<LeagueSection>,
        selectedPath: String?,
        favoriteKeys: Set<String>,
        nowMs: Long = 0L,
    ): List<String> {
        if (selectedPath == null) return emptyList()
        if (selectedPath == SportsLeagues.FAVORITES_PATH) {
            return sections
                .filter { section ->
                    section.games.any {
                        it.involvesFavorite(favoriteKeys) &&
                            (it.state == GameState.LIVE ||
                                isKickoffArmed(it.state, it.dateMs, nowMs))
                    }
                }
                .map { it.league.path }
        }
        val section = sections.firstOrNull { it.league.path == selectedPath } ?: return emptyList()
        return if (hasLive(section) || hasKickoffArmed(section, nowMs)) {
            listOf(selectedPath)
        } else {
            emptyList()
        }
    }
}
