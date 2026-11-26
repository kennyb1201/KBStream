package com.kennyb1201.kbstream.ui.iptv

import java.io.File
import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guide window re-anchors on the hour rollover (2026-11-26).
 *
 * Field report: the guide was opened at 1 AM and still showed the 1 AM
 * programmes as the live ones when the clock turned 2 AM. The now-line and the
 * live highlight were never stale - [rememberNowMillis] ticks them every 30s -
 * the WINDOW was: it is created once, floored to the hour, and only the F2a
 * edge follow moves it, which is six hours away.
 *
 * The rule is [hourRolloverWindow], pinned here as behaviour: a window that
 * opens on the hour that just ended rolls to the current hour, keeping its
 * span; a viewer who paged the axis keeps the window they chose; a window two
 * or more hours behind is a viewer who has been away and is not dragged
 * forward. The last tests pin what the JVM cannot see: both grids call the one
 * rule on the tick, and the TV re-anchors focus through the existing
 * `focusLiveOnNextWindow` path.
 *
 * Against the previous behaviour this fails by construction: the function it
 * calls did not exist, and nothing moved the axis at the hour.
 */
class GuideHourRolloverContractTest {

    private val hour = GuideGrid.HOUR_MS

    private fun at(hourOfDay: Int, minuteOfHour: Int = 0, second: Int = 0): Long {
        val cal = Calendar.getInstance()
        cal.set(2026, Calendar.NOVEMBER, 26, hourOfDay, minuteOfHour, second)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun window(openedAt: Long, hours: Int = GRID_SPAN_HOURS) =
        GridWindow.opening(openedAt, windowHours = hours)

    // ── The rule ──────────────────────────────────────────────────────────

    @Test
    fun `a window opened at 1 AM rolls to 2 AM on the first tick of the hour`() {
        val next = hourRolloverWindow(
            window = window(at(1)),
            nowUtcMillis = at(2, second = 1),
            windowHours = GRID_SPAN_HOURS,
            viewerPaged = false,
        )
        assertNotNull("the axis must move with the clock", next)
        assertEquals("the left edge is the new hour", at(2), next!!.startUtcMillis)
        assertEquals("and the span is unchanged", at(8), next.endUtcMillis)
    }

    @Test
    fun `the rollover holds for every tick inside the new hour`() {
        val next = hourRolloverWindow(
            window = window(at(1)),
            nowUtcMillis = at(2, minuteOfHour = 59, second = 30),
            windowHours = GRID_SPAN_HOURS,
            viewerPaged = false,
        )
        assertNotNull("2-59 is still the hour that just turned", next)
        assertEquals(at(2), next!!.startUtcMillis)
    }

    @Test
    fun `a viewer who paged the axis keeps the window they chose`() {
        assertNull(
            "the rollover never drags a paged viewer",
            hourRolloverWindow(
                window = window(at(4)),
                nowUtcMillis = at(2, minuteOfHour = 5),
                windowHours = GRID_SPAN_HOURS,
                viewerPaged = true,
            ),
        )
    }

    @Test
    fun `a window two hours behind is a viewer who has been away, not a rollover`() {
        assertNull(
            "1 AM left open until 3 AM is not dragged to 3 AM",
            hourRolloverWindow(
                window = window(at(1)),
                nowUtcMillis = at(3, minuteOfHour = 1),
                windowHours = GRID_SPAN_HOURS,
                viewerPaged = false,
            ),
        )
    }

    @Test
    fun `a window already on the current hour does not move`() {
        assertNull(
            "no-op until the clock crosses the next hour",
            hourRolloverWindow(
                window = window(at(2)),
                nowUtcMillis = at(2, minuteOfHour = 45),
                windowHours = GRID_SPAN_HOURS,
                viewerPaged = false,
            ),
        )
    }

    @Test
    fun `a paged-away window read before the rollover still does not move`() {
        assertNull(
            "the flag, not the window's position, is what protects the viewer",
            hourRolloverWindow(
                window = window(at(1)),
                nowUtcMillis = at(2, minuteOfHour = 5),
                windowHours = GRID_SPAN_HOURS,
                viewerPaged = true,
            ),
        )
    }

    @Test
    fun `the window keeps its own span`() {
        val next = hourRolloverWindow(
            window = window(at(1), hours = 3),
            nowUtcMillis = at(2, minuteOfHour = 5),
            windowHours = 3,
            viewerPaged = false,
        )
        assertNotNull(next)
        assertEquals(at(2), next!!.startUtcMillis)
        assertEquals("a 3-hour window stays 3 hours", 3 * hour, next.durationMs)
    }

    // ── The wiring both grids share ───────────────────────────────────────

    @Test
    fun `the TV grid re-anchors on the tick and through the live-focus path`() {
        val tv = source("com/kennyb1201/kbstream/ui/iptv/GuideGridView.kt")
        val rollover = between(
            tv,
            "2026-11-26: the window is opened on the hour once",
            "LaunchedEffect(focusLiveOnNextWindow, rows)",
        )
        assertTrue("it calls the shared rule", rollover.contains("hourRolloverWindow("))
        assertTrue(
            "it passes the viewer's paging as the rule's guard",
            rollover.contains("viewerPaged = pagedByViewer"),
        )
        assertTrue(
            "and focus re-anchors on the focused row's live program",
            rollover.contains("focusLiveOnNextWindow = true"),
        )
        assertTrue(
            "a manual page sets the flag",
            between(tv, "Key.DirectionRight ->", "Key.DirectionUp, Key.DirectionDown").let {
                it.contains("pagedByViewer = true")
            },
        )
        assertTrue(
            "a move back to live time clears it",
            tv.contains("pagedByViewer = false"),
        )
    }

    @Test
    fun `the phone grid re-anchors on the tick and brings the axis with it`() {
        val mobile = source("com/kennyb1201/kbstream/ui/mobile/GuideGridMobileView.kt")
        val rollover = between(
            mobile,
            "2026-11-26: the window opens on the hour once",
            "// Memoised per channel",
        )
        assertTrue("it calls the shared rule, no mobile fork", rollover.contains("hourRolloverWindow("))
        assertTrue(
            "the phone has no page gesture that moves the window",
            rollover.contains("viewerPaged = false"),
        )
        assertTrue(
            "the viewport follows the window, so the now-line stays on screen",
            rollover.contains("timeScroll.scrollTo(0)"),
        )
        assertTrue(
            "and the window moves first, as it does on a follow",
            rollover.indexOf("window = next") in 0 until rollover.indexOf("timeScroll.scrollTo(0)"),
        )
    }

    @Test
    fun `the unconditional hourly re-open is still gone`() {
        val mobile = source("com/kennyb1201/kbstream/ui/mobile/GuideGridMobileView.kt")
        assertFalse(
            "the old re-open keyed the window remember on the hour and moved every viewer",
            mobile.contains("remember(nowMs / GuideGrid.HOUR_MS)"),
        )
        assertTrue(
            "it opens once, at now",
            mobile.contains("var window by remember { mutableStateOf(GridWindow.opening("),
        )
    }

    // ── Source access (the module is the working dir under Gradle) ────────

    private fun between(text: String, start: String, end: String): String {
        val from = text.indexOf(start)
        assertTrue("marker missing: $start", from >= 0)
        val to = text.indexOf(end, from)
        assertTrue("marker missing after $start: $end", to > from)
        return text.substring(from, to)
    }

    private fun source(path: String): String {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) {
                    val file = File(candidate, path)
                    assertTrue("source missing: $file", file.isFile)
                    return file.readText()
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir"),
        )
    }
}
