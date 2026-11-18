package com.kennyb1201.kbstream.ui.components

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The long-press menu owns every directional press while it is open.
 *
 * The field report (Kenny, 2026-11-18 10:37): "sometimes on long press on home
 * the posters on home will scroll in the background while the long press menu
 * also scrolls."
 *
 * The mechanism, verified in this file's own target at the tip: the overlay
 * swallowed Up/Down only at the menu's vertical EDGES, and decided "edge" from
 * the index of the row that had last REPORTED focus. The panel scrolls to follow
 * focus and its rows compose lazily, so navigating to an off-screen row is
 * exactly when that report lags behind the real focus - and a stale index meant
 * the next edge press was not consumed. The event fell through to the screen
 * behind the scrim (the focusGroup bounds focus *search*, not a key nothing
 * consumed), whose rails scrolled while this menu scrolled to follow focus.
 *
 * So the handler no longer judges anything: it moves focus itself, bounded by
 * the group, and consumes the press either way. These pins are about that
 * ownership, because it is invisible in review and a "tidy-up" back to edge
 * clamping would silently restore the bug (spec 2026-11-18, F1).
 */
class PosterContextMenuKeyOwnershipContractTest {

    private val menu = readSource("ui/components/PosterContextMenu.kt")

    /** The overlay's `onPreviewKeyEvent` handler, braces balanced. */
    private val handler: String by lazy {
        val at = menu.indexOf(".onPreviewKeyEvent {")
        require(at >= 0) { "the overlay has no key handler" }
        val bodyStart = menu.indexOf("{", at)
        var depth = 0
        for (i in bodyStart until menu.length) {
            when (menu[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return@lazy menu.substring(at, i + 1)
                }
            }
        }
        error("unbalanced key handler")
    }

    // ── F1: the press is ours ────────────────────────────────────────────

    @Test
    fun `both vertical directions are consumed and moved by the handler`() {
        listOf("Up", "Down").forEach { direction ->
            assertTrue(
                "Direction$direction must be handled here, not left to Compose's " +
                    "default navigation (which falls through when it finds nothing)",
                handler.contains("Key.Direction$direction -> {")
            )
            assertTrue(
                "and the handler moves focus itself, bounded by the focusGroup",
                handler.contains("focusManager.moveFocus(FocusDirection.$direction)")
            )
        }
        assertTrue(
            "the move's answer is discarded: a press at the last row still consumes, " +
                "which is the clamp",
            handler.contains("moveFocus(FocusDirection.Up)\n                            true") &&
                handler.contains("moveFocus(FocusDirection.Down)\n                            true")
        )
        assertTrue(
            "the focus manager used is the overlay's own, not a passed-in one",
            menu.contains("val focusManager = LocalFocusManager.current")
        )
    }

    @Test
    fun `nothing about the press depends on which row reported focus last`() {
        // The stale index WAS the bug. If a future change reintroduces an
        // index-based decision here, this fails before the field does.
        assertFalse(
            "the overlay must not carry a focused-row index at all",
            menu.contains("focusedActionIndex")
        )
        assertFalse(
            "nor judge a vertical edge from the rows list inside the handler",
            handler.contains("rows.lastIndex")
        )
    }

    @Test
    fun `the move stays inside the menu, because the group is on the same chain`() {
        val chain = menu.substring(0, menu.indexOf(".onPreviewKeyEvent {"))
        val boxAt = chain.lastIndexOf("BoxWithConstraints(")
        val groupAt = chain.lastIndexOf(".focusGroup()")
        assertTrue(
            "the focusGroup is applied to the overlay the handler sits on, before " +
                "the handler itself, so moveFocus cannot leave it",
            boxAt >= 0 && groupAt > boxAt
        )
        assertTrue(
            "and nothing between them starts another composable, which would put " +
                "the handler outside the group",
            !chain.substring(groupAt).contains("Surface(") &&
                !chain.substring(groupAt).contains("Box(")
        )
    }

    // ── the arms that were deliberately left alone ───────────────────────

    @Test
    fun `Left and Right and Back keep their old behaviour`() {
        assertTrue(
            "horizontal presses are still swallowed outright",
            handler.contains("Key.DirectionLeft,\n                        Key.DirectionRight -> true")
        )
        assertTrue(
            "Back still dismisses and consumes, for the platforms that deliver it " +
                "to the focused hierarchy",
            handler.contains("Key.Back -> {") && handler.contains("dismiss()")
        )
        assertTrue(
            "and the fade-out swallow is still the first branch",
            handler.indexOf("} else if (dismissing) {") in 1 until handler.indexOf("when (event.key)")
        )
    }

    @Test
    fun `F2 - the picker and the rating dialog are still a second stage`() {
        assertTrue(
            "while either is open the menu and scrim step aside (alpha 0), so no " +
                "press can reach the rows behind the dialog window",
            menu.contains(
                ".alpha(if (libraryPickTarget == null && ratingTarget == null) 1f else 0f)"
            )
        )
    }

    @Test
    fun `F3 - the phone's sheet menu is untouched by this`() {
        val touch = readSource("ui/components/touch/TouchContextMenu.kt")
        assertTrue(
            "the phone menu is a ModalBottomSheet - a window of its own, with no " +
                "shared focus path to leak through",
            touch.contains("ModalBottomSheet")
        )
        assertFalse(
            "so this fix must not have been copied into it",
            touch.contains("moveFocus(FocusDirection.")
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun readSource(path: String): String {
        val file = File(findSourceRoot(), "com/kennyb1201/kbstream/$path")
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
        error("no src/main/java found above ${System.getProperty("user.dir")}")
    }
}
