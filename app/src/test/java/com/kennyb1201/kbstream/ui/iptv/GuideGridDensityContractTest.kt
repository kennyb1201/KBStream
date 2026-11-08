package com.kennyb1201.kbstream.ui.iptv

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The grid's density pass (spec F1-F4, T1-T3), pinned from the sources.
 *
 * The field report was "only four channels at once". The rows are at their
 * floor - 48dp with the score line inside - so every dp here comes from the
 * chrome: the band 180 -> 135 (about a row), the time header 30 -> 24, the row
 * gap 2 -> 0 and the strip era's 12dp bottom safe area -> 4. What must hold:
 *
 *  - the four numbers above, with the 48dp row untouched (T1),
 *  - the detail block still complete in the shorter band, preview on and off
 *    (T2) - it keeps its own 128dp of text height and nothing is cut,
 *  - the preview still the last-played channel, with audio, still toggled from
 *    the header (T3).
 *
 * Source-reading style, like the guide's other contract tests: this module has
 * no Compose test harness, so the shape is asserted and the field settles it.
 */
class GuideGridDensityContractTest {

    private val grid = source("com/kennyb1201/kbstream/ui/iptv/GuideGridView.kt")
    private val screen = source("com/kennyb1201/kbstream/ui/iptv/GuideScreen.kt")

    private val band = grid.slice("private fun GridTopBand(", "private fun GridPreview(")
    private val preview = grid.slice("private fun GridPreview(", " * F14: the focused program")
    private val detail = grid.substringAfter("private fun GridDetailBlock(")
    private val rows = grid.slice("Column(modifier = modifier.fillMaxSize()", "private fun GridTopBand(")

    /** The rows LazyColumn's own parameters, apart from the sidebar's list. */
    private val rowList = grid.slice("state = rowListState,", "itemsIndexed(items = rows")

    // ── T1: the four sizes, and the row that does not move ─────────────────

    @Test
    fun `the band is the 240 by 135 preview box and the block is not cut to fit`() {
        assertTrue(
            "F1a: the preview box shrinks to 240x135",
            grid.contains("private val GRID_PREVIEW_WIDTH: Dp = 240.dp") &&
                grid.contains("private val GRID_PREVIEW_HEIGHT: Dp = 135.dp")
        )
        assertEquals(
            "F1a: still 16:9, only smaller",
            240 * 9,
            135 * 16
        )
        assertTrue(
            "F1b: the band is exactly that box, 135dp",
            grid.contains("private val GRID_BAND_WITH_PREVIEW: Dp = 135.dp")
        )
        assertTrue(
            "F1b: the detail block keeps its own 128dp text height, untouched",
            grid.contains("private val GRID_BAND_TEXT_HEIGHT: Dp = 128.dp")
        )
        assertTrue(
            "F1b: the band leaves the block room rather than clipping it",
            135 > 128 && band.contains(
                "height(if (channel != null) GRID_BAND_WITH_PREVIEW else GRID_BAND_TEXT_HEIGHT)"
            )
        )
        assertTrue(
            "the preview is drawn at its own size inside the band, not stretched",
            band.contains(".width(GRID_PREVIEW_WIDTH)") &&
                band.contains(".height(GRID_PREVIEW_HEIGHT)")
        )
    }

    @Test
    fun `the time header is 24dp and the rows sit adjacent`() {
        assertTrue(
            "F2a: one constant, both the header row and its scrolling row",
            grid.contains("private val GRID_TIME_HEADER_HEIGHT: Dp = 24.dp") &&
                grid.contains("Modifier.fillMaxWidth().height(GRID_TIME_HEADER_HEIGHT)") &&
                grid.contains(".height(GRID_TIME_HEADER_HEIGHT)")
        )
        assertFalse(
            "F2a: the 30dp header is gone, not sitting beside the new one",
            grid.contains("height(30.dp)")
        )
        assertTrue(
            "F2a: the hour labels are still labelMedium with a day line above",
            grid.contains("style = MaterialTheme.typography.labelMedium") &&
                grid.contains("mark.dayLabel?.let { day ->")
        )
        assertTrue(
            "F3a: the rows list has no 2dp gap left (the sidebar's own 4dp stays)",
            !rowList.contains("verticalArrangement") &&
                !rows.contains("verticalArrangement = Arrangement.spacedBy(2.dp)") &&
                !grid.contains("spacedBy(2.dp)")
        )
        assertTrue(
            "F3a: the row itself is still 48dp, the score line's floor",
            grid.contains("private val GRID_ROW_HEIGHT: Dp = 48.dp") &&
                rows.contains("height(GRID_ROW_HEIGHT)")
        )
        assertTrue(
            "F4a: 4dp under the grid, not the strip era's 12",
            grid.contains(".fillMaxSize().padding(bottom = 4.dp)") &&
                !grid.contains("padding(bottom = 12.dp)")
        )
        assertTrue(
            "the LazyColumn's 6dp is content padding, not this margin - both still here",
            grid.contains("contentPadding = PaddingValues(bottom = 6.dp)")
        )
    }

    @Test
    fun `the savings are a little over a row before the per-row gap is counted`() {
        val bandSaved = 180 - 135
        val headerSaved = 30 - 24
        val bottomSaved = 12 - 4
        assertEquals("F1c", 45, bandSaved)
        assertEquals("F2b", 6, headerSaved)
        assertEquals("F4b", 8, bottomSaved)
        assertTrue(
            "F1-F4: 59dp of fixed chrome back, plus the 2dp each row no longer " +
                "spends - more than one full 48dp row",
            bandSaved + headerSaved + bottomSaved > 48
        )
    }

    // ── T2: the block is complete in the band that remains ─────────────────

    @Test
    fun `the detail block keeps all four of its elements in the shorter band`() {
        assertTrue(
            "the title still reserves two lines",
            detail.contains("minLines = 2") &&
                detail.contains("maxLines = 2") &&
                detail.contains("style = MaterialTheme.typography.titleMedium")
        )
        assertTrue(
            "the meta line is still there",
            detail.contains("gridDetailMeta(channelName, cell, nowMs)")
        )
        assertTrue(
            "and still one line",
            detail.contains("overflow = TextOverflow.Ellipsis")
        )
        assertTrue(
            "the description keeps its two lines",
            detail.contains("style = MaterialTheme.typography.bodySmall")
        )
        assertTrue(
            "the live progress bar survives the smaller band",
            detail.contains("liveFillFraction(cell, nowMs)")
        )
        assertTrue(
            "the block never sets a height of its own - the band owns it, so " +
                "focus cannot resize it and nothing is clipped by a fixed rule",
            !detail.contains("GRID_BAND_WITH_PREVIEW") && !detail.contains("GRID_BAND_TEXT_HEIGHT")
        )
        assertTrue(
            "the block is handed the band's full height",
            band.contains("GridDetailBlock(") &&
                band.contains("modifier = Modifier.weight(1f).fillMaxHeight()")
        )
    }

    // ── T3: the preview is still the preview, only smaller ─────────────────

    @Test
    fun `the preview is still last played, still audible, still toggled`() {
        assertTrue(
            "F1a/T3: still the last channel watched, from the recents queue",
            screen.contains("val key = recentChannelKeys.firstOrNull() ?: return@remember null")
        )
        assertTrue(
            "T3: still with audio",
            preview.contains("player.volume = 1f")
        )
        assertTrue(
            "T3: still on by default and still the header's toggle",
            screen.contains("guidePreferences.getBoolean(\"grid_preview\", true)") &&
                screen.contains("Icons.Filled.VisibilityOff")
        )
        assertTrue(
            "T3: off means no preview drawn and the band's 128dp text height",
            band.contains("previewChannel?.takeIf { previewEnabled }") &&
                band.contains("if (channel != null) GRID_BAND_WITH_PREVIEW else GRID_BAND_TEXT_HEIGHT")
        )
        assertTrue(
            "T3: nothing in the preview became focusable - it is display only",
            !preview.contains("focusable") && !preview.contains("FocusRequester")
        )
    }

    // ── source access ─────────────────────────────────────────────────────

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
