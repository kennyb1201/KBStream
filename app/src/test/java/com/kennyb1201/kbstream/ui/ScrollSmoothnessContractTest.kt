package com.kennyb1201.kbstream.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The browse-UI scroll-smoothness fixes, pinned where a device cannot be.
 *
 * The audit behind these found that the animations were never the problem -
 * card/row/chip focus already runs on the GPU. What cost the choppiness was
 * what fired per D-pad step and how big the images were, and every one of those
 * is a wiring decision that compiles cleanly whether or not it is right:
 *
 *  - the hero META resolve ran once per card crossed (a cancel/relaunch plus
 *    several StateFlow writes and a fresh image request) while only the trailer
 *    dwell is visible to the viewer;
 *  - the hero backdrop decoded at 1920x1080 (8 MB ARGB_8888) for a hero that
 *    draws at ~1150x520;
 *  - the skeleton pulse was read in composition, recomposing every placeholder
 *    at ~60fps through the whole loading window;
 *  - each poster tile rebuilt its ImageRequest on every composition;
 *  - the two main lazy columns gave Compose no content types, so it could not
 *    reuse a slot between items of different kinds.
 *
 * Read from the source the way the other screen contract tests do
 * (HandoffFilingContractTest): a TV has no JVM-renderable equivalent, so what is
 * pinned is the shape and the order.
 */
class ScrollSmoothnessContractTest {

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

    private fun read(relative: String): String {
        val file = File(sourceRoot, relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    @Test
    fun `the hero resolve is debounced, and the title is not`() {
        val home = read(HOME)
        val debounce = home.indexOf("delay(HERO_RESOLVE_DEBOUNCE_MS)")
        val resolve = home.indexOf("onResolveHeroMeta(it)")
        assertTrue("the hero effect must still resolve meta", resolve > 0)
        assertTrue(
            "the resolve must wait out a debounce first, or a held D-pad runs " +
                "one cancel/relaunch/state-write cycle per card crossed",
            debounce > 0 && debounce < resolve
        )
        assertTrue(
            "the debounce must be small (the audit's 120-180ms window)",
            Regex("HERO_RESOLVE_DEBOUNCE_MS = 1[2-8][0-9]L").containsMatchIn(home)
        )
    }

    @Test
    fun `the hero backdrop decodes at its render size, not 1080p`() {
        val home = read(HOME)
        assertTrue(
            "the hero backdrop must decode at 1280x720: at 1920x1080 it was 8 MB " +
                "of ARGB_8888 for a hero that draws at ~1150x520",
            home.contains(".size(Size(1280, 720))")
        )
        assertFalse(
            "the 1080p decode must not come back",
            home.contains(".size(Size(1920, 1080))")
        )
    }

    @Test
    fun `the skeleton pulse is read in the draw phase, not in composition`() {
        val skeleton = read(SKELETON)
        assertTrue(
            "the pulse must be a State, so callers can defer the read",
            skeleton.contains("private fun rememberSkeletonAlpha(): State<Float>")
        )
        assertTrue(
            "the tile must apply the pulse in graphicsLayer, which skips " +
                "recomposition",
            skeleton.contains(".graphicsLayer { this.alpha = alpha.value }")
        )
        assertFalse(
            "the pulse must not be applied as a per-composition background alpha",
            skeleton.contains(".background(KBSurfaceRaised.copy(alpha = alpha), shape)")
        )
    }

    @Test
    fun `tiles remember their image request instead of rebuilding it per composition`() {
        val poster = read(POSTER)
        assertTrue(
            "PosterCard must remember its ImageRequest keyed on the poster URL",
            poster.contains("model = remember(posterUrl) {")
        )
        val landscape = read(LANDSCAPE)
        assertTrue(
            "LandscapeCard's backdrop request must be remembered",
            landscape.contains("model = remember(effectiveUrl) {")
        )
        assertTrue(
            "LandscapeCard's logo request must be remembered",
            landscape.contains("model = remember(logoUrl) {")
        )
    }

    @Test
    fun `the main lazy columns declare content types`() {
        val home = read(HOME)
        assertTrue(
            "Home's spacer must carry its own content type",
            home.contains("item(key = \"hero_spacer\", contentType = \"spacer\")")
        )
        assertTrue(
            "Home's rail list must type each entry kind, so a reorder can reuse " +
                "a composition slot",
            home.contains("contentType = { _, entry ->")
        )
        val detail = read(DETAIL)
        assertTrue(
            "Detail's rows and headers must be typed too",
            detail.contains("item(key = \"infoblock\", contentType = \"infoblock\")")
        )
        assertTrue(
            "Detail's section rows must share a type",
            detail.contains("item(key = \"peoplerow\", contentType = \"section-row\")")
        )
    }

    private companion object {
        const val HOME = "com/kennyb1201/kbstream/ui/home/HomeScreen.kt"
        const val DETAIL = "com/kennyb1201/kbstream/ui/detail/DetailScreen.kt"
        const val SKELETON = "com/kennyb1201/kbstream/ui/components/KBSkeleton.kt"
        const val POSTER = "com/kennyb1201/kbstream/ui/components/PosterCard.kt"
        const val LANDSCAPE = "com/kennyb1201/kbstream/ui/components/LandscapeCard.kt"
    }
}
