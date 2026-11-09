package com.kennyb1201.kbstream.ui.search

import com.kennyb1201.kbstream.data.addon.MetaPreview
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Publishing the add-on rails earlier than the TMDB index.
 *
 * Two waits used to sit on the add-on publish path: the TMDB de-dupe index
 * (which needs both TMDB search waves) and, behind it, the digital-release
 * filter that only ever ran on the consolidated pass. Together they meant a
 * fast add-on's rails could stay off screen until a stalled TMDB request timed
 * out — and, once they did arrive, an un-released movie could sit in them until
 * the whole wave finished.
 *
 * What changed is WHERE each rule runs, not what the rules are:
 *  - a snapshot publishes with the `seen`-set cross-rail de-dupe only and the
 *    digital-release filter applied per rail as it is built;
 *  - the TMDB name+year de-dupe runs once, on the consolidated list, after the
 *    index lands;
 *  - a rail either rule empties is dropped instead of being drawn titleless;
 *  - a superseded wave still publishes nothing.
 *
 * The behavioural tests pin the rules; the text assertions pin the wiring that
 * no compiler would notice (an `await()` drifting back into the snapshot path,
 * or a rail going back to batch publishing).
 */
class AddonPublishTest {

    // ── behaviour ────────────────────────────────────────────────────────

    private fun result(
        id: String,
        name: String = id,
        type: String = "movie",
        year: Int? = null
    ) = SearchTitleResult(
        id = id,
        type = type,
        name = name,
        poster = null,
        year = year,
        meta = MetaPreview(id = id, type = type, name = name)
    )

    private fun group(
        results: List<SearchTitleResult>,
        railLabel: String = "Movies",
        searchStyle: Boolean = false
    ) = AddonResultGroup(
        addonName = "AIOMetadata",
        railLabel = railLabel,
        results = results,
        catalogType = "movie",
        searchStyle = searchStyle
    )

    /** The index the TMDB wave hand-wrote for a search that returned these hits. */
    private fun tmdbIndex(
        ids: Set<String> = emptySet(),
        nameYears: Set<String> = emptySet()
    ) = TmdbDedupIndex(ids = ids, nameYears = nameYears)

    @Test
    fun `the consolidated publish drops the titles the TMDB rail already shows`() {
        val shared = result("tt100", "Crash", year = 1996)
        val own = result("tt101", "Something Else")
        val rail = group(listOf(shared, own))
        val index = tmdbIndex(
            ids = setOf(TmdbDedupIndex.idKey("movie", "tt100"))
        )

        val published = dedupAddonGroups(listOf(rail), index)

        assertEquals(
            "the row TMDB already has must not be echoed in the add-on rail",
            listOf(own),
            published.single().results
        )
    }

    @Test
    fun `a name and year match is a duplicate, a different year is not`() {
        val remake = result("tt201", "Crash", year = 2004)
        val original = result("tt202", "Crash", year = 1996)
        val rail = group(listOf(original, remake))
        val index = tmdbIndex(
            nameYears = setOf(
                TmdbDedupIndex.nameYearKey("movie", "Crash", 1996)!!
            )
        )

        val published = dedupAddonGroups(listOf(rail), index)

        assertEquals(
            "same name AND same year is the only proven duplicate",
            listOf(remake),
            published.single().results
        )
    }

    @Test
    fun `a rail the de-dupe empties is dropped, never a titleless heading`() {
        val rail = group(listOf(result("tt100")))

        val published = dedupAddonGroups(
            listOf(rail),
            tmdbIndex(ids = setOf(TmdbDedupIndex.idKey("movie", "tt100")))
        )

        assertTrue("an emptied rail must not be published", published.isEmpty())
    }

    @Test
    fun `a search-style rail is exempt from the TMDB de-dupe`() {
        // "AI Search" is expected to overlap everything else; dropping its
        // hits is exactly the "my AI Search rail disappeared" bug.
        val hit = result("tt100", "Crash", year = 1996)
        val rail = group(listOf(hit), railLabel = "AI Search", searchStyle = true)

        val published = dedupAddonGroups(
            listOf(rail),
            tmdbIndex(ids = setOf(TmdbDedupIndex.idKey("movie", "tt100")))
        )

        assertEquals(listOf(hit), published.single().results)
    }

    @Test
    fun `a group the digital-release filter empties is dropped`() {
        // The filter returns the kept items; an empty answer is the whole rail
        // being un-released, which must leave no heading behind (F2).
        val emptied = group(results = emptyList())
        val kept = group(results = listOf(result("tt1", "Out Now")), railLabel = "Series")

        val published = dropEmptyAddonGroups(listOf(emptied, kept))

        assertEquals(
            "only the rail that still has a title may be published",
            listOf("Series"),
            published.map { it.railLabel }
        )
        assertNull(
            "and the emptied one is gone rather than republished empty",
            published.firstOrNull { it.railLabel == "Movies" }
        )
    }

    @Test
    fun `the de-dupe keeps the surviving order and the rail identity`() {
        val first = result("tt1", "A")
        val dropped = result("tt2", "B")
        val third = result("tt3", "C")
        val rail = group(listOf(first, dropped, third))

        val published = dedupAddonGroups(
            listOf(rail),
            tmdbIndex(ids = setOf(TmdbDedupIndex.idKey("movie", "tt2")))
        ).single()

        assertEquals(
            "de-duping must not reshuffle a catalog's relevance order",
            listOf(first, third),
            published.results
        )
        assertEquals("AIOMetadata", published.addonName)
        assertEquals("Movies", published.railLabel)
        assertEquals("movie", published.catalogType)
    }

    @Test
    fun `an empty index leaves every rail as it was`() {
        val rail = group(listOf(result("tt1"), result("tt2")))

        assertEquals(
            "a search TMDB answered nothing for must not lose add-on hits",
            listOf(rail),
            dedupAddonGroups(listOf(rail), tmdbIndex())
        )
    }

    // ── contract: where each step runs ───────────────────────────────────

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

    private fun viewModel() = source(VIEW_MODEL)

    /** From [start] up to the first [end] after it, or the file's end. */
    private fun slice(text: String, start: String, end: String): String {
        val from = text.indexOf(start)
        assertTrue("start marker missing: $start", from >= 0)
        val to = text.indexOf(end, from + start.length)
        return text.substring(from, if (to > from) to else text.length)
    }

    private fun collector() =
        slice(viewModel(), "private suspend fun collectAddonSearchGroup(", "\n    /**")

    private fun snapshotPublish() =
        slice(viewModel(), "private suspend fun publishAddonSnapshot(", "\n    /**")

    @Test
    fun `a rail is published without waiting on the TMDB de-dupe index`() {
        // F1: nothing on the collect/snapshot path may reach for the index.
        // Its only consumer is the consolidated publish below.
        val collector = collector()
        assertFalse(
            "the collector must finish a rail without the TMDB index — " +
                "awaiting it here is what held fast add-on rails behind a " +
                "stalled TMDB wave",
            collector.contains("tmdbDedup")
        )
        assertFalse(
            "and the per-add-on snapshot loop must not await it either",
            slice(
                viewModel(),
                "private suspend fun searchAddonCatalogs(",
                "private suspend fun publishAddonSnapshot("
            ).contains("tmdbDedup")
        )
        assertEquals(
            "exactly one await on the publish path: the consolidated republish",
            1,
            slice(
                viewModel(),
                "private fun launchAddonSearch(",
                "\n    /**"
            ).split("tmdbDedupDeferred.await()").size - 1
        )
        assertTrue(
            "and it is the de-dupe step, not a bare filter",
            viewModel().contains("dedupAddonGroups(groups, tmdbDedupDeferred.await())")
        )
        assertTrue(
            "the only other await is the cache storing the index for a repeat, " +
                "which publishes nothing",
            viewModel().contains("tmdbDedup = tmdbDedupDeferred.await()")
        )
    }

    @Test
    fun `the de-dupe rule drops a rail the TMDB hits empty`() {
        val rules = source(ADDON_PUBLISH)
        assertTrue(
            "the de-dupe rule exists away from the collector's coroutine plumbing",
            rules.contains("internal fun dedupAddonGroups(")
        )
        assertTrue(
            "it is the only rule that consults the index",
            rules.contains("index.matches(")
        )
        assertTrue(
            "and a rail whose every hit was a TMDB title is dropped, not left empty",
            rules.contains("if (kept.isEmpty()) null else group.copy(results = kept)")
        )
    }

    @Test
    fun `every rail goes through the digital-release filter before any publish`() {
        val vm = viewModel()
        // F2: the collector's finish step filters, so both publishes show the
        // same rule — the snapshot is not a preview of unfiltered rails.
        val emit = slice(vm, "suspend fun emit(group: AddonResultGroup) {", "\n        }")
        assertTrue(
            "a rail must be filtered as it is finished",
            emit.contains("withHomeAvailability(listOf(group)).firstOrNull()")
        )
        assertTrue(
            "and the consolidated publish re-applies it to the final state",
            vm.contains("val visibleGroups = withHomeAvailability(")
        )
        assertTrue(
            "the filter hides un-released movies through the shared repository call",
            vm.contains("results = tmdbRepository.filterByHomeAvailabilityById(")
        )
        assertTrue(
            "and an emptied group is dropped rather than published",
            vm.contains("dropEmptyAddonGroups(")
        )
    }

    @Test
    fun `each rail is published as it answers, not when its add-on finishes`() {
        val vm = viewModel()
        val collector = collector()
        val probes = slice(
            collector,
            "val probes = searchableCatalogs.map",
            "return@coroutineScope groups"
        )
        assertTrue(
            "the probes are not batch-awaited, so the first answer can publish",
            probes.contains("for (probe in probes)") && probes.contains("probe.await()")
        )
        assertFalse(
            "an awaitAll would put the whole add-on back behind its slowest catalog",
            probes.contains(".awaitAll()")
        )
        assertTrue(
            "each finished rail is handed straight to the publisher",
            probes.contains("emit(") &&
                collector.contains("onGroup(visible)")
        )
        assertTrue(
            "and the publisher republishes the snapshot on every arrival",
            vm.contains("collectAddonSearchGroup(addon, query) { group ->") &&
                vm.contains("publishAddonSnapshot(index, group, slots, generation)")
        )
        assertFalse(
            "the wave must not go back to publishing once, after joinAll",
            slice(
                vm,
                "private suspend fun searchAddonCatalogs(",
                "private suspend fun publishAddonSnapshot("
            ).contains("_addonResultGroups.value")
        )
    }

    @Test
    fun `a superseded wave still publishes nothing`() {
        val publish = snapshotPublish()
        val gate = publish.indexOf("if (generation != addonSearchGeneration) return")
        val write = publish.indexOf("_addonResultGroups.value = withKidsCeiling(snapshot)")
        assertTrue("the snapshot must check the token it captured", gate >= 0)
        assertTrue("and the write must come after that check", write > gate)
        assertTrue(
            "the consolidated publish keeps its own gate",
            viewModel().contains("if (generation != addonSearchGeneration) return@launch")
        )
    }

    @Test
    fun `a snapshot and the consolidated list are the same groups, earlier`() {
        val vm = viewModel()
        assertTrue(
            "the snapshot coalesces the slots the consolidated list is built from",
            vm.split("coalesceAddonGroups(\n            (0 until slots.length())").size - 1 == 2
        )
        assertTrue(
            "both publishes take the same rail cap",
            vm.split(").take(MAX_ADDON_GROUPS)").size - 1 == 2
        )
        assertTrue(
            "and the collector caps rails per add-on as they arrive",
            vm.contains("if (groups.size >= MAX_ADDON_GROUPS_PER_ADDON) return")
        )
    }

    private companion object {
        const val VIEW_MODEL = "com/kennyb1201/kbstream/ui/search/SearchViewModel.kt"
        const val ADDON_PUBLISH = "com/kennyb1201/kbstream/ui/search/AddonPublish.kt"
    }
}
