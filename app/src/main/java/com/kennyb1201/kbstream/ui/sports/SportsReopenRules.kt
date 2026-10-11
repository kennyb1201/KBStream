package com.kennyb1201.kbstream.ui.sports

import com.kennyb1201.kbstream.data.sports.SportsGame
import com.kennyb1201.kbstream.data.sports.TournamentEvent

/**
 * The lookup behind the hub's one-shot "reopen the sheet you launched from".
 *
 * A game played from the hub's detail sheet is launched by that sheet with the
 * game's own id (see the hub branch in MainActivity and [SportsHubScreen]'s
 * `initialDetailGameId`), so a Back from the player returns to the sheet the
 * viewer pressed WATCH in rather than to the bare slate. The id has to be
 * resolved here, against the sections the hub has ALREADY loaded: the sheet
 * renders a game object, and nothing on this path may fetch - it is the same
 * no-fetch policy the sheet itself follows (see `SportsHubViewModel.openDetail`).
 *
 * Pure, and a file of its own rather than an inline `find`, because the case
 * that has to be right is the one a TV in CI cannot show: an id that names
 * nothing on the board - the game dropped off it while the viewer was watching -
 * must yield null so the hub renders normally, instead of an empty sheet. See
 * [SportsReopenRulesTest].
 */
internal object SportsReopenRules {

    /**
     * The game [id] names among [sections], or null when none of them carries
     * it - and null for a null [id] too, which is every way into the hub other
     * than a player's Back (a fresh hub from the guide's header, or a restored
     * one).
     */
    fun game(sections: List<LeagueSection>, id: String?): SportsGame? =
        id?.let { wanted ->
            sections.firstNotNullOfOrNull { section ->
                section.games.find { it.id == wanted }
            }
        }

    /** The tournament [id] names among [sections], or null. See [game]. */
    fun tournament(sections: List<LeagueSection>, id: String?): TournamentEvent? =
        id?.let { wanted ->
            sections.firstNotNullOfOrNull { section ->
                section.tournaments.find { it.id == wanted }
            }
        }
}
