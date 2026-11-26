package com.kennyb1201.kbstream.ui.iptv

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A gap-only row names its channel (spec 2026-11-26, TiviMate parity).
 *
 * Field report: Prime's "24/7 Laughs" is filled on TiviMate and empty on
 * KBStream from the same epg6.xml.gz. The guide source explains it - that
 * `<channel>` carries ZERO `<programme>` entries, so the cell is honestly empty,
 * not mismatched - and TiviMate's answer is to repeat the channel name in the
 * cell rather than leave a hole that reads as a rendering failure.
 *
 * The rule itself is [gridCellTitle], Compose-free and pinned here as behaviour:
 * a gap in a gap-ONLY row resolves to the channel name, a gap in a populated row
 * keeps the surface's own text (blank on the TV, "No program data" on the
 * phone), and a programme resolves to its title. The last tests pin the wiring
 * the JVM cannot see: both surfaces call the one rule, and the phone's
 * inter-programme gaps and focused-cell detail strip still say what they said.
 *
 * Against the previous behaviour this file fails twice by construction - the
 * function it calls did not exist, and the TV cell drew `cell.title.orEmpty()`
 * for every gap.
 */
class GuideGapRowNameContractTest {

    private val minute = 60_000L
    private val now = 1_700_000_000_000L
    private val window = GridWindow.opening(now, windowHours = GRID_SPAN_HOURS)

    private fun program(startUtcMillis: Long, minutes: Int, title: String) = GridProgram(
        channelKey = "ch1",
        title = title,
        description = null,
        startUtcMillis = startUtcMillis,
        endUtcMillis = startUtcMillis + minutes * minute,
    )

    // ── The rule ──────────────────────────────────────────────────────────

    @Test
    fun `a channel with no programmes is one gap cell`() {
        val cells = rowCells("ch1", emptyList(), window)
        assertTrue("a no-data channel is a single cell", cells.size == 1)
        assertTrue("and that cell is a gap", cells.single().isGap)
        assertTrue("so the row is gap-only", isGapOnlyRow(cells))
    }

    @Test
    fun `a gap-only row resolves to the channel name on both surfaces`() {
        val cell = rowCells("ch1", emptyList(), window).single()
        // The TV passes an empty gap text, the phone passes "No program data";
        // a named row must not care which.
        assertEquals("24/7 Laughs", gridCellTitle(cell, "24/7 Laughs", gapOnlyRow = true, gapText = ""))
        assertEquals(
            "24/7 Laughs",
            gridCellTitle(cell, "24/7 Laughs", gapOnlyRow = true, gapText = "No program data"),
        )
    }

    @Test
    fun `a hole between two programmes keeps the surface's own text`() {
        val cells = rowCells(
            "ch1",
            listOf(
                program(window.startUtcMillis + 30 * minute, 30, "Film"),
                program(window.startUtcMillis + 3 * 60 * minute, 30, "News"),
            ),
            window,
        )
        assertFalse("a row with programmes is not gap-only", isGapOnlyRow(cells))
        val holes = cells.filter { it.isGap }
        assertTrue("the span before, between and after the programmes is drawn", holes.isNotEmpty())
        holes.forEach { hole ->
            assertEquals("the TV keeps the hole blank", "", gridCellTitle(hole, "24/7 Laughs", false, ""))
            assertEquals(
                "the phone keeps the hole's old text",
                "No program data",
                gridCellTitle(hole, "24/7 Laughs", false, "No program data"),
            )
        }
    }

    @Test
    fun `a programme cell resolves to its title`() {
        val cells = rowCells("ch1", listOf(program(window.startUtcMillis + 30 * minute, 30, "News at Six")), window)
        val cell = cells.first { !it.isGap }
        assertEquals("News at Six", gridCellTitle(cell, "24/7 Laughs", gapOnlyRow = false, gapText = ""))
        assertEquals(
            "News at Six",
            gridCellTitle(cell, "24/7 Laughs", gapOnlyRow = false, gapText = "No program data"),
        )
    }

    @Test
    fun `a channel whose programmes are all outside the window is gap-only`() {
        val cells = rowCells(
            "ch1",
            listOf(program(window.startUtcMillis - 4 * 60 * minute, 60, "Ended Film")),
            window,
        )
        assertTrue("nothing on screen means nothing to name but the channel", isGapOnlyRow(cells))
        assertEquals(
            "24/7 Laughs",
            gridCellTitle(cells.single(), "24/7 Laughs", gapOnlyRow = true, gapText = ""),
        )
    }

    @Test
    fun `a blank channel name falls back to the surface's own text`() {
        val cell = rowCells("ch1", emptyList(), window).single()
        assertEquals("", gridCellTitle(cell, "   ", gapOnlyRow = true, gapText = ""))
        assertEquals(
            "No program data",
            gridCellTitle(cell, "", gapOnlyRow = true, gapText = "No program data"),
        )
    }

    // ── The wiring both surfaces share ────────────────────────────────────

    @Test
    fun `the TV cell resolves its title through the rule`() {
        val tv = source("com/kennyb1201/kbstream/ui/iptv/GuideGridView.kt")
        assertTrue(
            "the row's channel reaches the cell",
            tv.contains("channelName = row.item.channel.displayName"),
        )
        assertTrue(
            "the row is measured for its gap-only shape",
            tv.contains("val gapOnlyRow = remember(row.cells) { isGapOnlyRow(row.cells) }"),
        )
        assertTrue(
            "and the cell draws the rule's answer, blank elsewhere",
            tv.contains("gridCellTitle(cell, channelName, gapOnlyRow, gapText = \"\")"),
        )
    }

    @Test
    fun `the phone cell resolves its title through the rule`() {
        val mobile = source("com/kennyb1201/kbstream/ui/mobile/GuideGridMobileView.kt")
        assertTrue(
            "the row's channel reaches the cell",
            mobile.contains("channelName = item.channel.displayName"),
        )
        assertTrue(
            "the row is measured for its gap-only shape",
            mobile.contains("val gapOnlyRow = remember(cells) { isGapOnlyRow(cells) }"),
        )
        assertTrue(
            "the cell asks the rule with the phone's own gap text",
            mobile.contains("gapText = \"No program data\""),
        )
        assertTrue(
            "and the name is one line, so it reads as a placeholder",
            mobile.contains("maxLines = if (cell.isGap && gapOnlyRow) 1 else 2"),
        )
    }

    @Test
    fun `the phone's detail strip still calls a gap what it is`() {
        val mobile = source("com/kennyb1201/kbstream/ui/mobile/GuideGridMobileView.kt")
        assertEquals(
            "one rendering of \"No program data\" outside the rule: the tapped gap's strip",
            1,
            Regex(Regex.escape("text = if (cell.isGap) \"No program data\" else cell.title.orEmpty(),"))
                .findAll(mobile)
                .count(),
        )
    }

    @Test
    fun `the TV's detail block still reports a gap as nothing scheduled`() {
        val tv = source("com/kennyb1201/kbstream/ui/iptv/GuideGridView.kt")
        assertTrue(
            "the focused-cell detail is not the cell's title",
            tv.contains("cell.isGap -> \"Nothing scheduled in this span.\""),
        )
    }

    // ── Source access (the module is the working dir under Gradle) ────────

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
