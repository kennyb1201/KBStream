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

    private val row = BrowseHomeShortcuts.LEGACY_ROW_KEY
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
    fun `a browse key is in its own space, apart from collections and catalogs`() {
        assertTrue(BrowseHomeShortcuts.isLegacyRowKey(row))
        assertTrue(BrowseHomeShortcuts.isShortcutKey(row))
        assertTrue(BrowseHomeShortcuts.isShortcutKey(BrowseShortcutRail.GENRES_TAGS.key))
        assertTrue(BrowseHomeShortcuts.isShortcutKey(BrowseShortcutRail.COLLECTIONS.key))
        assertFalse(BrowseHomeShortcuts.isShortcutKey(colA))
        assertFalse(BrowseHomeShortcuts.isShortcutKey(catA))
        assertFalse(BrowseHomeShortcuts.isLegacyRowKey(colA))
        // A collection's key is a different key space from a collection CHIP.
        assertFalse(BrowseHomeShortcuts.isLegacyRowKey(BrowseShortcutRail.COLLECTIONS.key))
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

    // ── the rails ───────────────────────────────────────────────────

    @Test
    fun `each rail key is its own, and every rail is pinnable`() {
        val keys = BrowseShortcutRail.entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        keys.forEach { key ->
            assertTrue(
                "$key must be pinnable, it is a rail with a pin control",
                KBHomeOrderPrefs.isPinnableKey(key)
            )
            assertTrue("$key must not collide with the legacy row", key != row)
        }
    }

    @Test
    fun `a chip's category decides its rail`() {
        // Genres and tags share a rail: both browse by subject.
        assertEquals(BrowseShortcutRail.GENRES_TAGS, browseShortcutRail("genres"))
        assertEquals(BrowseShortcutRail.GENRES_TAGS, browseShortcutRail("KEYWORDS"))
        // A network chip added from a show's Detail page and a service chip
        // added from Browse end up on the same rail.
        assertEquals(BrowseShortcutRail.SERVICES, browseShortcutRail("services"))
        assertEquals(BrowseShortcutRail.STUDIOS, browseShortcutRail("studios"))
        assertEquals(BrowseShortcutRail.DECADES, browseShortcutRail("decades"))
        assertEquals(BrowseShortcutRail.COLLECTIONS, browseShortcutRail("collections"))
        assertEquals(null, browseShortcutRail("vibes"))
        assertEquals(null, browseShortcutRail(null))
    }

    @Test
    fun `chips render as one rail per kind, in a fixed order`() {
        val rails = browseHomeRails(
            listOf(
                shortcut("studios", 1, "A24"),
                shortcut("genres", 35, "Comedy"),
                shortcut("keywords", 9715, "Superhero"),
                shortcut("services", 8, "Netflix"),
                shortcut("decades", 1990, "1990s")
            )
        )

        // Fixed order, not "however they were added": adding a studio must
        // never move the genres rail.
        assertEquals(
            listOf(
                BrowseShortcutRail.GENRES_TAGS.key,
                BrowseShortcutRail.SERVICES.key,
                BrowseShortcutRail.STUDIOS.key,
                BrowseShortcutRail.DECADES.key
            ),
            rails.map { it.key }
        )

        // The shared rail holds the genre AND the tag, in the order added.
        val genresAndTags = rails.first()
        assertEquals("Genres & Tags", genresAndTags.title)
        assertEquals(listOf("Comedy", "Superhero"), genresAndTags.shortcuts.map { it.name })
    }

    @Test
    fun `a rail with nothing in it is not a rail`() {
        val rails = browseHomeRails(listOf(shortcut("services", 8, "Netflix")))

        assertEquals(1, rails.size)
        assertEquals(BrowseShortcutRail.SERVICES.key, rails.single().key)
        assertEquals("Services & Networks", rails.single().title)
        assertEquals(emptyList<BrowseHomeRail>(), browseHomeRails(emptyList()))
    }

    @Test
    fun `a category this build does not know keeps its tiles on the legacy row`() {
        // A blob written by a newer build: dropping these would silently
        // delete tiles the viewer added.
        val rails = browseHomeRails(
            listOf(shortcut("genres", 35, "Comedy"), shortcut("vibes", 1, "Chill"))
        )

        assertEquals(2, rails.size)
        assertEquals(row, rails.last().key)
        assertEquals(listOf("Chill"), rails.last().shortcuts.map { it.name })
    }

    @Test
    fun `a rail takes over from the legacy row only once its own key is arranged`() {
        val own = BrowseShortcutRail.SERVICES.key

        // Untouched rails inherit the old shared row's flags, so an install
        // that pinned or placed that row keeps it there (and a hidden row
        // stays hidden).
        val inherited = browseRailArrangementOf(
            key = own,
            legacyKey = row,
            pinnedKeys = setOf(row),
            order = emptyList(),
            hiddenSet = emptySet()
        )
        assertTrue(inherited.pinned)
        assertFalse(inherited.hidden)
        assertFalse(inherited.arranged)

        // Once the rail's own key appears, it wins - the legacy row's pin
        // must not drag it along.
        val own2 = browseRailArrangementOf(
            key = own,
            legacyKey = row,
            pinnedKeys = setOf(row),
            order = listOf(own),
            hiddenSet = emptySet()
        )
        assertFalse(own2.pinned)
        assertTrue(own2.arranged)

        // And a rail the viewer hid is hidden even though it has no order
        // entry of its own.
        val hiddenRail = browseRailArrangementOf(
            key = own,
            legacyKey = row,
            pinnedKeys = emptySet(),
            order = emptyList(),
            hiddenSet = setOf(own)
        )
        assertTrue(hiddenRail.hidden)
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
