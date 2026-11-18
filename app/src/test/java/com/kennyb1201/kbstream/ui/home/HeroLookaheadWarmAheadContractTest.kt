package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hero lookahead, warm-ahead spec (2026-11-17).
 *
 * The lookahead warmed the right things but always arrived behind the walk:
 * it fired from inside the hero resolve after the dwell (which a held D-pad
 * never survives), it warmed its cards one at a time, and it only ever looked
 * forward in the same rail. On a fast walk - exactly when the jank is felt -
 * every card was cold.
 *
 * This pins the three repairs where a JVM test can see them: the warm starts on
 * the FOCUS event, the cards are warmed in parallel behind the same bounds, and
 * the near field now covers back and across rails at hero size. The numbers on
 * a device (the hit rate) are F5's job, not this class's.
 */
class HeroLookaheadWarmAheadContractTest {

    private val vm by lazy { source(VIEW_MODEL) }
    private val home by lazy { source(HOME_SCREEN) }
    private val imageTrace by lazy { source(IMAGE_TRACE) }

    // ── F1: fired on focus, before the debounce ──────────────────────────

    @Test
    fun `the warm starts in the focus handler, not in the resolve job`() {
        val select = home.substringAfter("fun selectHero(")
            .substringBefore("fun selectContinueWatchingHero(")
        assertTrue(
            "the card's own focus handler asks for the near field",
            select.contains("viewModel.warmHeroFocus(item)")
        )
        val entry = vm.substringAfter("fun warmHeroFocus(")
            .substringBefore("private fun warmHeroLookahead(")
        assertTrue(
            "one entry for both warms, so the focus path cannot fire only half",
            entry.contains("warmHeroLookahead(item)") &&
                entry.contains("warmRailTiles(item)")
        )
        val resolve = vm.substringAfter("fun resolveHeroMeta(")
            .substringBefore("fun reWarmIfCachesWereReleased()")
        assertFalse(
            "and it is gone from the resolve job, where the 150 ms debounce and the " +
                "dwell kept the warm behind the walk",
            resolve.contains("warmHeroLookahead(item)")
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
