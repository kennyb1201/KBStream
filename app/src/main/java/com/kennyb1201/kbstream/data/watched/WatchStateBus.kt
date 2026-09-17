package com.kennyb1201.kbstream.data.watched

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object WatchStateBus {
    // Replay = 1 ensures new collectors instantly get the last emitted state change.
    // extraBufferCapacity = 256: tryEmit never suspends, so a burst of watch
    // writes (bulk import, season mark) can't drop updates while a slow
    // collector (Home rail re-render) drains the buffer.
    private val _updates = MutableSharedFlow<Pair<String, Boolean>>(replay = 1, extraBufferCapacity = 256)
    val updates = _updates.asSharedFlow()

    fun notifyChanged(watchedKey: String, isWatched: Boolean) {
        _updates.tryEmit(watchedKey to isWatched)
    }
}
