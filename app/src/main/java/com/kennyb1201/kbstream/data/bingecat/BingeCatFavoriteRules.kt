package com.kennyb1201.kbstream.data.bingecat

import com.kennyb1201.kbstream.data.library.TasteSignals

/**
 * What one thumb press owes BingeCat's Favorites list - the favorites spec's F2
 * table, as code, with no network and no Android in it.
 *
 * The thumbs are a LOCAL taste signal first and always: the press has already
 * been written by `TasteSignals` before this answer is asked for, and a failed
 * favorites call never un-does it. What this decides is only the mirror:
 *
 * | press                     | local signal | favorites          |
 * |---------------------------|--------------|--------------------|
 * | up, from none or down     | LIKE         | add                |
 * | up, from like (a clear)   | none         | remove, if a member |
 * | down, from none or like   | DISLIKE      | remove, if a member |
 * | down, from down (a clear) | none         | nothing            |
 *
 * The last two rows are different answers on the same resulting local state,
 * which is why [legFor] takes the signal the press started from as well as the
 * one it left: pressing the thumb that was already lit is the viewer taking it
 * back, and a dislike they just cleared has nothing on BingeCat to change.
 *
 * "If a member" is the app's own membership answer (the cached
 * `sync/favorites` rows, F3's read) - never a guess. A title with no row and no
 * cache entry is left alone rather than earning a remove the server would answer
 * `not_found` for.
 */
object BingeCatFavoriteRules {

    /** The one favorites call a press owes. */
    enum class Leg {
        /** Put the title on the profile's Favorites. */
        ADD,

        /** Take it back off - only ever asked for a title the cache says is on it. */
        REMOVE,

        /** Nothing to do: no membership to change, or none the app can see. */
        NONE
    }

    /**
     * The leg for one press.
     *
     * [current] is the local signal the thumb was painting when it was pressed,
     * [next] the one the press left behind (`null` = cleared), and [favorited]
     * whether the cached favorites rows hold this title for the mapped profile.
     */
    fun legFor(current: String?, next: String?, favorited: Boolean): Leg = when {
        next == TasteSignals.LIKE -> Leg.ADD
        next == TasteSignals.DISLIKE -> if (favorited) Leg.REMOVE else Leg.NONE
        next == null && current == TasteSignals.LIKE && favorited -> Leg.REMOVE
        // Cleared from a dislike: the title was never a favorite this press
        // could have added, and there is nothing to take back.
        else -> Leg.NONE
    }
}
