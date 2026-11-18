package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The billboard's digital-release filter (spec 2026-11-17).
 *
 * The filter already runs on the rails, the KB folders and add-on search, but
 * never on the billboard: its gate applied ONLY the kids ceiling, and the
 * trending walk's source also applies only the ceiling. So with the Settings
 * switch ON an unreleased movie could be dealt straight onto the hero.
 *
 * The fix is one call at the same choke point as the ceiling - the pool job in
 * [HomeViewModel.resolveBillboard] - so the chosen feed, the walked default
 * fallback and the kids rails are all filtered before a page is dealt, and the
 * shared implementation (with its switch, its series rule, its unknown rule and
 * its cache/throttle) is the only rule in play.
 */
class BillboardDigitalReleaseFilterContractTest {

    // ── F1/F2: one gate, on the ceiling-passed pool, before the dealer ─────

    @Test
    fun `the resolve filters the ceiling-passed pool before any page is dealt`() {
        val resolve = resolve()
        assertTrue(
            "the ceiling pass is still the first thing the pool job does",
            resolve.contains("val pool = billboardKidsSafePool(items)")
        )
        assertTrue(
            "the digital filter runs on the CEILING-PASSED pool, and the dealer " +
                "is handed its result - not the raw feed and not the ceiling's own pool",
            resolve.contains("val available = applyDigitalAvailabilityFilter(pool)") &&
                resolve.contains("publishBillboardPool(available, forProfile)") &&
                !resolve.contains("publishBillboardPool(pool, forProfile)")
        )
        assertTrue(
            "and it runs after the ceiling pass, before the deal",
            resolve.indexOf("billboardKidsSafePool(items)") <
                resolve.indexOf("applyDigitalAvailabilityFilter(pool)") &&
                resolve.indexOf("applyDigitalAvailabilityFilter(pool)") <
                resolve.indexOf("publishBillboardPool(")
        )
        assertFalse(
            "the supplier's raw list never reaches the dealer from the pool job",
            resolve.contains("publishBillboardPool(items")
        )
    }

    @Test
    fun `the filter lands in the same hop and under the same epoch guard`() {
        val resolve = resolve()
        assertTrue(
            "no new job: the filter shares the pool job",
            resolve.contains("billboardPoolJob = viewModelScope.launch {")
        )
        assertTrue(
            "the epoch is checked again AFTER the filter, so a filter that lands " +
                "after a profile switch is dropped exactly like a resolve would be",
            resolve.split("if (epoch != billboardResolveEpoch) return@launch").size - 1 >= 2 &&
                resolve.indexOf("applyDigitalAvailabilityFilter(pool)") <
                resolve.lastIndexOf("if (epoch != billboardResolveEpoch) return@launch") &&
                resolve.lastIndexOf("if (epoch != billboardResolveEpoch) return@launch") <
                resolve.indexOf("publishBillboardPool(available")
        )
        assertTrue(
            "the pool the filter sees is the one the ceiling/fallback pass returned, " +
                "so every rung of the fallback is filtered too",
            resolve.contains("val pool = billboardKidsSafePool(items)") &&
                resolve.contains("applyDigitalAvailabilityFilter(pool)")
        )
    }

    // ── F3: the toggle is the shared implementation's, unchanged ──────────

    @Test
    fun `the toggle is the shared implementation's, not a billboard setting`() {
        val impl = filterImpl()
        assertTrue(
            "off returns the list untouched - the Settings switch gates the shared " +
                "implementation itself",
            impl.contains("if (items.isEmpty() || !isDigitalFilterEnabled()) return items")
        )
        assertTrue(
            "and that switch is still the one Home already reads",
            source(TMDB).contains(
                "fun isDigitalFilterEnabled(): Boolean =\n" +
                    "        AppPreferences.getHomeRailHideUpcoming(appContext)"
            )
        )
        assertFalse(
            "no second gate, no new billboard setting: the resolve does not read " +
                "the switch itself",
            resolve().contains("isDigitalFilterEnabled")
        )
    }

    // ── F4: series pass, unknowns are kept ────────────────────────────────

    @Test
    fun `series pass and an unknown release status keeps the title`() {
        val impl = filterImpl()
        assertTrue(
            "only movies are verified; a series is returned before any lookup",
            impl.contains("if (!type(item).equals(\"movie\", ignoreCase = true)) {") &&
                impl.contains("return@async item")
        )
        assertTrue(
            "only a definite `not available` drops the title - null keeps it",
            impl.contains("false -> null") && impl.contains("else -> item")
        )
        assertTrue(
            "and the verdict itself is nullable, so an unresolved title is kept",
            impl.contains("?.isAvailableAtHome(today)") &&
                impl.contains(".getOrNull()")
        )
    }

    // ── F5: no stampede ───────────────────────────────────────────────────

    @Test
    fun `the lookups stay on the shared cache and throttle`() {
        val impl = filterImpl()
        assertTrue(
            "every verified title takes the shared availability semaphore",
            impl.contains("availabilitySemaphore.withPermit {")
        )
        assertTrue(
            "and the enriched-detail cache is what it resolves through (12h/30d)",
            impl.contains("fetchEnrichedMetaCached(") &&
                source(TMDB).contains("imdbId = \"tmdb:\$tmdbId\"")
        )
    }

    // ── F6: everything else keeps its own rule ────────────────────────────

    @Test
    fun `the walk, the rails and the hero widgets keep their own rules`() {
        val walk = source(TMDB)
            .substringAfter("suspend fun trendingDayPage(mediaType: String, page: Int = 1)")
            .substringBefore("data class TrendingDayPage")
        assertFalse(
            "the walk's source is untouched: the filter lives at the billboard's gate, " +
                "so its kept counts stay the ceiling's evidence",
            walk.contains("isAvailableAtHome") || walk.contains("filterByHomeAvailability")
        )
        assertTrue(
            "the rail loader still applies the ceiling AND the digital filter, once",
            source(TMDB).contains("The app-wide digital-release filter runs here too")
        )
        assertFalse(
            "the shared MobileBillboard adds no filter of its own - phone and TV " +
                "Spotlight both get this via the resolve",
            source(PHONE).contains("filterByHomeAvailability")
        )
        listOf(PHONE to "phone", SPOTLIGHT to "TV").forEach { (path, surface) ->
            assertTrue(
                "the $surface billboard still resolves through the one gate",
                source(path).contains("viewModel.resolveBillboard(")
            )
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /** [HomeViewModel.resolveBillboard]'s body, up to the ceiling helper. */
    private fun resolve(): String =
        source(HOME_VM)
            .substringAfter("fun resolveBillboard(items: List<MetaPreview>)")
            .substringBefore("private suspend fun billboardKidsSafePool(")

    /** The shared implementation the rails, folders, search and billboard use. */
    private fun filterImpl(): String =
        source(TMDB)
            .substringAfter("suspend fun <T> filterByHomeAvailabilityById(")
            .substringBefore("// Kids Mode enforcement. Every discover-backed rail")

    private fun source(relative: String): String {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) {
                    val file = File(candidate, relative)
                    assertTrue("source missing: $file", file.isFile)
                    return file.readText()
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private companion object {
        const val HOME_VM = "com/kennyb1201/kbstream/ui/home/HomeViewModel.kt"
        const val TMDB = "com/kennyb1201/kbstream/data/tmdb/TmdbRepository.kt"
        const val PHONE = "com/kennyb1201/kbstream/ui/mobile/HomeScreenMobile.kt"
        const val SPOTLIGHT = "com/kennyb1201/kbstream/ui/home/SpotlightHome.kt"
    }
}
