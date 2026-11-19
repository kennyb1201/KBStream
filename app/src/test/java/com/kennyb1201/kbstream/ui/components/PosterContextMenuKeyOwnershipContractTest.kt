package com.kennyb1201.kbstream.ui.components

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The long-press menu owns every directional press while it is open, and never
 * hands focus to the page behind the scrim.
 *
 * Two field reports, one component.
 *
 * The first (Kenny, 2026-11-18 10:37): "sometimes on long press on home the
 * posters on home will scroll in the background while the long press menu also
 * scrolls." The overlay swallowed Up/Down only at the menu's vertical EDGES,
 * judged from the index of the row that had last REPORTED focus. The panel
 * scrolls to follow focus, so that report lags exactly when the next press
 * arrives - and a stale index meant the edge press was not consumed. The event
 * fell through to the screen behind the scrim, whose rails scrolled while this
 * menu scrolled too. Fix: consume unconditionally and move focus here.
 *
 * The second (Kenny, 2026-11-18 23:17 and 2026-11-19 00:22): "long press menu on
 * detail page loses focus to the background" - Down from the last row, Up from
 * the first. The move was a focus SEARCH, and a focusGroup bounds where a search
 * can FIND something, not where it lands when there is nothing left inside the
 * group: at the edge the search took the background card below (or above) the
 * panel. Consuming the press afterwards cannot undo a move that already
 * happened. Fix (spec 2026-11-19, F1): address a row's own requester, with the
 * target clamped to a real row index.
 *
 * These pins are about the ownership and the clamp, because both are invisible
 * in review and a "tidy-up" back to either edge judgement or focus search would
 * silently restore a field bug.
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
    fun `both vertical directions are consumed and addressed, never searched`() {
        listOf("Up", "Down").forEach { direction ->
            assertTrue(
                "Direction$direction must be handled here, not left to Compose's " +
                    "default navigation (which falls through when it finds nothing)",
                handler.contains("Key.Direction$direction -> {")
            )
            assertTrue(
                "and the arm names a row of THIS menu instead of searching for " +
                    "one - the search is what found the card behind the scrim",
                handler.substringAfter("Key.Direction$direction -> {")
                    .substringBefore("true")
                    .contains("moveToRow(menuMoveTarget(focusedActionIndex,")
            )
        }
        assertTrue(
            "the target is bounded by the rows list, and the clamp lives in " +
                "menuMoveTarget - so no reported index can leave the menu",
            handler.contains(", rows.lastIndex)") &&
                menu.contains("coerceIn(0, lastIndex)")
        )
        assertTrue(
            "the press is consumed after the request, exactly as the discarded " +
                "move answer used to be",
            handler.contains(
                "moveToRow(menuMoveTarget(focusedActionIndex, -1, rows.lastIndex))"
            ) && handler.contains(
                "moveToRow(menuMoveTarget(focusedActionIndex, 1, rows.lastIndex))"
            )
        )
        assertFalse(
            "and the search is gone for EVERY direction, not just the edges",
            menu.contains("moveFocus(")
        )
    }

    @Test
    fun `the reported row picks a target, and the clamp keeps it in the menu`() {
        // The index that decided "is this an edge?" WAS the first field bug, and
        // the search that ignored it was the second. The index is allowed back
        // only because it can CHOOSE which row to ask - and that choice is
        // clamped, so a stale report costs a duplicate press, never an escape.
        assertFalse(
            "no unclamped indexing of the requesters by the reported row",
            menu.contains("rowFocusRequesters[focusedActionIndex]")
        )
        assertFalse(
            "and no edge decision is made from it: the clamp decides, not the index",
            handler.contains("focusedActionIndex >= ") ||
                handler.contains("focusedActionIndex >") ||
                handler.contains("focusedActionIndex ==")
        )
        assertTrue(
            "every row carries a requester, indexed by position, so a clamped " +
                "target always has a node to land on",
            menu.contains("List(rows.size) { FocusRequester() }") &&
                menu.contains("focusRequester = rowFocusRequesters[index]") &&
                menu.contains("rowFocusRequesters.getOrNull(index)")
        )
        assertTrue(
            "and the Phase 2 take asks row 0's own requester, not a second one " +
                "attached to the same row",
            menu.contains("rowFocusRequesters.first().requestFocus()")
        )
    }

    @Test
    fun `the move stays inside the menu, because the group is on the same chain`() {
        val chain = menu.substring(0, menu.indexOf(".onPreviewKeyEvent {"))
        val boxAt = chain.lastIndexOf("BoxWithConstraints(")
        val groupAt = chain.lastIndexOf(".focusGroup()")
        assertTrue(
            "the focusGroup is applied to the overlay the handler sits on, before " +
                "the handler itself, so whatever Compose navigates cannot leave it",
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
