package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The billboard warms its own art, at its own size, on the page turn.
 *
 * Spec 2026-11-18, Billboard Warm. The report behind it: the hero lookahead fires
 * on rail focus and writes a `Size(1280, 720)` cache entry - the TV Modern hero's
 * size and Detail's - while both billboard surfaces draw through `MobileHero`,
 * which asks Coil for `Size(1280, 800)`. A warm writes the entry of the size it
 * asked for and no other, so the billboard's turns were paying a fresh hero-size
 * decode while `heroWarms` counted decodes it could never use. And no billboard
 * page change called the warm at all: at the top of Home no rail card is focused,
 * so the focus path never ran there.
 *
 * Pinned as source because the two numbers are in three files and the failure is
 * invisible (both sizes are "the hero" to a reader): a refactor that collapses
 * them back to one constant restores a decoded-but-never-read entry per turn,
 * which no unit test can observe without a live Coil cache.
 */
class BillboardWarmContractTest {

    private val vm = source(HOME_VM)
    private val mobile = source(HOME_MOBILE)
    private val spotlight = source(SPOTLIGHT)
    private val rules = source(BILLBOARD_RULES)

    // ── F1: the warm exists, at the billboard's size ─────────────────────

    @Test
    fun `the warm asks for the size the billboard actually draws`() {
        assertTrue(
            "the draw site is the source of truth: MobileHero asks for 1280x800",
            mobile.contains(".size(coil3.size.Size(1280, 800))")
        )
        val warm = block(vm, "fun warmBillboardArt(")
        assertTrue(
            "and the billboard's warm asks for exactly that pair",
            warm.contains("BILLBOARD_LOOKAHEAD_BITMAP_WIDTH") &&
                warm.contains("BILLBOARD_LOOKAHEAD_BITMAP_HEIGHT")
        )
        assertTrue(
            "whose values are the draw's, not the Modern hero's 1280x720",
            vm.contains("private const val BILLBOARD_LOOKAHEAD_BITMAP_WIDTH = 1280") &&
                vm.contains("private const val BILLBOARD_LOOKAHEAD_BITMAP_HEIGHT = 800")
        )
    }

    @Test
    fun `S3 - the 1280x720 warm is untouched, because two surfaces still read it`() {
        assertTrue(
            "the Modern hero and Detail keep their entry",
            vm.contains("private const val HERO_LOOKAHEAD_BITMAP_WIDTH = 1280") &&
                vm.contains("private const val HERO_LOOKAHEAD_BITMAP_HEIGHT = 720")
        )
        val hero = block(vm, "private suspend fun warmHeroArtBitmap(")
        assertTrue(
            "and the hero warm still decodes at it",
            hero.contains(".size(HERO_LOOKAHEAD_BITMAP_WIDTH, HERO_LOOKAHEAD_BITMAP_HEIGHT)")
        )
        assertTrue(
            "the billboard's own constants are a SEPARATE pair - collapsing them " +
                "to one is the regression this class exists for",
            vm.contains("BILLBOARD_LOOKAHEAD_BITMAP_HEIGHT = 800") &&
                !vm.contains("BILLBOARD_LOOKAHEAD_BITMAP_HEIGHT = 720")
        )
    }

    @Test
    fun `the warm is memory-only, deduped, and skipped until the art resolves`() {
        val warm = block(vm, "fun warmBillboardArt(")
        assertTrue(
            "it decodes INTO the loader, which is what writes the memory entry " +
                "(the disk-byte warm is the other function, and does not help a draw)",
            warm.contains("SingletonImageLoader.get(application).execute(")
        )
        assertTrue(
            "a blank or unresolved url warms nothing: the page hands over its " +
                "resolved backdrop, and null is skipped before any decode",
            warm.contains("artUrl?.takeIf { it.isNotBlank() } ?: return")
        )
        assertTrue(
            "and a swipe burst cannot stack decodes of the same url",
            warm.contains("if (!heroArtPrefetchInFlight.add(url)) return") &&
                warm.contains("heroArtPrefetchInFlight.remove(url)")
        )
    }

    // ── F2: it fires on the page turn ────────────────────────────────────

    @Test
    fun `the billboard fires the warm whenever a page settles`() {
        val effect = mobile.substringAfter("LaunchedEffect(pagerState.settledPage, nextPage?.backdropUrl) {")
            .substringBefore("}")
        assertTrue(
            "the turn is the trigger: a cycle tick, a swipe and a chevron press all " +
                "move settledPage, and a page whose art lands later re-runs this " +
                "because the url is a key",
            effect.contains("onWarmNextPageArt(nextPage?.backdropUrl)")
        )
        assertTrue(
            "the page it warms is the NEXT one - the one the turn is about to show",
            mobile.contains(
                "val nextPage = pages.getOrNull((pagerState.settledPage + 1) % pages.size.coerceAtLeast(1))"
            )
        )
    }

    @Test
    fun `both billboard surfaces wire it to the view model`() {
        assertTrue(
            "the phone Home",
            mobile.contains("onWarmNextPageArt = viewModel::warmBillboardArt")
        )
        assertTrue(
            "and the TV's Spotlight, where no rail card is focused at the top of Home",
            spotlight.contains("onWarmNextPageArt = viewModel::warmBillboardArt")
        )
    }

    // ── F3: nothing else about the billboard moved ───────────────────────

    @Test
    fun `the pager setup, the cycle and the rail warm are all as they were`() {
        assertTrue(
            "the pager's own window is untouched - this spec is not a prefetch widening",
            mobile.contains("beyondViewportPageCount")
        )
        assertFalse(
            "and the billboard warm did not become a second cycle clock",
            block(vm, "fun warmBillboardArt(").contains("cycleMs")
        )
        assertTrue(
            "the rail-focus warm is still the rail card's (F3: an additional " +
                "trigger, not a moved one)",
            source(HOME_SCREEN).contains("warmHeroFocus(")
        )
        assertTrue(
            "and the sweep it warms FOR is unchanged",
            rules.contains("internal fun billboardSweep(index: Int)")
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /** One function's source, by matching its declaration and balancing braces. */
    private fun block(source: String, declaration: String): String {
        val at = source.indexOf(declaration)
        require(at >= 0) { "missing: $declaration" }
        val bodyStart = source.indexOf("{", at)
        require(bodyStart > at) { "no body: $declaration" }
        var depth = 0
        for (i in bodyStart until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(at, i + 1)
                }
            }
        }
        error("unbalanced: $declaration")
    }

    private fun source(relative: String): String {
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
        throw AssertionError("source not found above ${System.getProperty("user.dir")}: $relative")
    }

    private companion object {
        const val HOME_VM = "ui/home/HomeViewModel.kt"
        const val HOME_MOBILE = "ui/mobile/HomeScreenMobile.kt"
        const val SPOTLIGHT = "ui/home/SpotlightHome.kt"
        const val BILLBOARD_RULES = "ui/home/BillboardRules.kt"
        const val HOME_SCREEN = "ui/home/HomeScreen.kt"
    }
}
