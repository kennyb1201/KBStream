package com.kennyb1201.kbstream.ui.mobile

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F7a and F7b: how Detail lays itself out on a wide window.
 *
 * The arithmetic belongs to `MobileAdaptiveRules` and `MobileAdaptiveRulesTest`
 * already pins it at 599/839dp and per class. What is pinned here is the page's
 * own use of those answers, and the property the spec calls out as the thing that
 * must not fork: the header is ONE lambda called by whichever list is on screen,
 * so the trailer, the hero chrome fade and the season/episode selection stay in
 * the single hoisted state they had before the pane existed.
 */
class DetailColumnCapContractTest {

    private val detail: String by lazy { stripComments(source(DETAIL)) }

    @Test
    fun `the medium column cap comes from the shared rule`() {
        assertTrue(
            "Detail no longer asks detailColumnMaxWidthDp, so a Medium window " +
                "keeps an overview paragraph running the full 840dp",
            detail.contains("MobileAdaptiveRules.detailColumnMaxWidthDp(widthClass)")
        )
        assertTrue(
            "the cap must be a widthIn on the page's own column, not on a section",
            detail.contains("Modifier.widthIn(max = detailColumnCapDp.dp)")
        )
        assertTrue(
            "the capped column is centred by the row's own arrangement - a " +
                "per-branch align is the kind of thing that silently stops " +
                "applying when the container changes",
            detail.contains("horizontalArrangement = Arrangement.Center")
        )
    }

    @Test
    fun `expanded is list-detail, with the left pane from the rule`() {
        assertTrue(
            "Detail no longer asks detailTwoPaneApplies, so an expanded window " +
                "keeps the single phone column",
            detail.contains("MobileAdaptiveRules.detailTwoPaneApplies(widthClass)")
        )
        assertTrue(
            "the left pane takes the rule's width, not a literal",
            detail.contains("MobileAdaptiveRules.DETAIL_LEFT_PANE_WIDTH_DP.dp")
        )
        assertTrue(
            "the right pane must take the remaining width, or the two lists " +
                "split the window down the middle",
            detail.contains(".weight(1f)")
        )
    }

    @Test
    fun `the header is one lambda, shown by whichever list is on screen`() {
        assertEquals(
            "the header body must exist once - a second copy is how the trailer " +
                "and the hero chrome fade end up with two identities",
            1,
            Regex("val detailHeader: @Composable \\(\\) -> Unit = \\{").findAll(detail).count()
        )
        assertTrue(
            "the left pane renders the header",
            detail.contains("item(key = \"header\") { detailHeader() }")
        )
        assertTrue(
            "and the single-column layout does too, behind the two-pane guard",
            detail.contains("if (!twoPane) {") &&
                detail.contains("item(key = \"header\") { detailHeader() }")
        )
        assertEquals(
            "the header is composed in exactly two places: the left pane and the " +
                "guarded item in the single list",
            2,
            Regex("detailHeader\\(\\)").findAll(detail).count()
        )
    }

    @Test
    fun `the left pane carries the header, the calls to action and the facts`() {
        val leftPane = detail.substringAfter("if (twoPane) {").substringBefore("}\n            LazyColumn(")
        listOf("header", "cta", "facts").forEach { key ->
            assertTrue(
                "the left pane must render the $key item - the spec's split is " +
                    "header art, title block, CTA row and facts on the left",
                leftPane.contains("item(key = \"$key\")")
            )
            assertTrue(
                "the single-column list must skip the $key item when the panes " +
                    "are showing, or every one of them is drawn twice",
                Regex("if \\(!twoPane\\) \\{\\s*\\n\\s*item\\(key = \"$key\"\\)").containsMatchIn(detail)
            )
        }
        assertEquals(
            "the calls to action must exist once, like the header",
            1,
            Regex("val detailCta: @Composable \\(\\) -> Unit = \\{").findAll(detail).count()
        )
        assertEquals(
            "and the facts with them",
            1,
            Regex("val detailFacts: @Composable \\(\\) -> Unit = \\{").findAll(detail).count()
        )
    }

    @Test
    fun `crossing the breakpoint keeps both lists where they were`() {
        val hoisted = detail.indexOf("val sectionListState = rememberIdleLazyListState()")
        val row = detail.indexOf("Row(\n                modifier = Modifier.fillMaxSize(),")
        assertTrue("the panes' list states must be hoisted above the branch", row > hoisted && hoisted > 0)
        assertTrue(
            "the section list reads the hoisted state in both layouts",
            detail.contains("state = sectionListState,")
        )
        assertTrue(
            "and so does the left pane",
            detail.contains("state = leftPaneState,")
        )
    }

    @Test
    fun `the panes are layout only`() {
        assertEquals(
            "the hero chrome fade must stay a single piece of state above both " +
                "panes: one copy per pane would fade the CTA twice, at different " +
                "times",
            1,
            Regex("animateFloatAsState\\(").findAll(detail).count()
        )
        assertTrue(
            "the compact path is still the full-width list it always was",
            detail.contains(".fillMaxWidth()")
        )
        assertTrue(
            "the header art is still 16:9 of its own width, which is what makes " +
                "the capped column cap the art with it",
            detail.contains("aspectRatio(16f / 9f)")
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
        const val DETAIL = "com/kennyb1201/kbstream/ui/mobile/DetailScreenMobile.kt"
    }
}
