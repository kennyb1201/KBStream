package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The season chip's ring rule (spec 2026-11-15, second attempt — F1 and F3).
 *
 * Two fixes have now been aimed at "the ring doesn't show". The first moved the
 * ring into the foreground; the verdict was that it still shows nowhere AND that
 * the pill is white, which this code cannot draw. The proven cause is the
 * platform: `View.draw()` paints the default focus highlight at step 7, AFTER
 * `onDrawForeground` (step 6), so the system's light rounded rect landed on the
 * fill and on the ring at once. That is pinned by source in
 * [SeasonChipFocusHighlightContractTest]; what this test settles is the OTHER
 * half of the old rule's failure — that its ring colour could not read on the
 * fills it was drawn on, no matter who painted what.
 *
 * The numbers below are the real palette's. Emerald (`0xFF35C56B`, the accent in
 * the field) against the old ring, `kb_text_hi`, is 2.0:1 — an outline that is
 * technically drawn and practically invisible, on the very chip Kenny reported.
 * Against the void it is 8.4:1. So the ring is chosen from its fill now, and the
 * rule is checked across every accent the palette offers rather than only the one
 * that was reported.
 */
class SeasonChipContrastTest {

    // The app's real tones, read from res/values/colors.xml rather than restated.
    private val void = color("kb_void")
    private val surface = color("kb_surface")
    private val raised = color("kb_surface_raised")
    private val textHi = color("kb_text_hi")

    /** The accents that matter: the default brass, Emerald, and the palette's light end. */
    private val brass = 0xFFE8A33D.toInt()
    private val emerald = 0xFF35C56B.toInt()
    private val lime = 0xFF8FD14A.toInt()
    private val chartreuse = 0xFFC2D64B.toInt()
    private val ice = 0xFF7FD3E8.toInt()

    @Test
    fun `the luminance and contrast math matches WCAG`() {
        assertEquals("black is 0", 0.0, SeasonChipContrast.relativeLuminance(0xFF000000.toInt()), 1e-9)
        assertEquals("white is 1", 1.0, SeasonChipContrast.relativeLuminance(0xFFFFFFFF.toInt()), 1e-9)
        assertEquals(
            "black on white is the 21:1 extreme",
            21.0,
            SeasonChipContrast.contrastRatio(0xFF000000.toInt(), 0xFFFFFFFF.toInt()),
            1e-6
        )
        assertEquals(
            "a colour against itself never contrasts",
            1.0,
            SeasonChipContrast.contrastRatio(emerald, emerald),
            1e-9
        )
    }

    @Test
    fun `the old white-on-accent ring could not read - which is why it is replaced`() {
        val emerald = emerald
        val whiteRing = SeasonChipContrast.contrastRatio(emerald, textHi)
        val darkRing = SeasonChipContrast.contrastRatio(emerald, void)

        assertTrue(
            "the ring the field had, on the accent the field had: $whiteRing:1",
            whiteRing < SeasonChipContrast.MIN_RING_CONTRAST
        )
        assertTrue(
            "the void reads on the same fill: $darkRing:1",
            darkRing >= 7.0
        )
        assertEquals(
            "so the ring on an accent-filled chip is the dark one",
            void,
            SeasonChipContrast.ringColor(emerald, emerald, void, textHi)
        )
    }

    @Test
    fun `the accent ring stays wherever it reads - the chip speaks the chrome's language`() {
        // The unselected chips: the dark surface tones, at every theme's black.
        listOf(surface, raised, 0xFF0D1117.toInt(), 0xFF06080B.toInt(), 0xFF050505.toInt())
            .forEach { fill ->
                assertEquals(
                    "an accent outline on a dark fill is the app's focus look",
                    emerald,
                    SeasonChipContrast.ringColor(fill, emerald, void, textHi)
                )
            }
    }

    @Test
    fun `whatever the fill, the chosen ring reaches the readability floor`() {
        // Every accent in the palette as a selected fill, on both the ordinary and
        // the AMOLED surface as an unselected one: the rule has to hold for the
        // colours a viewer can actually choose, not just the reported one.
        val accents = listOf(brass, emerald, lime, chartreuse, ice)
        val fills = accents.map { it } + listOf(surface, raised)
        fills.forEach { fill ->
            accents.forEach { accent ->
                val ring = SeasonChipContrast.ringColor(fill, accent, void, textHi)
                val ratio = SeasonChipContrast.contrastRatio(fill, ring)
                assertTrue(
                    "fill=#${Integer.toHexString(fill)} accent=#${Integer.toHexString(accent)} " +
                        "ring=#${Integer.toHexString(ring)} is $ratio:1",
                    ratio >= SeasonChipContrast.MIN_RING_CONTRAST
                )
            }
        }
    }

    @Test
    fun `a pale accent takes the dark ring instead of a near-white one`() {
        // Lime (0xFF8FD14A) is the palette's light end: near-white on it is the
        // same invisible outline Emerald's white ring was.
        assertTrue(SeasonChipContrast.isLight(lime))
        assertEquals(void, SeasonChipContrast.ringColor(lime, lime, void, textHi))
        assertEquals(
            "and the light ring is what a dark fill gets",
            emerald,
            SeasonChipContrast.ringColor(raised, emerald, void, textHi)
        )
    }

    @Test
    fun `the app's palette has no white - the white pill was never this code's fill`() {
        // The diagnosis, in one assertion: whatever accent is stored, the fill can
        // only ever be one of the palette's colours (or the default), and none of
        // them is white. So a white pill has to come from something that is not
        // this code - the platform's focus highlight, which the chip now disables.
        val palette = paletteColors()
        assertTrue("the palette was read", palette.size >= 20)
        palette.forEach { accent ->
            assertTrue(
                "no palette accent is white: #${Integer.toHexString(accent)}",
                SeasonChipContrast.relativeLuminance(accent) < 0.95
            )
        }
        assertTrue(
            "and the near-white token is the ring's, not a fill",
            SeasonChipContrast.relativeLuminance(textHi) > 0.8
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private fun color(name: String): Int {
        val xml = read("res/values/colors.xml")
        val line = xml.lineSequence().firstOrNull { it.contains("name=\"$name\"") }
            ?: throw AssertionError("colors.xml has no $name")
        val hex = line.substringAfter(">").substringBefore("<").trim()
        return java.lang.Long.decode(hex.replace("#", "0x")).toInt()
    }

    /** The accent palette's colours, read from Theme.kt so they cannot drift. */
    private fun paletteColors(): List<Int> {
        val theme = read("ui/theme/Theme.kt")
        val block = theme.substringAfter("val KBAccentPalette: List<KBAccentColor> = listOf(")
            .substringBefore("\n)")
        return Regex("Color\\(0x([0-9A-Fa-f]{8})\\)")
            .findAll(block)
            .map { it.groupValues[1].toLong(16).toInt() }
            .toList()
    }

    private fun read(relative: String): String {
        val prefixes = listOf("", "app/src/main/java/com/kennyb1201/kbstream/", "app/src/main/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "$prefix$relative")
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("source missing: $relative")
    }
}
