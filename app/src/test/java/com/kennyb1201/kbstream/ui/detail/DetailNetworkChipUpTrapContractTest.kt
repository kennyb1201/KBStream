package com.kennyb1201.kbstream.ui.detail

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reported trap: on an item with networks but NO cast, UP on a network chip
 * did nothing at all.
 *
 * The UP handler tries the people column first, and when no chip there took the
 * focus it fell back to a scroll to the top and a bare
 * `movieDetailsFocusRequester.requestFocus()`. On an item with no cast, writer
 * or director there is no people rail for that requester to belong to, so the
 * request could only throw, the focus stayed on the chip and the key was
 * swallowed: trapped. The same handler also treated a people rail the scroll had
 * disposed as a rail that was not there.
 *
 * What this pins: the two cases are told apart by whether the rail EXISTS.
 * A rail that exists but was disposed is put back on screen and handed the
 * column ([revealAndFocusChipColumn]); a rail that does not exist sends the
 * press to the top of the page, where the play button in the fixed header above
 * the list is always composed ([focusTopOfPage]). Every arm consumes the press,
 * so the default search - and the network rail's restorer - can never move the
 * focus a second time.
 *
 * Read from the source: these are key handlers and Compose modifiers, which
 * need a device and a remote to press.
 */
class DetailNetworkChipUpTrapContractTest {

    /** The detail source, comments removed, then whitespace collapsed. */
    private val source: String by lazy {
        normalized(stripComments(File(findMainSourceRoot(), DETAIL).readText()))
    }

    /** The network chips' key handlers, and nothing else on the page. */
    private val networkRow: String by lazy {
        source
            .substringAfter("item(key = \"networkrow\"")
            .substringBefore("item(key = \"productionheader\"")
    }

    /** The network chip's UP arm alone. */
    private val upArm: String by lazy {
        networkRow
            .substringAfter("Key.DirectionUp ->")
            .substringBefore("Key.DirectionDown ->")
    }

    /** The page's last-resort arm. */
    private val topOfPage: String by lazy {
        source
            .substringAfter("suspend fun focusTopOfPage() {")
            .substringBefore("val effectiveSeason")
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
    fun `the people column is still tried first`() {
        assertTrue(
            "an item WITH cast must keep the unchanged behaviour: UP lands on " +
                "the people card in this column, and only a rail that did not " +
                "answer is looked at further",
            upArm.contains(
                "if ( focusChipColumn( PEOPLE_ROW_KEY, networkIndex ) ) { true } " +
                    "else if (hasPeopleRow) {"
            )
        )
    }

    @Test
    fun `a disposed people rail is brought back on screen instead of skipped`() {
        assertTrue(
            "the rail above exists, so no chip taking the focus means the " +
                "scroll disposed it - which is not the same as the row not " +
                "being there",
            upArm.contains(
                "revealAndFocusChipColumn( PEOPLE_ROW_KEY, NETWORK_ROW_KEY, networkIndex )"
            )
        )
        assertTrue(
            "and the press is consumed while that scroll runs",
            upArm.contains(
                "revealAndFocusChipColumn( PEOPLE_ROW_KEY, NETWORK_ROW_KEY, networkIndex ) " +
                    "} true }"
            )
        )
    }

    @Test
    fun `with no people rail the press goes to the top of the page`() {
        assertTrue(
            "no people rail at all is the one case that falls through, and it " +
                "falls to the page's own controls rather than nowhere",
            upArm.contains("} else { scope.launch { focusTopOfPage() } true }")
        )
        val disposed = upArm.indexOf("revealAndFocusChipColumn( PEOPLE_ROW_KEY,")
        val absent = upArm.indexOf("focusTopOfPage()")
        assertTrue(
            "existence decides the order: the reveal arm is reached before the " +
                "no-rail arm",
            disposed in 0 until absent
        )
    }

    @Test
    fun `the press is consumed by every arm`() {
        assertEquals(
            "three arms - people, dispose-that-exists, no-rail - and each one " +
                "answers true: a key handed to an async scroll must never also " +
                "reach the default search",
            3,
            Regex("\\btrue\\b").findAll(upArm).count()
        )
        assertTrue(
            "the arm as a whole ends on the consuming true of its last branch",
            upArm.trim().endsWith("true }")
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
        assertTrue("the focus request must follow the scroll", scroll in 0 until rail)
        assertTrue(
            "and the PLAY button - in the fixed header ABOVE the list, so " +
                "always composed - is the anchor behind the rail: a page with " +
                "no people rail is exactly the case the old bare request threw on",
            rail in 0 until button
        )
        assertTrue(
            "the request is retried while the page recomposes, not tried once",
            topOfPage.contains("while (attempts < RETURN_FOCUS_STEPS)")
        )
    }

    @Test
    fun `the working neighbours are untouched`() {
        assertTrue(
            "the people-column attempt itself must not have been folded into " +
                "the reveal fallback",
            upArm.trim().startsWith("if ( focusChipColumn( PEOPLE_ROW_KEY, networkIndex ) )")
        )
        assertTrue(
            "DOWN still aims at the production chip in this column",
            networkRow.contains(
                "Key.DirectionDown -> focusChipColumn( PRODUCTION_ROW_KEY, networkIndex )"
            )
        )
    }

    private companion object {
        const val DETAIL = "com/kennyb1201/kbstream/ui/detail/DetailScreen.kt"
    }
}
