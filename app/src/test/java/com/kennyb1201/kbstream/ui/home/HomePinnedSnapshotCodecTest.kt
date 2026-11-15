package com.kennyb1201.kbstream.ui.home

import com.kennyb1201.kbstream.data.addon.MetaPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The paint-first blob's codec (spec 2026-11-15, Home cold start F1).
 *
 * The snapshot is what puts the pinned rails on screen on a cold start before a
 * single request goes out, so a codec that quietly drops an item, reorders a rail
 * or loses a `null` poster corrupts the first frame the viewer sees - and it does
 * so silently, because the next build replaces the rails anyway. These cases pin
 * the round trip exactly: identity, order, rank, the optional fields, and the
 * separators (a title with a tab in it must not become two fields).
 */
class HomePinnedSnapshotCodecTest {

    private fun item(
        id: String,
        name: String = "Title $id",
        poster: String? = "https://img/$id.jpg"
    ) = MetaPreview(
        id = id,
        type = "movie",
        name = name,
        poster = poster,
        background = "https://img/$id-bg.jpg"
    )

    private fun rail(
        catalogId: String,
        items: List<MetaPreview>,
        ranked: Boolean = false
    ) = Rail(
        addonName = "TMDB Top Today",
        catalogName = "Top Movies Today",
        type = "movie",
        items = items,
        catalogId = catalogId,
        baseUrl = "https://toptoday.llamayu.com",
        ranked = ranked
    )

    @Test
    fun `a rail round-trips with its identity, order and rank`() {
        val original = listOf(
            rail("top_movies_today", listOf(item("a"), item("b"), item("c")), ranked = true),
            rail("top_shows_today", listOf(item("d")), ranked = false).copy(
                type = "series",
                catalogName = "Top Shows Today"
            )
        )

        val decoded = HomePinnedSnapshot.decode(HomePinnedSnapshot.encode(original))

        assertEquals("rails must come back in the slots they were written", 2, decoded.size)
        assertEquals(
            listOf("a", "b", "c"),
            decoded[0].items.map { it.id }
        )
        assertEquals("top_movies_today", decoded[0].catalogId)
        assertEquals("TMDB Top Today", decoded[0].addonName)
        assertEquals("https://toptoday.llamayu.com", decoded[0].baseUrl)
        assertTrue("the ranked flag is what draws the Top 10 numbers", decoded[0].ranked)
        assertEquals("series", decoded[1].type)
        assertEquals("Top Shows Today", decoded[1].catalogName)
        assertEquals(listOf("d"), decoded[1].items.map { it.id })
    }

    @Test
    fun `a missing poster stays missing rather than becoming an empty string`() {
        val decoded = HomePinnedSnapshot.decode(
            HomePinnedSnapshot.encode(
                listOf(rail("top_movies_today", listOf(item("x", poster = null))))
            )
        )

        assertEquals(1, decoded.size)
        assertNull(
            "a null poster must decode as null: an empty URL is a broken image, not a fallback",
            decoded[0].items[0].poster
        )
        assertEquals("https://img/x-bg.jpg", decoded[0].items[0].background)
    }

    @Test
    fun `separators inside a title cannot split a field`() {
        val nasty = "Tab\there\nand a newline \\ backslash"
        val decoded = HomePinnedSnapshot.decode(
            HomePinnedSnapshot.encode(
                listOf(rail("top_movies_today", listOf(item("x", name = nasty))))
            )
        )

        assertEquals(1, decoded.size)
        assertEquals(1, decoded[0].items.size)
        assertEquals(nasty, decoded[0].items[0].name)
    }

    @Test
    fun `an unreadable blob decodes to nothing instead of throwing`() {
        assertEquals(0, HomePinnedSnapshot.decode(null).size)
        assertEquals(0, HomePinnedSnapshot.decode("").size)
        assertEquals(
            "garbage must not reach the rail list",
            0,
            HomePinnedSnapshot.decode("R\tonly\ta\tfew\nI\tnope").size
        )
        assertEquals(0, HomePinnedSnapshot.decode("\u0000\u0001binary").size)
    }

    @Test
    fun `an empty rail is not published as a rail with no items`() {
        val decoded = HomePinnedSnapshot.decode(
            HomePinnedSnapshot.encode(
                listOf(rail("top_movies_today", emptyList()))
            )
        )

        assertEquals("a rail with nothing cached is a skeleton, not a row", 0, decoded.size)
    }

    @Test
    fun `the blob is bounded`() {
        val many = (1..40).map { index -> item("id$index") }
        val decoded = HomePinnedSnapshot.decode(
            HomePinnedSnapshot.encode(listOf(rail("top_movies_today", many)))
        )

        assertEquals("a rail stores at most 40 items", 40, decoded[0].items.size)
    }

    @Test
    fun `the scope separates profiles and their ceilings`() {
        assertTrue(
            "a kids profile must never read the adult profile's cached rows",
            HomePinnedSnapshot.scopeOf("p1", kidsCeiling = true) !=
                HomePinnedSnapshot.scopeOf("p1", kidsCeiling = false)
        )
        assertTrue(
            "two profiles must not share a blob",
            HomePinnedSnapshot.scopeOf("p1", false) != HomePinnedSnapshot.scopeOf("p2", false)
        )
        assertEquals(
            "the same profile and ceiling must read back the same key",
            HomePinnedSnapshot.scopeOf("p1", false),
            HomePinnedSnapshot.scopeOf("p1", false)
        )
    }
}
