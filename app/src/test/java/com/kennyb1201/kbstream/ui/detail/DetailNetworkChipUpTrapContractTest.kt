package com.kennyb1201.kbstream.ui.detail

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reported trap: on an item with networks but NO cast, UP on a network chip
 * did nothing at all.
 *
 * That handler tried the people column first, and when no chip there took the
 * focus it fell back to a version of the move written out for that one row -
 * `hasPeopleRow` deciding between a reveal and a jump to the top of the page -
 * so the two shapes could disagree with the rails around them. A press out of a
 * rail whose neighbour existed but had been scrolled away read as a press out
 * of a rail that was not there.
 *
 * What this pins is that the shape cannot come back: the network chip asks the
 * page's one chain, which resolves the press to a row that EXISTS (stepping
 * over the ones this page does not have) and hands it over through the same
 * reveal / header-fallback every other rail uses. There is no row-specific
 * branch left to disagree with.
 *
 * Read from the source: these are key handlers and Compose modifiers, which
 * need a device and a remote to press.
 */
class DetailNetworkChipUpTrapContractTest {

    /** The detail source, comments removed, then whitespace collapsed. */
    private val source: String by lazy {
        normalized(stripComments(File(findMainSourceRoot(), DETAIL).readText()))
    }

    /** The network chips' key handler, and nothing else on the page. */
    private val networkRow: String by lazy {
        source
            .substringAfter("item(key = \"networkrow\"")
            .substringBefore("item(key = \"productionheader\"")
    }

    /** The page's last-resort arm. */
    private val topOfPage: String by lazy {
        source
            .substringAfter("suspend fun focusTopOfPage(): Boolean {")
            .substringBefore("val effectiveSeason")
    }

    /** The move the shared handler hands a resolvable press to. */
    private val move: String by lazy {
        source
            .substringAfter("suspend fun moveFocusVertical(")
            .substringBefore("fun detailRowVerticalFocus(")
    }

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

    @Test
    fun `the network chip asks the one chain, with its own column`() {
        assertTrue(
            "UP and DOWN out of a network chip are decided in the same place " +
                "as every other rail's, so this row can no longer disagree " +
                "with the rows around it",
            networkRow.contains(
                "detailRowVerticalFocus( DetailRow.NETWORK, networkIndex, detailRowPresence )"
            )
        )
    }

    @Test
    fun `the press is resolved against the rows the page has, not against one guard`() {
        assertTrue(
            "the row a press lands on is the nearest one that exists, walked " +
                "from the one list",
            move.contains(
                "val target = DetailFocusChain.nextRow(from, direction, presence) ?: return false"
            )
        )
        assertFalse(
            "the no-cast case is no longer a branch of this row's own: " +
                "whether a rail exists is the chain's business now",
            networkRow.contains("hasPeopleRow")
        )
    }

    @Test
    fun `the press is consumed while the move runs`() {
        assertTrue(
            "a key handed to the (asynchronous) move must never also reach the " +
                "default search, or the network rail's restorer would move the " +
                "focus a second time",
            source.contains(
                "startFocusMove { moveFocusVertical(row, direction, column, presence) } true"
            )
        )
        assertTrue(
            "and the bottom of the chain - reached only by DOWN, never by UP, " +
                "because the header always exists - is consumed too: the press " +
                "stays where the viewer is, on the last row the page paints",
            source.contains("DetailFocusChain.nextRow(row, direction, presence) == null") &&
                source.contains(
                    "DetailFocusChain.nextRow(row, direction, presence) == null) { true } else {"
                )
        )
        assertFalse(
            "and it is NOT thrown up to the header PLAY, which is what that " +
                "arm used to do from the bottom of the page",
            source.contains("scope.launch { focusTopOfPage() } true")
        )
    }

    @Test
    fun `a rail that exists but was disposed is revealed before anything else`() {
        assertTrue(
            "the rail above exists, so no chip taking the focus means the " +
                "scroll disposed it - which is not the same as the row not " +
                "being there",
            source.contains(
                "revealAndFocusChipColumn(targetKey, anchorKey, fromColumn)"
            )
        )
        val direct = source.indexOf("focusChipColumn(targetKey, fromColumn)")
        val reveal = source.indexOf(
            "revealAndFocusChipColumn(targetKey, anchorKey, fromColumn)"
        )
        val fallback = source.indexOf(
            "val nextTarget = DetailFocusChain.nextRow(target, direction, presence)"
        )
        assertTrue(
            "existence decides the order: ask the rail, put it back on screen if " +
                "it is disposed, and only then walk on to the next row",
            direct in 0 until reveal && reveal in 0 until fallback
        )
    }

    @Test
    fun `the last resort scrolls before it asks, and can always take the focus`() {
        assertTrue(
            "the list is back at its top before anything is asked for the focus",
            topOfPage.contains("runCatching { detailListState.scrollToItem(0) }")
        )
        val scroll = topOfPage.indexOf("scrollToItem(0)")
        val rail = topOfPage.indexOf("movieDetailsFocusRequester.requestFocus()")
        val button = topOfPage.indexOf("playButtonFocusRequester.requestFocus()")
        assertTrue("the focus request must follow the scroll", scroll in 0 until button)
        assertTrue(
            "and the PLAY button - in the fixed header ABOVE the list, so " +
                "always composed - is asked FIRST, because it is the row UP " +
                "points at. Asked the other way round the landing was the " +
                "people rail, an item INSIDE the list below the header: UP on a " +
                "genre chip scrolled to the top and then dropped the focus into " +
                "the actors rail, whose own UP hands the viewer back to the " +
                "chips - the reported loop, with the header and the top bar " +
                "unreachable from a chip",
            button in 0 until rail
        )
        assertTrue(
            "the request is retried while the page recomposes, not tried once",
            topOfPage.contains("while (attempts < RETURN_FOCUS_STEPS)")
        )
        assertTrue(
            "and the arm reports whether it landed, which is what lets the " +
                "chain's last step be an honest answer rather than a swallow",
            topOfPage.contains("return true") && topOfPage.contains("return false")
        )
    }

    private companion object {
        const val DETAIL = "com/kennyb1201/kbstream/ui/detail/DetailScreen.kt"
    }
}
