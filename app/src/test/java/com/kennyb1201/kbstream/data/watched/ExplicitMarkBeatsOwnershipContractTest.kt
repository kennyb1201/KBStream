package com.kennyb1201.kbstream.data.watched

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An EXPLICIT mark on the active profile wins the sibling-ownership filter.
 *
 * The filter's evidence is name+type ownership (`TitleProfileOwnership`), so a
 * title the kids profile has watched counts as owned elsewhere even after the
 * viewer marks it here - and the publish stripped their own mark off every
 * surface while the write sat intact in this profile's override store. Field
 * report: Spider-Man: Brand New Day did not badge after Mark as watched, while
 * Ali G (owned by nobody) did. Tracker-derived state keeps being filtered: that
 * is the kids-leak the filter exists for.
 *
 * Precedence, which every site below has to express: explicit local override >
 * ownership filter > tracker-derived / merged watched state.
 *
 * The predicate is the override set the BADGES already resolve with, twin
 * expanded, read live per publish ([WatchedStatusRepository.
 * explicitWatchedOverrideKeys]) - no new store, no new key, no cached exemption.
 * These pins are source-text because each site is a lambda inside an Android
 * view model; [ExplicitMarkExemptionTest] exercises the set itself.
 */
class ExplicitMarkBeatsOwnershipContractTest {

    private fun source(path: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, prefix + "src/main/java/" + path)
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    /** The `src/main/java` directory the other helpers read files out of. */
    private fun sourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private fun count(src: String, fragment: String): Int =
        src.split(fragment).size - 1

    private val repository: String by lazy { source(REPOSITORY) }
    private val home: String by lazy { source(HOME) }
    private val search: String by lazy { source(SEARCH) }
    private val detail: String by lazy { source(DETAIL) }
    private val library: String by lazy { source(LIBRARY) }

    @Test
    fun `the exemption is the badge resolver's own set, twin expanded`() {
        assertTrue(
            "one accessor, over the set the badges already OR in: read raw, then " +
                "expand the twin through the same memo the resolver uses",
            repository.contains(
                "suspend fun explicitWatchedOverrideKeys(): Set<String> =\n" +
                    "        expandOverrideTwins(localWatchedOverrideKeys())"
            )
        )
        assertFalse(
            "no second store, no new key: the override blob is the only place an " +
                "explicit mark is recorded",
            repository.contains("EXEMPT") && repository.contains("KEY_EXEMPT")
        )
    }

    @Test
    fun `home exempts at both publish sites and in the live collector`() {
        assertTrue(
            "the refresh's watched set",
            home.contains("keyOwnedByAnotherProfile(key) && key !in explicitWatched")
        )
        assertTrue(
            "four sites: refresh's watched + partial, and the live collector's pair",
            count(home, "keyOwnedByAnotherProfile(key) && key !in explicitWatched") == 4
        )
        assertTrue(
            "fetched once for the refresh pass, plus once per live event",
            count(home, "explicitWatchedKeys()") == 3
        )
        assertFalse(
            "and no site left with the bare filter",
            home.contains("filterNot { key -> keyOwnedByAnotherProfile(key) }")
        )
    }

    @Test
    fun `search exempts in the refresh and inside BOTH live branches`() {
        assertTrue(
            "the preload publish",
            search.contains("keyOwnedByAnotherProfile(key) && key !in explicitWatched")
        )
        assertTrue(
            "the bus branches remove an owned update instead of adding it, so the " +
                "exemption has to be inside each branch",
            count(search, "update.key !in explicitWatched") == 2
        )
        assertTrue(
            "and a sibling-owned update with no exemption is still REMOVED",
            search.contains("current.remove(update.key)") &&
                search.contains("partial.remove(update.key)")
        )
        assertFalse(
            "the bare branch shape is what swallowed an explicit mark",
            search.contains("if (keyOwnedByAnotherProfile(update.key)) {")
        )
    }

    @Test
    fun `the five browse pages carry the same predicate`() {
        browsePages.forEach { (name, path) ->
            val src = source(path)
            assertTrue(
                "$name must fetch the exemption in its publish pass",
                src.contains("val explicitWatched = explicitWatchedKeys()")
            )
            assertTrue(
                "$name must exempt both its watched and partial sets",
                count(
                    src,
                    "keyOwnedByAnotherProfile(key, siblingOwned) && key !in explicitWatched"
                ) == 2
            )
            assertFalse(
                "$name must not keep the bare filter",
                src.contains("filterNot { key -> keyOwnedByAnotherProfile(key, siblingOwned) }")
            )
            assertTrue(
                "$name reads the live set out of the repository",
                src.contains("watchedStatusRepository.explicitWatchedOverrideKeys()")
            )
        }
    }

    @Test
    fun `detail exempts a poster by its own badge keys`() {
        assertTrue(
            "the owned set is narrowed before the two filters run",
            detail.contains("val withheldElsewhere =") &&
                detail.contains("if (explicitlyMarked) withheldElsewhere -= item.tmdbId")
        )
        assertTrue(
            "the check reuses the badges' key math, both id flavors included",
            detail.contains("matches = { id, type ->") &&
                detail.contains("WatchedStatusKeys.key(id, type) in explicitWatched")
        )
        assertTrue(
            "both sets (checkmark and eye) filter on the narrowed set",
            count(detail, "filterNot { item -> item.tmdbId in withheldElsewhere }") == 2
        )
        assertFalse(
            "and no poster site still filters on the raw owned set",
            detail.contains("filterNot { item -> item.tmdbId in ownedElsewhere }")
        )
    }

    @Test
    fun `detail publishes this profile's own episode rows whatever a sibling owns`() {
        assertTrue(
            "local completed ids are per-profile by construction, so they always publish",
            detail.contains("_completedEpisodeIds.value = completedEntries.map { it.id }.toSet()")
        )
        val publish = detail.substringAfter("private fun publishWatchedEpisodeKeys(")
            .substringBefore("fun load(")
        assertTrue(
            "the sibling-owned branch publishes the LOCAL slice, not an empty set",
            publish.contains("simklCompletedEpisodes = emptySet()")
        )
        assertTrue(
            "and it is the local keys the branch publishes",
            publish.contains("localCompletedEntries = localCompletedEntries") &&
                publish.contains("_watchedEpisodeKeys.value =")
        )
        assertTrue(
            "the tracker portion stays withheld: the MDBList and Trakt keys are " +
                "only added on the un-owned path",
            publish.indexOf("detailOwnedByAnotherProfile()") <
                publish.indexOf("mdbListWatchedEpisodes")
        )
    }

    @Test
    fun `library exempts a row whose own badge key was marked here`() {
        assertTrue(
            "either badge key qualifies",
            library.contains("val explicitBadgeKeys =") &&
                library.contains("listOfNotNull(watched, partiallyWatched)")
        )
        assertTrue(
            "and the gate stands for everything else",
            library.contains("val explicitlyMarked =") &&
                library.contains("!explicitlyMarked &&")
        )
        assertTrue(
            "the exemption comes from the shared accessor, once per batch",
            count(library, "explicitWatchedOverrideKeys()") == 1
        )
    }

    /*
     * ── every rail, not just the one in the report ───────────────────────
     *
     * The field report named one pinned rail, but the filter is not rail-wide
     * by construction: Home publishes ONE pair of key sets and every rail
     * (Top Today, catalog rails, add-on rails, Because You Watched, the catalog
     * grid) draws its badge from them. So the fix has to be proven over the
     * whole tree rather than over the ten files the spec happened to list -
     * otherwise the next rail added copies the bare filter and the same bug
     * comes back on a different row of cards.
     */

    @Test
    fun `every ownership filter in the tree exempts an explicit mark`() {
        // Walks the whole source tree, so a filter added later in a file this
        // spec never mentions fails here instead of on Kenny's TV.
        val sourceRoot = sourceRoot()
        val offenders = mutableListOf<String>()
        var sites = 0
        sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { file ->
                val lines = file.readLines()
                lines.forEachIndexed { index, raw ->
                    val line = raw.trim()
                    // Comments describe the rule, and the declarations ARE it.
                    if (line.startsWith("//") || line.startsWith("*") ||
                        line.startsWith("/*")
                    ) return@forEachIndexed
                    if (!line.contains("keyOwnedByAnotherProfile(")) return@forEachIndexed
                    if (line.contains("fun keyOwnedByAnotherProfile(")) return@forEachIndexed
                    sites++
                    // A filter is a lambda that may wrap, so the exemption is
                    // read from the call and the next two lines. Nothing in
                    // the tree puts it further away than that.
                    val window = lines.subList(index, minOf(lines.size, index + 3))
                        .joinToString(" ")
                    if (!window.contains("!in explicitWatched")) {
                        offenders += "${file.name}:${index + 1}: $line"
                    }
                }
            }

        assertEquals(
            "a bare ownership filter strips the viewer's own mark - this is the " +
                "reported bug, and there must be none left anywhere",
            emptyList<String>(),
            offenders
        )
        assertTrue(
            "the sweep must actually be looking at the filter sites, not at zero " +
                "files (18 at this tip: Home x4, Search x4, five browse pages x2)",
            sites >= 18
        )
    }

    @Test
    fun `no rail builds its own watched state`() {
        // HomeScreen draws the badge for EVERY rail - pinned top lists, catalog
        // rails, add-on rails, built-ins - from `viewModel.watchedKeys` /
        // `partialWatchedKeys`, which are the two exempted sets. A rail that
        // asked the repository itself would have to re-implement the filter.
        val screen = source("com/kennyb1201/kbstream/ui/home/HomeScreen.kt")
        assertFalse(
            "a rail reading the repository could bypass the exemption",
            screen.contains("WatchedStatusRepository") ||
                screen.contains("isWatchedCached") ||
                screen.contains("preloadAndGetWatchedKeys")
        )
        assertTrue(
            "both badge sets come from the ViewModel that applies the predicate",
            screen.contains("viewModel.watchedKeys.collectAsStateWithLifecycle()") &&
                screen.contains("viewModel.partialWatchedKeys.collectAsStateWithLifecycle()")
        )
        val grid = source("com/kennyb1201/kbstream/ui/home/CatalogGridScreen.kt")
        assertTrue(
            "the full catalog grid is a rail too: same two sets, same exemption",
            grid.contains("viewModel.watchedKeys.collectAsStateWithLifecycle()") &&
                grid.contains("viewModel.partialWatchedKeys.collectAsStateWithLifecycle()")
        )
        // KB folders apply NO ownership filter at all (spec 4.6), so there is
        // nothing there to strip an explicit mark: pinned that way on purpose.
        val kb = source("com/kennyb1201/kbstream/ui/kb/KBFolderViewModel.kt")
        assertFalse(
            "if a KB rail ever gains the filter it must gain the exemption with it",
            kb.contains("keyOwnedByAnotherProfile") || kb.contains("titleOwnedByAnotherProfile")
        )
    }

    @Test
    fun `no view model caches the exemption`() {
        // §5.4: the predicate reads the live set, so an unmark resumes filtering
        // on the next publish and a profile switch cannot carry one profile's
        // exemption into another. A field would do both wrong.
        val files = listOf(HOME, SEARCH, DETAIL, LIBRARY) + browsePages.map { it.second }
        files.forEach { path ->
            val src = source(path)
            assertFalse(
                "$path must read the set per publish, not hold it",
                src.contains("private val explicitWatchedKeys") ||
                    src.contains("private var explicitWatchedKeys") ||
                    src.contains("private val explicitWatched =")
            )
        }
    }

    private companion object {
        private const val REPOSITORY =
            "com/kennyb1201/kbstream/data/watched/WatchedStatusRepository.kt"
        private const val HOME = "com/kennyb1201/kbstream/ui/home/HomeViewModel.kt"
        private const val SEARCH = "com/kennyb1201/kbstream/ui/search/SearchViewModel.kt"
        private const val DETAIL = "com/kennyb1201/kbstream/ui/detail/DetailViewModel.kt"
        private const val LIBRARY = "com/kennyb1201/kbstream/ui/library/LibraryViewModel.kt"

        private val browsePages = listOf(
            "ActorViewModel" to "com/kennyb1201/kbstream/ui/actor/ActorViewModel.kt",
            "CollectionViewModel" to
                "com/kennyb1201/kbstream/ui/collection/CollectionViewModel.kt",
            "DecadeViewModel" to "com/kennyb1201/kbstream/ui/decade/DecadeViewModel.kt",
            "StudioViewModel" to "com/kennyb1201/kbstream/ui/studio/StudioViewModel.kt",
            "TagViewModel" to "com/kennyb1201/kbstream/ui/tag/TagViewModel.kt"
        )
    }
}
