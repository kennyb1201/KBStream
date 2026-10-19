package com.kennyb1201.kbstream.ui.mobile

import java.io.File
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
