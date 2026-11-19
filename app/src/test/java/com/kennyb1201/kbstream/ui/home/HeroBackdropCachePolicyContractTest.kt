package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hero's half of the memory cache (spec 2026-11-19, churn F2).
 *
 * The build-389 walk reported `image cache: 47MB of 48MB hero=28MB/8 other=34MB/121`:
 * eight 1280x720 entries - 3.5MB each - holding 58% of the cache the rail's own
 * ~0.38MB tiles were evicted from, with `heroWarms` already cut to 10. The warms
 * were never that 28MB; every hero DRAW that missed one decoded a backdrop and
 * Coil cached it, and only the hero on screen is ever drawn again.
 *
 * So the pair is now split: the lookahead still writes the entry (that is the
 * whole point of the warm - the hero should swap, not decode), and the draw only
 * reads it. These cases pin both halves, because either one alone is a different
 * defect: a draw that stops writing but a warm that stops writing too is a
 * prefetch that does nothing, and a warm pinned read-only would leave every hero
 * draw decoding its own backdrop.
 */
class HeroBackdropCachePolicyContractTest {

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
        const val SCREEN = "com/kennyb1201/kbstream/ui/home/HomeScreen.kt"
        const val VIEW_MODEL = "com/kennyb1201/kbstream/ui/home/HomeViewModel.kt"
    }

    @Test
    fun `the hero draws the warmed entry but never writes one`() {
        val screen = source(SCREEN)
        val backdrop = screen
            .substringAfter("private fun HeroBackdropArt(url: String?)")
            .substringBefore("internal fun HeroClearLogo(")

        val sizeAt = backdrop.indexOf(".size(Size(1280, 720))")
        val policyAt = backdrop.indexOf(".memoryCachePolicy(CachePolicy.READ_ONLY)")
        val crossfadeAt = backdrop.indexOf(".crossfade(false)")

        assertTrue("the hero still asks for its own 1280x720 decode", sizeAt >= 0)
        assertTrue(
            "the draw must read the memory cache, not add to it: the eight heroes a " +
                "walk passes were 28MB of a 47/48MB cache and none but the one on " +
                "screen is ever drawn again (build-389 paste)",
            policyAt >= 0
        )
        assertTrue(
            "one request: the policy belongs to the builder that carries the size and " +
                "the crossfade setting, not to a second one",
            sizeAt < policyAt && policyAt < crossfadeAt
        )
        assertFalse(
            "a disabled memory cache would throw away the hits the warm buys (11 of 37 " +
                "draws, 29%): the draw reads, it just does not write",
            backdrop.contains("memoryCachePolicy(CachePolicy.DISABLED)") ||
                backdrop.contains("memoryCachePolicy(CachePolicy.WRITE_ONLY)")
        )
    }

    @Test
    fun `both hero draw sites go through the one composable that sets it`() {
        val screen = source(SCREEN)
        // One declaration and two draws: the hero itself, and the layer held over
        // the trailer player until its first frame. A third draw written inline
        // would sidestep the policy this test exists for.
        val draws = Regex("HeroBackdropArt\\(").findAll(screen).count()
        assertTrue(
            "the hero backdrop is drawn in exactly the two places this composable " +
                "serves, found $draws uses",
            draws == 3
        )
    }

    @Test
    fun `the lookahead keeps writing the entry the hero reads`() {
        val vm = source(VIEW_MODEL)
        val bitmap = vm
            .substringAfter("private suspend fun warmHeroArtBitmap(")
            .substringBefore("fun warmBillboardArt(")

        assertTrue(
            "the warm must write at the hero's own size, or the entry it writes is " +
                "one the hero can never read",
            bitmap.contains(".size(HERO_LOOKAHEAD_BITMAP_WIDTH, HERO_LOOKAHEAD_BITMAP_HEIGHT)")
        )
        assertFalse(
            "the warm's whole job is the write: a read-only or disabled warm is the " +
                "disk prefetch this one was added to replace",
            bitmap.contains("memoryCachePolicy")
        )
    }
}
