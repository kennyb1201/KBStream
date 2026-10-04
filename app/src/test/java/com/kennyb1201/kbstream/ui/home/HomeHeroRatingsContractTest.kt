package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Home hero deliberately shows NO MDBList rating tokens.
 *
 * The hero's single ellipsized metadata line carries only the item's own IMDb
 * rating, year, certification, runtime and first genre, so its tail is never
 * clipped on a TV; the detail page owns the full rating chip strip. The hero
 * used to fold MDBList tokens into that line ("RT 92%", "TMDB 8.1") and fetch
 * the figures alongside the rest of the hero meta, and either half of that is
 * invisible until someone reads the screen - so both are asserted absent to
 * stop the tokens creeping back.
 */
class HomeHeroRatingsContractTest {

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
        const val SCREEN = "com/kennyb1201/kbstream/ui/home/HomeScreen.kt"
    }

    @Test
    fun `the hero carries no rating tokens`() {
        val screen = source(SCREEN)
        assertFalse(
            "the hero must not append MDBList tokens to its metadata line",
            screen.contains("heroRatingTokens(")
        )
        assertFalse(
            "the hero must not draw the rating chip strip",
            screen.contains("MdbListRatingChips(")
        )
        assertFalse(
            "HomeScreen must not thread hero ratings into the hero",
            screen.contains("heroRatings")
        )
    }

    @Test
    fun `runtime rides the first metadata line`() {
        val screen = source(SCREEN)
        val start = screen.indexOf("val heroInfoParts =")
        assertTrue("heroInfoParts must exist", start >= 0)
        val end = screen.indexOf("val heroInfo =", start)
        assertTrue("heroInfo must follow heroInfoParts", end > start)
        assertTrue(
            "runtime must be on the first metadata line",
            screen.substring(start, end).contains("runtime")
        )
    }

    @Test
    fun `the view model no longer fetches hero ratings`() {
        val vm = source(VIEW_MODEL)
        assertFalse(
            "the hero ratings state must be gone",
            vm.contains("_heroRatings") || vm.contains("heroRatings")
        )
    }
}
