package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The focus-path warm looks its card up instead of scanning for it.
 *
 * Spec 2026-11-18, warm-ahead dispatch cost. `warmHeroFocus` runs synchronously
 * from the focus handler, so on a held D-pad (a step every 30-100 ms) the warm
 * paid four full passes over every rail's items before it queued a single
 * decode: `rails.indexOfFirst { ANY item matches }` and then
 * `items.indexOfFirst`, twice - once in `warmHeroLookahead`, once in
 * `warmRailTiles`.
 *
 * F1 replaces those four scans with a `type:id` -> position index beside
 * `_rails`, rebuilt only when a rail's ITEMS change. F2 keeps everything else:
 * coverage stays 1 back / 3 forward / 1 rail head / max 6 (and the tile warm's
 * own numbers), the trigger is still focus, and the decode sizes are untouched.
 *
 * Source pins, because the ViewModel is not harness-compilable.
 */
class WarmDispatchCostContractTest {

    private val model = source(VIEW_MODEL)

    // ── F1: the scans are gone, the lookup is O(1) ───────────────────────

    @Test
    fun `neither warm scans the rails any more`() {
        val hero = block(model, "private fun warmHeroLookahead(")
        val tiles = block(model, "private fun warmRailTiles(")
        listOf("hero" to hero, "tiles" to tiles).forEach { (name, warm) ->
            assertFalse(
                "$name: no rail scan left",
                warm.contains("rails.indexOfFirst")
            )
            assertFalse(
                "$name: and no item scan either",
                warm.contains("rail.items.indexOfFirst") ||
                    warm.contains("items.indexOfFirst")
            )
            assertTrue(
                "$name: the position comes from the index",
                warm.contains("val slot = warmSlotOf(rails, item) ?: return") &&
                    warm.contains("val railIndex = slot.railIndex") &&
                    warm.contains("val rail = slot.rail") &&
                    warm.contains("val index = slot.itemIndex")
            )
        }
    }

    @Test
    fun `the index is built once per items change, not per focus step`() {
        val index = block(model, "private fun warmSlotOf(")
        assertTrue(
            "one build, keyed on the item lists it was built from",
            index.contains("val changed =") &&
                index.contains("rails.indices.any { index -> source[index] !== rails[index].items }")
        )
        assertTrue(
            "the change check precedes the build - that ordering is the whole saving",
            index.indexOf("if (changed) {") < index.indexOf("val built = HashMap")
        )
        assertTrue(
            "and the snapshot it keeps is the ITEM lists, not the rail instances: " +
                "the landscape-art merge copies rails without touching their items",
            index.contains("warmIndexSource = rails.map { rail -> rail.items }")
        )
        assertTrue(
            "first wins, which is what the two indexOfFirst calls answered with",
            index.contains("if (key !in built) built[key] = WarmSlot(rail, railIndex, itemIndex)")
        )
        assertTrue(
            "replaced, never mutated, so the lock covers a swap and a read",
            model.contains("@Synchronized\n    private fun warmSlotOf(")
        )
        assertTrue(
            "the index lives beside the rails it indexes",
            model.contains("private var warmIndexSource: List<List<MetaPreview>> = emptyList()") &&
                model.contains("private var warmIndex: Map<String, WarmSlot> = emptyMap()")
        )
    }

    @Test
    fun `the key is the comparison the scans made - id exact, type case-insensitive`() {
        assertTrue(
            "lowercased type, untouched id",
            model.contains(
                "private fun warmKeyOf(item: MetaPreview): String = " +
                    "\"\${item.type.lowercase()}:\${item.id}\""
            )
        )
    }

    // ── F2: coverage, trigger and sizes unchanged ────────────────────────

    @Test
    fun `S2 - the near field is the same cards it was`() {
        val hero = block(model, "private fun warmHeroLookahead(")
        assertTrue(
            "hero: 3 forward",
            hero.contains(".take(HERO_LOOKAHEAD_CARDS)") &&
                model.contains("private const val HERO_LOOKAHEAD_CARDS = 3")
        )
        assertTrue(
            "hero: 1 back, reversed so the nearest is warmed first",
            hero.contains("maxOf(0, index - HERO_LOOKAHEAD_BACK)") &&
                hero.contains(".asReversed()") &&
                model.contains("private const val HERO_LOOKAHEAD_BACK = 1")
        )
        assertTrue(
            "hero: the rail above and below, head card each",
            hero.contains("rails.getOrNull(railIndex - 1)?.items") &&
                hero.contains("rails.getOrNull(railIndex + 1)?.items") &&
                model.contains("private const val HERO_LOOKAHEAD_RAIL_HEAD = 1")
        )
        assertTrue(
            "hero: deduped and capped at 6",
            hero.contains(".distinctBy { \"\${it.type}:\${it.id}\" }") &&
                hero.contains(".take(HERO_LOOKAHEAD_MAX_CARDS)") &&
                model.contains("private const val HERO_LOOKAHEAD_MAX_CARDS = 6")
        )
        val tiles = block(model, "private fun warmRailTiles(")
        assertTrue(
            "tiles: 4 forward, 2 back, 2 rail-head cards - its own numbers",
            tiles.contains(".take(RAIL_TILE_LOOKAHEAD_FORWARD)") &&
                tiles.contains("maxOf(0, index - RAIL_TILE_LOOKAHEAD_BACK)") &&
                tiles.contains("rails.getOrNull(railIndex - 1)?.items") &&
                model.contains("private const val RAIL_TILE_LOOKAHEAD_FORWARD = 4") &&
                model.contains("private const val RAIL_TILE_LOOKAHEAD_BACK = 2") &&
                model.contains("private const val RAIL_TILE_LOOKAHEAD_RAIL_HEAD = 2")
        )
        assertTrue(
            "and the limiter is still two hero decodes at a time",
            model.contains("private val heroArtPrefetchSemaphore = Semaphore(permits = 2)")
        )
    }

    @Test
    fun `the per-step buildList work is untouched, as F1 asked`() {
        listOf(
            "private fun warmHeroLookahead(" to "hero",
            "private fun warmRailTiles(" to "tiles"
        ).forEach { (declaration, name) ->
            val warm = block(model, declaration)
            assertTrue(
                "$name: still a buildList per step",
                warm.contains("val targets = buildList {") || warm.contains("val neighbours = buildList {")
            )
            assertTrue("$name: still filters blank ids", warm.contains(".filter { it.id.isNotBlank() }"))
            assertTrue("$name: still deduped", warm.contains(".distinctBy {"))
            assertTrue(
                "$name: still guarded against a card already warming",
                warm.contains("InFlight.add(key)")
            )
            assertTrue(
                "$name: still its own launch, not a child of the resolve",
                warm.contains("viewModelScope.launch {")
            )
        }
    }

    @Test
    fun `S3 - the trigger path is the same two calls on focus`() {
        val focus = block(model, "fun warmHeroFocus(")
        assertTrue(
            "episode rows still return early",
            focus.contains("if (warmEpisodeRowNeighbours(item)) return")
        )
        assertTrue(
            "then the two warms, unchanged in order",
            focus.indexOf("warmHeroLookahead(item)") in
                1 until focus.indexOf("warmRailTiles(item)")
        )
        assertTrue(
            "the arrival counter the paste reads is still noted",
            focus.contains("ImageLoadTrace.noteHeroFocusArrival()")
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────

    /** One function's source, by matching its declaration and balancing braces. */
    private fun block(source: String, declaration: String): String {
        val at = source.indexOf(declaration)
        require(at >= 0) { "missing: $declaration" }
        val bodyStart = source.indexOf("{", at)
        require(bodyStart > at) { "no body: $declaration" }
        var depth = 0
        for (i in bodyStart until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(at, i + 1)
                }
            }
        }
        error("unbalanced: $declaration")
    }

    private fun source(relative: String): String {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate =
                    File(dir, "${prefix}src/main/java/com/kennyb1201/kbstream/$relative")
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("source not found above ${System.getProperty("user.dir")}: $relative")
    }

    private companion object {
        const val VIEW_MODEL = "ui/home/HomeViewModel.kt"
    }
}
