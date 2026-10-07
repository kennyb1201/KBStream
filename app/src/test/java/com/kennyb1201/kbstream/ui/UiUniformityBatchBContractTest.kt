package com.kennyb1201.kbstream.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Batch B of the UI uniformity sweep: the screen edge, the spacing scale and
 * the rail heading.
 *
 * All three had drifted into per-screen literals - Search and the two discover
 * grids lined their rails up to 20dp while the rest of the app used 24dp, the
 * same rail sat 10dp apart on one screen and 12dp on the next, and Detail drew
 * its rail headings in a dim 12sp style of its own while Home drew them in
 * another. Four dp and one type step are invisible at ten feet, which is
 * exactly why the drift survived: nothing ever looked broken, only slightly
 * different from the screen before it.
 *
 * Source-level because a television is the only place the difference shows.
 */
class UiUniformityBatchBContractTest {

    private val mainDir: File by lazy { findMainDir() }

    private fun findMainDir(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "src/main not found walking up from " + System.getProperty("user.dir")
        )
    }

    private fun source(relative: String): String {
        val file = File(mainDir, "java/com/kennyb1201/kbstream/$relative")
        assertTrue("source missing: $file", file.isFile)
        return squash(file.readText())
    }

    private fun squash(text: String): String = text.replace(Regex("\\s+"), " ")

    private fun count(haystack: String, needle: String): Int =
        Regex(Regex.escape(needle)).findAll(haystack).count()

    @Test
    fun `the screen edge is one value`() {
        assertTrue(
            "the app's screen edge has to be a token, not a per-screen literal",
            source("ui/theme/Theme.kt").contains("val KBScreenEdge = 24.dp")
        )
        assertTrue(
            "Search's edge must be that token",
            source("ui/search/SearchScreenPart2.kt")
                .contains("internal val SEARCH_RAIL_EDGE_PADDING = KBScreenEdge")
        )
        listOf(
            "ui/decade/DecadeScreen.kt",
            "ui/home/CatalogGridScreen.kt"
        ).forEach { path ->
            val text = source(path)
            assertTrue(
                "$path must line its grid up to the shared screen edge",
                text.contains("start = KBScreenEdge, end = KBScreenEdge")
            )
            assertFalse(
                "$path still insets its content by its own 20dp",
                text.contains("start = 20.dp, end = 20.dp")
            )
        }
    }

    @Test
    fun `a rail inside the search column insets once`() {
        val search = source("ui/search/SearchScreen.kt")
        val part2 = source("ui/search/SearchScreenPart2.kt")

        assertEquals(
            "the search column keeps its own edge inset (plus the comment that " +
                "names it), and nothing else in the file does",
            3,
            count(search, "SEARCH_RAIL_EDGE_PADDING")
        )
        assertEquals(
            "each rail's side room is the focus inset, cancelled so the tiles " +
                "line up with the heading above them",
            3,
            count(search, "Modifier.offset(x = -KBFocusChipInset)")
        )
        assertEquals(
            "the shared search rail does the same",
            1,
            count(part2, "Modifier.offset(x = -KBFocusChipInset)")
        )
        assertTrue(
            "and its side room is the focus inset rather than the screen edge",
            part2.contains("end = KBFocusChipInset")
        )
    }

    @Test
    fun `rows in the search screen share one item gap`() {
        assertTrue(
            "the shared rail asks the spacing scale for its gap",
            source("ui/search/SearchScreenPart2.kt")
                .contains("horizontalArrangement = Arrangement.spacedBy(KBSpacing.md)")
        )
        assertTrue(
            "and the titles rail it sits beside uses the same 12dp, not 10dp",
            source("ui/search/SearchScreen.kt").contains("spacedBy(12.dp)")
        )
    }

    @Test
    fun `the spacing scale holds the app's five steps`() {
        val theme = source("ui/theme/Theme.kt")
        assertTrue("the spacing scale is missing", theme.contains("object KBSpacing {"))
        listOf(
            "val xs = 4.dp",
            "val sm = 8.dp",
            "val md = 12.dp",
            "val lg = 16.dp",
            "val xl = 24.dp"
        ).forEach { step ->
            assertTrue("the spacing scale is missing $step", theme.contains(step))
        }
    }

    @Test
    fun `every rail heading is the shared section header`() {
        assertTrue(
            "Home's rail titles are the shared heading, not a style of their own",
            source("ui/home/HomeScreen.kt").contains("KBSectionHeader( title = text,")
        )
        val detail = source("ui/detail/DetailScreen.kt")
        assertEquals(
            "Detail's rail headings are all the shared heading",
            7,
            count(detail, "KBSectionHeader(")
        )
        // The file-wide "no titleSmall in Detail" check this replaces was
        // over-broad: it also ruled out the episode card's own NAME, which is a
        // TITLE and is supposed to be titleSmall (Batch D item 25). What the
        // old treatment actually was is a heading drawn at its own 14sp
        // Medium, so pin the headings themselves - reintroducing a hand-rolled
        // one fails these, and the cards are free to name a title a title.
        listOf("PEOPLE", "NETWORK", "PRODUCTION", "REVIEWS", "MORE LIKE THIS")
            .forEach { label ->
                assertTrue(
                    "Detail's \"$label\" heading is not the shared section header",
                    detail.contains("KBSectionHeader( title = \"$label\",")
                )
            }
        assertTrue(
            "and Home's collection rail is not a fourth heading treatment of " +
                "its own",
            source("ui/kb/KBHomeSlots.kt")
                .contains("KBSectionHeader( title = collection.title.ifBlank")
        )
    }
}
