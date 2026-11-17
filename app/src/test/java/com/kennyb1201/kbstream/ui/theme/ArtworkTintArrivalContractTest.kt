package com.kennyb1201.kbstream.ui.theme

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dynamic colour fix 2026-11-17: the tint resolves when the art arrives.
 *
 * The two surfaces' reports had one cause - the sampler kept asking inside a fixed
 * 1.5 s window and never again - so the fix is a trigger, and a trigger is wiring.
 * What is pinned here is the wiring: the loader reports a disk arrival, the tint
 * listens for its own URL and samples once more, a timing miss is invalidated by
 * that arrival, and the gradient lets a held colour go once the answer is in.
 *
 * The behaviour itself (S1-S5) is Kenny's on the TV and the phone; this class
 * claims no frame.
 */
class ArtworkTintArrivalContractTest {

    private val tintSource by lazy { source("com/kennyb1201/kbstream/ui/theme/ArtworkTint.kt") }
    private val ledgerSource by lazy {
        source("com/kennyb1201/kbstream/ui/theme/ArtworkTintArrivalLedger.kt")
    }
    private val application by lazy { source("com/kennyb1201/kbstream/MainApplication.kt") }
    private val detail by lazy { source("com/kennyb1201/kbstream/ui/mobile/DetailScreenMobile.kt") }
    private val home by lazy { source("com/kennyb1201/kbstream/ui/home/HomeScreen.kt") }

    // ── F1: the sample is triggered by the art landing ────────────────────

    @Test
    fun `the image loader reports art that reached the disk`() {
        val listener = application
            .substringAfter("object : coil3.EventListener()")
            .substringBefore(".build()")
        assertTrue(
            "the success path feeds the tint's arrival signal",
            listener.contains("ArtworkTintArrivals.noteLoaded(")
        )
        assertTrue(
            "a memory hit is not an arrival: the file was already there",
            listener.contains("result.dataSource != coil3.decode.DataSource.MEMORY_CACHE")
        )
        assertTrue(
            "and the URL the sampler will ask for is the request's own data",
            listener.contains("request.data as? String")
        )
    }

    @Test
    fun `the tint re-samples its own url when that art lands`() {
        assertTrue(
            "the signal carries the URL",
            tintSource.contains("MutableSharedFlow<String>(") &&
                tintSource.contains("val landed: SharedFlow<String> = _landed")
        )
        val resolver = tintSource
            .substringAfter("internal fun rememberArtworkTintState(")
            .substringBefore("internal object ArtworkTintArrivals")
        assertTrue(
            "the tint listens to it, filtered to the URL it drew",
            resolver.contains("ArtworkTintArrivals.landed") &&
                resolver.contains(".filter { arrived -> arrived == key }")
        )
        assertTrue(
            "and samples again on arrival",
            resolver.contains("sampleFromCache(context, key)")
        )
        assertTrue(
            "a hit short-circuits the arrival",
            resolver.contains("ArtworkTintCache.get(key) != null) return@collect")
        )
    }

    @Test
    fun `both surfaces take the tint through the arrival-aware resolver`() {
        assertTrue(
            "phone Detail, from the title's own backdrop",
            detail.contains("rememberArtworkTintState(backdropUrl)")
        )
        assertTrue(
            "TV Spotlight, from the focused card's art",
            home.contains("rememberArtworkTintState(") &&
                home.contains("focusedItemState.value?.background")
        )
    }

    // ── F2: a timing miss is not an answer ────────────────────────────────

    @Test
    fun `an arrival voids a miss recorded while the art was downloading`() {
        assertTrue(
            "the cache can forget a miss",
            tintSource.contains("fun invalidateMiss(url: String)")
        )
        assertTrue(
            "and the arrival does it before anything else",
            tintSource
                .substringAfter("fun noteLoaded(url: String?)")
                .contains("ArtworkTintCache.invalidateMiss(key)")
        )
        // A miss recorded after the art already landed stays an answer: the file
        // was there and the artwork has no usable colour (S4).
        assertTrue(
            "the window's null is only an answer when the art had already landed",
            tintSource.contains("if (ArtworkTintArrivalLedger.hasLanded(key)) resolved = true")
        )
    }

    @Test
    fun `the sampler never treats its own load as an arrival`() {
        assertTrue(
            "the probe is marked for its whole life",
            tintSource.contains("ArtworkTintArrivalLedger.beginProbe(url)") &&
                tintSource.contains("ArtworkTintArrivalLedger.endProbe(url)")
        )
        assertTrue(
            "and the ledger drops it",
            ledgerSource.contains("if ((probes[key] ?: 0) > 0) return false")
        )
    }

    // ── F3: the hold is transient ─────────────────────────────────────────

    @Test
    fun `a held tint is released once the answer is in`() {
        assertTrue(
            "the strip still holds while the tint is unknown",
            tintSource.contains("val shown = tint ?: if (resolved) null else held")
        )
        assertTrue(
            "and the resolved flag is part of the gradient's contract",
            tintSource.contains("resolved: Boolean = true")
        )
        assertTrue(
            "phone Detail passes it",
            detail.contains("resolved = backdropTint.resolved")
        )
        assertTrue(
            "TV Spotlight passes it",
            home.contains("resolved = spotlightTint.resolved")
        )
    }

    // ── F4/F5: the gates that must not move ───────────────────────────────

    @Test
    fun `the sample still reads the cache only`() {
        val sample = tintSource
            .substringAfter("internal fun sampleFromCache(")
            .substringBefore("/** Samples a grid of pixels")
        assertTrue(sample.contains("memoryCachePolicy(CachePolicy.READ_ONLY)"))
        assertTrue(sample.contains("diskCachePolicy(CachePolicy.READ_ONLY)"))
        assertTrue(sample.contains("networkCachePolicy(CachePolicy.DISABLED)"))
        assertTrue(sample.contains("allowHardware(false)"))
        assertFalse(
            "and nothing in the arrival path may fetch either",
            tintSource.substringAfter("internal object ArtworkTintArrivals")
                .contains("networkCachePolicy(CachePolicy.ENABLED)")
        )
    }

    @Test
    fun `the billboard gate is untouched`() {
        val strip = home
            .substringAfter("private fun SpotlightTopTint(")
            .substringBefore("private fun SpotlightHeroFocusDriver(")
        assertTrue(
            "the strip still waits for the hero to scroll away",
            strip.contains("visible = heroScrolledAway") &&
                strip.contains("SPOTLIGHT_HERO_ITEM_KEY")
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun source(relative: String): String {
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
        throw AssertionError("main source root not found from " + System.getProperty("user.dir"))
    }
}
