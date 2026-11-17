package com.kennyb1201.kbstream.ui.components

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rail-size warming, generalised (round four F5, 2026-11-16).
 *
 * Round three's F3 warmed Home's rails, where a card's drawn size is a constant
 * in HomeRailMetrics. Everywhere else it is not: a grid cell is whatever
 * `GridCells.Adaptive` produced from the pane, a phone rail's card is what the
 * density made of a dp. A warm at a guessed size writes a cache entry beside the
 * one the card will read - a decode spent for nothing.
 *
 * So the window takes the size from the surface: the grid's own laid-out cell,
 * or the poster size the card is actually drawn at. Nothing here polls Coil and
 * nothing scans the cache; the only reads are the lazy list's layout info.
 */
class ArtWarmAheadContractTest {

    private val warm by lazy { source("com/kennyb1201/kbstream/ui/components/ArtWarmAhead.kt") }
    private val library by lazy { source("com/kennyb1201/kbstream/ui/library/LibraryScreen.kt") }
    private val catalog by lazy { source("com/kennyb1201/kbstream/ui/home/CatalogGridScreen.kt") }

    @Test
    fun `the window is bounded, de-duplicated and silent when it misses`() {
        assertTrue("four decodes at a time, not a frame's worth", warm.contains("private const val MAX_PARALLEL = 4"))
        assertTrue("one warm per (url, size) even if the grid wiggles", warm.contains("if (!inFlight.add(key)) return"))
        assertTrue(
            "and a failed warm is not an error - the card still decodes on first sight",
            warm.contains("catch (_: Exception) {")
        )
        assertTrue(
            "a warm that HIT the memory cache bought nothing and is not counted",
            warm.contains("result.dataSource != DataSource.MEMORY_CACHE")
        )
        assertTrue(
            "the remembered set dies with the cache it describes",
            warm.contains("private var doneEpoch: Long = -1L") && warm.contains("MemoryPressure.imageCacheRevision()")
        )
    }

    @Test
    fun `the size is measured from the list, never guessed`() {
        assertTrue(
            "the laid-out cell is the size the coming cell will be drawn at",
            warm.contains("info.visibleItemsInfo.firstOrNull()?.size ?: IntSize.Zero") ||
                warm.contains("visibleItemsInfo.firstOrNull()?.size")
        )
        assertTrue(
            "and the snapshot is the last laid-out index, so the window moves with the scroll",
            warm.contains("info.visibleItemsInfo.lastOrNull()?.index ?: -1")
        )
        assertTrue(
            "the notification is a snapshotFlow, so layout changes are observed",
            warm.contains("snapshotFlow {")
        )
        assertFalse(
            "nothing warms before layout: there is no size to warm at",
            warm.contains("ArtWarm.warmAll(context, urls, 0,")
        )
    }

    @Test
    fun `the Library grid warms the next row at the poster's own size`() {
        val site = library
            .substringAfter("// The posters of the row past the one on screen")
            .substringBefore("LazyVerticalGrid(")
        assertTrue(
            "the window is the grid's state and the list's size: $site",
            site.contains("WarmGridAhead(") && site.contains("state = gridState")
        )
        assertTrue(
            "at the Poster Size dp, which is what the card is given",
            site.contains("IntSize(posterSize.width.roundToPx(), posterSize.height.roundToPx())")
        )
        assertTrue(
            "portrait only: landscape draws resolved art, not the poster",
            site.contains("if (!landscapeNow)")
        )
        assertTrue(
            "and a row with no poster warms nothing",
            site.contains("{ index -> items.getOrNull(index)?.posterUrl }")
        )
    }

    @Test
    fun `the catalog grid warms the next paged row without loading a page`() {
        val site = catalog
            .substringAfter("// The row past the one on screen, decoded before it is reached")
            .substringBefore("LazyVerticalGrid(")
        assertTrue(
            "the tile's own dp in both shapes: $site",
            site.contains("IntSize(tileWidth.roundToPx(), tileHeight.roundToPx())")
        )
        assertTrue(
            "peek, so a placeholder is a skip rather than a page load",
            site.contains("{ index -> lazyItems.peek(index)?.poster }")
        )
    }

    private fun source(relative: String): String {
        val file = File(mainRootPath(), relative)
        if (!file.isFile) throw AssertionError("source missing: " + file.absolutePath)
        return file.readText()
    }

    private fun mainRootPath(): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, prefix + "src/main/java")
                if (candidate.isDirectory) return candidate.absolutePath
            }
            dir = dir.parentFile
        }
        throw AssertionError("main source root not found")
    }
}
