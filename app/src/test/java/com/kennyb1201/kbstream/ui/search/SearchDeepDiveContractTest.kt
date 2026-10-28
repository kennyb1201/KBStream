package com.kennyb1201.kbstream.ui.search

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The search-screen deep dive's fixes, pinned where a JVM cannot compose them.
 *
 * Search is a Compose screen over an AndroidViewModel: the return-focus rail
 * wiring, the badge publication and the error branch cannot be exercised here.
 * What this pins is the wiring, read from source, plus the one rule that IS
 * pure ([TmdbDedupIndex.matches]).
 *
 * The findings are the ones the audit called CONFIRMED: C1 (non-title rails
 * lost their return focus), C2/C3 (sibling-profile badges: no filter, no
 * clearing), C4 (a failed search was invisible on TV), C5 (name-only dedup
 * dropped distinct same-name titles), C6 (a mark with no IMDb id was a silent
 * no-op) and C7 (the `clear_recent` duplicate key). I1 is the suggestion
 * flicker. S1/S2 stay code-evidence-only in the audit: their fixes wait for the
 * device timing/edge check, so nothing here pins a speculative change.
 */
class SearchDeepDiveContractTest {

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
        return file.readText().replace(Regex("\\s+"), " ")
    }

    private fun viewModel(): String = source(VIEW_MODEL)
    private fun tv(): String = source(TV_SCREEN)

    private fun occurrences(haystack: String, needle: String): Int {
        var count = 0
        var from = 0
        while (true) {
            val at = haystack.indexOf(needle, from)
            if (at < 0) return count
            count++
            from = at + needle.length
        }
    }

    private fun slice(
        source: String,
        from: String,
        until: String
    ): String {
        assertTrue("missing start marker: $from", source.contains(from))
        val body = source.substringAfter(from)
        assertTrue("missing end marker: $until", body.contains(until))
        return body.substringBefore(until)
    }

    // ── C1: actor / studio / collection rails return focus ───────────────

    @Test
    fun `every non-title rail records the card a drill-down was opened from`() {
        val screen = tv()

        val actors = slice(screen, "item(key = \"actors_rail\")", "item(key = \"studios_rail\")")
        assertTrue(
            "Back from a person page used to land on the search field; the " +
                "actor rail must record its card",
            actors.contains("SearchReturnFocus.record(") &&
                actors.contains("railKey = ACTORS_RAIL_FOCUS_KEY") &&
                actors.contains("PersonResultCard(") &&
                actors.contains(".focusRequester(requester)")
        )

        val studios = slice(screen, "item(key = \"studios_rail\")", "item(key = \"collections_rail\")")
        assertTrue(
            "and so must the studio rail",
            studios.contains("SearchReturnFocus.record(") &&
                studios.contains("railKey = STUDIOS_RAIL_FOCUS_KEY") &&
                studios.contains("StudioResultCard(") &&
                studios.contains(".focusRequester(requester)")
        )

        val collections = slice(screen, "item(key = \"collections_rail\")", "// Long-press context menu")
        assertTrue(
            "and the collection rail",
            collections.contains("SearchReturnFocus.record(") &&
                collections.contains("railKey = COLLECTIONS_RAIL_FOCUS_KEY") &&
                collections.contains("CollectionPosterTile(") &&
                collections.contains(".focusRequester(requester)")
        )
    }

    @Test
    fun `the non-title rails seed their scroll from the return record`() {
        val screen = tv()
        val actors = slice(screen, "item(key = \"actors_rail\")", "item(key = \"studios_rail\")")
        val studios = slice(screen, "item(key = \"studios_rail\")", "item(key = \"collections_rail\")")
        val collections = slice(screen, "item(key = \"collections_rail\")", "// Long-press context menu")

        assertTrue(
            "a rail that re-composes at offset 0 cannot focus a card the viewer " +
                "had scrolled to",
            actors.contains("initialFirstVisibleItemIndex = if (railReturn?.railKey == ACTORS_RAIL_FOCUS_KEY)") &&
                studios.contains("initialFirstVisibleItemIndex = if (railReturn?.railKey == STUDIOS_RAIL_FOCUS_KEY)") &&
                collections.contains("initialFirstVisibleItemIndex = if (railReturn?.railKey == COLLECTIONS_RAIL_FOCUS_KEY)")
        )
    }

    // ── C2: sibling-profile ownership at badge publication ───────────────

    @Test
    fun `both badge sets drop titles another profile owns at publication`() {
        val vm = viewModel()
        assertEquals(
            "the checkmark AND the eye read the same account-scoped cache the " +
                "ownership filter exists for - with the one exception an explicit " +
                "mark on this profile earns (see ExplicitMarkBeatsOwnershipContractTest)",
            2,
            occurrences(vm, "keyOwnedByAnotherProfile(key) && key !in explicitWatched")
        )
        assertTrue(
            "the ownership answers come from the published results, by title",
            vm.contains("private fun siblingOwnedMarkerIdsFor(results: List<SearchTitleResult>)") &&
                vm.contains("TitleProfileOwnership.snapshot(getApplication())") &&
                vm.contains("titleOwnedByAnotherProfile(")
        )
        assertEquals(
            "and a live sibling write is filtered too, as Home's collector does - " +
                "inside each branch, so an explicitly marked key is still added",
            2,
            occurrences(vm, "keyOwnedByAnotherProfile(update.key) &&")
        )
        assertEquals(
            "both branches carry the exemption",
            2,
            occurrences(vm, "update.key !in explicitWatched")
        )
        assertTrue(
            "ownership is recomputed whenever a wave publishes (titles, add-ons, " +
                "and after IMDb resolution)",
            occurrences(vm, "refreshSiblingOwnedMarkers()") >= 4
        )
    }

    // ── C3: badge sets do not survive a profile switch ───────────────────

    @Test
    fun `resetSearchState clears the badge state a switch must not carry`() {
        val reset = slice(
            viewModel(),
            "fun resetSearchState() {",
            "fun exitSearch()"
        )
        assertTrue(
            "the outgoing profile's checkmarks must not linger on the incoming one",
            reset.contains("_watchedKeys.value = emptySet()")
        )
        assertTrue(
            "nor its eyes",
            reset.contains("_partialWatchedKeys.value = emptySet()")
        )
        assertTrue(
            "nor its resolved ids, which badges and marks read",
            reset.contains("_resolvedIds.value = emptyMap()")
        )
        assertTrue(
            "and the ownership answers go with them",
            reset.contains("siblingOwnedMarkerIds = emptySet()")
        )
    }

    // ── C4: a failed search says so on TV too ────────────────────────────

    @Test
    fun `all five TMDB waves failing publishes the error, not an empty result`() {
        val vm = viewModel()
        assertTrue(
            "per-call swallowing must still be counted, or the all-fail case is " +
                "indistinguishable from a genuine zero-hit search",
            vm.contains("val failedTmdbWaves = AtomicInteger(0)") &&
                vm.contains("if (failedTmdbWaves.get() == TMDB_WAVE_COUNT)")
        )
        assertTrue(
            "and that branch is what sets the error the screens already read",
            vm.contains("_searchError.value = SEARCH_UNAVAILABLE")
        )
        assertFalse(
            "one surviving wave is still a usable result set, not an error",
            vm.contains("if (failedTmdbWaves.get() > 0)")
        )
    }

    @Test
    fun `the TV page collects the error and stops offering browse for a failed query`() {
        val screen = tv()
        assertTrue(
            "the TV page had zero references to the error state",
            screen.contains("val searchError by viewModel.searchError.collectAsStateWithLifecycle()")
        )
        assertTrue(
            "it must draw the error with a retry, as the phone does",
            screen.contains("item(key = \"search_error\")") &&
                screen.contains("actionLabel = \"TRY AGAIN\"") &&
                screen.contains("onRetry = { viewModel.search(query) }")
        )
        assertTrue(
            "\"No matches found.\" must not stand in for a search that never ran",
            screen.contains(
                "if (!isLoading && searchError == null && query.isNotBlank() && totalCount == 0)"
            )
        )
        assertTrue(
            "nor may a failed query offer the browse browser",
            screen.contains(
                "if (query.isBlank() || (!isLoading && totalCount == 0 && searchError == null))"
            )
        )
    }

    // ── C5: dedup by identity, or name AND year ──────────────────────────

    @Test
    fun `a same-name different-year title survives the cross-wave dedup`() {
        val index = TmdbDedupIndex(
            ids = setOf(TmdbDedupIndex.idKey("movie", "tmdb:100")),
            nameYears = setOfNotNull(
                TmdbDedupIndex.nameYearKey("movie", "Crash", 1996)
            )
        )

        assertTrue(
            "the same title and year is a duplicate",
            index.matches("movie", "tt200", "Crash", 1996)
        )
        assertFalse(
            "the 2004 Crash is a DIFFERENT title and must keep its add-on row",
            index.matches("movie", "tt201", "Crash", 2004)
        )
        assertFalse(
            "an add-on that omits the year cannot prove a duplicate either",
            index.matches("movie", "tt202", "Crash", null)
        )
        assertTrue(
            "the same identity is a duplicate however the year reads",
            index.matches("movie", "tmdb:100", "Crash", null)
        )
        assertFalse(
            "a different name is not a duplicate even in the same year",
            index.matches("movie", "tt203", "Crash Landing", 1996)
        )
    }

    @Test
    fun `the search view model no longer filters add-on rows by name alone`() {
        val vm = viewModel()
        assertTrue(
            "the dedup index is what both add-on paths consult",
            vm.contains("!tmdbDedup.matches(")
        )
        assertFalse(
            "the old name-only comparison is the bug C5 reports",
            vm.contains("\"${'$'}{meta.type}:${'$'}{meta.name.lowercase()}\" !in")
        )
        assertFalse(
            "and the TMDB side must offer more than a name key",
            vm.contains("private fun tmdbNameKey(")
        )
    }

    // ── C6: a mark is never a silent no-op ───────────────────────────────

    @Test
    fun `both mark actions fall back to the TMDB key the badge reads`() {
        assertEquals(
            "Mark as Watched and Mark as Unwatched must key exactly like the tile " +
                "badge when no IMDb id can be resolved",
            2,
            occurrences(viewModel(), "resolved ?: WatchedStatusKeys.tmdbKeyId(tmdbId)")
        )
    }

    // ── C7: the literal recent search cannot collide with CLEAR RECENT ───

    @Test
    fun `the TV recent-search keys are prefixed like the phone's`() {
        val screen = tv()
        assertTrue(
            "a recent search literally called \"clear_recent\" minted a duplicate " +
                "LazyRow key and crashed the screen",
            screen.contains("key = { recent: String -> \"recent:\$recent\" }")
        )
        assertFalse(
            "the bare text is the collision",
            screen.contains("key = { recent: String -> recent }")
        )
    }

    // ── I1: suggestions only quote the results of the current query ──────

    @Test
    fun `suggestions do not quote the previous query's results during the debounce`() {
        val vm = viewModel()
        assertTrue(
            "result names are read synchronously, so they need the query they belong to",
            vm.contains("if (resultsQuery == lower)") &&
                vm.contains("resultsQuery = normalized.lowercase()")
        )
    }

    private companion object {
        const val VIEW_MODEL = "com/kennyb1201/kbstream/ui/search/SearchViewModel.kt"
        const val TV_SCREEN = "com/kennyb1201/kbstream/ui/search/SearchScreen.kt"
    }
}
