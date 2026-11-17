package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-17: small, quiet chevrons on the phone billboard's art.
 *
 * Kenny asked for arrows and, in the same breath, for them not to be "huge and
 * ugly and too noticable". Those two demands are the two halves of this class: the
 * step (F1: a tap is manual navigation, wrapping like the auto-advance) and the
 * restraint (F2/F3: an 18dp glyph on a 26dp barely-there backing, at 0.55 alpha,
 * with a 48dp hit target and a ring only while focused).
 *
 * Placement is the half that cannot be seen from here, but it is measurable in the
 * source: the arrows are aligned to the mid-height edges, and the page's own
 * content stays in the bottom block. S1-S7 (where they land on the TCL, whether a
 * thumb finds them) are Kenny's; this class claims no frame.
 */
class BillboardChevronsContractTest {

    private val mobile by lazy {
        source("com/kennyb1201/kbstream/ui/mobile/HomeScreenMobile.kt")
    }
    private val spotlight by lazy { source("com/kennyb1201/kbstream/ui/home/SpotlightHome.kt") }

    private fun chevron(): String =
        mobile.substringAfter("private fun BillboardPageChevron(")
            .substringBefore("/**\n * The three pills a hero page can draw")

    // ── F1: a tap steps one page, wrapping ────────────────────────────────

    @Test
    fun `a tap steps the pager with the auto-advance's own wrap`() {
        val step = mobile.substringAfter("val stepPage: (Int) -> Unit = { offset ->")
            .substringBefore("// The pills, handed to the CURRENT page's hero")
        assertTrue(
            "the wrap rule decides, from the page in front",
            step.contains("billboardWrapPage(pages.size, pagerState.currentPage, offset)")
        )
        assertTrue(
            "and the step is the pager's own animation, like a segment press",
            step.contains("scope.launch { pagerState.animateScrollToPage(target) }")
        )
        assertTrue(
            "left steps back, right steps forward",
            mobile.contains("{ stepPage(-1) }") && mobile.contains("{ stepPage(1) }")
        )
    }

    @Test
    fun `they draw on the page in front, on either surface, and only with another page`() {
        val call = mobile.substringAfter("onPreviousPage = if (")
            .substringBefore("onClick = { onDetails(page) }")
        assertFalse(
            "the TV used to be excluded by a fixed heroHeight; the gate is now " +
                "about the pages, not the screen (2026-11-17)",
            call.contains("heroHeight")
        )
        assertTrue(
            "only the page in front carries live controls",
            call.contains("index == pagerState.currentPage")
        )
        assertTrue(
            "nothing to cycle, nothing drawn",
            call.contains("pages.size > 1")
        )
        // The TV gets them through this shared component, not through its own
        // call: SpotlightHeroSection still hands in nothing extra, which is what
        // keeps one gate in one place.
        assertTrue(
            "the TV hero owns no chevron wiring of its own",
            !spotlight.contains("onPreviousPage") && !spotlight.contains("onNextPage")
        )
        assertTrue(
            "and the segments stay phone-only - that gate is not this one",
            mobile.contains("if (pages.size > 1 && heroHeight == null) {")
        )
    }

    @Test
    fun `both surfaces get the same quiet control`() {
        // F2/F4 of the TV spec: a D-pad stop with the phone's measurements. The
        // component is shared, so "same subtlety" is a property of there being one
        // implementation, not two that agree today.
        val chevron = chevron()
        assertTrue(
            "a 48dp focusable hit area with its own interaction source",
            chevron.contains(".size(48.dp)") && chevron.contains("MutableInteractionSource()")
        )
        assertTrue(
            "and the focus ring and firm-up are the only extra furniture",
            chevron.contains("if (focused) {") && chevron.contains("focused -> 0.95f")
        )
        assertFalse(
            "entry focus is still the caller's to place - the chevron asks for it " +
                "from nowhere",
            chevron.contains("FocusRequester") || chevron.contains("focusRequester")
        )
    }

    // ── F2: subtle by construction ────────────────────────────────────────

    @Test
    fun `the glyph is small and faint, the hit target is not`() {
        val chevron = chevron()
        assertTrue("the glyph is 18dp: about half a pill's label", chevron.contains(".size(18.dp)"))
        assertTrue("on a barely-there 26dp backing", chevron.contains(".size(26.dp)"))
        assertTrue(
            "resting at 0.55 alpha - part of the art, not chrome",
            chevron.contains("else -> 0.55f")
        )
        assertTrue(
            "firm on focus and at press, so a focus user can see where they are",
            chevron.contains("focused -> 0.95f") && chevron.contains("pressed -> 1f")
        )
        assertTrue(
            "the THUMB's target stays 48dp",
            chevron.contains(".size(48.dp)")
        )
        assertTrue(
            "and the ring is drawn only while focused",
            chevron.contains("if (focused) {") && chevron.contains("Modifier.border(1.5.dp, KBAccent, CircleShape)")
        )
        assertFalse(
            "no border, no plate at rest",
            chevron.contains("border(1.dp") || chevron.contains("KBShapePanel")
        )
    }

    // ── F3: placement clears everything ───────────────────────────────────

    @Test
    fun `they sit at the mid-height edges, where no page content lives`() {
        val overlay = mobile.substringAfter("// The page-step chevrons (F1/F3).")
            .substringBefore("// A playing trailer needs a way out")
        assertTrue(
            "left arrow at the vertical middle of the left edge",
            overlay.contains(".align(Alignment.CenterStart)")
        )
        assertTrue(
            "right arrow at the vertical middle of the right edge",
            overlay.contains(".align(Alignment.CenterEnd)")
        )
        assertFalse(
            "never in the bottom block the clearlogo, meta and pills share",
            overlay.contains("Alignment.BottomStart")
        )
        assertFalse(
            "and never where the trailer's close mark is",
            overlay.contains("Alignment.TopEnd")
        )
        // The bottom block's own clearance is untouched.
        assertTrue(
            "the content column still keeps its 40dp for the segments",
            mobile.contains("padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 40.dp)")
        )
    }

    // ── F4/F5/F7: focusable, non-greedy, and nothing else changes ─────────

    @Test
    fun `they are focusable and they do not swallow the art`() {
        val chevron = chevron()
        assertTrue(
            "F4: a real focusable, on its own interaction source so focus is visible",
            chevron.contains("MutableInteractionSource()") &&
                chevron.contains("collectIsFocusedAsState()") &&
                chevron.contains(".clickable(")
        )
        assertTrue(
            "F5: the hero's own tap is still the art's, and the arrows are children " +
                "of that box, not a replacement for it",
            mobile.contains(".clickable(onClick = onClick)\n    ) {\n        AsyncImage(") &&
                mobile.contains("contentDescription = if (forward) \"Next title\" else \"Previous title\"")
        )
        assertFalse(
            "F7: no page counter came back with them",
            mobile.contains(" of \" + (pagerState.currentPage + 1)")
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun source(relative: String): String {
        val file = File(findSourceRoot(), relative)
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
        throw AssertionError("main source root not found from " + System.getProperty("user.dir"))
    }
}
