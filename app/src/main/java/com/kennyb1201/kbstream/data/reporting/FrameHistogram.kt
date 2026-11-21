package com.kennyb1201.kbstream.data.reporting

/**
 * One scroll session's frame deltas, summarised.
 *
 * Four rounds of jank work were ranked by code shape because the app had no
 * frame number anywhere (scrolling dig five, part 1). This is the number: the
 * deltas a [android.view.Choreographer.FrameCallback] sees while a surface is
 * scrolling, summarised so one diagnostics line can carry them.
 *
 * Percentiles, not an average. A 60 Hz surface averages 16.7 ms through a
 * stutter as easily as through a clean scroll - the average hides exactly the
 * frames the viewer felt. p50 says how a normal frame went, p95 says how the bad
 * ones went, and the two counts say whether the misses were marginal (a 20 ms
 * frame) or visible (a 40 ms one).
 *
 * Pure Kotlin on purpose: no Android type reaches this file, so the arithmetic
 * is covered by the JVM harness the rest of the app's rules use.
 */
data class FrameStats(
    /** Frames measured in the session. */
    val frames: Int,
    /** Median frame delta. */
    val p50Ms: Int,
    /** 95th-percentile frame delta - where a stutter shows up. */
    val p95Ms: Int,
    /** The worst frame in the session. */
    val maxMs: Int,
    /** Frames that missed a 60 Hz budget (over 16.7 ms, measured as 17+). */
    val over16Ms: Int,
    /** Frames that missed two budgets - a visible hitch. */
    val over33Ms: Int
)

object FrameHistogram {

    /** A 60 Hz frame's budget, rounded down to a whole millisecond. */
    const val BUDGET_MS = 16

    /** Two budgets: the point where a dropped frame is visible as a stutter. */
    const val HITCH_MS = 33

    /**
     * How few frames are worth reporting.
     *
     * Under this, a session is a tap rather than a scroll: the first delta after
     * a begin is measured against nothing, and the sample says more about when
     * the effect started than about the scroll. Reporting it would put noise
     * lines in every paste.
     */
    const val MIN_FRAMES = 6

    /**
     * Summarises the first [count] deltas of [deltas], or null when the sample is
     * too short to mean anything.
     */
    fun of(deltas: IntArray, count: Int = deltas.size): FrameStats? =
        summarise(deltas.take(count.coerceIn(0, deltas.size)).filter { it > 0 })

    /**
     * The same summary for a FRAME PHASE sample, where zero is a reading rather
     * than a missing one.
     *
     * [of] drops non-positive values because a zero frame DELTA is the first
     * frame measured against nothing, and letting it into the median would
     * understate the scroll. A phase duration has no such defect: a frame that
     * spent 0 ms on the UI thread really did, and dropping those frames would
     * answer "how long did the frames that worked take" instead of "how long did
     * this phase take".
     */
    fun phases(values: IntArray, count: Int = values.size): FrameStats? =
        summarise(values.take(count.coerceIn(0, values.size)).map { it.coerceAtLeast(0) })

    private fun summarise(values: List<Int>): FrameStats? {
        if (values.size < MIN_FRAMES) return null
        val sorted = values.sorted()
        return FrameStats(
            frames = sorted.size,
            p50Ms = percentile(sorted, 50),
            p95Ms = percentile(sorted, 95),
            maxMs = sorted.last(),
            over16Ms = sorted.count { it > BUDGET_MS },
            over33Ms = sorted.count { it > HITCH_MS }
        )
    }

    /**
     * Whether a session of [frames] frames replaces the line kept for its label.
     *
     * Longest wins, and a tie goes to the newer line. The report is copied AFTER
     * the gesture - by walking to Settings - so the short stray step taken on
     * that trip must not displace the walk the report is about. A second full
     * walk still refreshes the numbers, because equal length is replaced.
     */
    fun supersedes(frames: Int, keptFrames: Int?): Boolean =
        keptFrames == null || frames >= keptFrames

    /**
     * The diagnostics line for one surface's last scroll session.
     *
     * One line, the same shape the other traces use (`label: a=N b=N`), and
     * named by SURFACE: a session-global number would repeat the defect that made
     * the hero decode counter unusable (dig four §1c) - it cannot say which
     * surface was slow.
     */
    fun line(label: String, stats: FrameStats): String =
        "frame: $label frames=${stats.frames} p50=${stats.p50Ms}ms " +
            "p95=${stats.p95Ms}ms max=${stats.maxMs}ms " +
            ">16=${stats.over16Ms} >33=${stats.over33Ms}"

    /**
     * The decode half of a walk's line: the frames an image decode landed in,
     * summarised the way the whole session is.
     *
     * One `frame:` line cannot say whether the slow frames were the ones carrying
     * new bitmaps - the build-390 walk reported p50=33ms with 85 image decodes
     * inside its window, and the next fix depends on whether those frames are the
     * p95 or the p50. Same budgets, same percentiles, so the two halves are read
     * against each other: `decodeP50=16ms` beside `p50=33ms` clears the loader and
     * points at the draw; `decodeP50=48ms` does the opposite.
     *
     * A session with no decodes at all says so (`decodeFrames=0`) rather than
     * going quiet, and a session that never sampled a frame carries nothing.
     *
     * The distinction is not pedantry: the 15:52 paste printed no split on either
     * `frame:` line while `image: decode:` reported 470 decodes, which read as an
     * instrument that had failed. It had not - those decodes landed in the walk's
     * two-second tail, after the last sampled frame, so no frame could carry one.
     * `decodeFrames=0` says that in the line itself.
     *
     * [decodeFrames] is the session's own count, which can be above zero while
     * [stats] is null: a session with a decode or two but too few frames to
     * print percentiles over still reports the count.
     */
    fun decodeSplit(
        stats: FrameStats?,
        decodeFrames: Int,
        sessionFrames: Int
    ): String =
        when {
            sessionFrames <= 0 -> ""
            stats == null -> " decodeFrames=$decodeFrames"
            else ->
                " decodeFrames=${stats.frames} decodeP50=${stats.p50Ms}ms " +
                    "decodeP95=${stats.p95Ms}ms decode>33=${stats.over33Ms}"
        }

    /**
     * The third half of a walk's line: WHERE the frame time went.
     *
     * `frame:` says how the frames went and `decodeFrames` says which of them
     * carried a bitmap - and the 2026-11-21 warm walk left the next question
     * open: 250 frames at p50=16ms with only 26 carrying a decode, so 79 of the
     * 105 slow frames had nothing to do with the loader. "Not the loader" is not
     * a fix; which thread held them is.
     *
     * Three aggregates, per frame, from the platform's own FrameMetrics:
     *
     *  - `ui` - `animation + layout/measure + draw`, i.e. the app's own work on
     *    the frame thread. A large `uiP95` is composition, layout or
     *    recordDisplayList, and the fix is in the composables.
     *  - `render` - `sync + command`, i.e. the display lists being pushed and the
     *    draw commands issued on the render thread. A large `renderP95` with a
     *    small `ui` is draw cost (layers, alpha, oversized surfaces), not
     *    composition.
     *  - `total` - the frame's own measured duration. Read BESIDE the deltas: a
     *    `p50=33ms` frame line with `totalP50=16ms` means the frame's work fits a
     *    budget and the 33 ms is the frame not being scheduled - vsync pacing,
     *    a compositor holding a buffer, or another surface owning the display -
     *    which is a different bug from any of the above and the one no amount of
     *    app-side work can fix.
     *
     * Same budgets and percentiles as the rest of the line, so the three are read
     * against each other. A session whose phases were never sampled (a device
     * that never delivers them) prints `phaseFrames=0` rather than a blank that
     * reads as a broken instrument - the same rule `decodeFrames` follows.
     */
    fun phaseSplit(
        ui: FrameStats?,
        render: FrameStats?,
        total: FrameStats?,
        phaseFrames: Int,
        sessionFrames: Int
    ): String = when {
        sessionFrames <= 0 -> ""
        ui == null || render == null || total == null -> " phaseFrames=$phaseFrames"
        else ->
            " phaseFrames=$phaseFrames" +
                " uiP50=${ui.p50Ms}ms uiP95=${ui.p95Ms}ms" +
                " renderP50=${render.p50Ms}ms renderP95=${render.p95Ms}ms" +
                " totalP50=${total.p50Ms}ms totalP95=${total.p95Ms}ms"
    }

    /**
     * The `ui` aggregate, split into the three parts it is the sum of.
     *
     * The first split named the thread; this one names the WORK. `uiP50=107ms`
     * beside `renderP50=8ms` (the 2026-11-21 walk) says the app's own frame
     * thread held the frame, but `ui` is `animation + layout/measure + draw` and
     * each of those is a different fix:
     *
     *  - `anim` - the animation clock and callbacks (a change animation, an
     *    `animate*AsState` running across a rail, a scheduled scroll);
     *  - `layout` - measure and layout of the tree (a wide invalidation, a
     *    non-constant size, a list re-measuring off-screen children);
     *  - `draw` - `recordDisplayList` for the frame's views (`graphicsLayer`,
     *    alpha, clipping, an oversized surface).
     *
     * Same p50/p95 pairing as the aggregates, so the three parts are read
     * against the `ui` they add up to. Empty when the session sampled no phases
     * - [phaseSplit] already says `phaseFrames=0`, and two instruments
     * announcing the same silence would read as two failures.
     */
    fun uiParts(
        anim: FrameStats?,
        layout: FrameStats?,
        draw: FrameStats?,
        phaseFrames: Int,
        sessionFrames: Int
    ): String = when {
        sessionFrames <= 0 -> ""
        anim == null || layout == null || draw == null -> ""
        else ->
            " uiAnimP50=${anim.p50Ms}ms uiAnimP95=${anim.p95Ms}ms" +
                " uiLayoutP50=${layout.p50Ms}ms uiLayoutP95=${layout.p95Ms}ms" +
                " uiDrawP50=${draw.p50Ms}ms uiDrawP95=${draw.p95Ms}ms"
    }

    /** Nearest-rank percentile over an ascending list. */
    private fun percentile(sorted: List<Int>, percent: Int): Int {
        val rank = (percent / 100.0 * sorted.size).let { kotlin.math.ceil(it).toInt() }
        return sorted[(rank - 1).coerceIn(0, sorted.lastIndex)]
    }
}
