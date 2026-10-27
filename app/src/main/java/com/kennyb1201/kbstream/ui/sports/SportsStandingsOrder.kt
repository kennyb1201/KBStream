package com.kennyb1201.kbstream.ui.sports

import com.kennyb1201.kbstream.data.sports.StandingEntry
import com.kennyb1201.kbstream.data.sports.StandingGroup

/**
 * The order a league's table is read in: the best record at the top.
 *
 * ESPN does not send every standings document sorted the same way - the NBA's
 * arrives worst-first (the 0-3 Nets led the Eastern Conference, and the 0-3
 * teams filled the top of every group), and so does college basketball's, while
 * the NFL's and the WNBA's arrive best-first. The feed's own order is therefore
 * not a rule the hub can lean on, and a table read from the bottom is the one
 * report a standings screen must never produce.
 *
 * Every group is ordered by the figure its table is read on: league points where
 * the sport is ranked that way (soccer, the NHL), the win percentage everywhere
 * else.
 *
 * Stable, deliberately: equal records keep ESPN's order, because that is where
 * its own tiebreakers live (goal difference, division record, playoff seed). Only
 * the rows that are genuinely out of place move, so a table that arrives already
 * sorted is returned in exactly the order it arrived.
 *
 * Pure, and here rather than inline in the ViewModel, because the interesting
 * property is an ORDER and an order is arithmetic a unit test can pin exactly,
 * with no TV and no ESPN payload: see [SportsStandingsOrderTest].
 */
internal object SportsStandingsOrder {

    /**
     * [groups] with every group's rows in rank order, the groups themselves
     * untouched.
     *
     * Named apart from the row sort below on purpose: `List<StandingGroup>` and
     * `List<StandingEntry>` erase to the same JVM signature, so two `sorted`
     * overloads clash at compile time ("Platform declaration clash ...
     * sorted(Ljava/util/List;Z)"). The compiler caught it on the first CI run
     * of the release that added this file; the names keep the two call shapes
     * distinguishable to a reader as well.
     */
    fun sortedGroups(groups: List<StandingGroup>, pointsTable: Boolean): List<StandingGroup> =
        groups.map { group -> group.copy(entries = sorted(group.entries, pointsTable)) }

    /**
     * [entries] best record first.
     *
     * A value the feed left blank sorts LAST, not first: a table that publishes
     * no figure for a row (college football sends no win percentage before its
     * first conference game) must not be pushed to the top of the table on a
     * technicality. When the feed sends no figure for ANY row of a group, every
     * row is equal and the feed's own order stands, unchanged.
     */
    fun sorted(entries: List<StandingEntry>, pointsTable: Boolean): List<StandingEntry> =
        entries.sortedByDescending { entry -> rank(entry, pointsTable) }

    /** The one number this table ranks on, or negative infinity when it has none. */
    private fun rank(entry: StandingEntry, pointsTable: Boolean): Double =
        (if (pointsTable) entry.points else entry.winPercent)
            .toDoubleOrNull()
            ?: Double.NEGATIVE_INFINITY
}
