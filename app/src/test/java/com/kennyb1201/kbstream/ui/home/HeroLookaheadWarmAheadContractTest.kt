package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hero lookahead: warm-ahead spec (2026-11-17), as re-shaped by the 0.9.399
 * walk (2026-11-20).
 *
 * Every rule here was bought with a paste. F2/F3 fixed the SHAPE of the warm -
 * parallel, bounded by a two-permit semaphore and an in-flight set - and those
 * pins still hold. F1 moved the trigger onto the focus event; the 2026-11-19
 * churn reading moved it to the art dwell, because firing per step put six
 * ~3.5MB decodes into a 48MB cache for every card the D-pad passed.
 *
 * The 0.9.399 walk then showed the dwell is the wrong half of the same mistake:
 * `heroWarms=10` beside `heroDraws=14` with `hits=2 misses=12`, and 117 focus
 * arrivals for those 14 draws. A warm fired at the dwell starts in the same
 * frame the hero draws, so it can only duplicate that decode; and the near field
 * it wrote was the art of cards the walk merely crossed. `hero=24MB/7` of a
 * 44/48MB cache was entries the hero never read.
 *
 * The rule since 0.9.400: the FOCUS STEP starts a timer, every further step
 * cancels it, and the one card still owed a warm when the walk stops is the card
 * the hero will draw - 600ms of stillness after that last step. One card, one
 * decode, one entry, and enough lead for it to be a hit.
 *
 * Source pins, because the ViewModel is not harness-compilable.
 */
class HeroLookaheadWarmAheadContractTest {

    private val vm by lazy { source(VIEW_MODEL) }
    private val home by lazy { source(HOME_SCREEN) }
    private val imageTrace by lazy { source(IMAGE_TRACE) }

    // ── F1: fired on focus, never from the dwell ─────────────────────────

    @Test
    fun `the warm starts on the focus step, never from the art dwell`() {
        val select = home.substringAfter("fun selectHero(")
            .substringBefore("fun selectContinueWatchingHero(")
        assertTrue(
            "the card's own focus handler asks for the warm",
            select.contains("viewModel.warmHeroFocus(item)")
        )
        val entry = vm.substringAfter("fun warmHeroFocus(")
            .substringBefore("private fun warmHeroLookahead(")
        assertTrue(
            "which starts the tile warm",
            entry.contains("warmRailTiles(item)")
        )
        assertTrue(
            "and the hero-size warm, which the walk's own cadence re-aims " +
                "(see the cancel below) instead of warming a field of neighbours",
            entry.contains("warmHeroLookahead(item)")
        )
        val resolve = vm.substringAfter("fun resolveHeroMeta(")
            .substringBefore("fun reWarmIfCachesWereReleased()")
        assertFalse(
            "the art dwell must NOT start it: that is the frame the hero draws " +
                "in, so a warm there can only duplicate its decode (0.9.399: " +
                "heroWarms=10, heroDraws=14, hits=2)",
            resolve.contains("warmHeroLookahead(item)")
        )
        assertTrue(
            "the dwells themselves still gate what the resolve publishes",
            resolve.contains("delay(HERO_RESOLVE_DWELL_MS)") &&
                resolve.contains("awaitHeroArtDwell(resolveStartedAtMs)")
        )
    }

    // ── F2: one card, behind the same bounds ────────────────────────────

    @Test
    fun `one card is warmed, and the walk's step decides which`() {
        val lookahead = lookaheadBlock()
        assertFalse(
            "no near field: 117 arrivals bought 14 draws on 0.9.399, so a warm " +
                "for a card the walk passes is 3.5MB of art nothing draws",
            lookahead.contains("HERO_BITMAP_LOOKAHEAD_CARDS") ||
                lookahead.contains("HERO_LOOKAHEAD_BACK") ||
                lookahead.contains(".take(HERO_LOOKAHEAD_MAX_CARDS)")
        )
        assertTrue(
            "every focus step cancels the pending warm, so only a step the walk " +
                "STOPS on survives the delay",
            lookahead.contains("heroLookaheadJob?.cancel()") &&
                lookahead.indexOf("heroLookaheadJob?.cancel()") <
                lookahead.indexOf("delay(HERO_WARM_AFTER_ART_MS)")
        )
        assertTrue(
            "and that card is the focused item itself",
            lookahead.contains("resolveHeroArtworkForPrefetch(item)")
        )
        assertTrue(
            "still behind the existing semaphore and in-flight set, so a fast walk " +
                "cannot launch an unbounded warm",
            lookahead.contains("heroArtPrefetchSemaphore.withPermit") &&
                lookahead.contains("heroArtPrefetchInFlight.add(key)") &&
                lookahead.contains("heroArtPrefetchInFlight.remove(key)")
        )
        assertTrue(
            "the permit covers the decode too: 1280x720 bitmaps are never in " +
                "flight at once on a small box",
            Regex("withPermit \\{\\s*warmHeroArtBitmap\\(").containsMatchIn(lookahead)
        )
    }

    // ── F3: the entry written is the entry the hero reads ───────────────

    @Test
    fun `the warm asks at the size the hero draws, on the url the hero upgraded`() {
        assertTrue(
            "1280x720 in, 1280x720 out: an entry at any other size is one the " +
                "hero can never read (0.9.393: hits=0 misses=25)",
            vm.contains("private const val HERO_LOOKAHEAD_BITMAP_WIDTH = 1280") &&
                home.contains(".size(Size(1280, 720))") &&
                lookaheadBlock().contains("warmHeroArtBitmap(")
        )
        assertTrue(
            "and on the resolved url, not the w1280 one the hero upgrades before " +
                "asking Coil - keyed by url AND size, the warm has to match both",
            vm.substringAfter("private suspend fun warmHeroArtBitmap(")
                .contains("TmdbRepository.fullResBackdropUrl(")
        )
    }

    // ── F4: nothing else moved ───────────────────────────────────────────

    @Test
    fun `the lookahead still touches TMDB only, and the backlog and re-warm are intact`() {
        val lookahead = lookaheadBlock()
        assertFalse(
            "no MDBList, no tracker",
            lookahead.contains("MdbList") || lookahead.contains("Trakt") ||
                lookahead.contains("Simkl")
        )
        assertTrue(
            "the 60-card backlog warm is untouched",
            vm.contains("private val heroArtPrefetchLimit = 60") &&
                vm.contains("fun prefetchHeroArt(items: List<MetaPreview>)")
        )
        val reWarm = vm.substringAfter("fun reWarmIfCachesWereReleased() {")
            .substringBefore("// ── The phone Home billboard")
        assertTrue(
            "and a released image cache still re-warms the same two halves",
            reWarm.contains("warmHeroLookahead(item)") &&
                reWarm.contains("warmRailTiles(item)")
        )
        assertTrue(
            "the rail-tile warm keeps its own directions at rail size",
            vm.contains("RAIL_TILE_LOOKAHEAD_BACK") &&
                vm.contains("RAIL_TILE_LOOKAHEAD_FORWARD")
        )
    }

    // ── F2 (2026-11-19, churn): the wait, and who owns it ───────────────

    @Test
    fun `the wait sits inside the dwell and belongs to the walk`() {
        assertTrue(
            "250ms, well inside the 600ms of stillness the hero needs before it " +
                "publishes - the rest of the dwell is the lead the hit comes from",
            vm.contains("private const val HERO_WARM_AFTER_ART_MS = 250L") &&
                vm.contains("private const val HERO_ART_DWELL_MS = 600L")
        )
        val lookahead = lookaheadBlock()
        val launchAt = lookahead.indexOf("viewModelScope.launch {")
        val delayAt = lookahead.indexOf("delay(HERO_WARM_AFTER_ART_MS)")
        val firstWarmAt = lookahead.indexOf("warmHeroArtBitmap(")
        assertTrue(
            "the wait is inside the lookahead's own launch",
            launchAt >= 0 && delayAt > launchAt
        )
        assertTrue(
            "and before the first decode, or the step's own work and the warm " +
                "still share the frame the walk is on",
            firstWarmAt > delayAt
        )
        assertFalse(
            "the rail-tile warm is not delayed with it: it fires on the focus " +
                "event, a rail of card-size decodes",
            vm.substringAfter("fun warmHeroFocus(")
                .substringBefore("private var heroLookaheadJob")
                .contains("HERO_WARM_AFTER_ART_MS")
        )
    }

    // ── F5: the hit rate reaches the paste ───────────────────────────────

    @Test
    fun `the paste carries focus arrivals and the hero's warm hits`() {
        assertTrue(
            "the arrival is counted where the focus entry is",
            vm.substringAfter("fun warmHeroFocus(")
                .substringBefore("private fun warmHeroLookahead(")
                .contains("ImageLoadTrace.noteHeroFocusArrival()")
        )
        assertTrue(
            "the draw reports Coil's own source, so a hit is a hit",
            home.contains("ImageLoadTrace.noteHeroDraw(") &&
                home.contains("coil3.decode.DataSource.MEMORY_CACHE")
        )
        assertTrue(
            "and the line carries arrivals, hits, misses and the integer rate",
            imageTrace.contains("\"hero: lookahead arrivals=\$heroFocusArrivals \"") &&
                imageTrace.contains("hits=\$heroDrawHits misses=\$heroDrawMisses") &&
                imageTrace.contains("rate=\$rate%")
        )
        assertTrue(
            "the counts are cleared with the rest of the trace",
            imageTrace.substringAfter("fun reset()").contains("heroFocusArrivals = 0") &&
                imageTrace.substringAfter("fun reset()").contains("heroDrawHits = 0")
        )
    }

    private fun lookaheadBlock(): String =
        vm.substringAfter("private fun warmHeroLookahead(")
            .substringBefore("private fun warmRailTiles(")

    private fun source(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) {
                    val file = File(candidate, relative)
                    assertTrue("source missing: $file", file.isFile)
                    return file.readText()
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError("main source root not found")
    }

    private companion object {
        const val VIEW_MODEL = "com/kennyb1201/kbstream/ui/home/HomeViewModel.kt"
        const val HOME_SCREEN = "com/kennyb1201/kbstream/ui/home/HomeScreen.kt"
        const val IMAGE_TRACE = "com/kennyb1201/kbstream/data/reporting/ImageLoadTrace.kt"
    }
}
