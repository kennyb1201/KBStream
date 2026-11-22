package com.kennyb1201.kbstream.ui.home

import com.kennyb1201.kbstream.data.addon.MetaPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The catalog paint-first blob (spec 2026-11-21 F2).
 *
 * It deliberately reuses the pinned snapshot's codec, so these cases pin the two
 * things that are specific to the catalog set: a length the pinned batch's
 * twelve-rail cap would truncate, and a scope that separates profiles and their
 * kids ceilings.
 */
class HomeCatalogSnapshotCodecTest {

    private fun item(id: String) = MetaPreview(
        id = id,
        type = "movie",
        name = "Title $id",
        poster = "https://img/$id.jpg",
        background = "https://img/$id-bg.jpg",
        releaseInfo = "2024"
    )

    private fun rail(key: String, items: List<MetaPreview>) = Rail(
        addonName = "Addon",
        catalogName = "Rail $key",
        type = "movie",
        items = items,
        catalogId = key,
        baseUrl = "https://addon",
        ranked = true
    )

    @Test
    fun `a catalog set round-trips in order with the card fields it needs`() {
        val rails = listOf(
            rail("one", listOf(item("a"), item("b"))),
            rail("two", listOf(item("c")))
        )

        val decoded = HomeCatalogSnapshot.decode(HomeCatalogSnapshot.encode(rails))

        assertEquals(listOf("one", "two"), decoded.map { it.catalogId })
        assertEquals(listOf("a", "b"), decoded[0].items.map { it.id })
        assertEquals("https://img/a.jpg", decoded[0].items[0].poster)
        assertEquals("2024", decoded[0].items[0].releaseInfo)
        assertTrue(decoded[0].ranked)
    }

    @Test
    fun `more catalogs than the pinned batch's cap still round-trip`() {
        val rails = (1..20).map { index -> rail("catalog_$index", listOf(item("id$index"))) }

        val decoded = HomeCatalogSnapshot.decode(HomeCatalogSnapshot.encode(rails))

        assertEquals(
            "a viewer with many add-ons must not lose their tail from the snapshot",
            20,
            decoded.size
        )
        assertEquals("catalog_20", decoded.last().catalogId)
    }

    @Test
    fun `items are capped the same way as the pinned snapshot`() {
        val many = (1..60).map { index -> item("id$index") }

        val decoded = HomeCatalogSnapshot.decode(
            HomeCatalogSnapshot.encode(listOf(rail("one", many)))
        )

        assertEquals(40, decoded[0].items.size)
    }

    @Test
    fun `the scope separates profiles and their ceilings`() {
        assertNotEquals(
            "a kids profile must never paint the adult profile's catalog rows",
            HomeCatalogSnapshot.scopeOf("p1", kidsCeiling = true),
            HomeCatalogSnapshot.scopeOf("p1", kidsCeiling = false)
        )
        assertNotEquals(
            "two profiles must not share a blob",
            HomeCatalogSnapshot.scopeOf("p1", false),
            HomeCatalogSnapshot.scopeOf("p2", false)
        )
        assertEquals(
            "the same profile and ceiling must read back the same key",
            HomeCatalogSnapshot.scopeOf("p1", false),
            HomeCatalogSnapshot.scopeOf("p1", false)
        )
    }

    @Test
    fun `an unreadable blob decodes to nothing instead of throwing`() {
        assertEquals(0, HomeCatalogSnapshot.decode(null).size)
        assertEquals(0, HomeCatalogSnapshot.decode("").size)
        assertEquals(0, HomeCatalogSnapshot.decode("R\tonly").size)
    }
}
