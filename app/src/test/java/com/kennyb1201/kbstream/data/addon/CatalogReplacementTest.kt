package com.kennyb1201.kbstream.data.addon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dynamic addons swap catalog ids as their content changes. The bug this pins:
 * a reordered rail (BingeCat "Because you watched", curated lists) must keep
 * its per-catalog settings and Home arrangement slot across that swap instead
 * of dropping back to the default tail because its id-key no longer matches.
 */
class CatalogReplacementTest {

    private fun catalog(type: String, id: String, name: String = id) =
        ManifestCatalog(type = type, id = id, name = name)

    @Test
    fun `a swapped id pairs the newcomer with the removed catalog`() {
        val pairs = pairReplacedCatalogs(
            existing = listOf(catalog("series", "bwc-1"), catalog("movie", "trending")),
            fresh = listOf(catalog("movie", "trending"), catalog("series", "bwc-9"))
        )

        assertEquals(1, pairs.size)
        val (added, removed) = pairs.single()
        assertEquals("bwc-9", added.id)
        assertEquals("bwc-1", removed.id)
    }

    @Test
    fun `an unchanged manifest pairs nothing`() {
        val catalogs = listOf(catalog("series", "a"), catalog("movie", "b"))
        assertTrue(pairReplacedCatalogs(catalogs, catalogs).isEmpty())
    }

    @Test
    fun `pairs are matched in saved order against manifest order`() {
        val pairs = pairReplacedCatalogs(
            existing = listOf(catalog("series", "old-1"), catalog("series", "old-2")),
            fresh = listOf(catalog("series", "new-1"), catalog("series", "new-2"))
        )

        assertEquals(listOf("new-1" to "old-1", "new-2" to "old-2"), pairs.map { it.first.id to it.second.id })
    }

    @Test
    fun `a genuine addition with nothing removed is not paired`() {
        val pairs = pairReplacedCatalogs(
            existing = listOf(catalog("movie", "keep")),
            fresh = listOf(catalog("movie", "keep"), catalog("series", "brand-new"))
        )

        assertTrue(pairs.isEmpty())
    }

    @Test
    fun `only as many pairs as the smaller side are produced`() {
        val pairs = pairReplacedCatalogs(
            existing = listOf(catalog("series", "old-1"), catalog("series", "old-2"), catalog("series", "old-3")),
            fresh = listOf(catalog("series", "new-1"))
        )

        assertEquals(listOf("new-1" to "old-1"), pairs.map { it.first.id to it.second.id })
    }

    @Test
    fun `type is compared case-insensitively`() {
        val pairs = pairReplacedCatalogs(
            existing = listOf(catalog("Series", "old")),
            fresh = listOf(catalog("series", "new"))
        )
        assertEquals(1, pairs.size)
    }

    @Test
    fun `a reorder cannot pair a newcomer with a removed catalog of another type`() {
        // Two ids swap in the SAME refresh and the addon also reorders them.
        // Pairing across types matched the series newcomer with the removed
        // MOVIE rail (and vice versa), so each inherited the other's showOnHome
        // / customName — a rail the user hid came back on, renamed.
        val pairs = pairReplacedCatalogs(
            existing = listOf(
                catalog("movie", "movie-old"),
                catalog("series", "series-old")
            ),
            fresh = listOf(
                catalog("series", "series-new"),
                catalog("movie", "movie-new")
            )
        )

        assertEquals(
            listOf("series-new" to "series-old", "movie-new" to "movie-old"),
            pairs.map { it.first.id to it.second.id }
        )
    }

    @Test
    fun `a same-type reorder pairs by name, not by slot`() {
        // Both ids churn AND the manifest reorders them. Pairing purely by
        // position hands each newcomer the other rail's settings — the rail the
        // user renamed loses its name and a hidden rail comes back on.
        val pairs = pairReplacedCatalogs(
            existing = listOf(
                catalog("series", "search-1", name = "Search"),
                catalog("series", "trending-1", name = "Trending")
            ),
            fresh = listOf(
                catalog("series", "trending-2", name = "Trending"),
                catalog("series", "search-2", name = "Search")
            )
        )

        assertEquals(
            listOf("trending-2" to "trending-1", "search-2" to "search-1"),
            pairs.map { it.first.id to it.second.id }
        )
    }

    @Test
    fun `a content-embedded rename falls back to the positional pairing`() {
        // "Because you watched X" -> "...Y": nothing matches by name, so the
        // saved-order rule still carries the settings across.
        val pairs = pairReplacedCatalogs(
            existing = listOf(
                catalog("movie", "bwc-1", name = "Because you watched A"),
                catalog("movie", "bwc-2", name = "Because you watched B")
            ),
            fresh = listOf(
                catalog("movie", "bwc-8", name = "Because you watched C"),
                catalog("movie", "bwc-9", name = "Because you watched D")
            )
        )

        assertEquals(
            listOf("bwc-8" to "bwc-1", "bwc-9" to "bwc-2"),
            pairs.map { it.first.id to it.second.id }
        )
    }

    @Test
    fun `slots freed are only this addon's own, never a sibling addon's position`() {
        // Global order: two Cinemeta catalogs at 0/1, the BingeCat rail at 2,
        // an AIOStreams list at 3. Only the BingeCat rail's id changed.
        val globalOrder = mapOf(
            "cinemeta::movie::top" to 0,
            "cinemeta::series::top" to 1,
            "bingecat::movie::railA" to 2,
            "aiostreams::movie::list" to 3
        )

        val slots = slotsFreedByManifest(
            existingAddonKeys = setOf("bingecat::movie::railA"),
            manifestKeys = setOf("bingecat::movie::railB"),
            globalOrder = globalOrder
        )

        // 2, not [0, 1, 2, 3]: reusing index 0 is what planted the rail at the
        // top of Home on every refresh.
        assertEquals(listOf(2), slots)
    }

    @Test
    fun `an unchanged manifest frees no slots`() {
        val globalOrder = mapOf("addon::movie::a" to 0, "addon::series::b" to 1)

        assertTrue(
            slotsFreedByManifest(
                existingAddonKeys = setOf("addon::movie::a", "addon::series::b"),
                manifestKeys = setOf("addon::movie::a", "addon::series::b"),
                globalOrder = globalOrder
            ).isEmpty()
        )
    }

    @Test
    fun `a catalog removed with no stored position frees nothing`() {
        assertTrue(
            slotsFreedByManifest(
                existingAddonKeys = setOf("addon::movie::gone"),
                manifestKeys = emptySet(),
                globalOrder = emptyMap()
            ).isEmpty()
        )
    }
}
