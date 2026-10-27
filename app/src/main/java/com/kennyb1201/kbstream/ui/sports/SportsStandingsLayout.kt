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
        val byConference = byConference(groups)
        return if (byConference.size == 2) byConference.values.map { it.toList() } else listOf(groups)
    }

    /**
     * The table's top-level groups - the conferences a viewer can pick from.
     *
     * A group the feed nested under a conference keys on that conference, so a
     * league that splits into divisions (the NFL's AFC East and AFC North) names
     * two conferences rather than eight divisions; a flat group IS its own top
     * level and keys on its own name, which is the college case - ESPN ships
     * every conference as a sibling under one document.
     */
    fun conferences(groups: List<StandingGroup>): List<String> =
        groups.map { it.conference ?: it.name }
            .filter { it.isNotBlank() }
            .distinct()

    /**
     * Whether the table is long enough to need a conference picker.
     *
     * Only a table with more than two top-level groups: those are the ones
     * [columns] cannot split, so they scroll as one list - and a college
     * document ships every conference it has (31 for basketball, 11 for
     * football), which is the giant list a viewer had to scroll past to reach the
     * one they wanted. A two-conference league (the NFL, the NBA, MLB) already
     * fits one screen per conference on TV, and its phone body is short enough to
     * read straight through.
     */
    fun needsConferenceFilter(groups: List<StandingGroup>): Boolean =
        conferences(groups).size > 2

    /**
     * A conference's name as a chip reads it: the feed spells every one of them
     * "... Conference" ("Southeastern Conference", "Big Ten Conference"), and
     * the trailing word is the one that says the least on a pill.
     */
    fun label(conference: String): String =
        conference.removeSuffix(" Conference").trim().ifBlank { conference }

    /**
     * [groups] narrowed to [conference], in feed order. Null is the whole table,
     * which is what "ALL" means; a name this table does not carry is also the
     * whole table rather than an empty one, so a stale selection after a refresh
     * can never blank the view.
     */
    fun filter(groups: List<StandingGroup>, conference: String?): List<StandingGroup> =
        conference?.let { name -> groups.filter { (it.conference ?: it.name) == name } }
            ?.takeIf { it.isNotEmpty() }
            ?: groups

    private fun byConference(groups: List<StandingGroup>): LinkedHashMap<String, MutableList<StandingGroup>> {
        val out = LinkedHashMap<String, MutableList<StandingGroup>>()
        groups.forEach { group -> out.getOrPut(group.conference ?: group.name) { mutableListOf() } += group }
        return out
    }
}
