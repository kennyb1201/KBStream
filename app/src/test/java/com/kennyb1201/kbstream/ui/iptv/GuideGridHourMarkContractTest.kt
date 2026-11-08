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
 * The time header's hour marks (header-clip spec F1, T1-T3).
 *
 * The field report: "3 PM" under "Sun Nov 8" was clipped at the header's bottom
 * edge while "4 PM" beside it was clean. The compaction left the strip 24dp and
 * the boundary mark was a two-line column (day above hour), which needs ~28-31dp
 * of Inter - only the mark carrying a date had a second line, which is why only
 * it was cut.
 *
 * The fix is the phone's shape: one line, `Sun Nov 8 · 3 PM`, accent for the day
 * and plain for the hour. The line text is a rule ([hourMarkLine]) so the zoom
 * cases are asserted as behaviour; the drawing is pinned from the source, which
 * is the only way to see it without a Compose harness.
 */
class GuideGridHourMarkContractTest {

    private val grid = source("com/kennyb1201/kbstream/ui/iptv/GuideGridView.kt")
    private val rules = source("com/kennyb1201/kbstream/ui/iptv/GuideGridRules.kt")

    /** The mark loop inside the sticky time header. */
    private val marks = grid.slice("hourMarks(window).forEach { mark ->", "LazyColumn(")

    private val minute = 60_000L
    private val hour = GuideGrid.HOUR_MS

    private fun at(hour: Int, dayOfMonth: Int = 8): Long {
        val cal = Calendar.getInstance()
        cal.set(2026, Calendar.NOVEMBER, dayOfMonth, hour, 0, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    // ── T1: one line, the day and the hour together ────────────────────────

    @Test
    fun `a mark with a day label draws the day and the hour on one line`() {
        assertEquals(
            "F1a: the phone's own line, for the TV's own mark",
            "Sun Nov 8 · 3 PM",
            hourMarkLine(GridHourMark(offsetMinutes = 0, label = "3 PM", dayLabel = "Sun Nov 8"))
        )
        assertEquals(
            "F1a: an ordinary hour stays the hour alone - no dangling separator",
            "4 PM",
            hourMarkLine(GridHourMark(offsetMinutes = 60, label = "4 PM", dayLabel = null))
        )
    }

    @Test
    fun `the day rides on the hour's line at three, six and twelve hour zoom`() {
        listOf(3, 6, 12).forEach { hours ->
            // 3 PM on Sunday Nov 8, the window the field report opened on.
            val window = GridWindow.opening(at(15), windowHours = hours)
            val zoomMarks = hourMarks(window)
            assertTrue("a $hours h window has marks", zoomMarks.isNotEmpty())

            val opening = zoomMarks.first()
            assertNotNull("the first mark always carries the date", opening.dayLabel)
            assertEquals(
                "F1a: at $hours h the opening mark is one line, day then hour",
                "${opening.dayLabel} · ${opening.label}",
                hourMarkLine(opening)
            )
            assertEquals(
                "F1a: ...and the hour beside it is the hour alone",
                zoomMarks[1].label,
                hourMarkLine(zoomMarks[1])
            )
            assertEquals(
                "F1a: at $hours h the whole window is covered by hour marks",
                (hours).toInt(),
                zoomMarks.size
            )
        }
    }

    @Test
    fun `the next midnight marks its own day inline too`() {
        // 12 h from 3 PM reaches 3 AM, so midnight is inside the window.
        val zoomMarks = hourMarks(GridWindow.opening(at(15), windowHours = 12))
        val midnight = zoomMarks.first { it.label == "12 AM" }

        assertNotNull("S3: the day change still carries the date", midnight.dayLabel)
        assertEquals(
            "S3: and it reads inline, not stacked",
            "${midnight.dayLabel} · 12 AM",
            hourMarkLine(midnight)
        )
        assertEquals(
            "F2b: no other mark of that window grows a date",
            2,
            zoomMarks.count { it.dayLabel != null }
        )
        assertNull(
            "the hours between the two boundaries carry no date",
            zoomMarks.first { it.label == "6 PM" }.dayLabel
        )
    }

    // ── T2: nothing to clip ────────────────────────────────────────────────

    @Test
    fun `the mark is one line drawn in the 24dp strip, so nothing overflows`() {
        assertEquals(
            "T2/F1b: the strip stays 24dp - the fix is the mark, not the strip",
            1,
            grid.countOccurrences("private val GRID_TIME_HEADER_HEIGHT: Dp = 24.dp")
        )
        assertFalse(
            "T2: the mark is no longer a stacked column, which is what clipped",
            marks.contains("Column {")
        )
        assertEquals(
            "T2: exactly one TextView per mark - one line cannot be half cut",
            1,
            marks.countOccurrences("Text(")
        )
        assertTrue(
            "T2/F1a: one line, no wrap, ellipsis as the guard",
            marks.contains("maxLines = 1,") &&
                marks.contains("softWrap = false,") &&
                marks.contains("overflow = TextOverflow.Ellipsis")
        )
        assertTrue(
            "F1a: the day keeps the accent, the hour keeps the plain colour",
            marks.contains("withStyle(SpanStyle(color = KBAccent))") &&
                marks.contains("color = KBTextLo,")
        )
        assertTrue(
            "F1a: the line itself comes from the rule, so the phone and the TV " +
                "cannot spell it differently",
            marks.contains("val line = hourMarkLine(mark)") &&
                rules.contains(
                    "internal fun hourMarkLine(mark: GridHourMark): String =\n" +
                        "    mark.dayLabel?.let { day -> \"\$day · \${mark.label}\" } ?: mark.label"
                )
        )
    }

    // ── T3: everything else about the header is unchanged ──────────────────

    @Test
    fun `the header keeps its height, its shared scroll and the day-boundary rule`() {
        assertTrue(
            "T3/F1b: the header row and its scrolling row still share the one 24dp constant",
            grid.contains("Modifier.fillMaxWidth().height(GRID_TIME_HEADER_HEIGHT)") &&
                grid.contains(".height(GRID_TIME_HEADER_HEIGHT),")
        )
        assertTrue(
            "T3: the sticky header still scrolls with the shared timeScroll, not its own",
            2 <= grid.countOccurrences("Modifier.horizontalScroll(timeScroll)")
        )
        assertFalse(
            "T3: the pre-compaction 30dp header did not come back",
            grid.contains("height(30.dp)")
        )
        assertTrue(
            "S4/T3: the rows are still 48dp and the band still 135dp, so six rows " +
                "still fit - the header did not grow to fix this",
            grid.contains("private val GRID_ROW_HEIGHT: Dp = 48.dp") &&
                grid.contains("private val GRID_BAND_WITH_PREVIEW: Dp = 135.dp")
        )
        assertTrue(
            "F2b: the date still appears only where the day turns",
            rules.contains("val opensDay = lastDay == null || day != lastDay") &&
                rules.contains("dayLabel = if (opensDay) DateFormats.time(millis, GRID_DAY_LABEL) else null")
        )
        assertFalse(
            "F2a: the phone's own mark is untouched by this spec",
            grid.contains("GuideGridMobileView")
        )
    }

    // ── source access ─────────────────────────────────────────────────────

    private fun String.countOccurrences(needle: String): Int {
        var count = 0
        var at = indexOf(needle)
        while (at >= 0) {
            count++
            at = indexOf(needle, at + needle.length)
        }
        return count
    }

    private fun source(relative: String): String {
        val file = File(findSourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun String.slice(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        assertTrue("$startMarker is missing", start >= 0)
        val end = indexOf(endMarker, start)
        assertTrue("$endMarker must follow $startMarker", end > start)
        return substring(start, end)
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
