package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The billboard is taller on both surfaces (spec 2026-11-25).
 *
 * Kenny's ask was the hero reading as the page's subject rather than a strip
 * above the rails: "a little bigger taller and a little more immersive", on the
 * television and the phone. The frame was already full-bleed horizontally, so
 * the whole change is height - three numbers and the phone's wordmark, which
 * would read small if it stayed at the old size in a taller frame.
 *
 * The suite has no Compose runtime, so the TV's height is checked as the
 * arithmetic it is: the fraction the one helper multiplies by the viewport, on
 * the TCL's 540dp screen the commission was measured on. The phone's frame is
 * pinned where it is written, because a 16:11 `aspectRatio` is the whole rule.
 * [com.kennyb1201.kbstream.ui.mobile.MobileAdaptiveRulesTest] covers the
 * above-Compact cap behaviourally, with real window sizes; the expression pin
 * here is the belt to that braces.
 */
class BillboardHeightContractTest {

    private val spotlight: String by lazy { read("ui/home/SpotlightHome.kt") }
    private val mobileHome: String by lazy { read("ui/mobile/HomeScreenMobile.kt") }
    private val windowSize: String by lazy { read("ui/mobile/MobileWindowSize.kt") }

    @Test
    fun `the television hero is 60 percent of the viewport`() {
        val fraction = Regex("SPOTLIGHT_HERO_FRACTION = ([0-9.]+)f").find(spotlight)
        assertTrue("the hero's fraction must be a named constant", fraction != null)
        val share = fraction!!.groupValues[1].toFloat()
        assertEquals(
            "60% is the value now - the old spec's ceiling - not a value under it",
            0.60f,
            share,
            0.0001f
        )
        assertEquals(
            "so on the 540dp TCL the hero is 324dp tall, the height the commission " +
                "asked for",
            324,
            (540 * share).toInt()
        )
        assertTrue(
            "and it is still one helper the item, the placeholder and the billboard " +
                "all read, so the three cannot disagree",
            spotlight.contains("(screenHeightDp * SPOTLIGHT_HERO_FRACTION).dp")
        )
    }

    @Test
    fun `the phone billboard is a 16 by 11 frame`() {
        assertTrue(
            "the width-derived frame grew by one row of eleven: 16:11 is ~268dp on a " +
                "390dp phone against 16:10's ~244dp",
            mobileHome.contains("aspectRatio(16f / 11f)")
        )
        assertFalse(
            "and the old 16:10 rule is gone from the billboard, not kept beside it",
            mobileHome.contains("aspectRatio(16f / 10f)")
        )
        assertTrue(
            "a window wide enough for the cap keeps its own height instead of the " +
                "aspect ratio",
            mobileHome.contains("heroHeight = billboardHeightDp.takeIf { it > 0 }?.dp,")
        )
    }

    @Test
    fun `above compact the cap is half the window's height`() {
        assertTrue(
            "the cap is a named share of the window, not a number in the minOf",
            windowSize.contains("val halfTheWindow = (heightDp * 50 / 100)")
        )
        assertTrue(
            "and it is still the smaller of the width slice and that share - a rail " +
                "has to stay on screen",
            windowSize.contains("return minOf(sixteenByTen, halfTheWindow)")
        )
        assertTrue(
            "Compact still opts out, so the phone's own frame rule is the only one " +
                "it uses",
            windowSize.contains("if (widthClass == MobileWidthClass.Compact) return 0")
        )
    }

    @Test
    fun `the phone wordmark grew with the frame, and the television's did not`() {
        assertTrue(
            "a 40dp mark in a 268dp billboard reads small: the phone's box is 48dp " +
                "tall, 260dp wide",
            mobileHome.contains(".height(48.dp)") &&
                mobileHome.contains(".widthIn(max = 260.dp)")
        )
        assertTrue(
            "the TV keeps the hero's own logo box, unchanged - the two surfaces keep " +
                "their own scale",
            mobileHome.contains(".height(HeroLogoHeight)") &&
                mobileHome.contains(".widthIn(max = HeroLogoWidth)")
        )
    }

    private fun read(relative: String): String {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate =
                    File(dir, "${prefix}src/main/java/com/kennyb1201/kbstream/$relative")
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("source missing: $relative")
    }
}
