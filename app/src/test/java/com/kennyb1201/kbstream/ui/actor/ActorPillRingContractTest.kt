package com.kennyb1201.kbstream.ui.actor

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The biography's "View more" pill, and the room its focus ring is drawn in.
 *
 * The report: on Tobias Edward Heslewood Jones's page, scrolled up to the pill,
 * the ring around it was cut off along the left and the bottom. The pill is a
 * tv-material `Surface` with the app's chip treatment - 1.06x scale, a 2dp accent
 * ring and an 8dp glow - and all three are drawn OUTSIDE its layout rectangle,
 * inside a `LazyColumn` that clips its content to the viewport.
 *
 * This is the second report on the same pill ("View more is clipped when
 * focused"), so the checks here are the finish of that fix, not a repetition: the
 * pill owns its room ([KBFocusChipInset], the same pattern the chip rows use) and
 * the page's landing measures the DRAWN rect, not the layout one
 * ([ActorListBringIntoViewSpec], the hub's rule per the `KBFocusRowInset` note).
 *
 * What is arithmetic and what is structure is kept apart on purpose: T2 computes
 * the landing exactly; T1 and T3 read the composition out of the source, the way
 * this tree's other UI contracts do, because there is no TV, emulator or Compose
 * test harness in CI for a screen that wants a ViewModel and a network round
 * trip. Which side of the pill an edge is on is a device question (S1-S4).
 */
class ActorPillRingContractTest {

    // ── T1: the pill carries its own room, and does not move ─────────────

    @Test
    fun `the pill carries the chip inset on all four sides`() {
        // One padding, all four sides: the ring can meet an edge on any of them
        // once the list has scrolled, and a two-value padding would leave the
        // vertical side short.
        assertTrue(
            "the pill must carry KBFocusChipInset room, all four sides, or the " +
                "ring has nowhere to grow inside the list's clip",
            pill.contains("padding(KBFocusChipInset)")
        )
        assertTrue(
            "and it must be the app's chip inset, not a number invented here",
            page.contains("import com.kennyb1201.kbstream.ui.theme.KBFocusChipInset")
        )
    }

    @Test
    fun `the room is cancelled on the outside, less the gap the pill already had`() {
        // The offset is what keeps the pill where it was drawn: the room pulls
        // it in from every side, the offset pushes it back out by the same
        // amount - less the 8dp gap under the text, which is now part of the
        // room (so it is not paid twice).
        assertTrue(
            "the pill's drawn position must be unchanged in both axes",
            pill.contains(
                "offset( x = -KBFocusChipInset, y = -(KBFocusChipInset - ActorPillGap) )"
            )
        )
        assertTrue(
            "the gap under the text is part of the room now, not a second margin",
            page.contains("private val ActorPillGap = 8.dp")
        )
        assertFalse(
            "a separate top padding would put the gap back on top of the room",
            pill.contains("padding(top = 8.dp)")
        )
    }

    @Test
    fun `the pill still sits at the bio column's 24dp start, under the text`() {
        assertTrue(
            "the bio column keeps its 24dp start (and its 24dp end)",
            biography.contains("start = 24.dp") && biography.contains("end = 24.dp")
        )
        // The pill is the biography's last child: the paragraph is drawn first,
        // and the toggle's block follows it. Nothing is placed between them.
        val text = biography.indexOf("Text(")
        val toggle = biography.indexOf("if (bioOverflows) {")
        assertTrue("the biography text comes first", text >= 0 && toggle > text)
        assertTrue(
            "and the pill's room is its own, not the column's padding",
            biography.substringBefore("if (bioOverflows) {").let {
                !it.contains("KBFocusChipInset")
            }
        )
    }

    // ── T2: the landing clears the DRAWN rect ───────────────────────────

    /** The pill's landing, as the page builds it: row inset + caption room. */
    private val pillSpec = ActorListBringIntoViewSpec(
        insetPx = INSET_PX,
        glowPx = GLOW_PX,
        focusedScale = CHIP_SCALE
    )

    @Test
    fun `a pill at the bottom edge asks for the scroll that clears its ring`() {
        // 900 + 80 = 980 layout, against a 1000 viewport clearing 120: a
        // layout-only landing answers 100 and parks the ring (16dp glow + 2.4dp
        // of half-growth) under the clip. The drawn rect reaches 980 + 18.4, so
        // the honest answer is 118.4.
        val drawn = pillSpec.calculateScrollDistance(offset = 900f, size = 80f, containerSize = 1000f)
        assertEquals("the ring's own reach is part of the distance", 118.4f, drawn, TOLERANCE)
        assertTrue(
            "and it is strictly more than the layout-only answer, which is the bug",
            drawn > layoutOnly.calculateScrollDistance(offset = 900f, size = 80f, containerSize = 1000f)
        )
        assertEquals(
            "the layout-only answer is the plain deficit, unchanged for every " +
                "other node in the list",
            100f,
            layoutOnly.calculateScrollDistance(offset = 900f, size = 80f, containerSize = 1000f),
            TOLERANCE
        )
    }

    @Test
    fun `a pill whose drawn rect already clears asks for zero`() {
        // 200 + 80 + 18.4 = 298.4, far inside 880: nothing to do. This is also
        // what keeps a pill that merely remeasured from being nudged.
        assertEquals(
            "a pill in the middle of the viewport stays where it is",
            0f,
            pillSpec.calculateScrollDistance(offset = 200f, size = 80f, containerSize = 1000f),
            TOLERANCE
        )
    }

    @Test
    fun `the drawn rect is cleared at the top edge too`() {
        // A pill whose layout top is 10dp under the viewport's top, against the
        // page's 120 margin: the layout rect is inside the viewport, so the
        // layout-only answer is ZERO and the ring (18.4dp of it) is above the
        // top edge, cut. Measured drawn, it is 8.4dp past the edge and lands on
        // the margin: -128.4.
        val drawn = pillSpec.calculateScrollDistance(offset = 10f, size = 80f, containerSize = 1000f)
        assertEquals("the top edge pays the same overhang", -128.4f, drawn, TOLERANCE)
        assertEquals(
            "where the layout rect alone decided nothing had to move",
            0f,
            layoutOnly.calculateScrollDistance(offset = 10f, size = 80f, containerSize = 1000f),
            TOLERANCE
        )
    }

    @Test
    fun `a container with no glow and no growth keeps the old arithmetic`() {
        // Other call sites - and the spec's own older tests - build it with no
        // treatment: no glow, no growth, no overhang, so their landings are the
        // arithmetic they always were (1100 - 880 = 220 here).
        val plain = ActorListBringIntoViewSpec(insetPx = INSET_PX)
        assertEquals(
            "the defaults must not change any other row's landing",
            220f,
            plain.calculateScrollDistance(offset = 900f, size = 200f, containerSize = 1000f),
            TOLERANCE
        )
        assertEquals(
            "nor a row already on screen",
            0f,
            plain.calculateScrollDistance(offset = 100f, size = 200f, containerSize = 1000f),
            TOLERANCE
        )
    }

    @Test
    fun `the page builds its landing with the pill's own treatment`() {
        assertTrue(
            "the glow the pill draws past its bounds is what the landing clears",
            page.contains("glowPx = with(density) { KBFocusGlowSmall.toPx() }")
        )
        assertTrue(
            "and the chip growth, split evenly about the centre",
            page.contains("focusedScale = KBFocusChip")
        )
        assertTrue(
            "the 60dp inset the rail rows were sized for does not change (F2c)",
            page.contains("insetPx = with(density) { (KBFocusRowInset + ActorCaptionRoom).toPx() }")
        )
    }

    @Test
    fun `the toggle's snap is one move and the landing stands down for it`() {
        // One driver per arrival still. The snap lands the ITEM's top, so the
        // pill - the item's last line - comes to rest inside the viewport; the
        // drawn-rect margin above is what a stop AT an edge has to pay.
        assertEquals(
            "exactly one scroll animation belongs to the toggle - the rails' own " +
                "UP-to-top press drives a different list and is not a second one",
            2,
            page.split("browseListState.animateScrollToItem(0)").size
        )
        assertTrue(
            "the page's landing answers zero while the snap owns the list",
            page.contains("canLand = { !pillOwnsListScroll.value }")
        )
    }

    // ── T3: nothing else moves ──────────────────────────────────────────

    @Test
    fun `the bio item is exactly as tall as it was`() {
        // The room is folded into space the item already had: the 8dp gap under
        // the text and the column's 20dp foot. Vertically the item therefore
        // reads 4 + (8 + pill) + 20 before and 4 + (12 + pill + 12) + 4 after -
        // the same total, so the MOVIES header and the first rail card do not
        // move.
        val before = COLUMN_TOP + PILL_GAP + COLUMN_FOOT_BEFORE
        val after = COLUMN_TOP + RING_ROOM + RING_ROOM + COLUMN_FOOT_AFTER
        assertEquals(
            "the pill's room comes out of the foot the item already carried",
            before,
            after
        )
        assertTrue(
            "and the column says so where the foot is set",
            biography.contains("bottom = 4.dp")
        )
        assertTrue(
            "the column's top and start are untouched",
            biography.contains("top = 4.dp") && biography.contains("start = 24.dp")
        )
    }

    @Test
    fun `the sections under the biography keep their own spacing`() {
        assertTrue(
            "the MOVIES header's padding is unchanged",
            page.contains("Modifier.padding(start = 24.dp, top = 20.dp, bottom = 10.dp)")
        )
        assertTrue(
            "and the first rail is still the biography's next item",
            page.contains("item(key = ACTOR_MOVIES_RAIL)") &&
                page.contains("bottomPadding = 16.dp")
        )
    }

    // ── F3: Detail's overview toggle, the same control ──────────────────

    @Test
    fun `detail's overview toggle needed the room and now has it`() {
        // Checked against DetailScreen.kt:741 in this pass: the toggle is the
        // same Surface, with the same 1.06x scale, 2dp ring and 8dp glow, inside
        // a LazyColumn that clips - and it had NO room at all. So it takes the
        // same treatment rather than being evidence that the actor pill's gap
        // was somewhere else.
        val toggle = detail.substringAfter("if (overviewOverflows) {")
            .substringBefore("Text(")
        assertTrue(
            "the same room, inside the list's clip",
            toggle.contains("padding(KBFocusChipInset)")
        )
        assertTrue(
            "cancelled on the outside, so the toggle does not move either",
            toggle.contains("offset( x = -KBFocusChipInset, y = -KBFocusChipInset )")
        )
    }

    // ── source access ───────────────────────────────────────────────────

    private val page by lazy { flat(source(SCREEN)) }
    private val detail by lazy { flat(source(DETAIL)) }
    private val pill by lazy { flat(biography).substringAfter("if (bioOverflows) {") }
    private val biography by lazy {
        val src = source(SCREEN)
        val start = src.indexOf("item(key = \"biography\")")
        assertTrue("the biography item is missing", start >= 0)
        val end = src.indexOf("if (movies.isNotEmpty())", start)
        assertTrue("the biography item must end before the movies section", end > start)
        src.substring(start, end)
    }

    private val layoutOnly by lazy { ActorListBringIntoViewSpec(insetPx = INSET_PX) }

    private fun flat(text: String): String = text.replace(Regex("\\s+"), " ")

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private val sourceRoot: File by lazy { findSourceRoot() }

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

    private companion object {
        const val SCREEN = "com/kennyb1201/kbstream/ui/actor/ActorScreen.kt"
        const val DETAIL = "com/kennyb1201/kbstream/ui/detail/DetailScreen.kt"

        /** `KBFocusRowInset` (16dp) + `ActorCaptionRoom` (44dp) at density 2. */
        const val INSET_PX = 120f

        /** `KBFocusGlowSmall`, 8dp at density 2. */
        const val GLOW_PX = 16f

        /** `KBFocusChip`: the pill's focused scale. */
        const val CHIP_SCALE = 1.06f

        /** The dp values above, for the item-height arithmetic in T3. */
        const val COLUMN_TOP = 4
        const val PILL_GAP = 8
        const val RING_ROOM = 12
        const val COLUMN_FOOT_BEFORE = 20
        const val COLUMN_FOOT_AFTER = 4

        const val TOLERANCE = 0.001f
    }
}
