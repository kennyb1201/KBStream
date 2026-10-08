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

    /** True when a section has anything in play right now. */
    fun hasLive(section: LeagueSection?): Boolean {
        if (section == null) return false
        return section.games.any { it.state == GameState.LIVE } ||
            section.tournaments.any { it.state == GameState.LIVE }
    }

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
    ): List<String> {
        if (selectedPath == null) return emptyList()
        if (selectedPath == SportsLeagues.FAVORITES_PATH) {
            return sections
                .filter { section ->
                    section.games.any {
                        it.state == GameState.LIVE && it.involvesFavorite(favoriteKeys)
                    }
                }
                .map { it.league.path }
        }
        val section = sections.firstOrNull { it.league.path == selectedPath } ?: return emptyList()
        return if (hasLive(section)) listOf(selectedPath) else emptyList()
    }
}
