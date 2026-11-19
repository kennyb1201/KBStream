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
                    warm.contains("val rail = slot.rail") &&
                    warm.contains("val index = slot.itemIndex")
            )
            if (name == "tiles") {
                assertTrue(
                    "tiles: it also reads the rail's number, because its warm " +
                        "reaches across rails; the hero warm no longer does",
                    warm.contains("val railIndex = slot.railIndex")
                )
            }
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
    fun `S2 - the hero bitmap warm is one card each way, and the rail heads are the tile warm's`() {
        val hero = block(model, "private fun warmHeroLookahead(")
        assertTrue(
            "hero: one forward - a hero entry is nine rail tiles, so a cache that " +
                "also holds the rail cannot carry six (build 387: 31MB of 47MB was " +
                "hero art, hits 22%)",
            hero.contains(".take(HERO_BITMAP_LOOKAHEAD_CARDS)") &&
                model.contains("private const val HERO_BITMAP_LOOKAHEAD_CARDS = 1")
        )
        assertTrue(
            "hero: 1 back, reversed so the nearest is warmed first",
            hero.contains("maxOf(0, index - HERO_LOOKAHEAD_BACK)") &&
                hero.contains(".asReversed()") &&
                model.contains("private const val HERO_LOOKAHEAD_BACK = 1")
        )
        assertFalse(
            "hero: the rails above and below are no longer warmed at HERO size - " +
                "the tile warm below covers those head cards at a ninth of the bytes",
            hero.contains("rails.getOrNull(railIndex - 1)?.items") ||
                hero.contains("rails.getOrNull(railIndex + 1)?.items")
        )
        assertTrue(
            "hero: deduped and capped at 6",
            hero.contains(".distinctBy { \"\${it.type}:\${it.id}\" }") &&
                hero.contains(".take(HERO_LOOKAHEAD_MAX_CARDS)") &&
                model.contains("private const val HERO_LOOKAHEAD_MAX_CARDS = 6")
        )
        val tiles = block(model, "private fun warmRailTiles(")
        assertTrue(
            "tiles: 4 forward, 2 back, 2 rail-head cards - its own numbers, and " +
                "the only warm that reaches across rails now",
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
    fun `S3 - focus triggers the tiles, the settled hero triggers the lookahead`() {
        val focus = block(model, "fun warmHeroFocus(")
        assertTrue(
            "episode rows still return early",
            focus.contains("if (warmEpisodeRowNeighbours(item)) return")
        )
        assertTrue(
            "the tile warm still starts on the focus event",
            focus.contains("warmRailTiles(item)")
        )
        assertFalse(
            "and the hero lookahead no longer does: six ~3.5MB decodes per D-pad " +
                "step filled the cache with art the walk never drew (113 warms, " +
                "18 draws, 0 memory hits on the build-386 walk; 2026-11-19 churn F1)",
            focus.contains("warmHeroLookahead(item)")
        )
        assertTrue(
            "it starts after the art dwell instead, so it describes the card the " +
                "viewer stopped on",
            block(model, "fun resolveHeroMeta(").let { resolve ->
                val dwell = resolve.indexOf("awaitHeroArtDwell(resolveStartedAtMs)")
                dwell in 1 until resolve.indexOf("warmHeroLookahead(item)")
            }
        )
        assertTrue(
            "the arrival counter the paste reads is still noted",
            focus.contains("ImageLoadTrace.noteHeroFocusArrival()")
        )
    }

    // ── the episode rows: the scan the first fix missed (diagnosis 2026-11-18, §4)

    @Test
    fun `the episode rows are looked up, not scanned`() {
        val warm = block(model, "private fun warmEpisodeRowNeighbours(")
        assertFalse(
            "no `indexOfFirst` over either row is left on the focus path",
            warm.contains("indexOfFirst")
        )
        assertTrue(
            "the position comes from the episode index",
            warm.contains("val slot = episodeRowSlotOf(item) ?: return false") &&
                warm.contains("slot.itemIndex")
        )
    }

    @Test
    fun `the episode index rebuilds only when a row publishes`() {
        val index = block(model, "private fun episodeRowSlotOf(")
        assertTrue(
            "both rows by identity, so a publish invalidates it and nothing else",
            index.contains(
                "if (source == null || source.first !== upNext || source.second !== upcoming) {"
            )
        )
        assertTrue(
            "the check precedes the build - that ordering is the whole saving",
            index.indexOf("if (source == null ||") < index.indexOf("val built = HashMap")
        )
        assertTrue(
            "first wins, which is what the two indexOfFirst calls answered with",
            index.contains("episodeRowIndexSource = upNext to upcoming") &&
                index.contains("if (candidate.id.isNotBlank() && candidate.id !in built)")
        )
        assertTrue(
            "replaced, never mutated, so the lock covers a swap and a read",
            model.contains("@Synchronized\n    private fun episodeRowSlotOf(")
        )
    }

    @Test
    fun `the episode warm asks for the art its cards draw`() {
        val warm = block(model, "private fun warmEpisodeRowNeighbours(")
        assertTrue(
            "the URL per row comes from the card's own rule, not from `poster`",
            warm.contains("upNextCardArt(") && warm.contains("upcomingCardArt(")
        )
        val cw = block(model, "private fun upNextCardArt(")
        assertTrue(
            "Continue Watching: the episode still unless spoiler-free hides it",
            cw.contains("item.episodeThumbnail ?: item.backdrop ?: item.poster") &&
                cw.contains("item.backdrop ?: item.poster") &&
                cw.contains("SpoilerFree.hidesIdentity(") &&
                cw.contains("started = item.badge == UpNextBadge.CONTINUE_WATCHING")
        )
        val up = model.substringAfter("private fun upcomingCardArt(")
            .substringBefore("\n\n")
        assertTrue(
            "Upcoming: the backdrop first, as its card draws it",
            up.contains("item.backdrop ?: item.poster")
        )
    }

    @Test
    fun `the episode warm asks for the size its cards draw`() {
        val warm = block(model, "private fun warmEpisodeRowNeighbours(")
        assertTrue(
            "each row warms at its own card's size - Coil keys the memory cache by " +
                "requested size, so one size cannot answer both rows",
            warm.contains("ArtWarm.warmAll(context, cwUrls, cwWidthPx, heightPx)") &&
                warm.contains("ArtWarm.warmAll(context, upUrls, upWidthPx, heightPx)")
        )
        assertTrue(
            "the sizes are the slot minus the row's gap inset, and the card height",
            model.contains("CONTINUE_WATCHING_ART_WIDTH_DP = 212f") &&
                model.contains("UPCOMING_ART_WIDTH_DP = 200f") &&
                model.contains("EPISODE_ROW_ART_HEIGHT_DP = 146f")
        )
        assertFalse(
            "the 1280x720 hero-size episode warm is gone: no card in either row " +
                "asks for that entry",
            model.contains("EPISODE_STILL_W")
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
