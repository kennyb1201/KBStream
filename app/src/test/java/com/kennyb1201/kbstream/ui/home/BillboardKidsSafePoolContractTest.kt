package com.kennyb1201.kbstream.ui.home

import com.kennyb1201.kbstream.data.addon.MetaPreview
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The billboard's kids-safe pool (spec 2026-11-12).
 *
 * The hero - the phone's billboard and the TV's Spotlight hero - must never show
 * an adult title on a kids profile. The trending-day default feed was already
 * ceiling-filtered per page; the rails-fed pool was not, so a chosen catalog
 * dealt whatever the rail held. The fix is one gate before the dealer:
 * [billboardKidsSafePool]'s algorithm, applied in the resolve, which both
 * surfaces pass through.
 *
 * The filter itself is the repository's existing `kidsFilterMetas` - the rule Up
 * Next and the catalog rails already use - so what this pins is that the
 * billboard reaches it, keyed per profile, with the walk's own "full" as the
 * fallback threshold.
 */
class BillboardKidsSafePoolContractTest {

    private fun item(id: String) = MetaPreview(id = id, type = "movie", name = id)

    private fun pool(size: Int, prefix: String = "t") =
        (1..size).map { index -> item("$prefix$index") }

    // ── F3: the fallback rule, as behaviour ───────────────────────────────

    @Test
    fun `a feed that survives the ceiling at full size is dealt untouched`() {
        val filtered = pool(BILLBOARD_MAX_PAGES, "feed")
        val walked = pool(BILLBOARD_MAX_PAGES, "day")

        assertEquals(
            "a full feed is the pool - the default feed is not merged in over it",
            filtered.map { it.id },
            billboardKidsSafePool(filtered, walked).map { it.id }
        )
    }

    @Test
    fun `a ceiling-thinned feed falls back to the longer walked pool`() {
        val filtered = pool(3, "feed")
        val walked = pool(BILLBOARD_MAX_PAGES, "day")

        assertEquals(
            "three survivors out of a rail is a hero with nothing to swipe to, so the " +
                "walked, ceiling-filtered default feed is the pool instead",
            walked.map { it.id },
            billboardKidsSafePool(filtered, walked).map { it.id }
        )
    }

    @Test
    fun `an empty feed falls back too, never a bare hero`() {
        val walked = pool(BILLBOARD_MAX_PAGES, "day")

        assertEquals(
            "nothing survived the ceiling: no empty hero",
            walked.map { it.id },
            billboardKidsSafePool(emptyList(), walked).map { it.id }
        )
    }

    @Test
    fun `a default pool no longer than the feed keeps the feed`() {
        val filtered = pool(4, "feed")
        val walked = pool(4, "day")

        assertEquals(
            "when the ceiling thinned both, the viewer keeps the catalog they picked",
            filtered.map { it.id },
            billboardKidsSafePool(filtered, walked).map { it.id }
        )
    }

    // ── F1: one gate, before a page is dealt, on both surfaces ────────────

    @Test
    fun `the resolve filters the whole pool before any page is dealt`() {
        val vm = source(HOME_VM)
        val resolve = vm.substringAfter("fun resolveBillboard(items: List<MetaPreview>)")
            .substringBefore("private fun publishBillboardPool(")
        assertTrue(
            "the ceiling pass runs first, and it is the repository's existing filter",
            resolve.contains("billboardKidsSafePool(items)") &&
                vm.contains("tmdbRepository.kidsFilterMetas(items)")
        )
        assertTrue(
            "an adult profile is a no-op: no ceiling, the feed straight through",
            vm.contains("tmdbRepository.kidsMaxAge() ?: return items")
        )
        val publisher = vm.substringAfter("private fun publishBillboardPool(")
            .substringBefore("private suspend fun resolveBillboardPage(")
        assertTrue(
            "the pages are seeded from the FILTERED pool, never the raw feed",
            publisher.contains("val feed = items") &&
                publisher.contains("_billboardPages.value = dealt.map")
        )
        assertFalse(
            "and the raw request list never reaches the dealer",
            resolve.contains("_billboardPages.value =")
        )
        listOf(PHONE to "phone", SPOTLIGHT to "TV").forEach { (path, surface) ->
            assertTrue(
                "the $surface billboard resolves through that one gate",
                source(path).contains("viewModel.resolveBillboard(")
            )
        }
    }

    @Test
    fun `up next and the billboard share the one ceiling rule`() {
        val vm = source(HOME_VM)
        assertTrue(
            "Up Next already went through kidsFilterMetas",
            vm.contains("tmdbRepository.kidsFilterMetas(")
        )
        val unrated = source(TMDB)
        assertTrue(
            "and the unrated rule lives there once: kept on PG-13, dropped on the " +
                "stricter ceilings",
            unrated.contains("val allowed = if (detail == null) {") &&
                unrated.contains("ceiling == KidsMode.CEIL_PG13")
        )
        assertFalse(
            "nothing in the billboard's own path restates it",
            source(HOME_VM).substringAfter("private suspend fun billboardKidsSafePool(")
                .substringBefore("private fun publishBillboardPool(")
                .contains("CEIL_PG13")
        )
    }

    // ── F2: the pool belongs to the profile, and a switch re-deals ────────

    @Test
    fun `the published pool is keyed by profile`() {
        val vm = source(HOME_VM)
        assertTrue(
            "keyed with the same spelling the cached default feed uses",
            vm.contains("val forProfile = \"\${profile?.id}:\${profile?.kidsMaxAge}\"") &&
                vm.contains("private var billboardPoolFor: String? = null")
        )
        assertTrue(
            "and a profile switch is not 'nothing new' even for the same titles",
            vm.contains("billboardPoolFor == forProfile")
        )
        assertTrue(
            "the switch clears the key it was dealt for",
            vm.contains("billboardPoolFor = null")
        )
        assertTrue(
            "and asks for the same pool again, so the new ceiling filters it",
            vm.contains("billboardRequestedItems?.let { requested -> resolveBillboard(requested) }")
        )
        assertTrue(
            "the requested pool is remembered when the screens ask",
            vm.contains("billboardRequestedItems = items")
        )
    }

    // ── F4: the walk and the chooser are untouched ────────────────────────

    @Test
    fun `the trending walk, the chooser and the deal are unchanged`() {
        val rules = source(RULES)
        assertTrue(
            "the walk still stops on its own two rules",
            rules.contains("pagesWalked < BILLBOARD_TRENDING_MAX_WALK_PAGES && poolSize < BILLBOARD_MAX_PAGES")
        )
        val vm = source(HOME_VM)
        assertTrue(
            "and the pool is still shuffled only when its identity changes",
            vm.contains("feed.shuffled()")
        )
    }

    private fun source(relative: String): String {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) {
                    val file = File(candidate, relative)
                    assertTrue("source missing: $file", file.isFile)
                    return file.readText()
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private companion object {
        const val HOME_VM = "com/kennyb1201/kbstream/ui/home/HomeViewModel.kt"
        const val RULES = "com/kennyb1201/kbstream/ui/home/BillboardRules.kt"
        const val PHONE = "com/kennyb1201/kbstream/ui/mobile/HomeScreenMobile.kt"
        const val SPOTLIGHT = "com/kennyb1201/kbstream/ui/home/SpotlightHome.kt"
        const val TMDB = "com/kennyb1201/kbstream/data/tmdb/TmdbRepository.kt"
    }
}
