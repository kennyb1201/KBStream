package com.kennyb1201.kbstream.ui.components

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The corner clearlogo's trim: draw the mark's INK, not its padded file.
 *
 * Field report (2026-10-25): in ONE landscape rail, at ONE card size, some
 * clearlogos render at a perfect size and others render far too small. The box
 * is constant (height = card width x 0.135, clamped 22-32dp, width capped at
 * 68%), so the card is ruled out. What varies is the artwork: TMDB's logo files
 * carry very different amounts of transparent padding, and the card fits the
 * WHOLE file - padding included - into that box, so a padded wordmark's ink
 * lands far smaller than the slot while a tight one fills it.
 *
 * So the fix is to measure the ink's bounding box and fit THAT, while the slot
 * itself stays exactly as it is. The cases pinned here are the ones that decide
 * whether that is safe:
 *
 *  - padding must not count towards the drawn size (the bug);
 *  - a tight logo must be a no-op (the previously-perfect controls must not
 *    change - growing the box instead would have oversized every one of them);
 *  - wide ink must still shrink by the 68% cap, measured on ink, with the
 *    file's own width irrelevant;
 *  - a soft drop shadow is ink, a faint halo is not (the threshold, pinned);
 *  - a mark the classifier refuses stays refused - a trim that could rescue an
 *    unusable mark would be drawing a blank plate;
 *  - whitening and trimming are independent;
 *  - and the box is measured once per URL, never per frame.
 *
 * Geometry is checked through [layoutBrandMarkInk], the same function the card
 * draws with, so these are the sizes the field pass would see.
 */
class BrandMarkInkTest {

    private val ink = 0xFF000000.toInt()
    private val netflixRed = 0xFFE50914.toInt()

    /** A color with [value] on the alpha channel, which is what the trim reads. */
    private fun alpha(value: Int) = value shl 24

    private fun IntArray.rect(width: Int, x0: Int, y0: Int, w: Int, h: Int, color: Int) {
        for (y in y0 until y0 + h) {
            for (x in x0 until x0 + w) this[y * width + x] = color
        }
    }

    /** A slot the size of a 210dp rail card's logo box. */
    private fun railSlot(ink: BrandMarkInk, imageWidth: Int, imageHeight: Int) =
        layoutBrandMarkInk(
            slotWidth = RAIL_LOGO_WIDTH,
            slotHeight = RAIL_LOGO_HEIGHT,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            ink = ink
        )!!

    // ── the reported bug: padding must not be part of the mark ────────

    @Test
    fun `the box is the ink's bounds, not the file's`() {
        // A 48x48 file with a 24x12 wordmark dead centre: the other three
        // quarters are transparent padding, which is what shrank the mark.
        val pixels = IntArray(TILE * TILE).apply { rect(TILE, 12, 18, 24, 12, ink) }

        val box = brandMarkInk(pixels, TILE, TILE)!!

        assertEquals(0.25f, box.left, EPS)
        assertEquals(0.375f, box.top, EPS)
        assertEquals(0.75f, box.right, EPS)
        assertEquals(0.625f, box.bottom, EPS)
        assertEquals(0.5f, box.widthFraction, EPS)
        assertEquals(0.25f, box.heightFraction, EPS)
    }

    @Test
    fun `a padded wordmark draws as large as the same wordmark drawn tight`() {
        // The same 24x12 wordmark, once in a tight 24x12 file and once in a
        // 48x48 file that is three quarters padding. Fitted by ink they have to
        // land on each other - that equality IS the field fix.
        val tight = brandMarkInk(IntArray(24 * 12) { ink }, 24, 12)!!
        val padded = brandMarkInk(
            IntArray(TILE * TILE).apply { rect(TILE, 12, 18, 24, 12, ink) },
            TILE,
            TILE
        )!!

        val tightPlacement = railSlot(tight, 24, 12)
        val paddedPlacement = railSlot(padded, TILE, TILE)

        assertEquals(RAIL_LOGO_HEIGHT, tightPlacement.height, EPS)
        assertEquals(tightPlacement.height, paddedPlacement.height, EPS)
        assertEquals(tightPlacement.width, paddedPlacement.width, EPS)
        // ...and the padded file gets there by being blown up, which is the
        // whole point: its ink, not its padding, is what fills the slot.
        assertTrue(
            "a padded mark has to be scaled up to draw at the tight mark's size",
            paddedPlacement.scale > 1.5f
        )
    }

    // ── the controls: tight logos must not change ────────────────────

    @Test
    fun `a tight mark is drawn exactly as it was`() {
        // Ink right up to all four edges: the transform has to be an identity,
        // or every logo that already rendered "perfect" grows or clips.
        val box = brandMarkInk(IntArray(TILE * TILE) { ink }, TILE, TILE)!!

        val placement = railSlot(box, TILE, TILE)

        assertEquals(0f, box.left, EPS)
        assertEquals(0f, box.top, EPS)
        assertEquals(1f, box.right, EPS)
        assertEquals(1f, box.bottom, EPS)
        assertEquals(1f, placement.scale, EPS)
        assertEquals(0f, placement.x, EPS)
        assertEquals(0f, placement.y, EPS)
        // A square file in a 143x28 slot draws 28x28, which is what Fit alone
        // drew before the trim existed.
        assertEquals(RAIL_LOGO_HEIGHT, placement.width, EPS)
        assertEquals(RAIL_LOGO_HEIGHT, placement.height, EPS)
    }

    // ── the width cap is applied to the ink ──────────────────────────

    @Test
    fun `wide ink still shrinks by the width cap, and the file's width does not count`() {
        // The same 240x30 wordmark, padded differently: a square file, and a
        // file that is wide enough that the WHOLE-file fit would have been
        // width-limited already. Both must land on the ink.
        val square = IntArray(256 * 256).apply { rect(256, 8, 113, 240, 30, ink) }
        val wideFile = IntArray(512 * 64).apply { rect(512, 8, 17, 240, 30, ink) }

        val squarePlacement = railSlot(brandMarkInk(square, 256, 256)!!, 256, 256)
        val widePlacement = railSlot(brandMarkInk(wideFile, 512, 64)!!, 512, 64)

        // The ink is 8:1, so the 68%-of-card width cap is what limits it - and
        // it is the ink that fills that width, not the padding around it.
        assertEquals(RAIL_LOGO_WIDTH, squarePlacement.width, EPS)
        assertEquals(RAIL_LOGO_WIDTH, widePlacement.width, EPS)
        assertEquals(squarePlacement.height, widePlacement.height, EPS)
        // Shrunk by the cap, not by the slot's height.
        assertTrue(
            "a wide mark must cap by width and come out shorter than the slot",
            squarePlacement.height < RAIL_LOGO_HEIGHT
        )
        // Two different files, two different whole-file fits: the ink result is
        // the same, so nothing about the file box leaks into the drawn size.
        assertTrue(squarePlacement.scale > widePlacement.scale * 2f)
    }

    // ── the alpha threshold, pinned ──────────────────────────────────

    @Test
    fun `a soft drop shadow is ink and a faint halo is not`() {
        // 1/48 of the tile either way. A shadow at alpha 40 is part of the
        // artwork; the alpha-5 halo around a JPEG's edge is not. Clipping the
        // shadow would shear the mark off at its hard core, which is why the
        // threshold is not [BRAND_MARK_OPAQUE_ALPHA].
        val pixels = IntArray(TILE * TILE)
        pixels.rect(TILE, 8, 8, 32, 32, alpha(5))
        pixels.rect(TILE, 10, 10, 28, 28, alpha(40))
        pixels.rect(TILE, 12, 12, 24, 24, ink)

        val box = brandMarkInk(pixels, TILE, TILE)!!

        assertEquals(10f / TILE, box.left, EPS)
        assertEquals(10f / TILE, box.top, EPS)
        assertEquals(38f / TILE, box.right, EPS)
        assertEquals(38f / TILE, box.bottom, EPS)
    }

    @Test
    fun `a pixel counts as ink only above the pinned alpha`() {
        assertTrue(
            "the threshold is pinned by this test, not tuned by eye",
            BRAND_MARK_INK_ALPHA == 10
        )

        val atThreshold = IntArray(TILE * TILE).apply {
            rect(TILE, 20, 20, 4, 4, alpha(BRAND_MARK_INK_ALPHA))
        }
        assertNull(
            "alpha at the threshold is padding, so a file of it has no box at all",
            brandMarkInk(atThreshold, TILE, TILE)
        )

        val above = IntArray(TILE * TILE).apply {
            rect(TILE, 20, 20, 4, 4, alpha(BRAND_MARK_INK_ALPHA + 1))
        }
        val box = brandMarkInk(above, TILE, TILE)!!
        assertEquals(20f / TILE, box.left, EPS)
        assertEquals(24f / TILE, box.right, EPS)
    }

    // ── the unusable path is untouched ───────────────────────────────

    @Test
    fun `an unusable mark never gains a box that could make it drawable`() {
        // A blank plate: no ink, so no box, so the caller draws it untrimmed
        // and the verdict keeps falling back to the corner title.
        val blank = IntArray(TILE * TILE)
        assertNull(brandMarkInk(blank, TILE, TILE))
        assertEquals(BrandMark.UNUSABLE, brandMarkTreatment(blank, TILE, TILE))

        // The 1x1 stub (TNT's mark) does have a box - its whole file - and it
        // must still be refused: trimming is a transform, never a verdict.
        val stub = IntArray(1) { ink }
        assertEquals(BrandMark.UNUSABLE, brandMarkTreatment(stub, 1, 1))
        assertEquals(1f, brandMarkInk(stub, 1, 1)!!.widthFraction, EPS)
    }

    @Test
    fun `a degenerate sample has no box`() {
        assertNull(brandMarkInk(IntArray(0), 0, 0))
        assertNull(brandMarkInk(IntArray(4), 0, 0))
        assertNull(
            "a sample shorter than its own dimensions is not measured",
            brandMarkInk(IntArray(4), 3, 3)
        )
    }

    // ── whitening is independent of the trim ─────────────────────────

    @Test
    fun `a dark padded mark still whitens over its trimmed ink`() {
        val dark = IntArray(TILE * TILE).apply { rect(TILE, 20, 10, 8, 28, ink) }

        assertEquals(BrandMark.WHITEN, brandMarkTreatment(dark, TILE, TILE))

        val box = brandMarkInk(dark, TILE, TILE)!!
        assertEquals(20f / TILE, box.left, EPS)
        assertTrue("the dark glyph is a sixth of the padded file", box.widthFraction < 0.2f)
    }

    @Test
    fun `a colored padded mark is drawn as it is, trimmed`() {
        val colored = IntArray(TILE * TILE).apply { rect(TILE, 8, 20, 32, 8, netflixRed) }

        assertEquals(BrandMark.AS_IS, brandMarkTreatment(colored, TILE, TILE))

        val box = brandMarkInk(colored, TILE, TILE)!!
        assertEquals(8f / TILE, box.left, EPS)
        assertEquals(40f / TILE, box.right, EPS)
    }

    // ── the caching contract ─────────────────────────────────────────

    @Test
    fun `the box is measured once per URL, never per frame`() {
        val mark = squash(source(BRAND_MARK))

        assertTrue(
            "the sample (verdict + ink box) must be held for the URL's lifetime",
            mark.contains("var sample by remember(url)")
        )
        assertTrue(
            "it must be measured in the load callback, off the draw and scroll path",
            mark.contains("val sampled = sampleBrandMark(state.result.image)")
        )
        assertTrue(
            "one sampling call site: a second would be a second pass per composition",
            Regex("sampleBrandMark\\(state").findAll(mark).count() == 1
        )
        assertTrue(
            "the transform must be remembered from the sample and the slot, not " +
                "recomputed in the draw lambda",
            mark.contains("val placement = remember(sample, slot)")
        )
        assertTrue(
            "and applied as a draw-time crop of the same paint",
            mark.contains(".brandMarkInkTrim(placement)")
        )
    }

    @Test
    fun `the logo slot itself is unchanged on both cards`() {
        // The trim must fit the ink INTO the existing box. Growing the box to
        // compensate for padding would oversize every logo that already
        // rendered correctly, which is the fix this is deliberately not.
        for (card in listOf(TV_CARD, PHONE_CARD)) {
            val source = squash(source(card))
            assertTrue(
                "$card must keep its logo height: 22-32dp off the card's width",
                source.contains("val logoHeight = (maxWidth * 0.135f).coerceIn(22.dp, 32.dp)")
            )
            assertTrue(
                "$card must keep its 68% width cap - it now measures the ink",
                source.contains("val logoMaxWidth = maxWidth * 0.68f")
            )
        }
    }

    // ── source access, the pattern the card contract tests here use ───

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

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun squash(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    private companion object {
        const val TILE = 48

        /** 68% of a 210dp card, and 13.5% of it - the landscape card's slot. */
        const val RAIL_LOGO_WIDTH = 142.8f
        const val RAIL_LOGO_HEIGHT = 28.35f

        const val EPS = 1e-3f

        const val BRAND_MARK = "com/kennyb1201/kbstream/ui/components/BrandMarkImage.kt"
        const val TV_CARD = "com/kennyb1201/kbstream/ui/components/LandscapeCard.kt"
        const val PHONE_CARD =
            "com/kennyb1201/kbstream/ui/components/touch/TouchLandscapeCard.kt"
    }
}
