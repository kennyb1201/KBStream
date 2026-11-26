package com.kennyb1201.kbstream.ui.mobile

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F9's wiring: Search's two caps, pinned where the rules already are.
 *
 * [MobileAdaptiveRules] owns the numbers - 1200dp of content, a 640dp query
 * field, both Expanded only - and `MobileAdaptiveRulesTest` pins them. What that
 * test cannot see is whether Search actually asks for them: a rule nobody calls
 * is a comment, and these two were exactly that until this wiring landed. The
 * assertions below are the join, and the guard assertions are the promise that a
 * phone's Search still renders what it always did.
 */
class SearchAdaptiveWiringContractTest {

    private val search: String by lazy { stripComments(source(SEARCH)) }

    /** The query field's row, and nothing below it. */
    private val searchBar: String by lazy {
        search.substringAfter("item(key = \"searchbar\")").substringBefore("val statusText")
    }

    @Test
    fun `Search asks the rules for both caps`() {
        assertTrue(
            "Search no longer asks searchContentMaxWidthDp, so the chip grid and " +
                "the result list run the full width of a landscape tablet",
            search.contains("MobileAdaptiveRules.searchContentMaxWidthDp(")
        )
        assertTrue(
            "Search no longer asks searchFieldMaxWidthDp, so the query field " +
                "stretches with the column",
            search.contains("MobileAdaptiveRules.searchFieldMaxWidthDp(")
        )
        assertTrue(
            "both caps must come from the one width class the chip columns " +
                "already read, not a second resolution of the window",
            search.contains("val widthClass = rememberMobileWindowWidthClass()") &&
                search.contains("MobileAdaptiveRules.searchChipColumns(widthClass)")
        )
    }

    @Test
    fun `the content column is capped and centred on an expanded window`() {
        assertTrue(
            "the cap must be a widthIn on the list container, not on the items",
            search.contains("Modifier.widthIn(max = contentMaxWidthDp.dp)")
        )
        assertTrue(
            "and the capped column is centred, or the cap just leaves a gap at " +
                "the end of every row",
            search.contains(".align(Alignment.TopCenter)")
        )
    }

    @Test
    fun `the query field is capped rather than the row it sits in`() {
        assertTrue(
            "the field keeps its weight, so below Expanded it is still as wide " +
                "as the row gives it",
            searchBar.contains(".weight(1f)")
        )
        assertTrue(
            "and above Expanded it stops at the rule's cap",
            searchBar.contains("Modifier.widthIn(max = fieldMaxWidthDp.dp)")
        )
        assertTrue(
            "and the row itself still spans the column, so capping the field " +
                "does not pull the back button in from the edge beside it",
            searchBar.contains(".padding(start = 4.dp, end = 12.dp, top = 4.dp)")
        )
    }

    @Test
    fun `both caps are no-ops below expanded`() {
        assertTrue(
            "each cap must be guarded by the rule's 0-means-unlimited answer, " +
                "or a phone's Search loses width the spec must not take from it",
            search.contains("if (contentMaxWidthDp > 0)") &&
                search.contains("if (fieldMaxWidthDp > 0)")
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
        const val SEARCH = "com/kennyb1201/kbstream/ui/mobile/SearchScreenMobile.kt"
    }
}
