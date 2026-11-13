package com.kennyb1201.kbstream.ui.streams

/**
 * The tab LEFT/RIGHT on a link lands on (spec 2026-11-12).
 *
 * The picker's tab row draws "All" first, then one chip per answering add-on in
 * the order the row shows them. LEFT/RIGHT on a source walks that same ring:
 * RIGHT forward, LEFT backward, wrapping at both ends, so from the last add-on
 * RIGHT returns to All and from All LEFT goes to the last add-on.
 *
 * [selectedTab] is the screen's own tab value: -1 (StreamsScreen's
 * ALL_ADDONS_TAB) for All, otherwise an index into the add-on groups. The result is expressed the same
 * way - the caller turns it back into a NAME (never stores an index), because
 * the groups can reorder or shrink under a reload and the name is what survives
 * that (see StreamsScreen's selectedAddonName).
 *
 * With one add-on answering there is no ring: the row is the refresh control
 * alone, no tab is drawn, and a switch would have nothing to switch to. The
 * selection is returned unchanged, and the caller leaves the keys alone.
 *
 * REFRESH is not part of this: it is a control, first in the row but an action
 * rather than a tab, so it is never a stop on the ring.
 */
internal fun cycleAddonTab(selectedTab: Int, direction: Int, addonCount: Int): Int {
    if (addonCount <= 1) return selectedTab
    val positions = addonCount + 1
    val current = (selectedTab + 1).coerceIn(0, positions - 1)
    val step = if (direction < 0) -1 else 1
    // Kotlin's % can go negative for a LEFT from All (position 0); the extra
    // wrap turns -1 into the last chip rather than clamping it back to All.
    val next = ((current + step) % positions + positions) % positions
    return next - 1
}
