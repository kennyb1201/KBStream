package com.kennyb1201.kbstream.data.watched

/**
 * The rule that decides which watched-episode set the Detail screen renders
 * (spec 2026-11-12, F1/F2).
 *
 * The screen draws every season's markers from ONE set of episode keys, and
 * that set is rebuilt from four places: local history plus Simkl, MDBList and
 * Trakt. The local leg answers from Room in milliseconds; the tracker legs are
 * network reads that can take seconds, fail, or simply not have reached the
 * show's episodes yet. A rebuild that has only heard from the local leg is a
 * PARTIAL set - and on the way back from the player that partial set used to
 * replace the complete one, so a show watched to the end rendered with only the
 * season that had just been played ticked, and filled in later (or on the next
 * entry, if a tracker read had failed).
 *
 * Two rules live here so they can be tested without a repository, a database or
 * a running coroutine:
 *
 *  - [shouldReplace] - a rebuild may only replace what is on screen when it is
 *    for the same title... and either nothing complete is being held yet, or
 *    the rebuild has heard from every tracker leg;
 *  - [merge] - a rebuild's snapshot can predate a local write that landed while
 *    it was in flight (the player finishing an episode), so its result is
 *    unioned with the marks made meanwhile instead of overwriting them.
 */
object WatchedKeysRebuild {

    /**
     * Whether a rebuild may replace the set the screen is rendering.
     *
     * @param sameTitle the held set belongs to the title being rebuilt.
     * @param holdingComplete the held set is a complete build (every tracker
     *   leg has answered for it at least once).
     * @param rebuildComplete this rebuild has heard from every tracker leg.
     */
    fun shouldReplace(
        sameTitle: Boolean,
        holdingComplete: Boolean,
        rebuildComplete: Boolean
    ): Boolean = !sameTitle || !holdingComplete || rebuildComplete

    /**
     * The rebuild's set, plus the local marks made while it was in flight,
     * minus the ones un-watched meanwhile.
     *
     * The union is what keeps the episode just finished from flickering off:
     * its local row can land after the rebuild's read of the history table, and
     * the incremental add is the only place that knows about it.
     */
    fun merge(
        built: Set<String>,
        addedInFlight: Set<String>,
        removedInFlight: Set<String>
    ): Set<String> = (built + addedInFlight) - removedInFlight
}
