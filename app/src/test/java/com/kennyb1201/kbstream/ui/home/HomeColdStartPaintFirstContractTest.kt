package com.kennyb1201.kbstream.ui.home

import com.kennyb1201.kbstream.data.addon.MetaPreview
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Home's cold start: paint the pinned rails from cache, refine in place, and warm
 * their first pages at launch (spec 2026-11-15).
 *
 * The field this answers: a cold TCL on 0.9.329 reported
 * `homeload: pinned=35490ms firstRail=35492ms catalogRails=37762ms
 * railsReady=37764ms`. The pinned batch resolved over the network, the catalog
 * rails waited on it before publishing, and rails-ready waited for both.
 *
 * These cases pin the WIRING, because the failure mode is a silent regression to
 * the old shape - one `pinnedDeferred.await()` before the first catalog row puts
 * 35 seconds back on a cold Home and nothing in review looks wrong. The merge
 * cases pin what a viewer sees: same slots, same order, no reshuffle.
 */
class HomeColdStartPaintFirstContractTest {

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

    private companion object {
        const val VIEW_MODEL = "com/kennyb1201/kbstream/ui/home/HomeViewModel.kt"
        const val SNAPSHOT = "com/kennyb1201/kbstream/ui/home/HomePinnedSnapshot.kt"
        const val CATALOG_SNAPSHOT =
            "com/kennyb1201/kbstream/ui/home/HomeCatalogSnapshot.kt"
        const val WARM = "com/kennyb1201/kbstream/data/home/HomePinnedWarm.kt"
        const val SPEC = "com/kennyb1201/kbstream/data/home/PinnedRailsSpec.kt"
        const val APPLICATION = "com/kennyb1201/kbstream/MainApplication.kt"
        const val TMDB = "com/kennyb1201/kbstream/data/tmdb/TmdbRepository.kt"
    }

    // ── F1: paint from cache, refine in place ─────────────────────────────

    @Test
    fun `the cached pinned batch is published before the pinned load is launched`() {
        val vm = source(VIEW_MODEL)
        val load = vm.indexOf("HomePinnedSnapshot.load(")
        val publish = vm.indexOf("publishPinnedInPlace(")
        val deferred = vm.indexOf("val pinnedDeferred =")

        assertTrue("the build must read the snapshot", load > 0)
        assertTrue("the cached batch must be published", publish > 0)
        assertTrue("the pinned load must still exist", deferred > 0)
        assertTrue(
            "the paint must happen before the network batch starts",
            load < deferred && publish < deferred
        )
    }

    @Test
    fun `home pinned is recorded from the cached publish, not from the network`() {
        val vm = source(VIEW_MODEL)
        val start = vm.indexOf("val cachedPinnedRails =")
        val deferred = vm.indexOf("val pinnedDeferred =")
        assertTrue(start > 0 && deferred > start)

        val paintFirst = vm.substring(start, deferred)
        assertTrue(
            "the cached publish is what home.pinned must measure",
            paintFirst.contains("\"home.pinned\"")
        )
        // ...and the deepened landing gets its own sample.
        val refine = vm.substring(deferred)
        assertTrue(
            "the refined batch must be recorded as home.pinnedRefined",
            refine.contains("\"home.pinnedRefined\"")
        )
        assertTrue(
            "the refined batch must be saved as the next cold start's cache",
            refine.contains("HomePinnedSnapshot.save(")
        )
    }

    @Test
    fun `no catalog rail waits on the pinned batch any more`() {
        val vm = source(VIEW_MODEL)
        assertEquals(
            "the only await on the pinned batch is the one that collects it for " +
                "the final publish; a second one is the 35-second cold start",
            1,
            vm.windowed("pinnedDeferred.await()".length)
                .count { it == "pinnedDeferred.await()" }
        )
        assertTrue(
            "the pinned batch is still collected for the final publish",
            vm.contains("pinned = pinnedDeferred.await()")
        )
    }

    @Test
    fun `the snapshot scope separates profiles and their ceilings`() {
        val snapshot = source(SNAPSHOT)
        assertTrue(
            "the scope key must include the kids ceiling",
            snapshot.contains("kids=\$kidsCeiling")
        )
        val vm = source(VIEW_MODEL)
        assertTrue(
            "the build must scope the snapshot by the active profile's ceiling",
            vm.contains("kidsCeiling = tmdbRepository.kidsMaxAge() != null")
        )
        assertTrue(
            "the build must scope the snapshot by the active profile",
            vm.contains("profileId = profileIdAtStart.orEmpty()")
        )
    }

    // ── F2: catalog SWR ────────────────────────────────────────────────

    @Test
    fun `the catalog snapshot is painted before the first catalog request`() {
        val vm = source(VIEW_MODEL)
        val load = vm.indexOf("HomeCatalogSnapshot.load(")
        val paint = vm.indexOf("publishCatalogSnapshotInPlace(")
        val fanOut = vm.indexOf("pendingCatalogs\n                        .map { pending ->")

        assertTrue("the build must read the catalog snapshot", load > 0)
        assertTrue("the snapshot must be published", paint > load)
        assertTrue("the live fan-out must still exist", fanOut > 0)
        assertTrue(
            "the snapshot must paint before a single catalog fetch is launched",
            fanOut > paint
        )
    }

    @Test
    fun `the catalog snapshot is scoped like the pinned one`() {
        val vm = source(VIEW_MODEL)
        assertTrue(
            "the catalog snapshot must be per profile + ceiling",
            vm.contains(
                "HomeCatalogSnapshot.scopeOf(\n                        profileId = profileIdAtStart.orEmpty(),\n                        kidsCeiling = tmdbRepository.kidsMaxAge() != null"
            )
        )
        val snapshot = source(CATALOG_SNAPSHOT)
        assertTrue(
            "the scope key must include the kids ceiling",
            snapshot.contains("kids=\$kidsCeiling")
        )
    }

    @Test
    fun `a failed rail keeps its snapshot row and the snapshot is not rewritten`() {
        val vm = source(VIEW_MODEL)
        assertTrue(
            "a rail whose refresh failed must keep its snapshot version",
            vm.contains("snapshotOnlyRails")
        )
        assertTrue(
            "a failed fetch makes the fresh set incomplete",
            vm.contains("catalogFetchFailures.get() == 0")
        )
        assertTrue(
            "the snapshot is only written after its verdicts answer",
            vm.contains("catalogVerdictJobs.toList().forEach { job -> job.join() }")
        )
        assertTrue(
            "and only from a completed fresh set",
            vm.indexOf("HomeCatalogSnapshot.save(") > vm.indexOf("catalogVerdictJobs.toList()")
        )
    }

    @Test
    fun `both publishes are guarded by the build epoch`() {
        val vm = source(VIEW_MODEL)
        val helper = vm.substringAfter("private fun publishPinnedInPlace(")
            .substringBefore("\n    private fun ")
        assertTrue(
            "a stale profile's pinned batch must never stream back in",
            helper.contains("if (buildEpochAtStart != railBuildEpoch) return")
        )
        val refine = vm.substringAfter("\"home.pinnedRefined\"")
        assertFalse("the refine block must exist", refine.isEmpty())
        assertTrue(
            "the refined batch is only published and saved when the build still " +
                "owns the active profile",
            vm.contains("if (buildEpochAtStart == railBuildEpoch) {") &&
                vm.contains("publishPinnedInPlace(\n                                    pinnedRails = rails")
        )
    }

    // ── F2: the launch warm ───────────────────────────────────────────────

    @Test
    fun `the launch warm runs as its own startup phase`() {
        val app = source(APPLICATION)
        assertTrue(
            "the warm must be its own trace label beside startup.catalogWarm",
            app.contains("PerfTrace.timedSuspend(\"startup.pinnedWarm\")")
        )
        assertTrue(
            "the warm must be a nested launch, never a step in the chain",
            app.contains("startupScope.launch {\n                runCatchingCancellable {\n                    PerfTrace.timedSuspend(\"startup.pinnedWarm\")")
        )
        assertTrue(
            "the catalog warm must still be there",
            app.contains("PerfTrace.timedSuspend(\"startup.catalogWarm\")")
        )
    }

    @Test
    fun `the warm asks for the build's own queries, first page only`() {
        val warm = source(WARM)
        assertTrue(
            "the warm must read the shared feed spec, not a copy of it",
            warm.contains("PinnedRailsSpec.catalogs") && warm.contains("PinnedRailsSpec.baseUrl")
        )
        assertTrue(
            "the warm must go through the repository call the build makes",
            warm.contains("repository.getCatalog(")
        )
        assertTrue(
            "the viewer's own rails must be warmed with page 1",
            warm.contains("page = 1")
        )
        assertFalse(
            "the warm must never page beyond the first page",
            warm.contains("page = 2") || warm.contains("page = page")
        )
        assertFalse(
            "the warm must never touch MDBList",
            warm.contains("MdbList") || warm.contains("mdbList")
        )
        assertFalse(
            "the warm must never touch the trackers",
            warm.contains("TraktRepository") || warm.contains("SimklRepository")
        )
    }

    @Test
    fun `the pinned feed is stated once`() {
        val spec = source(SPEC)
        val vm = source(VIEW_MODEL)
        assertTrue(spec.contains("top_movies_today") && spec.contains("top_shows_today"))
        assertFalse(
            "the feed URL must not be duplicated in the ViewModel",
            vm.contains("toptoday.llamayu.com")
        )
    }

    @Test
    fun `the discover page cache stores raw pages, filtered per call`() {
        val tmdb = source(TMDB)
        val cache = tmdb.indexOf("cachedDiscoverPage(")
        val ceiling = tmdb.indexOf("kidsFilterItems(", cache)
        assertTrue("the discover cache must exist", cache > 0)
        assertTrue(
            "the ceiling filter must still run on every discover call, after the cache",
            ceiling > cache
        )
        assertTrue(
            "a failure must never be cached",
            tmdb.substring(cache).contains("?: return null")
        )
    }

    // ── The merge: same slots, same order, no reshuffle ───────────────────

    private fun rail(key: String, itemId: String = key) = Rail(
        addonName = "TMDB Top Today",
        catalogName = "Rail $key",
        type = "movie",
        items = listOf(MetaPreview(id = itemId, type = "movie", name = itemId)),
        catalogId = key
    )

    private val keyOf: (Rail) -> String = { rail -> rail.catalogId ?: "" }

    @Test
    fun `a refine replaces its own slots and leaves the catalog rails where they are`() {
        val current = listOf(
            rail("pinned_a", "cached"),
            rail("pinned_b", "cached"),
            rail("catalog_1"),
            rail("catalog_2")
        )
        val refined = listOf(rail("pinned_a", "fresh"), rail("pinned_b", "fresh"), rail("pinned_c", "fresh"))

        val merged = PinnedRailMerge.merge(
            current = current,
            pinned = refined,
            replacing = setOf("pinned_a", "pinned_b"),
            keyOf = keyOf
        )

        assertEquals(
            "the pinned rows lead, in their own order, then the catalogs untouched",
            listOf("pinned_a", "pinned_b", "pinned_c", "catalog_1", "catalog_2"),
            merged.map { it.catalogId }
        )
        assertEquals(
            "the refined items replace the cached ones",
            "fresh",
            merged.first { it.catalogId == "pinned_a" }.items.single().id
        )
    }

    @Test
    fun `a rail that disappears from the refined batch stops being drawn`() {
        val merged = PinnedRailMerge.merge(
            current = listOf(rail("pinned_a"), rail("pinned_gone"), rail("catalog_1")),
            pinned = listOf(rail("pinned_a")),
            replacing = setOf("pinned_a", "pinned_gone"),
            keyOf = keyOf
        )

        assertEquals(
            listOf("pinned_a", "catalog_1"),
            merged.map { it.catalogId }
        )
    }

    @Test
    fun `an empty publish changes nothing`() {
        val current = listOf(rail("catalog_1"))
        assertEquals(
            "a batch that resolves to nothing must not blank the screen",
            current,
            PinnedRailMerge.merge(current = current, pinned = emptyList(), keyOf = keyOf)
        )
    }

    @Test
    fun `a rail already on screen is replaced, not duplicated`() {
        val merged = PinnedRailMerge.merge(
            current = listOf(rail("pinned_a", "cached"), rail("catalog_1")),
            pinned = listOf(rail("pinned_a", "fresh")),
            keyOf = keyOf
        )

        assertEquals(
            "a duplicate rail key crashes the rail list, so the old copy must leave",
            listOf("pinned_a", "catalog_1"),
            merged.map { it.catalogId }
        )
        assertEquals("fresh", merged.first().items.single().id)
    }
}
