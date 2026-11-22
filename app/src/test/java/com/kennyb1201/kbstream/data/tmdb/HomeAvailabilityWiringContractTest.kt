package com.kennyb1201.kbstream.data.tmdb

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two halves of the 2026-11-21 Home-verdict fix, pinned where they live.
 *
 * Both are one-liners in a call path nothing else compiles against, and both are
 * invisible when removed - the pass still answers correctly, it just goes back to
 * costing 11 seconds and to putting the pinned snapshot behind the catalog
 * backlog. That is exactly the kind of change this project pins with a source
 * contract rather than trusting review to catch.
 *
 * The evidence behind them (0.9.422): `catalogFilter=11603ms` and
 * `pinnedVerdicts=10944ms` against `detailSrc=mem:390 disk:962 net:461` - 1,353
 * payload reads re-deriving answers the device already had, six at a time, with
 * the pinned leg's persisted result queued behind all of them.
 */
class HomeAvailabilityWiringContractTest {

    private val projectRoot: File by lazy { findProjectRoot() }

    private fun findProjectRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "app/src/main/java").isDirectory) return dir
            if (File(dir, "src/main/java").isDirectory) return dir.parentFile ?: dir
            dir = dir.parentFile
        }
        throw AssertionError("project root not found from " + System.getProperty("user.dir"))
    }

    private fun source(relative: String): String {
        val file = File(projectRoot, "app/src/main/java/$relative")
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    @Test
    fun `an answered verdict is written to the cache, not just the memo`() {
        val repo = source("com/kennyb1201/kbstream/data/tmdb/TmdbRepository.kt")

        assertTrue(
            "the memo alone dies with the process, which is why every rebuild " +
                "re-read the payloads",
            repo.contains("cacheJson(") &&
                repo.contains("json = HomeAvailabilityVerdict.encode(")
        )
        assertTrue(
            "and a verdict has to be READ back, or writing it changes nothing",
            repo.contains("HomeAvailabilityVerdict.decode(row, today.toEpochDay())")
        )
        assertTrue(
            "the lookup must be the persisted one, not the bare memo map",
            repo.contains("knownHomeAvailabilityVerdict(verdictKey, today)") &&
                !repo.contains("homeAvailabilityVerdicts[verdictKey]?.let")
        )
    }

    @Test
    fun `a verdict is shared under the resolved TMDB id`() {
        val repo = source("com/kennyb1201/kbstream/data/tmdb/TmdbRepository.kt")

        assertTrue(
            "the pre-fetch lookup must canonicalise the row's id flavor",
            repo.contains("canonicalVerdictKey(id(item), \"movie\") ?: id(item)")
        )
        assertTrue(
            "and the answer must be filed under the id the payload resolved to, " +
                "or a pinned tt... row and a catalog tmdb: row still pay twice",
            repo.contains("val resolvedKey =") &&
                repo.contains("?.let { tmdbId -> \"tmdb:\$tmdbId\" }")
        )
    }

    @Test
    fun `the pinned leg has its own permits`() {
        val repo = source("com/kennyb1201/kbstream/data/tmdb/TmdbRepository.kt")
        assertTrue(
            "six shared permits is what queued the persisted answer behind the catalog",
            repo.contains("private val pinnedAvailabilitySemaphore = Semaphore(permits = 3)")
        )
        assertTrue(
            "and the pass must actually choose it",
            repo.contains(
                "if (pinnedLeg) pinnedAvailabilitySemaphore else availabilitySemaphore"
            )
        )

        val home = source("com/kennyb1201/kbstream/ui/home/HomeViewModel.kt")
        assertTrue(
            "the pinned leg is identified by the label it already records",
            home.contains("val pinnedLeg = label == \"home.pinnedVerdicts\"")
        )
        assertTrue(
            "and that flag must reach the filter",
            home.contains("pinnedLeg = pinnedLeg")
        )
    }
}
