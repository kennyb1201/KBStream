package com.kennyb1201.kbstream.data.watched

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/*
 * "Re-read the Continue Watching feeds" signal.
 *
 * A finished title leaves the rail only when the tracker feeds are read
 * again after the completion has been pushed to Simkl: the rail merges the
 * local history (which drops the resume row the moment the episode is
 * completed) with the Simkl/MDBList feeds, and it is the tracker side that
 * still lists the show until the push lands.
 *
 * The only automatic re-read after playback used to be Home's ON_RESUME
 * refresh - which races the player's own completion push, a fire-and-forget
 * coroutine on its own scope. When the resume read wins that race it sees the
 * pre-completion feed, the title stays on the rail, and nothing asks again
 * until the next resume (or the 15-minute periodic tick). The player emits
 * this once the push has actually resolved, so Home re-merges against the
 * updated feed instead of waiting for the next unrelated refresh.
 *
 * Deliberately separate from [WatchStateBus]: that bus carries the RESOLVED
 * badge state for a title key, and a single finished episode is not "the whole
 * series is watched". This signal only asks for a re-read; it says nothing
 * about any badge.
 */
object ContinueWatchingRefreshBus {

    /**
     * No replay and no buffering beyond one slot: the signal is only useful
     * while Home is alive (its ViewModel survives behind the player), and a
     * copy delivered much later would just trigger a spurious refetch. When
     * Home is not alive the ON_RESUME refresh covers it anyway.
     *
     * DROP_OLDEST, not the default SUSPEND policy: the signal is a level
     * ("re-read the feeds"), so when a burst arrives while a collector is
     * still draining the first one, keeping the NEWEST request is what matters
     * - the earlier request is already being served. Under the default policy
     * tryEmit returns false for that second request and the emit is silently
     * lost, so the read that a later completion needs could be the one
     * dropped.
     */
    private val _requests =
        MutableSharedFlow<Unit>(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )

    val requests = _requests.asSharedFlow()

    /** Fire-and-forget, safe to call from any thread. */
    fun requestRefresh() {
        _requests.tryEmit(Unit)
    }
}
