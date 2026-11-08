package com.kennyb1201.kbstream.ui.mobile

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone grid's timeline zoom (spec T1, T3, T4).
 *
 * The TV got 3h / 6h / 12h in round 3 (F12e) and the phone stayed locked at one
 * window with no control at all. This gives the phone the same levels, the same
 * key and the same shape: one state on the screen, a header button that walks
 * the cycle and persists it, and a grid that draws whatever level it is handed.
 *
 * The scale and the cap are arithmetic, so they are pinned in
 * `GuideGridRulesTest`; what is asserted here is the wiring - which view the
 * button appears in, what it writes, and that the window keeps its start.
 *
 * Source-reading style, like the guide's other mobile contract tests: this
 * module has no Compose test harness, so the shape is asserted and the field
 * settles it (S1-S5).
 */
class MobileGuideZoomContractTest {

    private val screen = source("com/kennyb1201/kbstream/ui/mobile/GuideScreenMobile.kt")
    private val grid = source("com/kennyb1201/kbstream/ui/mobile/GuideGridMobileView.kt")

    private val mainScreen = screen
        .substringAfter("fun GuideScreenMobile(")
        .substringBefore("private fun MobileGuideSetup(")

    private val topBar = screen
        .substringAfter("private fun MobileGuideTopBar(")
        .substringBefore("private fun MobileGuideChannelRow(")

    // ── T1: the state, the button and the write ───────────────────────────

    @Test
    fun `the screen owns the zoom in the TV's own pref`() {
        assertTrue(
            "F1a: the same key, the same default as the TV",
            mainScreen.contains(
                "guidePrefs.getInt(\"grid_window_hours\", GuideGrid.DEFAULT_WINDOW_HOURS)"
            )
        )
        assertTrue(
            "F1a: an unknown level opens at the default instead of sticking",
            mainScreen.contains(
                "takeIf { it in GuideGrid.ZOOM_WINDOW_HOURS } " +
                    "?: GuideGrid.DEFAULT_WINDOW_HOURS"
            )
        )
        assertTrue(
            "F1b: the state lives on the screen, not in the grid",
            mainScreen.contains("var gridWindowHours by remember(activeProfileId)")
        )
        assertFalse(
            "the grid composable only reads the level it is handed",
            grid.contains("var gridWindowHours")
        )
    }

    @Test
    fun `the header's zoom button exists in grid view only`() {
        assertTrue(
            "F2a: gated on the view, the TV's rule",
            mainScreen.contains(
                "onCycleWindow = if (guideView == GuideViewMode.GRID) {"
            )
        )
        assertTrue(
            "F2a: the button renders only when the grid offers it",
            topBar.contains("if (onCycleWindow != null) {")
        )
        assertTrue(
            "F2a: same glyph as the TV, and the span beside it",
            topBar.contains("Icons.Filled.ZoomIn") &&
                topBar.contains("text = \"\${windowHours}H\"")
        )
        assertTrue(
            "F2a: TalkBack names the level it will switch to",
            topBar.contains(
                "contentDescription = \"Zoom to \${GuideGrid.nextZoomHours(windowHours)} hours\""
            )
        )
    }

    @Test
    fun `the button sits after the view toggle and before sports`() {
        val toggle = topBar.indexOf("onToggleView)")
        val zoom = topBar.indexOf("if (onCycleWindow != null)")
        val sports = topBar.indexOf("if (onOpenSports != null)")

        assertTrue("both neighbours are there", toggle in 0 until zoom && sports > zoom)
    }

    @Test
    fun `a tap walks the cycle and writes the pref immediately`() {
        val cycle = mainScreen.substringAfter("onCycleWindow = if (guideView == GuideViewMode.GRID) {")
            .substringBefore("} else {")

        assertTrue("F2c: 3 -> 6 -> 12 -> 3", cycle.contains("GuideGrid.nextZoomHours(gridWindowHours)"))
        assertTrue("the state takes the next level", cycle.contains("gridWindowHours = next"))
        assertTrue(
            "F2c: persisted at once, like the view toggle",
            cycle.contains("guidePrefs.edit().putInt(\"grid_window_hours\", next).apply()")
        )
    }

    // ── T3: the window follows the zoom and keeps its start ───────────────

    @Test
    fun `the grid draws the level it is handed`() {
        assertTrue(
            "F3a: GuideGridMobileView takes the zoom",
            grid.contains("windowHours: Int = GuideGrid.DEFAULT_WINDOW_HOURS")
        )
        assertTrue(
            "F3a: and the screen passes the shared state",
            mainScreen.contains("windowHours = gridWindowHours")
        )
        assertTrue(
            "F3a: the window opens at the zoom's span",
            grid.contains("GridWindow.opening(nowMs, windowHours)")
        )
    }

    @Test
    fun `a zoom change keeps the axis where the viewer left it`() {
        assertTrue(
            "F3a: same start, new width - the TV's F12e rule",
            grid.contains("val next = window.withHours(windowHours)")
        )
        assertTrue(
            "the change is applied to the window itself, not a fresh opening",
            grid.contains("if (next != window) window = next")
        )
    }

    @Test
    fun `the request re-asks the new window because it keys on the window`() {
        assertTrue(
            "F3c: a window change is a new request",
            grid.contains(
                "LaunchedEffect(rows, window, listState.firstVisibleItemIndex) {"
            )
        )
        assertTrue(
            "and the request carries the new span",
            grid.contains("onRequestWindow(ids, window.startUtcMillis, window.endUtcMillis)")
        )
        assertTrue(
            "F3c: the cap follows the zoom at four an hour",
            mainScreen.contains(
                "GuideGrid.programsPerWindow(gridWindowHours)"
            )
        )
    }

    // ── T4: no zoom on the list, and the tap path is untouched ────────────

    @Test
    fun `the list has no zoom control`() {
        // The gate is the same expression read backwards: while the guide is the
        // list the top bar is handed null, so nothing renders and nothing writes.
        // One occurrence in the screen body - the grid-gated argument to the top
        // bar. The list body has no button of its own to render and nothing of
        // its own to write; the rest of the mentions are the top bar's own
        // parameter and its render, asserted above.
        val body = mainScreen.substringBefore("private fun MobileGuideTopBar(")
        assertEquals(1, body.split("onCycleWindow").size - 1)
    }

    @Test
    fun `the scale reaches the time axis, the cells and the now-line`() {
        assertTrue(
            "F3b: one scale for the hour marks",
            grid.contains("(GuideGrid.MINUTES_PER_HOUR * pxPerMin).dp")
        )
        assertTrue(
            "F3b: the cells take it too - no second width math",
            grid.contains("cellWidthDp(cell.startUtcMillis, cell.endUtcMillis, pxPerMin = pxPerMin)")
        )
        assertTrue(
            "F3b: the now-line is drawn from the same scale",
            grid.contains("/ 60_000L * pxPerMin).toInt().dp")
        )
        assertTrue(
            "F3b: the canvas is 1440dp at every level",
            grid.contains(".width(GuideGrid.CONTENT_WIDTH_DP.dp)")
        )
        assertTrue(
            "the level comes from the rule, not a table in the view",
            grid.contains("val pxPerMin = GuideGrid.pxPerMin(windowHours)")
        )
    }

    @Test
    fun `tap, reminder and catchup are unchanged at every level`() {
        assertTrue(
            "the same three-way tap, reading the cell's own span",
            grid.contains("cell.startUtcMillis > nowMs -> onToggleReminder(item, cell)") &&
                grid.contains("nowMs < cell.endUtcMillis -> onPlay(item)") &&
                grid.contains("else -> onCatchup?.invoke(item, cell)")
        )
        assertTrue(
            "and the cells still read the zoom's width, not the six-hour constant",
            !grid.contains("cellWidthDp(cell.startUtcMillis, cell.endUtcMillis).dp")
        )
    }

    @Test
    fun `the detail strip follows the tapped program across a zoom change`() {
        val reResolve = grid.substringAfter("LaunchedEffect(rows) {").take(420)
            .substringBefore("val timeScroll")

        assertTrue(
            "F3d: re-resolved by the air time the tapped cell covered",
            reResolve.contains("val mid = (picked.second.startUtcMillis + picked.second.endUtcMillis) / 2")
        )
        assertTrue(
            "F3d: onto the new window's cell for the same channel",
            reResolve.contains("rows.firstOrNull { it.first.channel.id == picked.first.channel.id }")
        )
        assertTrue(
            "F3d: kept only while that time is still on the axis",
            reResolve.contains("firstOrNull { mid in it.startUtcMillis until it.endUtcMillis }")
        )
        assertTrue(
            "and the strip still draws only a non-null selection (F2a)",
            grid.contains("selected?.let { picked ->") &&
                grid.contains("MobileGridDetailStrip(selected = picked, nowMs = nowMs)")
        )
    }

    // ── source access ─────────────────────────────────────────────────────

    /** Whitespace-normalized, so an assertion can name a whole statement. */
    private fun source(relative: String): String {
        val file = File(findSourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText().replace(Regex("\\s+"), " ")
    }

    private fun findSourceRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }
}
