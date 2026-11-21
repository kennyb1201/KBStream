package com.kennyb1201.kbstream.data.reporting

import android.os.SystemClock
import android.view.Choreographer

/**
 * Scrolling, measured.
 *
 * The app's only Choreographer until now measured the compositor's period for
 * the player's frame-rate match, and the only dropped-frame number anywhere was
 * Exo's session summary - so every scrolling report had to be argued from code
 * shape (scrolling dig five, part 1). This is the browsing half: while a surface
 * is scrolling, it times consecutive frames and keeps the last session's
 * histogram, per surface, in a shape COPY DIAGNOSTICS prints in one line.
 *
 * Scoped two ways, both deliberate:
 *
 *  - **Per surface.** A session belongs to the label that started it (`home`,
 *    `guide`, `library`, `catalog`), so a paste says WHICH screen stuttered. A
 *    session-global average cannot (the same defect dig four proved about the
 *    hero decode counter). A D-pad walk reports under `<label>-walk`: the walk
 *    across a rail and the scroll that got the viewer to it are different
 *    gestures, and one paste can carry both instead of the newer displacing the
 *    older.
 *  - **Per scroll session.** Sampling starts when scrolling starts and ends when
 *    it stops. Nothing is sampled while a screen sits still, so the instrument
 *    costs nothing at rest, and the number describes the gesture the viewer
 *    actually made rather than a whole app session's mix.
 *
 * The ring is small and LONGEST-WINS per label: one line per surface, and a
 * later session replaces it only when it measured at least as many frames. The
 * report is copied AFTER the gesture, by walking to Settings to copy it - so a
 * 7-frame stray step on that trip must not displace the 64-frame walk the report
 * is about. A tie keeps the newer line, because its numbers are as long and
 * fresher, so a second full walk still refreshes the sample. Everything here
 * must run on the main thread (Choreographer's own rule); Compose effects and
 * the diagnostics builder both do.
 */
object UiFrameTrace {

    /** Deltas kept per session: ~10 s of 60 Hz frames, a long fling. */
    private const val MAX_FRAMES = 600

    /** Surfaces remembered at once - one line each in the report. */
    private const val MAX_LABELS = 8

    /**
     * How long after a session closes its decodes still count as its own.
     *
     * Long enough to cover the hero's 600 ms art dwell and the tile warms queued
     * behind the 3-permit semaphore on a slow box, short enough that touching the
     * D-pad on the way to Settings cannot re-open a walk's accounting.
     */
    private const val WALK_TAIL_MS = 2_000L

    private class Session(val label: String) {
        val deltas = IntArray(MAX_FRAMES)
        var count = 0

        /**
         * The session's frames that had an image decode land inside them.
         *
         * The same deltas, filed twice: one population for the gesture, one for
         * the frames a bitmap was completed in. A frame is put here at most once
         * however many decodes landed in it, because the question is whether the
         * frame carried one, not how many (see [FrameHistogram.decodeSplit]).
         */
        val decodeDeltas = IntArray(MAX_FRAMES)
        var decodeCount = 0

        /**
         * The same frames filed by WHERE their time went (2026-11-21).
         *
         * Pushed by [noteFramePhases] from the platform's FrameMetrics, on the
         * frame thread like everything else here, so the three arrays share one
         * count and one index: they are the same frame, measured three ways.
         * See [FrameHistogram.phaseSplit] for what the three aggregates mean and
         * which fix each one points at.
         */
        val phaseUi = IntArray(MAX_FRAMES)
        val phaseRender = IntArray(MAX_FRAMES)
        val phaseTotal = IntArray(MAX_FRAMES)
        var phaseCount = 0

        var lastFrameNs = 0L
        var callback: Choreographer.FrameCallback? = null
    }

    private val entries = ArrayDeque<String>()

    /**
     * Frames measured by the line kept for each label: the longest-wins guard.
     *
     * A number beside [entries] rather than a parse of the rendered line - the
     * comparison must not depend on the text it renders.
     */
    private val keptFrames = HashMap<String, Int>()

    /**
     * Written on the main thread; also READ from loader threads, by
     * [ImageLoadTrace]'s caller asking whether a decode landed inside a metered
     * gesture. Volatile so that answer is not stale.
     */
    @Volatile
    private var active: Session? = null

    /**
     * `SystemClock.elapsedRealtime()` when the last session closed, 0 before any.
     *
     * The session closes 350 ms after the last step (ScrollFrameMeter's settle),
     * and the work that step asked for finishes later than that: the
     * hero's art dwell is 600 ms and the tile warms queue behind their semaphore.
     * [inWalkWindow] uses this so a count of "what the walk cost" still includes
     * those decodes instead of losing precisely the ones the walk asked for.
     */
    @Volatile
    private var lastEndedAtMs = 0L

    /**
     * Starts (or restarts) the session for [label].
     *
     * One session at a time: two scrollables cannot be under one finger, and a
     * meter that opened a second session would have to decide which frames
     * belonged to which - the newest begin wins, and the one it displaced is
     * finished so its sample is not lost.
     */
    fun begin(label: String) {
        active?.let { finish(it) }
        val session = Session(label)
        active = session
        val choreographer = Choreographer.getInstance()
        val callback =
            object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    // A stale callback can land after its session was finished
                    // (removeFrameCallback is not retroactive): drop it.
                    if (active !== session) return
                    val previous = session.lastFrameNs
                    session.lastFrameNs = frameTimeNanos
                    if (previous != 0L && session.count < MAX_FRAMES) {
                        val deltaMs = ((frameTimeNanos - previous) / 1_000_000L).toInt()
                        if (deltaMs > 0) {
                            session.deltas[session.count++] = deltaMs
                            // The same clock ImageLoadTrace stamps with, so this
                            // is not a comparison across two time bases.
                            val decodeEndNs = ImageLoadTrace.lastDecodeEndNanos()
                            if (decodeEndNs > previous && decodeEndNs <= frameTimeNanos &&
                                session.decodeCount < MAX_FRAMES
                            ) {
                                session.decodeDeltas[session.decodeCount++] = deltaMs
                            }
                        }
                    }
                    choreographer.postFrameCallback(this)
                }
            }
        session.callback = callback
        choreographer.postFrameCallback(callback)
    }

    /** Ends the running session, keeping its histogram if it was long enough. */
    fun end() {
        val session = active ?: return
        active = null
        finish(session)
    }

    /** Whether a session is running - the scroll surface's own question. */
    fun isRunning(): Boolean = active != null

    /**
     * One frame's time, split by the thread that spent it.
     *
     * The caller is FrameMetricsMeter, which is the only place that can read the
     * platform's per-frame breakdown. It passes three ints rather than the
     * FrameMetrics object so this file keeps its single Android dependency (the
     * Choreographer above) and the arithmetic stays in [FrameHistogram].
     *
     * Ignored when no session is open: the listener runs for every frame of the
     * window, including the thousands that are not a metered gesture, and a
     * session's sample has to be the gesture's own frames or the percentiles
     * describe the session rather than the walk.
     *
     * A frame with nothing at all to report is dropped, because a FrameMetrics
     * metric the platform does not have comes back as METRIC_UNKNOWN and would
     * otherwise be filed as a real 0 ms.
     */
    fun noteFramePhases(uiMs: Int, renderMs: Int, totalMs: Int) {
        val session = active ?: return
        if (uiMs <= 0 && renderMs <= 0 && totalMs <= 0) return
        if (session.phaseCount >= MAX_FRAMES) return
        session.phaseUi[session.phaseCount] = uiMs.coerceAtLeast(0)
        session.phaseRender[session.phaseCount] = renderMs.coerceAtLeast(0)
        session.phaseTotal[session.phaseCount] = totalMs.coerceAtLeast(0)
        session.phaseCount++
    }

    /**
     * Whether a metered gesture is open, or closed within [WALK_TAIL_MS].
     *
     * The image trace's question, not the scroll surface's: a decode belongs to
     * the walk if it lands while the walk is open OR in the short tail after it,
     * because the art a step asks for is decoded after the last step - the hero's
     * dwell alone is 600 ms against a 350 ms settle. Gating on [isRunning] alone
     * drops exactly those decodes (build-387 note, [ImageLoadTrace]).
     */
    fun inWalkWindow(): Boolean {
        val since = lastEndedAtMs
        return active != null ||
            (since != 0L && SystemClock.elapsedRealtime() - since <= WALK_TAIL_MS)
    }

    /** Whether the running session (if any) is [label]'s. */
    fun isRunning(label: String): Boolean = active?.label == label

    /**
     * Ends the running session only if it is [label]'s.
     *
     * A COMPOSABLE'S OWN session ends on its own gesture's stop signal, so it
     * must not close another surface's - see [
     * com.kennyb1201.kbstream.ui.components.TrackFocusSteps], whose settle timer
     * fires after its last step and could otherwise end a scroll that started in
     * the meantime.
     */
    fun endIf(label: String) {
        if (active?.label != label) return
        end()
    }

    /** One line per surface that has scrolled this session; empty before that. */
    fun lines(): List<String> = synchronized(entries) { entries.toList() }

    private fun finish(session: Session) {
        session.callback?.let { callback ->
            runCatching { Choreographer.getInstance().removeFrameCallback(callback) }
        }
        session.callback = null
        // Before the length check: even a session too short to print still marks
        // the moment a gesture ended, which is what the tail is measured from.
        lastEndedAtMs = SystemClock.elapsedRealtime()
        val stats = FrameHistogram.of(session.deltas, session.count) ?: return
        val line = FrameHistogram.line(session.label, stats) +
            FrameHistogram.decodeSplit(
                FrameHistogram.of(session.decodeDeltas, session.decodeCount),
                decodeFrames = session.decodeCount,
                sessionFrames = stats.frames
            ) +
            FrameHistogram.phaseSplit(
                ui = FrameHistogram.phases(session.phaseUi, session.phaseCount),
                render = FrameHistogram.phases(session.phaseRender, session.phaseCount),
                total = FrameHistogram.phases(session.phaseTotal, session.phaseCount),
                phaseFrames = session.phaseCount,
                sessionFrames = stats.frames
            )
        synchronized(entries) {
            // Longest wins for a label. The report is read after the gesture, on
            // the way to Settings, so a short stray session taken on that trip
            // must not displace the walk the paste is about; a tie keeps the
            // newer numbers (FrameHistogram.supersedes).
            if (!FrameHistogram.supersedes(stats.frames, keptFrames[session.label])) return
            keptFrames[session.label] = stats.frames
            // The prefix finds the label's SLOT to refresh in place. The count
            // above came from the map, never from parsing a rendered line.
            val slot = entries.indexOfFirst { it.startsWith("frame: ${session.label} ") }
            if (slot >= 0) {
                entries[slot] = line
            } else {
                entries.addLast(line)
                while (entries.size > MAX_LABELS) {
                    val dropped = entries.removeFirst()
                    // A label that fell out of the ring loses its count with it,
                    // or a number nothing displays would still veto a shorter
                    // line that is now the only one there is.
                    keptFrames.keys.toList().forEach { label ->
                        if (dropped.startsWith("frame: $label ")) keptFrames.remove(label)
                    }
                }
            }
        }
    }
}
