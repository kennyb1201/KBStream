package com.kennyb1201.kbstream.ui.iptv

import com.kennyb1201.kbstream.data.iptv.db.EpgProgramRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * The timeline grid's rules, pinned as behaviour (spec T1, T2, T3, T7).
 *
 * The grid is the guide's first two-axis focus surface, and the only place in
 * the app where a cell's width is a function of the data rather than the theme.
 * All of that is [GuideGridRules]' job, which is why it is Compose-free: these
 * tests run on the JVM, and none of them needs an emulator or an EPG database.
 */
class GuideGridRulesTest {

    private val minute = 60_000L
    private val hour = GuideGrid.HOUR_MS

    private fun at(hour: Int, minuteOfHour: Int = 0, dayOfMonth: Int = 7): Long {
        val cal = Calendar.getInstance()
        cal.set(2026, Calendar.NOVEMBER, dayOfMonth, hour, minuteOfHour, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun program(
        start: Long,
        minutes: Int,
        title: String = "Program",
        channel: String = "ch1",
    ) = GridProgram(
        channelKey = channel,
        title = title,
        description = null,
        startUtcMillis = start,
        endUtcMillis = start + minutes * minute,
    )

    // ── T1: cell width ────────────────────────────────────────────────────

    @Test
    fun `a 30 minute program is 240dp wide at 8dp per minute`() {
        val start = at(18)
        assertEquals(240, cellWidthDp(start, start + 30 * minute))
    }

    @Test
    fun `a two hour film is 960dp wide`() {
        val start = at(18)
        assertEquals(960, cellWidthDp(start, start + 120 * minute))
    }

    @Test
    fun `a five minute short renders at the minimum width but keeps its real span`() {
        val start = at(18)
        val end = start + 5 * minute

        // 5 * 4 = 20dp, under the 56dp floor.
        assertEquals(GuideGrid.MIN_CELL_WIDTH_DP, cellWidthDp(start, end))

        // The floor is geometry only: the cell still carries the real start/end,
        // so focus and the detail strip see the truth.
        val cell = rowCells("ch1", listOf(program(start, 5)), windowFrom(start)).first()
        assertEquals(start, cell.startUtcMillis)
        assertEquals(end, cell.endUtcMillis)
    }

    // ── Window geometry ───────────────────────────────────────────────────

    private fun windowFrom(start: Long) =
        GridWindow(start, start + GRID_SPAN_HOURS * hour)

    @Test
    fun `the grid opens at the top of the hour, six hours wide`() {
        val now = at(18, 42)
        val window = GridWindow.opening(now)

        assertEquals(at(18), window.startUtcMillis)
        assertEquals(at(18) + 6 * hour, window.endUtcMillis)
        // The program in progress is on screen, not scrolled away.
        assertTrue(window.contains(now))
    }

    @Test
    fun `cells are clipped to the window and gaps stay gaps`() {
        val windowStart = at(18)
        val window = windowFrom(windowStart)
        // A film that started at 17:00 runs two hours; the next starts 20:00 with
        // a half-hour hole before it.
        val cells = rowCells(
            "ch1",
            listOf(
                program(at(17), 120, title = "Film"),
                program(at(20, 30), 60, title = "Late"),
            ),
            window,
        )

        // Film (clipped to the left edge), the hole before Late, Late, and the
        // empty tail to the window's right edge.
        assertEquals(4, cells.size)
        assertEquals("Film", cells[0].title)
        assertEquals(windowStart, cells[0].startUtcMillis)
        assertEquals(at(19), cells[0].endUtcMillis)
        assertTrue(cells[1].isGap)
        assertEquals(at(19), cells[1].startUtcMillis)
        assertEquals(at(20, 30), cells[1].endUtcMillis)
        assertEquals("Late", cells[2].title)
        assertTrue(cells[3].isGap)
        assertEquals(at(21, 30), cells[3].startUtcMillis)
    }

    @Test
    fun `a program entirely outside the window is dropped`() {
        val window = windowFrom(at(18))
        // Starts exactly when the window ends: it is not part of this view.
        val cells = rowCells("ch1", listOf(program(at(18) + 6 * hour, 60)), window)

        // One gap covering the whole window, no phantom cell.
        assertEquals(1, cells.size)
        assertTrue(cells[0].isGap)
        assertEquals(window.startUtcMillis, cells[0].startUtcMillis)
        assertEquals(window.endUtcMillis, cells[0].endUtcMillis)
    }

    // ── T2: vertical target selection ─────────────────────────────────────

    @Test
    fun `up and down land on the program airing at the same time`() {
        val start = at(18)
        val focused = rowCells("a", listOf(program(start, 60, title = "A18")), windowFrom(start))[0]
        val rows = listOf(
            listOf(focused),
            rowCells(
                "b",
                listOf(
                    program(start, 30, title = "B18"),
                    program(start + 30 * minute, 30, title = "B1830"),
                ),
                windowFrom(start),
            ),
        )

        val down = verticalTarget(rows, fromRow = 0, fromCell = focused, direction = 1)
        assertNotNull(down)
        assertEquals(1, down!!.rowIndex)
        // The containing program, not the greatest overlap (both overlap fully).
        assertEquals("B18", down.cell.title)

        // And back up from the second half lands on the same containing program
        // in the row above.
        val back = verticalTarget(rows, fromRow = 1, fromCell = rows[1][1], direction = -1)
        assertNotNull(back)
        assertEquals("A18", back!!.cell.title)
    }

    @Test
    fun `with a gap the target is the greatest overlap, ties to the earlier start`() {
        val windowStart = at(18)
        val rows = listOf(
            // Focused on a gap from 18:50 to 19:00.
            rowCells(
                "a",
                listOf(
                    program(windowStart, 50, title = "A1850"),
                    program(at(19), 60, title = "A19"),
                ),
                windowFrom(windowStart),
            ),
            rowCells(
                "b",
                listOf(
                    program(at(18), 40, title = "B1840"),
                    program(at(18, 40), 20, title = "B1840b"),
                ),
                windowFrom(windowStart),
            ),
        )

        val gap = rows[0].first { it.isGap && it.startUtcMillis == at(18, 50) }
        val down = verticalTarget(rows, fromRow = 0, fromCell = gap, direction = 1)

        assertNotNull(down)
        // 18:40-19:00 overlaps the gap fully (20 min) and 18:00-18:40 not at all.
        assertEquals("B1840b", down!!.cell.title)
    }

    /**
     * REPOINTED (2026-11-12, build 262): this used to pin that an empty row is
     * SKIPPED for the next one with programs, which is the field bug - two
     * channels with no EPG data sat at the top of the guide, unreachable from
     * below, and a UP that skipped them found nothing and ejected focus to the
     * header. The empty row is drawn, has a channel and has a focusable gap
     * cell, so it is a stop; the row beyond it is reached by pressing again.
     */
    @Test
    fun `an empty row is a stop of its own, not a skip`() {
        val start = at(18)
        val focused = rowCells("a", listOf(program(start, 60, title = "A")), windowFrom(start))[0]
        val rows = listOf(
            listOf(focused),
            rowCells("b", emptyList(), windowFrom(start)),
            rowCells("c", listOf(program(start, 60, title = "C")), windowFrom(start)),
        )

        val down = verticalTarget(rows, fromRow = 0, fromCell = focused, direction = 1)
        assertNotNull("the empty row must be landable", down)
        assertEquals(1, down!!.rowIndex)
        assertTrue("and the landing cell is its gap", down.cell.isGap)

        // Pressing again continues past it, into the row that has programs.
        val beyond = verticalTarget(rows, fromRow = 1, fromCell = down.cell, direction = 1)
        assertNotNull(beyond)
        assertEquals(2, beyond!!.rowIndex)
        assertEquals("C", beyond.cell.title)

        // And back up lands on the empty row again rather than skipping it.
        val back = verticalTarget(rows, fromRow = 2, fromCell = rows[2][0], direction = -1)
        assertNotNull(back)
        assertEquals(1, back!!.rowIndex)
        assertTrue(back.cell.isGap)

        // At the last row a Down has no target at all: a defined edge no-op.
        assertNull(verticalTarget(rows, fromRow = 2, fromCell = rows[2][0], direction = 1))
    }

    @Test
    fun `up through empty rows never runs out of targets above the first row`() {
        // S2: from a program row with nothing but gap-only rows above it, UP
        // lands on the nearest one. Null here is what made the caller eject focus
        // to the header from halfway down the guide.
        val start = at(18)
        val rows = listOf(
            rowCells("a", emptyList(), windowFrom(start)),
            rowCells("b", emptyList(), windowFrom(start)),
            rowCells("c", listOf(program(start, 60, title = "C")), windowFrom(start)),
        )

        val up = verticalTarget(rows, fromRow = 2, fromCell = rows[2][0], direction = -1)
        assertNotNull(up)
        assertEquals(1, up!!.rowIndex)

        // S4: only the FIRST row has nothing above it - that is the one case the
        // caller turns into the header.
        assertNull(verticalTarget(rows, fromRow = 0, fromCell = rows[0][0], direction = -1))
    }

    @Test
    fun `a row with programs still picks the program, not its gaps`() {
        val start = at(18)
        val focused = rowCells("a", listOf(program(start, 60, title = "A")), windowFrom(start))[0]
        val rows = listOf(
            listOf(focused),
            rowCells(
                "b",
                listOf(
                    program(start, 20, title = "B0"),
                    program(start + 40 * minute, 20, title = "B40"),
                ),
                windowFrom(start),
            ),
        )

        // 18:50 on the row below is a gap between the two programs; the target
        // must still be a program, exactly as before the empty-row fix.
        val down = verticalTarget(rows, fromRow = 0, fromCell = focused, direction = 1)
        assertNotNull(down)
        assertFalse(down!!.cell.isGap)
    }

    @Test
    fun `horizontal adjacency walks slots and stops at the row edges`() {
        val windowStart = at(18)
        val cells = rowCells(
            "ch1",
            listOf(
                program(windowStart, 30, title = "One"),
                program(at(19), 60, title = "Two"),
            ),
            windowFrom(windowStart),
        )

        // One step right of the first program is the 30-minute hole; the step
        // after that is the next program.
        assertTrue(horizontalTarget(cells, cells[0].id, 1)!!.isGap)
        assertEquals("Two", horizontalTarget(cells, cells[1].id, 1)?.title)
        assertNull(horizontalTarget(cells, cells[0].id, -1))
        assertNull(horizontalTarget(cells, cells.last().id, 1))
    }

    // ── T3: window paging ─────────────────────────────────────────────────

    @Test
    fun `right pages three hours forward`() {
        val window = GridWindow.opening(at(18, 10))
        val next = window.paged(direction = 1, floorStartUtcMillis = at(18), maxEndUtcMillis = null)

        assertEquals(at(21), next.startUtcMillis)
        assertEquals(at(21) + 6 * hour, next.endUtcMillis)
    }

    @Test
    fun `left at the now window is a no-op`() {
        val window = GridWindow.opening(at(18, 10))
        val back = window.paged(direction = -1, floorStartUtcMillis = at(18), maxEndUtcMillis = null)

        assertEquals(window, back)
    }

    @Test
    fun `left pages back and clamps to the now window`() {
        val window = windowFrom(at(21))
        val floor = at(18)

        val back = window.paged(direction = -1, floorStartUtcMillis = floor, maxEndUtcMillis = null)
        assertEquals(at(18), back.startUtcMillis)

        // From the 19:00 window a full page would straddle 17:00; the clamp stops
        // at the now window rather than exposing time before it.
        val straddle = windowFrom(at(19))
        assertEquals(at(18), straddle.paged(-1, floor, null).startUtcMillis)
    }

    @Test
    fun `paging stops at the guide's last program`() {
        val window = windowFrom(at(18))
        val maxEnd = at(20)

        // The next page would open at 21:00, past the last imported program.
        assertEquals(window, window.paged(1, at(18), maxEnd))
    }

    // ── Header ────────────────────────────────────────────────────────────

    @Test
    fun `the header labels every hour and dates the first and the day change`() {
        val window = windowFrom(at(22))
        val marks = hourMarks(window)

        assertEquals(6, marks.size)
        assertEquals(0, marks[0].offsetMinutes)
        assertEquals("10 PM", marks[0].label)
        assertNotNull(marks[0].dayLabel)

        // Midnight opens a new day and says so.
        val midnight = marks.first { it.label == "12 AM" }
        assertNotNull(midnight.dayLabel)

        // A mark inside the same day carries no date.
        assertNull(marks.first { it.label == "11 PM" }.dayLabel)
    }

    @Test
    fun `hour labels are 12 hour spellings`() {
        assertEquals("12 AM", hourLabel(0))
        assertEquals("12 PM", hourLabel(12))
        assertEquals("6 PM", hourLabel(18))
        assertEquals("1 AM", hourLabel(1))
    }

    // ── Cell content ──────────────────────────────────────────────────────

    @Test
    fun `the live cell fills with elapsed time and a gap never does`() {
        val start = at(18)
        val live = rowCells("ch1", listOf(program(start, 60)), windowFrom(start))[0]

        assertEquals(0f, liveFillFraction(live, start), 0.001f)
        assertEquals(0.5f, liveFillFraction(live, start + 30 * minute), 0.001f)
        assertEquals(1f, liveFillFraction(live, start + 90 * minute), 0.001f)

        val gap = GridCell(
            id = "gap|x|1|2",
            channelKey = "ch1",
            startUtcMillis = start,
            endUtcMillis = start + 30 * minute,
        )
        assertEquals(0f, liveFillFraction(gap, start + 10 * minute), 0.001f)
        assertEquals("No program data", gridCellHeadline(gap, start))
    }

    // ── Sliding window ────────────────────────────────────────────────────

    @Test
    fun `the sliding window keeps the programs it already has`() {
        val program = program(at(18), 120)
        // A 19:00 window still shows the 18:00-20:00 film, so it is kept.
        assertTrue(keepForWindow(program, windowFrom(at(19))))

        // Two pages later it is entirely behind the view and can be dropped.
        assertFalse(keepForWindow(program, windowFrom(at(24))))
    }

    @Test
    fun `merging a second window read dedupes by channel and start`() {
        val a = program(at(18), 60, title = "A")
        val merged = mergeWindowPrograms(
            existing = listOf(a),
            incoming = listOf(program(at(18), 60, title = "A"), program(at(19), 60, title = "B")),
        )

        assertEquals(listOf("A", "B"), merged.map { it.title })
    }

    // ── T7: group order ───────────────────────────────────────────────────

    @Test
    fun `saved order applies to playlist groups only and pins the three heads`() {
        val labels = listOf("All", "Recent", "Favorites", "News", "Sports", "Movies")

        val applied = applyGroupOrder(labels, listOf("Movies", "News"))

        assertEquals(
            listOf("All", "Recent", "Favorites", "Movies", "News", "Sports"),
            applied,
        )
    }

    @Test
    fun `a stale label is pruned and a new group lands at the end`() {
        val labels = listOf("All", "News", "Sports", "Docs")

        val applied = applyGroupOrder(labels, listOf("Gone", "Sports"))

        assertEquals(listOf("All", "Sports", "News", "Docs"), applied)
        assertEquals(listOf("Sports"), pruneGroupOrder(listOf("Gone", "Sports"), labels))
    }

    @Test
    fun `the pinned heads never move, even if a saved order names them`() {
        val labels = listOf("All", "Recent", "Favorites", "News")

        val applied = applyGroupOrder(labels, listOf("News", "All", "Favorites"))

        assertEquals(listOf("All", "Recent", "Favorites", "News"), applied)
    }

    @Test
    fun `moving a group up and down leaves the ends alone`() {
        val labels = listOf("A", "B", "C")

        assertEquals(listOf("B", "A", "C"), moveGroupOrder(labels, 1, -1))
        assertEquals(listOf("A", "C", "B"), moveGroupOrder(labels, 1, 1))
        // Defined no-ops, not wraps.
        assertEquals(labels, moveGroupOrder(labels, 0, -1))
        assertEquals(labels, moveGroupOrder(labels, 2, 1))
        assertEquals(labels, moveGroupOrder(labels, 5, 1))
    }

    @Test
    fun `group order survives the round trip through its stored form`() {
        val labels = listOf("News", "Sci-Fi \"HD\"", "Kids & Family", "Espa\u00f1ol")

        val encoded = encodeGroupOrder(labels)

        assertTrue(encoded.startsWith("["))
        assertTrue(encoded.endsWith("]"))
        assertEquals(labels, decodeGroupOrder(encoded))
        assertEquals(emptyList<String>(), decodeGroupOrder(null))
        assertEquals(emptyList<String>(), decodeGroupOrder(""))
        assertEquals(emptyList<String>(), decodeGroupOrder("not json"))
        assertEquals(emptyList<String>(), decodeGroupOrder("[unquoted]"))
    }

    @Test
    fun `reorderable labels exclude the pinned heads`() {
        assertEquals(
            listOf("News", "Sports"),
            reorderableGroupLabels(listOf("All", "Recent", "Favorites", "News", "Sports")),
        )
    }

    // ── The cell model carries the program ────────────────────────────────

    @Test
    fun `grid programs keep the row's message fields for the strip`() {
        val start = at(18)
        val rows = listOf(
            EpgProgramRow(
                channelId = "guide-id",
                title = "News at Six",
                description = "The day's headlines.",
                category = "News",
                startUtcMillis = start,
                endUtcMillis = start + 30 * minute,
            )
        )

        val program = gridProgramsOf("playlist-ch", rows).single()

        assertEquals("playlist-ch", program.channelKey)
        assertEquals("The day's headlines.", program.description)
        // Round 3 (F14a): the category rides along for the top block.
        assertEquals("News", program.category)
        // A zero-length row would be invisible and unfocusable; it is dropped.
        assertEquals(
            emptyList<GridProgram>(),
            gridProgramsOf(
                "x",
                listOf(rows.single().copy(endUtcMillis = rows.single().startUtcMillis)),
            ),
        )
    }

    // ── The fixed span and the fixed scale ────────────────────────────────

    @Test
    fun `both grids open on the fixed six-hour span`() {
        val window = GridWindow.opening(at(18))
        assertEquals(6, GRID_SPAN_HOURS)
        assertEquals(6 * hour, window.durationMs)
    }

    @Test
    fun `a page keeps the window's own span`() {
        val window = GridWindow.opening(at(18))
        val page = window.paged(
            direction = 1,
            floorStartUtcMillis = window.startUtcMillis,
            maxEndUtcMillis = null,
        )
        assertEquals(6 * hour, page.durationMs)
        assertEquals(window.startUtcMillis + GuideGrid.PAGE_MS, page.startUtcMillis)
    }

    @Test
    fun `the scale is eight dp a minute, at any span`() {
        assertEquals(8, GRID_SCALE_DP_PER_MIN)
        val start = at(18)
        assertEquals(240, cellWidthDp(start, start + 30 * minute))
        assertEquals(480, cellWidthDp(start, start + 60 * minute))
        assertEquals(960, cellWidthDp(start, start + 120 * minute))
    }

    @Test
    fun `the canvas is the span's width, 2880dp at six hours`() {
        assertEquals(2880, contentWidthDp(GRID_SPAN_HOURS))
        assertEquals(2880, gridContentWidthDp(GridWindow.opening(at(18))))
        // An hour mark is the same 480dp the three-hour window's was.
        assertEquals(480, GuideGrid.MINUTES_PER_HOUR * GRID_SCALE_DP_PER_MIN)
    }

    @Test
    fun `the per-channel cap is 24 at the fixed span`() {
        assertEquals(24, GuideGrid.programsPerWindow(GRID_SPAN_HOURS))
        // The six-hour span is the read path's own default.
        assertEquals(GuideGrid.PER_CHANNEL_LIMIT, GuideGrid.programsPerWindow(GRID_SPAN_HOURS))
    }

    // ── Round 3: the top block's meta line ────────────────────────────────

    @Test
    fun `the meta names the channel, the time and a starts-in`() {
        val now = at(18)
        val cell = GridCell(
            id = "c1",
            channelKey = "ch1",
            startUtcMillis = now + 25 * minute,
            endUtcMillis = now + 85 * minute,
            title = "Film",
            description = null,
            category = "Movies",
        )
        val meta = gridDetailMeta("HBO", cell, now)
        assertTrue(meta, meta.startsWith("HBO · "))
        assertTrue(meta, meta.contains("Starts in 25 min"))
        assertTrue(meta, meta.endsWith(" · Movies"))
    }

    @Test
    fun `the meta says on-now for a live program and invents no rating`() {
        val now = at(18)
        val cell = GridCell(
            id = "c1",
            channelKey = "ch1",
            startUtcMillis = now - 10 * minute,
            endUtcMillis = now + 20 * minute,
            title = "News",
            description = null,
        )
        assertEquals("Sky · On now · 20 min left", gridDetailMeta("Sky", cell, now))
    }
}
