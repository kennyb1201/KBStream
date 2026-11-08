package com.kennyb1201.kbstream.ui.iptv

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone grid's auto-follow (spec T1-T7).
 *
 * The TV grid follows now on its own since `42afa3f3a`; the phone did not. What
 * it had instead was an unconditional hourly re-open, which moved the window on
 * the hour whether the viewer had browsed away or not, and which never moved the
 * viewport at all - so a grid left open could page its data forward while the
 * now-line sat off-screen to the left.
 *
 * This file pins the rule the phone now shares (the same [autoFollowWindow], no
 * mobile fork) and, from the sources, the two things the rule cannot show on the
 * JVM: that the phone grid calls it on the tick with the scroll reset, and that
 * the hourly re-open is gone. S1-S3 are the field's own checks.
 */
class GuideGridMobileFollowContractTest {

    private val minute = 60_000L
    private val hour = GuideGrid.HOUR_MS

    private fun opening(hours: Int, now: Long = 1_700_000_000_000L) =
        GridWindow.opening(now, windowHours = hours)

    // ── T1 / T5: a just-left window pages one page at its own zoom ─────────

    @Test
    fun `a tick just past the right edge pages one page at the current zoom`() {
        val window = opening(hours = GuideGrid.DEFAULT_WINDOW_HOURS)
        val next = autoFollowWindow(
            window = window,
            nowUtcMillis = window.endUtcMillis + 30_000L,
            windowHours = GuideGrid.DEFAULT_WINDOW_HOURS,
            scheduleEndMs = null,
        )

        assertNotNull("the phone must follow the clock, as the TV does", next)
        assertEquals(
            "one page, from the window's own start",
            window.startUtcMillis + GuideGrid.PAGE_MS,
            next!!.startUtcMillis
        )
        assertEquals(
            "and the follow lands where now is: the new window contains the tick",
            window.durationMs,
            next.durationMs
        )
        assertTrue(
            "the now-line is inside the window it just moved to",
            next.startUtcMillis <= window.endUtcMillis + 30_000L &&
                window.endUtcMillis + 30_000L < next.endUtcMillis
        )
    }

    @Test
    fun `every zoom level follows at its own width`() {
        listOf(6, 12).forEach { hours ->
            val window = opening(hours = hours)
            val next = autoFollowWindow(
                window = window,
                nowUtcMillis = window.endUtcMillis + 30_000L,
                windowHours = hours,
                scheduleEndMs = null,
            )

            assertNotNull("$hours h must follow too", next)
            assertEquals(
                "$hours h pages the shared page length",
                window.startUtcMillis + GuideGrid.PAGE_MS,
                next!!.startUtcMillis
            )
            assertEquals(
                "$hours h keeps its own span - the zoom does not snap back",
                hours * hour,
                next.durationMs
            )
        }
    }

    // ── T2 / T3: the yanks that are gone ───────────────────────────────────

    @Test
    fun `a tick ten minutes past the edge does not move the window`() {
        val window = opening(hours = GuideGrid.DEFAULT_WINDOW_HOURS)

        assertNull(
            "a viewer away for a while is not dragged forward",
            autoFollowWindow(window, window.endUtcMillis + 10 * minute, 3, null)
        )
    }

    @Test
    fun `a window that still holds now does not move, however far the viewer scrolled`() {
        val window = opening(hours = GuideGrid.DEFAULT_WINDOW_HOURS)

        assertNull(
            "the tick is inside the window, so there is nothing to follow",
            autoFollowWindow(window, window.startUtcMillis + hour, 3, null)
        )
    }

    @Test
    fun `a viewer paged into later programs is never yanked back or forward`() {
        val now = 1_700_000_000_000L
        val paged = GridWindow.opening(now, windowHours = 3)
            .paged(direction = 1, floorStartUtcMillis = now, maxEndUtcMillis = null)

        assertNull(
            "the window does not contain now, and no tick may move it",
            autoFollowWindow(paged, now + 30_000L, 3, null)
        )
    }

    // ── T4: the guide's own end still stops the axis ───────────────────────

    @Test
    fun `a schedule that has ended before the next page does not move`() {
        val window = opening(hours = GuideGrid.DEFAULT_WINDOW_HOURS)

        assertNull(
            "there is no time past the guide's last program to follow to",
            autoFollowWindow(window, window.endUtcMillis + 30_000L, 3, window.endUtcMillis)
        )
    }

    // ── T1 / T2 / T3 / T7: the phone's wiring, read from the source ────────

    @Test
    fun `the phone grid runs the shared rule on its tick and re-anchors the scroll`() {
        val mobile = source("com/kennyb1201/kbstream/ui/mobile/GuideGridMobileView.kt")
        val follow = region(
            mobile,
            "LaunchedEffect(nowMs, window.endUtcMillis, scheduleEndMs)",
            "val rows = remember("
        )

        assertTrue(
            "F1a: the phone runs the SAME rule as the TV, not a fork",
            follow.contains("autoFollowWindow(")
        )
        assertTrue(
            "F1a: on the screen's tick, with the guide's schedule end",
            follow.contains("nowUtcMillis = nowMs") && follow.contains("scheduleEndMs = scheduleEndMs")
        )
        assertTrue(
            "F2a: the follow also re-anchors the view to the window start",
            follow.contains("timeScroll.scrollTo(0)")
        )
        assertTrue(
            "F2a: the window moves first, then the viewport follows it",
            follow.indexOf("window = next") in 0 until follow.indexOf("timeScroll.scrollTo(0)")
        )
        assertTrue(
            "F1b: one crossing fires once, so a tick cannot page twice",
            follow.contains("autoFollowedEdgeMs == window.endUtcMillis") &&
                follow.contains("autoFollowedEdgeMs = window.endUtcMillis")
        )
        assertTrue(
            "F3c: a follow leaves a line to point at",
            follow.contains("Log.d(TAG, \"follow window")
        )
        assertEquals(
            "F2b: nothing else in the phone file scrolls the axis for the viewer",
            1,
            Regex("scrollTo\\(0\\)").findAll(mobile).count()
        )
    }

    @Test
    fun `the hourly re-open is gone`() {
        val mobile = source("com/kennyb1201/kbstream/ui/mobile/GuideGridMobileView.kt")

        assertFalse(
            "F1b: the window no longer re-opens on the hour",
            mobile.contains("remember(nowMs / GuideGrid.HOUR_MS)")
        )
        assertTrue(
            "it opens once, at now",
            mobile.contains("var window by remember { mutableStateOf(GridWindow.opening(")
        )
    }

    @Test
    fun `the phone screen hands the grid the guide's schedule end`() {
        val screen = source("com/kennyb1201/kbstream/ui/mobile/GuideScreenMobile.kt")
        val mobile = source("com/kennyb1201/kbstream/ui/mobile/GuideGridMobileView.kt")

        assertTrue(
            "F1a: the end comes from the ViewModel read the TV grid uses",
            screen.contains("viewModel.guideScheduleEndUtcMillis()")
        )
        assertTrue(
            "and it is passed to the grid",
            screen.contains("scheduleEndMs = guideScheduleEndMs")
        )
        assertTrue(
            "which takes it as the same parameter name the TV grid does",
            mobile.contains("scheduleEndMs: Long?")
        )
    }

    // ── T6: the strip's re-resolve is untouched ────────────────────────────

    @Test
    fun `a tapped program is still re-resolved by its air time across a follow`() {
        val mobile = source("com/kennyb1201/kbstream/ui/mobile/GuideGridMobileView.kt")
        val resolve = region(mobile, "LaunchedEffect(rows) {", "// The rows this view keeps loaded")

        assertTrue(
            "F2c: a follow moves the window, so the strip re-resolves on the new rows",
            resolve.contains("selected = rows.firstOrNull")
        )
        assertTrue(
            "kept while its time is still on the axis",
            resolve.contains("mid in it.startUtcMillis until it.endUtcMillis")
        )
        assertTrue(
            "and dropped when it is not (the null the whole expression can be)",
            resolve.contains("?.let { cell -> picked.first to cell }")
        )
    }

    // ── T7: the TV's follow is untouched ───────────────────────────────────

    @Test
    fun `the TV grid keeps its own follow, on the one shared rule`() {
        val tv = source("com/kennyb1201/kbstream/ui/iptv/GuideGridView.kt")
        val tvScreen = source("com/kennyb1201/kbstream/ui/iptv/GuideScreen.kt")
        val rules = source("com/kennyb1201/kbstream/ui/iptv/GuideGridRules.kt")

        assertTrue("the TV still follows", tv.contains("autoFollowWindow("))
        assertTrue(
            "and still re-anchors focus rather than the scroll",
            tv.contains("focusLiveOnNextWindow = true")
        )
        assertTrue(
            "the TV screen still hands the grid the schedule end",
            tvScreen.contains("scheduleEndMs = guideScheduleEndMs")
        )
        assertEquals(
            "one definition, in the shared rules - no surface has its own copy",
            1,
            listOf(rules, tv, source("com/kennyb1201/kbstream/ui/mobile/GuideGridMobileView.kt"))
                .sumOf { src -> Regex("fun autoFollowWindow\\(").findAll(src).count() }
        )
    }

    // ── Source helpers ────────────────────────────────────────────────────

    /** [source] between [from] and the first [to] after it, whitespace-normalized. */
    private fun region(source: String, from: String, to: String): String {
        val start = source.indexOf(from)
        assertTrue("missing marker: $from", start >= 0)
        val end = source.indexOf(to, start).takeIf { it > start } ?: source.length
        return source.substring(start, end).replace(Regex("\\s+"), " ")
    }

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
