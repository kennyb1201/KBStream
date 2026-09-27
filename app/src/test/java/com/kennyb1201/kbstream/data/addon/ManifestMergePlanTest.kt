package com.kennyb1201.kbstream.data.addon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure core of `AddonManager.updateAddonFromManifest`. This is the code
 * path that repeatedly regressed into "my rename came back", "the rail was
 * re-enabled", or "the rail jumped to the top on refresh" — so it is pinned
 * here, without a manager, a store, or a network.
 */
class ManifestMergePlanTest {

    private fun catalog(
        type: String,
        id: String,
        name: String = id,
        showOnHome: Boolean = true,
        order: Int = 0,
        customName: String? = null,
        showInHomeHint: Boolean? = null,
        isSearch: Boolean? = null
    ) = ManifestCatalog(
        type = type,
        id = id,
        name = name,
        showOnHome = showOnHome,
        order = order,
        customName = customName,
        showInHomeHint = showInHomeHint,
        isSearchCatalog = isSearch
    )

    private fun keyOf(catalog: ManifestCatalog): String =
        "${catalog.type.lowercase()}::${catalog.id}"

    @Test
    fun `a surviving catalog keeps its rename, pin and global slot`() {
        val plan = planManifestMerge(
            existingCatalogs = listOf(
                catalog(
                    "movie", "top",
                    name = "Top Movies",
                    order = 5,
                    customName = "Top",
                    showOnHome = false
                )
            ),
            manifestCatalogs = listOf(catalog("movie", "top", name = "Top Movies")),
            globalOrder = mapOf("movie::top" to 5),
            keyOf = ::keyOf
        )

        val merged = plan.catalogs.single()
        assertEquals("Top", merged.customName)
        assertFalse(merged.showOnHome)
        assertEquals(5, merged.order)
    }

    @Test
    fun `a new catalog honors the manifest visibility hint`() {
        val plan = planManifestMerge(
            existingCatalogs = emptyList(),
            manifestCatalogs = listOf(
                catalog("movie", "a", showInHomeHint = true),
                catalog("movie", "b", showInHomeHint = false)
            ),
            globalOrder = emptyMap(),
            keyOf = ::keyOf
        )

        assertTrue(plan.catalogs.first { it.id == "a" }.showOnHome)
        assertFalse(plan.catalogs.first { it.id == "b" }.showOnHome)
    }

    @Test
    fun `a search placeholder installs hidden`() {
        val plan = planManifestMerge(
            existingCatalogs = emptyList(),
            manifestCatalogs = listOf(
                catalog("movie", "ai-search", name = "AI Search"),
                catalog("movie", "people", name = "People").copy(isSearchCatalog = true)
            ),
            globalOrder = emptyMap(),
            keyOf = ::keyOf
        )

        assertTrue(plan.catalogs.none { it.showOnHome })
    }

    @Test
    fun `an id swap inherits the removed catalog and its freed slot`() {
        val plan = planManifestMerge(
            existingCatalogs = listOf(
                catalog(
                    "series", "bwc-1",
                    name = "Because you watched A",
                    order = 2,
                    customName = "A"
                )
            ),
            manifestCatalogs = listOf(catalog("series", "bwc-9", name = "Because you watched B")),
            globalOrder = mapOf("series::bwc-1" to 2),
            keyOf = ::keyOf
        )

        val merged = plan.catalogs.single()
        assertEquals("A", merged.customName)
        assertEquals(2, merged.order)

        // The pairing is exposed so the caller can remap the Home arrangement.
        assertEquals(1, plan.replaced.size)
        assertEquals("bwc-9", plan.replaced.single().first.id)
        assertEquals("bwc-1", plan.replaced.single().second.id)
    }

    @Test
    fun `a swapped rail keeps its slot instead of jumping to the top`() {
        // Global order: another addon at 0, this addon's rail at 1. The rail's
        // id changed, so the newcomer must take the freed slot 1 — not a low
        // fresh index that would plant it at the top of Home.
        val plan = planManifestMerge(
            existingCatalogs = listOf(catalog("movie", "railA", order = 1)),
            manifestCatalogs = listOf(catalog("movie", "railB")),
            globalOrder = mapOf("other::movie::x" to 0, "movie::railA" to 1),
            keyOf = ::keyOf
        )

        assertEquals(1, plan.catalogs.single().order)
    }

    @Test
    fun `a catalog the manifest drops disappears`() {
        val plan = planManifestMerge(
            existingCatalogs = listOf(
                catalog("movie", "gone", order = 0),
                catalog("movie", "keep", order = 1)
            ),
            manifestCatalogs = listOf(catalog("movie", "keep")),
            globalOrder = mapOf("movie::gone" to 0, "movie::keep" to 1),
            keyOf = ::keyOf
        )

        assertEquals(listOf("keep"), plan.catalogs.map { it.id })
        assertEquals(1, plan.catalogs.single().order)
    }
}
