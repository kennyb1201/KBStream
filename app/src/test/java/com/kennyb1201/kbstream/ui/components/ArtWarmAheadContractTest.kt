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
            // A LazyListItemInfo reports a MAIN-AXIS size only, so the box is
            // handed in by the caller that knows it - and a zero/unknown box
            // warms nothing rather than guessing one.
            warm.contains("sizePx: IntSize") &&
                warm.contains("if (last < 0 || size.width <= 0 || size.height <= 0) return@collect")
        )
        assertTrue(
            "and the snapshot is the last laid-out index, so the window moves with the scroll",
            warm.contains("layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1")
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

    @Test
    fun `a grid with header rows is warmed by KEY, not by index`() {
        assertTrue(
            "the key is what the grid reports for its last laid-out cell, and it is " +
                "looked up in the caller's own ordering",
            warm.contains("(last?.key as? String) to (last?.size?.width ?: 0)") &&
                warm.contains("val order = entries.map { it.first }") &&
                warm.contains("val position = order.indexOf(lastKey)")
        )
        assertTrue(
            "a key the map does not know (a header, a status row) warms nothing",
            warm.contains("if (position < 0) return@collect")
        )
        assertTrue(
            "the artwork's box comes from the measured cell WIDTH, because a poster " +
                "cell is taller than the poster it holds",
            warm.contains("sizeFor: (Int) -> IntSize")
        )
    }

    @Test
    fun `phone grids warm their next cells and file landscape answers`() {
        val phoneLibrary = source("com/kennyb1201/kbstream/ui/mobile/LibraryScreenMobile.kt")
        assertTrue(
            "portrait warms at the tile's own 2:3 box",
            phoneLibrary.contains("sizeFor = { cellWidth ->") &&
                phoneLibrary.contains("IntSize(cellWidth, (cellWidth * 3) / 2)")
        )
        assertTrue(
            "and landscape files the resolver's answers instead",
            phoneLibrary.contains("PrefetchLandscapeAhead(")
        )
        val phoneCatalog = source("com/kennyb1201/kbstream/ui/mobile/CatalogGridScreenMobile.kt")
        assertTrue(
            "the paged grid keys its entries exactly as it keys its cells",
            phoneCatalog.contains("placeholder#\$index") &&
                phoneCatalog.contains("PrefetchLandscapeAhead(")
        )
    }

    @Test
    fun `landscape prefetch goes through the one resolver, bounded and memo-first`() {
        val landscape = source("com/kennyb1201/kbstream/ui/components/GlobalLandscapeArt.kt")
        assertTrue(
            "a filed answer is not resolved again",
            landscape.contains("if (memo.containsKey(request.key)) return@forEach")
        )
        assertTrue(
            "and a failed prefetch changes nothing: the card resolves it itself",
            landscape.contains("runCatchingCancellable { resolve(context, request) }")
        )
        val tvLibrary = source("com/kennyb1201/kbstream/ui/library/LibraryScreen.kt")
        assertTrue(
            "the TV grid's landscape branch prefetches too",
            tvLibrary.contains("PrefetchLandscapeAhead(") &&
                tvLibrary.contains("LandscapeArtRequest(id = artId, type = item.mediaType)")
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
