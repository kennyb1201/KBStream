package com.kennyb1201.kbstream.ui.library

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Library grid must size its cells to the shape `GlobalPosterCard` draws.
 *
 * Reported from the field: with "Landscape Posters Everywhere" on, the Library
 * posters looked square rather than landscape. The library was the one poster
 * grid that still sized its `GridCells.Adaptive` cells from the raw poster
 * width (Medium = 124dp). A landscape card wants 210dp (see
 * `landscapeTileWidth`), so it was clamped to the ~124dp cell while its height
 * stayed the landscape height (~118dp) - nearly square. Every other surface
 * already routes through `rememberPosterTileWidth`; this pins the Library to
 * that same rule at the source, where the arithmetic cannot see the mistake.
 */
class LibraryGridLandscapeContractTest {

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
    fun `the library grid sizes its cells to the landscape tile width`() {
        val src = source(LIBRARY)
        assertTrue(
            "the grid must follow the tile shape the card draws",
            src.contains("val tileWidth = rememberPosterTileWidth(posterSize.width)")
        )
        assertTrue(
            "the adaptive cell size must be the landscape tile width",
            src.contains("GridCells.Adaptive(minSize = tileWidth)")
        )
        assertFalse(
            "the grid must not size its cells from the raw poster width",
            src.contains("GridCells.Adaptive(minSize = posterSize.width)")
        )
    }

    private companion object {
        const val LIBRARY = "com/kennyb1201/kbstream/ui/library/LibraryScreen.kt"
    }
}
