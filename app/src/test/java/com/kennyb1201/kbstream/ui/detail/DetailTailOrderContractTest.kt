package com.kennyb1201.kbstream.ui.detail

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A TMDB-backed series starts its episode list at step 3, not behind the add-on
 * meta probes.
 *
 * The 14:29 paste on 0.9.405 measured the page: `detailload: addons=0ms
 * tmdb=31ms firstPaint=32ms simkl=1ms load=3479ms tail=3447ms`, with
 * `http.addon.penguplay=2253ms` in the same block. The page was up in 32 ms and
 * the episode row stayed empty for the remaining 3.4 s, because the season call
 * sat after four META PROBES the load awaits - none of which the episode list
 * needs. Its inputs (TMDB season list, local watch state, resume row, tracker
 * sets) are all in hand at step 3.
 *
 * The synthetic fallback must stay where it is: a title TMDB has no record for
 * builds its episodes from the add-on meta's videos, which the probe produces.
 * Both halves are pinned here, because either one can regress silently - the
 * screen still works, it just waits.
 */
class DetailTailOrderContractTest {

    private companion object {
        const val DETAIL_VIEW_MODEL =
            "com/kennyb1201/kbstream/ui/detail/DetailViewModel.kt"

        const val KEYS_PUBLISHED = "publishWatchedEpisodeKeys(id, localCompletedEntries)"
        const val PROBES = "val addons = addonsDeferred.await()"
        const val SEASON_LOAD = "autoLoadRelevantSeason("
        const val SYNTHETIC_GUARD = "tmdbDetailResult.getOrNull() == null"
    }

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

    @Test
    fun `a TMDB-backed series starts its episodes before the add-on probes`() {
        val source = source(DETAIL_VIEW_MODEL)

        val keysPublished = source.indexOf(KEYS_PUBLISHED)
        val probes = source.indexOf(PROBES)

        assertTrue(
            "step 3 (publishWatchedEpisodeKeys) must still be in the load",
            keysPublished >= 0
        )
        assertTrue("the add-on meta probes must still be awaited", probes > keysPublished)

        val early = source.indexOf(SEASON_LOAD, keysPublished)

        assertTrue(
            "the season call must sit between step 3 and the add-on probes: it " +
                "ran AFTER them on 0.9.405, which is the 3.4 s the episode row " +
                "spent empty behind `http.addon.penguplay=2253ms`",
            early in keysPublished until probes
        )
        assertTrue(
            "the early call is for titles TMDB knows - a synthetic detail has " +
                "no season list yet",
            source.substring(keysPublished, early).contains("getOrNull() != null")
        )
    }

    @Test
    fun `the synthetic fallback still waits for the probe`() {
        val source = source(DETAIL_VIEW_MODEL)

        val probes = source.indexOf(PROBES)
        val last = source.lastIndexOf(SEASON_LOAD)

        assertTrue(
            "a title with no TMDB record builds its episode list from the " +
                "add-on meta's videos, so its season call must stay after the " +
                "probe that produces them",
            last > probes
        )

        val guardStart = source.lastIndexOf("if (normalizedType == \"series\"", last)
        val guard = source.substring(guardStart, last)

        assertTrue(
            "the late call must be guarded on the absence of a TMDB record, or " +
                "a TMDB-backed series loads its episodes twice",
            guardStart >= 0 && guard.contains(SYNTHETIC_GUARD)
        )
    }
}
