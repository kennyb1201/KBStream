package com.kennyb1201.kbstream.ui.mobile

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Live TV half of Phase 2's screen rule: one `FormFactor` branch in the TV
 * guide, the phone layout in a new tv-free file, and the same `IptvViewModel`
 * behind both.
 *
 * Pinned here because a JVM test cannot compose either layout (no
 * compose-ui-test harness in this module): the dispatch, the tactile widget
 * layer, the ViewModel reads that carry the playlist and the EPG, and the two
 * data contracts that make the phone list correct - the play hand-off and the
 * bounded EPG window.
 */
class GuideScreenMobileContractTest {

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

    private fun guideScreen(): String =
        source("com/kennyb1201/kbstream/ui/iptv/GuideScreen.kt")

    private fun mobileGuide(): String =
        source("com/kennyb1201/kbstream/ui/mobile/GuideScreenMobile.kt")

    @Test
    fun `the guide dispatches on the form factor at its entry point`() {
        val guide = guideScreen()
        val dispatch = guide.indexOf("if (!FormFactor.isTv)")
        val mobileCall = guide.indexOf("GuideScreenMobile(")
        assertTrue("GuideScreen must branch on FormFactor.isTv", dispatch >= 0)
        assertTrue("the mobile branch must call GuideScreenMobile", mobileCall > dispatch)
        val callBlock = guide.substring(mobileCall)
            .substringBefore("val playlist by viewModel.playlist")
        assertTrue(
            "the branch must hand over the same callbacks and ViewModel",
            callBlock.contains("onBack = onBack") &&
                callBlock.contains("onPlayChannel = onPlayChannel") &&
                callBlock.contains("onPlayCatchup = onPlayCatchup") &&
                callBlock.contains("onOpenSports = onOpenSports") &&
                callBlock.contains("viewModel = viewModel")
        )
        assertTrue(
            "the branch must return, so the D-pad guide never composes on a phone",
            callBlock.contains("return")
        )
        assertTrue(
            "the TV guide must still be the focus-driven one it was",
            guide.substringAfter("val playlist by viewModel.playlist")
                .contains("handleGuideBack")
        )
    }

    @Test
    fun `the mobile guide is built on the touch twins and the mobile theme`() {
        val mobile = mobileGuide()
        assertTrue(
            "the screen must apply MobileTheme",
            mobile.contains("MobileTheme {")
        )
        assertTrue(
            "the form must use the touch fields and buttons",
            mobile.contains("ui.components.touch.KBTextField") &&
                mobile.contains("ui.components.touch.KBButton") &&
                mobile.contains("ui.components.touch.KBPasteChip")
        )
        assertFalse(
            "the mobile guide must not import a tv-material3 widget",
            mobile.contains("import androidx.tv.material3.")
        )
        assertFalse(
            "the mobile guide must not import the TV focus plumbing",
            mobile.contains("import androidx.compose.ui.focus.")
        )

    }

    @Test
    fun `the mobile guide reads the tv guide view model`() {
        val mobile = mobileGuide()
        listOf(
            "viewModel.playlist",
            "viewModel.visibleChannels",
            "viewModel.resolvedGuideChannelIds",
            "viewModel.isLoading",
            "viewModel.isImportingGuide",
            "viewModel.guideImport",
            "viewModel.error",
            "viewModel.guideError",
            "viewModel.onPlaylistUrlChanged",
            "viewModel.onEpgUrlChanged",
            "viewModel.onPlaylistNameChanged",
            "viewModel.load()",
            "viewModel.importGuide()"
        ).forEach { read ->
            assertTrue("mobile Guide must read $read", mobile.contains(read))
        }
    }

    @Test
    fun `the mobile guide keeps the play path and the bounded epg window`() {
        val mobile = mobileGuide()
        assertTrue(
            "a tapped row must tune through the same callback the TV row uses",
            mobile.contains("onPlayChannel?.let")
        )
        assertTrue(
            "EPG rows must be requested from the visible window, not the whole lineup",
            mobile.contains("viewModel.updateGuideChannels(ids)") &&
                mobile.contains("listState.layoutInfo.visibleItemsInfo")
        )
        assertTrue(
            "the rows must distinguish an unresolved row from a resolved empty one",
            mobile.contains("Loading guide…") && mobile.contains("No program data")
        )
        assertTrue(
            "the program on air must draw its own progress and remaining label",
            mobile.contains("formatRemainingLabel(") && mobile.contains("MobileProgramProgress(")
        )
        assertTrue(
            "the guide must still be able to go back without a new callback",
            mobile.contains("BackHandler")
        )
    }

    @Test
    fun `the mobile guide publishes the zapping lineup the player reads`() {
        val mobile = mobileGuide()
        assertTrue(
            "a live session needs a published lineup for CH+/CH- and the in-player guide",
            mobile.contains("LiveChannelZapRegistry.setGroups(") &&
                mobile.contains("LiveChannelZapRegistry.ZapChannel(") &&
                mobile.contains("LiveChannelZapRegistry.ZapGroup(")
        )
        assertTrue(
            "the published channels must carry the guide match the EPG queries key off",
            mobile.contains("epgChannelId = item.epgChannel?.id") &&
                mobile.contains("epgUrls = guideSourceUrls")
        )
        assertTrue(
            "every configured guide source must be published, primary first",
            mobile.contains("viewModel.extraEpgUrls")
        )
        assertTrue(
            "the phone must publish its guide groups, with the group on screen selected",
            mobile.contains("LiveChannelZapRegistry.ZapGroup(") &&
                mobile.contains("selected = currentGroup") &&
                mobile.contains("guideGroupChannels(")
        )
        assertFalse(
            "search hits are a shortcut and must never become the zapping lineup",
            mobile.contains("channels = filtered.map")
        )
    }

    @Test
    fun `the guide is finished - config, groups, program search and reminders`() {
        val mobile = mobileGuide()

        // Extra sources, the last config gap in the setup form.
        assertTrue(
            "setup must take extra playlist and EPG sources",
            mobile.contains("onExtraPlaylistUrlsChanged = viewModel::onExtraPlaylistUrlsChanged") &&
                mobile.contains("onExtraEpgUrlsChanged = viewModel::onExtraEpgUrlsChanged") &&
                mobile.contains("EXTRA PLAYLISTS (OPTIONAL)") &&
                mobile.contains("EXTRA EPG SOURCES (OPTIONAL)")
        )

        // Hidden groups: hide from the sheet, restore from the manager.
        assertTrue(
            "a group must be hideable from the channel sheet",
            mobile.contains("Hide group: ") && mobile.contains("onToggleGroup = {")
        )
        assertTrue(
            "hidden groups must be restorable, and the set synced like the TV's",
            mobile.contains("MobileGroupsDialog(") &&
                mobile.contains("putStringSet(\"hidden_groups\"") &&
                mobile.contains("setHiddenGroups(") &&
                mobile.contains("onShowAll = { setHiddenGroups(emptySet()) }")
        )

        // EPG program search, not just channel names.
        assertTrue(
            "a query must search the EPG as well as channel names",
            mobile.contains("viewModel.searchPrograms(") &&
                mobile.contains("viewModel.programSearchResults") &&
                mobile.contains("MobileProgramHitRow(")
        )
        assertTrue(
            "program hits must be limited to unhidden channels",
            mobile.contains("item.epgChannel?.id") &&
                mobile.contains("byEpgId[row.channelId]")
        )

        // Reminders: stored, armed, banner, and notification taps.
        assertTrue(
            "a reminder must be written to the shared store and armed",
            mobile.contains("IptvReminderStore.add(") &&
                mobile.contains("ReminderWorker.arm(") &&
                mobile.contains("ReminderWorker.cancel(")
        )
        assertTrue(
            "the in-guide banner must offer WATCH",
            mobile.contains("MobileReminderBanner(") &&
                mobile.contains("onWatch = {")
        )
        assertTrue(
            "a reminder notification's tap must tune through the guide",
            mobile.contains("PendingChannelTune.consume()")
        )

        // The EPG window must survive program rows in the same list.
        assertTrue(
            "EPG windowing must map by row key, not by index",
            mobile.contains("(info.key as? String)") &&
                !mobile.contains("latestRows.getOrNull(info.index)")
        )
    }

    @Test
    fun `long press offers the tv channel actions, and catch-up plays through`() {
        val mobile = mobileGuide()
        assertTrue(
            "a long press must open the channel sheet",
            mobile.contains("onLongClick = { actionsChannel = item }") &&
                mobile.contains("combinedClickable(")
        )
        assertTrue(
            "favourite must be written to the tv guide's own store and synced",
            mobile.contains("putStringSet(\"favorites\"") &&
                mobile.contains("SupabaseSync.enqueuePrefs(") &&
                mobile.contains("PrefsPayloadBuilder.KEY_IPTV")
        )
        assertTrue(
            "hiding a channel must go through the view model",
            mobile.contains("viewModel.hideChannel(item.channel.id)")
        )
        assertTrue(
            "hiding must have an undo on the phone, not only on TV",
            mobile.contains("MobileHiddenChannelsDialog(") &&
                mobile.contains("viewModel.unhideChannel(") &&
                mobile.contains("viewModel.unhideAllChannels(")
        )
        assertTrue(
            "catch-up must be offered only when the provider has it and the route can play it",
            mobile.contains("onPlayCatchup != null") &&
                mobile.contains("item.channel.catchupSource != null") &&
                mobile.contains("onPlayCatchup?.invoke(item, program)")
        )
        assertTrue(
            "the catch-up list must be loaded for the channel that was long-pressed",
            mobile.contains("viewModel.loadCatchupPrograms(item.channel)")
        )
    }

    @Test
    fun `the mobile header's view toggle is an icon that names the view it is in`() {
        val mobile = mobileGuide()
        val header = mobile
            .substringAfter("private fun MobileGuideTopBar(")
            .substringBefore("private fun MobileGuideChannelRow(")
        val toggle = header
            .substringAfter("IconButton(onClick = onToggleView)")
            .substringBefore("if (onOpenSports != null)")
        assertTrue(
            "the toggle must be an IconButton holding an Icon",
            header.contains("IconButton(onClick = onToggleView)") &&
                toggle.contains("tint = KBAccent")
        )
        assertTrue(
            "the glyph must show the current view - grid while the grid is up, " +
                "list while the list is, as on TV",
            toggle.contains("imageVector = if (guideView == GuideViewMode.GRID)") &&
                toggle.contains("Icons.Filled.GridView") &&
                toggle.contains("Icons.AutoMirrored.Filled.List") &&
                toggle.indexOf("Icons.Filled.GridView") <
                toggle.indexOf("Icons.AutoMirrored.Filled.List")
        )
        assertTrue(
            "an icon-only button is silent, so it must be named for TalkBack",
            toggle.contains("contentDescription = if (guideView == GuideViewMode.GRID)") &&
                toggle.contains("\"Grid view\"") &&
                toggle.contains("\"List view\"")
        )
        assertTrue(
            "no word button may survive in the header, and the old reasoning with it",
            !header.contains("TextButton(") &&
                !header.contains("\"GRID\"") &&
                !header.contains("\"LIST\"") &&
                !mobile.contains("a word instead of an icon")
        )
        assertTrue(
            "the toggle must stay in the row's own order: after the groups button, " +
                "before sports",
            header.indexOf("contentDescription = \"Groups\"") <
                header.indexOf("IconButton(onClick = onToggleView)") &&
                header.indexOf("IconButton(onClick = onToggleView)") <
                header.indexOf("contentDescription = \"Sports hub\"")
        )
        assertTrue(
            "mobile has no D-pad here, so the toggle takes no focus requester",
            !header.contains("focusRequester")
        )
    }

    @Test
    fun `the group chips are list-only, and the grid keeps the groups drawer`() {
        val mobile = mobileGuide()

        // T1: one predicate decides both surfaces. The chips' gate is the
        // negation of the grid's own branch, so a chip row and the grid can
        // never be on screen together (F1a).
        assertTrue(
            "the grid's branch must be the predicate the chips' gate negates",
            mobile.contains("val showGrid = guideShowsGrid(guideView, query)") &&
                mobile.contains("showGrid -> {") &&
                mobile.contains("if (guideShowsGroupChips(guideView, query)) {")
        )
        assertEquals(
            "exactly one chip row, and it sits behind that gate - grid view " +
                "must not have a second one",
            1,
            Regex("items\\(groups, key = \\{ \"group:\" \\+ it \\}\\)").findAll(mobile).count()
        )
        val gate = mobile.substringAfter("if (guideShowsGroupChips(guideView, query)) {")
            .substringBefore("if (guideImportLabel.isNotBlank()) {")
        assertTrue(
            "the gate must hold the chip row and nothing else",
            gate.contains("items(groups, key = { \"group:\" + it })") &&
                gate.contains("MobileFilterChip(") &&
                gate.contains("selected = label == currentGroup")
        )
        // F1c: the freed height goes to the grid - no spacer, no reserved gap.
        assertFalse(
            "nothing may take the chips' place in grid view",
            gate.contains("Spacer(") || gate.contains("height(")
        )

        // T2: the drawer stays the grid's group switcher (F2a), and it writes
        // the same `selectedGroup` the chips write (F2b) - one state, so the
        // two controls cannot disagree about which group is up.
        assertTrue(
            "the hamburger must still open the drawer in grid view",
            mobile.contains("onOpenGroups = if (guideView == GuideViewMode.GRID) {") &&
                mobile.contains("showGroupDrawer = true")
        )
        val drawer = mobile.substringAfter("items(groups, key = { \"drawer:\" + it })")
            .substringBefore("GuideGridMobileView(")
        assertTrue(
            "a drawer row must select its group and close the drawer",
            drawer.contains("selectedGroup = label") &&
                drawer.contains("showGroupDrawer = false")
        )
        assertTrue(
            "the drawer must mark the group the chips mark",
            drawer.contains("val selected = label == currentGroup")
        )
        assertTrue(
            "the grid must draw the selection's members, so a drawer pick re-filters it",
            mobile.contains("GuideGridMobileView(") &&
                mobile.contains("items = filtered,") &&
                mobile.contains("initialValue = groupChannels,")
        )

        // T3: the lines under the chips are not group chips and are not gated -
        // they render in both views, unchanged.
        val gateAt = mobile.indexOf("if (guideShowsGroupChips(guideView, query)) {")
        listOf(
            "if (guideImportLabel.isNotBlank()) {",
            "if (guideStatus.lastGoodLabel().isNotBlank()) {",
            "if (guideStatus.failing) {",
            "MobileGuideErrorLine(message)"
        ).forEach { line ->
            val at = mobile.indexOf(line)
            assertTrue("$line must still render", at > 0)
            assertTrue("$line must not be behind the chips' gate", at > gateAt)
        }
    }

    @Test
    fun `the mobile guide offers the tv group chips`() {
        val mobile = mobileGuide()
        assertTrue(
            "the chip row must come from the shared group rules",
            mobile.contains("guideGroupLabels(")
        )
        assertTrue(
            "a chip must select its group",
            mobile.contains("onClick = { selectedGroup = label }")
        )
        assertTrue(
            "hidden groups and the per-profile prefs must be honoured",
            mobile.contains("hidden_groups") && mobile.contains("hiddenGroups")
        )
        assertTrue(
            "the list must render the selected group's members",
            mobile.contains("guideGroupChannels(") &&
                mobile.contains("val groupChannels = remember(")
        )
        assertTrue(
            "the last group must be restored and persisted, as on TV",
            mobile.contains("putString(\"last_group\"") &&
                mobile.contains("getString(\"last_group\"")
        )
        assertTrue(
            "tuning a channel must feed the Recent queue",
            mobile.contains("nextRecentKeys(") &&
                mobile.contains("putStringSet(\"recent_channels\"")
        )
    }
}
