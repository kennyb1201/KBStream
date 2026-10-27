package com.kennyb1201.kbstream.ui.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The last two Phase 2 gaps, pinned at the source level.
 *
 * A JVM test cannot compose either layout, so what is checked here is the
 * contract the spec asked for:
 *
 *  - both call sites in [MainActivity] take the phone layout when
 *    `!FormFactor.isTv`, and the TV composable is still on the else path;
 *  - the two TV files are untouched - no `FormFactor` branch, no `ui/mobile`
 *    import, nothing from the touch layer;
 *  - the phone onboarding is ONE vertical scroll (the TV's two-column `Row` is
 *    what squeezed the source rows off a phone screen) with the touch text
 *    field, which is the keyboard fix;
 *  - the phone catalog grid is a finger-scrolled grid of touch cards on the
 *    same Home ViewModel, hidden-title filter, pager footer and poster menu.
 */
class OnboardingAndCatalogGridMobileContractTest {

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

    private fun mainActivity(): String =
        source("com/kennyb1201/kbstream/MainActivity.kt")

    private fun onboardingMobile(): String =
        source("com/kennyb1201/kbstream/ui/mobile/OnboardingScreenMobile.kt")

    private fun catalogGridMobile(): String =
        source("com/kennyb1201/kbstream/ui/mobile/CatalogGridScreenMobile.kt")

    private fun onboardingTv(): String =
        source("com/kennyb1201/kbstream/ui/onboarding/OnboardingScreen.kt")

    private fun catalogGridTv(): String =
        source("com/kennyb1201/kbstream/ui/home/CatalogGridScreen.kt")

    /** Every mobile file carries the same three import rules. */
    private fun assertTouchScreen(text: String, screen: String) {
        assertTrue("$screen must run on MobileTheme", text.contains("MobileTheme {"))
        assertFalse(
            "$screen must not use tv-material3",
            text.contains("androidx.tv.material3")
        )
        assertFalse(
            "$screen must not ask for focus",
            text.contains("focusRequester") || text.contains("focusProperties")
        )
    }

    private fun assertOrder(text: String, screen: String, vararg markers: String) {
        var previous = -1
        markers.forEach { marker ->
            val at = text.indexOf(marker)
            assertTrue("$screen is missing: $marker", at >= 0)
            assertTrue(
                "$screen has $marker out of order (after ${markers.getOrNull(0)})",
                at > previous
            )
            previous = at
        }
    }

    @Test
    fun `onboarding call site branches on form factor`() {
        val text = mainActivity()

        // The gate itself is unchanged: onboarding still stands in for Home
        // until it is finished.
        val gate = text.indexOf("if (!onboardingComplete && screen is Screen.Home)")
        assertTrue("onboarding gate missing", gate >= 0)

        val branch = text.indexOf("!FormFactor.isTv", gate)
        val mobile = text.indexOf("OnboardingScreenMobile(", gate)
        val tv = text.indexOf("OnboardingScreen(", gate)

        assertTrue("onboarding call site does not branch on !FormFactor.isTv", branch > gate)
        assertTrue("phone onboarding is not called", mobile > branch)
        assertTrue("TV onboarding is not on the else path", tv > mobile)

        // Both halves finish the same way, through the one completion flag.
        val block = text.substring(gate, tv)
        assertTrue(
            "the phone branch must complete through OnboardingPrefs",
            block.contains("OnboardingPrefs.setComplete(context, true)")
        )
        assertTrue(
            "the phone branch must land on Home",
            block.contains("screen = Screen.Home")
        )
    }

    @Test
    fun `catalog grid call site branches on form factor`() {
        val text = mainActivity()

        val route = text.indexOf("is Screen.CatalogGrid ->")
        assertTrue("CatalogGrid route missing", route >= 0)

        val branch = text.indexOf("!FormFactor.isTv", route)
        val mobile = text.indexOf("CatalogGridScreenMobile(", route)
        val tv = text.indexOf("CatalogGridScreen(", route)

        assertTrue("grid call site does not branch on !FormFactor.isTv", branch > route)
        assertTrue("phone catalog grid is not called", mobile > branch)
        assertTrue("TV catalog grid is not on the else path", tv > mobile)

        // Detail still returns into the grid's own route, whichever grid opened.
        val block = text.substring(route, tv)
        assertTrue(
            "the grid must still open Detail with returnTo = current",
            block.contains("returnTo = current")
        )
        assertTrue(
            "the grid must still go back through stableBackDestination",
            block.contains("stableBackDestination(current.returnTo)")
        )
    }

    @Test
    fun `TV onboarding and catalog grid are untouched`() {
        listOf(
            "OnboardingScreen.kt" to onboardingTv(),
            "CatalogGridScreen.kt" to catalogGridTv()
        ).forEach { (name, text) ->
            assertFalse(
                "$name must keep its zero-diff contract: no FormFactor branch",
                text.contains("FormFactor")
            )
            assertFalse(
                "$name must not import the phone layout",
                text.contains("ui.mobile")
            )
            assertFalse(
                "$name must not reach into the touch layer",
                text.contains("ui.components.touch")
            )
        }
    }

    @Test
    fun `phone onboarding is one vertical scroll with touch fields`() {
        val text = onboardingMobile()
        assertTouchScreen(text, "OnboardingScreenMobile")

        // THE keyboard fix: the touch twin, not the TV D-pad field.
        assertTrue(
            "the phone onboarding must use the touch text field",
            text.contains("import com.kennyb1201.kbstream.ui.components.touch.KBTextField")
        )
        assertFalse(
            "the phone onboarding must not use the TV text field",
            text.contains("import com.kennyb1201.kbstream.ui.components.KBTextField")
        )
        assertTrue(text.contains("KBTextField( value = email"))
        assertTrue(text.contains("KBTextField( value = password"))
        assertTrue(
            "the password field must stay masked",
            text.contains("PasswordVisualTransformation()")
        )
        assertTrue(
            "the email field must ask for the email keyboard",
            text.contains("keyboardType = KeyboardType.Email")
        )

        // THE layout fix: one scrolling column, not the TV's fixed-width panes.
        assertTrue(
            "the phone onboarding must scroll vertically",
            text.contains("verticalScroll(rememberScrollState())")
        )
        assertFalse(
            "the TV pane width must not come across",
            text.contains("392.dp")
        )

        // Every section of the TV page, stacked in reading order.
        assertOrder(
            text,
            "OnboardingScreenMobile",
            "OnboardingAccountPanelMobile(",
            "\"Add Add-ons\"",
            "\"Live TV\"",
            "\"Connect Simkl\"",
            "onClick = onFinish",
            "You can also skip everything and browse now."
        )
        assertTrue(
            "START BROWSING must be the accent CTA",
            text.contains("START BROWSING") && text.contains("containerColor = KBAccent")
        )

        // Same callbacks, same account backend as the TV page and Settings.
        listOf("onOpenAddons", "onOpenSimkl", "onOpenGuide", "onFinish").forEach {
            assertTrue("phone onboarding dropped $it", text.contains("$it"))
        }
        assertTrue(text.contains("SupabaseSync.signIn(context, email, password)"))
        assertTrue(text.contains("SupabaseSync.signUp(context, email, password)"))
        assertTrue(
            "signing in must still be reported as a signed-in strip",
            text.contains("SIGNED IN — SYNC IS ON")
        )
        assertTrue(
            "the 6-character rule must match the TV form",
            text.contains("password.length >= 6")
        )
    }

    @Test
    fun `phone onboarding hands off to the mobile sub-flows`() {
        val text = mainActivity()
        val gate = text.indexOf("if (!onboardingComplete && screen is Screen.Home)")
        val mobile = text.indexOf("OnboardingScreenMobile(", gate)
        val tv = text.indexOf("OnboardingScreen(", gate)
        val block = text.substring(mobile, tv)

        // The three hand-offs the spec named: they already have phone versions,
        // so the phone onboarding routes into them rather than nowhere.
        assertTrue(block.contains("screen = Screen.Addons(returnTo = Screen.Home)"))
        assertTrue(block.contains("screen = Screen.Simkl(returnTo = Screen.Home)"))
        assertTrue(block.contains("screen = Screen.Guide"))
    }

    @Test
    fun `phone catalog grid is a touch grid on the same view model`() {
        val text = catalogGridMobile()
        assertTouchScreen(text, "CatalogGridScreenMobile")

        // Portrait is the phone's first shape (docs/mobile_portrait.md): the
        // column count is derived from the width the grid has, so a 360dp phone
        // draws three posters and a turned phone draws more. The old Adaptive
        // rule measured the viewer's poster width and produced two oversized
        // columns in portrait (PortraitBrowseContractTest pins the arithmetic).
        assertTrue(
            "the grid must be a finger-scrolled lazy grid whose columns come " +
                "from the available width",
            text.contains("LazyVerticalGrid(") &&
                text.contains("rememberMobileGridColumns(") &&
                text.contains("GridCells.Fixed(columns)")
        )
        assertTrue(
            "the grid must draw the touch tile that follows the landscape switch " +
                "and resolves the same backdrop + clearlogo the rails do",
            text.contains(
                "import com.kennyb1201.kbstream.ui.components.touch.TouchGlobalPosterTile"
            )
        )
        assertTrue(
            "the grid must keep the rail's landscape shape",
            text.contains("rememberHomeLandscape()")
        )

        // Same ViewModel and data path as the TV grid.
        assertTrue(text.contains("viewModel: HomeViewModel = viewModel()"))
        assertTrue(
            "the grid reopens its rail from the route's whole identity: the name " +
                "pair alone cannot tell two same-named rails apart (grid S2)",
            text.contains("viewModel.openCatalogInGrid(title, addonName, catalogId, type)")
        )
        assertTrue(text.contains("viewModel.closeCatalogGrid()"))
        assertTrue(text.contains("viewModel.catalogGridHeader"))
        assertTrue(text.contains("viewModel.catalogGridPaging"))
        assertTrue(
            "a title repeated across pages must not produce a duplicate key",
            text.contains("key = { index ->") && text.contains("lazyItems.peek(index)")
        )

        // The behaviours that must not be lost in the port.
        assertTrue(text.contains("HiddenTitles.hides("))
        assertTrue(text.contains("lazyItems.retry()"))
        assertTrue(text.contains("viewModel.watchedKey(meta.id, meta.type)"))
        assertTrue(text.contains("viewModel.markAsWatched(target)"))
        assertTrue(text.contains("viewModel.markUnwatched(target)"))
        assertTrue(text.contains("PosterContextMenu("))
        assertTrue(
            "the tile menu must open Detail through the caller",
            text.contains("onItemClick(target)")
        )
    }
}
