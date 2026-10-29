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
    fun `paired newcomers inherit their predecessor slots despite a manifest reorder`() {
        // The regression: a churned rail took the next freed slot in MANIFEST
        // order, while the arrangement remap gave it its predecessor's position.
        // Two stores, two answers, and a rail that moved on every refresh.
        val stored = listOf(
            catalog("movie", "a", name = "A", order = 0),
            catalog("movie", "b", name = "B", order = 1),
            catalog("movie", "c", name = "C", order = 2),
            catalog("movie", "d", name = "D", order = 3)
        )
        val globalOrder =
            mapOf("movie::a" to 0, "movie::b" to 1, "movie::c" to 2, "movie::d" to 3)

        // Two ids swapped, and the manifest lists the rails in another order.
        val reordered = listOf(
            catalog("movie", "c", name = "C"),
            catalog("movie", "d2", name = "D"),
            catalog("movie", "a", name = "A"),
            catalog("movie", "b2", name = "B")
        )

        val plan = planManifestMerge(stored, reordered, globalOrder, ::keyOf)
        assertEquals(
            "each newcomer takes its predecessor's slot, wherever the manifest put it",
            mapOf("c" to 2, "d2" to 3, "a" to 0, "b2" to 1),
            plan.catalogs.associate { it.id to it.order }
        )
        assertEquals(
            "and the pairing is exactly the one the arrangement remap consumes",
            setOf("b2" to "b", "d2" to "d"),
            plan.replaced.map { (added, removed) -> added.id to removed.id }.toSet()
        )

        // The manifest's own order must not matter: the same swap listed in the
        // stored order plans the same slots.
        val inStoredOrder = listOf(
            catalog("movie", "a", name = "A"),
            catalog("movie", "b2", name = "B"),
            catalog("movie", "c", name = "C"),
            catalog("movie", "d2", name = "D")
        )
        val again = planManifestMerge(stored, inStoredOrder, globalOrder, ::keyOf)
        assertEquals(
            mapOf("a" to 0, "b2" to 1, "c" to 2, "d2" to 3),
            again.catalogs.associate { it.id to it.order }
        )
    }

    @Test
    fun `content-embedded names pair positionally and the order follows that pairing`() {
        // BingeCat's rails embed the content in their names, so a swap matches
        // nothing by name and pairs positionally. The pairing may then be wrong
        // about WHICH rail a newcomer replaced - unknowable from a manifest -
        // but the order and the settings must follow the same pairing, so the
        // two stores still agree.
        val stored = listOf(
            catalog("series", "bwc-a", name = "Because you watched A", order = 0),
            catalog("series", "bwc-b", name = "Because you watched B", order = 1, customName = "B rail"),
            catalog("series", "bwc-c", name = "Because you watched C", order = 2),
            catalog(
                "series", "bwc-d", name = "Because you watched D", order = 3,
                customName = "D rail", showOnHome = false
            )
        )
        val globalOrder = mapOf(
            "series::bwc-a" to 0, "series::bwc-b" to 1,
            "series::bwc-c" to 2, "series::bwc-d" to 3
        )
        val reordered = listOf(
            catalog("series", "bwc-c", name = "Because you watched C"),
            catalog("series", "bwc-d2", name = "Because you watched X"),
            catalog("series", "bwc-a", name = "Because you watched A"),
            catalog("series", "bwc-b2", name = "Because you watched Y")
        )

        val plan = planManifestMerge(stored, reordered, globalOrder, ::keyOf)
        assertEquals(
            "removed in saved order against newcomers in manifest order, one-for-one",
            setOf("bwc-d2" to "bwc-b", "bwc-b2" to "bwc-d"),
            plan.replaced.map { (added, removed) -> added.id to removed.id }.toSet()
        )

        val byId = plan.catalogs.associateBy { it.id }
        assertEquals("bwc-b's slot", 1, byId.getValue("bwc-d2").order)
        assertEquals("bwc-d's slot", 3, byId.getValue("bwc-b2").order)
        assertEquals("no slot is handed out twice", 4, plan.catalogs.map { it.order }.toSet().size)

        assertEquals("the rename follows the same pairing", "B rail", byId.getValue("bwc-d2").customName)
        assertEquals("D rail", byId.getValue("bwc-b2").customName)
        assertFalse("and so does the pin", byId.getValue("bwc-b2").showOnHome)
    }

    @Test
    fun `unpaired newcomers take the remaining freed slots and a new type appends`() {
        val stored = listOf(
            catalog("movie", "a", name = "A", order = 0),
            catalog("movie", "b", name = "B", order = 1),
            catalog("movie", "c", name = "C", order = 2)
        )
        val globalOrder = mapOf("movie::a" to 0, "movie::b" to 1, "movie::c" to 2)

        val plan = planManifestMerge(
            stored,
            listOf(
                catalog("movie", "a", name = "A"),
                catalog("movie", "b2", name = "B"),
                catalog("movie", "e", name = "E"),
                catalog("series", "f", name = "F")
            ),
            globalOrder,
            ::keyOf
        )

        val byId = plan.catalogs.associateBy { it.id }
        assertEquals("a survivor keeps its slot", 0, byId.getValue("a").order)
        assertEquals("the named pair inherits", 1, byId.getValue("b2").order)
        assertEquals("the leftover freed slot goes to the unpaired newcomer", 2, byId.getValue("e").order)
        assertEquals("a genuinely new type appends past the stored ranks", 3, byId.getValue("f").order)
    }

    @Test
    fun `a dropped catalog with no replacement leaves the survivors alone`() {
        val plan = planManifestMerge(
            existingCatalogs = listOf(
                catalog("movie", "a", name = "A", order = 0),
                catalog("movie", "b", name = "B", order = 1),
                catalog("movie", "c", name = "C", order = 2)
            ),
            manifestCatalogs = listOf(
                catalog("movie", "c", name = "C"),
                catalog("movie", "a", name = "A")
            ),
            globalOrder = mapOf("movie::a" to 0, "movie::b" to 1, "movie::c" to 2),
            keyOf = ::keyOf
        )

        // Ranks, not indices: the densifier renumbers 0..n afterwards and is
        // covered elsewhere. What must hold is that nothing moved relative to
        // anything else.
        assertEquals(
            "the manifest's order, each survivor in the slot it already had",
            listOf("c" to 2, "a" to 0),
            plan.catalogs.map { it.id to it.order }
        )
        assertTrue("a drop with no replacement pairs nothing", plan.replaced.isEmpty())
    }

    @Test
    fun `duplicate names within one type pair first come in manifest order`() {
        val plan = planManifestMerge(
            existingCatalogs = listOf(
                catalog("movie", "x", name = "Dup", order = 0),
                catalog("movie", "y", name = "Dup", order = 1)
            ),
            manifestCatalogs = listOf(
                catalog("movie", "x2", name = "Dup"),
                catalog("movie", "x3", name = "Dup")
            ),
            globalOrder = mapOf("movie::x" to 0, "movie::y" to 1),
            keyOf = ::keyOf
        )

        assertEquals(
            "deterministic, not set-order luck",
            listOf("x2" to 0, "x3" to 1),
            plan.catalogs.map { it.id to it.order }
        )
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
