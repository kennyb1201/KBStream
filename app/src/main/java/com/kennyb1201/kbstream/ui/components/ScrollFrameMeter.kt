package com.kennyb1201.kbstream.ui.components

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import com.kennyb1201.kbstream.data.reporting.UiFrameTrace
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Times one screen's frames while it scrolls, for the diagnostics report.
 *
 * Add it once per scrollable surface, next to the state the surface already
 * owns:
 *
 * ```
 * TrackScrollFrames(label = "home", state = listState)
 * ```
 *
 * The scroll state is read inside a [snapshotFlow], never in composition -
 * reading `isScrollInProgress` directly would recompose the whole screen on
 * every scroll start and stop, which is the cost this meter exists to measure
 * rather than add. The session itself is one gesture: begin on the flip to true,
 * end on the flip back, sample nothing in between a screen sitting still.
 *
 * Diagnostics only. When nothing is scrolling there is no Choreographer
 * callback and no work; the value is one line per surface in COPY DIAGNOSTICS
 * (see [UiFrameTrace]).
 */
@Composable
fun TrackScrollFrames(label: String, state: ScrollableState) {
    LaunchedEffect(state, label) {
        snapshotFlow { state.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling ->
                if (scrolling) UiFrameTrace.begin(label) else UiFrameTrace.end()
            }
    }
}
