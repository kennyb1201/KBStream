package com.kennyb1201.kbstream.ui.library

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Library card with nowhere to go must say so, not swallow the press.
 *
 * `LibraryItem.navigationId` is null for a tracker row that carries only a
 * title and a poster (no IMDB or TMDB id). The card still rendered and still
 * took focus, but its `onClick` ran `item.navigationId?.let { onItemClick(...) }`
 * with no else branch, so pressing it did nothing at all - a dead-end that
 * reads as a frozen grid. The card is deliberately NOT filtered out (that would
 * hide a row the user can only remove from its long-press menu); instead the
 * press reports through the app-wide feedback channel.
 *
 * This is Compose wiring, so a plain JVM test cannot press the card. What it
 * can pin is the branch and the message at the source.
 */
class LibraryDeadEndContractTest {

    private fun source(): String {
        val file = File(findSourceRoot(), LIBRARY)
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
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    @Test
    fun `the grid reads the app-wide feedback channel`() {
        assertTrue(
            "a dead-end card must be able to report why nothing happened",
            source().contains("val feedback = rememberKBFeedback()")
        )
    }

    @Test
    fun `a card with no detail page reports it instead of dropping the press`() {
        val src = source()
        assertTrue(
            "the click must branch on whether there is a detail page",
            src.contains("val navigationId = item.navigationId")
        )
        assertTrue(
            "the null branch must report rather than do nothing",
            src.contains("feedback.show(\"No details page for")
        )
    }

    @Test
    fun `the dead-end row is kept, not filtered out`() {
        // Filtering would hide a row the user can only remove via its
        // long-press menu, so the fix must not add a navigationId filter to the
        // item list.
        val src = source()
        assertFalse(
            "non-navigable rows must not be filtered from the grid",
            src.contains(".filterNot { it.navigationId == null }")
        )
        assertFalse(
            "non-navigable rows must not be filtered from the grid",
            src.contains(".filter { it.navigationId != null }")
        )
    }

    private companion object {
        const val LIBRARY = "com/kennyb1201/kbstream/ui/library/LibraryScreen.kt"
    }
}
