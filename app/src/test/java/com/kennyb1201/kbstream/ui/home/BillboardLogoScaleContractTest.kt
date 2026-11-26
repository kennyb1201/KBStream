package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-17: the TV Spotlight billboard's clearlogo is sized for the TV frame.
 *
 * The billboard is the shared `MobileHero`, and its wordmark was a fixed phone
 * box - 40dp tall, at most 220dp wide - on a frame that is 2.6x the phone's. On the
 * TV that is under a quarter of the frame's width and under half the TV hero's own
 * logo, which is what "real small" named. The fix is not a bigger number: it is the
 * TV hero's OWN box for the TV frame, so the two surfaces cannot drift again.
 *
 * F2/F3 are pinned as negatives as much as positives: the fallback path, the fit
 * rule and the phone box all have to stay exactly as they were.
 */
class BillboardLogoScaleContractTest {

    private val mobile by lazy {
        source("com/kennyb1201/kbstream/ui/mobile/HomeScreenMobile.kt")
    }
    private val tvHome by lazy { source("com/kennyb1201/kbstream/ui/home/HomeScreen.kt") }
    private val brandMark by lazy {
        source("com/kennyb1201/kbstream/ui/components/BrandMarkImage.kt")
    }

    /** The logo block inside [MobileHero]'s content column, branch by branch. */
    private fun logoBlock(): String =
        mobile.substringAfter("            if (logo != null && !logoFailed) {")
            .substringBefore("            Text(\n                text = heroMetaLine(meta),")

    // ── F1: one flag, two boxes ───────────────────────────────────────────

    @Test
    fun `the TV frame draws the wordmark in the TV hero's own box`() {
        val block = logoBlock()
        assertTrue(
            "the surface flag is heroHeight - the same one the rest of the page uses",
            block.contains("modifier = if (heroHeight != null) {")
        )
        assertTrue(
            "the TV box is the hero's box, not a new number",
            block.contains(".height(HeroLogoHeight)") &&
                block.contains(".widthIn(max = HeroLogoWidth)")
        )
        assertTrue(
            "and it is imported from the hero, so the two cannot drift",
            mobile.contains("import com.kennyb1201.kbstream.ui.home.HeroLogoHeight") &&
                mobile.contains("import com.kennyb1201.kbstream.ui.home.HeroLogoWidth")
        )
        assertTrue(
            "whose sizes are still the hero's 82 x 300",
            tvHome.contains("internal val HeroLogoWidth = 300.dp") &&
                tvHome.contains("internal val HeroLogoHeight = 82.dp")
        )
    }

    @Test
    fun `the phone frame keeps a box of its own`() {
        val block = logoBlock()
        assertTrue(
            "the phone billboard's logo is its own pair of numbers, not the TV " +
                "hero's box (48 x 260 since the taller 2026-11-25 frame; it was " +
                "40 x 220, which read small in the new height)",
            block.contains(".height(48.dp)") && block.contains(".widthIn(max = 260.dp)")
        )
        assertTrue(
            "and the phone is the else branch of that same flag",
            block.substringAfter("} else {").contains(".height(48.dp)")
        )
    }

    // ── F2: it still fits, and it still falls back ────────────────────────

    @Test
    fun `the mark still fits instead of cropping, and a missing one still says the title`() {
        assertTrue(
            "S4: fit, never crop - the shared component's rule is untouched",
            brandMark.contains("contentScale = ContentScale.Fit")
        )
        assertTrue(
            "a failed load is still reported by the same callback",
            logoBlock().contains("onUnusable = { logoFailed = true }")
        )
        assertTrue(
            "and the text title is still the else branch",
            mobile.contains("            } else {\n                Text(\n                    text = meta.name,")
        )
        assertFalse(
            "no fixed width, so a wide mark is bounded by the box rather than the slot",
            logoBlock().contains("width(HeroLogoWidth)")
        )
    }

    // ── F3: nothing else moves ────────────────────────────────────────────

    @Test
    fun `the other two logos are not part of this change`() {
        assertTrue(
            "the Modern hero still defaults to the hero box",
            tvHome.contains("heroLogoHeight: Dp = HeroLogoHeight")
        )
        assertTrue(
            "and the Spotlight panel's own box is still the hero's, by its own fix",
            source("com/kennyb1201/kbstream/ui/home/SpotlightPreview.kt")
                .contains(".height(HeroLogoHeight)")
        )
        assertFalse(
            "the chevrons keep their own band: no logo sizing leaked into them",
            mobile.substringAfter("private fun BillboardPageChevron(")
                .substringBefore("private fun BillboardAction(")
                .contains("HeroLogoHeight")
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
