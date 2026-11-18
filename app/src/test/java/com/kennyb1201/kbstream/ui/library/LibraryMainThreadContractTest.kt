package com.kennyb1201.kbstream.ui.library

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Library does its bulk work off the main thread, and rebuilds only what moved.
 *
 * Spec 2026-11-18, Library main-thread work. Two costs, both invisible in review:
 *
 *  1. `refresh()` runs on `viewModelScope` (main) and synchronously reads and
 *     `JSONArray`-parsed My List, the lists blob and every local list's items -
 *     and the phone calls it on EVERY visit. Entry latency grew with the library's
 *     size, on the thread drawing the frame.
 *  2. `pushDisplay()` rebuilt all four display lists on every publish. An
 *     enrichment that touched only the open personal list re-sorted and
 *     re-applied the maps to three lists whose rows had not changed, handing the
 *     grid new list identities mid-scroll - recomposition, and a placement
 *     animation pass, over rows nobody touched.
 *
 * Neither is observable without a trace or a profiler, so the shape is pinned:
 * where the parse is allowed to run, and which sections a publish may rebuild.
 */
class LibraryMainThreadContractTest {

    private val vm = source(VM)
    private val models = source(MODELS)

    // ── F1: the parse is off the main thread, and cached ─────────────────

    @Test
    fun `the local half of refresh parses off the main thread`() {
        val block = vm.substringAfter("// OFF THE MAIN THREAD (spec 2026-11-18, Library F1)")
            .substringBefore("localListFlatCache = localListItems")
        assertTrue(
            "the read + JSON parse of every local list is inside the IO hop",
            block.contains("withContext(Dispatchers.IO) {") &&
                block.contains("LocalLibraryStore.userLists(appContext)") &&
                block.contains("LocalLibraryStore.myList(appContext)") &&
                block.contains("LocalLibraryStore.listItems(appContext, it.id)")
        )
        assertFalse(
            "and none of the three reads leaked back out onto the main dispatcher",
            block.substringBefore("withContext(Dispatchers.IO) {").contains("LocalLibraryStore.")
        )
    }

    @Test
    fun `recomputeAll parses the local lists in its own hop, not before it`() {
        val body = block(vm, "private fun recomputeAll(")
        val launch = body.indexOf("viewModelScope.launch {")
        val io = body.indexOf("withContext(Dispatchers.IO) {")
        assertTrue("there is one launch", launch >= 0)
        assertTrue(
            "the per-list parse is INSIDE it: it used to run on main in front of " +
                "the press that triggered it",
            io > launch &&
                body.substring(io).contains("LocalLibraryStore.listItems(appContext, it.id)")
        )
        assertFalse(
            "with nothing parsed before the launch",
            body.substringBefore("viewModelScope.launch {").contains("LibraryStore.listItems")
        )
    }

    @Test
    fun `S3 - an unchanged blob is served from the last parse`() {
        val read = block(models, "private fun readListOrNull(")
        assertTrue(
            "the raw text is the version, so a matching string is the proof nothing " +
                "was written",
            read.contains("lastParsed[storeKey]?.let { (shown, rows) ->") &&
                read.contains("if (shown == raw) return rows.toMutableList()")
        )
        assertTrue(
            "and a fresh parse is what fills it",
            read.contains("lastParsed[storeKey] = raw to parsed")
        )
        assertFalse(
            "the JSONArray parse is after the cache check, so a hit never reaches it",
            read.indexOf("JSONArray(raw)") < read.indexOf("lastParsed[storeKey]")
        )
    }

    // ── F2: only the lists whose rows moved are rebuilt ──────────────────

    @Test
    fun `pushDisplay rebuilds a section only when it is asked for`() {
        val push = squash(block(vm, "private fun pushDisplay("))
        listOf("ALL" to "allItems", "LOCAL" to "localItems", "WATCHLIST" to "watchlistItems")
            .forEach { (section, field) ->
                assertTrue(
                    "$section is gated on the section set and otherwise keeps the value " +
                        "it had, which is what preserves its identity",
                    push.contains("DisplaySection.$section in sections") &&
                        push.contains("state.$field")
                )
            }
        assertTrue(
            "the open list is gated the same way",
            push.contains("DisplaySection.SELECTED in sections") &&
                push.contains("state.selectedListItems")
        )
        assertTrue(
            "and the default is every section, so a mutation path that changed any " +
                "row cannot silently publish a stale list",
            vm.contains("private fun pushDisplay(sections: Set<DisplaySection> = ALL_SECTIONS)")
        )
    }

    @Test
    fun `the enrichment publish rebuilds the lists that hold its rows`() {
        val enrich = vm.substringAfter("private fun enrich(")
        assertTrue(
            "the decision is the enriched batch's own keys",
            enrich.contains("pushDisplay(sectionsTouching(items))")
        )
        val touch = block(vm, "private fun sectionsTouching(")
        listOf("canonicalAll", "canonicalLocal", "canonicalWatchlist", "canonicalListItems")
            .forEach { canonical ->
                assertTrue(
                    "$canonical is tested for an intersection, not rebuilt blindly",
                    touch.contains("touched($canonical)")
                )
            }
        assertTrue(
            "by the store's own identity, so a list is never missed for a spelling " +
                "the dedupe key would have matched",
            touch.contains("LocalLibraryStore.dedupeKey(it)")
        )
    }

    @Test
    fun `the maps keep their identity when an enrichment changes nothing`() {
        val enrich = squash(vm.substringAfter("private fun enrich("))
        listOf("ratings", "posters", "years", "watchedKeys", "partiallyWatchedKeys").forEach { map ->
            assertTrue(
                "$map is guarded by its own contents, so an unchanged map keeps its " +
                    "instance: every card that reads it recomposes otherwise",
                enrich.contains("if ($map == current.$map)")
            )
            assertFalse(
                "and it is not handed over unconditionally - that was the shape " +
                    "the guard exists to stop",
                enrich.contains("$map = $map,")
            )
        }
    }

    @Test
    fun `the refresh and list-open publishes name their own sections`() {
        assertTrue(
            "the local half of refresh: ALL (merged from it) and LOCAL",
            vm.contains("pushDisplay(setOf(DisplaySection.ALL, DisplaySection.LOCAL))")
        )
        // SELECTED is in this set on purpose: refresh re-derives the OPEN
        // list's rows too (LS-P2-17), so leaving it out would publish the pane
        // stale even though its canonical rows had just been replaced.
        assertTrue(
            "the remote watchlist: ALL, WATCHLIST and the re-derived open list - " +
                "never the local one, whose rows this half does not touch",
            vm.contains(
                "setOf(DisplaySection.ALL, DisplaySection.WATCHLIST, DisplaySection.SELECTED)"
            )
        )
        assertTrue(
            "an opened list: the sections that actually hold its rows",
            vm.contains("pushDisplay(sectionsTouching(items))")
        )
        // A mutation is NOT a narrowed publish: a removal clears the local twin
        // and the watchlist copy, and a list edit rewrites the open pane, so
        // recomputeAll keeps the full rebuild on purpose.
        val recompute = vm.substringAfter("private fun recomputeAll(")
            .substringBefore("\n    /**")
        assertTrue(
            "the mutation path rebuilds everything - narrowing belongs to the " +
                "paths that know which rows moved",
            squash(recompute).contains("pushDisplay()") &&
                !recompute.contains("pushDisplay(setOf(")
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /** One function's source, by matching its declaration and balancing braces. */
    private fun block(source: String, declaration: String): String {
        val at = source.indexOf(declaration)
        require(at >= 0) { "missing: $declaration" }
        val bodyStart = source.indexOf("{", at)
        require(bodyStart > at) { "no body: $declaration" }
        var depth = 0
        for (i in bodyStart until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(at, i + 1)
                }
            }
        }
        error("unbalanced: $declaration")
    }

    /** Whitespace runs to one space, so a pin does not depend on line wrapping. */
    private fun squash(text: String): String = text.replace(Regex("\\s+"), " ")

    private fun source(relative: String): String {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate =
                    File(dir, "${prefix}src/main/java/com/kennyb1201/kbstream/$relative")
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("source not found above ${System.getProperty("user.dir")}: $relative")
    }

    private companion object {
        const val VM = "ui/library/LibraryViewModel.kt"
        const val MODELS = "data/library/LibraryModels.kt"
    }
}
