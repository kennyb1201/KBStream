package com.kennyb1201.kbstream.data.kb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Browse row: the browse-menu chips a viewer mirrored to Home.
 *
 * What this pins down is where the row goes and what the pin control does to
 * it, because both are silent when wrong: a row that lands in the tail reads
 * as the long-press having done nothing, and a row that reaches the pinned
 * list without a way back out is the exact bug the collections already hit
 * (see KBHomeOrderMoveTest).
 */
class BrowseHomeShortcutsTest {

    private fun shortcut(
        categoryKey: String = "genres",
        id: Int = 35,
        name: String = "Comedy"
    ) = BrowseHomeShortcut(categoryKey = categoryKey, id = id, name = name)

    private val row = BrowseHomeShortcuts.ROW_KEY
    private val colA = "kb:My Collection"
    private val catA = "addon:https://a.example:movie:top"

    // ── identity ────────────────────────────────────────────────────

    @Test
    fun `a chip is identified by its category and its name`() {
        assertEquals("genres\u0001Comedy", BrowseHomeShortcuts.chipKey("genres", "Comedy"))
        // The name is trimmed so the same chip cannot land in the row twice
        // with different spacing.
        assertEquals(
            BrowseHomeShortcuts.chipKey("genres", "Comedy"),
            BrowseHomeShortcuts.chipKey("genres", "  Comedy  ")
        )
        // Same name, different category: two chips, not one.
        assertFalse(
            BrowseHomeShortcuts.chipKey("genres", "Comedy") ==
                BrowseHomeShortcuts.chipKey("keywords", "Comedy")
        )
    }

    @Test
    fun `the row's key is in its own space, apart from collections and catalogs`() {
        assertTrue(BrowseHomeShortcuts.isRowKey(row))
        assertTrue(BrowseHomeShortcuts.isShortcutKey(row))
        assertFalse(BrowseHomeShortcuts.isShortcutKey(colA))
        assertFalse(BrowseHomeShortcuts.isShortcutKey(catA))
        assertFalse(BrowseHomeShortcuts.isRowKey(colA))
    }

    @Test
    fun `contains answers for the chip, not for the category`() {
        val added = listOf(shortcut())
        assertTrue(BrowseHomeShortcuts.contains(added, "genres", "Comedy"))
        assertFalse(BrowseHomeShortcuts.contains(added, "genres", "Drama"))
        assertFalse(BrowseHomeShortcuts.contains(added, "keywords", "Comedy"))
    }

    // ── placement ───────────────────────────────────────────────────

    @Test
    fun `an empty row does not render at all`() {
        assertEquals(
            BrowseRowPlacement.NONE,
            browseRowPlacement(
                shortcuts = emptyList(),
                hidden = false,
                pinned = false,
                arranged = false
            )
        )
    }

    @Test
    fun `a row the user hid stays hidden wherever it was`() {
        for (pinned in listOf(false, true)) {
            for (arranged in listOf(false, true)) {
                assertEquals(
                    BrowseRowPlacement.NONE,
                    browseRowPlacement(
                        shortcuts = listOf(shortcut()),
                        hidden = true,
                        pinned = pinned,
                        arranged = arranged
                    )
                )
            }
        }
    }

    @Test
    fun `a never-arranged row defaults to just under the Top Today rows`() {
        assertEquals(
            BrowseRowPlacement.BELOW_TOP_TODAY,
            browseRowPlacement(
                shortcuts = listOf(shortcut()),
                hidden = false,
                pinned = false,
                arranged = false
            )
        )
    }

    @Test
    fun `a pin outranks the default spot`() {
        assertEquals(
            BrowseRowPlacement.PINNED,
            browseRowPlacement(
                shortcuts = listOf(shortcut()),
                hidden = false,
                pinned = true,
                arranged = false
            )
        )
    }

    @Test
    fun `a placed row stays where the manager put it`() {
        assertEquals(
            BrowseRowPlacement.IN_ORDER,
            browseRowPlacement(
                shortcuts = listOf(shortcut()),
                hidden = false,
                pinned = false,
                arranged = true
            )
        )
    }

    // ── the pin control ─────────────────────────────────────────────

    @Test
    fun `the row is pinnable, and a catalog still is not`() {
        assertTrue(KBHomeOrderPrefs.isPinnableKey(row))
        assertTrue(KBHomeOrderPrefs.isPinnableKey(colA))
        assertFalse(KBHomeOrderPrefs.isPinnableKey(catA))
    }

    @Test
    fun `pinning the row lifts it out of the stored order`() {
        val result = toggleCollectionPin(
            KBHomeOrder(order = listOf(catA, row)),
            row
        )
        assertEquals(listOf(row), result.pinned)
        assertEquals(listOf(catA), result.order)
    }

    @Test
    fun `unpinning leaves the row arranged, never in neither list`() {
        // Same guard the collections need: a key in neither list is
        // "never arranged", and the row would then be re-placed by default
        // rather than left where the viewer put it.
        val result = toggleCollectionPin(
            KBHomeOrder(order = listOf(catA), pinned = listOf(row)),
            row
        )
        assertTrue("unpinned row must stay in the arrangement", row in result.order)
        assertEquals(emptyList<String>(), result.pinned)
    }

    @Test
    fun `a top move pins the row instead of parking it in the order block`() {
        val result = moveRailToEnd(
            KBHomeOrder(order = listOf(catA, row)),
            row,
            toTop = true
        )
        assertEquals(listOf(row), result.pinned)
        assertEquals(listOf(catA), result.order)
    }

    @Test
    fun `a bottom move unpins the row and appends it last`() {
        val result = moveRailToEnd(
            KBHomeOrder(order = listOf(catA), pinned = listOf(row)),
            row,
            toTop = false
        )
        assertEquals(emptyList<String>(), result.pinned)
        assertEquals(listOf(catA, row), result.order)
    }

    @Test
    fun `the read-time repair keeps the row pinned`() {
        val healed = normalizeHomeOrder(
            KBHomeOrder(pinned = listOf(catA, row, colA))
        )
        // The row and the collection stay pinned; only the catalog is lifted.
        assertEquals(listOf(row, colA), healed.pinned)
        assertEquals(listOf(catA), healed.order)
    }

    // ── the tile's caption ──────────────────────────────────────────

    @Test
    fun `a tile names the category its chip came from`() {
        assertEquals("Genres", browseShortcutCategoryLabel("genres"))
        assertEquals("Services & Networks", browseShortcutCategoryLabel("services"))
        assertEquals("Collections", browseShortcutCategoryLabel("collections"))
        // An unknown key still produces a caption rather than an empty tile.
        assertEquals("Vibes", browseShortcutCategoryLabel("vibes"))
    }
}
