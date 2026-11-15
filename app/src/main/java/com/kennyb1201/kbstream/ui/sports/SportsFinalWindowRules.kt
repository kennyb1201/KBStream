package com.kennyb1201.kbstream.ui.sports

import com.kennyb1201.kbstream.data.sports.GameState
import com.kennyb1201.kbstream.data.sports.SportsGame

/**
 * How long a final stays on the hub (spec 2026-11-15, Sports Hub Favorites FINAL).
 *
 * The field: the 2026 World Series, ended October 28, was still the first card in
 * the Favorites tab's FINAL section eighteen days later. Nothing was wrong with
 * the favorites filter or the poll - the section simply had no notion of a final
 * getting old. Whatever brings a stale final to the section (ESPN's offseason
 * scoreboard answers the season's last day, and the section keeps every state it
 * is handed), the hub keeps every final it has ever had, for every followed team,
 * for the rest of the season.
 *
 * So a final's usefulness is a WINDOW, not a state: the few days after the game,
 * when the viewer checks the score they missed. A week is generous on purpose - it
 * carries the whole weekend's finals through the week - and it is one constant,
 * because a viewer with a slider here is a viewer tuning a scoreboard.
 *
 * Only FINAL is aged out. LIVE is current by definition and UPCOMING is dated in
 * the future, so neither can ever be old - and the rules return them untouched
 * even if a feed dates one strangely.
 *
 * Pure, so a JVM test can pin the window, the edge and the two states it must not
 * touch without a feed, a page or a remote.
 */
internal object SportsFinalWindowRules {

    /** A final is carried for this long after its own date, then dropped. */
    const val FINAL_WINDOW_DAYS = 7L

    /** The cap on FINAL cards per section - the backstop, not the fix. */
    const val FINAL_CARD_CAP = 20

    private const val DAY_MS = 24L * 60L * 60L * 1000L

    /** The window in milliseconds. */
    val finalWindowMs: Long get() = FINAL_WINDOW_DAYS * DAY_MS

    /**
     * Whether [game] is a final old enough to drop, as of [nowMs].
     *
     * The boundary is inclusive of the window itself: a final dated exactly seven
     * days ago is still a score to check, and one a millisecond past it is not.
     * A feed that dates a final in the FUTURE reads as age 0 rather than a
     * negative one, which cannot drop it by accident.
     */
    fun isExpiredFinal(game: SportsGame, nowMs: Long): Boolean {
        if (game.state != GameState.FINAL) return false
        val age = (nowMs - game.dateMs).coerceAtLeast(0L)
        return age > finalWindowMs
    }

    /**
     * [games] without the finals past the window, in the order they came.
     *
     * Applied where a section's games are BUILT, not where they are drawn: the
     * section is the source of truth for the Favorites tab and every league tab
     * alike, so one filter here is the fix for all of them and a dropped final
     * cannot come back from the list, the count or the scroll anchor.
     */
    fun keepCurrent(games: List<SportsGame>, nowMs: Long): List<SportsGame> {
        if (games.isEmpty()) return games
        return games.filterNot { isExpiredFinal(it, nowMs) }
    }

    /**
     * The FINAL partition the section draws: [final] newest first, capped.
     *
     * The cap bounds a heavy weekend (many followed teams, many finals) even
     * inside the window, and the section's own count shows it, so nothing is
     * hidden from the viewer.
     */
    fun finalCards(final: List<SportsGame>): List<SportsGame> =
        final.sortedByDescending { it.dateMs }.take(FINAL_CARD_CAP)
}
