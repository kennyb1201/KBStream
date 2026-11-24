package com.kennyb1201.kbstream.ui.search

import com.kennyb1201.kbstream.ui.theme.KBFocusChip
import com.kennyb1201.kbstream.ui.theme.KBFocusGlowSmall
import java.io.File
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bottom row of the browse submenu, at the bottom of the grid.
 *
 * Reported from the field: "the browse chips under search are clipped on the
 * bottom - the last row - before it scrolls", and then "after scroll the last
 * row is clipped the same".
 *
 * Both are the same arithmetic, and it was never a scroll bug:
 *
 *  - the grid's height was a round 360dp while a row is `chip + gap`, so the
 *    viewport ended in the middle of a row. At rest that is a sliver of the next
 *    row along the bottom edge; at the end of the list it is the last row's
 *    bottom edge, flush against the clip;
 *  - the cells reserved room for the focus cue HORIZONTALLY only. A focused
 *    chip scales uniformly, so it grows downward too, and in the last row that
 *    growth went straight through the 16dp bottom padding.
 *
 * The fix is [submenuGridHeightDp] (a cap snapped to whole rows, derived from
 * the row the grid measured) plus a cell that reserves its cue's room on both
 * axes and a uniform row height so "a whole number of rows" can mean anything
 * at all. These tests pin the geometry; the wiring test at the bottom pins that
 * the grid actually uses it.
 */
class BrowseChipRowClipContractTest {

    // The chip's two heights, from SearchChip: bodySmall (16sp line height) per
    // label line plus the chip's own 14dp of vertical padding. Two lines is the
    // tallest a submenu chip gets (labelMaxLines = 2).
    private val oneLineChipDp = 16f + 14f
    private val twoLineChipDp = 32f + 14f
    private val gapDp = 16f
    private val budgetDp = 360f

    private val sideGrowth = (KBFocusChip - 1f) / 2f

    /** A cell's height: the chip plus the room its cue needs on both sides. */
    private fun cellHeightDp(chipHeightDp: Float): Float =
        chipHeightDp + 2f * submenuChipCueRoomDp(chipHeightDp)

    private fun capDp(chipHeightDp: Float): Float =
        submenuGridHeightDp(
            rowHeightDp = cellHeightDp(chipHeightDp),
            gapDp = gapDp,
            budgetDp = budgetDp,
            edgePadDp = gapDp
        )

    @Test
    fun `the cap ends on a row boundary, for either chip height`() {
        listOf(oneLineChipDp, twoLineChipDp).forEach { chip ->
            val row = cellHeightDp(chip)
            val cap = capDp(chip)
            // contentPadding (top + bottom) + N rows + the gaps between them,
            // for a WHOLE N: that is what leaves no sliver of a row along the
            // bottom edge.
            val rows = ((cap - 2f * gapDp + gapDp) / (row + gapDp)).roundToInt()
            assertTrue("chip $chip: cap $cap shows no rows at all", rows >= 1)
            assertEquals(
                "chip $chip: cap $cap is not a whole number of ${row}dp rows",
                2f * gapDp + rows * row + (rows - 1) * gapDp,
                cap,
                0.01f
            )
            assertTrue("chip $chip: cap $cap must fit the budget", cap <= budgetDp)
        }
    }

    @Test
    fun `the cap shows more than one whole row, on a TV and at a larger font scale`() {
        // A cap of one row would defeat the point of the grid, and the font
        // scale is what a chip's height really depends on.
        listOf(1f, 1.15f, 1.3f).forEach { scale ->
            val chip = twoLineChipDp * scale
            val cap = capDp(chip)
            val rows = ((cap - 2f * gapDp + gapDp) / (cellHeightDp(chip) + gapDp)).roundToInt()
            assertTrue("scale $scale: only $rows row(s) at $cap", rows >= 2)
        }
    }

    @Test
    fun `a row that cannot fit the budget still gets its whole height`() {
        // A viewport shorter than one row would slice EVERY row it showed,
        // which is the reported bug turned into a permanent condition.
        val giant = 300f
        val cap = submenuGridHeightDp(
            rowHeightDp = giant,
            gapDp = gapDp,
            budgetDp = budgetDp,
            edgePadDp = gapDp
        )
        assertEquals(2f * gapDp + giant, cap, 0.01f)
        // Degenerate inputs answer the budget rather than a negative height.
        assertEquals(budgetDp, submenuGridHeightDp(0f, gapDp, budgetDp, gapDp), 0.01f)
    }

    @Test
    fun `a focused chip's scale and glow fit inside its own cell, vertically`() {
        // The horizontal half of this is pinned by BrowseChipCueRoomTest. The
        // vertical half is the reported clip: the scale is uniform, so the
        // overhang is the same closed form applied to the chip's HEIGHT.
        listOf(oneLineChipDp, twoLineChipDp, twoLineChipDp * 1.3f).forEach { chip ->
            val room = submenuChipCueRoomDp(chip)
            val overhang = sideGrowth * chip + KBFocusGlowSmall.value
            assertTrue(
                "chip $chip: room $room must cover overhang $overhang",
                room >= overhang - 0.01f
            )
            val cell = chip + 2f * room
            assertTrue(
                "chip $chip: the cell ($cell) holds the chip and its room",
                cell >= chip + 2f * overhang - 0.01f
            )
        }
    }

    @Test
    fun `the last row is whole when the grid is scrolled to its end`() {
        // At the end of the list the viewport's bottom is the content's bottom,
        // so the last row's bottom edge sits the bottom padding above it. It
        // only counts as whole if that padding plus the last row still fit in
        // the viewport.
        listOf(oneLineChipDp, twoLineChipDp).forEach { chip ->
            val row = cellHeightDp(chip)
            val cap = capDp(chip)
            val rows = (9..20)
            rows.forEach { count ->
                val content = 2f * gapDp + count * row + (count - 1) * gapDp
                // Scrolled to the end: the offset is whatever is left over.
                val offset = content - cap
                if (offset < 0f) return@forEach // shorter than the viewport: no scroll
                val lastRowTop = content - gapDp - row
                assertTrue(
                    "chip $chip, $count rows: the last row's top ($lastRowTop) is " +
                        "above the viewport's top ($offset)",
                    lastRowTop >= offset - 0.01f
                )
            }
        }
    }

    @Test
    fun `the grid takes its height from that rule and its cells reserve the cue`() {
        // The geometry above is only worth anything if the composable uses it.
        val browser = normalized(stripComments(source(BROWSER)))
        val grid = browser
            .substringAfter("private fun SubmenuChipGrid(")
            .substringBefore("private fun browseCategoryIcon(")

        assertTrue(
            "the grid's height comes from the rule, not from a constant",
            grid.contains("submenuGridHeightDp(") &&
                grid.contains("rowHeightDp = cellMinHeightDp") &&
                grid.contains("budgetDp = SUBMENU_CHIP_HEIGHT_BUDGET.value") &&
                grid.contains(".heightIn(max = gridMaxHeight)")
        )
        assertTrue(
            "and the row it derives it from is the chip the grid actually laid out",
            grid.contains("var chipHeightPx by remember(categoryKey) { mutableStateOf(0) }") &&
                grid.contains(".onSizeChanged { size ->") &&
                grid.contains("if (size.height > chipHeightPx) chipHeightPx = size.height")
        )
        assertTrue(
            "every cell reserves the cue's room on BOTH axes, so a focused chip " +
                "in the last row draws its glow inside its own cell instead of " +
                "through the grid's bottom padding",
            grid.contains("val chipVerticalCueRoomDp = submenuChipCueRoomDp(measuredChipHeightDp)") &&
                grid.contains("horizontal = cellCueRoom,") &&
                grid.contains("vertical = chipVerticalCueRoomDp.dp")
        )
        assertTrue(
            "and every cell is at least a full row tall, so a row of short chips " +
                "is the same height as a row of wrapped ones - otherwise a cap " +
                "snapped to whole rows is only whole for one mix of labels",
            grid.contains("modifier = Modifier.heightIn(min = cellMinHeightDp.dp)")
        )
        assertTrue(
            "the grid still scrolls and still reveals: the bottom padding and the " +
                "reveal's cue room are unchanged",
            grid.contains("bottom = SUBMENU_CHIP_GAP,") &&
                grid.contains(
                    "val chipCueRoomPx = with(LocalDensity.current) { KBFocusChipInset.roundToPx() }"
                ) &&
                grid.contains("revealFocusedChipRow(gridState, entryIndex, chipCueRoomPx)")
        )
    }

    private fun source(relativePath: String): String =
        File(findMainSourceRoot(), relativePath).readText()

    private fun stripComments(text: String): String =
        text.lineSequence()
            .joinToString("\n") { line ->
                val marker = line.indexOf("//")
                if (marker < 0) line else line.substring(0, marker)
            }

    private fun normalized(text: String): String =
        text.replace(Regex("\\s+"), " ").trim()

    private fun findMainSourceRoot(): File {
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
        const val BROWSER = "com/kennyb1201/kbstream/ui/search/BrowseBrowser.kt"
    }
}
