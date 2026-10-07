package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The grid a Home rail opens through "Open in Grid" draws the same tiles as the
 * rest of the app.
 *
 * Reported from the field, twice. First: with landscape mode on, its tiles were
 * square. The grid was the one poster grid left on `GridCells.Fixed(6)` sized
 * from the raw poster width, so a landscape card (210dp at Medium) was clamped
 * to the ~163dp cell while its height stayed the landscape height (~118dp) -
 * nearly square. That is the same defect `LibraryGridLandscapeContractTest`
 * pins for the Library grid, and it is fixed the same way.
 *
 * Second: the grid ignored the "Poster Titles / Years / Star Ratings" toggles
 * and Home's own landscape switch. Its caption was a hand-rolled title line
 * that showed the name whatever the toggles said, with no year or rating to
 * show at all; and because it asked only the everywhere switch, a viewer whose
 * Home rails are all landscape cards got posters the moment they opened a grid
 * from one of them.
 *
 * Both are pinned at the source, where the arithmetic cannot see the mistake.
 */
class CatalogGridLandscapeContractTest {

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
    fun `the grid sizes its cells to the tile it draws`() {
        val src = source(GRID)

        assertTrue(
            "the adaptive cell size must be the tile width, or a landscape card " +
                "is clamped to a poster cell and reads as square",
            src.contains("columns = GridCells.Adaptive(minSize = tileWidth)")
        )
        assertTrue(
            "and that width must be the landscape one when the grid is landscape",
            src.contains(
                "if (landscape) landscapeTileWidth(posterSize.width) else posterSize.width"
            )
        )
        assertFalse(
            "a hardcoded column count cannot hold a landscape tile and ignores " +
                "the Poster Size setting",
            src.contains("GridCells.Fixed(")
        )
    }

    @Test
    fun `the grid follows Home's landscape rule, not only the everywhere switch`() {
        val src = source(GRID)

        assertTrue(
            "a Home surface asks the Home rule (see AppPreferences.homeLandscapeActive)",
            src.contains("val landscape = rememberHomeLandscape()")
        )
        assertTrue(
            "and the card must be told the same shape the cell was sized from",
            src.contains("landscape = landscape")
        )
        assertTrue(
            "the helper must be the Home one, or the Home-rails switch would " +
                "reshape nothing here",
            source(TILE_SIZE).contains(
                "return remember { AppPreferences.homeLandscapeActive(context) }"
            )
        )
        assertFalse(
            "while the shared card keeps knowing only the everywhere switch: " +
                "the Home switch must not reshape every surface",
            source(GLOBAL_CARD).contains("homeLandscapeActive")
        )
    }

    @Test
    fun `the placeholders take the shape the tiles will`() {
        val src = source(GRID)

        assertTrue(
            "the skeleton is what the viewer sees while the grid seeds, so it " +
                "must not be portrait-shaped in a landscape grid",
            src.contains("cellWidth = tileWidth") &&
                src.contains("cellHeight = tileHeight")
        )
    }

    @Test
    fun `the captions are the shared block, so the three toggles reach the grid`() {
        val src = source(GRID)

        assertTrue(
            "the grid must render its captions through PosterCaptions",
            src.contains("PosterCaptions(")
        )
        assertTrue(
            "a catalog preview's own year and rating are what the toggles show",
            src.contains("year = meta.yearOrNull?.toString()") &&
                src.contains("rating = meta.imdbRating?.toDoubleOrNull()")
        )
        assertFalse(
            "and it must not draw its own caption line: that is the version " +
                "that ignored all three toggles",
            src.contains("style = MaterialTheme.typography.labelSmall")
        )
    }

    private companion object {
        const val GRID = "com/kennyb1201/kbstream/ui/home/CatalogGridScreen.kt"
        const val GLOBAL_CARD =
            "com/kennyb1201/kbstream/ui/components/GlobalPosterCard.kt"
        const val TILE_SIZE =
            "com/kennyb1201/kbstream/ui/components/PosterSize.kt"
    }
}
