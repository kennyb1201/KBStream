package com.kennyb1201.kbstream.ui.iptv

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Both grids draw six hours at eight dp a minute, and nothing can change it.
 *
 * Three hours paged too often to see an evening; twelve squeezed the cells into
 * 2dp a minute because the canvas was a fixed 1440dp. The settled design is a
 * fixed scale on a canvas that is the span's own width, with the zoom control
 * gone from both headers and no per-profile span left to store.
 *
 * The width and cap arithmetic is pinned against [GuideGridRules]; the removal
 * itself (no button, no pref, no dead zoom symbols) is pinned from the sources,
 * because this module has no Compose harness - T1/T2's renders and the field
 * checks S1-S5 are what the phone and the television settle.
 */
class GuideGridFixedSpanContractTest {

    private val minute = 60_000L
    private val hour = GuideGrid.HOUR_MS

    private val rules by lazy { source("com/kennyb1201/kbstream/ui/iptv/GuideGridRules.kt") }
    private val tvGrid by lazy { source("com/kennyb1201/kbstream/ui/iptv/GuideGridView.kt") }
    private val tvScreen by lazy { source("com/kennyb1201/kbstream/ui/iptv/GuideScreen.kt") }
    private val phoneGrid by lazy { source("com/kennyb1201/kbstream/ui/mobile/GuideGridMobileView.kt") }
    private val phoneScreen by lazy { source("com/kennyb1201/kbstream/ui/mobile/GuideScreenMobile.kt") }

    // ── T1: the cells keep the three-hour size ────────────────────────────

    @Test
    fun `a half-hour cell is 240dp and an hour mark is 480dp`() {
        val start = 1_700_000_000_000L
        assertEquals(
            "F1c: a 30-minute program is the same 240dp the 3h window drew",
            240,
            cellWidthDp(start, start + 30 * minute)
        )
        assertEquals(480, cellWidthDp(start, start + 60 * minute))
        assertEquals(
            "F1c: and an hour mark is 480dp",
            480,
            GuideGrid.MINUTES_PER_HOUR * GRID_SCALE_DP_PER_MIN
        )
        assertEquals("F1a: the scale is the 3h scale", 8, GRID_SCALE_DP_PER_MIN)
    }

    @Test
    fun `both grids draw their hour marks at the fixed scale`() {
        assertTrue(
            "the television's time header: $tvGrid",
            tvGrid.contains("(GuideGrid.MINUTES_PER_HOUR * GRID_SCALE_DP_PER_MIN).dp")
        )
        assertTrue(
            "the phone's header reads its own scale, which is the same constant: $phoneGrid",
            phoneGrid.contains("val pxPerMin = GRID_SCALE_DP_PER_MIN") &&
                phoneGrid.contains("(GuideGrid.MINUTES_PER_HOUR * pxPerMin).dp")
        )
    }

    // ── T2: the canvas is the span's width ────────────────────────────────

    @Test
    fun `the content width is 2880dp at the fixed span`() {
        assertEquals(2880, contentWidthDp(GRID_SPAN_HOURS))
        assertEquals(
            2880,
            gridContentWidthDp(GridWindow.opening(1_700_000_000_000L))
        )
        assertEquals(
            "F1b: a page moves the window, it never changes the width's rule",
            2880,
            gridContentWidthDp(
                GridWindow.opening(1_700_000_000_000L).paged(
                    direction = 1,
                    floorStartUtcMillis = 1_700_000_000_000L,
                    maxEndUtcMillis = null,
                )
            )
        )
    }

    @Test
    fun `both grids size their canvas from the window`() {
        assertTrue(
            "the television: $tvGrid",
            tvGrid.contains(".width(gridContentWidthDp(window).dp)")
        )
        assertTrue(
            "the phone: $phoneGrid",
            phoneGrid.contains(".width(gridContentWidthDp(window).dp)")
        )
        assertFalse(
            "and neither draws into a fixed canvas any more",
            tvGrid.contains("CONTENT_WIDTH_DP") || phoneGrid.contains("CONTENT_WIDTH_DP")
        )
    }

    // ── T3 / F3: no zoom button, no stored span ───────────────────────────

    @Test
    fun `neither header has a zoom button`() {
        assertFalse(
            "F3a: the television header's glyph and level are gone: $tvScreen",
            tvScreen.contains("ZoomIn") || tvScreen.contains("onCycleWindow")
        )
        assertFalse(
            "F3a: and the phone's after the view toggle: $phoneScreen",
            phoneScreen.contains("ZoomIn") || phoneScreen.contains("onCycleWindow")
        )
        assertFalse(
            "F3b: no replacement control was left behind either",
            tvScreen.contains("ZoomOut") || phoneScreen.contains("ZoomOut")
        )
    }

    @Test
    fun `no grid_window_hours read or write is left in the tree`() {
        listOf(rules, tvGrid, tvScreen, phoneGrid, phoneScreen).forEach { file ->
            assertFalse(
                "F4a: nothing reads the old per-profile span",
                file.contains("getInt(\"grid_window_hours\"")
            )
            assertFalse(
                "F4a: and nothing writes it",
                file.contains("putInt(\"grid_window_hours\"")
            )
        }
    }

    // ── T4: a stored three or twelve cannot resurrect a span ──────────────

    @Test
    fun `a profile with a stored pref opens at six hours`() {
        // The key is not read anywhere, so a stored 3 or 12 cannot reach the
        // window: the only thing that decides the span is the constant below.
        val window = GridWindow.opening(1_700_000_000_000L)
        assertEquals(6, GRID_SPAN_HOURS)
        assertEquals(6 * hour, window.durationMs)
        assertEquals(
            "F2a: the default span is the fixed one",
            6 * hour,
            GridWindow.opening(1_700_000_000_000L, windowHours = GRID_SPAN_HOURS).durationMs
        )
        assertTrue(
            "F4a: the stale key is cleared on open instead - both screens, per profile",
            tvScreen.contains("guidePreferences.edit().remove(\"grid_window_hours\").apply()") &&
                phoneScreen.contains("guidePrefs.edit().remove(\"grid_window_hours\").apply()")
        )
        assertTrue(
            "and the grids open on the default, with no level handed in",
            tvGrid.contains("var window by remember { mutableStateOf(GridWindow.opening(nowMs)) }") &&
                phoneGrid.contains(
                    "var window by remember { mutableStateOf(GridWindow.opening(nowMs)) }"
                )
        )
    }

    // ── T5: the cap follows the span ──────────────────────────────────────

    @Test
    fun `24 programs per channel per window`() {
        assertEquals(24, GuideGrid.programsPerWindow(GRID_SPAN_HOURS))
        assertEquals(
            "the read path's own default, unchanged",
            GuideGrid.PER_CHANNEL_LIMIT,
            GuideGrid.programsPerWindow(GRID_SPAN_HOURS)
        )
        assertTrue(
            "the television asks for the fixed span's cap",
            tvScreen.contains("GuideGrid.programsPerWindow(GRID_SPAN_HOURS)")
        )
        assertTrue(
            "and so does the phone",
            phoneScreen.contains("GuideGrid.programsPerWindow(GRID_SPAN_HOURS)")
        )
    }

    // ── T6: auto-follow is unchanged, one 3h page at the 6h span ──────────

    @Test
    fun `auto-follow pages three hours inside the six-hour span`() {
        val window = GridWindow.opening(1_700_000_000_000L)
        val next = autoFollowWindow(
            window = window,
            nowUtcMillis = window.endUtcMillis + 30_000L,
            windowHours = GRID_SPAN_HOURS,
            scheduleEndMs = null,
        )

        assertNotNull("the clock just left the window, so it follows", next)
        assertEquals(
            "one 3h page, the shared page length",
            window.startUtcMillis + GuideGrid.PAGE_MS,
            next!!.startUtcMillis
        )
        assertEquals(
            "and the span is still six hours - the page scrolls, it does not resize",
            6 * hour,
            next.durationMs
        )
    }

    @Test
    fun `paging left at the now-window is still a no-op`() {
        val now = 1_700_000_000_000L
        val window = GridWindow.opening(now)

        assertEquals(
            "F2c: the floor is unchanged - no time before the current hour",
            window,
            window.paged(
                direction = -1,
                floorStartUtcMillis = window.startUtcMillis,
                maxEndUtcMillis = null,
            )
        )
    }

    // ── T7: the zoom's symbols are gone ───────────────────────────────────

    @Test
    fun `the zoom's constants and its cycle are removed from the rules`() {
        listOf("DEFAULT_WINDOW_HOURS", "ZOOM_WINDOW_HOURS", "nextZoomHours", "withHours")
            .forEach { symbol ->
                assertFalse(
                    "F2b: $symbol is dead code once there is one span",
                    rules.contains(symbol)
                )
            }
        assertTrue(
            "F1a/F2a: their replacements are the two fixed constants",
            rules.contains("internal const val GRID_SCALE_DP_PER_MIN = 8") &&
                rules.contains("internal const val GRID_SPAN_HOURS = 6")
        )
        assertFalse(
            "F2a: and neither grid takes a span from its screen any more",
            tvGrid.contains("windowHours: Int") || phoneGrid.contains("windowHours: Int")
        )
        assertTrue(
            "both hand the rule the fixed span instead",
            tvGrid.contains("windowHours = GRID_SPAN_HOURS") &&
                phoneGrid.contains("windowHours = GRID_SPAN_HOURS")
        )
    }

    // ── Source helpers ────────────────────────────────────────────────────

    private fun source(path: String): String {
        val file = File(findSourceRoot(), path)
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
