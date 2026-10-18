package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Home's catalog rails are drawn in CATALOG order - the order the viewer set in
 * the add-ons screen - not in the order the fan-out happened to finish fetching
 * them.
 *
 * Field report: "catalogs order and visibility on home is incorrect and
 * switched up somehow after a manual addon refresh". The refresh itself was
 * innocent: it rebuilds the add-on list, which rebuilds the rails, and the rail
 * list was published as `pinned + collected` - where `collected` is appended by
 * each rail's own coroutine AS IT RESOLVES. The order of that list is therefore
 * a property of the network (which add-on host answered first), and it is the
 * order Home lays every unarranged rail out by (KBHomeSlots.buildMergedEntries
 * takes the rail list as its default order). So every rebuild could deal the
 * catalogs a fresh hand, and the one visible trigger was the button that forces
 * a full rebuild.
 *
 * The ordering is pure and pinned directly here; the two places the view model
 * applies it are pinned by reading the source, the way the other contract tests
 * in this package do.
 */
class HomeRailCatalogOrderTest {

    private val sourceRoot: File by lazy { findSourceRoot() }

    private fun findSourceRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun squash(text: String): String = text.replace(Regex("\\s+"), " ")

    private fun rail(addon: String, id: String, type: String = "movie"): Rail =
        Rail(
            addonName = addon,
            catalogName = id,
            type = type,
            items = emptyList(),
            catalogId = id
        )

    private fun pending(addon: String, id: String, type: String = "movie") =
        PendingCatalogLoad(
            addonName = addon,
            baseUrl = "https://$addon/",
            catalogId = id,
            catalogType = type,
            catalogRawName = id
        )

    // ── The order itself ───────────────────────────────────────────────────

    @Test
    fun `the catalog order map names every load by its rail key, in order`() {
        val order = catalogOrderByKey(
            listOf(
                pending("First", "top"),
                pending("Second", "rail"),
                pending("First", "recent", type = "series")
            )
        )

        assertEquals(3, order.size)
        assertEquals(0, order[catalogRailKey("First", "top", "movie")])
        assertEquals(1, order[catalogRailKey("Second", "rail", "movie")])
        assertEquals(2, order[catalogRailKey("First", "recent", "series")])
    }

    @Test
    fun `rails are ordered by the catalog order whatever order they resolved in`() {
        val order = catalogOrderByKey(
            listOf(
                pending("A", "one"),
                pending("A", "two"),
                pending("B", "three")
            )
        )

        // The same three rails, in three different resolution orders: every
        // one of them has to come back as A-one, A-two, B-three.
        val expected = listOf(
            catalogRailKey("A", "one", "movie"),
            catalogRailKey("A", "two", "movie"),
            catalogRailKey("B", "three", "movie")
        )
        val rails = mapOf(
            catalogRailKey("A", "one", "movie") to rail("A", "one"),
            catalogRailKey("A", "two", "movie") to rail("A", "two"),
            catalogRailKey("B", "three", "movie") to rail("B", "three")
        )

        listOf(
            listOf("B::three::movie", "A::one::movie", "A::two::movie"),
            listOf("A::two::movie", "B::three::movie", "A::one::movie"),
            listOf("A::one::movie", "B::three::movie", "A::two::movie")
        ).forEach { arrival ->
            val resolved = arrival.map { key -> rails.getValue(key) }

            assertEquals(
                "resolution order must not decide the order on Home",
                expected,
                orderCatalogRails(resolved, order).map {
                    catalogRailKey(it.addonName, it.catalogId, it.type)
                }
            )
        }
    }

    @Test
    fun `a rail the catalog order does not name sorts last and keeps its arrival order`() {
        val order = catalogOrderByKey(listOf(pending("A", "one")))
        // An app-built row (a Top Today/kids/guest rail) and a row left over
        // from a catalog that is no longer loaded: neither is in the map, and
        // both must stay behind the catalogs rather than being dealt into the
        // middle of them.
        val appBuilt = rail("TMDB", "top_movies_today")
        val leftover = rail("A", "gone")
        val first = rail("A", "one")

        assertEquals(
            listOf(first, appBuilt, leftover),
            orderCatalogRails(listOf(appBuilt, leftover, first), order)
        )
        // Stable: two rails the order does not name keep the order they
        // arrived in, so the rule can never reorder them against each other.
        assertEquals(
            listOf(first, leftover, appBuilt),
            orderCatalogRails(listOf(leftover, appBuilt, first), order)
        )
    }

    @Test
    fun `the rail key and the catalog key are one spelling`() {
        // The published order is computed against keys built before any rail
        // exists, so a second spelling of the same identity would silently
        // order nothing at all.
        assertEquals(
            catalogRailKey("Addon", "id", "movie"),
            "Addon::id::movie"
        )
    }

    // ── The wiring ────────────────────────────────────────────────────────

    @Test
    fun `the persistent rail list is the pinned rows plus the catalogs in catalog order`() {
        val src = squash(source(HOME_VM))
        assertTrue(
            "the catalog order must be built from the loads Home is about to make",
            src.contains("val catalogOrder = catalogOrderByKey(pendingCatalogs)")
        )
        assertTrue(
            "and the published list must be ordered by it - `pinned + collected` " +
                "is fetch-completion order, which is the defect",
            src.contains("pinned + orderCatalogRails(collected, catalogOrder)")
        )
        assertFalse(
            "nothing may publish the collected rails raw again",
            src.contains("val finalRails = pinned + collected")
        )
    }

    @Test
    fun `a rail that streams in lands in its catalog slot, not at the end`() {
        val src = squash(source(HOME_VM))
        assertTrue(
            "the streamed insert must place the rail by catalog order",
            src.contains("val insertAt = if (mine == null) { -1 } else {")
        )
        assertTrue(
            "against the same map the final publish uses",
            src.contains("val theirs = catalogOrder[railKeyOf(existing)]")
        )
        assertFalse(
            "appending after the last rail that had resolved is the completion " +
                "order this replaced - the rows then had to be re-ordered under " +
                "the viewer when the build finished",
            src.contains("without.indexOfLast { existing ->")
        )
    }

    @Test
    fun `the fan-out never iterates the list it is still filling`() {
        // `collected` is a Collections.synchronizedList, whose iteration needs
        // manual synchronization - and the fan-out appends to it from every
        // rail's own coroutine. The streamed insert used to iterate it
        // (`collected.map { railKeyOf(it) }`) from exactly those coroutines, so
        // two rails finishing close together could throw
        // ConcurrentModificationException out of the build: the outer catch logs
        // it, the final publish never runs, and Home is left holding only the
        // rails that had streamed so far, in resolution order - the missing and
        // shuffled catalogs of the report.
        val src = squash(source(HOME_VM))
        listOf(
            "collected.map",
            "collected.filter",
            "collected.indexOf",
            "collected.forEach",
            "collected.sortedBy",
            "collected.toList"
        ).forEach { call ->
            assertFalse(
                "no code may iterate 'collected' (use the rail key map instead): $call",
                src.contains(call)
            )
        }
        assertTrue(
            "its one read is the publish, after every rail has landed",
            src.contains("orderCatalogRails(collected, catalogOrder)")
        )
    }

    private companion object {
        const val HOME_VM = "com/kennyb1201/kbstream/ui/home/HomeViewModel.kt"
    }
}
