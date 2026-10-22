package com.kennyb1201.kbstream.ui.search

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That "Add to Home" on a browse chip no longer moves the page.
 *
 * Reported problem: long-pressing a chip deep in a submenu and choosing "Add to
 * Home" scrolled the whole screen up to the top and then back down to the chip,
 * after every chip.
 *
 * The chip menu is drawn over the page in its own focus group (see
 * PosterContextMenu, which is an overlay and not a Dialog), so when it closes
 * focus has nowhere to be and the framework fills the gap with the page's first
 * focusable - the category strip, at the very top of the browser. That is the
 * scroll up. The chip's return arm then re-focused the chip a frame later,
 * which is the scroll back down - and on the way it also ran the grid's own
 * scroll-to-the-armed-chip, which jumped the rail so that chip became its first
 * row.
 *
 * [BrowseRulesTest] pins the reveal rule. This pins the wiring that makes it
 * matter: focus is handed back to the chip by the menu itself, in the same
 * callback that closes it, and the rail is only scrolled when the chip is not
 * already showing.
 *
 * Read from the source: these are Compose modifiers and callbacks, which need a
 * device and a remote to press.
 */
class BrowseChipMenuFocusContractTest {

    /** The browser, comments removed, then whitespace collapsed. */
    private val browser: String by lazy { normalized(stripComments(source(BROWSER))) }

    /** The search screen, comments removed, then whitespace collapsed. */
    private val screen: String by lazy { normalized(stripComments(source(SCREEN))) }

    /** The shared chip, comments removed, then whitespace collapsed. */
    private val chip: String by lazy { normalized(stripComments(source(CHIP))) }

    /** The ViewModel, comments removed, then whitespace collapsed. */
    private val viewModel: String by lazy { normalized(stripComments(source(VIEW_MODEL))) }

    /** The submenu grid's composable, and nothing else in the browser. */
    private val grid: String by lazy {
        browser
            .substringAfter("private fun SubmenuChipGrid(")
            .substringBefore("private fun browseCategoryIcon(")
    }

    /** The chip menu's two handlers, and nothing else on the screen. */
    private val chipMenu: String by lazy {
        screen
            .substringAfter("hiddenChipMenu?.let { (categoryKey, entry) ->")
            .substringBefore("categoryChipMenu?.let { category ->")
    }

    @Test
    fun `the rail is scrolled only to a chip it is not already showing`() {
        assertTrue(
            "the grid asks what it has laid out right now, which is what makes " +
                "\"already on screen\" answerable",
            grid.contains(
                "val showing = gridState.layoutInfo.visibleItemsInfo.map { it.index }"
            )
        )
        assertTrue(
            "and the scroll is behind the reveal rule",
            grid.contains("if (browseChipNeedsReveal(index, showing)) {")
        )
        assertFalse(
            "the unconditional scroll-to-the-armed-chip is the jump: the chip " +
                "a viewer just long-pressed is on screen by definition, so " +
                "scrolling to it only re-aligns the rail around it",
            grid.contains("runCatching { gridState.scrollToItem(armedIndex) }")
        )
        assertTrue(
            "and a chip the rail has not composed is still revealed, or a " +
                "return to it would have no node to focus",
            grid.contains("runCatching { gridState.scrollToItem(index) }")
        )
    }

    @Test
    fun `the grid neutralizes the automatic reveal and performs its own`() {
        // Reported: D-pad UP into the grid's third row reset the scroll to the
        // top while focus stayed on the now-off-screen chip. Foundation's
        // FocusableNode asks for a bring-into-view on every focus change, and
        // that request walks outward from the chip through the grid and on to
        // the screen's own LazyColumn, so the reveal was computed by the nested
        // chain instead of by this grid.
        //
        // The chip cannot swallow that request (BringIntoViewRequester is a
        // sealed interface here, and a responder re-enters the walk upward), so
        // the grid answers it with a spec that never scrolls - the one lever
        // ContentInViewNode reads its distances from - and reveals the row
        // itself.
        val callSite = browser.substringBefore("private fun SubmenuChipGrid(")
        assertTrue(
            "the spec is provided around the grid's CALL SITE, which is what " +
                "scopes it to this grid: everything else on the screen - the " +
                "category tabs, the filter field - keeps the framework's reveal",
            callSite.contains("ChipRevealScope {") && callSite.contains("SubmenuChipGrid(")
        )
        assertEquals(
            "and exactly once, in the wrapper: a second provider would mean " +
                "the spec is applied somewhere it was not meant to be",
            1,
            Regex("LocalBringIntoViewSpec provides").findAll(browser).count()
        )
        assertTrue(
            "the spec answers every axis with zero scroll, which is what makes " +
                "the framework's automatic reveal a no-op inside the grid",
            // Whitespace-normalized, so the wrapped parameter list reads as one line.
            browser.contains(
                "override fun calculateScrollDistance( offset: Float, size: Float, " +
                    "containerSize: Float ): Float = 0f"
            )
        )
        assertTrue(
            "the reveal the grid performs instead is a nearest-edge " +
                "animateScrollToItem driven by the chip's own focus event",
            grid.contains("if (state.isFocused) {") &&
                grid.contains("revealScope.launch { revealFocusedChipRow(gridState, entryIndex) }") &&
                grid.contains("state.animateScrollToItem(target.index)") &&
                grid.contains("state.animateScrollToItem(target.index, target.scrollOffset)")
        )
        assertTrue(
            "the row's height is the one the grid measured, so a two-line " +
                "curated chip reveals as fully as a one-line one",
            grid.contains("itemSize = info.size.height") &&
                grid.contains("itemTop = info.offset.y")
        )
        assertTrue(
            "the decision itself is the pure rule, not arithmetic inline in " +
                "the composable",
            grid.contains("val target = chipRevealTarget(")
        )
    }

    @Test
    fun `the armed-chip reveal stays direct`() {
        // The return path (back-out of a studio screen, menu close) runs while
        // the browse section is on screen, so it keeps its direct scrollToItem:
        // it is not a bringIntoView request and must not be routed through the
        // nearest-edge reveal, which would leave a returning chip flush with
        // the rail's bottom edge instead of at the top where it opens.
        val armed = grid
            .substringAfter("LaunchedEffect(armedIndex) {")
            .substringBefore("LazyVerticalGrid(")
        assertTrue(
            "the armed chip is still scrolled to directly",
            armed.contains("runCatching { gridState.scrollToItem(index) }")
        )
        assertTrue(
            "and still only when the rail is not already showing it",
            armed.contains("if (browseChipNeedsReveal(index, showing)) {")
        )
        assertFalse(
            "the armed path must not go through the nearest-edge reveal",
            armed.contains("revealFocusedChipRow(")
        )
    }

    @Test
    fun `a chip's cell key is its identity, never its position`() {
        assertTrue(
            "the entry's own identity keys the cell, so a list that is " +
                "re-derived around the same entries cannot make Compose treat " +
                "them as new cells",
            grid.contains(
                "key = { _, entry -> BrowseChipVisibility.key(categoryKey, entry.name) }"
            )
        )
        assertFalse(
            "a positional key would re-key every cell after an insertion or a " +
                "filter, which is what LOSES a lazy rail's scroll position",
            grid.contains("key = { index ->") || grid.contains("key = { entryIndex ->")
        )
    }

    @Test
    fun `the menu hands focus back to the chip it was opened on, before it closes`() {
        assertTrue(
            "a chip's requester comes out of the registry the CALLER owns, " +
                "keyed by the chip's own identity, and is given to the chip and " +
                "to the long-press handler together. It cannot be a per-item " +
                "remember: the Hide action runs in the screen's scope, outside " +
                "this grid, and it is the action that has to hand focus to the " +
                "chip taking a hidden one's place before that chip leaves",
            grid.contains("val chipKey = BrowseChipVisibility.key(categoryKey, entry.name)") &&
                // The registry lookup is wrapped in a remember, because lint's
                // RememberInComposition rejects a FocusRequester built during
                // composition outside one - and a memoising map is not
                // something it can see through. The registry is still the
                // caller's, still keyed by the chip's own identity, and still
                // the one instance per chip the Hide action names.
                grid.contains("val chipFocusRequester = remember(chipKey) {") &&
                grid.contains("chipFocusRequesters.getOrPut(chipKey) { FocusRequester() }") &&
                grid.contains("{ handler(categoryKey, entry, chipFocusRequester) }") &&
                grid.contains("chipFocusRequester = chipFocusRequester,")
        )
        assertTrue(
            "the chip focuses through the requester its caller owns",
            chip.contains("chipFocusRequester: FocusRequester? = null") &&
                chip.contains(
                    "val returnFocusRequester = chipFocusRequester ?: ownFocusRequester"
                )
        )
        assertTrue(
            "and the two are created unconditionally: a `?:` around the " +
                "remember would make the call conditional, which Compose's " +
                "slot table cannot survive",
            chip.contains("val ownFocusRequester = remember { FocusRequester() }")
        )
        assertTrue(
            "the screen keeps that requester when the menu opens, which is " +
                "what lets the menu name the node it hands focus back to",
            screen.contains("onChipLongPress = { categoryKey, entry, chipRequester ->") &&
                screen.contains("lastChipFocusRequester = chipRequester")
        )
        assertEquals(
            "both the action and Back hand the focus over - two ways out of " +
                "the menu, one contract - and both go through the app's verified " +
                "hand-back rather than asking once and reading nothing",
            2,
            Regex("handFocusBack" + Regex.escape("(lastChipFocusRequester)"))
                .findAll(chipMenu).count()
        )
        val handBack = chipMenu.indexOf("handFocusBack(lastChipFocusRequester)")
        val closed = chipMenu.indexOf("hiddenChipMenu = null")
        assertTrue(
            "and the hand-back comes FIRST, while the menu is still in the " +
                "tree: a request made after it is removed is a request made " +
                "after the framework has already moved focus to the category " +
                "strip, which is the scroll up the report describes",
            handBack in 0 until closed
        )
    }

    @Test
    fun `the browser's own return is kept, as the fallback`() {
        assertTrue(
            "the request above can miss (a node that is not attached yet), so " +
                "the arm that re-asks for the chip by name stays",
            screen.contains("returnChipName = browseChipMenuReturn") &&
                browser.contains("armedChipName: String?") &&
                browser.contains("grabInitialFocus = armedIndex == entryIndex")
        )
    }

    @Test
    fun `adding a chip to Home hands the rail the same list it already had`() {
        val add = viewModel
            .substringAfter("fun addBrowseChipToHome(")
            .substringBefore("fun removeBrowseChipFromHome(")
        assertTrue(
            "the action writes the shortcuts and nothing else",
            add.contains("_browseHomeShortcuts.value = BrowseHomeShortcuts.add(") &&
                !add.contains("publishBrowseCategories(") &&
                !add.contains("_browseCategories.value")
        )
        assertFalse(
            "and the browser does not even read them, so nothing it renders - " +
                "the grid's list, the cell keys, the chip's own look - depends " +
                "on the action. There is no \"added\" badge on a chip to appear " +
                "and steal focus, which is the third thing the report suspected",
            browser.contains("browseHomeShortcuts")
        )
        assertTrue(
            "the grid's entries are the category's own, re-derived only by a " +
                "category switch or the filter",
            browser.contains("val visibleEntries = remember(activeCategory?.entries, filterQuery) {")
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
                if (candidate.isDirectory) {
                    return candidate
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private companion object {
        const val BROWSER = "com/kennyb1201/kbstream/ui/search/BrowseBrowser.kt"
        const val SCREEN = "com/kennyb1201/kbstream/ui/search/SearchScreen.kt"
        const val CHIP = "com/kennyb1201/kbstream/ui/search/SearchScreenPart2.kt"
        const val VIEW_MODEL = "com/kennyb1201/kbstream/ui/search/SearchViewModel.kt"
    }
}
