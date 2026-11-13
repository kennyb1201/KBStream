package com.kennyb1201.kbstream.ui.home

import java.time.LocalDate

/**
 * When Home offers the year in review, and which year it offers (spec
 * 2026-11-13, F1).
 *
 * December and January only: the review is a look back, and the two months
 * either side of New Year are when one is wanted - December on the year that is
 * ending, January on the one just gone. Outside that window the card would be a
 * permanent fixture among the rails, so it is not drawn at all; the Library
 * header's row and the Settings row are the year-round doors.
 *
 * Pure so the window is pinned by a test rather than by waiting until December.
 */
object YearInReviewCard {

    /** True from December 1 through January 31 inclusive. */
    fun inSeason(today: LocalDate): Boolean =
        today.monthValue == 12 || (today.monthValue == 1)

    /**
     * The year the seasonal card offers.
     *
     * January belongs to the year that ENDED, not the one two weeks old: on
     * Jan 5 2027 the card says "Your 2026 in review". December names the year
     * about to end.
     */
    fun yearFor(today: LocalDate): Int =
        if (today.monthValue == 1) today.year - 1 else today.year

    /** "Your 2026 in review" - the card's title for [year]. */
    fun title(year: Int): String = "Your $year in review"
}
