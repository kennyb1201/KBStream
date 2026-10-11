package com.kennyb1201.kbstream.ui.detail

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reported trap: on an item with networks but NO cast, UP on a network chip
 * did nothing at all.
 *
 * The UP handler tries the people column first, and when that rail is not
 * composed (no cast and no writer) it fell back to a bare
 * `movieDetailsFocusRequester.requestFocus()`. That request throws once the
 * rail has been disposed by scrolling - LazyColumn drops what is off screen -
 * so the handler answered false, the default search had nothing in the same
 * column to move to either, and the key stayed trapped on the chip.
 *
 * What this pins: the fallback scrolls the page back to the top FIRST, so the
 * rail is composed again, and only then asks it for the focus; and the press is
 * consumed so the default search cannot move the focus a second time.
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
                "the people card in this column",
            upArm.contains(
                "if ( focusChipColumn( PEOPLE_ROW_KEY, networkIndex ) ) { true } else {"
            )
        )
    }

    @Test
    fun `a failed people jump scrolls the page back up before asking for the focus`() {
        assertTrue(
            "the rail above is only composable again once the list is back at " +
                "the top, so the scroll has to come first",
            upArm.contains("scope.launch { detailListState .scrollToItem(0)")
        )
        val scroll = upArm.indexOf("scrollToItem(0)")
        val focus = upArm.indexOf("movieDetailsFocusRequester .requestFocus()")
        assertTrue("the focus request must follow the scroll", scroll in 0 until focus)
        assertTrue(
            "and the LazyColumn gets a moment to re-compose the item before it: " +
                "a request issued in the same frame as the scroll lands on a " +
                "node that is not there yet",
            upArm.contains("scrollToItem(0) delay(100) runCatching { movieDetailsFocusRequester .requestFocus() }")
        )
    }

    @Test
    fun `the press is consumed so the fallback search cannot move the focus twice`() {
        assertTrue(
            "the launched block owns the move; answering false here would let " +
                "the default search - and the network rail's restorer - run too",
            upArm.trim().contains("} true }")
        )
    }

    @Test
    fun `the working neighbours are untouched`() {
        assertTrue(
            "the people-column attempt itself must not have been folded into " +
                "the scroll fallback",
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
