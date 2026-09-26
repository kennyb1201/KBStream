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
}
