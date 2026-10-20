package com.kennyb1201.kbstream.ui.mobile

import com.kennyb1201.kbstream.Screen
import com.kennyb1201.kbstream.ui.detail.StreamsTarget
import java.io.File
import org.junit.Assert.assertEquals
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
 *  3. **The header gave the icons up.** Home's top bar carries branding and
 *     profile identity only.
 *  4. **The bar is the app's**, not a stock Material strip: brand surface,
 *     accent when selected, labels under the icons, the bottom inset reserved.
 *
 * The layout itself (the bar sitting below the screen switch's Box, so no
 * top-level screen's last row lands under it) is source-checked here and needs
 * Kenny's eyes on a phone; there is no device in this workspace.
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

    @Test
    fun `Home's header no longer carries the four icons`() {
        val mobile = mobileHome()
        assertTrue(
            "the header must keep branding and profile identity only",
            mobile.contains("private fun MobileTopBar( onSwitchProfile: () -> Unit )")
        )
        assertTrue(
            "the wordmark and the profile chip are still drawn",
            mobile.contains("\"KBSTREAM\"") &&
                mobile.contains("clickable(onClick = onSwitchProfile)")
        )
        listOf("IconButton", "Icons.Filled.Search", "Icons.Filled.LiveTv", "Icons.Filled.VideoLibrary", "Icons.Filled.Settings").forEach { gone ->
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
            "a hairline separates the plate from the content scrolling behind it",
            bar.contains("HorizontalDivider(")
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
