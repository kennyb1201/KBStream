package com.kennyb1201.kbstream.ui.mobile

import com.kennyb1201.kbstream.data.kb.KBHomeOrderPrefs
import java.io.File
import org.junit.Assert.assertEquals
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
    fun `the Upcoming rail obeys the hide-upcoming pref`() {
        val mobile = mobileHome()
        assertTrue(
            "the phone's own toggle label is 'Hide the Upcoming rail'",
            mobile.contains("AppPreferences.getHomeRailHideUpcoming(")
        )
        assertTrue(
            "the row's visibility must be the pref AND the schedule",
            mobile.contains("val upcomingVisible = upcoming.isNotEmpty() && !hideUpcomingRail")
        )
        assertTrue(
            "the row must disappear from the built-ins the order builder sees",
            mobile.contains("if (upcomingVisible) add(KBHomeOrderPrefs.BUILTIN_UPCOMING_SCHEDULE)")
        )
        assertTrue(
            "and the screen must not draw the item it just dropped",
            mobile.contains("if (upcomingVisible) {") &&
                mobile.contains("item(key = \"upcoming\", contentType = \"section\")")
        )
    }

    @Test
    fun `Continue Watching and Upcoming cards carry the tv treatment`() {
        val mobile = mobileHome()
        // The state chip, the time-left chip and the shared resume bar: the
        // three things the phone card used to leave UNDER the artwork.
        listOf(
            "displayBadge",
            "\"SERIES FINALE\"",
            "ep. left",
            "formatTimeLeft(",
            "KBProgressBar("
        ).forEach { marker ->
            assertTrue("the Continue Watching card must draw $marker", mobile.contains(marker))
        }
        assertTrue(
            "the state chip is spelled the way the TV rail spells it",
            mobile.contains("\"NEXT UP\"") && mobile.contains("UpNextBadge.NEW_SEASON")
        )
        assertTrue(
            "and filled with the TV rail's per-state colour, not one accent for all",
            mobile.contains("val badgeColor = when {") &&
                mobile.contains("item.isSeriesFinale -> KBDanger") &&
                mobile.contains("UpNextBadge.NEXT_UP -> KBSteel")
        )
        // Upcoming: the air-date chip on top, the calendar date leading the
        // bottom block, and the S/E line the TV card prints - from the TV's own
        // rule, so the two rails cannot number an episode two ways ("S2 E8",
        // not "S02 · E08").
        listOf(
            "\"NEW SEASON\"",
            "episode.airDateFull",
            "tvUpNextEpisodeLabel(episode.season, episode.episode)"
        ).forEach { marker ->
            assertTrue("the Upcoming card must draw $marker", mobile.contains(marker))
        }
        assertFalse(
            "and it must not keep a padded pair of its own",
            mobile.contains("S%02d · E%02d")
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
            "landscape cards must follow the Home-or-global synced pref, or the " +
                "phone stayed portrait with 'Landscape Posters Everywhere' on",
            mobile.contains("AppPreferences.homeLandscapeActive(") &&
                mobile.contains("rail.landscapeArt")
        )
        assertTrue(
            "and index that map by its real 'type:id' key - a raw id lookup found " +
                "nothing, which is why the phone drew no clearlogo",
            mobile.contains("rail.landscapeArt[ meta.landscapeArtKey() ]")
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

    /**
     * A KB folder can set `hideTitle` in the manifest, and the TV rail has
     * always obeyed it: the scrim and the label both go, so the artwork stands
     * alone. The phone drew the label unconditionally, so an imported
     * collection whose folders hide titles still printed every name under the
     * cover - the phone and the television disagreed about one folder.
     */
    @Test
    fun `a folder that hides its title hides it on the phone too`() {
        val mobile = mobileHome()
        val card = mobile.substringAfter("private fun CollectionFolderCard(")
            .substringBefore("private fun MobileBrowseShortcutSection(")
        val gate = card.indexOf("if (!folder.hideTitle) {")
        assertTrue(
            "the phone's folder card must branch on the manifest's hideTitle flag",
            gate >= 0
        )
        assertTrue(
            "and the folder title must be drawn inside that branch, not above it",
            card.indexOf("text = folder.title,") > gate
        )
        assertTrue(
            "the scrim must be dropped with the label - a bare gradient with no " +
                "name under it just dims the artwork",
            card.indexOf("Brush.verticalGradient(") > gate
        )
        // Parity: the TV tile keeps the same rule, so the two surfaces cannot
        // drift apart again.
        val tvTile = source("com/kennyb1201/kbstream/ui/kb/KBHomeSlots.kt")
        assertTrue(
            "the TV folder tile must still gate its label on hideTitle",
            tvTile.contains("if (!folder.hideTitle) {")
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

    // ------------------------------------------------------- rail renames

    /**
     * A rail renamed in the home manager reached both builders and the TV's
     * Home while the phone went on drawing the original name, so the rule is
     * asserted as behaviour: the override wins, and a cleared name restores
     * the default.
     */
    @Test
    fun `a rename decides a built-in row's phone title`() {
        assertEquals(
            "a rename from the manager must win on a phone",
            "What I'm Watching",
            mobileBuiltinRailTitle(
                renames = mapOf(
                    KBHomeOrderPrefs.BUILTIN_CONTINUE_WATCHING to "What I'm Watching"
                ),
                key = KBHomeOrderPrefs.BUILTIN_CONTINUE_WATCHING,
                fallback = "Continue Watching"
            )
        )
        assertEquals(
            "and on the Upcoming row",
            "Next Up",
            mobileBuiltinRailTitle(
                renames = mapOf(KBHomeOrderPrefs.BUILTIN_UPCOMING_SCHEDULE to "Next Up"),
                key = KBHomeOrderPrefs.BUILTIN_UPCOMING_SCHEDULE,
                fallback = "Upcoming"
            )
        )
        assertEquals(
            "clearing a rename restores the key's default title",
            "Continue Watching",
            mobileBuiltinRailTitle(
                renames = mapOf(KBHomeOrderPrefs.BUILTIN_CONTINUE_WATCHING to "  "),
                key = KBHomeOrderPrefs.BUILTIN_CONTINUE_WATCHING,
                fallback = "What I'm Watching"
            )
        )
        assertEquals(
            "an unrenamed rail keeps the key's default, not the caller's fallback",
            "Upcoming",
            mobileBuiltinRailTitle(
                renames = emptyMap(),
                key = KBHomeOrderPrefs.BUILTIN_UPCOMING_SCHEDULE,
                fallback = "Something Else"
            )
        )
        assertEquals(
            "a key the manager has no default for keeps the caller's title",
            "Watchlist",
            mobileBuiltinRailTitle(
                renames = emptyMap(),
                key = "not-a-builtin",
                fallback = "Watchlist"
            )
        )
    }

    @Test
    fun `the phone takes its built-in titles and add-on names from the arrangement`() {
        val mobile = mobileHome()
        assertTrue(
            "the built-in rows must read the home manager's renames - the same " +
                "arrangement the merge is built from, so a rename that arrives by " +
                "sync re-titles the row",
            mobile.contains("renames = kbState.arrangement.renames")
        )
        assertTrue(
            "and the resolved titles must be the ones the sections draw",
            mobile.contains("title = continueWatchingTitle") &&
                mobile.contains("title = upcomingTitle")
        )
        // The literals that were the bug: a section titled by a constant cannot
        // show a rename, however correct the arrangement is.
        listOf("Continue Watching", "Upcoming").forEach { literal ->
            assertFalse(
                "no built-in section may be titled by the \"$literal\" literal",
                mobile.contains("title = \"$literal\"")
            )
        }
        assertTrue(
            "an add-on rail must draw the manager's name over the rail's own, " +
                "exactly as the TV rail does (a Top Today row, a built catalog)",
            mobile.contains("catalogName = entry.titleOverride ?: rail.catalogName")
        )
        assertTrue(
            "and the name must still be composed through the shared header rule",
            mobile.contains("title = mobileRailTitle(")
        )
    }

    /**
     * The Upcoming heading is a door on both form factors: the TV rail's header
     * opens Library's Calendar pane, and the phone's row is the same schedule
     * behind the same word. The heading itself stays a heading on the phone -
     * no chip or pill wraps it - so the word is the whole target.
     */
    @Test
    fun `the Upcoming heading opens the calendar on the phone`() {
        val mobile = mobileHome()
        val home = homeScreen()

        assertTrue(
            "the shell's calendar hand-off must reach the phone screen through " +
                "HomeScreen's dispatch, as every other callback does",
            home.contains("onOpenCalendar = onOpenCalendar, viewModel = viewModel")
        )
        assertTrue(
            "the phone's Upcoming section must wire its heading to that hand-off",
            mobile.contains("title = upcomingTitle, onClick = onOpenCalendar")
        )
        // The plain-word contract: the header is made pressable by a clickable
        // modifier on the heading itself, not by a chip, pill or button twin.
        assertTrue(
            "a pressable section heading is the heading text with a clickable " +
                "modifier on it",
            mobile.contains(
                ".padding(horizontal = MobileSpacing.edge) .clickable(onClick = onClick)"
            )
        )
        assertFalse(
            "the Upcoming heading must not gain a chip or button around it",
            mobile.contains("MobileSectionButton") ||
                mobile.contains("title = upcomingTitle, trailing")
        )
        assertTrue(
            "every other section keeps a plain heading (the CW row is the only " +
                "plain-title call left in the built-in pair)",
            mobile.contains("MobileSection(title = continueWatchingTitle) {")
        )
    }

    /**
     * A rail's pagination is the television's rule, not a phone rule: an
     * add-on catalog is addressed by `skip`, one page at a time, and Home asks
     * for the next page as the viewer nears the end of the row.
     *
     * The phone drew `rail.items` and never asked, so a catalog stopped dead at
     * whatever the add-on returned for `skip=0` - reported as "on home rails on
     * mobile theres no pagination and it only shows a certain amount of items".
     * The trigger, not the fetch, was missing: `loadMoreForRail` is the same
     * call the TV rail makes, and it is self-guarded.
     */
    @Test
    fun `an add-on rail asks for the next page on the phone too`() {
        val mobile = mobileHome()
        assertTrue(
            "the phone's rail row must use the shared scroll sentinel",
            mobile.contains("ui.components.InfiniteScrollEffect")
        )

        val row = mobile.substringAfter("is HomeEntry.AddonRail -> {")
            .substringBefore("Every rail the loader produced has been hidden")

        assertTrue(
            "the rail row must own a scroll state the sentinel can watch",
            row.contains("val railRowState = rememberLazyListState()")
        )
        assertTrue(
            "the sentinel must be driven by the rail's own LazyRow state",
            row.contains("state = railRowState,")
        )
        assertTrue(
            "the sentinel must read the rail's live item count, so every append " +
                "re-arms the check",
            row.contains("itemCount = rail.items.size,")
        )
        assertTrue(
            "the next page must be the ViewModel's own rail paging call",
            row.contains("viewModel.loadMoreForRail(railKey)")
        )
        assertTrue(
            "the rail's key must come from the one spelling the ViewModel keys " +
                "its pagination with (catalogRailKey), or the page lands on a " +
                "rail the phone is not drawing",
            row.contains("val railKey = catalogRailKey(")
        )
        assertTrue(
            "the television rail must keep the same trigger - this is parity, " +
                "not a phone-only paging rule",
            homeScreen().contains("InfiniteRailPageHandler(")
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

    /**
     * A Continue Watching long press is the TV card's menu, and every row has
     * to do what its label says. The row that was wrong was "Go to Details":
     * it opened the episode's PLAY target, so Detail auto-played the file
     * instead of showing the page - and because the shared menu derives "Play
     * Manually" / "Play from Beginning" from the details row, both of those
     * resumed it too.
     */
    @Test
    fun `a Continue Watching long press offers the tv rows and each does its own thing`() {
        val mobile = mobileHome()
        val menu = mobile.substringAfter("continueWatchingMenu?.let { item ->")
            .substringBefore("// Raised from a long press on a rail card")

        listOf("Go to Details", "Play Manually", "Play from Beginning")
            .forEach { label ->
                assertTrue(
                    "the Continue Watching menu must offer \"$label\"",
                    menu.contains("label = \"$label\"")
                )
            }

        val detailsRow = menu.substringAfter("label = \"Go to Details\"")
            .substringBefore("label = \"Play Manually\"")
        assertTrue(
            "\"Go to Details\" must ask for the page, not the episode's target",
            detailsRow.contains("mode = UpNextOpen.DETAILS")
        )
        val manualRow = menu.substringAfter("label = \"Play Manually\"")
            .substringBefore("label = \"Play from Beginning\"")
        assertTrue(
            "\"Play Manually\" must open the streams picker",
            manualRow.contains("mode = UpNextOpen.STREAMS")
        )

        assertTrue(
            "the shell must hand the phone screen the streams route - the row " +
                "has nowhere to go without it",
            homeScreen().contains("onOpenStreams = onOpenStreams") &&
                mobile.contains("onOpenStreams: (MetaPreview, StreamsTarget, String?) -> Unit")
        )
        assertTrue(
            "\"Go to Details\" must reach the Details route with no play target",
            mobile.contains("UpNextOpen.DETAILS -> onOpenDetails(detail)")
        )
        assertTrue(
            "\"Play Manually\" must reach the streams route",
            mobile.contains("UpNextOpen.STREAMS -> onOpenStreams(detail, target, item.poster)")
        )
        assertTrue(
            "\"Play from Beginning\" must start at 0 rather than resume",
            mobile.contains("val fromStart = mode == UpNextOpen.FROM_BEGINNING") &&
                mobile.contains(
                    "resumePositionMs = if (fromStart) 0L else item.startPositionMs"
                )
        )
        assertFalse(
            "the pre-fix route - every row running the resume opener - must be gone",
            menu.contains("onOpen = onOpenDetailTarget")
        )
    }
}
