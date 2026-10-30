package com.kennyb1201.kbstream.ui.mobile

import androidx.compose.ui.unit.dp
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Portrait browsing, pinned where a JVM test can see it.
 *
 * The phone was built as a touch twin of the landscape television UI, so a
 * portrait phone inherited fixed-width panels, two-column grids and a top bar
 * that assumed room for five controls. This pass makes browsing follow the
 * device: portrait is a first-class shape for every browse screen, playback is
 * the only thing that asks for a side, and the width a screen actually has
 * decides how many columns it draws.
 *
 * A unit test cannot compose those layouts (there is no compose-ui-test harness
 * in this module), so the pure column arithmetic is exercised directly and the
 * layout rules are pinned in source - the same contract-test style the rest of
 * the mobile port uses.
 */
class PortraitBrowseContractTest {

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

    /** One line, so an assertion can span a multi-line declaration. */
    private fun flat(path: String): String = source(path).replace(Regex("\\s+"), " ")

    private fun mobile(path: String): String = "com/kennyb1201/kbstream/ui/mobile/$path"

    // ── 1. The width rule behind every portrait grid ─────────────────────────

    @Test
    fun `a portrait phone is three posters wide at every poster size`() {
        // 360dp minus the 16dp gutters each side.
        val portrait = 328.dp
        listOf(110.dp, 124.dp, 140.dp).forEach { tile ->
            assertEquals(
                "a ${tile.value}dp poster tile must still make three columns in " +
                    "a portrait hand; the poster-size pref cannot collapse the grid",
                3,
                mobileGridColumns(portrait, tile, 12.dp, landscape = false)
            )
        }
        // A 320dp phone is narrower still, and still gets three.
        assertEquals(
            3,
            mobileGridColumns(288.dp, 124.dp, 12.dp, landscape = false)
        )
    }

    @Test
    fun `turning the phone buys columns instead of wider posters`() {
        // 740dp landscape minus the gutters.
        val landscape = 708.dp
        assertEquals(
            "a turned phone fits five medium posters, not three huge ones",
            5,
            mobileGridColumns(landscape, 124.dp, 12.dp, landscape = false)
        )
        assertTrue(
            "and more than the portrait grid, which is the point of the rotation",
            mobileGridColumns(landscape, 124.dp, 12.dp, landscape = false) > 3
        )
        assertTrue(
            "every window is capped, so a foldable cannot draw a contact sheet",
            mobileGridColumns(2000.dp, 100.dp, 12.dp, landscape = false) <= 8
        )
    }

    @Test
    fun `landscape cards start at two columns in a portrait hand`() {
        // A 16:9 card three-across a 360dp phone is a thumbnail; two is the
        // smallest shape that still reads as a card.
        assertEquals(
            2,
            mobileGridColumns(328.dp, 210.dp, 12.dp, landscape = true)
        )
        // And they still grow with the window when the phone is turned.
        assertEquals(
            3,
            mobileGridColumns(708.dp, 210.dp, 12.dp, landscape = true)
        )
    }

    @Test
    fun `the poster-size setting still changes a wide window's grid`() {
        // 720dp (a small tablet or a foldable) has room for either answer, so
        // the pref must still choose between them.
        val wide = 688.dp
        assertEquals(
            5,
            mobileGridColumns(wide, 124.dp, 12.dp, landscape = false)
        )
        assertEquals(
            4,
            mobileGridColumns(wide, 140.dp, 12.dp, landscape = false)
        )
    }

    // ── 2. Every phone poster grid asks the rule ─────────────────────────────

    @Test
    fun `every phone poster grid derives its columns from the width`() {
        listOf(
            "CatalogGridScreenMobile.kt",
            "LibraryScreenMobile.kt",
            "CollectionScreenMobile.kt",
            "ActorScreenMobile.kt",
            "KBFolderScreenMobile.kt"
        ).forEach { file ->
            val text = flat(mobile(file))
            assertTrue(
                "$file must ask rememberMobileGridColumns for its column count",
                text.contains("rememberMobileGridColumns(")
            )
            assertTrue(
                "$file must draw a fixed column count, so the grid and its " +
                    "captions agree",
                text.contains("GridCells.Fixed(columns)")
            )
            assertFalse(
                "$file must not fall back to GridCells.Adaptive: that is the " +
                    "rule that gave a portrait phone two oversized posters",
                text.contains("GridCells.Adaptive")
            )
        }
    }

    @Test
    fun `the grid tiles keep their chosen shape in both orientations`() {
        // The cell narrows; the tile's aspect ratio does not. This is what makes
        // three portrait posters and a landscape-card grid both work.
        val posterCard = flat(
            "com/kennyb1201/kbstream/ui/components/touch/TouchGlobalPoster.kt"
        )
        assertTrue(
            "the shared touch poster picks 2:3 or 16:9 from the viewer's shape " +
                "pref, independent of the cell width",
            posterCard.contains("aspectRatio(if (useLandscape) 16f / 9f else 2f / 3f)")
        )
        val grid = flat(mobile("CatalogGridScreenMobile.kt"))
        assertTrue(
            "and the skeleton stands in at the same shape the cards will use",
            grid.contains("aspectRatio(if (landscape) 16f / 9f else 2f / 3f)")
        )
    }

    // ── 3. The browse screens' portrait shape ────────────────────────────────

    @Test
    fun `home's top bar fits a 360dp portrait phone as the wordmark alone`() {
        // The bar used to fit five controls at 360dp by shrinking the four icon
        // buttons to 40dp. Those four destinations are the bottom bar's items
        // now (see MobileBottomNav), and the profile chip followed them there -
        // so the header has a single child and nothing left to squeeze.
        val topBar = flat(mobile("HomeScreenMobile.kt"))
        assertTrue(
            "the wordmark takes the width it is given and ellipsises instead of " +
                "overflowing",
            topBar.contains("text = \"KBSTREAM\"") &&
                topBar.contains("maxLines = 1") &&
                topBar.contains("Modifier.fillMaxWidth()")
        )
        assertTrue(
            "the profile chip is gone: its door is the bottom bar's avatar",
            !topBar.contains(".widthIn(max = 140.dp)") &&
                !topBar.contains("onSwitchProfile")
        )
        // The header's own body, not the whole file: the billboard below it
        // draws an icon button of its own (the trailer's close mark), and the
        // width this test is about is the HEADER's.
        val header = topBar
            .substringAfter("private fun MobileTopBar()")
            .substringBefore("One billboard page")
        assertTrue(
            "the four icon buttons are gone from the header; that is what makes it fit",
            !header.contains("IconButton(") &&
                !header.contains("contentDescription = \"TV guide\"")
        )
        assertTrue(
            "Home's rails stay horizontal carousels in portrait",
            topBar.contains("LazyRow(")
        )
    }

    @Test
    fun `detail stacks its backdrop and actions instead of squeezing columns`() {
        val detail = flat(mobile("DetailScreenMobile.kt"))
        assertTrue(
            "the backdrop header is full-width 16:9 at the top",
            detail.contains("private fun MobileDetailHeader(") &&
                detail.contains("aspectRatio(16f / 9f)")
        )
        assertTrue(
            "the meta and calls to action are stacked below it, not beside it",
            detail.contains("item(key = \"header\")") &&
                detail.contains("item(key = \"cta\")")
        )
        assertTrue(
            "and the page itself is one vertical scroll",
            detail.contains("LazyColumn(")
        )
    }

    @Test
    fun `search keeps its field and results full-width above the keyboard`() {
        val search = flat(mobile("SearchScreenMobile.kt"))
        assertTrue(
            "the field takes the row's remaining width",
            search.contains("KBTextField(") && search.contains("Modifier.weight(1f)")
        )
        assertTrue(
            "browse chips are three across in portrait and five on a wide window",
            search.contains("screenWidthDp >= 600") &&
                search.contains("visibleEntries.chunked(browseChipColumns)")
        )
        val shell = flat("com/kennyb1201/kbstream/MainActivity.kt")
        assertTrue(
            "the app shell pads for the IME once, so the keyboard cannot sit " +
                "over the first results row",
            shell.contains(".imePadding()")
        )
        assertTrue(
            "and the results/rails are full-width scrolls",
            search.contains("LazyRow(")
        )
    }

    @Test
    fun `the guide is a portrait-safe channel list that never force-rotates`() {
        val guide = flat(mobile("GuideScreenMobile.kt"))
        assertTrue(
            "the phone guide is a vertical channel list",
            guide.contains("LazyColumn(") && guide.contains("MobileGuideChannelRow(")
        )
        assertFalse(
            "it must not ask the window for a side",
            guide.contains("requestedOrientation")
        )
        assertFalse(
            "and it must not force landscape through the helper either",
            guide.contains("applyPlaybackOrientation")
        )
    }

    @Test
    fun `settings, addons, library, sports and the connect screens stack at 360`() {
        listOf(
            "SettingsScreenMobile.kt",
            "AddonsScreenMobile.kt",
            "LibraryScreenMobile.kt",
            "SportsHubScreenMobile.kt",
            "TraktConnectScreenMobile.kt",
            "SimklConnectScreenMobile.kt"
        ).forEach { file ->
            val text = flat(mobile(file))
            assertTrue(
                "$file must apply the mobile theme",
                text.contains("MobileTheme {")
            )
            assertFalse(
                "$file must not import a tv-material3 widget",
                text.contains("import androidx.tv.material3.")
            )
        }
        // The choice controls wrap rather than run off a 360dp edge.
        val settings = flat(mobile("SettingsScreenMobile.kt"))
        assertTrue(
            "Settings' choices are a wrapping flow",
            settings.contains("FlowRow(")
        )
        assertTrue(
            "and every label row gives its switch the leftover space",
            settings.contains("weight(1f)")
        )
    }

    // ── 4. The overlay width rule ────────────────────────────────────────────

    @Test
    fun `no phone overlay has a fixed width wider than a portrait phone`() {
        val fixedWidth = Regex("\\.width\\(\\s*(\\d+)\\.dp\\s*\\)")
        val offenders = mutableListOf<String>()
        val mobileDir = File(sourceRoot, "com/kennyb1201/kbstream/ui/mobile")
        mobileDir.listFiles { file -> file.extension == "kt" }
            ?.forEach { file ->
                fixedWidth.findAll(file.readText()).forEach { match ->
                    val width = match.groupValues[1].toInt()
                    if (width > 340) {
                        offenders.add("${file.name}: ${match.value}")
                    }
                }
            }
        assertTrue(
            "dialogs, sheets and panels must cap their width instead of fixing " +
                "it above ~340dp: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun `the phone dialog plate and menus cap their width`() {
        val dialog = flat(
            "com/kennyb1201/kbstream/ui/components/touch/TouchDialog.kt"
        )
        assertTrue(
            "the touch dialog fills the width and caps it",
            dialog.contains(".fillMaxWidth()") && dialog.contains(".widthIn(max = width)")
        )
        val menu = flat(
            "com/kennyb1201/kbstream/ui/components/touch/TouchContextMenu.kt"
        )
        assertTrue(
            "long-press menus are bottom sheets, which cannot overflow sideways",
            menu.contains("ModalBottomSheet(")
        )
        val shell = flat("com/kennyb1201/kbstream/MainActivity.kt")
        listOf("560.dp", "620.dp").forEach { width ->
            assertTrue(
                "the shared $width panel is a cap with a gutter on a phone",
                shell.contains("widthIn(max = $width)") &&
                    shell.contains("padding(horizontal = 16.dp)")
            )
        }

        // The "Add to list…" picker is shared with the TV menu, and it used to
        // be a fixed 560dp TV dialog all the way through - opened from the
        // phone's own long-press sheet, with rows a thumb could not press.
        val picker = flat(
            "com/kennyb1201/kbstream/ui/components/LibraryPicker.kt"
        )
        assertTrue(
            "the shared picker caps its width on a phone",
            picker.contains("widthIn(max = 560.dp)")
        )
        assertTrue(
            "and swaps its TV rows and actions for the tappable twins",
            picker.contains("TouchPickerRow(") && picker.contains("TouchKBButton(")
        )
        assertTrue(
            "the TV panel keeps its own fixed width",
            picker.contains("Modifier.width(560.dp)")
        )
    }

    // ── 5. Rotation keeps the viewer's place ─────────────────────────────────

    @Test
    fun `a rotation keeps the screen and the scroll position`() {
        val shell = flat("com/kennyb1201/kbstream/MainActivity.kt")
        assertTrue(
            "the navigation destination survives activity recreation",
            shell.contains("var screen by rememberSaveable(stateSaver = entrySaver)")
        )
        listOf(
            "LibraryScreenMobile.kt",
            "CollectionScreenMobile.kt",
            "ActorScreenMobile.kt",
            "KBFolderScreenMobile.kt",
            "CatalogGridScreenMobile.kt"
        ).forEach { file ->
            assertTrue(
                "$file must use a saveable grid state, or turning the phone " +
                    "throws the viewer back to the first card",
                flat(mobile(file)).contains("rememberLazyGridState()")
            )
        }
        val browse = flat(mobile("BrowseSectionsMobile.kt"))
        assertTrue(
            "each browse rail's sideways offset is saved too",
            browse.contains("rememberSaveable(") && browse.contains("mapSaver(")
        )
    }
}
