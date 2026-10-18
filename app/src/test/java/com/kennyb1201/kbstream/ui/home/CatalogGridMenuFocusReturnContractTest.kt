package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "Open in Grid" poster grid hands the D-pad back when its long-press menu
 * closes.
 *
 * Field report: after adding a title to My List from a poster's long-press
 * menu, focus was lost and the next press landed on what looked like a random
 * item. It was this grid. Every other title menu in the app - Home's rails, the
 * detail page's rails, Search, Actor, Studio, Tag, Decade, Collection, the KB
 * folders and the Library grid - names the pressed card and asks for it again
 * when the overlay closes; this one set its menu target to null and stopped.
 * The focused menu row was then disposed with the overlay, so nothing held
 * focus: a press performs a focus search from a dead node, and whatever the
 * framework finds first (the top-left tile) takes it.
 *
 * The whole contract is in the wiring, which is what these assert against the
 * source: one requester per tile, the pressed tile recalled by key, a landing
 * through the app's verified hand-back rule, and - because Hide takes the tile
 * itself away - the successor named and focused before the pressed tile is
 * gone.
 */
class CatalogGridMenuFocusReturnContractTest {

    private val sourceRoot: File by lazy { findSourceRoot() }

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

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun squash(text: String): String = text.replace(Regex("\\s+"), " ")

    private fun grid(): String = source(GRID)

    /** The squashed source between the first [from] anchor and the next [to]. */
    private fun window(text: String, from: String, to: String): String {
        val start = text.indexOf(from)
        assertTrue("anchor missing: $from", start >= 0)
        val end = text.indexOf(to, start + from.length)
        assertTrue("anchor missing after '${from}': $to", end > start)
        return text.substring(start, end)
    }

    @Test
    fun `every tile publishes a focus requester under the key the grid keys it with`() {
        val src = grid()
        assertTrue(
            "one requester per tile, keyed the way the cells are keyed, so a " +
                "reorder cannot move the landing to a different poster",
            src.contains("val cardFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }")
        )
        assertTrue(
            "the cell key must be the pager's own \"type:id\" shape",
            src.contains("val cardKey = \"\${meta.type}:\${meta.id}\"")
        )
        assertTrue(
            "the requester is remembered and published as the tile composes",
            src.contains("val cardRequester = remember(cardKey) { FocusRequester() }") &&
                src.contains("cardFocusRequesters[cardKey] = cardRequester")
        )
        assertTrue(
            "and it is attached to the card itself, which is the focusable node",
            src.contains(".focusRequester(cardRequester)")
        )
    }

    @Test
    fun `the long press remembers the tile it was opened on`() {
        val src = squash(grid())
        val longPress = window(
            src,
            "onLongClick = {",
            "},"
        )
        assertTrue(
            "the pressed tile is recalled by key, not by index: the grid can " +
                "reorder under the overlay",
            longPress.contains("menuCardKey = cardKey")
        )
        assertTrue(
            "and the tile that takes its place if Hide is pressed is named " +
                "while the pressed tile is still composed",
            longPress.contains("menuSuccessorKey = successorIndexAfter(")
        )
    }

    @Test
    fun `closing the menu lands focus on the pressed tile through the verified rule`() {
        val src = squash(grid())
        assertTrue(
            "dismiss clears the menu and re-arms the landing",
            src.contains("fun closeMenu(restoreKey: String?) { menuTarget = null menuCardKey = restoreKey restoreFocusSignal++ }")
        )
        assertTrue(
            "the landing reads the request's answer and retries across frames - " +
                "the app's one hand-back rule, not a bare requestFocus()",
            src.contains("landFocusOn(cardFocusRequesters[key])")
        )
        assertTrue(
            "the scrim and Back hand back to the pressed tile",
            src.contains("onDismiss = { closeMenu(menuCardKey) }")
        )
        assertFalse(
            "a dismiss that only clears the target is the reported defect: it " +
                "leaves focus on a row the same frame disposes",
            src.contains("onDismiss = { menuTarget = null }")
        )
    }

    @Test
    fun `a row that stays on the grid dismisses through the hand-back`() {
        val src = squash(grid())
        val watchedAction = window(
            src,
            "label = if (isWatched) {",
            "onDismiss"
        )
        assertTrue(
            "the watched toggle changes the badge in place, so it must put the " +
                "D-pad back on the same tile",
            watchedAction.contains("closeMenu(targetKey)")
        )
    }

    @Test
    fun `Hide names the successor before the pressed tile leaves`() {
        val src = squash(grid())
        val beforeHide = window(src, "onBeforeHide = {", "},")
        assertTrue(
            "the successor is focused FIRST, while the pressed tile and the " +
                "menu are still in the tree to hand it over",
            beforeHide.contains("cardFocusRequesters[key]?.requestFocus()")
        )
        assertTrue(
            "and the dying tile must not be asked for focus by the dismiss " +
                "that follows",
            beforeHide.contains("menuCardKey = null")
        )
    }

    private companion object {
        const val GRID = "com/kennyb1201/kbstream/ui/home/CatalogGridScreen.kt"
    }
}
