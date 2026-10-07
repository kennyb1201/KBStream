package com.kennyb1201.kbstream.ui.library

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Library / search P2s. Each is a source-level contract: the fixes live inside
 * Android-bound ViewModels and network clients, but a missing one is a silent
 * user-visible bug (a blank pane, a stale list, a phantom row), so the wiring is
 * pinned here.
 */
class LibraryP2ContractTest {

    @Test
    fun `switching lists bumps the request version`() {
        val body = functionBody(LIBRARY_VM, "private fun loadListItems(")
        assertTrue(
            "the list loader must INCREMENT, not just read, the version",
            body.contains("val version = ++requestVersion")
        )
        assertFalse(
            "reading without incrementing let a slower earlier list win the pane",
            body.contains("val version = requestVersion\n")
        )
    }

    @Test
    fun `a refresh keeps the open personal list instead of blanking it`() {
        val body = functionBody(LIBRARY_VM, "fun refresh(")
        assertTrue(
            "the open list must be re-derived on the refresh",
            body.contains("fetchListItems(it)")
        )
        assertFalse(
            "refresh must no longer blank the open list's rows",
            body.contains("canonicalListItems = emptyList()")
        )
    }

    @Test
    fun `hiding an already-hidden title folds the spellings instead of duplicating`() {
        val body = functionBody(HIDDEN, "fun hide(")
        assertTrue(
            "the entry being merged must be read before it is filtered out",
            body.contains("val existing = current.firstOrNull")
        )
        assertFalse(
            "searching the already-filtered kept list can never find a match",
            body.contains("kept.firstOrNull")
        )
        assertTrue(body.contains("existing?.keys"))
    }

    @Test
    fun `an MDBList row with no id is dropped, not merged into a phantom`() {
        val body = functionBody(MDBLIST, "private fun entryFromJson(")
        assertTrue(
            "id-less rows collapsed onto one key and drew as a phantom row",
            body.contains("if (imdbId == null && tmdbId == null) return null")
        )
    }

    @Test
    fun `the no-matches card counts raw hits, not the hidden-filtered ones`() {
        val src = source(SEARCH_SCREEN)
        assertTrue(
            "totalCount must be computed from the raw result lists",
            src.contains("resultsRaw.size + actorResults.size")
        )
    }

    @Test
    fun `the browse submenu grid resets its scroll per category`() {
        val src = source(BROWSE)
        assertTrue(
            "the grid state must be keyed on the category",
            src.contains("remember(categoryKey) { LazyGridState() }")
        )
    }

    @Test
    fun `removing a row refreshes both canonicals`() {
        val body = functionBody(LIBRARY_VM, "fun removeItem(")
        assertTrue(
            "the local branch must drop the remote watchlist twin too",
            body.contains("val removedKey = LocalLibraryStore.dedupeKey(item)")
        )
        val mdbBranch = body.substring(
            body.indexOf("LibrarySource.MDBLIST_WATCHLIST ->"),
            body.indexOf("LibrarySource.MDBLIST_LIST ->")
        )
        assertTrue(
            "the remote branch must refresh the local canonical too",
            mdbBranch.contains("canonicalLocal = LocalLibraryStore.myList(appContext)")
        )
    }

    @Test
    fun `the enrichment warms the watched cache before it reads it`() {
        val body = functionBody(LIBRARY_VM, "private fun enrich(")
        val preload = body.indexOf("preloadAndGetWatchedKeys(")
        val read = body.indexOf("isWatchedCached(")
        assertTrue("enrich must preload the watched cache", preload >= 0)
        assertTrue("and the preload must precede the per-row read", preload in 0 until read)
    }

    @Test
    fun `the library grid draws the eye badge for a started title`() {
        // Reported: the Library was the one poster surface with no eye marker -
        // a show watched halfway looked exactly like one never opened. The
        // enrichment only ever resolved the finished flag, so the grid had
        // nothing to draw even though every other screen shows both.
        val body = functionBody(LIBRARY_VM, "private fun enrich(")
        val preload = body.indexOf("preloadAndGetPartiallyWatchedKeys(")
        val read = body.indexOf("isPartiallyWatchedCached(")
        assertTrue("enrich must preload the partial-watch flag too", preload >= 0)
        assertTrue("and the preload must precede the per-row read", preload in 0 until read)
        assertTrue(
            "a finished title keeps the checkmark: the eye is never claimed for one",
            body.contains("watched == null &&")
        )

        val screen = source(LIBRARY_SCREEN)
        assertTrue(
            "the grid must hand the eye flag to the shared poster card",
            screen.contains("isPartiallyWatched = item.watchedKey() in partiallyWatchedKeys")
        )
        assertTrue(
            "and each pane must pass the resolved set through",
            screen.contains("partiallyWatchedKeys = state.partiallyWatchedKeys")
        )
    }

    @Test
    fun `the watchlist fetch follows the pagination cursor`() {
        val body = functionBody(MDBLIST, "suspend fun getWatchlist(")
        assertTrue("the watchlist must page", body.contains("next_cursor"))
        assertTrue("and pass the cursor through", body.contains("append(\"&cursor=\")"))
        assertTrue(
            "and keep paging until the cursor is exhausted",
            body.contains("} while (!cursor.isNullOrBlank() && guard < 30)")
        )
    }

    @Test
    fun `every library mutator is serialised`() {
        val src = source(MODELS)
        listOf(
            "fun addToMyList(",
            "fun removeFromMyList(",
            "fun createList(",
            "fun renameList(",
            "fun deleteList(",
            "fun addToLocalList(",
            "fun removeFromLocalList("
        ).forEach { signature ->
            val idx = src.indexOf(signature)
            assertTrue("missing $signature", idx >= 0)
            val window = src.substring((idx - 800).coerceAtLeast(0), idx)
            assertTrue(
                "$signature must be @Synchronized, or two writers lose an entry",
                window.lastIndexOf("@Synchronized") > window.lastIndexOf("fun ")
            )
        }
    }

    @Test
    fun `an invalidation retires a fetch already in flight`() {
        val src = source(MDBLIST)
        assertTrue(src.contains("private var snapshotGeneration = 0"))
        val fetch = src.indexOf("val generationAtStart = snapshotGeneration")
        val gate = src.indexOf("if (!result.isEmpty && generationAtStart == snapshotGeneration)")
        assertTrue("the fetch must stamp the generation it started under", fetch >= 0)
        assertTrue("and refuse to cache over a later invalidation", gate > fetch)
        assertTrue(
            "the invalidate must bump the generation",
            src.contains("snapshotGeneration += 1")
        )
    }

    private fun source(path: String): String {
        val file = File(findSourceRoot(), path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

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

    /** The body of the function starting at [signature], up to its closing brace. */
    private fun functionBody(path: String, signature: String): String {
        val src = source(path)
        val start = src.indexOf(signature)
        assertTrue("source missing function: $signature", start >= 0)
        val brace = src.indexOf('{', start)
        assertTrue("no body for function: $signature", brace >= 0)
        val rest = src.substring(brace + 1)
        val end = Regex("\\n {4}\\S").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    private companion object {
        const val LIBRARY_VM = "com/kennyb1201/kbstream/ui/library/LibraryViewModel.kt"
        const val LIBRARY_SCREEN = "com/kennyb1201/kbstream/ui/library/LibraryScreen.kt"
        const val HIDDEN = "com/kennyb1201/kbstream/data/library/HiddenTitles.kt"
        const val MDBLIST = "com/kennyb1201/kbstream/data/mdblist/MdbListClient.kt"
        const val MODELS = "com/kennyb1201/kbstream/data/library/LibraryModels.kt"
        const val SEARCH_SCREEN = "com/kennyb1201/kbstream/ui/search/SearchScreen.kt"
        const val BROWSE = "com/kennyb1201/kbstream/ui/search/BrowseBrowser.kt"
    }
}
