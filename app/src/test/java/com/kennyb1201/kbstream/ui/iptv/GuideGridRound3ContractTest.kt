package com.kennyb1201.kbstream.ui.iptv

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The grid's THIRD round (spec F12-F14, T1-T9), pinned from the sources.
 *
 * Three field findings, three answers: the rows were half again as tall as
 * TiviMate's so fewer of them fit; there was nothing playing while you browsed;
 * and the details sat in a strip at the bottom instead of a block at the top.
 * What must hold now is the shape, not the wording:
 *
 *  - a row is 48dp and a cell's content still fits inside it (T1),
 *  - the window opens at three hours and the zoom walks 3/6/12 (T2),
 *  - the preview is the last channel played, toggled from the header and never
 *    driven by focus (T3-T5),
 *  - the D-pad chain is untouched and the preview is not a focus stop (T6),
 *  - the top block's heights are fixed and the block never resizes with focus
 *    (T7-T9).
 *
 * Source-reading style, like the guide's other contract tests: this module has
 * no Compose test harness, so the shape is asserted and the field settles it.
 */
class GuideGridRound3ContractTest {

    private val grid = source("com/kennyb1201/kbstream/ui/iptv/GuideGridView.kt")
    private val screen = source("com/kennyb1201/kbstream/ui/iptv/GuideScreen.kt")
    private val rules = source("com/kennyb1201/kbstream/ui/iptv/GuideGridRules.kt")

    private val header = screen
        .substringAfter("private fun GuideHeader(")
        .substringBefore("private fun GuideHeaderClock(")

    /** The band, its preview and the detail block: the three new composables. */
    private val band = grid.slice("private fun GridTopBand(", "private fun GridPreview(")
    private val preview = grid.slice("private fun GridPreview(", " * F14: the focused program")
    private val detail = grid.substringAfter("private fun GridDetailBlock(")

    private val channelCell = grid.slice("/** The fixed channel column's cell", "One row of program cells")
    private val programCell = grid.slice("private fun ProgramCell(", "private fun GridTopBand(")

    // ── T1: denser rows ────────────────────────────────────────────────────

    @Test
    fun `a row is 48dp and both cells' content still fits inside it`() {
        assertTrue(
            "F12a: TiviMate's own 48dp row, not the old 62",
            grid.contains("private val GRID_ROW_HEIGHT: Dp = 48.dp")
        )
        assertFalse(
            "and the 62dp row is gone rather than sitting beside it",
            grid.contains("GRID_ROW_HEIGHT: Dp = 62.dp")
        )
        assertTrue(
            "F12a: the program cell's 4dp padding - 20 + 16 of text plus 8 is 44 " +
                "of the 48dp row, so the reminder line still fits",
            programCell.contains(".padding(horizontal = 8.dp, vertical = 4.dp)")
        )
        assertTrue(
            "the optional second line is preserved, not dropped to make room",
            programCell.contains("\"REMINDER SET\"")
        )
        assertTrue(
            "the rows are still 2dp apart",
            grid.contains("verticalArrangement = Arrangement.spacedBy(2.dp)")
        )
        assertTrue(
            "F12b: the channel logo is 28dp",
            channelCell.contains(".size(28.dp)")
        )
        val name = channelCell.slice(
            "text = item.channel.displayName.ifBlank { \"Live Channel\" },",
            "val number ="
        )
        assertTrue(
            "F12b: the channel name is labelMedium, so name + number fit one row",
            name.contains("style = MaterialTheme.typography.labelMedium")
        )
        assertTrue(
            "the channel cell keeps its own 4dp padding",
            channelCell.contains(".padding(horizontal = 8.dp, vertical = 4.dp)")
        )
    }

    // ── T2: the zoom, and its 3h default ───────────────────────────────────

    @Test
    fun `the window opens at three hours and the cycle is three six twelve`() {
        assertTrue(
            "F12e: three hours is the default, not six",
            rules.contains("const val DEFAULT_WINDOW_HOURS = 3")
        )
        assertTrue(
            "and the levels are the spec's three, in cycle order",
            rules.contains("val ZOOM_WINDOW_HOURS: List<Int> = listOf(3, 6, 12)")
        )
        assertTrue(
            "the cycle wraps 12 back to 3",
            rules.contains("ZOOM_WINDOW_HOURS[(index + 1) % ZOOM_WINDOW_HOURS.size]")
        )
        assertTrue(
            "an unknown level opens the cycle at its first level instead of sticking",
            rules.contains("if (index < 0) {") && rules.contains("ZOOM_WINDOW_HOURS.first()")
        )
        assertTrue(
            "the grid opens on the level the screen hands it",
            grid.contains("GridWindow.opening(nowMs, windowHours)")
        )
        assertTrue(
            "a zoom change keeps the axis where the viewer left it",
            grid.contains("window.withHours(windowHours)")
        )
        assertTrue(
            "a page keeps the window's OWN span, so a zoomed window stays zoomed",
            rules.contains("val span = durationMs")
        )
        assertTrue(
            "the scrolled content is the window's width, not a fixed 6h one",
            grid.contains("gridContentWidthDp(window).dp")
        )
        assertTrue(
            "the header writes the level per profile",
            screen.contains("guidePreferences.edit()\n                                    .putInt(\"grid_window_hours\", next)")
        )
        assertTrue(
            "and reads it back, defaulting to the spec's three hours",
            screen.contains("guidePreferences.getInt(\"grid_window_hours\", GuideGrid.DEFAULT_WINDOW_HOURS)")
        )
    }

    // ── T3/T4: the preview ─────────────────────────────────────────────────

    @Test
    fun `the preview is the last channel played, never the focused one`() {
        assertTrue(
            "F13b: the head of the per-profile recents queue",
            screen.contains("val key = recentChannelKeys.firstOrNull()")
        )
        assertTrue(
            "and that is what the grid is handed - there is no focused-item wiring",
            grid.contains("previewChannel: IptvChannel?") &&
                screen.contains("previewChannel = gridPreviewChannel")
        )
        assertFalse(
            "the preview reads no focus state",
            preview.contains("focused")
        )
    }

    @Test
    fun `the header toggle turns it off and the choice is remembered`() {
        assertTrue(
            "F13d: a KBCard like the rest of the header, not a bare button",
            header.contains("if (onTogglePreview != null)") &&
                header.contains("KBCard(onClick = onTogglePreview)")
        )
        assertTrue(
            "the eye says the state as well as the action",
            header.contains("Icons.Filled.Visibility") &&
                header.contains("Icons.Filled.VisibilityOff")
        )
        assertTrue(
            "and TalkBack hears which one it is - an icon-only button is silent otherwise",
            header.contains("\"Preview on\"") && header.contains("\"Preview off\"")
        )
        assertTrue(
            "the glyph and padding are the header row's own, so it does not move",
            header.contains("Modifier.padding(horizontal = 12.dp, vertical = 9.dp)") &&
                header.contains("Modifier.size(24.dp)")
        )
        assertTrue(
            "F13d: on by default",
            screen.contains("guidePreferences.getBoolean(\"grid_preview\", true)")
        )
        assertTrue(
            "and the choice is written, so leaving and coming back keeps it",
            screen.contains(".putBoolean(\"grid_preview\", next)")
        )
        assertTrue(
            "grid view only - the list has no preview to turn off",
            screen.contains("onTogglePreview = if (guideView == GuideViewMode.GRID)")
        )
    }

    @Test
    fun `the preview is a preview - no subtitles, no controls, released with the grid`() {
        assertTrue(
            "F13e: live TV starts with subtitles off here too",
            preview.contains("setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)")
        )
        assertTrue(
            "the stream is the channel's own URL and headers, not a re-resolve",
            preview.contains("channel.streamUrl") &&
                preview.contains("setDefaultRequestProperties(channel.headers)")
        )
        assertTrue(
            "F13b: it plays with sound - that is the point of it",
            preview.contains("player.volume = 1f") && preview.contains("player.playWhenReady = true")
        )
        assertTrue(
            "it is not a player: no controls, no seek",
            preview.contains("useController = false") && !preview.contains("seekTo")
        )
        assertTrue(
            "F13g: released when the grid closes, the toggle turns it off, or playback starts",
            preview.contains("DisposableEffect(previewPlayer, channel.streamUrl)") &&
                preview.contains("player.release()")
        )
        assertTrue(
            "OK on a cell still plays that channel full-screen, exactly as before",
            grid.contains("activateCell(row.item, cell)") && grid.contains("-> onPlay(item)")
        )
    }

    // ── T5: a dead feed ────────────────────────────────────────────────────

    @Test
    fun `a dead preview feed leaves the channel's still, once`() {
        assertTrue(
            "F13h: an error flips the box to its still",
            preview.contains("override fun onPlayerError(error: PlaybackException)")
        )
        val onError = preview
            .substringAfter("override fun onPlayerError(error: PlaybackException) {")
            .substringBefore("}")
        assertTrue("the failure is recorded", onError.contains("failed = true"))
        assertTrue("and the player is stopped", onError.contains("player.stop()"))
        assertFalse(
            "no retry, no re-prepare - one attempt per grid open",
            onError.contains("prepare()") || onError.contains("playWhenReady")
        )
        assertTrue(
            "the still is the channel's own logo over its name",
            preview.contains("AsyncImage(") && preview.contains("channel.displayName")
        )
        assertTrue(
            "and nothing here can raise a toast",
            !preview.contains("Toast")
        )
    }

    // ── T6: the focus chain is untouched ───────────────────────────────────

    @Test
    fun `the preview is not a focus stop and the arrows still reach the header`() {
        assertTrue(
            "UP from the first row still reaches the header (round 2's F3g)",
            grid.contains("runCatching { headerFocusRequester.requestFocus() }")
        )
        assertFalse(
            "the preview has no focus machinery of any kind",
            preview.contains("focusable(") ||
                preview.contains("focusRequester(") ||
                preview.contains("onPreviewKeyEvent")
        )
        assertFalse(
            "nor does the detail block",
            detail.contains("focusable(") || detail.contains("onPreviewKeyEvent")
        )
        assertTrue(
            "F14d: the block is display-only, grid view only",
            grid.contains("GridTopBand(")
        )
    }

    // ── T7: the top block's content ────────────────────────────────────────

    @Test
    fun `the block shows a fixed two-line title, the meta and the description`() {
        assertTrue(
            "F14b: the title reserves two lines and never more",
            detail.contains("minLines = 2") && detail.contains("maxLines = 2")
        )
        assertTrue(
            "the meta line is the rules' own (channel, time, category, starts-in)",
            detail.contains("gridDetailMeta(channelName, cell, nowMs)")
        )
        assertTrue(
            "a live program gets its elapsed bar",
            detail.contains("liveFillFraction(cell, nowMs)")
        )
        assertTrue(
            "a focused gap cell shows its row's live program instead of itself",
            grid.contains("focusedCell?.takeUnless { it.isGap }")
        )
        assertFalse(
            "no rating badge was invented - the guide carries no rating",
            detail.contains("rating") || detail.contains("Strong")
        )
        assertTrue(
            "and the block's height is the band's, so text cannot resize it",
            band.contains(
                "height(if (channel != null) GRID_BAND_WITH_PREVIEW else GRID_BAND_TEXT_HEIGHT)"
            )
        )
    }

    // ── T8: the band's geometry ────────────────────────────────────────────

    @Test
    fun `the band is the preview's 16 by 9 box above the timeline, preview left`() {
        assertTrue(
            "F13a: 320x180",
            grid.contains("private val GRID_PREVIEW_WIDTH: Dp = 320.dp") &&
                grid.contains("private val GRID_PREVIEW_HEIGHT: Dp = 180.dp")
        )
        assertTrue(
            "F14b: 180dp while the preview is drawn",
            grid.contains("private val GRID_BAND_WITH_PREVIEW: Dp = 180.dp")
        )
        assertTrue(
            "the preview sits at the band's left and the block is beside it",
            band.indexOf("GridPreview(") >= 0 &&
                band.indexOf("GridPreview(") < band.indexOf("GridDetailBlock(")
        )
        val bandAt = grid.indexOf("GridTopBand(")
        val sidebarAt = grid.indexOf("AnimatedVisibility(")
        val rowsAt = grid.indexOf("LazyColumn(")
        assertTrue(
            "the band is drawn before the columns that hold the sidebar and the rows",
            bandAt >= 0 && bandAt < sidebarAt && sidebarAt < rowsAt
        )
        assertTrue(
            "and it covers no cell: the preview is in the band, not over a row",
            !band.contains("ProgramRow(") && band.contains("GridDetailBlock(")
        )
    }

    // ── T9: turning it off gives the height back ───────────────────────────

    @Test
    fun `turning the preview off gives the band's extra height back to the rows`() {
        assertTrue(
            "the text height is the block's own, inside the spec's 120-130dp",
            grid.contains("private val GRID_BAND_TEXT_HEIGHT: Dp = 128.dp")
        )
        assertTrue(
            "and the band picks it whenever there is no preview to draw",
            band.contains("height(if (channel != null) GRID_BAND_WITH_PREVIEW else GRID_BAND_TEXT_HEIGHT)")
        )
        assertTrue(
            "F13c: no last-played channel means no preview at all",
            band.contains("previewChannel?.takeIf { previewEnabled }")
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
