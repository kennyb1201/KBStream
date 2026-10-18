package com.kennyb1201.kbstream.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The P3 polish pass, pinned where it can be seen.
 *
 * Twenty items, every one of them a value: a padding, an inset, a type style, a
 * wording. None of them are behaviour, which is exactly why they need pinning -
 * a 2dp inset is invisible in review and comes back the next time someone
 * touches the file it lives in. This is the same shape as the UiUniformity
 * batches: each item is asserted against the file the sweep named, so the
 * number cannot be un-chosen without something going red.
 *
 * Read out of the source, like the other UI contracts in this tree: this module
 * has no Compose harness for screens that want a ViewModel and network data.
 */
class UiPolishP3ContractTest {

    private fun source(path: String): String {
        val file = File(findMainSourceRoot(), path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun count(haystack: String, needle: String): Int =
        haystack.split(needle).size - 1

    // ── Home ────────────────────────────────────────────────────────────

    @Test
    fun `upcoming badges use the same inner padding as every other rail badge`() {
        val home = source(HOME)
        assertEquals(
            "the two Upcoming badges are 6x3, the padding every other badge on " +
                "Home's rails uses",
            2,
            count(home, ".padding(horizontal = 6.dp, vertical = 3.dp)")
        )
        assertFalse(
            "and the tighter 5x2 pair is gone: they sit on adjacent rails, so " +
                "the difference was visible",
            home.contains("horizontal = 5.dp, vertical = 2.dp")
        )
    }

    @Test
    fun `the catalog grid is on the Library grid's spacing`() {
        val grid = source(CATALOG_GRID)
        assertTrue(
            "12dp between columns and 16dp between rows, matching the Library " +
                "grid that holds the same posters in the same shape",
            grid.contains("horizontalArrangement = Arrangement.spacedBy(12.dp)") &&
                grid.contains("verticalArrangement = Arrangement.spacedBy(16.dp)")
        )
        assertFalse(
            "and the 10/14 pair it was drawn with is gone",
            grid.contains("spacedBy(10.dp)") || grid.contains("spacedBy(14.dp)")
        )
    }

    // ── Search ──────────────────────────────────────────────────────────

    @Test
    fun `the search screens' loose ends are tied`() {
        val search = source(SEARCH)
        assertTrue(
            "a full sentence, with the stop its sibling status lines end on",
            search.contains("message = \"No matches found.\"")
        )
        assertTrue(
            "the Titles rail keeps 4dp under its posters, like the suggestion " +
                "and recent rows above it",
            search.contains("bottom = 4.dp")
        )
        val rails = source(SEARCH_RAILS)
        assertTrue(
            "and the shared SearchRail does too",
            rails.contains("bottom = 4.dp") && !rails.contains("bottom = 2.dp")
        )
        val browser = source(BROWSE_BROWSER)
        assertTrue(
            "the browse chips use the same 12x7 inset as the search chips they " +
                "hand off to",
            browser.contains("horizontal = 12.dp, vertical = 7.dp")
        )
        assertFalse(
            "and their own 13x8 is gone",
            browser.contains("horizontal = 13.dp, vertical = 8.dp")
        )
    }

    // ── Streams, Decade/Tag ─────────────────────────────────────────────

    @Test
    fun `the mismatch notice says the sources HOLD the episode`() {
        assertTrue(
            "the sentence is about what each source carries",
            source(STREAMS).contains("None of these sources holds S%02dE%02d")
        )
    }

    @Test
    fun `the header fans draw the app's hairline`() {
        listOf(DECADE, TAG).forEach { path ->
            val file = source(path)
            assertTrue(
                "$path: KBTextLo at 25%, the app's hairline (the Collection " +
                    "header's own fan included)",
                file.contains("KBTextLo.copy(alpha = 0.25f)")
            )
            assertFalse(
                "$path: no Color.White hairline left - it read brighter and " +
                    "bluer than every other edge in the app",
                file.contains("Color.White.copy")
            )
        }
    }

    // ── KB folders ──────────────────────────────────────────────────────

    @Test
    fun `folder captions sit on the app's caption beat`() {
        val layouts = source(KB_LAYOUTS)
        assertEquals(
            "the rails' captions and the grid's, both at 5dp",
            2,
            count(layouts, "padding(top = 5.dp")
        )
        assertFalse(
            "the 2dp that read as touching the tile is gone",
            layouts.contains("padding(top = 2.dp")
        )
    }

    @Test
    fun `the folder error card can retry`() {
        val layouts = source(KB_LAYOUTS)
        assertTrue(
            "the card takes a retry and every layout's error branch passes it",
            layouts.contains("onRetry: (() -> Unit)? = null") &&
                layouts.contains("onRetry = onRetry")
        )
        assertEquals(
            "one per layout: the hero rails, the rows and the grid",
            3,
            count(layouts, "FolderStatus(state.error.orEmpty(), onRetry = onRetryFolder)")
        )
        val viewModel = source(KB_VIEW_MODEL)
        assertTrue(
            "and the ViewModel re-runs the load, forced: both of its existing " +
                "entry points treat \"already loaded\" as nothing to do, which " +
                "is the state a failed load leaves behind",
            viewModel.contains("fun retry(folderId: String)") &&
                viewModel.contains("force = true")
        )
        assertTrue(
            "the screen wires it to the folder it is showing",
            source(KB_SCREEN).contains("onRetryFolder = { viewModel.retry(folderId) }")
        )
    }

    // ── Actor, Library ──────────────────────────────────────────────────

    @Test
    fun `the bio opens and closes on an animation`() {
        val actor = source(ACTOR)
        assertTrue(
            "animateContentSize on the bio's column, so View more/less slides " +
                "instead of snapping between four lines and the whole paragraph",
            actor.contains(".animateContentSize()")
        )
    }

    @Test
    fun `the empty watchlist reads as one sentence`() {
        val library = source(LIBRARY)
        assertTrue(
            "an em dash, not a full stop: the reasons belong to the sentence " +
                "rather than being stranded after it",
            library.contains("\"Watchlist is empty — \" + sources.joinToString(\" · \")")
        )
        assertFalse(
            "the full stop that broke it is gone",
            library.contains("\"Watchlist is empty. \" + sources")
        )
    }

    // ── Settings, Stats ─────────────────────────────────────────────────

    @Test
    fun `the settings dialogs use the app's plate and buttons`() {
        val settings = source(SETTINGS)
        assertTrue(
            "both dialogs are on KBDialogPanel, the player picker at its own width",
            settings.contains("KBDialogPanel(title = \"Clear Continue Watching?\")") &&
                settings.contains(
                    "KBDialogPanel(title = \"Play titles in which app?\", width = 640.dp)"
                )
        )
        assertTrue(
            "and their actions are the app's one button, the destructive one " +
                "carrying the danger tint",
            settings.contains("KBButton(label = \"CLEAR\", danger = true") &&
                settings.contains("KBButton(label = \"DONE\"")
        )
        assertFalse(
            "no hand-rolled plate or button is left in this file",
            settings.contains("KBShapePanel")
        )
    }

    @Test
    fun `the version and changelog plates are not D-pad stops`() {
        val settings = source(SETTINGS)
        assertFalse(
            "VERSION and WHAT'S NEW are read, never pressed",
            settings.contains("KBCard(onClick = {}")
        )
        val stats = source(STATS)
        assertFalse(
            "and so are the stats tiles and the top-shows rows",
            stats.contains("KBCard(onClick = {}")
        )
        assertTrue(
            "both drawn as plain non-focusable plates instead",
            stats.contains("shape = KBShapeCard,")
        )
    }

    /**
     * The About pane ends on the author credit, in the same read-only style as
     * the lines above it: it is wording about the app's author, so it is pinned
     * here rather than left to a layout pass that could quietly drop it.
     */
    @Test
    fun `the about pane credits its author`() {
        val settings = source(SETTINGS)
        assertTrue(
            "About ends on the centered author credit",
            settings.contains("text = \"Made with ❤️ by Kenny B\",") &&
                settings.contains("textAlign = TextAlign.Center,")
        )
    }

    // ── Simkl, Guide, Sports, MPV ───────────────────────────────────────

    @Test
    fun `the Simkl screen's actions are the app's one button`() {
        val simkl = source(SIMKL)
        assertTrue(
            "every action is a KBButton now",
            !simkl.contains("KBCard(") &&
                simkl.contains("KBButton(label = \"REFRESH\"") &&
                simkl.contains("KBButton(label = \"BACK TO HOME\"")
        )
        assertTrue(
            "and DISCONNECT carries the danger affordance it was missing",
            simkl.contains("label = \"DISCONNECT\",\n                            danger = true")
        )
    }

    @Test
    fun `the guide's search labels both halves of its hits`() {
        val guide = source(GUIDE)
        assertTrue(
            "CHANNELS joins PROGRAMS, in the same labelSmall/KBTextLo style",
            guide.contains("text = \"CHANNELS\"") &&
                guide.contains("text = \"PROGRAMS\"")
        )
        assertFalse(
            "and the favourite stars are one size, whichever row they are on",
            guide.contains("size(15.dp)")
        )
    }

    @Test
    fun `the league view toggle matches the league tabs`() {
        val sports = source(SPORTS)
        // Comments stripped: the note beside this chip NAMES the values it was
        // moved off, and a contract that read them would be pinning prose.
        val chip = sports.substringAfter("private fun LeagueViewChip(")
            .substringBefore("\n}\n")
            .lines()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")
        assertTrue(
            "the tabs' own label style and pill padding",
            chip.contains("typography.labelLarge") &&
                chip.contains("padding(horizontal = 14.dp, vertical = 8.dp)")
        )
        assertTrue(
            "and the tabs' brighter unselected label",
            chip.contains("else KBTextHi")
        )
        assertFalse(
            "at neither of the smaller values it was drawn with",
            chip.contains("labelSmall") || chip.contains("vertical = 6.dp")
        )
    }

    @Test
    fun `the MPV video header is on the panels' 16dp beat`() {
        val mpv = source(MPV_SETTINGS)
        assertTrue(
            "16, like every other section header in both player panels",
            mpv.contains("ui.header(\"VIDEO\", topMarginDp = 16)")
        )
        assertFalse("and not the 14 it was", mpv.contains("topMarginDp = 14"))
    }

    // ── plumbing ────────────────────────────────────────────────────────

    private fun findMainSourceRoot(): File {
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
        const val UI = "com/kennyb1201/kbstream/ui/"
        const val HOME = "${UI}home/HomeScreen.kt"
        const val CATALOG_GRID = "${UI}home/CatalogGridScreen.kt"
        const val SEARCH = "${UI}search/SearchScreen.kt"
        const val SEARCH_RAILS = "${UI}search/SearchScreenPart2.kt"
        const val BROWSE_BROWSER = "${UI}search/BrowseBrowser.kt"
        const val STREAMS = "${UI}streams/StreamsScreen.kt"
        const val DECADE = "${UI}decade/DecadeScreen.kt"
        const val TAG = "${UI}tag/TagScreen.kt"
        const val KB_LAYOUTS = "${UI}kb/KBFolderLayouts.kt"
        const val KB_SCREEN = "${UI}kb/KBFolderScreen.kt"
        const val KB_VIEW_MODEL = "${UI}kb/KBFolderViewModel.kt"
        const val ACTOR = "${UI}actor/ActorScreen.kt"
        const val LIBRARY = "${UI}library/LibraryScreen.kt"
        const val SETTINGS = "${UI}settings/SettingsScreen.kt"
        const val STATS = "${UI}settings/StatsScreen.kt"
        const val SIMKL = "${UI}simkl/SimklConnectScreen.kt"
        const val GUIDE = "${UI}iptv/GuideScreen.kt"
        const val SPORTS = "${UI}sports/SportsHubScreen.kt"
        const val MPV_SETTINGS = "${UI}player/MpvSettingsSection.kt"
    }
}
