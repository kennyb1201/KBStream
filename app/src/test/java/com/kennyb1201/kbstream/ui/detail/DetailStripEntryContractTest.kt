package com.kennyb1201.kbstream.ui.detail

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DOWN out of the detail page's tab strip, and the overview above it
 * (2026-11-21 revision).
 *
 * The field report: "i cant scroll down to the new revamped tabs on detail…
 * the detail trap is still there" - with a paste that showed `HEADER DOWN ->
 * GENRE`, `GENRE DOWN -> TABS` eight times and NOT ONE `TABS DOWN ->` line. The
 * press was reaching [enterTabContent] and leaving it without saying where it
 * went, so "the landing failed" and "nothing was ever asked" read the same; and
 * the function's last arm could hand the press to a geometric search that had
 * nothing to find, leaving the key consumed with the focus still on the pill.
 *
 * What is pinned here is the wiring, because that is a key handler and no unit
 * test can press one: every exit is traced, and the page has a landing of last
 * resort that is not a no-op. The rule halves - the chain, the tab row sets and
 * the overview's own stop - live in DetailFocusChainTest and DetailTabsTest.
 */
class DetailStripEntryContractTest {

    private val detail: String by lazy {
        normalized(stripComments(File(findMainSourceRoot(), DETAIL).readText()))
    }

    /** DOWN out of the strip: the function and nothing after it. */
    private val entry: String by lazy {
        detail.substringAfter("suspend fun enterTabContent(): Boolean {")
            .substringBefore("fun rowsStillArriving()")
    }

    // ── Every exit says where it went ─────────────────────────────────────

    @Test
    fun `every exit from the strip is traced in the move format`() {
        assertTrue(
            "one line, the same shape as every other move (row, direction, " +
                "target, landed, via) - the trace that was missing from the paste",
            entry.contains(
                "detail move TABS DOWN -> \$target landed=\$landed via=\$via"
            )
        )
        assertTrue(
            "and each arm names itself, so the next paste says WHICH landing " +
                "failed rather than only that the press went nowhere",
            entry.contains("trace(target.name, true, \"chain\")") &&
                entry.contains("trace(\"geometry\", true, \"geometry\")") &&
                entry.contains("trace(\"container\", container, \"container\")")
        )
        assertTrue(
            "a chain target that would not land is reported as such, not " +
                "swallowed",
            entry.contains("trace(target.name, false, \"chain\")")
        )
    }

    // ── The landing of last resort ────────────────────────────────────────

    @Test
    fun `the strip's DOWN always ends somewhere real`() {
        assertTrue(
            "the last arm is the tab container itself, so an empty or still-" +
                "loading tab is entered rather than bounced",
            entry.contains("val container = landOnContainer()") &&
                entry.contains("tabContentFocusSink.requestFocus()")
        )
        assertTrue(
            "the container arm runs only after the chain and the platform's " +
                "search have both failed",
            entry.indexOf("focusByGeometry(DetailFocusDirection.DOWN)") <
                entry.indexOf("val container = landOnContainer()")
        )
        assertTrue(
            "and it is retried across frames, because the region can be one " +
                "frame behind the tab switch that asked for it",
            entry.contains("while (attempts < RETURN_FOCUS_STEPS)") &&
                entry.contains("awaitFrame()")
        )
    }

    @Test
    fun `the container is focusable only while the fallback is asking`() {
        assertTrue(
            "a container that could always take focus would win the geometric " +
                "search against the cards inside it, which would take DOWN away " +
                "from rows that DID land",
            detail.contains(".focusProperties { canFocus = tabContentSinkArmed }")
        )
        assertTrue(
            "armed by the fallback",
            entry.contains("tabContentSinkArmed = true")
        )
        assertTrue(
            "and disarmed the moment the viewer leaves it, so it cannot become " +
                "a shadow row of the page",
            detail.contains(
                "if (!state.isFocused && tabContentSinkArmed) { tabContentSinkArmed = false }"
            )
        )
        assertTrue(
            "the sink is a 1dp node at the top of the content region: no layout " +
                "cost, and the platform's search from it goes DOWN into the tab",
            detail.contains(".size(1.dp)") &&
                detail.contains(".focusRequester(tabContentFocusSink)")
        )
    }

    // ── The overview is above the strip, on every tab ─────────────────────

    @Test
    fun `the overview paints in the fixed header, above the strip`() {
        val header = detail.substringAfter("val overviewText =")
            .substringBefore("DetailTabStrip(")
        assertTrue(
            "the block is composed before the strip, so it is above it on every " +
                "tab instead of living in the Details tab's list",
            header.contains("OVERVIEW_COLLAPSED_LINES") &&
                detail.contains("OVERVIEW_EXPANDED_LINES")
        )
        assertFalse(
            "and it is no longer a list item of the Details tab",
            detail.contains("OVERVIEW_ROW_KEY")
        )
        assertFalse(
            "nor gated on a tab: switching tabs must never move or recompose it",
            header.contains("activeTab == DetailTab.DETAILS")
        )
        assertTrue(
            "the toggle still publishes its presence from the block that draws it",
            header.contains("overviewToggleAvailable = true")
        )
    }

    @Test
    fun `the overview's toggle is the chain stop between the chips and the strip`() {
        assertTrue(
            "the toggle is what publishes the OVERVIEW row's presence",
            detail.contains("hasOverviewRow = overviewToggleAvailable")
        )
        assertTrue(
            "and it is reached through the same focusRow the chain uses for every " +
                "other row",
            detail.contains("DetailRow.OVERVIEW -> focusOverviewToggle()")
        )
        assertTrue(
            "a remembered landing above the strip is not a landing INSIDE the tab, " +
                "so the strip's DOWN never re-enters onto it",
            entry.contains("remembered != DetailRow.OVERVIEW")
        )
    }

    // ── helpers ────────────────────────────────────────────────────────────

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
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private companion object {
        const val DETAIL = "com/kennyb1201/kbstream/ui/detail/DetailScreen.kt"
    }
}
