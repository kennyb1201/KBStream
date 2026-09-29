package com.kennyb1201.kbstream.ui.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The browse submenu renders as a LazyVerticalGrid, and a lazy grid REJECTS a
 * duplicate item key (it throws at composition). Its keys are
 * [BrowseChipVisibility.key] — the same "category\u0001name" identity the
 * long-press Hide feature stores — so a duplicate name inside one category
 * would not merely draw two identical chips, it would take the submenu down.
 *
 * These tests pin that identity over every submenu whose chips are static.
 * The keyword and collection submenus are resolved against TMDB at runtime
 * and de-duplicated by name before they reach the grid
 * (SearchViewModel.resolveCatalogEntries), so their names are covered by the
 * catalog tests instead.
 */
class SearchBrowseSubmenuGridTest {

    private fun assertUniqueKeys(categories: List<BrowseCategory>, whose: String) {
        categories.forEach { category ->
            val keys = category.entries.map { entry ->
                BrowseChipVisibility.key(category.key, entry.name)
            }
            assertEquals(
                "$whose submenu \"${category.label}\" has a duplicate chip key, " +
                    "which a lazy grid cannot render",
                keys.size,
                keys.toSet().size
            )
        }
    }

    @Test
    fun `every adult submenu chip has a unique key`() {
        assertUniqueKeys(BROWSE_CATEGORIES, "adult")
    }

    @Test
    fun `every kids submenu chip has a unique key`() {
        assertUniqueKeys(KIDS_BROWSE_CATEGORIES, "kids")
    }

    @Test
    fun `a chip key is scoped to its category`() {
        // The adult and kids categories deliberately share key strings
        // ("genres", "services", ...), and a category is shown in isolation,
        // so the prefix is what keeps two categories' chips - and their
        // hidden sets - apart. Dropping it would let hiding "Comedy" under
        // Genres hide it under Keywords too.
        assertNotEquals(
            BrowseChipVisibility.key("genres", "Comedy"),
            BrowseChipVisibility.key("keywords", "Comedy")
        )
    }
}
