package com.kennyb1201.kbstream.ui.home

import com.kennyb1201.kbstream.data.memory.ImageCacheRelease
import com.kennyb1201.kbstream.data.memory.ImageCacheTrim
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The art-decode audit's three fixes (spec 2026-11-26, Phase 2).
 *
 * The Phase 1 walk pastes named the mechanisms; these are the rules those fixes
 * rest on, kept pure so they run on the JVM:
 *
 *  - (c) HERO RE-DECODE: the focus event's walk-ahead decoded the next card's
 *    1280x720 bitmap on every step - `scroll: heroDecodes=124` in a 51-frame
 *    walk. The rule is that a metered walk does not decode hero art ahead; the
 *    settled card is still warmed after its dwell, and the URLs still resolve.
 *  - (a) WARM SIZE: the tile warm and the card's own request must take their
 *    size from one function ([HomeRailMetrics.artSizePx]), or the warm writes a
 *    memory-cache entry (keyed by URL *and* size) the card never reads.
 *  - (b) TRIM EVICTION: a memory-pressure trim that lands mid-walk evicts the
 *    cards the walk is about to reach; the rule defers it until the walk is over.
 *
 * Against the previous behaviour every one of these fails: the walk-ahead
 * decoded unconditionally, the warm computed its own pixel size while the card
 * let Coil resolve one from layout, and any critical level trimmed.
 */
class ArtDecodeWalkContractTest {

    // ── (a) The warm and the card ask for the same size ───────────────────

    @Test
    fun `one function fixes the rail card's artwork size in pixels`() {
        assertEquals(
            "landscape: 210x118dp at 2x - the size the card is drawn at",
            RailArtPx(width = 420, height = 236),
            HomeRailMetrics.artSizePx(landscapeCards = true, density = 2f)
        )
        assertEquals(
            "poster: 124x180dp at 2x",
            RailArtPx(width = 248, height = 360),
            HomeRailMetrics.artSizePx(landscapeCards = false, density = 2f)
        )
        assertEquals(
            "a fractional TV density rounds ONCE, here, for both callers",
            RailArtPx(width = 578, height = 325),
            HomeRailMetrics.artSizePx(landscapeCards = true, density = 2.75f)
        )
        assertEquals(
            "a degenerate density still asks for a real box",
            RailArtPx(width = 1, height = 1),
            HomeRailMetrics.artSizePx(landscapeCards = true, density = 0f)
        )
    }

    @Test
    fun `the tile warm decodes at that size, not one it computes itself`() {
        val warm = between(
            read(VIEW_MODEL),
            "private fun warmRailTiles(",
            "/**\n     * One rail tile, decoded into the memory cache"
        )
        assertTrue(
            "the warm takes its box from the shared function",
            warm.contains("HomeRailMetrics.artSizePx(")
        )
        assertTrue(
            "and hands those exact pixels to the decode",
            warm.contains("widthPx = artSize.width") && warm.contains("heightPx = artSize.height")
        )
        assertFalse(
            "no second rounding of its own: that is how the two sizes drifted",
            warm.contains("HomeRailMetrics.widthDp(") || warm.contains("HomeRailMetrics.heightDp(") ||
                warm.contains(".roundToInt()")
        )
    }

    @Test
    fun `the card's request carries that size instead of leaving it to layout`() {
        val screen = read(HOME_SCREEN)
        assertTrue(
            "Home reads the same function, off the same display metrics",
            screen.contains("private fun rememberRailArtPx(landscapeCards: Boolean): RailArtPx") &&
                screen.contains("LocalContext.current.resources.displayMetrics.density") &&
                screen.contains("HomeRailMetrics.artSizePx(landscapeCards = landscapeCards, density = density)")
        )
        assertTrue(
            "and every Home rail card hands it to the card",
            6 <= screen.countOccurrences("requestWidthPx = railArtPx.width")
        )
        listOf(POSTER_CARD to "PosterCard", LANDSCAPE_CARD to "LandscapeCard").forEach { (path, name) ->
            val card = read(path)
            assertTrue(
                "$name takes the request size",
                card.contains("requestWidthPx: Int = 0,") && card.contains("requestHeightPx: Int = 0")
            )
            assertTrue(
                "$name applies it to the request when given",
                card.contains("if (requestWidthPx > 0 && requestHeightPx > 0) {") &&
                    card.contains("size(requestWidthPx, requestHeightPx)")
            )
            assertTrue(
                "and the request is rebuilt when the size changes",
                card.contains("remember(") &&
                    card.contains("requestWidthPx, requestHeightPx) {")
            )
        }
    }

    // ── (b) A trim during a walk does not evict the visible set ───────────

    @Test
    fun `a critical trim is deferred while a walk is open`() {
        assertEquals(
            "the walk's own cards are the entries nothing has touched yet",
            ImageCacheRelease.NONE,
            ImageCacheTrim.releaseFor(
                backgrounded = false,
                critical = true,
                inWalkWindow = true
            )
        )
        assertEquals(
            "at rest it still trims, so the cap keeps doing its job",
            ImageCacheRelease.TRIM,
            ImageCacheTrim.releaseFor(
                backgrounded = false,
                critical = true,
                inWalkWindow = false
            )
        )
    }

    @Test
    fun `the background clear and the hint levels are untouched`() {
        assertEquals(
            "nothing on screen: the deep reclaim still runs, walk or no walk",
            ImageCacheRelease.CLEAR,
            ImageCacheTrim.releaseFor(
                backgrounded = true,
                critical = false,
                inWalkWindow = true
            )
        )
        assertEquals(
            ImageCacheRelease.CLEAR,
            ImageCacheTrim.releaseFor(
                backgrounded = true,
                critical = true,
                inWalkWindow = false
            )
        )
        assertEquals(
            "a level below critical was already a hint and still is",
            ImageCacheRelease.NONE,
            ImageCacheTrim.releaseFor(
                backgrounded = false,
                critical = false,
                inWalkWindow = false
            )
        )
    }

    @Test
    fun `the trim response is applied at the one delivery site`() {
        val app = read(APPLICATION)
        assertTrue(
            "the platform levels are mapped onto the rule, not re-branched",
            app.contains("ImageCacheTrim.releaseFor(") &&
                app.contains("backgrounded = level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND") &&
                app.contains("critical = level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL") &&
                app.contains("inWalkWindow = UiFrameTrace.inWalkWindow()")
        )
        assertTrue(
            "and the two releases happen only in the rule's arms",
            app.contains("ImageCacheRelease.CLEAR -> releaseImageMemoryCache(this)") &&
                app.contains("ImageCacheRelease.TRIM ->") &&
                app.contains("trimImageMemoryCache(this, ImageCacheTrim.SCROLL_FRACTION)")
        )
    }

    // ── (c) The walk does not decode hero art ahead ───────────────────────

    @Test
    fun `the walk-ahead resolves URLs but does not decode hero art mid-walk`() {
        val ahead = between(
            read(VIEW_MODEL),
            "private fun warmHeroWalkAhead(",
            "/**\n     * Warms the HERO bitmap of the card focus settled on"
        )
        assertTrue(
            "the walk window is the gate",
            ahead.contains("val walkOpen = UiFrameTrace.inWalkWindow()")
        )
        assertTrue(
            "and only a settled walk decodes the next card's bitmap",
            ahead.contains("val decodeNext = !walkOpen && heroNextBitmapInFlight.add(nextKey)")
        )
        assertTrue(
            "the skipped decode is counted, so the trace can prove it",
            ahead.contains("if (walkOpen) ImageLoadTrace.noteHeroWarmSkippedWalk()")
        )
        assertTrue(
            "the URLs still resolve - the settled warm must never wait on a lookup, " +
                "and a skipped decode moves its card into that list",
            ahead.contains("resolveHeroArtworkForPrefetch(candidate)") &&
                ahead.contains("val urlOnly = if (decodeNext) forward.drop(1) else forward")
        )
        assertTrue(
            "the settle warm itself is untouched: it is the card that draws",
            between(
                read(VIEW_MODEL),
                "private fun warmHeroLookahead(",
                "private fun warmRailTiles("
            ).contains("warmHeroArtBitmap(artwork)")
        )
    }

    @Test
    fun `the instruments the audit is measured with are untouched`() {
        val trace = read(IMAGE_TRACE)
        assertTrue(
            "the walk-scoped counts still print",
            trace.contains("scroll: heroDecodes=\$heroDecodes cardDiskDecodes=\$railDiskDecodes")
        )
        assertTrue(
            "and the warm's line still prints",
            trace.contains("hero: lookahead arrivals=") && trace.contains("skippedWalk=\$heroWarmSkippedWalk")
        )
        assertTrue(
            "no cache-cap raise: the cap line is the same arithmetic it was",
            !trace.contains("64MB") && !read(APPLICATION).contains("setMaxSize")
        )
    }

    // ── Source access ─────────────────────────────────────────────────────

    private fun between(text: String, start: String, end: String): String {
        val from = text.indexOf(start)
        assertTrue("marker missing: $start", from >= 0)
        val to = text.indexOf(end, from)
        assertTrue("marker missing after $start: $end", to > from)
        return text.substring(from, to)
    }

    private fun String.countOccurrences(needle: String): Int {
        var count = 0
        var at = indexOf(needle)
        while (at >= 0) {
            count++
            at = indexOf(needle, at + needle.length)
        }
        return count
    }

    private fun read(relative: String): String {
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
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private companion object {
        const val VIEW_MODEL = "com/kennyb1201/kbstream/ui/home/HomeViewModel.kt"
        const val HOME_SCREEN = "com/kennyb1201/kbstream/ui/home/HomeScreen.kt"
        const val POSTER_CARD = "com/kennyb1201/kbstream/ui/components/PosterCard.kt"
        const val LANDSCAPE_CARD = "com/kennyb1201/kbstream/ui/components/LandscapeCard.kt"
        const val APPLICATION = "com/kennyb1201/kbstream/MainApplication.kt"
        const val IMAGE_TRACE = "com/kennyb1201/kbstream/data/reporting/ImageLoadTrace.kt"
    }
}
