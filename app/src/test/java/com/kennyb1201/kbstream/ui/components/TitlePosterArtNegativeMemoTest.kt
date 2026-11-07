package com.kennyb1201.kbstream.ui.components

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The card-art memo's negative answers (Sentry ANDROID-1C).
 *
 * `TitlePosterArt.memo` caches what TMDB answered for a rail card that carries
 * no poster of its own, including "TMDB had nothing" - otherwise every art-less
 * card would re-ask on every scroll. The memo used to be a
 * `ConcurrentHashMap<String, String?>` and stored that miss as a null VALUE,
 * which a ConcurrentHashMap does not allow: its `put` throws
 * NullPointerException instead of storing one. So the first rail card whose
 * title TMDB could not resolve killed the process from inside the memo write
 * (0.9.183, Android 12 Smart TV, fatal, 1 user).
 *
 * The miss is now stored as [NO_POSTER] and translated back to null on read, so
 * the caching behaviour is unchanged and no write can be null.
 */
class TitlePosterArtNegativeMemoTest {

    private val source = File(
        findSourceRoot(),
        "com/kennyb1201/kbstream/ui/components/TitlePosterArt.kt"
    ).readText()

    private val encoding = File(
        findSourceRoot(),
        "com/kennyb1201/kbstream/ui/components/PosterMemo.kt"
    ).readText()

    // ── the crash itself ────────────────────────────────────────────────────

    @Test
    fun `a concurrent map rejects a null value, which is what the old memo did`() {
        // Pinned as the reason the sentinel exists: this is the exact throw the
        // Sentry event shows (ConcurrentHashMap.putVal from TitlePosterArt.resolve).
        val map = ConcurrentHashMap<String, String?>()

        try {
            map["movie:tmdb:1"] = null
            fail("a ConcurrentHashMap must never accept a null value")
        } catch (expected: NullPointerException) {
            assertEquals(0, map.size)
        }
    }

    // ── the fix ─────────────────────────────────────────────────────────────

    @Test
    fun `a miss is memoised as a value and still reads back as a miss`() {
        val stored = posterMemoValue(null)

        assertEquals("the miss must be a real value", NO_POSTER, stored)
        assertNull(
            "and still mean 'no poster' to the card, which keeps its title fallback",
            posterFromMemoValue(stored)
        )
    }

    @Test
    fun `a poster round-trips through the memo unchanged`() {
        val url = "https://image.tmdb.org/t/p/w500/abc.jpg"

        assertEquals(url, posterMemoValue(url))
        assertEquals(url, posterFromMemoValue(posterMemoValue(url)))
        assertNull("and a key that was never filed is not an answer", posterFromMemoValue(null))
    }

    @Test
    fun `a blank answer is a miss, not a URL`() {
        assertNull(posterFromMemoValue(""))
    }

    // ── the wiring ──────────────────────────────────────────────────────────

    @Test
    fun `the memo's value type is non-null and its only write encodes through the sentinel`() {
        assertTrue(
            "a nullable value type is the bug: ConcurrentHashMap would throw on every miss",
            source.contains("java.util.concurrent.ConcurrentHashMap<String, String>()")
        )
        assertTrue(
            "the write must go through the encoder",
            source.contains("memo[key] = posterMemoValue(poster)")
        )
        assertTrue(
            "and both reads must decode it",
            source.contains("memo[key]?.let { return posterFromMemoValue(it) }") &&
                source.contains("memo[key]?.let { return@withPermit posterFromMemoValue(it) }")
        )
        assertTrue(
            "the negative entry is what stops a re-ask on every scroll",
            encoding.contains("internal const val NO_POSTER = \"\"")
        )
    }

    @Test
    fun `the same null-value trap is guarded everywhere else it existed`() {
        val home = File(findSourceRoot(), "com/kennyb1201/kbstream/ui/home/HomeViewModel.kt")
            .readText()

        assertTrue(
            "the per-pass show detail cache is a ConcurrentHashMap<Int, TmdbDetail?>",
            home.contains("java.util.concurrent.ConcurrentHashMap<Int, TmdbDetail?>()")
        )
        assertTrue(
            "so a failed lookup may not be written to it either",
            home.contains("if (detail != null) showDetailCache[tmdbId] = detail")
        )
        assertTrue(
            "and nothing writes it unguarded",
            !home.contains("\n        showDetailCache[tmdbId] = detail\n")
        )
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
}
