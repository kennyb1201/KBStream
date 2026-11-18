package com.kennyb1201.kbstream.ui.components

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The frame meter can see a D-pad walk, not just a drag.
 *
 * Spec 2026-11-18, frame meter covers horizontal rail walks. Before this, a
 * session opened only on the flip of `isScrollInProgress` — and a TV Home walk
 * scrolls a RAIL's own LazyRow while the meter watched the VERTICAL column's
 * state, so the app's main gesture produced no `frame: home` line at all. TV
 * Library had no meter call whatsoever, so its walk produced no `library` line
 * either.
 *
 * Instrumentation only (F3): the line shape, the budgets and the caps are the
 * ones the meter already had, and nothing here touches a scroll offset.
 */
class FrameMeterWalkContractTest {

    private val meter = source(METER)
    private val trace = source(TRACE)
    private val histogram = source(HISTOGRAM)
    private val home = source(HOME)
    private val library = source(LIBRARY)

    // ── the meter's existing trigger is untouched ────────────────────────

    @Test
    fun `S4 - the vertical scroll session opens and closes exactly as before`() {
        val scroll = block(meter, "fun TrackScrollFrames(")
        assertTrue(
            "still one snapshotFlow on the state's own flag",
            scroll.contains("snapshotFlow { state.isScrollInProgress }")
        )
        assertTrue(
            "still distinctUntilChanged, so only the flips start and end a session",
            scroll.contains(".distinctUntilChanged()")
        )
        assertTrue(
            "and the flips still map to the same pair of calls",
            scroll.contains("if (scrolling) UiFrameTrace.begin(label) else UiFrameTrace.end()")
        )
        assertTrue(
            "Home's column call is unchanged",
            home.contains("TrackScrollFrames(label = \"home\", state = railListState)")
        )
    }

    // ── F1: the walk gets its own trigger ────────────────────────────────

    @Test
    fun `the walk opens a session on the focus step, held across the walk`() {
        val walk = block(meter, "fun TrackFocusSteps(")
        assertTrue(
            "the step is READ inside the snapshot: a parameter would be captured " +
                "once and the flow would never see a change",
            walk.contains("snapshotFlow(step)") &&
                meter.contains("fun TrackFocusSteps(label: String, step: () -> Any?)") &&
                !walk.contains("snapshotFlow { step }")
        )
        assertTrue(
            "never runs off the value composition found: only a real step counts",
            walk.contains(".drop(1)")
        )
        assertTrue(
            "each step restarts the SETTLE window rather than the session, so one " +
                "walk is one sample - collectLatest is what cancels the previous wait",
            walk.contains(".collectLatest {") && walk.contains("delay(WALK_SETTLE_MS)")
        )
        assertTrue(
            "the walk remembers whether the running session is its own, across the " +
                "steps' cancellations",
            walk.contains("var ownsSession = false") &&
                walk.contains("UiFrameTrace.begin(label)") &&
                walk.contains("ownsSession = true")
        )
        assertTrue(
            "a session it did not start is left alone in both directions: a running " +
                "scroll keeps its sample, and one that starts mid-walk is not closed " +
                "by this walk's timer",
            walk.contains("if (UiFrameTrace.isRunning(label)) return@collectLatest") &&
                walk.contains("ownsSession = false") &&
                walk.contains("UiFrameTrace.endIf(label)")
        )
        assertTrue(
            "the window clears the meter's own floor: a walk must not be dropped as " +
                "a tap",
            meter.contains("private const val WALK_SETTLE_MS = 350L")
        )
    }

    @Test
    fun `the trace can answer per label and end per label`() {
        assertTrue(
            "isRunning stays for the scroll surface's own question",
            trace.contains("fun isRunning(): Boolean = active != null")
        )
        assertTrue(
            "and gained the per-label form the walk's guard uses",
            trace.contains("fun isRunning(label: String): Boolean = active?.label == label")
        )
        assertTrue(
            "endIf refuses to close another surface's session",
            trace.contains("fun endIf(label: String) {") &&
                trace.contains("if (active?.label != label) return")
        )
        assertTrue(
            "one session at a time still holds: a begin that displaces another still " +
                "finishes it, which is what lets a scroll win over a walk",
            trace.contains("fun begin(label: String) {") &&
                trace.contains("active?.let { finish(it) }")
        )
    }

    @Test
    fun `Home metres the walk with the SAME label and the card that took focus`() {
        assertTrue(
            "the walk rides the home label, so its line reads beside the column's",
            home.contains("TrackFocusSteps(label = \"home\", step = { focusedCardRequester })")
        )
        assertTrue(
            "and the step is the card focus the rails already publish - no new " +
                "focus plumbing, nothing added to a card",
            home.contains("var focusedCardRequester by remember {") &&
                home.contains("focusedCardRequester = requester")
        )
    }

    // ── F2: TV Library gets a line at all ────────────────────────────────

    @Test
    fun `TV Library writes a library line for a fling and for a walk`() {
        val grid = block(library, "private fun ItemGrid(")
        assertTrue(
            "the grid's own state is metered, the way the phone's Library grid is",
            grid.contains("TrackScrollFrames(label = \"library\", state = gridState)")
        )
        assertTrue(
            "and a walk has its trigger there too",
            grid.contains("TrackFocusSteps(label = \"library\", step = { walkStep })")
        )
        assertTrue(
            "which is the key of the poster the D-pad landed on",
            grid.contains(".onFocusChanged { if (it.isFocused) walkStep = cardKey }")
        )
        assertFalse(
            "the phone's Library line is untouched",
            source("ui/mobile/LibraryScreenMobile.kt")
                .let { !it.contains("TrackScrollFrames(label = \"library\", state = gridState)") }
        )
    }

    @Test
    fun `the LISTS view's rail writes the same library line`() {
        val pane = block(library, "private fun ListsPane(")
        assertTrue(
            "the state the LazyColumn would have made for itself, hoisted so the " +
                "meter can watch it - a plain one, so the rail's scrolling is untouched",
            pane.contains("val listsRailState = rememberLazyListState()")
        )
        assertTrue(
            "and metered under the screen's own label",
            pane.contains("TrackScrollFrames(label = \"library\", state = listsRailState)")
        )
        assertTrue(
            "the rail is then DRAWN with that state, not with one of its own",
            pane.contains("state = listsRailState,")
        )
        val rail = block(library, "private fun RailListColumn(")
        assertTrue(
            "the column takes it, required rather than defaulted: one caller, one state",
            rail.contains("state: LazyListState,")
        )
        assertTrue(
            "and hands it to the LazyColumn it builds",
            rail.contains("LazyColumn(") && rail.contains("state = state,")
        )
        assertTrue(
            "the pane's item grid keeps its own two calls - this is the rail, not a " +
                "replacement for them",
            block(library, "private fun ItemGrid(")
                .contains("TrackScrollFrames(label = \"library\", state = gridState)")
        )
    }

    // ── F3: instrumentation only ─────────────────────────────────────────

    @Test
    fun `F3 - budgets, caps and the line format are the meter's own`() {
        assertTrue(
            "the 60 Hz budget and the hitch line are unchanged",
            histogram.contains("const val BUDGET_MS = 16") &&
                histogram.contains("const val HITCH_MS = 33")
        )
        assertTrue(
            "so is the short-sample floor",
            histogram.contains("const val MIN_FRAMES = 6")
        )
        assertTrue(
            "one line per surface, same shape",
            histogram.contains("\"frame: \$label frames=\${stats.frames} p50=\${stats.p50Ms}ms \"")
        )
        assertTrue(
            "the ring sizes are unchanged too",
            trace.contains("private const val MAX_FRAMES = 600") &&
                trace.contains("private const val MAX_LABELS = 6")
        )
        assertFalse(
            "and the walk did not become a second line format: the meter emits no " +
                "line of its own",
            block(meter, "fun TrackFocusSteps(").contains("FrameHistogram")
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /** One function's source, by matching its declaration and balancing braces. */
    private fun block(source: String, declaration: String): String {
        val at = source.indexOf(declaration)
        require(at >= 0) { "missing: $declaration" }
        val bodyStart = source.indexOf("{", at)
        require(bodyStart > at) { "no body: $declaration" }
        var depth = 0
        for (i in bodyStart until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(at, i + 1)
                }
            }
        }
        error("unbalanced: $declaration")
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

    private companion object {
        const val METER = "ui/components/ScrollFrameMeter.kt"
        const val TRACE = "data/reporting/UiFrameTrace.kt"
        const val HISTOGRAM = "data/reporting/FrameHistogram.kt"
        const val HOME = "ui/home/HomeScreen.kt"
        const val LIBRARY = "ui/library/LibraryScreen.kt"
    }
}
