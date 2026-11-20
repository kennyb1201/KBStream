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
    fun `the hero keeps the bitmap it draws and drops the one it replaced`() {
        val screen = source(SCREEN)
        val backdrop = screen
            .substringAfter("private fun HeroBackdropArt(url: String?)")
            .substringBefore("internal fun HeroClearLogo(")

        assertTrue(
            "the hero still asks for its own 1280x720 decode",
            backdrop.contains(".size(Size(1280, 720))")
        )
        assertFalse(
            "the draw WRITES its bitmap again (0.9.399, coverage). `hits=0 " +
                "misses=17` beside `hero=10MB/3` on the 2026-11-19 walk was a draw " +
                "that could never leave an entry behind, so every walk back over " +
                "ground already seen paid the decode again",
            backdrop.contains("memoryCachePolicy(CachePolicy.READ_ONLY)")
        )
        assertFalse(
            "and it is neither disabled nor write-only: the hero has to READ the " +
                "entry the lookahead warmed",
            backdrop.contains("memoryCachePolicy(CachePolicy.DISABLED)") ||
                backdrop.contains("memoryCachePolicy(CachePolicy.WRITE_ONLY)")
        )
        assertTrue(
            "the draw hands Coil's own key back to the holder: that is what makes " +
                "the eviction possible without rebuilding the key by hand",
            backdrop.contains("held.hold(context, url, state.result.memoryCacheKey)")
        )

        val holder = screen.substringAfter("private class HeroHeldBitmap")
            .substringBefore("/**\n * The hero's backdrop art")
        assertTrue(
            "one entry, not eight: the entry the replaced hero wrote is removed as " +
                "soon as the new one has landed",
            holder.contains("memoryCache") && holder.contains(".remove(previous)")
        )
        assertTrue(
            "and the same url drawn again is not a new hero: removing it would " +
                "throw away the bitmap that is on screen",
            holder.contains("if (url == heldUrl)")
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
        assertTrue(
            "and at the URL the hero can read, not just the size: the hero upgrades " +
                "the w1280 artwork url through fullResBackdropUrl before asking Coil, " +
                "and Coil's memory cache is keyed by url AND size - so a warm on the " +
                "raw backdropUrl wrote an entry the draw could never hit. That is the " +
                "0.9.393 paste's `heroWarms=6` with `hits=0` out of 25 draws.",
            bitmap.contains("TmdbRepository.fullResBackdropUrl(")
        )
        assertFalse(
            "the warm's whole job is the write: a read-only or disabled warm is the " +
                "disk prefetch this one was added to replace",
            bitmap.contains("memoryCachePolicy")
        )
    }
}
