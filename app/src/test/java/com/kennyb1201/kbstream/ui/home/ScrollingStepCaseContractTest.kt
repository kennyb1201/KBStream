package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Scrolling round three - the STEP case (spec 2026-11-16).
 *
 * Round two fixed the HOLD: a gliding rail. This round is a deliberate D-pad
 * walk, card by card, which is how the screen is actually browsed. Per step the
 * walk paid four things that had nothing to do with what the viewer sees:
 *
 *  - the hero's meta state being nulled synchronously on every focus change
 *    (three StateFlow writes, each a hero recomposition) - F1;
 *  - the hero's text waiting on an add-on NETWORK round trip for fields the
 *    TMDB cache had already answered - F2;
 *  - the arriving card's tile being decoded from disk on the frame it appeared,
 *    because only hero-size art was ever warmed - F3;
 *  - the two 1280x720 buffers a crossfade holds - F6.
 *
 * F4 (recomposition) and F5 (idle pre-composition) are pinned here for the
 * shape that makes them real; their counts are field tests (S1).
 */
class ScrollingStepCaseContractTest {

    private val viewModel by lazy { source("com/kennyb1201/kbstream/ui/home/HomeViewModel.kt") }
    private val home by lazy { source("com/kennyb1201/kbstream/ui/home/HomeScreen.kt") }
    private val spotlight by lazy { source("com/kennyb1201/kbstream/ui/home/SpotlightHome.kt") }
    private val metrics by lazy { source("com/kennyb1201/kbstream/ui/home/HomeRailMetrics.kt") }

    // ── F1: a focus change no longer clears the hero ─────────────────────

    @Test
    fun `a focus change does not null the hero's state`() {
        // From the item's ids to the launch: past the null-item branch, which
        // SHOULD clear the hero (there is nothing to show), and over the focus
        // path that used to clear it too.
        val resolve = viewModel
            .substringAfter("val requestedId = item.id")
            .substringBefore("heroResolveJob = viewModelScope.launch")
        assertFalse(
            "the three synchronous writes that recomposed the hero per step are " +
                "gone: they cleared state the resolve is about to replace, and the " +
                "hero's text already follows focus from the MetaPreview",
            resolve.contains("_heroMeta.value = _heroMeta.value?.takeIf") ||
                resolve.contains("_heroTmdbDetail.value = null") ||
                resolve.contains("_heroTrailerKey.value = null")
        )
        // The null path (focus lost) still clears - that is a real state.
        assertTrue(
            "with no focused item the hero still empties",
            viewModel.substringAfter("if (item == null) {")
                .substringBefore("val requestedId")
                .contains("_heroMeta.value = null")
        )
    }

    // ── F2: the addon refines the hero, it does not gate it ──────────────

    @Test
    fun `the hero text publishes before the addon leg answers`() {
        val coroutine = viewModel
            .substringAfter("val resolveStartedAtMs = android.os.SystemClock.elapsedRealtime()")
            .substringBefore("val resolvedAddonMeta = addonMetaDeferred.await()")
        assertTrue(
            "the TMDB leg publishes the composite Meta itself",
            coroutine.contains("_heroMeta.value = buildHeroMeta(") &&
                coroutine.contains("addonMeta = null")
        )
        assertTrue(
            "and it does so in the early launch, which awaits only the TMDB detail",
            coroutine.substringAfter("launch {").substringBefore("_heroMeta.value").contains("tmdbDetailDeferred.await()")
        )
        assertTrue(
            "the addon leg's answer still refines that same Meta",
            viewModel.substringAfter("val resolvedAddonMeta = addonMetaDeferred.await()")
                .contains("addonMeta = resolvedAddonMeta")
        )
        assertFalse(
            "the composite Meta is no longer built inline at one gate",
            viewModel.contains("val finalMeta = resolvedAddonMeta?.copy(")
        )
        assertTrue(
            "one spelling of the composite, used by both legs",
            viewModel.contains("private fun buildHeroMeta(")
        )
    }

    // ── F3: rail-size tiles are warmed, both ways and across rails ───────

    @Test
    fun `the lookahead warms rail tiles, not just hero art`() {
        val warm = viewModel
            .substringAfter("private fun warmRailTiles(")
            .substringBefore("private suspend fun warmRailTile(")
        assertTrue(
            "the tile warm is still called from the focus entry; the hero warm is " +
                "fired there too and re-aimed by the next step (0.9.400)",
            viewModel.contains("warmHeroLookahead(item)") &&
                viewModel.substringAfter("fun warmHeroFocus(")
                    .substringBefore("private fun warmHeroLookahead(")
                    .contains("warmRailTiles(item)")
        )
        assertTrue(
            "both directions along the rail",
            warm.contains("RAIL_TILE_LOOKAHEAD_BACK") && warm.contains("RAIL_TILE_LOOKAHEAD_FORWARD")
        )
        assertTrue(
            "and the rails above and below, so a vertical move is warm too",
            warm.contains("railIndex - 1") && warm.contains("railIndex + 1")
        )
        assertTrue(
            "in parallel, not the hero warm's sequential loop",
            warm.contains("async {") && warm.contains("awaitAll()")
        )
        assertTrue(
            "at the card's own drawn size, from the one spelling of the metrics - " +
                "since 2026-11-26 that is ONE function, HomeRailMetrics.artSizePx, " +
                "which the card's own request reads too",
            warm.contains("HomeRailMetrics.artSizePx(") &&
                warm.contains("displayMetrics.density") &&
                warm.contains("widthPx = artSize.width")
        )
        val tile = viewModel
            .substringAfter("private suspend fun warmRailTile(")
            .substringBefore("/**")
        assertTrue(
            "landscape rails go through the same resolver the card uses, and " +
                "poster rails the poster the row brought",
            tile.contains("homeLandscapeArt(") && tile.contains("item.poster")
        )
        assertTrue(
            "the hero-size warm is untouched",
            viewModel.contains("private suspend fun warmHeroArtBitmap(")
        )
        // The sizes are the rail's, not a second copy of the numbers.
        assertTrue(
            "the rail composable takes its sizes from the same object",
            home.contains("HomeRailMetrics.LANDSCAPE_WIDTH_DP.dp") &&
                home.contains("HomeRailMetrics.POSTER_HEIGHT_DP.dp")
        )
        assertTrue(
            "and the metrics file spells them once",
            metrics.contains("const val LANDSCAPE_WIDTH_DP = 210f") &&
                metrics.contains("const val LANDSCAPE_HEIGHT_DP = 118f")
        )
    }

    // ── F4: the body-level Spotlight reads are scoped ────────────────────

    @Test
    fun `Spotlight's focus reads live in their own scopes`() {
        assertTrue(
            "the strip reads focus inside itself",
            home.contains("private fun SpotlightTopTint(") &&
                home.contains("SpotlightTopTint(\n                railListState = railListState,")
        )
        assertTrue(
            "and so does the resolve driver",
            home.contains("private fun SpotlightHeroFocusDriver(") &&
                home.contains("SpotlightHeroFocusDriver(\n                    focusedItemState = focusedItemState,")
        )
        assertTrue(
            "the panel host takes the state and reads it, rather than Home " +
                "resolving an id per focus change",
            spotlight.contains("focusedItemState: State<MetaPreview?>") &&
                spotlight.contains("ImdbTrailerData.imdbIdOf(focusedItemState.value?.id)") &&
                !home.contains("imdbIdOf(focusedItemState.value?.id)")
        )
        // The dwell is unchanged (S3 reads behaviour, not timings).
        assertTrue(
            "the resolve still waits the hero's own debounce",
            home.substringAfter("private fun SpotlightHeroFocusDriver(")
                .substringBefore("Target for the long-press menu")
                .contains("delay(HERO_RESOLVE_DEBOUNCE_MS)")
        )
    }

    // ── F5: adjacent rows compose while the list is idle ─────────────────

    @Test
    fun `the lazy lists keep an idle window around the viewport`() {
        // Dig four moved the rule into one shared object (the rest of the app had
        // no window at all); the rails keep their name by delegation.
        val window = source("com/kennyb1201/kbstream/ui/components/IdleListCacheWindow.kt")
        assertTrue(
            "a window both ways, through the one shared object",
            window.contains("override fun Density.calculateAheadWindow(viewport: Int): Int = viewport / 2") &&
                window.contains("override fun Density.calculateBehindWindow(viewport: Int): Int = viewport / 2") &&
                window.contains("rememberIdleLazyListState")
        )
        // The window rides the STATE in this foundation: the rails' list and the
        // rail rows each take their state from the shared helper.
        val uses = Regex("rememberIdleLazyListState").findAll(home).count()
        assertTrue(
            "applied to the rails list and to the rail rows themselves: $uses",
            uses >= 3
        )
    }

    // ── F6: one backdrop buffer through the hero swap ────────────────────

    @Test
    fun `the hero holds one backdrop buffer`() {
        val art = home
            .substringAfter("private fun HeroBackdropArt(")
            .substringBefore("/**\n * A clear logo")
        assertTrue(
            "the hero requests its own 1280x720 bitmap",
            art.contains(".size(Size(1280, 720))")
        )
        assertFalse(
            "and no longer asks Coil for a crossfade, which holds the outgoing " +
                "bitmap beside the incoming one for the length of the fade: $art",
            art.contains(".crossfade(true)")
        )
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun source(relative: String): String {
        val file = File(mainRootPath(), relative)
        if (!file.isFile) throw AssertionError("source missing: ${file.absolutePath}")
        return file.readText()
    }

    private fun mainRootPath(): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate.absolutePath
            }
            dir = dir.parentFile
        }
        throw AssertionError("main source root not found")
    }

    @Test
    fun `the metrics stay pure so the warm and the rail cannot drift`() {
        assertFalse(
            "no Compose or Android import: it is shared by a composable and a ViewModel",
            metrics.lines().any { line ->
                line.startsWith("import ") && !line.contains("kotlin.")
            }
        )
        assertEquals(
            "and it exposes the pair the warm converts",
            true,
            metrics.contains("fun widthDp(landscapeCards: Boolean): Float")
        )
    }
}
