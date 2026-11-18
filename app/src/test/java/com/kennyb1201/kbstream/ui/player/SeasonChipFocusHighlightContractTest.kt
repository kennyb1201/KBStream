package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Why the second ring fix is about the PLATFORM, not about the fill (spec
 * 2026-11-15, second attempt — F1, F2, F4).
 *
 * The field verdict on the first fix: the pill is white and no outline shows on
 * any chip. Neither is something this code can draw — the palette has no white,
 * the stored accent resolves through a palette with no white entry, the fills are
 * the accent or the two dark tones, and the theme walk already skips the chip. So
 * F1's answer is the framework: a focusable view whose focus indicator the system
 * does not recognise gets `drawDefaultFocusHighlight`, and `View.draw()` runs that
 * at step 7, after `onDrawForeground` (step 6) — the step a foreground ring lives
 * in. One system rectangle, over the fill and over the ring, explains both
 * symptoms without a single line of this app's colour code being wrong.
 *
 * What is pinned here: the highlight is off in both places it can be turned on,
 * the fills are still what F2 says, the ring is still chosen from the fill, the
 * ring is set in the same function as the fill on bind AND on focus change (so a
 * season-switch rebind cannot drop it), and both engines still share one strip.
 */
class SeasonChipFocusHighlightContractTest {

    @Test
    fun `the platform's own focus highlight is off on the chip`() {
        val layout = read("app/src/main/res/layout/picker_season_chip.xml")
        assertTrue(
            "the layout turns it off - it defaults to TRUE on API 26+ and is " +
                "painted after the foreground",
            layout.contains("android:defaultFocusHighlightEnabled=\"false\"")
        )
        val adapter = read("ui/player/SeasonStripAdapter.kt")
        assertTrue(
            "and the adapter turns it off again on every chip it creates, so a " +
                "layout edit cannot bring the white pill back",
            adapter.contains(".setDefaultFocusHighlightEnabled(false)")
        )
    }

    @Test
    fun `the fills are the accent, the raised tone and the surface - in that order`() {
        val style = read("ui/player/SeasonChipStyle.kt")
        val fills = style.substringAfter("fun fillFor(").substringBefore("*/ }").take(700)
        assertTrue(
            "selected is the themed accent, never a resolved XML colour",
            fills.contains("selected -> themeAccentColor(context)")
        )
        assertTrue("focused-unselected is the raised tone", fills.contains("focused -> themedTone(context, R.color.kb_surface_raised)"))
        assertTrue("unfocused-unselected is the surface", fills.contains("else -> themedTone(context, R.color.kb_surface)"))
        assertFalse(
            "and the accent is not read from the XML resource the field's 317 did",
            style.contains("accent = ContextCompat.getColor(context, R.color.kb_accent)")
        )
    }

    @Test
    fun `the ring is chosen from its fill, not from the chip's state`() {
        val style = read("ui/player/SeasonChipStyle.kt")
        assertTrue(
            "the contrast rule decides",
            style.contains("SeasonChipContrast.ringColor(")
        )
        assertFalse(
            "the old state rule (near-white whenever selected) is gone",
            style.contains("if (selected) {\n            ContextCompat.getColor(context, R.color.kb_text_hi)")
        )
        assertTrue(
            "and the background stroke is the same colour as the foreground one - " +
                "they coincide, so neither reads as a second outline",
            style.contains("setStroke((RING_DP * density).toInt().coerceAtLeast(1), ringFor(context, fill))")
        )
    }

    @Test
    fun `the ring is set with the fill on every bind and every focus change`() {
        val adapter = read("ui/player/SeasonStripAdapter.kt")
        val restyle = adapter.substringAfter("fun restyle(focused: Boolean) {").substringBefore("restyle(chip.isFocused)")
        assertTrue("the fill is applied there", restyle.contains("SeasonChipStyle.apply("))
        assertTrue(
            "and the ring in the SAME function, so a rebind cannot keep one " +
                "without the other",
            restyle.contains("chip.foreground = if (dpad) SeasonChipStyle.ring(")
        )
        assertTrue(
            "the bind runs it",
            adapter.contains("restyle(chip.isFocused)")
        )
        assertTrue(
            "and a focus change runs it again (and, on a gain, reveals the chip - " +
                "spec 2026-11-17, F3)",
            adapter.contains("chip.setOnFocusChangeListener { _, focused ->") &&
                adapter.contains("restyle(focused)")
        )
        assertTrue(
            "THE RING IS THE D-PAD'S (the F1(e) rule): a touch keeps the chip " +
                "focused but must not leave a ring behind",
            restyle.contains("val dpad = focused && !chip.isInTouchMode")
        )
        assertTrue(
            "a season switch rebinds the SAME adapter, so focus (and its ring) " +
                "survives the switch",
            adapter.contains("notifyDataSetChanged()") && !adapter.contains("newAdapter")
        )
    }

    @Test
    fun `the theme walk still cannot reach the chip's state`() {
        assertTrue(
            "the walk skips the chip outright",
            read("ui/player/PlayerChromeTheme.kt").contains("if (view.id == R.id.picker_season_chip) return")
        )
    }

    @Test
    fun `both engines still draw the strip from one adapter`() {
        listOf("ui/player/MpvPlayerActivity.kt", "ui/player/NativePlayerActivity.kt").forEach { engine ->
            assertTrue(
                "$engine builds the shared strip",
                read(engine).contains("seasonStripAdapter = SeasonStripAdapter(items)")
            )
        }
        listOf("ui/player/MpvPlayerActivity.kt", "ui/player/NativePlayerActivity.kt").forEach { engine ->
            assertFalse(
                "neither engine styles or rings a chip itself - one strip, one fix",
                read(engine).contains("chip.foreground") ||
                    read(engine).contains("SeasonChipStyle.apply(")
            )
        }
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
