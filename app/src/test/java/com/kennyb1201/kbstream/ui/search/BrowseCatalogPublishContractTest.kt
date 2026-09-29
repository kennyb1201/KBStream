package com.kennyb1201.kbstream.ui.search

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Browse strip is filled from ONE resolved union of the standard and kids
 * name lists — one TMDB lookup pass, one disk cache entry — so both publish
 * paths (the resolver, and the cache read that serves every later session) hold
 * the other mode's names and each has to slice back down to its own.
 *
 * Slicing is the job of [ResolvedBrowseCatalog.forMode], which is the union's
 * only accessor, and this test is what makes that a fact rather than a habit:
 * the union's two raw lists are still locals in the ViewModel, and a future
 * publish path could reach for them exactly the way the resolver once did —
 * which put 330 adult-only tags and 267 adult-only collections into a kids
 * profile's Browse menu.
 *
 * It reads the ViewModel's source instead of calling it, because the mistake
 * this guards against is invisible to a behavioural test: a raw list and a
 * sliced list are the same type, so only the call site distinguishes them. The
 * precedent is PlayerGuideWriteGateContractTest, which pins the player/gate
 * pairing for the same reason — nothing about writing a new publish path makes
 * a missing slice visible.
 */
class BrowseCatalogPublishContractTest {

    private companion object {
        const val VIEW_MODEL = "com/kennyb1201/kbstream/ui/search/SearchViewModel.kt"

        /**
         * The ViewModel locals that hold the union before it is sliced. A
         * published entry list coming from one of these is the leak.
         */
        val UnionLists = setOf("keywordEntries", "collectionEntries", "keywords", "collections")

        /**
         * `copy(entries = <bare identifier>)`: a publish call whose entries are
         * a single variable. An expression deliberately does not match —
         * `category.entries`, `keywordsForMode.ifEmpty { ... }` — because the
         * leak is always the bare list, and matching expressions would flag the
         * per-mode curated categories too.
         */
        val EntriesAssignment =
            Regex("""copy\(\s*entries\s*=\s*([A-Za-z_][A-Za-z0-9_]*)\s*[,)]""")
    }

    private val sourceRoot: File by lazy { findSourceRoot() }

    /**
     * Resolves `…/src/main/java` from the test's working directory, which is
     * the module dir under Gradle (`app/`) but the repo root under some
     * runners. Walking up covers both; a miss is loud rather than a silent
     * skip, because a green run that read nothing is worse than no test.
     */
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

    private val viewModel: String by lazy {
        val file = File(sourceRoot, VIEW_MODEL)
        assertTrue("ViewModel source missing: $file", file.isFile)
        file.readText()
    }

    @Test
    fun `no publish path is handed the raw resolved union`() {
        val offenders = EntriesAssignment.findAll(viewModel)
            .map { it.groupValues[1] }
            .filter { it in UnionLists }
            .toList()
        assertTrue(
            "these published entries come straight from the pre-slice union, which " +
                "hands a kids profile the adult half of the menu: $offenders",
            offenders.isEmpty()
        )

        // A scan that matched nothing would pass whether or not the leak came
        // back, so require the publish sites it is meant to be watching.
        val assignments = EntriesAssignment.findAll(viewModel).count()
        assertTrue(
            "expected at least the resolver's two sliced assignments, found $assignments",
            assignments >= 2
        )
    }

    @Test
    fun `both publish paths slice through the union value`() {
        // The resolver and the cache read are the two places entries reach the
        // strip. Each builds the value and takes a mode slice from it.
        val wrapped = Regex("""ResolvedBrowseCatalog\(""").findAll(viewModel).count()
        val sliced = Regex("""\.forMode\(""").findAll(viewModel).count()
        assertTrue(
            "expected one ResolvedBrowseCatalog per publish path, found $wrapped",
            wrapped >= 2
        )
        assertTrue(
            "expected one forMode slice per publish path, found $sliced",
            sliced >= 2
        )
    }

    @Test
    fun `the strip is never sliced by hand`() {
        // browseEntriesFor is the correct helper, but calling it from the
        // ViewModel means remembering to, once per publish path — the property
        // that failed. The value type performs the slice itself.
        assertTrue(
            "SearchViewModel should slice through ResolvedBrowseCatalog, not call " +
                "browseEntriesFor directly",
            !viewModel.contains("browseEntriesFor(")
        )
    }
}
