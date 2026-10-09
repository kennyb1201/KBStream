package com.kennyb1201.kbstream.ui.kb

import com.kennyb1201.kbstream.data.kb.KBFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The collection rail's two pure rules: which folders it draws, and how each
 * one is keyed.
 *
 * The reported defect was a rail that locked up and oscillated focus at the
 * same position on specific collections. Both halves of it live here rather
 * than in Compose: a null-id folder used to be dropped INSIDE the item block
 * (leaving a keyed, focusable slot that emitted nothing), and the key was
 * `id ?: title`, which collides for duplicate ids or blank titles. Compose
 * cannot be run in this module's test suite, so the rules it depends on are
 * tested directly and the wiring that applies them is pinned by
 * [CollectionRailWiringContractTest].
 */
class CollectionRailTilesTest {

    private fun folder(id: String?, title: String = "title") = KBFolder(id = id, title = title)

    // ── Which folders are drawn ─────────────────────────────────────

    @Test
    fun `the reported shape draws three tiles, in order`() {
        // [id=1, id=null, id=1, id=2]: the null is dropped, the duplicate id
        // is kept (it is a real, openable folder), and order is preserved.
        val tiles = collectionRailTiles(
            listOf(folder("1"), folder(null), folder("1"), folder("2"))
        )
        assertEquals(listOf("1", "1", "2"), tiles.map { it.first })
    }

    @Test
    fun `a null-id folder between two real tiles is dropped, not left as a slot`() {
        val tiles = collectionRailTiles(listOf(folder("a"), folder(null), folder("b")))
        assertEquals(listOf("a", "b"), tiles.map { it.first })
    }

    @Test
    fun `a folder with an id but a blank title still renders`() {
        assertEquals(1, collectionRailTiles(listOf(folder("1", ""))).size)
    }

    @Test
    fun `an all-null list produces an empty rail rather than a crash`() {
        assertTrue(collectionRailTiles(listOf(folder(null), folder(null))).isEmpty())
    }

    @Test
    fun `order is preserved`() {
        val ids = listOf("c", "a", "b")
        assertEquals(ids, collectionRailTiles(ids.map { folder(it) }).map { it.first })
    }

    // ── Keys ────────────────────────────────────────────────────────

    @Test
    fun `keys are unique for the duplicate-id input`() {
        val tiles = collectionRailTiles(
            listOf(folder("1"), folder(null), folder("1"), folder("2"))
        )
        val keys = tiles.mapIndexed { index, tile -> collectionRailKey(index, tile) }
        assertEquals(3, keys.size)
        assertEquals("every position needs its own key", keys.size, keys.toSet().size)
        assertNotEquals(
            "the two folders sharing id=1 must not share a key",
            keys[0],
            keys[1]
        )
    }

    @Test
    fun `keys are unique even when ids and titles collide`() {
        // The old key was `id ?: title`, which collided here twice over: the two
        // null-id blank titles both keyed "", and the two folders sharing both
        // id and title keyed "x". The null-id pair is now dropped outright; the
        // duplicate pair survives and has to get a key each.
        val tiles = collectionRailTiles(
            listOf(
                folder(null, ""),
                folder(null, ""),
                folder("x", "same"),
                folder("x", "same")
            )
        )
        assertEquals(2, tiles.size)
        val keys = tiles.mapIndexed { index, tile -> collectionRailKey(index, tile) }
        assertEquals(keys.size, keys.toSet().size)
        assertNotEquals(keys[0], keys[1])
    }

    @Test
    fun `a rail whose ids are already unique keeps one tile per id`() {
        val tiles = collectionRailTiles(listOf(folder("1"), folder("2"), folder("3")))
        assertEquals(3, tiles.size)
        assertEquals(
            "same order and same tiles as before, with the position suffix",
            listOf("1#0", "2#1", "3#2"),
            tiles.mapIndexed { index, tile -> collectionRailKey(index, tile) }
        )
    }
}
