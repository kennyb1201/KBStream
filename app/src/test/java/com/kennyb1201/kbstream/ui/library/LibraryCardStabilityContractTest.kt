package com.kennyb1201.kbstream.ui.library

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Perf spec 2026-11-22, item 4 remainder, on the Library grid.
 *
 * The Home rails pin their clicks in [com.kennyb1201.kbstream.ui.home.CardClickStabilityContractTest];
 * this is the same invariant on the other D-pad grid the spec names. Two halves:
 * the card's click and long press are remembered per cell, and - unlike a Home
 * rail card, whose modifier carries a `@Composable` hero hook - the grid's whole
 * modifier chain is rememberable, so it is remembered too. A cell handed a fresh
 * click or a fresh chain on every pass cannot skip, and a D-pad step inside the
 * grid is exactly such a pass.
 */
class LibraryCardStabilityContractTest {

    private val grid: String by lazy { stripComments(source(GRID)) }

    /** The card call in the grid, not the composable's own definition. */
    private val cardCall: String by lazy {
        grid.substringAfter("LibraryPosterCard(\n                item = item,")
            .substringBefore("\n            )")
    }

    @Test
    fun `the grid card is handed remembered handlers and a remembered modifier`() {
        assertTrue(
            "the cell's click must be the remembered instance",
            cardCall.contains("onClick = cardClick,")
        )
        assertTrue(
            "and so must its long press",
            cardCall.contains("onLongClick = cardLongClick,")
        )
        assertTrue(
            "and the modifier chain, or the card still sees a new parameter " +
                "on every pass even with stable lambdas",
            cardCall.contains("modifier = cardModifier")
        )
        assertTrue(
            "no literal handler may come back",
            !Regex("""on(Click|LongClick)\s*=\s*\{""").containsMatchIn(cardCall)
        )
    }

    @Test
    fun `the click is remembered on the row it opens`() {
        assertTrue(
            "the click is remembered on the item, its card key and its " +
                "position - the three things the record and the navigation use",
            grid.contains("val cardClick = remember(item, cardKey, index) {")
        )
        assertTrue(
            "and the handler is read through rememberUpdatedState, because the " +
                "screen passes a fresh closure",
            grid.contains("val currentOnItemClick = rememberUpdatedState(onItemClick)")
        )
        assertTrue(
            "the record still precedes the navigation inside the remembered body",
            grid.indexOf("BrowseReturnFocus.record(") <
                grid.indexOf("currentOnItemClick.value(item.mediaType, navigationId)")
        )
    }

    @Test
    fun `the long press is remembered on the successor it hands over`() {
        assertTrue(
            "the long press carries the item and the key of the card that " +
                "takes its place, so both are its remember keys",
            grid.contains("remember(item, successorKey) {")
        )
        assertTrue(
            "and it is only offered when the grid actually has a menu",
            grid.contains("if (onItemLongClick == null) {")
        )
    }

    @Test
    fun `the modifier is remembered without losing the walk trigger`() {
        assertTrue(
            "the chain is remembered on the cell's key, its requester, whether " +
                "it is the return target and the return requester",
            grid.contains(
                "val cardModifier = remember(\n" +
                    "                cardKey,\n" +
                    "                cardRequester,\n" +
                    "                isReturnItem,\n" +
                    "                returnFocusRequester\n" +
                    "            ) {"
            )
        )
        assertEquals(
            "the D-pad walk still reports the step it landed on, from inside " +
                "the remembered chain",
            1,
            Regex(Regex.escape(".onFocusChanged { if (it.isFocused) walkStep = cardKey }"))
                .findAll(grid).count()
        )
        assertTrue(
            "and the removal animation still rides the cell",
            grid.contains(".animateItem()")
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

    private fun findMainSourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        error("src/main/java not found above ${System.getProperty("user.dir")}")
    }

    private companion object {
        const val GRID = "com/kennyb1201/kbstream/ui/library/LibraryScreen.kt"
    }
}
