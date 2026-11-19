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
            "the row publishes on the local filters only",
            fetch.contains("val distinct =") &&
                fetch.contains("filterUpcoming(metas)")
        )
        assertFalse(
            "and NOT on the verdicts: the availability check is one TMDB detail " +
                "fetch per movie and measured 27757ms inside a 28347ms cold load, " +
                "while the first row was on screen in 409ms",
            fetch.contains("applyDigitalAvailabilityFilter") ||
                fetch.contains("kidsFilterMetas")
        )
    }

    @Test
    fun `the verdicts land behind the row, and only a pass that hid something rewrites it`() {
        val behind = vm.substringAfter("private fun filterCatalogRowBehind(")
            .substringBefore("private suspend fun fetchCatalogThrottled(")

        assertTrue(
            "the pass is the same chain - nothing it decides is weakened, only " +
                "its position moved",
            behind.contains("applyDigitalAvailabilityFilter(") &&
                behind.contains("kidsFilterMetas(")
        )
        assertTrue(
            "its cost is still recorded under the same label, so the next paste " +
                "says what the verdicts cost even though they no longer gate the row",
            behind.contains("home.catalogFilter")
        )
        assertTrue(
            "a pass that resolves for the profile the viewer has left never lands",
            behind.indexOf("buildEpoch != railBuildEpoch") in
                0 until behind.indexOf("_rails.update")
        )
        assertTrue(
            "the ids it hid are recorded BEFORE the screen is touched: the build " +
                "streams this row as it resolves and then replaces the whole list, " +
                "so a verdict that landed mid-build would otherwise be undone by " +
                "the build's own last write",
            behind.indexOf("catalogHiddenIds.compute(railKey)") in
                0 until behind.indexOf("_rails.update") &&
                behind.contains("withCatalogVerdicts(railKey, rail.items)")
        )
        assertTrue(
            "and both publish paths re-apply them - a rebuilt row is filtered " +
                "before it is drawn, and the final list is filtered again",
            vm.contains("val visible = withCatalogVerdicts(railKey, distinct)") &&
                vm.contains("val items = withCatalogVerdicts(railKeyOf(rail), rail.items)")
        )
        assertTrue(
            "a row every title was filtered out of is removed here and dropped " +
                "from the final list, exactly as the blocking version never " +
                "published it",
            behind.contains("current.filterNot { rail -> railKeyOf(rail) == railKey }") &&
                vm.contains("if (items.isEmpty()) null else rail.copy(items = items)")
        )
        assertTrue(
            "and nothing is recorded or rewritten when nothing was hidden",
            behind.contains("if (hidden.isEmpty()) return@launch")
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
