package com.kennyb1201.kbstream.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * One-shot request to open Home at the Continue Watching rail.
 *
 * Two callers, one destination: the Google Assistant app action ("open Continue
 * Watching on KBStream") and the Continue Watching launcher shortcut (spec
 * 2026-11-13, F1 and F3). Neither invents a screen - Home already draws the rail
 * and the rail already owns a verified landing (scroll + focus) that the card
 * menus use - so this carries a request, not a route with a payload.
 *
 * A counter rather than a boolean, for the two cases a boolean cannot separate:
 *
 *  - A request that arrives while the app is ALREADY on Home has to reach the
 *    composed screen, not just a launch. [signal] is Compose state for exactly
 *    that: reading it in a screen's composition subscribes that screen, so the
 *    warm request recomposes instead of being dropped by `singleTop`.
 *  - A request must be answered exactly once. [consume] burns the newest signal,
 *    so a Home recreated later (Detail -> Back) does not replay a landing the
 *    viewer already had.
 *
 * Considered and rejected: a `Screen.Home` variant carrying the rail, which
 * would have needed the rail's identity in the `Screen` sealed class and a new
 * argument on every Home call site for a request that has no payload.
 */
internal object HomeContinueWatchingRequest {

    /** Bumped once per request; Compose state so a live Home can see it. */
    var signal by mutableIntStateOf(0)
        private set

    /** The newest signal a screen has already acted on. */
    @Volatile
    private var answered = 0

    fun request() {
        signal++
    }

    /** True once per request, for the Home that is about to land. */
    fun consume(): Boolean {
        val current = signal
        if (current == 0 || current == answered) return false
        answered = current
        return true
    }
}
