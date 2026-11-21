package com.kennyb1201.kbstream.data.reporting

import android.os.Handler
import android.os.Looper
import android.view.FrameMetrics
import android.view.Window

/**
 * The platform's own per-frame breakdown, fed to the walk's line.
 *
 * [UiFrameTrace] samples frame DELTAS, which answers "how long between frames"
 * and cannot answer "what was the frame doing". Three rounds of image work were
 * aimed with that gap open - the 2026-11-21 warm walk ran 250 frames at
 * `p50=16ms` with only 26 of them carrying a decode, so **79 of the 105 slow
 * frames had no decode in them**. Naming the loader as innocent does not name
 * the culprit, and every remaining fix (composition scoping, draw cost, layer
 * count) is on a different thread.
 *
 * [android.view.Window.addOnFrameMetricsAvailableListener] is the only API that
 * can say which thread held the frame, and this app had never used it: the
 * player's Choreographer reads the compositor's PERIOD for the frame-rate match,
 * and nothing anywhere reads a frame's phases.
 *
 * What it costs: one callback per frame of THIS window, on the main thread,
 * reading six longs off the FrameMetrics object. That is not free, so the read
 * is kept out of the numbers it reports: it records only while a metered gesture
 * is open ([UiFrameTrace] drops anything without a session), so a screen sitting
 * still pays one null check, and the arithmetic lives in [FrameHistogram], which
 * never runs here.
 *
 * The window is NOT retained. An Activity re-created by a theme or profile change
 * - which this app does on both - gets a fresh window and calls [install] again,
 * and a field holding the previous one would keep that Activity's whole view tree
 * and Context alive for the life of the process. On a 1.7 GB TV box that is a
 * leak in exactly the place the meter is supposed to help.
 *
 * minSdk is 26 and FrameMetrics arrived in 24, so no version guard is needed and
 * lint has nothing to flag.
 */
internal object FrameMetricsMeter {

    /**
     * Starts the per-frame read for [window], on the main thread.
     *
     * Called once per Activity creation, which is once per window: there is
     * nothing to de-duplicate, and nothing here may outlive the window it reads.
     *
     * Wrapped, because an instrument must never be the reason a launch fails -
     * this runs on the launch path beside the first-frame observer.
     */
    fun install(window: Window) {
        runCatching {
            window.addOnFrameMetricsAvailableListener(
                { _, metrics, _ -> record(metrics) },
                Handler(Looper.getMainLooper())
            )
        }
    }

    /**
     * One frame, split into the three aggregates the report carries.
     *
     * `ui` is the app's own work on the frame thread - the animation clock, the
     * measure/layout pass and recordDisplayList. `render` is the display lists
     * being synced to the render thread and the draw commands being issued on
     * it. `total` is the frame's whole measured duration, which is read beside
     * the delta the Choreographer meter reports: when `total` fits a budget and
     * the delta does not, the frame was not SCHEDULED, and that is a compositor
     * or pacing problem rather than a composition one.
     */
    private fun record(metrics: FrameMetrics) {
        runCatching {
            UiFrameTrace.noteFramePhases(
                uiMs = metrics.millis(FrameMetrics.ANIMATION_DURATION) +
                    metrics.millis(FrameMetrics.LAYOUT_MEASURE_DURATION) +
                    metrics.millis(FrameMetrics.DRAW_DURATION),
                renderMs = metrics.millis(FrameMetrics.SYNC_DURATION) +
                    metrics.millis(FrameMetrics.COMMAND_DURATION),
                totalMs = metrics.millis(FrameMetrics.TOTAL_DURATION)
            )
        }
    }

    /**
     * A metric in whole milliseconds, with the platform's "not available"
     * answer folded to 0.
     *
     * `getMetric` returns `METRIC_UNKNOWN` (-1) for a metric this device or this
     * frame does not carry; treating that as a real 0 ms would say a phase was
     * free when it was never measured.
     */
    private fun FrameMetrics.millis(index: Int): Int {
        val nanos = getMetric(index)
        return if (nanos <= 0L) 0 else (nanos / 1_000_000L).toInt()
    }
}
