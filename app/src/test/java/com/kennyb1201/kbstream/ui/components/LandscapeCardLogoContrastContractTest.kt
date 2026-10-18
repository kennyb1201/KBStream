package com.kennyb1201.kbstream.ui.components

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The corner clearlogo on a landscape card has to be READABLE on the card.
 *
 * Field report: "some of the corner clearlogos on landscape cards are black and
 * hard to see". The card draws a backdrop, a bottom scrim and then a small
 * clearlogo in the bottom-left - and a clearlogo is a wordmark on transparency,
 * often drawn in black ink for a light listing page. Dropped onto a near-black
 * corner, its ink was the same colour as the surface behind it.
 *
 * The treatment already existed for the same failure on network marks (see
 * [brandMarkTreatment]): sample the decoded mark once, whiten a dark, colorless
 * one with a SrcIn tint, and leave a colored or already-light mark exactly as
 * the artist drew it. This pins that the card goes THROUGH it, and that a mark
 * it cannot draw at all still hands the corner back to the title.
 *
 * Read from the source, the way the other card contract tests here are: a
 * Composable draws to a device surface, and what is being checked is which
 * component the card chose, not a pixel.
 */
class LandscapeCardLogoContrastContractTest {

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

    // ── The pure rule, on the reported mark ───────────────────────────────

    @Test
    fun `a black wordmark is whitened rather than drawn as it is`() {
        // A black glyph on transparent padding, which is what a clearlogo from
        // a light listing page is: colorless, dark, and covering a fraction of
        // the tile. This is the exact shape the report calls unreadable.
        val width = 48
        val height = 48
        val pixels = IntArray(width * height)
        for (y in 16 until 32) {
            for (x in 8 until 40) {
                pixels[y * width + x] = 0xFF000000.toInt()
            }
        }

        assertEquals(BrandMark.WHITEN, brandMarkTreatment(pixels, width, height))
    }

    @Test
    fun `a light wordmark is left exactly as drawn`() {
        val width = 48
        val height = 48
        val pixels = IntArray(width * height)
        for (y in 16 until 32) {
            for (x in 8 until 40) {
                pixels[y * width + x] = 0xFFFFFFFF.toInt()
            }
        }

        assertEquals(BrandMark.AS_IS, brandMarkTreatment(pixels, width, height))
    }

    // ── The wiring ────────────────────────────────────────────────────────

    @Test
    fun `the corner clearlogo is drawn through the brand-mark treatment`() {
        val card = squash(source(LANDSCAPE))
        assertTrue(
            "the card's corner logo must be a BrandMarkImage - a plain AsyncImage " +
                "is what drew a black wordmark invisibly on the card's dark corner",
            card.contains("BrandMarkImage( url = logo,")
        )
        assertFalse(
            "and it must not go back to an untreated image request",
            card.contains("model = remember(logoUrl) {")
        )
    }

    @Test
    fun `a mark that cannot be drawn hands the corner back to the title`() {
        val card = squash(source(LANDSCAPE))
        assertTrue(
            "an unusable mark (a blank plate, artwork that never arrives) must fall " +
                "back to the corner title instead of an empty slot",
            card.contains("onUnusable = { logoFailed = true }")
        )
        assertTrue(
            "a painted mark hands the corner over to the art",
            card.contains("onDrawn = { logoPainted = true }")
        )
        assertTrue(
            "which is the state the corner title already keys on",
            card.contains("val showTitle = !showLogo || !logoPainted")
        )
    }

    @Test
    fun `only a dark mark is whitened`() {
        val mark = squash(source(BRAND_MARK))
        assertTrue(
            "the whitening is a SrcIn tint, which keeps the mark's own alpha",
            mark.contains("ColorFilter.tint(Color.White, BlendMode.SrcIn)")
        )
        assertTrue(
            "and it applies to the WHITEN verdict alone, so a colored mark " +
                "(a studio's red, a service's blue) is drawn untouched",
            mark.contains("if (treatment == BrandMark.WHITEN) {")
        )
        assertTrue(
            "the verdict needs the decoded pixels, so the request must stay off " +
                "the hardware path",
            mark.contains(".allowHardware(false)")
        )
    }

    private companion object {
        const val LANDSCAPE = "com/kennyb1201/kbstream/ui/components/LandscapeCard.kt"
        const val BRAND_MARK = "com/kennyb1201/kbstream/ui/components/BrandMarkImage.kt"
    }
}
