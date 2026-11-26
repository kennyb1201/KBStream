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
 * The guide's ruler marks the half-hours (2026-11-26).
 *
 * Field request: "add the half hr marks to the guide". The ruler was hourly
 * only, so a program slot at 3:45 sat between two labels four hundred and eighty
 * dp apart - readable as a position, but not as a time.
 *
 * The marks are emitted by [hourMarks], so that is where the behaviour is
 * asserted: a fixed 3 PM-9 PM window must read `3 PM, 3:30 PM, 4 PM, 4:30 PM…`
 * on a 12-hour clock and `15:00, 15:30, 16:00, 16:30…` on a 24-hour one, with
 * the date on the hour that opens a day only. The drawing is pinned from the
 * source: the two surfaces style the :30 down, and both keep the ruler's strip
 * height, scroll state and focus story untouched.
 *
 * Against the hourly-only ruler this file fails on the first assertion: six
 * marks, not twelve, and no mark carries `isHalfHour`.
 */
class GuideHalfHourMarkContractTest {

    private val minute = 60_000L

    private fun at(hour: Int, dayOfMonth: Int = 8): Long {
        val cal = Calendar.getInstance()
        cal.set(2026, Calendar.NOVEMBER, dayOfMonth, hour, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private val window = GridWindow.opening(at(15), windowHours = 6)

    // ── The marks ─────────────────────────────────────────────────────────

    @Test
    fun `the twelve-hour ruler marks every half-hour of a six-hour window`() {
        val marks = hourMarks(window, use24Hour = false)

        assertEquals(
            "2026-11-26: double the hourly-only list - one mark per half-hour",
            12,
            marks.size
        )
        assertEquals(
            "at 8dp a minute the marks tile the window exactly",
            (0 until 12).map { it * 30 },
            marks.map { it.offsetMinutes }
        )
        assertEquals(
            "3 PM, 3:30 PM, 4 PM, 4:30 PM…",
            listOf(
                "3 PM", "3:30 PM", "4 PM", "4:30 PM", "5 PM", "5:30 PM",
                "6 PM", "6:30 PM", "7 PM", "7:30 PM", "8 PM", "8:30 PM"
            ),
            marks.map { it.label }
        )
        assertEquals(
            "the hours are the even positions, the halves the odd ones",
            List(12) { it % 2 == 1 },
            marks.map { it.isHalfHour }
        )
    }

    @Test
    fun `the twenty-four-hour ruler flips the halves with the hours`() {
        val marks = hourMarks(window, use24Hour = true)

        assertEquals(
            "15:00, 15:30, 16:00, 16:30…",
            listOf(
                "15:00", "15:30", "16:00", "16:30", "17:00", "17:30",
                "18:00", "18:30", "19:00", "19:30", "20:00", "20:30"
            ),
            marks.map { it.label }
        )
        assertFalse(
            "a 24-hour guide must never show 3:30 PM over cells that say 15:30",
            marks.any { it.label.contains(" AM") || it.label.contains(" PM") }
        )
    }

    @Test
    fun `the date stays on the hour that opens a day, never on its half`() {
        val marks = hourMarks(window, use24Hour = false)

        val opening = marks.first { it.offsetMinutes == 0 }
        assertNotNull("the opening hour carries the date", opening.dayLabel)
        assertNull(
            "its :30 does not repeat it",
            marks.first { it.offsetMinutes == 30 }.dayLabel
        )
        assertEquals(
            "and nothing else in the window grows a date",
            1,
            marks.count { it.dayLabel != null }
        )

        // Midnight is an hour mark, so the day change dates itself - and its
        // :30 stays bare.
        val across = hourMarks(GridWindow.opening(at(15), windowHours = 12), use24Hour = false)
        val midnight = across.first { it.label == "12 AM" }
        assertNotNull("the day change carries the date", midnight.dayLabel)
        assertNull(
            "12:30 AM does not",
            across.first { it.label == "12:30 AM" }.dayLabel
        )
        assertEquals(
            "two days in the window, two dates",
            2,
            across.count { it.dayLabel != null }
        )
    }

    @Test
    fun `a window that opens mid-hour starts on the next boundary`() {
        // A paged window can keep its own left edge; here 3:17 PM. (Constructed
        // directly: GridWindow.opening() floors to the hour, which is the case
        // this one is not.)
        val start = at(15) + 17 * minute
        val paged = GridWindow(start, start + 6 * GuideGrid.HOUR_MS)
        val marks = hourMarks(paged, use24Hour = false)

        assertEquals(
            "no mark is drawn before the window's left edge",
            13,
            marks.first().offsetMinutes
        )
        assertEquals("3:30 PM", marks.first().label)
        assertTrue("and it is a half-hour mark", marks.first().isHalfHour)
        assertNull(
            "there was no hour mark to date, so the next hour takes the date",
            marks.first().dayLabel
        )
        assertNotNull(
            "4 PM opens the ruler's day",
            marks.first { !it.isHalfHour }.dayLabel
        )
    }

    @Test
    fun `hourLabel keeps the whole hours bare and spells the halves out`() {
        assertEquals("3 PM", hourLabel(15, use24Hour = false))
        assertEquals("3:30 PM", hourLabel(15, use24Hour = false, minuteOfHour = 30))
        assertEquals("12:30 AM", hourLabel(0, use24Hour = false, minuteOfHour = 30))
        assertEquals("12 PM", hourLabel(12, use24Hour = false))
        assertEquals("15:00", hourLabel(15, use24Hour = true))
        assertEquals("15:30", hourLabel(15, use24Hour = true, minuteOfHour = 30))
    }

    // ── The drawing ───────────────────────────────────────────────────────

    @Test
    fun `every mark is half an hour wide on both surfaces`() {
        assertEquals(
            "one mark is 30 minutes at 8dp a minute: 240dp",
            30,
            GRID_RULER_MARK_MINUTES
        )
        assertEquals(240, GRID_RULER_MARK_MINUTES * GRID_SCALE_DP_PER_MIN)

        val tv = read(TV)
        val phone = read(PHONE)
        assertTrue(
            "TV: the mark's width is the shared 30-minute span",
            tv.contains("(GRID_RULER_MARK_MINUTES * GRID_SCALE_DP_PER_MIN).dp")
        )
        assertTrue(
            "phone: the same span, in its own density variable",
            phone.contains("(GRID_RULER_MARK_MINUTES * pxPerMin).dp")
        )
        assertFalse(
            "and neither surface still sizes a mark as an hour",
            tv.contains("(GuideGrid.MINUTES_PER_HOUR * GRID_SCALE_DP_PER_MIN).dp") ||
                phone.contains("(GuideGrid.MINUTES_PER_HOUR * pxPerMin).dp")
        )
    }

    @Test
    fun `the half-hour label is subordinate, the hour keeps its style`() {
        val tv = tvRuler
        val phone = phoneRuler

        assertTrue(
            "TV: the hour keeps labelMedium, the :30 is the same type dimmed",
            tv.contains("style = MaterialTheme.typography.labelMedium") &&
                tv.contains("KBTextLo.copy(alpha = 0.65f)")
        )
        assertTrue(
            "phone: the hour keeps labelSmall, the :30 is dimmed the same way",
            phone.contains("style = MaterialTheme.typography.labelSmall") &&
                phone.contains("KBTextLo.copy(alpha = 0.65f)")
        )
        assertTrue(
            "both read the mark's kind rather than parsing its label",
            tv.contains("mark.isHalfHour") && phone.contains("mark.isHalfHour")
        )
        assertTrue(
            "single line, no wrap, on both",
            tv.contains("maxLines = 1") && tv.contains("softWrap = false") &&
                phone.contains("maxLines = 1")
        )
    }

    @Test
    fun `the ruler keeps its strip, its shared scroll and its focus story`() {
        val tv = read(TV)
        val phone = read(PHONE)

        assertEquals(
            "TV: the strip is still 24dp - the marks changed, not the header",
            1,
            tv.countOccurrences("private val GRID_TIME_HEADER_HEIGHT: Dp = 24.dp")
        )
        assertTrue(
            "TV: the header still rides the rows' one timeScroll",
            2 <= tv.countOccurrences("Modifier.horizontalScroll(timeScroll)")
        )
        assertTrue(
            "phone: its 26dp strip is untouched",
            phone.contains("Row(modifier = Modifier.fillMaxWidth().height(26.dp))")
        )
        listOf("tv" to tvRuler, "phone" to phoneRuler).forEach { (surface, region) ->
            listOf("focusRequester", "requestFocus", "focusable(").forEach { marker ->
                assertFalse(
                    "$surface: the ruler was never a focus stop and must not become one ($marker)",
                    region.contains(marker)
                )
            }
        }
    }

    // ── Source access ─────────────────────────────────────────────────────

    /** The TV ruler: its mark loop and the Text that draws each label. */
    private val tvRuler: String by lazy {
        read(TV).slice("hourMarks(window, rememberUse24HourClock())", "// Round four F5")
    }

    private val phoneRuler: String by lazy {
        read(PHONE).slice("hourMarks(window, rememberUse24HourClock())", "The next channels' logos")
    }

    private fun String.slice(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        assertTrue("$startMarker is missing", start >= 0)
        val end = indexOf(endMarker, start)
        assertTrue("$endMarker must follow $startMarker", end > start)
        return substring(start, end)
    }

    private fun String.countOccurrences(needle: String): Int {
        var count = 0
        var at = indexOf(needle)
        while (at >= 0) {
            count++
            at = indexOf(needle, at + needle.length)
        }
        return count
    }

    private fun read(relative: String): String {
        val file = File(findSourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
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

    private companion object {
        const val TV = "com/kennyb1201/kbstream/ui/iptv/GuideGridView.kt"
        const val PHONE = "com/kennyb1201/kbstream/ui/mobile/GuideGridMobileView.kt"
    }
}
