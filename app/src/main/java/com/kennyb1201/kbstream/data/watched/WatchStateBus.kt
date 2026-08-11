package com.kennyb1201.kbstream.data.watched

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object WatchStateBus {
    // Replay = 1 ensures new collectors instantly get the last emitted state change
    private val _updates = MutableSharedFlow<Pair<String, Boolean>>(replay = 1, extraBufferCapacity = 64)
    val updates = _updates.asSharedFlow()

    fun notifyChanged(watchedKey: String, isWatched: Boolean) {
        _updates.tryEmit(watchedKey to isWatched)
    }
}
