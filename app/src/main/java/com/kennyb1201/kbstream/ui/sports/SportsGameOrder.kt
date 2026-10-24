package com.kennyb1201.kbstream.ui.sports

import com.kennyb1201.kbstream.data.sports.SportsGame
import com.kennyb1201.kbstream.data.sports.involvesFavorite

/**
 * The order a league tab's cards are drawn in: the viewer's own games first.
 *
 * A game one of the followed teams is playing floats above the rest of its
 * group, and everything else keeps ESPN's own order - so following a team is
 * what makes its games easy to find without hiding anything, and a viewer who
 * follows nobody reads the slate exactly as the feed sent it.
 *
 * Pure, and here rather than inline in the ViewModel, because the interesting
 * property is an ORDER and an order is arithmetic a unit test can pin exactly,
 * with no TV and no ESPN payload: see [SportsGameOrderTest].
 */
internal object SportsGameOrder {

    /**
     * An UPCOMING group's order: the soonest start first, and an undated fixture
     * LAST.
     *
     * Sorting on the raw number put `dateMs` 0 at the very top of the group - so
     * a TBD fixture with no date at all led the viewer's next-games list, and the
     * label under it said "Today" (see SportsDetailRules.upcomingLabel). An
     * undated item is the one thing the feed could not say, never the next one
     * on. Everything dated keeps the feed's own order among equal times, as
     * before, because `sortedWith` is stable.
     */
    fun upcoming(games: List<SportsGame>): List<SportsGame> =
        games.sortedWith(compareBy({ it.dateMs <= 0L }, { it.dateMs }))

    /**
     * [games] with the followed ones first, by [keys] (a set of
     * [com.kennyb1201.kbstream.data.sports.SportsTeam.favoriteKey] strings).
     *
     * Stable, deliberately: Kotlin's `sortedWith` is, so favorites keep ESPN's
     * order among themselves and the rest keep it among themselves. That is
     * what stops the 30-second poll from churning the list - a card only ever
     * moves when its favorite status changes, which is the one event that
     * should move it.
     *
     * Empty [keys] returns [games] itself, not a copy: the no-favorites slate
     * is the feed's own list, instance for instance.
     */
    fun favoritesFirst(games: List<SportsGame>, keys: Set<String>): List<SportsGame> =
        if (keys.isEmpty()) {
            games
        } else {
            games.sortedWith(
                compareByDescending { game: SportsGame -> game.involvesFavorite(keys) }
            )
        }
}
