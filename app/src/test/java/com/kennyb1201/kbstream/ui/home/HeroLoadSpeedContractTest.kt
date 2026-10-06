package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How fast the Home hero and its landscape rail cards get their art on screen.
 *
 * This is a wiring contract, not a behaviour one: what it pins is the SHAPE of
 * the paths that decide when a viewer sees a backdrop, a clearlogo or a rail
 * card's art, because each of them has a faster and a slower spelling that look
 * equally correct in review and differ by a network round trip on screen.
 *
 *  - The hero's TMDB detail is published the moment its own leg answers rather
 *    than behind the add-on meta leg (the slow one), so the info line, the
 *    status tag, the season count, the synopsis and the cast are not all held
 *    hostage to the add-on.
 *  - The trailer key comes from one rule, [heroTrailerKeyOf], used by both
 *    publishes - two inline copies could drift.
 *  - The prefetch warms the hero art's image BYTES, not just its URLs, and
 *    does that outside the TMDB prefetch's permit so the URL warms queued
 *    behind it are not pushed back.
 *  - A landscape rail card never draws a hole: with no resolved art and no
 *    add-on background it falls back to the item's poster.
 */
class HeroLoadSpeedContractTest {

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

    private companion object {
        const val VIEW_MODEL = "com/kennyb1201/kbstream/ui/home/HomeViewModel.kt"
        const val SCREEN = "com/kennyb1201/kbstream/ui/home/HomeScreen.kt"
        const val PREWARM = "com/kennyb1201/kbstream/ui/home/HeroPrewarm.kt"
    }

    @Test
    fun `the hero info is published before the add-on meta leg is awaited`() {
        val vm = source(VIEW_MODEL)
        val addonAwait = vm.indexOf("val resolvedAddonMeta = addonMetaDeferred.await()")
        assertTrue("the add-on meta leg must still be awaited", addonAwait > 0)

        val beforeAddon = vm.substring(0, addonAwait)
        assertTrue(
            "the hero must publish its TMDB detail as soon as that leg answers",
            beforeAddon.contains("_heroTmdbDetail.value = earlyDetail")
        )
        assertTrue(
            "the TMDB detail leg must be awaited to do that",
            beforeAddon.contains("val earlyDetail = tmdbDetailDeferred.await()")
        )
        // The composite meta still lands later with the add-on's own fields.
        assertTrue(
            "the resolved meta must still be published from the awaited legs",
            vm.substring(addonAwait).contains("_heroMeta.value = finalMeta")
        )
    }

    @Test
    fun `the trailer key comes from one rule used by both publishes`() {
        val vm = source(VIEW_MODEL)
        assertFalse(
            "the hero's trailer rule must not be spelled inline twice",
            vm.contains("video.type.equals(\"Trailer\"")
        )
        assertTrue(
            "both publishes must go through heroTrailerKeyOf",
            vm.split("heroTrailerKeyOf(").size - 1 >= 2
        )
        assertTrue(
            "the shared rule lives in HeroPrewarm.kt",
            source(PREWARM).contains("internal fun heroTrailerKeyOf(")
        )
    }

    @Test
    fun `the prefetch warms the hero art images outside the TMDB permit`() {
        val vm = source(VIEW_MODEL)
        val artworkStart = vm.indexOf("val artwork =")
        val warmIndex = vm.indexOf("warmHeroArtImages(artwork)")
        assertTrue("the prefetch must hold the resolved artwork", artworkStart >= 0)
        assertTrue("the prefetch must warm the art images", warmIndex > artworkStart)
        assertEquals(
            "the art warm is called once, from the prefetch",
            1,
            vm.split("warmHeroArtImages(artwork)").size - 1
        )

        // The permit block ends with its own `finally`; a warm written before
        // that finally is holding a prefetch permit across Coil's network work.
        val between = vm.substring(artworkStart, warmIndex)
        assertTrue(
            "the art warm must run outside the TMDB prefetch permit",
            between.contains("} finally {")
        )
        assertTrue(
            "the warm is bounded per build",
            vm.contains("if (index < HERO_ART_IMAGE_WARM_LIMIT) {")
        )
        assertTrue(
            "the warm's targets come from the shared rule",
            source(PREWARM).contains("internal fun heroArtWarmTargets(") &&
                vm.contains("heroArtWarmTargets(artwork)")
        )
        assertTrue(
            "the warm must not keep its thumbnails in the memory cache",
            vm.contains(".memoryCachePolicy(coil3.request.CachePolicy.DISABLED)")
        )
    }

    @Test
    fun `a landscape rail card never draws a hole`() {
        val screen = source(SCREEN)
        val start = screen.indexOf("backdropUrl = art?.first")
        val end = screen.indexOf("logoUrl = art?.second", start)
        assertTrue("the landscape card's backdrop chain must exist", start >= 0 && end > start)

        val chain = screen.substring(start, end)
        assertTrue(
            "the add-on's own background is still preferred over the poster",
            chain.contains("?: meta.background")
        )
        assertTrue(
            "a card with no background at all must fall back to its poster",
            chain.contains("?: meta.poster")
        )
    }
}
