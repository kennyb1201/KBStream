package com.kennyb1201.kbstream.ui.search

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deleting ONE recent search.
 *
 * Clear-all was the only way to take a single committed query off the strip, so
 * removing a mistyped or private search cost the other nine. The rule lives in
 * [withoutRecentSearch] and is exercised directly; the long-press menu that
 * reaches it is pinned from source on both surfaces, because neither can be
 * composed in a JVM test.
 */
class RecentSearchesTest {

    private val recents = listOf("alien", "heist", "cyberpunk movie")

    @Test
    fun `deleting one recent leaves the others`() {
        assertEquals(
            "the pressed chip goes, the rest keep their order",
            listOf("heist", "cyberpunk movie"),
            withoutRecentSearch(recents, "alien")
        )
        assertEquals(
            "and any other chip can be the one that goes",
            listOf("alien", "cyberpunk movie"),
            withoutRecentSearch(recents, "heist")
        )
        assertEquals(
            "a multi-word query is removed whole",
            listOf("alien", "heist"),
            withoutRecentSearch(recents, "cyberpunk movie")
        )
    }

    @Test
    fun `the match is the one commitSearch de-dupes by`() {
        assertEquals(
            "case does not make it a different recent",
            listOf("heist", "cyberpunk movie"),
            withoutRecentSearch(recents, "ALIEN")
        )
        assertEquals(
            "nor does surrounding space from a pasted query",
            listOf("heist", "cyberpunk movie"),
            withoutRecentSearch(recents, "  alien ")
        )
    }

    @Test
    fun `a query that is not in recents drops nothing`() {
        assertEquals(
            "an unknown or already-forgotten query must not touch the list",
            recents,
            withoutRecentSearch(recents, "matrix")
        )
        assertEquals("and an empty strip stays empty", emptyList<String>(), withoutRecentSearch(emptyList(), "alien"))
    }

    // ── the menus that reach it ──────────────────────────────────────────

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

    private fun slice(text: String, start: String, end: String): String {
        val from = text.indexOf(start)
        assertTrue("start marker missing: $start", from >= 0)
        val to = text.indexOf(end, from + start.length)
        return text.substring(from, if (to > from) to else text.length)
    }

    @Test
    fun `the view model forgets just that query and persists the rest`() {
        val body = slice(
            viewModel(),
            "fun removeRecentSearch(query: String) {",
            "\n    }"
        )
        assertTrue(
            "the removal goes through the tested rule",
            body.contains("withoutRecentSearch(_recentSearches.value, query)")
        )
        assertTrue(
            "and the shorter list is written back to disk, or the chip returns on restart",
            body.contains("persistRecentSearches()")
        )
        assertFalse(
            "clear-all must not be what a single delete does",
            body.contains("_recentSearches.value = emptyList()")
        )
    }

    @Test
    fun `both surfaces offer the delete from a long press`() {
        listOf(TV, MOBILE).forEach { path ->
            val screen = source(path)
            assertTrue(
                "$path must open a menu on a recent chip's long press",
                screen.contains("onLongClick = { recentMenu = recent }")
            )
            assertTrue(
                "$path must offer exactly the recents action",
                screen.contains("label = \"Remove from recents\"")
            )
            assertTrue(
                "$path must call the model's single-item delete",
                screen.contains("viewModel.removeRecentSearch(recent)")
            )
        }
        // The two surfaces use their own menu component, and both are the ones
        // a long press already raises elsewhere on that surface.
        assertTrue(
            "TV uses the TV menu",
            source(TV).contains("PosterContextMenu(") &&
                source(TV).contains("subtitle = \"Recent search\"")
        )
        assertTrue(
            "the phone uses the touch sheet",
            source(MOBILE).contains("subtitle = \"Recent search\"")
        )
    }

    private companion object {
        const val VIEW_MODEL = "com/kennyb1201/kbstream/ui/search/SearchViewModel.kt"
        const val TV = "com/kennyb1201/kbstream/ui/search/SearchScreen.kt"
        const val MOBILE = "com/kennyb1201/kbstream/ui/mobile/SearchScreenMobile.kt"
    }
}
