package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A cold Home load's 20 seconds can be attributed.
 *
 * The build-390 paste (2026-11-19) carried `homeload: … catalogRails=19938ms
 * railsReady=19961ms` beside `addon.catalog n=2 total=530ms` and
 * `home.artLookup n=9 total=9629ms`. Those numbers do not add up to the 20
 * seconds, so most of a cold load was attributed to nothing at all - and a
 * figure no fix can be aimed at is worse than no figure, because it reads like
 * progress is being made.
 *
 * The split is three records around work that already existed:
 *
 *  - `home.catalogFetch` - the addon fetch, which queues behind
 *    [HomeViewModel]'s catalog semaphore, recorded even when it fails or returns
 *    nothing (a rail that never appears is the case a fetch timer has to cover);
 *  - `home.catalogFilter` - the kids-mode / upcoming / availability pass, which
 *    is one TMDB verdict per row rather than a pure in-memory filter;
 *  - `home.catalogFan` and `home.catalogAwaitPinned` - where the fan-out ends and
 *    where the pinned batch's await begins, because `home.catalogRails` used to
 *    swallow the pinned refine whole (`pinnedRefined=12462ms`).
 *
 * `home.catalogRails` stays the total, so every older paste compares.
 */
class CatalogRailSplitContractTest {

    private val vm = source(VIEW_MODEL)

    @Test
    fun `one catalog row is a fetch and a filter, timed apart`() {
        val fetch = vm.substringAfter("val fetchStartedAtMs =")
            .substringBefore("return rail")
        assertTrue(
            "the addon fetch is timed from before it is attempted",
            fetch.contains("home.catalogFetch")
        )
        assertTrue(
            "and the record is after the failure branch, so a rail that never " +
                "appears is still counted as a fetch that happened",
            fetch.indexOf("home.catalogFetch") > fetch.indexOf("failedAddonsThisBuild.add")
        )
        assertFalse(
            "a failed fetch must not skip the timer on its way out",
            fetch.substringBefore("home.catalogFetch").contains("return null")
        )
        assertTrue(
            "the filter pass is timed on the other side of the fetch",
            fetch.contains("val filtered =") && fetch.contains("home.catalogFilter")
        )
        assertTrue(
            "and it covers the kids verdict, which is the call in it",
            fetch.indexOf("home.catalogFilter") > fetch.indexOf("kidsFilterMetas")
        )
    }

    @Test
    fun `the fan-out ends before the pinned await, and the total is still recorded`() {
        val fan = vm.substringAfter("PerfTrace.record(\n                        \"home.catalogFan\",")
            .substringBefore("PerfTrace.record(\n                    \"home.catalogRails\",")
        assertTrue(
            "the fan is timed to the last catalog row, not to the pinned batch",
            fan.isNotEmpty() && fan.contains("pinned = pinnedDeferred.await()")
        )
        assertTrue(
            "and the await has its own record after it",
            fan.contains("home.catalogAwaitPinned")
        )
        assertTrue(
            "the await is entered after the fan's record, or the two numbers " +
                "would be the same thing twice",
            fan.indexOf("pinned = pinnedDeferred.await()") <
                fan.indexOf("home.catalogAwaitPinned")
        )
        assertTrue(
            "the total stays for every paste that already reads it",
            vm.contains("\"home.catalogRails\"")
        )
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
