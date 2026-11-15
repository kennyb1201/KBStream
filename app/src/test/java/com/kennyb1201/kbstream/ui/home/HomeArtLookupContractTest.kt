package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the landscape card's half-second went, and that a known-empty title is
 * not asked again (spec 2026-11-15, Home art F1/F5).
 *
 * The field paste carried `home.artLookup n=274 avg=493ms p95=1993ms max=3513ms`
 * and `home.artEmpty n=2 total=989ms`, which together cannot say whether the
 * cost is the six-permit queue, a network fetch, a disk parse after an eviction,
 * or the same empty answer being re-learned every session. Those want different
 * fixes, so the lookup is measured in its parts and the empties are remembered
 * across builds - both pinned here, from the source, because a suspend lookup on
 * a TV rail is not something a JVM test can run.
 *
 * [com.kennyb1201.kbstream.data.memory.LandscapeArtEmptyStoreTest] covers the
 * store's own rules.
 */
class HomeArtLookupContractTest {

    private val home by lazy { read("ui/home/HomeViewModel.kt") }
    private val repository by lazy { read("data/tmdb/TmdbRepository.kt") }
    private val diagnostics by lazy { read("data/reporting/Diagnostics.kt") }

    /** The one lookup every landscape card goes through. */
    private val art: String by lazy {
        home.substringAfter("private suspend fun homeLandscapeArt(")
            .substringBefore("return art\n    }")
    }

    // ── F1: the parts, timed apart ────────────────────────────────────────

    @Test
    fun `the semaphore queue is timed apart from the lookup behind it`() {
        assertFalse("the lookup must exist", art.isBlank())
        assertTrue(
            "F1: the wait is the gap between starting and holding a permit. A " +
                "rail of cards behind six permits is a different problem from a " +
                "slow fetch, and the average in the paste could not tell them apart",
            art.contains("var acquiredAtMs = startedAtMs") &&
                art.contains("acquiredAtMs = android.os.SystemClock.elapsedRealtime()") &&
                art.contains("PerfTrace.record(\"home.artWait\", acquiredAtMs - startedAtMs)")
        )
        assertTrue(
            "and the lookup's own cost still lands under home.artLookup, so the " +
                "field number keeps its meaning",
            art.contains("PerfTrace.record(\"home.artLookup\", elapsedMs)")
        )
    }

    @Test
    fun `each lookup says whether it was answered from memory, disk, the network or a shared fetch`() {
        assertTrue(
            "F1: the repository reports the branch that answered. A cache full at " +
                "150/150 says nothing about how often a card actually goes to the " +
                "network, which is what F3's decision needs",
            art.contains("onSource = { lookupSource = it }") &&
                art.contains("PerfTrace.recordCount(\"home.artSrc.\$lookupSource\", 1)")
        )
        listOf("mem", "disk", "net", "shared").forEach { source ->
            assertTrue(
                "the repository must name `$source` at the branch it answers from",
                repository.contains("onSource?.invoke(\"$source\")")
            )
        }
        assertTrue(
            "and the parameter is optional, so no other caller changes",
            repository.contains("onSource: ((String) -> Unit)? = null")
        )
        assertTrue(
            "the paste prints the four counts in one line, because the perf " +
                "summary ranks by total and would crowd them out",
            diagnostics.contains("PerfTrace.latestByPrefix(\"home.art\")") &&
                diagnostics.contains("\"homeart: \"") &&
                diagnostics.contains("src mem=\${PerfTrace.count(\"home.artSrc.mem\")}") &&
                diagnostics.contains("disk=\${PerfTrace.count(\"home.artSrc.disk\")}") &&
                diagnostics.contains("net=\${PerfTrace.count(\"home.artSrc.net\")}") &&
                diagnostics.contains("shared=\${PerfTrace.count(\"home.artSrc.shared\")}")
        )
    }

    // ── F5: an empty answer is learned once, not once per session ─────────

    @Test
    fun `a remembered empty answer is answered before anything queues`() {
        val store = art.indexOf("loadLandscapeEmptyAnswers()")
        val permit = art.indexOf("landscapeArtSemaphore.withPermit")
        assertTrue(
            "F5: the persisted check sits BEFORE the semaphore - the whole point " +
                "is never to queue for a lookup that comes back empty",
            store >= 0 && permit > store
        )
        assertTrue(
            "and it is its own recorded answer, so a paste can tell a learned " +
                "empty from this build's memo",
            art.contains("PerfTrace.record(\"home.artEmptyReuse\", 0L)")
        )
        assertTrue(
            "the memo is still filled from it, so the rest of the build is free too",
            art.contains("rememberLandscapeLookup(key, empty)")
        )
    }

    @Test
    fun `a fresh empty answer is written down for the next session`() {
        val emptyAt = art.indexOf("PerfTrace.record(\"home.artEmpty\", elapsedMs)")
        assertTrue("the empty answer is still recorded", emptyAt >= 0)
        assertTrue(
            "and remembered after it - with the store's own TTL, so a title that " +
                "gains a backdrop is asked again a fortnight later",
            art.indexOf("rememberLandscapeEmptyAnswer(key)", emptyAt) > emptyAt
        )
        assertTrue(
            "but only when the fetch ANSWERED: a network failure is not evidence " +
                "that the title has no art, and remembering it for a fortnight would " +
                "keep the backdrop off a card that has one",
            art.contains("if (fetched.isSuccess) rememberLandscapeEmptyAnswer(key)")
        )
        assertTrue(
            "the store is profile-scoped, like every other small home pref: one " +
                "profile's learned empties must not answer another's rails",
            home.contains("private const val PREFS_LANDSCAPE_EMPTY = \"landscape_art_empty\"") &&
                home.contains("ProfileStorage.prefsName(")
        )
    }

    // ── The constraints ───────────────────────────────────────────────────

    @Test
    fun `the addon fast-path stays out and the caps stay on`() {
        assertFalse(
            "the spec's documented constraint: the addon background is usually the " +
                "same primary backdrop the hero shows, so returning it early made " +
                "landscape cards mirror the hero. TMDB is always consulted",
            art.contains("addonBackdrop")
        )
        assertTrue(
            "the memo cap is unchanged (memory)",
            home.contains("LANDSCAPE_MEMO_MAX_KEYS = 2_000")
        )
        assertTrue(
            "and the semaphore is still the backstop, at six permits",
            home.contains("private val landscapeArtSemaphore = Semaphore(permits = 6)")
        )
        assertTrue(
            "the repository's detail cap is NOT raised here: F3's own spec says do " +
                "not raise it blind, and the two specs share the one change",
            repository.contains("private val MAX_DETAIL_ENTRIES = 150")
        )
    }

    private fun read(relative: String): String {
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
        throw AssertionError("source missing: $relative")
    }
}
