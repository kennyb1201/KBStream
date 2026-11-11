package com.kennyb1201.kbstream.ui.search

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Browse/Search's two minors (spec 2026-11-11).
 *
 * Both are wiring gaps with a visible symptom and no rule to change:
 *
 *  - the same title could play its trailer from a browse page and not from a
 *    search result, because search rails were scoped out of the long-press
 *    trailer spec (F1);
 *  - two grids whose title + add-on name collide opened at each other's scroll
 *    offset, because the LazyGridState was keyed by that pair while the content
 *    was already keyed by `catalogId` (F2).
 *
 * Source-reading: both surfaces are Compose screens this module has no harness
 * for, so what is pinned is the wiring. Every test below fails against the
 * 0.9.223 source.
 */
class BrowseSearchMinorsContractTest {

    private val tvSearch by lazy { source(TV_SEARCH) }
    private val phoneSearch by lazy { source(PHONE_SEARCH) }
    private val tvGrid by lazy { source(TV_GRID) }
    private val phoneGrid by lazy { source(PHONE_GRID) }
    private val menu by lazy { source(MENU) }

    // ── F1: the Search tile menu plays trailers ────────────────────────────

    @Test
    fun `both search menus carry the trailer row, between Details and watched`() {
        listOf("TV" to tvMenu(), "phone" to phoneMenu()).forEach { (surface, block) ->
            val details = block.indexOf("label = \"Go to Details\"")
            val trailer = block.indexOf("trailerMenuAction(")
            val watched = block.indexOf("label = watchedMenuLabel(")
            assertTrue("F1: $surface has the trailer row", trailer >= 0)
            assertTrue(
                "F1: $surface places it after Go to Details, as every other menu does",
                details in 0 until trailer
            )
            assertTrue(
                "F1: and before the watched toggle",
                trailer in 0 until watched
            )
        }
    }

    @Test
    fun `the trailer row is wired to the split ids and the result's type`() {
        listOf("TV" to tvMenu(), "phone" to phoneMenu()).forEach { (surface, block) ->
            val action = block
                .substringAfter("trailerMenuAction(")
                .substringBefore("label = watchedMenuLabel(")
            assertTrue(
                "F1: $surface passes the id pair the menu already resolved",
                action.contains("tmdbId = resultIds.tmdbId,") &&
                    action.contains("imdbId = resultIds.imdbId,")
            )
            assertTrue(
                "F1: $surface passes the row's own type, which is what the videos call is keyed on",
                action.contains("type = result.type,")
            )
            assertTrue(
                "F1: $surface dismisses through the menu's own state clear, like its other rows - " +
                    "this menu's dismiss is not a focus hand-off",
                action.contains("onDismissFirst = { menuResult = null }") &&
                    action.contains("context = context")
            )
        }
    }

    @Test
    fun `the shared trailer contract is untouched, so a missing trailer still toasts`() {
        assertTrue(
            "F1: always shown, no pre-fetch per menu open",
            menu.contains("label = \"Play trailer\"") &&
                menu.contains("fun trailerMenuAction(")
        )
        assertTrue(
            "F1: dismiss first, then fetch - the menu animates out while the call is in flight",
            menu.indexOf("onDismissFirst()") in 0 until menu.indexOf("videosFor(")
        )
        assertTrue(
            "F1: a null tmdbId takes the one toast: no navigation, no retry",
            menu.contains("if (tmdbId == null) {") &&
                menu.contains("NO_TRAILER_TOAST") &&
                menu.contains("TrailerPick.best(videos)")
        )
        assertTrue(
            "F1: and imdbId stays optional - the launcher derives it when it must",
            menu.contains("imdbId: String?,")
        )
        assertTrue(
            "F1: no other surface's menu was touched - the browse grids still call it",
            source(TV_CATALOG).contains("trailerMenuAction(") &&
                source(PHONE_CATALOG).contains("trailerMenuAction(")
        )
    }

    // ── F2: the grid's scroll state is keyed by the whole catalog identity ─

    @Test
    fun `both grids key their scroll state by title, addon name AND catalog id`() {
        assertTrue(
            "F2: the TV grid keys all three",
            tvGrid.contains("val gridState = key(title, addonName, catalogId) { rememberLazyGridState() }")
        )
        assertTrue(
            "F2: and so does the phone's",
            phoneGrid.contains("val gridState = key(title, addonName, catalogId) { rememberLazyGridState() }")
        )
        assertTrue(
            "F2: the pair-only key is gone from both - it is the shape that collided",
            !tvGrid.contains("key(title, addonName) {") &&
                !phoneGrid.contains("key(title, addonName) {")
        )
        assertTrue(
            "F2: catalogId is the route parameter on both, so the key can name it",
            tvGrid.contains("catalogId: String? = null,") &&
                phoneGrid.contains("catalogId: String? = null,")
        )
    }

    @Test
    fun `content seeding and return restore still use the same identity`() {
        assertTrue(
            "F2: the content was already catalogId-keyed - the scroll now matches it",
            tvGrid.contains("viewModel.openCatalogInGrid(title, addonName, catalogId, type)") &&
                phoneGrid.contains("viewModel.openCatalogInGrid(title, addonName, catalogId, type)")
        )
        assertTrue(
            "F2: the leave effect stays Unit-keyed, so a replaced grid still closes itself",
            tvGrid.contains("DisposableEffect(Unit) {") &&
                phoneGrid.contains("DisposableEffect(Unit) {")
        )
        assertTrue(
            "F2: the state is still hoisted above the loading branch (the `KBStatusMessage(" +
                "loading = true` frame of the TV grid), which is what keeps the offset across " +
                "an un-seeded frame",
            tvGrid.indexOf("key(title, addonName, catalogId)") <
                tvGrid.indexOf("KBStatusMessage(loading = true")
        )
    }

    // ── source access ───────────────────────────────────────────────────────

    private fun tvMenu(): String =
        tvSearch
            .substringAfter("menuResult?.let { result ->")
            .substringBefore("hiddenChipMenu?.let {")

    private fun phoneMenu(): String =
        phoneSearch
            .substringAfter("menuResult?.let { result ->")
            .substringBefore("recentMenu?.let {")

    private fun source(relative: String): String {
        val file = File(findSourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

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

    private companion object {
        const val TV_SEARCH = "com/kennyb1201/kbstream/ui/search/SearchScreen.kt"
        const val PHONE_SEARCH = "com/kennyb1201/kbstream/ui/mobile/SearchScreenMobile.kt"
        const val TV_GRID = "com/kennyb1201/kbstream/ui/home/CatalogGridScreen.kt"
        const val PHONE_GRID = "com/kennyb1201/kbstream/ui/mobile/CatalogGridScreenMobile.kt"
        const val MENU = "com/kennyb1201/kbstream/ui/components/PosterContextMenu.kt"
        const val TV_CATALOG = "com/kennyb1201/kbstream/ui/home/CatalogGridScreen.kt"
        const val PHONE_CATALOG = "com/kennyb1201/kbstream/ui/mobile/CatalogGridScreenMobile.kt"
    }
}
