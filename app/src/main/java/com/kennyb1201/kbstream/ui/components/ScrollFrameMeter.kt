package com.kennyb1201.kbstream.ui.components

import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import com.kennyb1201.kbstream.data.reporting.UiFrameTrace
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

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
                // endIf, not end: a WALK can open its own session while a scroll
                // is still running (the walk label is its own), and that walk's
                // sample must not be cut short by the scroll stopping.
                if (scrolling) UiFrameTrace.begin(label) else UiFrameTrace.endIf(label)
            }
    }
}

/**
 * Times one screen's frames during a D-pad walk, where no scroll state flips.
 *
 * [TrackScrollFrames] measures a fling or a drag: it opens on the flip of
 * `isScrollInProgress`. A left/right walk inside a rail is not that gesture. A
 * focus step asks the rail to bring its card into view, and on a rail whose
 * cards are already on screen that is no scroll at all - and on Home the walk
 * scrolls a RAIL's own row, never the column whose state the meter watches - so
 * the main TV gesture produced no `frame:` line and every scroll-path finding on
 * TV Home leaned on a meter that could not see it (2026-11-18).
 *
 * The trigger is therefore the step itself: [step] is a lambda, not a value, so
 * the read happens inside the [snapshotFlow]'s snapshot - a parameter would be
 * captured once and the flow would never see a change. It returns any value that
 * takes a new identity for each card the D-pad lands on (a focus requester, a
 * card key).
 *
 * The session stays OPEN while the steps keep coming and closes
 * [WALK_SETTLE_MS] after the last one, so one walk is one sample rather than a
 * frame or two per card (a session under [
 * com.kennyb1201.kbstream.data.reporting.FrameHistogram.MIN_FRAMES] is dropped as
 * a tap). The label is the surface's, so the line has the same shape and meaning
 * as its scroll line.
 *
 * A session this composable did NOT start is left alone in both directions: a
 * scroll that is already running when a step lands keeps its own session, and a
 * scroll that starts mid-walk displaces the walk's (one session at a time, newest
 * wins) while this one's settle timer can no longer end it - so a vertical scroll
 * that begins during a walk is still sampled to its own end.
 *
 * The walk reports under `<label>-walk`, while the scroll keeps the bare label -
 * so a paste carries the scroll that reached the rail AND the walk across it,
 * and neither displaces the other's line. The suffix is applied here, not at the
 * call sites: a screen still says `TrackFocusSteps(label = "home", ...)`.
 */
@Composable
fun TrackFocusSteps(label: String, step: () -> Any?) {
    LaunchedEffect(label) {
        // What this walk's sessions are filed under. The guard below still asks
        // about the BASE label: only a scroll is yielded to, never another walk.
        val walkLabel = walkLabel(label)
        // Whether the running session is this walk's. Survives the steps'
        // cancellations, so a step arriving mid-walk does not open (and the
        // walk's close does not forget) a second session.
        var ownsSession = false
        snapshotFlow(step)
            // The first emission is the value composition found, not a step the
            // viewer made.
            .drop(1)
            .collectLatest {
                if (!ownsSession) {
                    // Someone else's session - a scroll - is running: it wins,
                    // and its own stop signal is what closes it.
                    if (UiFrameTrace.isRunning(label)) return@collectLatest
                    UiFrameTrace.begin(walkLabel)
                    ownsSession = true
                }
                // Each step restarts the wait, not the session (collectLatest
                // cancels this delay): the walk stays one sample while its steps
                // keep coming, and closes once they stop.
                delay(WALK_SETTLE_MS)
                ownsSession = false
                UiFrameTrace.endIf(walkLabel)
            }
    }
}

/**
 * The label one walk reports under: its surface's, with `-walk` after it.
 *
 * Derived here so the call sites keep naming surfaces, and so the walk and the
 * scroll of the same surface are two entries in the report rather than one
 * overwriting the other.
 */
private fun walkLabel(label: String): String = "$label-walk"

/**
 * How long a walk's session is held open after its last focus step.
 *
 * A step's work lands within a couple of frames of the step itself, and the
 * bring-into-view it starts can run for a few more; a third of a second covers
 * that plus the art decode it triggers, while staying short enough that the walk
 * does not absorb the next gesture a viewer makes after a pause.
 */
private const val WALK_SETTLE_MS = 350L
