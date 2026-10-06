package com.kennyb1201.kbstream.ui.search

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The search rails' LazyRow keys, both of them.
 *
 * A title id is only unique WITHIN a type: TMDB, and an add-on's `/search`, can
 * return the same id as both a movie and a series. A bare-id key then collides,
 * and `LazyRow` throws on duplicate keys - the screen dies instead of drawing
 * the second tile, which is the crash the titles rail's key already carries a
 * comment about. The add-on rail was the one that was missed: its own list can
 * hold both flavors, so the rail index does not disambiguate them either.
 *
 * LS-P2-3 then removed the remaining POSITIONAL component: the add-on rails
 * publish one by one as each add-on answers, so a slow add-on shifts every rail
 * after it. A key carrying the position reads as a brand-new item whenever that
 * happens and throws away the rail's remembered scroll and focus, and the inner
 * row key had the same problem from its own rail's position. Both are keyed by
 * identity now.
 *
 * Read from the source because a key is a string expression, not a callable: the
 * only other way to reach it is to render the rail.
 */
class SearchRailKeyContractTest {

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

    @Test
    fun `both search rails qualify their row keys with the title's type`() {
        val screen = source(SCREEN)
        assertTrue(
            "the titles rail must keep the type in its key",
            screen.contains("\"${'$'}{result.type}:${'$'}{result.id}\"")
        )
        assertTrue(
            "and the add-on rail must too - its own list can hold a movie and " +
                "a series under one id, so the rail index does not separate them",
            screen.contains("\"addon:${'$'}{result.type}:${'$'}{result.id}\"")
        )
    }

    @Test
    fun `no add-on rail key carries a positional index`() {
        val screen = source(SCREEN)
        assertFalse(
            "the add-on rail's inner row key must not carry the rail index - " +
                "the type-qualified id is already unique inside one rail",
            screen.contains("addon:${'$'}index:")
        )
        assertTrue(
            "the rail itself is keyed by (add-on, label, type)",
            screen.contains("key = \"addons_rail:${'$'}{group.addonName}:\"")
        )
        assertFalse(
            "and must not fall back to its position",
            screen.contains("${'$'}{group.catalogType ?: \"\"}:${'$'}index")
        )
    }

    @Test
    fun `an identity-keyed rail is unique because the view model coalesces it`() {
        val viewModel = source(MODELS)
        assertTrue(
            "a duplicate (add-on, label, type) triple would be a duplicate " +
                "LazyColumn key, which throws - the view model merges them first",
            viewModel.contains("private fun coalesceAddonGroups(")
        )
        assertTrue(
            "and the merged list is what both publish paths hand to the screen",
            viewModel.contains("val snapshot = coalesceAddonGroups(") &&
                viewModel.contains("return@coroutineScope coalesceAddonGroups(")
        )
    }

    private companion object {
        const val SCREEN = "com/kennyb1201/kbstream/ui/search/SearchScreen.kt"
        const val MODELS = "com/kennyb1201/kbstream/ui/search/SearchViewModel.kt"
    }
}
