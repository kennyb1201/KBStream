package com.kennyb1201.kbstream.ui.home

/**
 * Where the pinned rails go when a paint-first publish or its refine lands
 * (spec 2026-11-15, Home cold start F1).
 *
 * Pure, so the ordering rule can be tested off-device - it is the part of the
 * change a viewer can see: the pinned rows are the HEAD of Home, in their own
 * order, and a publish must never move a catalog row or shrink the list.
 *
 * The rule:
 *
 *  - [pinned] comes first, in the order given. That is where the final publish
 *    draws them too (`pinned + catalog rails`), so painting them early cannot
 *    reshuffle anything later;
 *  - every rail whose key is in [replacing] or in the new pinned set leaves -
 *    [replacing] is the previous publish's keys, which is how a rail that no
 *    longer exists in the refined batch stops being drawn instead of lingering
 *    as a stale head row;
 *  - everything else stays exactly where it was, in its existing order. Catalog
 *    order is the viewer's arrangement (see `catalogOrderByKey`) and the refine
 *    has no business touching it.
 */
object PinnedRailMerge {

    fun merge(
        current: List<Rail>,
        pinned: List<Rail>,
        replacing: Set<String> = emptySet(),
        keyOf: (Rail) -> String
    ): List<Rail> {
        if (pinned.isEmpty()) return current
        val mine = pinned.map(keyOf).toSet()
        val rest = current.filterNot { rail ->
            val key = keyOf(rail)
            key in mine || key in replacing
        }
        return pinned + rest
    }
}
