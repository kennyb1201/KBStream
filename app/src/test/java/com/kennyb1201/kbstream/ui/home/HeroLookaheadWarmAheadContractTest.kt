package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hero lookahead, warm-ahead spec (2026-11-17), as amended 2026-11-19.
 *
 * The lookahead warmed the right things but arrived behind the walk: it warmed
 * its cards one at a time and only ever looked forward in the same rail, so on a
 * fast walk - exactly when the jank is felt - every card was cold. F2/F3 fixed
 * the SHAPE of the warm (parallel, back and across rails) and those pins hold.
 *
 * F1 moved the trigger onto the focus event; the 2026-11-19 churn reading
 * reversed that half. Firing per focus step put six ~3.5MB decodes against a 48MB
 * cache for every card the D-pad passed, and the build-386 walk drew 18 heroes
 * from 113 warms with ZERO memory hits - the art publishes only after the same
 * 600 ms dwell, so the warms had evicted each other before anything read them.
 * The warm now fires at that dwell, so it describes the card the viewer stopped
 * on. Nothing about the warm itself changed - bounds, sizes, parallelism,
 * semaphore, in-flight set - which is what the rest of this class pins.
 */
class HeroLookaheadWarmAheadContractTest {

    private val vm by lazy { source(VIEW_MODEL) }
    private val home by lazy { source(HOME_SCREEN) }
    private val imageTrace by lazy { source(IMAGE_TRACE) }

    // ── F1: fired on focus, before the debounce ──────────────────────────

    @Test
    fun `the warm starts on the settled hero, and the tiles still start on focus`() {
        val select = home.substringAfter("fun selectHero(")
            .substringBefore("fun selectContinueWatchingHero(")
        assertTrue(
            "the card's own focus handler still asks for the near field",
            select.contains("viewModel.warmHeroFocus(item)")
        )
        val entry = vm.substringAfter("fun warmHeroFocus(")
            .substringBefore("private fun warmHeroLookahead(")
        assertTrue(
            "which starts the tile warm",
            entry.contains("warmRailTiles(item)")
        )
        assertFalse(
            "and NOT the hero lookahead: per focus step it was six 3.5MB decodes " +
                "the walk never drew (2026-11-19, churn F1)",
            entry.contains("warmHeroLookahead(item)")
        )
        val resolve = vm.substringAfter("fun resolveHeroMeta(")
            .substringBefore("fun reWarmIfCachesWereReleased()")
        val dwellAt = resolve.indexOf("awaitHeroArtDwell(resolveStartedAtMs)")
        assertTrue(
            "the lookahead now starts after the art dwell, where the hero has " +
                "settled and the art actually publishes",
            dwellAt >= 0 && resolve.indexOf("warmHeroLookahead(item)") > dwellAt
        )
        assertTrue(
            "the dwells themselves still gate what the resolve publishes",
            resolve.contains("delay(HERO_RESOLVE_DWELL_MS)") &&
                resolve.contains("awaitHeroArtDwell(resolveStartedAtMs)")
        )
    }

    // ── F2: parallel, behind the same bounds ─────────────────────────────

    @Test
    fun `the cards warm in parallel, not one at a time`() {
        val lookahead = lookaheadBlock()
        assertTrue(
            "one async per card, joined - the sequential forEach loop this replaced " +
                "made three cards cost three cards' latency",
            lookahead.contains("targets.map { next ->") &&
                lookahead.contains("async {") &&
                lookahead.contains("}.awaitAll()") &&
                !lookahead.contains("ahead.forEach {")
        )
        assertTrue(
            "still behind the existing semaphore and in-flight set, so a fast walk " +
                "cannot launch an unbounded warm",
            lookahead.contains("heroArtPrefetchSemaphore.withPermit") &&
                lookahead.contains("heroArtPrefetchInFlight.add(key)") &&
                lookahead.contains("heroArtPrefetchInFlight.remove(key)")
        )
        assertTrue(
            "the permit covers the decode too: six 1280x720 bitmaps are never in " +
                "flight at once on a small box",
            Regex("withPermit \\{\\s*warmHeroArtBitmap\\(").containsMatchIn(lookahead)
        )
    }

    // ── F3: the near field is no longer forward-only ─────────────────────

    @Test
    fun `back, forward and both neighbouring rail heads are warmed at hero size`() {
        val lookahead = lookaheadBlock()
        assertTrue(
            "forward is unchanged",
            lookahead.contains("rail.items.drop(index + 1).take(HERO_LOOKAHEAD_CARDS)")
        )
        assertTrue(
            "back: the card a walk back lands on",
            lookahead.contains("subList(maxOf(0, index - HERO_LOOKAHEAD_BACK), index)")
        )
        assertTrue(
            "and the head card of the rail above and below - a vertical move's landing",
            lookahead.contains("rails.getOrNull(railIndex - 1)?.items") &&
                lookahead.contains("rails.getOrNull(railIndex + 1)?.items") &&
                lookahead.contains("?.take(HERO_LOOKAHEAD_RAIL_HEAD)")
        )
        assertTrue(
            "at most six per focus event, and no duplicate cards",
            lookahead.contains(".take(HERO_LOOKAHEAD_MAX_CARDS)") &&
                lookahead.contains(".distinctBy { \"\${it.type}:\${it.id}\" }")
        )
        assertTrue(
            "the constants say what they cover",
            vm.contains("HERO_LOOKAHEAD_CARDS = 3") &&
                vm.contains("HERO_LOOKAHEAD_BACK = 1") &&
                vm.contains("HERO_LOOKAHEAD_RAIL_HEAD = 1") &&
                vm.contains("HERO_LOOKAHEAD_MAX_CARDS = 6")
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
