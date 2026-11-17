package com.kennyb1201.kbstream.data.imdb

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dig four §1a's two defects, both on the TV hero's scroll path.
 *
 * 1. The IMDb pre-warm fired on every hero-id change with no autoplay, no
 *    trailer-mode and no suppression check - and its miss path runs a WebView
 *    title-page load on `Dispatchers.Main.immediate`, budgeted at 18 s, i.e.
 *    real main-thread work per uncached title on a focus path that is not behind
 *    either hero dwell.
 * 2. The resolver had no in-flight de-duplication at all: only
 *    `synchronized(cache)` around the map, so two prefetches for the same title
 *    ran the full chain twice and - on the page leg - queued one WebView behind
 *    the other on the single gate permit.
 *
 * A play press deliberately keeps its own path: it must never wait on a prefetch
 * that is allowed to spend the page budget.
 */
class ImdbTrailerPrefetchCoalesceContractTest {

    private val home by lazy { source("com/kennyb1201/kbstream/ui/home/HomeScreen.kt") }
    private val resolver by lazy { source("com/kennyb1201/kbstream/data/imdb/ImdbTrailerResolver.kt") }

    @Test
    fun `the hero's IMDb pre-warm is gated like the YouTube one`() {
        val effect = home
            .substringAfter("// The IMDb pre-resolve, on its own effect")
            .substringBefore("LaunchedEffect(heroTrailerKey.value)")
        assertTrue(
            "autoplay and suppression are both checked before the resolve runs",
            effect.contains("if (!heroTrailerAutoplay || trailerSuppressed) return@LaunchedEffect")
        )
        assertTrue(
            "and they are effect keys, so enabling the setting mid-session warms again",
            effect.contains("heroTrailerAutoplay,") && effect.contains("trailerSuppressed")
        )
        assertFalse(
            "the old ungated key pair is gone: a focus change no longer fires a " +
                "resolve that could build a WebView on the main thread",
            effect.contains("LaunchedEffect(heroImdbId, heroTmdbDetail.value?.id) {")
        )
    }

    @Test
    fun `overlapping prefetches for one title run once`() {
        assertTrue(
            "an in-flight map keyed by the resolved IMDb id",
            resolver.contains("private val inFlight =") &&
                resolver.contains("CompletableDeferred<ImdbStreamSelector.Pick?>")
        )
        assertTrue(
            "the first caller owns the work, the rest await it",
            resolver.contains("val existing = inFlight.putIfAbsent(id, mine)") &&
                resolver.contains("if (existing != null) return existing.await()")
        )
        assertTrue(
            "the owner always clears its slot, success or failure",
            resolver.contains("} finally {\n            inFlight.remove(id, mine)\n        }")
        )
        assertTrue(
            "a cancelled owner tells awaiters 'nothing came back' instead of " +
                "cancelling their scopes",
            resolver.contains("mine.complete(null)\n            throw e")
        )
    }

    @Test
    fun `a play press never waits on a prefetch`() {
        assertTrue(
            "the non-page path goes straight to the lookup, no in-flight join",
            resolver.contains(
                "if (!allowPage) return resolveUncoalesced(id, allowPage = false)"
            )
        )
        assertTrue(
            "and the cache is still read first for both",
            resolver.indexOf("cached(id)?.let { entry -> return entry.pick }") <
                resolver.indexOf("val mine = CompletableDeferred")
        )
    }

    private fun source(relative: String): String {
        val file = File(mainRootPath(), relative)
        if (!file.isFile) throw AssertionError("source missing: ${file.absolutePath}")
        return file.readText()
    }

    private fun mainRootPath(): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate.absolutePath
            }
            dir = dir.parentFile
        }
        throw AssertionError("main source root not found")
    }
}
