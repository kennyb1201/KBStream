package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hero warm timing (spec 2026-11-21 F3): resolve URLs ahead, decode one ahead.
 *
 * The 0.9.440 paste priced the wrong half: `arrivals=27 hits=3 misses=8
 * rate=27% skippedInFlight=8`, with the decode at `warmP50=287ms` and the art
 * URL lookup averaging 883ms (p95 2.4s). The warm was missing because it was
 * still resolving WHICH URL when focus reached the card.
 *
 * The rules pinned here: the near field's URLs are resolved at the focus event
 * and at rail paint, only the immediate next card is decoded, and the settled
 * card's dwell warm remains the backstop - without returning to the six-decodes-
 * per-step churn that filled a 48MB cache with art nothing drew.
 */
class HeroUrlAheadContractTest {

    private val vm by lazy { source(VIEW_MODEL) }

    @Test
    fun `the focus event resolves the near field before the settled warm`() {
        val focus = block(vm, "fun warmHeroFocus(")
        val walkAhead = focus.indexOf("warmHeroWalkAhead(item)")
        val settled = focus.indexOf("warmHeroLookahead(item)")

        assertTrue("the focus path must run the walk-ahead", walkAhead > 0)
        assertTrue("the settled dwell warm must stay as the backstop", settled > 0)
        assertTrue(
            "the walk-ahead starts before the debounced warm, so the next card " +
                "gets the whole step in front of its own dwell",
            walkAhead < settled
        )
    }

    @Test
    fun `only the immediate next card is decoded`() {
        val walk = block(vm, "private fun warmHeroWalkAhead(")
        assertTrue(
            "the window is bounded, not a field of neighbours",
            vm.contains("private const val HERO_WALK_URL_AHEAD = 3") &&
                walk.contains(".take(HERO_WALK_URL_AHEAD)")
        )
        assertTrue(
            "the immediate next card is the one decoded",
            walk.contains("val next = forward.first()") &&
                walk.indexOf("warmHeroArtBitmap(") > walk.indexOf("val next = forward.first()")
        )
        assertTrue(
            "exactly one bitmap decode: the rest of the window is URL resolution",
            walk.split("warmHeroArtBitmap(").size - 1 == 1
        )
        assertTrue(
            "and the rest of the window is URLs only",
            walk.contains("forward.drop(1)")
        )
    }

    @Test
    fun `the near field resolves URLs, never bitmap memory`() {
        val urls = block(vm, "private fun warmHeroArtUrls(")
        assertTrue(
            "the same resolver the hero and the backlog use, so the cache is shared",
            urls.contains("resolveHeroArtworkForPrefetch(")
        )
        assertFalse(
            "no bitmap decode at rail paint: the near field is a URL warm",
            urls.contains("warmHeroArtBitmap(") || urls.contains("warmHeroArtImages(")
        )
        assertTrue(
            "bounded to the head of the painted rows",
            vm.contains("private const val HERO_URL_RAIL_WINDOW = 4") &&
                vm.contains("private const val HERO_URL_WARM_LIMIT = 24") &&
                urls.contains(".take(HERO_URL_RAIL_WINDOW)") &&
                urls.contains(".take(HERO_URL_WARM_LIMIT)")
        )
    }

    @Test
    fun `the snapshot paints warm their own head cards`() {
        assertTrue(
            "the pinned snapshot is on screen before the network, so its URLs start then",
            vm.contains("warmHeroArtUrls(cachedPinnedRails)")
        )
        assertTrue(
            "same for the catalog snapshot",
            vm.contains("warmHeroArtUrls(cachedCatalogRails)")
        )
        assertTrue(
            "and a fresh rail warms its head when it publishes",
            vm.contains("warmHeroArtUrls(listOf(rail))")
        )
    }

    @Test
    fun `resolution and decode have separate permits`() {
        assertTrue(
            "the URL warm has its own bounded semaphore",
            vm.contains("private val heroUrlWarmSemaphore = Semaphore(permits = 2)")
        )
        assertTrue(
            "the decode permit stays the two-hero memory ceiling",
            vm.contains("private val heroArtPrefetchSemaphore = Semaphore(permits = 2)")
        )
        val walk = block(vm, "private fun warmHeroWalkAhead(")
        assertTrue(
            "a slow URL lookup must not hold a bitmap permit",
            walk.indexOf("heroArtPrefetchSemaphore.withPermit") >
                walk.indexOf("heroUrlWarmSemaphore.withPermit")
        )
    }

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
    }
}
