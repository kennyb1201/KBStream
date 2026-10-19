package com.kennyb1201.kbstream.ui.mobile

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2's first screen, and the shape every ported screen after it will take:
 *
 *  1. **One branch at the entry point.** `HomeScreen` decides on
 *     `FormFactor.isTv` and hands the same callbacks and ViewModel to
 *     [HomeScreenMobile] for a phone. Everything below that branch is the TV
 *     screen, byte-for-byte as it was.
 *  2. **The mobile variant is touch-first and tv-free.** It is built on the
 *     Phase 1 twins and `MobileTheme`, reads no tv-material3 widget, and pulls
 *     its data from the same ViewModel flows the TV screen uses - so the two
 *     form factors can never show different libraries.
 *
 * Source text is what a JVM test can see here (the module has no
 * compose-ui-test harness); the actual layout is exercised on a device.
 */
class HomeScreenMobileContractTest {

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

    private fun homeScreen(): String =
        source("com/kennyb1201/kbstream/ui/home/HomeScreen.kt")

    private fun mobileHome(): String =
        source("com/kennyb1201/kbstream/ui/mobile/HomeScreenMobile.kt")

    @Test
    fun `home dispatches on the form factor at its entry point`() {
        val home = homeScreen()
        val dispatch = home.indexOf("if (!FormFactor.isTv)")
        val mobileCall = home.indexOf("HomeScreenMobile(")
        val tvHero = home.indexOf("HomeHeroHost(")
        assertTrue(
            "HomeScreen must branch on FormFactor.isTv - that is the one edit a " +
                "TV file is allowed to take in this port",
            dispatch >= 0
        )
        assertTrue("the mobile branch must call HomeScreenMobile", mobileCall > dispatch)
        val callBlock = home.substring(mobileCall)
            .substringBefore("val context = LocalContext.current")
        assertTrue(
            "the mobile branch must hand over the same callbacks and ViewModel",
            callBlock.contains("viewModel = viewModel") &&
                callBlock.contains("onOpenDetailTarget = onOpenDetailTarget")
        )
        listOf(
            "onOpenKBFolder = onOpenKBFolder",
            "onOpenBrowseShortcut = onOpenBrowseShortcut"
        ).forEach { handoff ->
            assertTrue(
                "the mobile branch must hand over $handoff so the phone can open " +
                    "collections and Browse rows",
                callBlock.contains(handoff)
            )
        }
        assertTrue(
            "the branch must return, so the TV body never composes on a phone",
            callBlock.contains("return")
        )
        assertTrue(
            "the TV screen after the branch must still exist (hero and rails untouched)",
            tvHero >= 0 &&
                home.substringAfter("val context = LocalContext.current")
                    .contains("HomeHeroHost(")
        )
    }

    @Test
    fun `the mobile home is built on the touch twins and the mobile theme`() {
        val mobile = mobileHome()
        assertTrue(
            "the screen must apply MobileTheme (material3 over the brand tokens)",
            mobile.contains("MobileTheme {")
        )
        listOf(
            "ui.components.touch.PosterCard",
            "ui.components.touch.LandscapeCard",
            "ui.components.touch.PosterContextMenu",
            "ui.components.touch.KBButton"
        ).forEach { twin ->
            assertTrue("mobile Home must use the touch twin $twin", mobile.contains(twin))
        }
        assertFalse(
            "mobile Home must not import a tv-material3 widget",
            mobile.contains("import androidx.tv.material3.")
        )
        assertFalse(
            "mobile Home must not import the TV card components; the touch twins " +
                "are its whole widget layer",
            mobile.contains("import com.kennyb1201.kbstream.ui.components.PosterCard") ||
                mobile.contains("import com.kennyb1201.kbstream.ui.components.LandscapeCard")
        )
    }

    @Test
    fun `the mobile home reads the same view model and filters the same way`() {
        val mobile = mobileHome()
        listOf(
            "viewModel.rails",
            "viewModel.railsProfileId",
            "viewModel.upNext",
            "viewModel.upcomingSchedule",
            "viewModel.watchedKeys",
            "viewModel.partialWatchedKeys",
            "viewModel.heroMeta",
            "viewModel.markAsWatched(",
            "viewModel.markUnwatched("
        ).forEach { read ->
            assertTrue("mobile Home must read $read", mobile.contains(read))
        }
        assertTrue(
            "hidden titles must drop out of the phone's rails as they do on TV",
            mobile.contains("HiddenTitles.hides(") && mobile.contains("rememberHiddenTitleKeys()")
        )
        assertTrue(
            "a rail built for the previous profile must not paint",
            mobile.contains("railsBuiltForProfile")
        )
        assertTrue(
            "the synced rail-label prefs must be honoured (and re-read on revision)",
            mobile.contains("PrefsPayloadApplier.revision") &&
                mobile.contains("getHomeRailShowCatalogType") &&
                mobile.contains("getHomeRailShowAddonName")
        )
    }

    @Test
    fun `the mobile home keeps the portrait-first home shape`() {
        val mobile = mobileHome()
        assertTrue("Home must be one vertical scroll", mobile.contains("LazyColumn("))
        assertTrue(
            "each rail must be a horizontal carousel",
            mobile.contains("LazyRow(")
        )
        assertTrue(
            "Continue Watching and Upcoming must both be carried over",
            mobile.contains("\"Continue Watching\"") && mobile.contains("\"Upcoming\"")
        )
        assertTrue(
            "the hero must be a full-width 16:9 card",
            mobile.contains("aspectRatio(16f / 9f)")
        )
        assertTrue(
            "a Continue Watching tap must still resume through the shared target",
            mobile.contains("onOpenDetailTarget(") && mobile.contains("StreamsTarget(")
        )
    }

    @Test
    fun `the mobile home renders the arranged entries the tv builder produces`() {
        val mobile = mobileHome()
        assertTrue(
            "the home manager's slot builder must drive the phone's order",
            mobile.contains("KBHomeSlots.buildMergedEntries(")
        )
        listOf(
            "HomeEntry.BuiltinRail",
            "HomeEntry.Collection",
            "HomeEntry.BrowseRail",
            "HomeEntry.AddonRail"
        ).forEach { branch ->
            assertTrue("the entries loop must render $branch", mobile.contains(branch))
        }
        assertTrue(
            "KB collections must open their folder screen",
            mobile.contains("MobileCollectionSection(") && mobile.contains("onOpenFolder(")
        )
        assertTrue(
            "Browse chips must open their discover screen and be removable from Home",
            mobile.contains("MobileBrowseShortcutSection(") &&
                mobile.contains("removeBrowseShortcut")
        )
        assertTrue(
            "landscape cards must follow the synced pref",
            mobile.contains("getHomeLandscapeCards") && mobile.contains("rail.landscapeArt")
        )
        assertTrue(
            "spoiler-free mode must hide episode identities on the phone",
            mobile.contains("SpoilerFree.hidesIdentity(")
        )
        assertTrue(
            "a Continue Watching tap must re-verify its root, bounded like the TV path",
            mobile.contains("verifiedResumeStreamId(") && mobile.contains("withTimeoutOrNull(")
        )
        assertTrue(
            "a Continue Watching long press must offer the TV menu",
            mobile.contains("continueWatchingMenu") &&
                mobile.contains("removeFromContinueWatching(")
        )
        assertTrue(
            "the phone must not raise the TV's long-press overlay for its rails",
            !mobile.contains("ParentalOverlay")
        )
    }

    @Test
    fun `the all-hidden card reaches the same manager through the same handoff`() {
        // HD-P2-5 on a phone: every rail hidden used to leave the hero and
        // nothing else. The card is the phone's way back, and it must use the
        // SAME one-shot request the TV card does - not a second door.
        val mobile = mobileHome()
        assertTrue(
            "the phone must offer the card when the merged order is empty but " +
                "the pieces it is built from are not",
            mobile.contains("val allRailsHidden") &&
                mobile.contains("homeEntries.isEmpty()") &&
                mobile.contains("\"All rails are hidden.\"")
        )
        assertTrue(
            "and the button must be the manager action, not a retry",
            mobile.contains("KBButton(label = \"MANAGE RAILS\", onClick = onManageRails)")
        )
        assertTrue(
            "the mobile branch of HomeScreen must hand onManageRails to the phone",
            homeScreen().contains("onManageRails = onManageRails")
        )
        val addons = source("com/kennyb1201/kbstream/ui/mobile/AddonsScreenMobile.kt")
        assertTrue(
            "and the phone Add-ons screen must consume the request exactly once",
            addons.contains("if (HomeRailManagerRequest.consume()) showCatalogManager = true")
        )
        assertTrue(
            "hosting the phone's own manager, not the TV dialog",
            addons.contains("HomeManagerDialogMobile(")
        )
    }

    @Test
    fun `the mobile home does not edit the tv hero or rails`() {
        // The only HomeScreen edit is the dispatch; the rail-card and hero code
        // must still be the tv-material3 implementation below it.
        val home = homeScreen()
        assertTrue(
            "HomeScreen must still import the tv-material3 Card stack it draws with",
            home.contains("androidx.tv.material3")
        )
        assertTrue(
            "HomeScreen must still derive its rail title the same way",
            home.contains("private fun homeRailTitle(")
        )
        assertFalse(
            "the mobile screen must not be folded into HomeScreen; it lives in its own file",
            home.contains("fun MobileSection(")
        )
    }
}
