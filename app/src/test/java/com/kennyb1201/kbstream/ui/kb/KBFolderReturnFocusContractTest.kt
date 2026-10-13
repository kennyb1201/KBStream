package com.kennyb1201.kbstream.ui.kb

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An imported KB folder survives a trip into Detail and back.
 *
 * Reported: open a folder, press a poster, Back — the folder reopened at the
 * top with the D-pad wherever the framework dropped it. The folder is built
 * from scratch on every return (MainActivity swaps to the Detail screen, which
 * disposes this whole composition), so nothing about the viewer's place
 * survived it; the same record the browse pages use (see [BrowseReturnFocus])
 * is what puts it back.
 *
 * The page is a hero plus rails of its own shape, and it draws in three layouts
 * (hero rails / plain rows / poster grid), so what is asserted here is that ALL
 * of them read the same record and that the screen — the only thing that
 * outlives the trip — is the one that owns it. Read out of the source, the way
 * the browse pages' own return contracts are: there is no Compose harness in
 * this module for a screen that wants a ViewModel.
 */
class KBFolderReturnFocusContractTest {

    private val screen by lazy { source(SCREEN) }
    private val layouts by lazy { source(LAYOUTS) }

    // ------------------------------------------------- who owns the record --

    @Test
    fun `the screen owns the return record, not the layout it draws`() {
        assertTrue(
            "the page keys its own record, one key per folder",
            screen.contains(
                "val browseReturnKey = remember(folderId) { kbFolderBrowseScreenKey(folderId) }"
            )
        )
        assertTrue(
            "and owns the two scroll states and the requester the landing " +
                "asks: a Back out of Detail disposes everything below it",
            screen.contains("val browseListState = rememberLazyListState()") &&
                screen.contains("val browseGridState = rememberLazyGridState()") &&
                screen.contains("val returnFocusRequester = remember { FocusRequester() }")
        )
        assertTrue(
            "a press records the card before it navigates",
            screen.contains("BrowseReturnFocus.record(target)")
        )
        assertTrue(
            "one record per visit, not one per composition",
            screen.contains("var returnTarget by remember(folderId) {") &&
                screen.contains("var returnHandled by remember(folderId)")
        )
        assertFalse(
            "no layout re-creates the page's own column state: the hero rails' " +
                "LazyColumn is handed it (see the argument above)",
            layouts.substringAfter("private fun FollowHomeLayout(")
                .substringBefore("private fun RowsLayout(")
                .contains("val listState = rememberLazyListState()")
        )
        assertTrue(
            "and the grid's own scroll state is the screen's too",
            layouts.contains("state = gridState")
        )
    }

    @Test
    fun `the record is held until it is honoured`() {
        assertTrue(
            "read once, by the page it was raised for",
            screen.contains("val recorded = returnTarget ?: BrowseReturnFocus.consume(browseReturnKey)")
        )
        assertTrue(
            "and the page's own rail list is scrolled to the rail it names, " +
                "because a rail outside the viewport has composed nothing",
            screen.contains(
                "browseListState.revealBrowseReturnRail(railKey, recorded.railIndex)"
            )
        )
        assertTrue(
            "the landing is retried and reported, never assumed",
            screen.contains("returnHandled = landFocusOn(returnFocusRequester)")
        )
        assertTrue(
            "bounded, so a card that never comes back cannot spin",
            screen.contains("if (returnAttempts >= BROWSE_RETURN_FOCUS_STEPS) return@LaunchedEffect")
        )
        assertTrue(
            "a new load is a new list: a position in the old one means nothing",
            screen.contains("returnHandled = false") && screen.contains("returnAttempts = 0")
        )
    }

    // --------------------------------------------- what the rails and grid do --

    @Test
    fun `every rail scrolls itself onto the card the record names`() {
        assertEquals(
            "the dispatcher, the two rail layouts, the grid AND the bundle the " +
                "rails read it out of all carry the record - no layout is left " +
                "drawing a page that cannot put itself back",
            5,
            layouts.split("returnTarget: BrowseReturnTarget?,").size - 1
        )
        assertTrue(
            "the rail picks the card by its OWN key first, so a rail that has " +
                "re-ordered under the viewer still comes back on it",
            layouts.contains("indexOfFirst { kbFolderCardKey(it) == target.itemId }")
        )
        assertTrue(
            "and falls back to the recorded position for a card it cannot name",
            layouts.contains("target.position.takeIf { it in rail.items.indices }")
        )
        assertTrue(
            "the row is scrolled before the focus is asked for, which the " +
                "screen retries while that settles",
            layouts.contains("LaunchedEffect(returnIndex) {") &&
                layouts.contains("runCatching { listState.scrollToItem(index) }")
        )
        assertTrue(
            "the grid does the same on its own state",
            layouts.contains("LaunchedEffect(returnTarget, gridItems) {") &&
                layouts.contains("runCatching { gridState.scrollToItem(index) }")
        )
    }

    @Test
    fun `the card a return is coming back for is the one attached to the requester`() {
        assertTrue(
            "one card, by key and position",
            layouts.contains("isReturnItem = itemIndex == returnIndex") ||
                layouts.contains(
                    "val isReturnItem = returnTarget != null &&\n" +
                        "                            itemIndex == returnTarget.position &&\n" +
                        "                            cardKey == returnTarget.itemId"
                )
        )
        assertTrue(
            "and it carries the page's requester alongside its own",
            layouts.contains(".focusRequester(cardFocusRequester)") &&
                layouts.contains("Modifier.focusRequester(returnFocusRequester)")
        )
    }

    // ------------------------------------------- the long-press hand-back --

    @Test
    fun `a long press hands the D-pad back to a card that is still there`() {
        assertTrue(
            "one requester per card, keyed the way the page records it",
            layouts.contains("internal fun kbFolderCardKey(item: KBContentItem)") &&
                layouts.contains("val cardFocusRequester = remember(cardKey) { FocusRequester() }")
        )
        assertTrue(
            "the key of the card that takes the pressed one's place is worked " +
                "out BEFORE the menu opens, while the pressed card can still name it",
            layouts.contains("successorIndexAfter(")
        )
        assertTrue(
            "a dismiss lands on the pressed card",
            screen.contains("closeMenu(menuCardKey)")
        )
        assertTrue(
            "and a Hide - which takes that card off the rail on the next frame " +
                "- lands on the successor instead, told apart by the menu's own " +
                "before-hide hook",
            screen.contains("onBeforeHide = { menuHidePending = true }") &&
                screen.contains("closeMenu(if (wasHidden) menuSuccessorKey else menuCardKey)")
        )
        assertTrue(
            "the landing itself is the shared retried one, not a bare request",
            screen.contains("landFocusOn(key?.let { cardFocusRequesters[it] })")
        )
    }

    @Test
    fun `the folder filters hidden titles, so a Hide is not a visual no-op`() {
        assertTrue(
            "read at the source of every layout, not in one of them",
            layouts.contains("private fun visibleFolderRails(") &&
                layouts.contains("HiddenTitles.hides(")
        )
        assertEquals(
            "the hero rails, the rows and the grid all read through it",
            3,
            layouts.split("visibleFolderRails(rails, hiddenTitleKeys, resolvedIds)").size - 1
        )
        assertTrue(
            "the hidden set is observed as state, so the rail repaints the " +
                "moment a title is hidden",
            layouts.contains("val hiddenTitleKeys = rememberHiddenTitleKeys()")
        )
    }

    // ------------------------------------------------------------- plumbing --

    private fun source(path: String): String {
        val file = File(findMainSourceRoot(), path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

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
        const val SCREEN = "com/kennyb1201/kbstream/ui/kb/KBFolderScreen.kt"
        const val LAYOUTS = "com/kennyb1201/kbstream/ui/kb/KBFolderLayouts.kt"
    }
}
