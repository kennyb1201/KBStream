package com.kennyb1201.kbstream.ui.sports

import com.kennyb1201.kbstream.data.sports.StandingGroup

/**
 * How a league's table is split across the screen: one column, or two
 * conferences side by side.
 *
 * A two-conference league (NFL AFC/NFC, NBA and NHL East/West, MLB AL/NL) drawn
 * as one column is a full page of rows read on a TV; the same table split by
 * conference takes half the vertical scroll and keeps each conference a single
 * glance. A single-table league (the Premier League, MLS, the WNBA) has nothing
 * to split, and a college league's ten-plus conferences cannot sit in two
 * columns at all - both scroll as one list, grouped by conference as the feed
 * ordered them.
 *
 * Pure and separate from the composition so the rule the viewer sees is the one
 * a unit test can pin without a TV: the repository has already flattened the
 * conferences into their divisions, so all this needs is the conference each
 * group came from ([StandingGroup.conference]) and the screen width.
 */
internal object SportsStandingsLayout {

    /**
     * Below this width two tables side by side stop being readable, so the body
     * collapses to one column. The same threshold the game grid uses, so the hub
     * changes shape at ONE width rather than two.
     */
    const val TWO_COLUMN_MIN_WIDTH_DP = 900

    /**
     * The table's columns, in feed order.
     *
     * One column for a single-table league (a single group, or a league whose
     * groups do not fall into two conferences), for a narrow screen, and for a
     * league with more than two conferences - which can only be a long list.
     * Two columns when the groups belong to exactly two conferences: each
     * column is one conference's divisions, in order.
     */
    fun columns(groups: List<StandingGroup>, widthDp: Int): List<List<StandingGroup>> {
        if (groups.size <= 1) return listOf(groups)
        if (widthDp < TWO_COLUMN_MIN_WIDTH_DP) return listOf(groups)
        val byConference = LinkedHashMap<String, MutableList<StandingGroup>>()
        groups.forEach { group ->
            // A group the feed did not nest under a conference IS its own
            // top level (a flat "Eastern Conference" row), so it keys on its own
            // name; a division keys on the conference it came from.
            byConference.getOrPut(group.conference ?: group.name) { mutableListOf() } += group
        }
        return if (byConference.size == 2) byConference.values.map { it.toList() } else listOf(groups)
    }
}
