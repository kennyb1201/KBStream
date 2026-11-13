package com.kennyb1201.kbstream.ui.streams

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The way back UP out of the links list (spec 2026-11-12, build 0.9.253).
 *
 * Field report: on "All", DOWN into the links, UP - and focus landed on the
 * LAST source chip on the right. That is a double fault. The tab chips are
 * focus-to-select ([StreamsTabFocusContractTest]), so the misplaced ring did not
 * just look wrong: landing on an add-on's chip SELECTED that add-on and swapped
 * the list, which means "go back to All" re-filtered the picker instead.
 *
 * The cause was that there was nothing to return to: the screen had requesters
 * for the first source and for REFRESH, none for a tab, so UP was left to
 * Compose's spatial search - and the rightmost chip is what that search finds.
 *
 * Pinned here: the active tab carries a requester, it follows the selection, the
 * first row answers UP by requesting it (and only the first row does), the chip
 * scrolls itself into view when focus arrives, and the focus-to-select that makes
 * the wrong landing harmful is still there - so returning to the active tab must
 * stay a no-op for the filter.
 */
class StreamsUpReturnsToActiveTabContractTest {

    @Test
    fun `the screen names the ACTIVE tab, and that name follows the selection`() {
        val screen = read()
        assertTrue(
            "one requester for the active tab",
            screen.contains("val activeTabFocusRequester = remember { FocusRequester() }")
        )
        assertTrue(
            "handed to the chip row",
            screen.contains("activeTabFocusRequester = activeTabFocusRequester")
        )
        val tabs = declaration("private fun AddonTabs(")
        assertTrue(
            "the row takes it",
            tabs.contains("activeTabFocusRequester: FocusRequester? = null")
        )
        assertTrue(
            "\"All\" carries it only while All is the active tab",
            tabs.contains("?.takeIf { selectedIndex == ALL_ADDONS_TAB }")
        )
        assertTrue(
            "and each add-on chip only while ITS index is the active tab",
            tabs.contains("activeTabFocusRequester?.takeIf { selectedIndex == index }")
        )
        assertTrue(
            "exactly one chip is handed it - the selection decides which",
            tabs.split("takeIf { selectedIndex").size == 3
        )
    }

    @Test
    fun `UP from the first source requests the active tab, not a spatial guess`() {
        val screen = read()
        assertTrue(
            "the answer is a named requester, not a neighbour chip",
            screen.contains("runCatching { activeTabFocusRequester.requestFocus() }") &&
                screen.contains(".getOrDefault(false) ||")
        )
        assertTrue(
            "a picker with no tabs (one add-on) still has somewhere to go up to",
            screen.contains("runCatching { refreshFocusRequester.requestFocus() }")
        )
        val onFirstRow = screen.substringAfter("if (index == 0) {")
            .substringBefore("} else {\n                                            Modifier")
        assertTrue(
            "the first row intercepts UP on the preview pass",
            onFirstRow.contains("Modifier.onPreviewKeyEvent { event ->") &&
                onFirstRow.contains("event.type == KeyEventType.KeyDown") &&
                onFirstRow.contains("event.key == Key.DirectionUp") &&
                onFirstRow.contains("backToActiveTab()")
        )
        assertTrue(
            "and the interception lives INSIDE the index == 0 branch, so a middle " +
                "row keeps ordinary list navigation",
            screen.contains(
                "if (index == 0) {\n" +
                    "                                            Modifier.onPreviewKeyEvent { event ->"
            )
        )
        assertFalse(
            "the column must not swallow UP for every row",
            screen.contains("LazyColumn(\n                state = streamListState,\n" +
                "                verticalArrangement = Arrangement.spacedBy(8.dp),")
                && screen.substringAfter("LazyColumn(", "")
                    .substringBefore("itemsIndexed(")
                    .contains("Key.DirectionUp")
        )
    }

    @Test
    fun `the chip scrolls itself into view, so the ring is never off-screen`() {
        val chip = body("private fun StreamTabChip(")
        assertTrue(
            "each chip carries its own bring-into-view requester",
            chip.contains("val bringIntoView = remember { BringIntoViewRequester() }") &&
                chip.contains(".bringIntoViewRequester(bringIntoView)")
        )
        assertTrue(
            "called when focus arrives, which is the same moment the row is at " +
                "risk of showing the ring off its edge",
            chip.substringAfter("if (state.isFocused) {")
                .substringBefore("}")
                .contains("bringIntoView.bringIntoView()")
        )
    }

    @Test
    fun `returning to the active tab cannot change the filter`() {
        val chips = body("private fun StreamTabChip(")
        // The chip's focus-to-select is the reason the old landing was a double
        // fault; it stays, and it is safe here because the way back UP names the
        // tab that is already selected.
        assertTrue(
            "the chip still adopts its tab on focus",
            chips.contains("onSelect()")
        )
        val tabs = declaration("private fun AddonTabs(")
        assertTrue(
            "the returned-to tab is the SELECTED one, so adoption re-selects the " +
                "same tab and the list stays as it is",
            tabs.contains("?.takeIf { selectedIndex == ALL_ADDONS_TAB }") &&
                tabs.contains("activeTabFocusRequester?.takeIf { selectedIndex == index }")
        )
    }

    private fun read(): String {
        val file = File(findSourceRoot(), SCREEN)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    /** The whole top-level function starting at [signature], parameters included. */
    private fun declaration(signature: String): String {
        val src = read()
        val start = src.indexOf(signature)
        assertTrue("source missing function: $signature", start >= 0)
        val rest = src.substring(start)
        val end = rest.indexOf("\n}")
        return if (end < 0) rest else rest.substring(0, end)
    }

    /** The body of the top-level function starting at [signature]. */
    private fun body(signature: String): String {
        val src = read()
        val start = src.indexOf(signature)
        assertTrue("source missing function: $signature", start >= 0)
        val brace = src.indexOf('{', start)
        assertTrue("no body for function: $signature", brace >= 0)
        val rest = src.substring(brace + 1)
        val end = rest.indexOf("\n}")
        return if (end < 0) rest else rest.substring(0, end)
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
        const val SCREEN = "com/kennyb1201/kbstream/ui/streams/StreamsScreen.kt"
    }
}
