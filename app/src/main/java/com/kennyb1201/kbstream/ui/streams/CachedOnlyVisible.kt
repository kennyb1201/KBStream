package com.kennyb1201.kbstream.ui.streams

import com.kennyb1201.kbstream.data.addon.Stream
import com.kennyb1201.kbstream.data.debrid.TorBoxCachedOnly

/**
 * How the cached-only filter is applied to the picker's published lists.
 *
 * The rule itself is [TorBoxCachedOnly] (hash rows only, direct rows always
 * kept). What is here is the bookkeeping the merged list needs and the tabs
 * need kept in step with it:
 *
 *  - [keptIndices] filters by POSITION, so the add-on names published beside the
 *    merged list ([StreamsViewModel.sourceAddons]) are thinned by the same
 *    decision - filtering the rows and not their add-on names would slide every
 *    name onto the next add-on's row.
 *  - [groups] applies the same rule to each add-on's own list and drops a group
 *    that has nothing left: an empty tab is not a tab.
 *
 * Pure, so the two lists' consistency is a unit test rather than a claim.
 */
internal object CachedOnlyVisible {

    /** Indices of [streams] that survive the rule, in order. */
    fun keptIndices(streams: List<Stream>, cachedHashes: Set<String>): List<Int> =
        streams.indices.filter { TorBoxCachedOnly.keeps(streams[it], cachedHashes) }

    /**
     * [values] - a list published row-for-row beside a [streamCount]-long list -
     * thinned by [kept], or returned untouched when it is not aligned with the
     * rows. An unaligned list is the progressive paint's empty hand
     * ([StreamsViewModel] publishes rows before it knows their add-ons), and
     * inventing entries for it would be a lie about which add-on served what.
     */
    fun <T> alongside(values: List<T>, streamCount: Int, kept: List<Int>): List<T> =
        if (values.size == streamCount) kept.map { values[it] } else values

    /** Each group's rows filtered by the same rule; an emptied group is dropped. */
    fun groups(
        groups: List<StreamAddonGroup>,
        cachedHashes: Set<String>
    ): List<StreamAddonGroup> =
        groups.mapNotNull { group ->
            val kept = TorBoxCachedOnly.rows(group.streams, cachedHashes)
            if (kept.isEmpty()) null else group.copy(streams = kept)
        }
}
