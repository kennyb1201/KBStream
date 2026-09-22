package com.kennyb1201.kbstream.data.watched

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * One watched-state change as the RESOLVED badge state for a key: a title is
 * unwatched, partially watched (the eye) or fully watched (the checkmark).
 *
 * [isPartiallyWatched] has to travel with [isWatched] because "not watched"
 * alone is ambiguous: unmarking one season of a partly-watched series means
 * "eye", while clearing the whole title means "no badge at all". Rated as a
 * bare boolean before, the two were indistinguishable on the bus and every
 * collector cleared the eye on any non-watched event - so a series that had
 * just had a season unmarked lost the very eye the unmark was supposed to
 * give it, until the next full marker preload.
 */
data class WatchStateUpdate(
    val key: String,
    val isWatched: Boolean,
    val isPartiallyWatched: Boolean
)

object WatchStateBus {
    // Replay = 1 ensures new collectors instantly get the last emitted state change.
    // extraBufferCapacity = 256: tryEmit never suspends, so a burst of watch
    // writes (bulk import, season mark) can't drop updates while a slow
    // collector (Home rail re-render) drains the buffer.
    private val _updates =
        MutableSharedFlow<WatchStateUpdate>(replay = 1, extraBufferCapacity = 256)
    val updates = _updates.asSharedFlow()

    fun notifyChanged(
        watchedKey: String,
        isWatched: Boolean,
        isPartiallyWatched: Boolean = false
    ) {
        _updates.tryEmit(
            WatchStateUpdate(
                key = watchedKey,
                isWatched = isWatched,
                isPartiallyWatched = isPartiallyWatched
            )
        )
    }
}
