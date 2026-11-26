package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scrolling round-two wiring (spec 2026-11-15): the two-tier hero dwell, the
 * lookahead warm, and the graded bitmap trim.
 *
 * Each of these is a small number of lines in a very large file, and each has a
 * slower spelling that looks just as correct in review: art published with the
 * meta, a lookahead that skips the semaphore, a trim that calls `clear()`. These
 * cases pin the fast spelling.
 */
class ScrollRoundTwoContractTest {

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
        const val APPLICATION = "com/kennyb1201/kbstream/MainApplication.kt"
        const val MEMORY = "com/kennyb1201/kbstream/data/memory/MemoryPressure.kt"
    }

    // ── F2: meta at 150ms, art at a real stop ─────────────────────────────

    @Test
    fun `the art tier waits for its own dwell, the meta tier does not`() {
        val vm = source(VIEW_MODEL)
        assertTrue(
            "the art dwell must be 600ms",
            vm.contains("private const val HERO_ART_DWELL_MS = 600L")
        )
        assertTrue(
            "the meta tier must keep its 150ms dwell",
            vm.contains("private const val HERO_RESOLVE_DWELL_MS = 150L")
        )
        // Every art publish inside the hero resolve goes through the gate.
        val resolve = vm.substringAfter("fun resolveHeroMeta(")
            .substringBefore("// ── The phone Home billboard")
        assertTrue(
            "the hero resolve must gate its art",
            resolve.contains("awaitHeroArtDwell(resolveStartedAtMs)")
        )
        assertEquals(
            "both art publishes - the early one and the full set - go through the " +
                "gate; a third, ungated assignment would flash the art on a step",
            2,
            resolve.windowed("awaitHeroArtDwell(resolveStartedAtMs)".length)
                .count { it == "awaitHeroArtDwell(resolveStartedAtMs)" }
        )
        // The gate is only useful because the job dies on a focus move.
        assertTrue(
            "the dwell gate rides the resolve job's cancellation",
            vm.contains("heroResolveJob?.cancel()")
        )
    }

    @Test
    fun `the early art fetch still runs in parallel, only its publish is held`() {
        val vm = source(VIEW_MODEL)
        val resolve = vm.substringAfter("fun resolveHeroMeta(")
            .substringBefore("// ── The phone Home billboard")
        val artworkFetch = resolve.indexOf("val artworkDeferred = async")
        val earlyLaunch = resolve.indexOf("val earlyArt = artworkDeferred.await()")
        val dwell = resolve.indexOf("awaitHeroArtDwell(resolveStartedAtMs)")
        assertTrue("the artwork fetch must stay in the parallel block", artworkFetch > 0)
        assertTrue("the early publish still awaits the fetch", earlyLaunch > artworkFetch)
        assertTrue(
            "and then waits out the art dwell before publishing",
            dwell > earlyLaunch
        )
    }

    // ── F3: the lookahead ─────────────────────────────────────────────────

    @Test
    fun `the lookahead is bounded, shared and cancelled with nothing else`() {
        val vm = source(VIEW_MODEL)
        val lookahead = vm.substringAfter("private fun warmHeroLookahead(")
            .substringBefore("private suspend fun resolveHeroArtworkForPrefetch(")
        assertTrue("three cards ahead", vm.contains("HERO_LOOKAHEAD_CARDS = 3"))
        assertTrue(
            "it must share the prefetch semaphore, or it competes with the foreground",
            lookahead.contains("heroArtPrefetchSemaphore.withPermit")
        )
        assertTrue(
            "it must share the in-flight set, or it warms the same card twice",
            lookahead.contains("heroArtPrefetchInFlight.add(key)") &&
                lookahead.contains("heroArtPrefetchInFlight.remove(key)")
        )
        assertTrue(
            "the lookahead is a launch of its own, not a child of the resolve job",
            lookahead.contains("viewModelScope.launch {")
        )
        assertFalse(
            "it must never touch MDBList or a tracker",
            lookahead.contains("MdbList") || lookahead.contains("Trakt") ||
                lookahead.contains("Simkl")
        )
    }

    @Test
    fun `the lookahead warms the bitmap the hero draws, into memory`() {
        val vm = source(VIEW_MODEL)
        val bitmap = vm.substringAfter("private suspend fun warmHeroArtBitmap(")
            .substringBefore("private fun warmHeroLookahead(")
        assertTrue(
            "the hero's own size, so the cache entry matches what the hero reads",
            vm.contains("HERO_LOOKAHEAD_BITMAP_WIDTH = 1280") &&
                vm.contains("HERO_LOOKAHEAD_BITMAP_HEIGHT = 720")
        )
        assertTrue(
            "the backdrop is the bitmap that costs the 1280x720 decode",
            bitmap.contains("artwork?.backdropUrl")
        )
        assertFalse(
            "the memory policy must stay ON: a disk-only warm is what the old " +
                "prefetch already did, and it leaves the decode to the hero",
            bitmap.contains("memoryCachePolicy(coil3.request.CachePolicy.DISABLED)")
        )
    }

    @Test
    fun `the lookahead is the walk's to aim, one card at a time`() {
        val vm = source(VIEW_MODEL)
        val focusEntry = vm.substringAfter("fun warmHeroFocus(")
            .substringBefore("private var heroLookaheadJob")
        assertTrue(
            "the focus entry still starts the tile warm",
            focusEntry.contains("warmRailTiles(item)")
        )
        assertTrue(
            "and the hero warm starts there too: the settle frame is the hero's own " +
                "draw, so a warm fired there can only duplicate it (0.9.399: " +
                "heroWarms=10, heroDraws=14, hits=2 misses=12)",
            focusEntry.contains("warmHeroLookahead(item)")
        )
        assertTrue(
            "the hit rate is still counted per focus arrival, not per warm",
            focusEntry.contains("ImageLoadTrace.noteHeroFocusArrival()")
        )
        assertTrue(
            "every step cancels the pending warm, so the card that survives the " +
                "delay is the one the walk STOPPED on",
            vm.substringAfter("private fun warmHeroLookahead(")
                .contains("heroLookaheadJob?.cancel()")
        )
        val resolve = vm.substringAfter("fun resolveHeroMeta(")
            .substringBefore("fun reWarmIfCachesWereReleased()")
        assertFalse(
            "the resolve must not start it any more",
            resolve.contains("warmHeroLookahead(item)")
        )
        assertTrue(
            "while the dwells still gate what the resolve PUBLISHES",
            resolve.contains("delay(HERO_RESOLVE_DWELL_MS)")
        )
        // The focus handler still starts the half that is cheap per step.
        val screen = source(SCREEN)
        val select = screen.substringAfter("fun selectHero(")
            .substringBefore("fun selectContinueWatchingHero(")
        assertTrue(
            "the card's focus handler still asks for the near field",
            select.contains("viewModel.warmHeroFocus(item)")
        )
    }

    // ── F4: the graded trim ───────────────────────────────────────────────

    @Test
    fun `critical trims half the bitmap cache and low memory still clears it`() {
        val app = source(APPLICATION)
        // Dig four §3b/§3e (rank 2) made the response two-stage, so the round-two
        // pin is re-anchored on the branch it describes rather than on the whole
        // critical block.
        // 2026-11-26 (art-decode audit): the branch is now a `when` over
        // ImageCacheRelease, so the pin moves to the arms and keeps the same
        // two rules - a foreground critical trims (not clears), at the smaller
        // bite; a clear waits for the process having left the screen.
        assertTrue(
            "a foreground CRITICAL is a graded trim - and the smaller bite " +
                "(round four F3c), because this is the only trim with a rail on screen",
            app.contains("critical = level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL") &&
                app.contains("ImageCacheRelease.TRIM ->") &&
                app.contains("trimImageMemoryCache(this, ImageCacheTrim.SCROLL_FRACTION)")
        )
        assertTrue(
            "and a full clear waits for BACKGROUND and above, where nothing is on " +
                "screen: that is what makes it free",
            app.contains("backgrounded = level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND") &&
                app.contains("ImageCacheRelease.CLEAR -> releaseImageMemoryCache(this)")
        )
        assertTrue(
            "onLowMemory still drops everything - that is the process dying",
            app.substringAfter("override fun onLowMemory()")
                .substringBefore("override fun onTrimMemory(")
                .contains("releaseImageMemoryCache(this)")
        )
        assertTrue(
            "the browsing caches are still released in the same branch",
            app.substringAfter("override fun onTrimMemory(level: Int) {")
                .substringBefore("private fun trimLevelName")
                .contains("MemoryPressure.releaseBrowsingCaches()")
        )
    }

    @Test
    fun `the trim reports itself apart from a clear`() {
        val memory = source(MEMORY)
        assertTrue(
            "a graded trim is counted",
            memory.contains("fun noteImageCacheTrimmed()")
        )
        assertTrue(
            "the line has to say which kind of release it was",
            memory.contains("trims=\${imageTrims.get()} clears=\${imageClears.get()}")
        )
        assertTrue(
            "images= stays the total, so an older paste still reads the same way",
            memory.contains("\"releases: images=\$images browsing=\$browsing\"")
        )
        // Dig four §3c revised the ordering: the trim is now Coil's own LRU
        // (see LruTrimWiringContractTest), so this pin names the shape that
        // replaced the hero-first pass rather than the pass itself.
        assertTrue(
            "the trim's size comes from the pure fraction rule, at the fraction the " +
                "caller asked for",
            memory.contains("ImageCacheTrim.targetBytes(resident, fraction)")
        )
        assertTrue(
            "and the eviction order is the cache's own LRU, not a computed list",
            memory.contains("cache.trimToSize(target)")
        )
    }

    // ── F7: verified, not invented ───────────────────────────────────────

    @Test
    fun `the hero does not hold two backdrops during a crossfade`() {
        val screen = source(SCREEN)
        val art = screen.substringAfter("private fun HeroBackdropArt(")
            // The logo drawer is `internal` since the Spotlight panel reuses it
            // (spec 2026-11-15); the slice ends where it begins.
            .substringBefore("internal fun HeroClearLogo(")
        assertEquals(
            "one AsyncImage, one buffer: the crossfade is Coil's own alpha on a " +
                "single image, not two full-screen layers",
            1,
            Regex("AsyncImage\\(").findAll(art).count()
        )
        assertFalse(
            "no per-card blur anywhere in the home card path (the glow is the " +
                "tv-material3 platform shadow, which allocates no offscreen buffer)",
            screen.contains("Modifier.blur(")
        )
    }
}
