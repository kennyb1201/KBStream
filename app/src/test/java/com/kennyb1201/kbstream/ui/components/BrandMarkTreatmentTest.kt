package com.kennyb1201.kbstream.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The header's brand-mark classifier: which logos it draws, how, and which it
 * refuses.
 *
 * The case worth pinning is the knockout plate. TMDB ships Bravo, CMT, FXX and
 * BET ONLY as a solid dark plate with the wordmark cut out of it (transparent
 * letters inside the mark), and the classifier used to call every dark
 * high-coverage plate unreadable - so those four networks showed no logo at
 * all next to their name. A plate with lettering knocked out of it whitens
 * into exactly the mark it is supposed to be: light plate, dark lettering.
 * A plate with nothing knocked out of it (TNT's, E!'s) still has to be
 * refused, which is what [hasKnockoutDetail] separates.
 *
 * Every geometry below mirrors a measured TMDB row (2026-09): Bravo 1200x914
 * at 0.71 coverage with 0.085 of the tile enclosed, FXX 0.032, CMT 0.146, BET
 * 0.406, against 0.000 for the featureless plates and 0.80 coverage with a
 * spread of 0.89 for ABC's opaque-lettering disc.
 */
class BrandMarkTreatmentTest {

    private val opaqueBlack = 0xFF000000.toInt()
    private val opaqueWhite = 0xFFFFFFFF.toInt()
    private val netflixRed = 0xFFE50914.toInt()
    private val clear = 0x00000000

    private fun filled(color: Int) = IntArray(TILE * TILE) { color }

    private fun IntArray.rect(x0: Int, y0: Int, w: Int, h: Int, color: Int) {
        for (y in y0 until y0 + h) {
            for (x in x0 until x0 + w) this[y * TILE + x] = color
        }
    }

    /** A solid plate with the brand name knocked out of it. */
    private fun knockoutPlate() = filled(opaqueBlack).apply { rect(6, 18, 36, 10, clear) }

    private fun classify(pixels: IntArray) = brandMarkTreatment(pixels, TILE, TILE)

    // ── the regression: a knocked-out plate is drawable ───────────────

    @Test
    fun `a dark plate with knockout lettering whitens instead of hiding`() {
        assertEquals(BrandMark.WHITEN, classify(knockoutPlate()))
    }

    @Test
    fun `a featureless dark plate is still refused`() {
        assertEquals(BrandMark.UNUSABLE, classify(filled(opaqueBlack)))
    }

    @Test
    fun `a one-pixel stub is refused`() {
        // TNT's network logo is a single 1x1 pixel; even if a size filter ever
        // let it through, it can never be a mark.
        assertEquals(BrandMark.UNUSABLE, brandMarkTreatment(IntArray(1) { opaqueBlack }, 1, 1))
    }

    @Test
    fun `artwork that never arrives draws nothing`() {
        assertEquals(BrandMark.UNUSABLE, classify(filled(clear)))
    }

    // ── the cases that already worked, unchanged ──────────────────────

    @Test
    fun `a dark glyph on transparency whitens`() {
        assertEquals(BrandMark.WHITEN, classify(filled(clear).apply { rect(20, 10, 8, 28, opaqueBlack) }))
    }

    @Test
    fun `a dark plate with opaque light lettering still whitens`() {
        // ABC's top-voted disc: a filled plate whose letters are painted, not
        // knocked out, so the coverage test must not swallow it into UNUSABLE.
        val disc = filled(opaqueBlack).apply { rect(12, 20, 24, 6, opaqueWhite) }
        assertEquals(BrandMark.WHITEN, classify(disc))
    }

    @Test
    fun `a colored mark is drawn as it is`() {
        assertEquals(BrandMark.AS_IS, classify(filled(netflixRed)))
    }

    @Test
    fun `a light, colorful wordmark is drawn as it is`() {
        val wordmark = filled(clear).apply { rect(2, 20, 44, 10, netflixRed) }
        assertEquals(BrandMark.AS_IS, classify(wordmark))
    }

    @Test
    fun `an empty or degenerate sample is left alone`() {
        assertEquals(BrandMark.AS_IS, brandMarkTreatment(IntArray(0), 0, 0))
        assertEquals(BrandMark.AS_IS, brandMarkTreatment(IntArray(4), 0, 0))
    }

    // ── the knockout detector itself ─────────────────────────────────

    @Test
    fun `enclosed transparency counts as knockout detail`() {
        assertTrue(hasKnockoutDetail(knockoutPlate(), TILE, TILE))
    }

    @Test
    fun `transparency touching the border is not knockout detail`() {
        // A letter sitting in the padding a logo is drawn with: everything
        // transparent can be reached from the edge.
        val glyph = filled(opaqueBlack).apply { rect(0, 0, TILE, 12, clear) }
        assertFalse(hasKnockoutDetail(glyph, TILE, TILE))
    }

    @Test
    fun `a fully opaque tile has no knockout detail`() {
        assertFalse(hasKnockoutDetail(filled(opaqueBlack), TILE, TILE))
    }

    @Test
    fun `a tile too small to hold a hole is never knockout`() {
        assertFalse(hasKnockoutDetail(IntArray(4) { opaqueBlack }, 2, 2))
        assertFalse(hasKnockoutDetail(IntArray(1) { opaqueBlack }, 1, 1))
    }

    private companion object {
        const val TILE = 48
    }
}
