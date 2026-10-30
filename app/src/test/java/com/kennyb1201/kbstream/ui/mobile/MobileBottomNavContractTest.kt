package com.kennyb1201.kbstream.ui.mobile

import com.kennyb1201.kbstream.Screen
import com.kennyb1201.kbstream.ui.detail.StreamsTarget
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's bottom navigation.
 *
 * The bar is the shell's, not a screen's, which is the whole property that makes
 * it worth having: switching to Search must not take the way back to Home with
 * it. What a JVM test can hold is that shape and the rules around it:
 *
 *  1. **One owner.** `MainActivity` draws it, outside the screen switch. No
 *     mobile screen hosts a bar of its own.
 *  2. **One visibility rule.** [mobileNavSelection] answers for the five
 *     top-level destinations and for nothing else, and it is *derived* from the
 *     screen - so Back cannot leave the wrong item lit.
 *  3. **The header gave it all up.** Home's top bar carries branding only; the
 *     profile switcher is the bar's avatar, so it exists on all five screens.
 *  4. **The bar is the app's**, not a stock Material strip: brand surface,
 *     accent when selected, labels under the icons, the bottom inset reserved.
 *
 * The layout itself is checked here twice over: that the bar is emitted after
 * the screen switch's Box, and - by brace depth, because token order cannot see
 * it - that the bar is the shell Column's own last child rather than a sibling
 * of the Column. That second one is not pedantry: the first version of this bar
 * passed every substring check in this file and still drew its five items along
 * the TOP of the phone, because a `}` one line early closed the screen Box
 * before the bar's container. What still needs eyes on a device is how the bar
 * looks and feels; there is none in this workspace.
 */
class MobileBottomNavContractTest {

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
        return file.readText().replace(Regex("\\s+"), " ")
    }

    private fun activity(): String = source("com/kennyb1201/kbstream/MainActivity.kt")

    /**
     * A source file exactly as written.
     *
     * [source] flattens whitespace, which is right for a substring check and
     * wrong for anything that walks the brace structure: a flattened `//`
     * comment would swallow the rest of the file.
     */
    private fun rawSource(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun rawActivity(): String =
        rawSource("com/kennyb1201/kbstream/MainActivity.kt")

    /**
     * The open-brace depth at each of [markers], over real Kotlin braces only.
     *
     * Which composable call is a *child* of which is the whole difference
     * between a bar at the bottom of the window and a bar across the top of it,
     * and a substring check cannot see it. So this walks the source once and
     * measures: string and char literals, line comments and block comments are
     * skipped (a brace in a log line is not a block), and the walk must end at
     * depth zero - a lexing mistake fails the assertion below instead of quietly
     * making a nesting claim pass. [markers] are matched at their first
     * occurrence, so each must be unique in the file.
     */
    private fun braceDepths(text: String, markers: List<String>): Map<String, Int> {
        val targets = markers
            .map { marker ->
                val at = text.indexOf(marker)
                assertTrue("marker not found in the shell: $marker", at >= 0)
                at to marker
            }
            .sortedBy { it.first }
        val found = mutableMapOf<String, Int>()
        var next = 0
        var depth = 0
        var i = 0
        var inLineComment = false
        var inBlockComment = false
        var inString = false
        var inChar = false
        while (i < text.length) {
            while (next < targets.size && targets[next].first == i) {
                found[targets[next].second] = depth
                next++
            }
            val c = text[i]
            val following = text.getOrNull(i + 1)
            when {
                inLineComment -> if (c == '\n') inLineComment = false
                inBlockComment -> if (c == '*' && following == '/') {
                    inBlockComment = false
                    i++
                }
                inString -> if (c == '\\') i++ else if (c == '"') inString = false
                inChar -> if (c == '\\') i++ else if (c == '\'') inChar = false
                c == '/' && following == '/' -> {
                    inLineComment = true
                    i++
                }
                c == '/' && following == '*' -> {
                    inBlockComment = true
                    i++
                }
                c == '"' -> inString = true
                c == '\'' -> inChar = true
                c == '{' -> depth++
                c == '}' -> depth--
            }
            i++
        }
        assertEquals(
            "the brace walk must consume the whole file, or a depth above is meaningless",
            0,
            depth
        )
        assertEquals("every marker must have been measured", markers.size, found.size)
        return found
    }

    private fun bar(): String =
        source("com/kennyb1201/kbstream/ui/mobile/MobileBottomNav.kt")

    private fun mobileHome(): String =
        source("com/kennyb1201/kbstream/ui/mobile/HomeScreenMobile.kt")

    // ------------------------------------------------------------------ selection

    @Test
    fun `each of the five top-level destinations selects its own item`() {
        assertEquals(MobileNavDestination.Home, mobileNavSelection(Screen.Home))
        assertEquals(MobileNavDestination.Search, mobileNavSelection(Screen.Search))
        assertEquals(MobileNavDestination.LiveTv, mobileNavSelection(Screen.Guide))
        assertEquals(MobileNavDestination.Library, mobileNavSelection(Screen.Library))
        assertEquals(MobileNavDestination.Settings, mobileNavSelection(Screen.Settings))
    }

    @Test
    fun `the bar is absent on every pushed screen`() {
        // Pushed screens of all five top-level destinations, plus the ones that
        // belong to none of them. A bar over a poster grid, a connect page or a
        // film is the failure this covers.
        val pushed = listOf(
            Screen.Detail(type = "movie", id = "tt1", returnTo = Screen.Home),
            Screen.Detail(type = "movie", id = "tt1", returnTo = Screen.Search),
            Screen.Actor(personId = 1, returnTo = Screen.Search),
            Screen.Studio(id = 1, name = "A24", isNetwork = false, returnTo = Screen.Search),
            Screen.Decade(decadeStart = 1990, name = "1990s", returnTo = Screen.Search),
            Screen.Tag(id = 1, name = "Action", isKeyword = false, mediaType = "movie"),
            Screen.Collection(id = 1, name = "Marvel", returnTo = Screen.Search),
            Screen.CatalogGrid(title = "Top", addonName = "Addon", returnTo = Screen.Home),
            Screen.KBFolder(folderId = "f1", returnTo = Screen.Home),
            Screen.Sports(),
            Screen.Stats,
            Screen.Addons(returnTo = Screen.Settings),
            Screen.CatalogBuilder(returnTo = Screen.Settings),
            Screen.Trakt(returnTo = Screen.Settings),
            Screen.Simkl(returnTo = Screen.Settings),
            Screen.ProfilePicker(returnTo = Screen.Settings),
            Screen.ProfileEdit(editingProfileId = null, returnTo = Screen.Settings),
            Screen.Streams(
                target = StreamsTarget(
                    contentType = "movie",
                    streamId = "tt1",
                    title = "A Film",
                    displayName = "A Film",
                    season = null,
                    episode = null,
                    resumePositionMs = 0L
                ),
                parentId = "tt1",
                returnTo = Screen.Detail("movie", "tt1"),
                parentType = "movie",
                itemPoster = null
            ),
            Screen.Player(
                url = "https://example.com/video.mp4",
                audioUrl = null,
                parentId = "tt1",
                parentType = "movie",
                season = null,
                episode = null,
                episodeStreamId = null,
                itemName = "A Film",
                itemPoster = null,
                clearLogoUrl = null,
                startPositionMs = 0L,
                sources = emptyList(),
                streamHeaders = emptyMap(),
                returnTo = Screen.Home
            )
        )
        pushed.forEach { screen ->
            assertNull(
                "the bottom bar must not be drawn on $screen - it is a pushed screen",
                mobileNavSelection(screen)
            )
        }
    }

    @Test
    fun `the selection is derived from the screen, so Back relights the right item`() {
        val main = activity()
        assertTrue(
            "the lit item must be computed from the screen being drawn, never stored " +
                "as a remembered tab - that is what goes stale when Back moves between " +
                "the five destinations",
            main.contains("val mobileNavDestination = if (FormFactor.isTv) null else " +
                "mobileNavSelection(screen)")
        )
        assertTrue(
            "and the item handed to the bar is that value",
            main.contains("selected = destination")
        )
    }

    // ------------------------------------------------------------------- hosting

    @Test
    fun `the shell hosts the bar, outside the screen switch`() {
        val main = activity()
        val switch = main.indexOf("AnimatedContent(")
        val bar = main.indexOf("MobileBottomNav(")
        assertTrue("the shell must draw the bottom bar", bar >= 0)
        assertTrue(
            "the bar must sit outside the screen switch, or it would be disposed " +
                "with the screen that owns it",
            switch >= 0 && bar > switch
        )
        val screenBox = main.indexOf("Box(modifier = Modifier.weight(1f).fillMaxWidth())")
        assertTrue(
            "the switch must be given the space above the bar, so no screen's last " +
                "row can end up underneath it",
            screenBox >= 0 && screenBox < switch
        )
        assertTrue(
            "the bar is added after the screen switch (and its splash) has closed",
            main.indexOf("} // closes AnimatedContent(") in 0 until bar
        )
        assertTrue(
            "the shell is the only host: no top-level screen may draw its own bar",
            listOf(
                "ui/mobile/HomeScreenMobile.kt",
                "ui/mobile/SearchScreenMobile.kt",
                "ui/mobile/GuideScreenMobile.kt",
                "ui/mobile/LibraryScreenMobile.kt",
                "ui/mobile/SettingsScreenMobile.kt",
                "ui/home/HomeScreen.kt",
                "ui/search/SearchScreen.kt",
                "ui/iptv/GuideScreen.kt",
                "ui/library/LibraryScreen.kt",
                "ui/settings/SettingsScreen.kt"
            ).none { path -> source("com/kennyb1201/kbstream/$path").contains("MobileBottomNav(") }
        )
    }

    /**
     * The nesting, not the ordering. This is the check whose absence shipped a
     * bar across the top of the phone: every substring assertion above passed
     * while `MobileBottomNav` sat outside the shell Column, because the brace
     * that was supposed to close the screen Box closed it one line too early.
     */
    @Test
    fun `the bar is the shell column's last child, so the column puts it at the bottom`() {
        // The bar's host is now an AnimatedVisibility (it slides and shrinks
        // rather than resizing the screen in one frame), so the host marker is
        // its `visible` line rather than the old `let`. The bar is composed
        // inside the host's content lambda, one level down per wrapping lambda
        // - the exact depth is measured below rather than assumed.
        val host = "visible = mobileNavDestination != null"
        val depths = braceDepths(
            rawActivity(),
            listOf(
                "Column(modifier = Modifier.fillMaxSize())",
                "Box(modifier = Modifier.weight(1f).fillMaxWidth())",
                "LaunchSplash()",
                host,
                "MobileBottomNav("
            )
        )
        val column = depths.getValue("Column(modifier = Modifier.fillMaxSize())")
        val screenBox = depths.getValue("Box(modifier = Modifier.weight(1f).fillMaxWidth())")
        val splash = depths.getValue("LaunchSplash()")
        val barHost = depths.getValue(host)
        val bar = depths.getValue("MobileBottomNav(")

        // A column's children are one level deeper than the line that opens it
        // (the extra levels are wrapping lambdas, a Box child, etc.).
        assertEquals(
            "the screen switch's Box must be the column's own child, so weight(1f) " +
                "hands the bar the space it does not use",
            column + 1,
            screenBox
        )
        // Deeper than the Box itself: the splash is inside the Box (and inside
        // its `if (splashVisible)` block), which is what makes it cover the
        // screen and appear without re-laying out the screen.
        assertTrue(
            "the launch splash must overlay inside that Box, as its last child, so " +
                "it covers the screen instead of taking a slot in the column (its " +
                "depth $splash must be inside the Box at depth $screenBox)",
            splash > screenBox
        )
        assertEquals(
            "the bar's host must be the column's own child - the sibling of the " +
                "weighted screen Box - so the column hands it the bottom of the " +
                "window. Measured at the column's own level instead, the bar is " +
                "OUTSIDE the column: a sibling of it in the shell's stacking host, " +
                "which is what drew the five items across the TOP of the phone",
            column + 1,
            barHost
        )
        assertTrue(
            "and the bar itself is composed inside that host's content lambda " +
                "(host at $barHost, bar at $bar)",
            bar > barHost
        )
    }

    @Test
    fun `the five items call the very same wiring the header icons called`() {
        val main = activity()
        listOf(
            "val navToSearch: () -> Unit = { screen = Screen.Search }",
            "val navToGuide: () -> Unit = { screen = Screen.Guide }",
            "val navToLibrary: () -> Unit = { screen = Screen.Library }",
            "val navToSettings: () -> Unit = { screen = Screen.Settings }"
        ).forEach { action ->
            assertTrue("the bar must reuse $action", main.contains(action))
        }
        // ...and the TV top bar still goes through the same four, so the two
        // form factors cannot drift onto different destinations.
        listOf(
            "onSearch = navToSearch",
            "onOpenGuide = navToGuide",
            "onOpenLibrary = navToLibrary",
            "onOpenSettings = navToSettings"
        ).forEach { handoff ->
            assertTrue("HomeScreen must be handed $handoff", main.contains(handoff))
        }
        assertTrue(
            "the Live TV item is the guide, exactly as the header icon was",
            bar().contains("LiveTv(\"Live TV\"")
        )
    }

    @Test
    fun `onboarding stands in for Home before the bar is decided`() {
        // The guide is not a Screen, so no rule in [mobileNavSelection] can
        // exclude it. It is drawn because AppRoot returns from the onboarding
        // branch above the bar, and a refactor that moved that branch below the
        // bar would put five destinations under a first-run guide.
        val main = activity()
        val onboarding = main.indexOf("if (!onboardingComplete && screen is Screen.Home)")
        val barDecision = main.indexOf("val mobileNavDestination =")
        assertTrue("the onboarding branch must exist", onboarding >= 0)
        assertTrue("and the bar must be decided after it", barDecision > onboarding)
        assertTrue(
            "with a return, so the shell never reaches the bar during onboarding",
            main.substring(onboarding, barDecision).contains("return")
        )
    }

    @Test
    fun `re-tapping Home takes Home back to the top`() {
        val main = activity()
        assertTrue(
            "tapping Home while on Home must ask it to scroll up rather than navigate",
            main.contains(
                "if (screen is Screen.Home) { MobileHomeTopRequest.request() } else { " +
                    "screen = Screen.Home }"
            )
        )
        val mobile = mobileHome()
        assertTrue(
            "Home must own exactly one list state so the request has something to move",
            mobile.contains("val listState = rememberLazyListState()") &&
                mobile.contains("state = listState")
        )
        assertTrue(
            "and it must consume the request by scrolling to the first item",
            mobile.contains("MobileHomeTopRequest.requests") &&
                mobile.contains("listState.animateScrollToItem(0)")
        )
        assertTrue(
            "the request is one-shot: a re-tap scrolls once, never on every entry",
            mobile.contains("if (MobileHomeTopRequest.consume())") &&
                bar().contains("fun consume(): Boolean")
        )
    }

    // -------------------------------------------------------------------- header

    /**
     * The header's own source, so "the header no longer draws X" is a claim
     * about the header and not about some rail card that shares a word with it.
     */
    private fun mobileHeader(): String {
        val mobile = mobileHome()
        val start = mobile.indexOf("private fun MobileTopBar()")
        assertTrue("the header must exist, with no parameters left to carry a door", start >= 0)
        val end = mobile.indexOf("One billboard page", start)
        assertTrue("the header body must be bounded by the next composable", end > start)
        return mobile.substring(start, end)
    }

    /** The avatar button's own source - the last function in the bar file. */
    private fun profileButton(): String {
        val bar = bar()
        val start = bar.indexOf("private fun MobileProfileButton")
        assertTrue("the bar must draw a profile button", start >= 0)
        return bar.substring(start)
    }

    @Test
    fun `the Home header is the wordmark alone`() {
        val header = mobileHeader()
        assertTrue("the wordmark is still drawn", header.contains("\"KBSTREAM\""))
        listOf("onSwitchProfile", "activeProfile", "clickable(", "PROFILES").forEach { gone ->
            assertTrue(
                "the header must no longer draw $gone: the switcher is the bar's " +
                    "avatar now, and that is reachable from all five top-level screens",
                !header.contains(gone)
            )
        }
        val mobile = mobileHome()
        // Scoped to the HEADER: the Home billboard below it draws its own icon
        // button (the trailer's close mark), and that is not one of the header's
        // doors. What this test protects is that the header itself shrank to one
        // child.
        assertTrue(
            "the header must no longer draw any icon button; that destination " +
                "lives in the bar",
            !header.contains("IconButton(")
        )
        listOf("Icons.Filled.Search", "Icons.Filled.LiveTv", "Icons.Filled.VideoLibrary", "Icons.Filled.Settings").forEach { gone ->
            assertTrue(
                "the header's $gone must be gone; that destination lives in the bar",
                !mobile.contains(gone)
            )
        }
        listOf("onSearch", "onOpenGuide", "onOpenLibrary", "onOpenSettings").forEach { param ->
            assertTrue(
                "mobile Home must not keep an unused $param parameter now that the " +
                    "shell calls it",
                !mobile.contains(param)
            )
        }
        assertTrue(
            "and it must not keep the profile callback either, now that the bar " +
                "owns the door",
            !mobile.contains("onSwitchProfile")
        )
    }

    // ------------------------------------------------------------------- profile

    @Test
    fun `the profile avatar is a door beside the five destinations, never a sixth`() {
        assertEquals(
            "the bar still navigates to exactly the five top-level screens",
            5,
            MobileNavDestination.entries.size
        )
        val bar = bar()
        val button = profileButton()
        assertTrue(
            "the bar takes a profile callback separate from destination selection",
            bar.contains("onSwitchProfile: () -> Unit") &&
                bar.contains("MobileProfileButton(onClick = onSwitchProfile)")
        )
        assertTrue(
            "the five items keep equal shares of what the avatar leaves",
            bar.contains("NavigationBar( modifier = Modifier.weight(1f)")
        )
        assertTrue(
            "the avatar takes a fixed slot of its own - a share would make it a sixth item",
            button.contains(".width(56.dp)") && button.contains(".height(80.dp)")
        )
        assertTrue(
            "it is not a destination: no NavigationBarItem, no selected state, no label",
            !button.contains("NavigationBarItem") && !button.contains("selected")
        )
        assertTrue(
            "it draws the active profile's avatar at the bar's scale",
            button.contains("ProfileAvatarCircleMobile(") &&
                button.contains("size = 30.dp")
        )
    }

    @Test
    fun `the avatar reads the active profile and resolves it like the picker`() {
        val button = profileButton()
        assertTrue(
            "it observes the same flow Home's chip did, so a switch re-renders it " +
                "on every top-level screen at once",
            button.contains("ProfileManager.activeProfile.collectAsStateWithLifecycle()")
        )
        assertTrue(
            "the image resolution is the picker's: a custom avatar first, then the " +
                "uploaded blob",
            button.contains(
                "customAvatarUrl = activeProfile?.customAvatarUrl ?: activeProfile?.avatarData"
            )
        )
        assertTrue(
            "and the circle itself is the picker's composable, not a second drawing",
            source("com/kennyb1201/kbstream/ui/mobile/ProfilePickerScreenMobile.kt")
                .contains("internal fun ProfileAvatarCircleMobile(") &&
                button.contains("ProfileAvatarCircleMobile(")
        )
    }

    @Test
    fun `the shell points the avatar at the picker, and choosing a profile starts on Home`() {
        val main = activity()
        assertTrue(
            "the bar's avatar is handed a callback",
            main.contains("onSwitchProfile = onMobileSwitchProfile")
        )
        assertTrue(
            "that callback opens the same picker Home's chip opened, and `returnTo` " +
                "is what BACK leaves for",
            main.contains("screen = Screen.ProfilePicker(returnTo = screen)")
        )

        // Choosing a profile is not Back. The rule lives in the picker's own
        // onSelect, so the bar's avatar, Home's chip, Settings' Profiles row and
        // the cold-launch picker cannot disagree about it.
        val select = main.substringAfter("is Screen.ProfilePicker -> {")
            .substringBefore("onManage = {")
        assertTrue(
            "every profile switch must start the new profile on Home",
            !select.isEmpty() && select.contains("screen = Screen.Home")
        )
        assertFalse(
            "it must not hand back the screen the picker was opened on - that is " +
                "how a switch made on Search landed on the previous profile's " +
                "Search",
            select.contains("current.returnTo ?: Screen.Home")
        )
        assertTrue(
            "and Home is the cold-launch loading beat, so the splash is re-armed",
            select.contains("splashArmKey += 1")
        )
        assertTrue(
            "TV is untouched: the shell still hands Home the chip's callback",
            main.contains("onSwitchProfile = {") &&
                main.contains("Screen.ProfilePicker(returnTo = Screen.Home)")
        )
    }

    // ------------------------------------------------------------------- styling

    @Test
    fun `the bar wears the app's own plate and accent`() {
        val bar = bar()
        assertTrue(
            "the bar must be composed in the phone theme so it shares the brand type scale",
            bar.contains("MobileTheme {")
        )
        assertTrue("the plate is the brand surface", bar.contains("KBSurface"))
        assertTrue(
            "the selected item is drawn in the accent, icon and label",
            bar.contains("selectedIconColor = KBAccent") &&
                bar.contains("selectedTextColor = KBAccent")
        )
        assertTrue(
            "the unselected items stay on the secondary text tone",
            bar.contains("unselectedIconColor = KBTextLo") &&
                bar.contains("unselectedTextColor = KBTextLo")
        )
        assertTrue(
            "the selected state is more than a tint: it carries its indicator",
            bar.contains("indicatorColor = KBAccent.copy(")
        )
        assertTrue(
            "labels sit under the icons, as a bottom bar is read",
            bar.indexOf("icon = {") in 0 until bar.indexOf("label = {")
        )
        assertTrue(
            "a hairline separates the plate from the content scrolling behind it, " +
                "at the app's one divider weight",
            bar.contains("MobileDivider()")
        )
        assertTrue(
            "the phone's bottom system inset is respected",
            bar.contains("navigationBarsPadding()")
        )
        assertTrue(
            "and every item is a tap target, not a focus surface",
            bar.contains("NavigationBarItem(") && bar.contains("onClick = { onSelect(")
        )
        assertTrue(
            "the bar is a touch widget: it must not import the TV widget layer",
            !bar.contains("import androidx.tv.material3.")
        )
    }

    @Test
    fun `the bar draws all five destinations in the design's order`() {
        val bar = bar()
        listOf("Home", "Search", "Live TV", "Library", "Settings").forEach { label ->
            assertTrue("the bar must offer $label", bar.contains("\"$label\""))
        }
        val order = listOf("Home(", "Search(", "LiveTv(", "Library(", "Settings(")
            .map { entry -> bar.indexOf("$entry\"") }
        assertTrue(
            "Home, Search, Live TV, Library, Settings is the concept's order",
            order.all { it >= 0 } && order == order.sorted()
        )
        assertTrue(
            "the items come from the enum, so a destination cannot be half-added",
            bar.contains("MobileNavDestination.entries.forEach")
        )
    }
}
