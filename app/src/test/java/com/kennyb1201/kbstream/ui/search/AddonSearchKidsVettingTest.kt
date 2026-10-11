package com.kennyb1201.kbstream.ui.search

import com.kennyb1201.kbstream.data.addon.MetaPreview
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kids Mode on the add-on search rails.
 *
 * The rails used to be suppressed outright on a kids profile — a third-party
 * catalog's own metadata carries no certification, so the code could not vet it.
 * But the app can vet it: every result carries an id, and
 * `TmdbRepository.kidsFilterMetas` resolves that id through the shared detail
 * cache and decides against the profile's ceiling, exactly as it does for the
 * TMDB title wave. So the rails now run on every profile and each rail is
 * filtered per result before it is published.
 *
 * The rule that no compile can check is the one that decides whether a rail
 * lives: a rail left with nothing is DROPPED, not drawn as an empty
 * "AIOMetadata" heading. [vetAddonGroup] owns it and is tested behaviourally;
 * the wiring that could silently return to suppression is pinned as text.
 */
class AddonSearchKidsVettingTest {

    private fun result(id: String, name: String = id, type: String = "movie") =
        SearchTitleResult(
            id = id,
            type = type,
            name = name,
            poster = null,
            meta = MetaPreview(id = id, type = type, name = name)
        )

    private fun group(
        addonName: String = "AIOMetadata",
        railLabel: String = "Movies",
        results: List<SearchTitleResult>
    ) = AddonResultGroup(
        addonName = addonName,
        railLabel = railLabel,
        results = results,
        catalogType = "movie"
    )

    /** The verdict a kidsFilterMetas call returns, as a set. */
    private fun allowed(vararg metas: MetaPreview) = metas.toSet()

    @Test
    fun `a partly allowed rail keeps only the allowed results`() {
        val kept = result("tt1", "Finding Nemo")
        val dropped = result("tt2", "Saw")
        val rail = group(results = listOf(kept, dropped))

        val vetted = vetAddonGroup(rail, allowed(kept.meta))

        assertEquals(
            "only the result the ceiling allowed may be published",
            listOf(kept),
            vetted?.results
        )
    }

    @Test
    fun `the rail keeps its own labels, only its results change`() {
        // The group's identity is (add-on, rail label, catalog type) and the
        // screen's LazyColumn is keyed on it — a vetting step that rebuilt the
        // group from scratch could collide two rails or mislabel one.
        val kept = result("tt1", "Finding Nemo")
        val rail = group(addonName = "AIOMetadata", railLabel = "Series", results = listOf(kept))
            .copy(catalogType = "series")

        val vetted = vetAddonGroup(rail, allowed(kept.meta))!!

        assertEquals("AIOMetadata", vetted.addonName)
        assertEquals("Series", vetted.railLabel)
        assertEquals("series", vetted.catalogType)
    }

    @Test
    fun `a rail the ceiling empties is dropped, never a titleless heading`() {
        val rail = group(results = listOf(result("tt1", "Saw"), result("tt2", "Hostel")))
        // Nothing allowed: the ceiling resolved both and rejected both.
        assertNull(vetAddonGroup(rail, allowed()))
        // Or the ceiling resolved them all and none matched a listed meta.
        assertNull(vetAddonGroup(rail, allowed(result("tt9").meta)))
    }

    @Test
    fun `the surviving results keep their original order`() {
        // Order is the catalog's relevance order; filtering must not reshuffle
        // it into ceiling-check completion order (the checks run concurrently).
        val first = result("tt1", "A")
        val second = result("tt2", "B")
        val third = result("tt3", "C")
        val rail = group(results = listOf(first, second, third))

        val vetted = vetAddonGroup(rail, allowed(first.meta, third.meta))

        assertEquals(listOf(first, third), vetted?.results)
    }

    @Test
    fun `an all-allowed rail is returned unchanged in content`() {
        val a = result("tt1", "A")
        val b = result("tt2", "B")
        val rail = group(results = listOf(a, b))

        assertEquals(listOf(a, b), vetAddonGroup(rail, allowed(a.meta, b.meta))?.results)
    }

    // ── contract: the wiring that could slide back to suppression ─────────

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

    /** From [start] up to the first [end] after it, or the file's end. */
    private fun slice(text: String, start: String, end: String): String {
        val from = text.indexOf(start)
        assertTrue("start marker missing: $start", from >= 0)
        val to = text.indexOf(end, from + start.length)
        return text.substring(from, if (to > from) to else text.length)
    }

    private fun launch(text: String) =
        slice(text, "private fun launchAddonSearch(", "addonSearchJob = viewModelScope.launch {")

    @Test
    fun `the kids suppression early return is gone`() {
        val viewModel = source(VIEW_MODEL)
        assertFalse(
            "launchAddonSearch must not bail out before starting the probes for a " +
                "kids profile — that is the behavior this change removes",
            launch(viewModel).contains("_addonResultGroups.value = emptyList()")
        )
        assertFalse(
            "and the old suppression gate must not come back in another spelling",
            viewModel.contains("Kids Mode suppresses add-on rails entirely")
        )
    }

    @Test
    fun `a non-kids profile publishes the very list it always did`() {
        val viewModel = source(VIEW_MODEL)
        assertTrue(
            "the ceiling step must be a no-op off a kids profile, so the adult path " +
                "keeps publishing the untouched digital-filter output",
            viewModel.contains(
                "if (groups.isEmpty() || tmdbRepository.kidsMaxAge() == null) return groups"
            )
        )
        assertTrue(
            "and the consolidated wave must be published through it",
            viewModel.contains("val publishedGroups = withKidsCeiling(visibleGroups)")
        )
    }

    @Test
    fun `kids profiles vet each rail through the shared ceiling policy`() {
        val viewModel = source(VIEW_MODEL)
        assertTrue(
            "each rail's results must be checked through kidsFilterMetas",
            viewModel.contains("val allowedMetas = tmdbRepository.kidsFilterMetas(")
        )
        assertTrue(
            "and the verdict must go through the drop-the-empty-rail rule",
            viewModel.contains("vetAddonGroup(group, allowedMetas)")
        )
    }

    @Test
    fun `the eager per-add-on snapshot is vetted too, so no unvetted rail is shown`() {
        // Two publish paths: the snapshot each add-on writes while the slower
        // ones are still answering, and the consolidated wave. An unfiltered
        // snapshot would put adult hits on a kids profile for that window.
        val viewModel = source(VIEW_MODEL)
        assertTrue(
            "the intermediate snapshot must take the same ceiling step",
            viewModel.contains("_addonResultGroups.value = withKidsCeiling(snapshot)")
        )
        assertFalse(
            "no publish path may write raw add-on groups",
            viewModel.contains("_addonResultGroups.value = snapshot")
        )
        val calls = viewModel.split("withKidsCeiling(").size - 1
        assertTrue(
            "expected the helper and its two publish call sites, found $calls",
            calls >= 3
        )
    }

    @Test
    fun `the vetting runs after the stale-wave gate and before the publish`() {
        val body = slice(
            source(VIEW_MODEL),
            "val visibleGroups =",
            "// Add-on results already carry IMDB ids"
        )
        val gate = body.indexOf("if (generation != addonSearchGeneration) return@launch")
        val vet = body.indexOf("val publishedGroups = withKidsCeiling(visibleGroups)")
        val publish = body.indexOf("_addonResultGroups.value = publishedGroups")
        assertTrue("the stale-wave gate must still exist", gate >= 0)
        assertTrue("the vetting must come after the gate", vet in (gate + 1) until publish)
        assertTrue("and the publish must come last", publish > vet)
    }

    @Test
    fun `the published list, not the pre-vetting one, is what gets worked on`() {
        val viewModel = source(VIEW_MODEL)
        assertTrue(
            "the rails published must be the vetted ones",
            viewModel.contains("_addonResultGroups.value = publishedGroups")
        )
        assertTrue(
            "the watched-state preload must not run for hidden results",
            viewModel.contains("preloadWatchedKeysFor(\n                publishedGroups")
        )
        assertTrue(
            "and neither must the enrichment, or a kids profile pays TMDB lookups " +
                "for hits it will never show",
            viewModel.contains("val enriched = enrichAddonGroups(publishedGroups)") &&
                viewModel.contains("enriched != publishedGroups")
        )
        assertFalse(
            "the pre-vetting list must not be published, preloaded or enriched anywhere",
            viewModel.contains("enrichAddonGroups(visibleGroups)") ||
                viewModel.contains("_addonResultGroups.value = visibleGroups")
        )
    }

    private companion object {
        const val VIEW_MODEL = "com/kennyb1201/kbstream/ui/search/SearchViewModel.kt"
    }
}
