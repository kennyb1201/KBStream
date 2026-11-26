package com.kennyb1201.kbstream.ui.theme

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The card's focus lift, halved (spec 2026-11-26).
 *
 * A rail walk runs two scale animations per D-pad step - the card being left
 * shrinking, the one being entered growing - concurrently with the scroll. The
 * spec's one number takes the card from 1.03 to 1.015 and changes nothing else:
 * the 2dp ring is the cue at rest and does not move, the glow stays settle-gated
 * at 220 ms, and the 24dp headroom under the artwork is untouched (a smaller
 * lift simply leaves it more generous).
 *
 * A source-reading test, like its neighbours: `KBFocusCard` lives in a Compose
 * theme file and the glow is a `LaunchedEffect`, so neither can be imported or
 * run in this module's unit tests. The glow test passes with and without this
 * change - it is here as the guard that the out-of-scope half did not regress.
 */
class FocusScaleCardLiftContractTest {

    private val theme by lazy { source("com/kennyb1201/kbstream/ui/theme/Theme.kt") }
    private val card by lazy { source("com/kennyb1201/kbstream/ui/components/KBCard.kt") }
    private val home by lazy { source("com/kennyb1201/kbstream/ui/home/HomeScreen.kt") }

    /** A `const val` from the theme, or a failed assertion naming it. */
    private fun scale(name: String): Float {
        val match = Regex("const val $name = ([0-9.]+)f").find(theme)
        assertTrue("$name is missing from Theme.kt", match != null)
        return match!!.groupValues[1].toFloat()
    }

    /** [KBCard]'s glow gate: the effect and the state it writes. */
    private fun glowGate(): String =
        card.substringAfter("LaunchedEffect(focused) {")
            .substringBefore("// The Card below recomposes")

    // ── the one number ─────────────────────────────────────────────────────

    @Test
    fun `the card's focused scale is 1_015 and it is still what KBCard defaults to`() {
        assertTrue(
            "the constant itself carries the new step, at the same place in the file",
            theme.contains("const val KBFocusCard = 1.015f")
        )
        assertEquals(
            "and it is read as 1.5% - half the 3% lift a walk used to run twice a step",
            1.015f,
            scale("KBFocusCard"),
            0f
        )
        assertTrue(
            "a card that does not name its own scale takes this one",
            card.contains("focusedScale: Float = KBFocusCard")
        )
    }

    // ── the surface-class order ────────────────────────────────────────────

    @Test
    fun `every surface class keeps its own step, and the card now sits just under the row`() {
        val none = scale("KBFocusNone")
        val row = scale("KBFocusRow")
        val cardScale = scale("KBFocusCard")
        val button = scale("KBFocusButton")
        val chip = scale("KBFocusChip")
        val tile = scale("KBFocusTile")

        assertEquals("nothing scales at rest", 1f, none, 0f)
        assertTrue("the card still lifts on focus: $cardScale", cardScale > none)
        assertTrue(
            "and every surface above it still steps further: $cardScale < $button < $chip < $tile",
            cardScale < button && button < chip && chip < tile
        )
        assertEquals("the row's step is untouched", 1.02f, row, 0f)
        assertEquals("the button's step is untouched", 1.04f, button, 0f)
        assertEquals("the chip's step is untouched", 1.06f, chip, 0f)
        assertEquals("the tile's step is untouched", 1.08f, tile, 0f)

        // The spec asked for `row < card` to survive, but 1.015 is BELOW the
        // row's 1.02 - the two claims cannot both hold. The number is the point
        // of the spec and is stated three times, so it wins; what is pinned
        // here is the order that actually ships: the card is the closest step
        // to the row's, just under it, and the ladder runs upward from there.
        assertTrue(
            "the card is the ladder's second-smallest step, 0.005 under the row: " +
                "$cardScale vs $row",
            cardScale < row && row - cardScale < 0.01f
        )
        assertTrue(
            "and the theme's own ordering line says so, instead of claiming the old pair",
            theme.contains("//   card (1.015) < row (1.02) < button (1.04) < chip (1.06) < tile (1.08)")
        )
    }

    // ── what this change must NOT have moved ───────────────────────────────

    @Test
    fun `the ring and the headroom the smaller lift sits inside are untouched`() {
        assertTrue(
            "the 2dp accent border is the cue at rest - the smaller lift leans on it, " +
                "so it may not shrink with the lift",
            card.contains("Border(border = BorderStroke(2.dp, KBAccent), shape = shape)")
        )
        assertTrue(
            "the box under the artwork still reserves the lift plus a 12dp glow: a " +
                "smaller lift only leaves that room more generous, and nothing moves",
            theme.contains("val KBFocusGlow = 12.dp") &&
                home.contains("private val PosterFocusHeadroom = 24.dp")
        )
    }

    @Test
    fun `a card that gains and loses focus inside the settle never arms a glow`() {
        val gate = glowGate()
        assertTrue("the glow gate is there", gate.isNotEmpty())
        assertTrue(
            "220 ms is still the settle",
            card.contains("private const val FOCUS_GLOW_SETTLE_MS = 220L")
        )
        assertTrue(
            "the effect is keyed on `focused`, so losing focus CANCELS the pending arm " +
                "rather than letting it complete on an unfocused card",
            card.contains("LaunchedEffect(focused) {")
        )
        assertTrue(
            "blurring disarms first and returns before the delay is ever scheduled",
            gate.contains("if (!focused) {") &&
                gate.contains("glowArmed = false") &&
                gate.contains("return@LaunchedEffect") &&
                gate.indexOf("glowArmed = false") < gate.indexOf("delay(FOCUS_GLOW_SETTLE_MS)")
        )
        assertTrue(
            "only a focus that survives the full settle arms it, once",
            gate.indexOf("delay(FOCUS_GLOW_SETTLE_MS)") < gate.indexOf("glowArmed = true") &&
                1 == Regex("glowArmed = true").findAll(gate).count()
        )
        assertTrue(
            "and an unarmed glow is ZERO elevation, not a cheaper shadow - at 4K the " +
                "blur is what the walk was paying for",
            card.contains("elevation = if (glowArmed && !gliding) KBFocusGlow else 0.dp")
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun source(relative: String): String {
        val file = File(mainSourceRoot(), relative)
        assertTrue("source missing: ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    private fun mainSourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("main source root not found")
    }
}
