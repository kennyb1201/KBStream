package com.kennyb1201.kbstream.data.reporting

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The walk's line says which thread held the slow frame.
 *
 * The 2026-11-21 warm walk closed the question the last three builds were
 * chasing: `p50=16ms`, and 79 of its 105 slow frames had no decode in them, so
 * the loader was never the median cost. What is left is a tail that belongs to
 * composition, to draw cost, or to the frame never being scheduled - three
 * problems on three different threads with three different fixes, and nothing in
 * the app could tell them apart.
 *
 * This pins the wiring: a Window-level FrameMetrics read, gated to a metered
 * session, summarised by the pure `FrameHistogram`, printed on the same `frame:`
 * line so one paste carries the gesture whole. Instrumentation only - nothing
 * here changes a frame.
 */
class FramePhaseMeterWiringContractTest {

    private val meter = source("data/reporting/FrameMetricsMeter.kt")
    private val trace = source("data/reporting/UiFrameTrace.kt")
    private val histogram = source("data/reporting/FrameHistogram.kt")
    private val activity = source("MainActivity.kt")
    private val diagnostics = source("data/reporting/Diagnostics.kt")

    @Test
    fun `the meter is installed on the window every walk is measured in`() {
        assertTrue(
            "a Window is the only place FrameMetrics can be read from, so the " +
                "browsing Activity registers it - the player is a separate Activity " +
                "with its own frame-rate work",
            activity.contains("FrameMetricsMeter.install(window)")
        )
        assertTrue(
            "and it is a listener on THIS window's frames",
            meter.contains("window.addOnFrameMetricsAvailableListener(")
        )
        assertTrue(
            "delivered on the main thread, which is where the frame meter itself " +
                "must run (Choreographer's rule)",
            meter.contains("Handler(Looper.getMainLooper())")
        )
        assertTrue(
            "installed once per window with nothing retained: an Activity re-created " +
                "by a theme/profile change gets a fresh window and installs again, " +
                "and a field holding the previous one would keep that Activity's " +
                "view tree and Context alive for the life of the process",
            meter.contains("fun install(window: Window)") &&
                !meter.contains("Window? = null")
        )
    }

    @Test
    fun `the phases are the two threads and the frame itself`() {
        assertTrue(
            "ui = the app's own frame-thread work",
            meter.contains("metrics.millis(FrameMetrics.ANIMATION_DURATION)") &&
                meter.contains("metrics.millis(FrameMetrics.LAYOUT_MEASURE_DURATION)") &&
                meter.contains("metrics.millis(FrameMetrics.DRAW_DURATION)")
        )
        assertTrue(
            "render = the display lists going out and the commands being issued",
            meter.contains("metrics.millis(FrameMetrics.SYNC_DURATION)") &&
                meter.contains("metrics.millis(FrameMetrics.COMMAND_ISSUE_DURATION)")
        )
        assertTrue(
            "and total is read beside the delta the Choreographer meter reports: a " +
                "frame whose own work fits a budget but whose spacing does not was " +
                "never scheduled, which no app-side fix reaches",
            meter.contains("metrics.millis(FrameMetrics.TOTAL_DURATION)")
        )
        assertTrue(
            "METRIC_UNKNOWN is folded to 0 rather than filed as a free phase",
            meter.contains("if (nanos <= 0L) 0") &&
                meter.contains("METRIC_UNKNOWN")
        )
    }

    @Test
    fun `a frame is only filed while a metered gesture is open`() {
        assertTrue(
            "the listener runs on every frame of the window; the session is the gate",
            trace.contains("fun noteFramePhases(") &&
                trace.contains("animMs: Int") &&
                trace.contains("layoutMs: Int") &&
                trace.contains("drawMs: Int") &&
                trace.contains("val session = active ?: return")
        )
        assertTrue(
            "and every array shares one count, because they are one frame",
            trace.contains("session.phaseUi[at] = uiMs") &&
                trace.contains("session.phaseRender[at] =") &&
                trace.contains("session.phaseTotal[at] =") &&
                trace.contains("session.phaseAnim[at] =") &&
                trace.contains("session.phaseLayout[at] =") &&
                trace.contains("session.phaseDraw[at] =") &&
                trace.contains("session.phaseCount++") &&
                trace.contains("val at = session.phaseCount")
        )
        assertTrue(
            "the sample is capped like every other array in the session",
            trace.contains("if (session.phaseCount >= MAX_FRAMES) return")
        )
    }

    @Test
    fun `the phases ride the walk's own line, under the same longest-wins rule`() {
        val finish = trace.substringAfter("private fun finish(session: Session)")
            .substringBefore("synchronized(entries)")
        assertTrue(
            "one session, one line: the phases are appended where the decode split " +
                "already is, so the supersedes() check covers both halves at once",
            finish.contains("FrameHistogram.decodeSplit(") &&
                finish.contains("FrameHistogram.phaseSplit(") &&
                finish.indexOf("decodeSplit(") < finish.indexOf("phaseSplit(")
        )
        assertTrue(
            "built from the session's own arrays through the pure summariser",
            trace.contains("FrameHistogram.phases(session.phaseUi, session.phaseCount)") &&
                trace.contains("FrameHistogram.phases(session.phaseRender, session.phaseCount)") &&
                trace.contains("FrameHistogram.phases(session.phaseTotal, session.phaseCount)") &&
                trace.contains("phaseFrames = session.phaseCount") &&
                trace.contains("sessionFrames = stats.frames")
        )
        assertTrue(
            "and ui is split into the work it is made of, from the same frames",
            finish.contains("FrameHistogram.uiParts(") &&
                trace.contains("FrameHistogram.phases(session.phaseAnim, session.phaseCount)") &&
                trace.contains("FrameHistogram.phases(session.phaseLayout, session.phaseCount)") &&
                trace.contains("FrameHistogram.phases(session.phaseDraw, session.phaseCount)")
        )
        assertTrue(
            "so a paste gets it from the frame line it already reads",
            diagnostics.contains("UiFrameTrace.lines().forEach { report.appendLine(it) }")
        )
    }

    @Test
    fun `a phase sample keeps its zeros and the summariser is shared`() {
        assertTrue(
            "phases() exists beside of(), which is the whole point: a 0 ms phase " +
                "is a reading, not a first frame measured against nothing",
            histogram.contains("fun phases(values: IntArray, count: Int = values.size)") &&
                histogram.contains("map { it.coerceAtLeast(0) }")
        )
        assertTrue(
            "both go through one summariser, so the two lines can never disagree " +
                "about what a percentile means",
            histogram.contains("private fun summarise(values: List<Int>)")
        )
        assertTrue(
            "and the split names itself, so a paste with no phases is not read as a " +
                "broken instrument",
            histogram.contains("fun phaseSplit(") &&
                histogram.contains("phaseFrames=\$phaseFrames") &&
                histogram.contains("sessionFrames <= 0 -> \"\"")
        )
    }

    @Test
    fun `the instrument can never be the reason a launch fails`() {
        val install = meter.substringAfter("fun install(window: Window)").substringBefore("\n    /**")
        assertTrue(
            "the registration is wrapped: this runs on the launch path",
            install.contains("runCatching {")
        )
        val record = meter.substringAfter("private fun record(metrics: FrameMetrics)")
        assertTrue(
            "and so is the per-frame read, which runs on every frame of the window",
            record.contains("runCatching {")
        )
        assertFalse(
            "nothing here touches a scroll offset, a cache or a policy",
            meter.contains("scrollBy") || meter.contains("maxSizeBytes")
        )
    }

    private fun source(relative: String): String {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate =
                    File(dir, "${prefix}src/main/java/com/kennyb1201/kbstream/$relative")
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("source not found above ${System.getProperty("user.dir")}: $relative")
    }
}
