package com.kennyb1201.kbstream.ui.mobile

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The source picker's half of Phase 2's screen rule: one `FormFactor` branch in
 * the TV file, the phone layout in a new tv-free file, and the same
 * `StreamsViewModel` plus the same auto-play rules behind both.
 *
 * Pinned here because a JVM test cannot compose either layout (no
 * compose-ui-test harness in this module): the dispatch, the tactile widget
 * layer, the ViewModel reads, the auto-play decision chain that decides whether
 * the player opens at all - and the episode-title parser, which is pure and is
 * therefore tested by behaviour rather than by grep.
 */
class StreamsScreenMobileContractTest {

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

    private fun streamsScreen(): String =
        source("com/kennyb1201/kbstream/ui/streams/StreamsScreen.kt")

    private fun mobileStreams(): String =
        source("com/kennyb1201/kbstream/ui/mobile/StreamsScreenMobile.kt")

    @Test
    fun `the picker dispatches on the form factor at its entry point`() {
        val screen = streamsScreen()
        val dispatch = screen.indexOf("if (!FormFactor.isTv)")
        val mobileCall = screen.indexOf("StreamsScreenMobile(")
        assertTrue("StreamsScreen must branch on FormFactor.isTv", dispatch >= 0)
        assertTrue("the mobile branch must call StreamsScreenMobile", mobileCall > dispatch)
        val callBlock = screen.substring(mobileCall)
            .substringBefore("val streams by viewModel.streams")
        assertTrue(
            "the branch must hand over every route parameter",
            callBlock.contains("title = title") &&
                callBlock.contains("displayName = displayName") &&
                callBlock.contains("season = season") &&
                callBlock.contains("episode = episode") &&
                callBlock.contains("runtimeMinutes = runtimeMinutes") &&
                callBlock.contains("backdropUrl = backdropUrl") &&
                callBlock.contains("tmdbId = tmdbId") &&
                callBlock.contains("clearLogoUrl = clearLogoUrl") &&
                callBlock.contains("suppressAutoSelect = suppressAutoSelect") &&
                callBlock.contains("onStreamSelected = onStreamSelected") &&
                callBlock.contains("viewModel = viewModel")
        )
        assertTrue(
            "the branch must return, so the D-pad picker never composes on a phone",
            callBlock.contains("return")
        )
        assertTrue(
            "the TV picker must still be the focus-driven one it was",
            screen.substringAfter("val streams by viewModel.streams")
                .contains("firstStreamFocusRequester")
        )
    }

    @Test
    fun `the mobile picker is built on the touch twins and the mobile theme`() {
        val mobile = mobileStreams()
        assertTrue("the screen must apply MobileTheme", mobile.contains("MobileTheme {"))
        assertTrue(
            "a source must be a touch card, and the control a touch button",
            mobile.contains("ui.components.touch.KBCard") &&
                mobile.contains("ui.components.touch.KBButton")
        )
        assertFalse(
            "the mobile picker must not import a tv-material3 widget",
            mobile.contains("import androidx.tv.material3.")
        )
        assertFalse(
            "the mobile picker must not carry the TV focus plumbing",
            mobile.contains("FocusRequester")
        )
        assertTrue(
            "Back must use the platform dispatcher, since the route has no back callback",
            mobile.contains("LocalOnBackPressedDispatcherOwner")
        )
    }

    @Test
    fun `the mobile picker reads the tv picker view model`() {
        val mobile = mobileStreams()
        listOf(
            "viewModel.streams",
            "viewModel.sourceAddons",
            "viewModel.isLoading",
            "viewModel.loadedKey",
            "viewModel.addonGroups",
            "viewModel.refresh(contentType, streamId, runtimeMinutes)"
        ).forEach { read ->
            assertTrue("mobile Streams must read $read", mobile.contains(read))
        }
    }

    @Test
    fun `auto-play keeps the tv decision chain and its suppressions`() {
        val mobile = mobileStreams()
        // The picker uses ONE capped pick (autoplayPickFor) - the Detail page
        // no longer downloads, so this is the phone's only caller - so the chain
        // is asserted where it is written now, and separately that this screen
        // reaches it through that call.
        val sharedPick = source("com/kennyb1201/kbstream/ui/mobile/DownloadActions.kt")
        // One pick for the whole app now (spec C19): the episode rules, the
        // binge tie-break and the ceiling live inside AutoplayPick.pick, and
        // every surface - this picker, the TV picker, the background resolve -
        // reaches them through it instead of rebuilding the chain.
        val sharedChain = source("com/kennyb1201/kbstream/domain/streamengine/AutoplayPick.kt")
        assertTrue(
            "auto-play must use the shared episode pick and quality ceiling",
            mobile.contains("autoplayPickFor(") &&
                sharedPick.contains("AutoplayPick.pick(") &&
                sharedChain.contains("EpisodeMatch.autoplayPick(") &&
                sharedChain.contains("AutoPlayQuality.cappedPick(")
        )
        assertTrue(
            "what auto-play did must still be reported",
            mobile.contains("StreamRankReport.noteAutoPlay(")
        )
        assertTrue(
            "both prefs (on, and the quality cap) must gate the automatic start",
            mobile.contains("AppPreferences.getAutoSelectStream(context)") &&
                // The EFFECTIVE ceiling, not the raw preference: the cellular
                // data saver narrows the viewer's own cap while the connection
                // is metered (see AppPreferences.getEffectiveAutoPlayQuality).
                mobile.contains("AppPreferences.getEffectiveAutoPlayQuality(context)")
        )
        assertTrue(
            "a manual pick and a viewer refresh must both suppress auto-select",
            mobile.contains("ManualSourceSelection.isPendingPickFor(loadedKey)") &&
                mobile.contains("suppressAutoSelect || refreshed")
        )
        assertTrue(
            "a DRM source must still drop the anime engine verdict",
            mobile.contains("PlayerEngine.clearLaunchAnime()")
        )
        assertTrue(
            "a viewer refresh must re-ask for the target on screen - the route's own " +
                "contentType/streamId, never one the ViewModel remembered",
            mobile.contains("viewModel.refresh(contentType, streamId, runtimeMinutes)")
        )
        assertFalse(
            "REFRESH must stay pressable while a fetch is in flight - a hanging " +
                "provider is exactly when a viewer reaches for it",
            mobile.contains("enabled = !(refreshed && isLoading)")
        )
    }

    @Test
    fun `the list keeps the tabs, the mismatch notice and the episode labels`() {
        val mobile = mobileStreams()
        assertTrue(
            "per-add-on tabs must appear only with more than one add-on",
            mobile.contains("if (addonGroups.size > 1)")
        )
        assertTrue(
            "the all-other-episodes case must still be explained in words",
            mobile.contains("EpisodeMatch.onlyOtherEpisodes(")
        )
        assertTrue(
            "a source declaring another episode must say so on its card",
            mobile.contains("EpisodeMatch.declaredLabel(") &&
                mobile.contains("The file says ")
        )
        assertTrue(
            "the badge-order preference must be honoured",
            mobile.contains("AppPreferences.getBadgesAboveFile(context)")
        )
    }

    @Test
    fun `the episode title parser strips the marker and its separator`() {
        assertEquals(
            "Karambits",
            episodeTitleFrom("Show S4 E4 • Karambits", 4, 4)
        )
        assertEquals(
            "Name",
            episodeTitleFrom("Show S04E04 - Name", 4, 4)
        )
        assertEquals(
            "Two Words",
            episodeTitleFrom("Show S01 E02 Two Words", 1, 2)
        )
        assertNull(
            "a title with no marker has no episode name",
            episodeTitleFrom("Show", 4, 4)
        )
        assertNull(
            "movies have no episode name",
            episodeTitleFrom("Show S4 E4 • Karambits", null, null)
        )
        assertNull(
            "another episode's marker is not this episode's name",
            episodeTitleFrom("Show S4 E5 • Karambits", 4, 4)
        )
    }

    @Test
    fun `downloads are a long-press menu on a row and nothing on Detail`() {
        val mobile = mobileStreams()
        assertTrue(
            "a row must raise the shared touch poster menu on a hold - one sheet, " +
                "not a bespoke menu per row",
            mobile.contains("ui.components.touch.PosterContextMenu") &&
                mobile.contains("onLongClick = if (gate == DownloadGate.Hidden)")
        )
        assertFalse(
            "the inline Download chip is gone: the hold is the only download action",
            mobile.contains("downloadSlot")
        )
        assertTrue(
            "the menu's record comes from the row's own lookup - the media key " +
                "enqueue writes - never a second key",
            mobile.contains("mediaKey = DownloadIdentity.mediaKey( parentId, parentId )") &&
                mobile.contains("downloadFor(downloads, row.mediaKey, season, episode)")
        )
        assertTrue(
            "the menu's rows are the shared state machine, not a second state list",
            mobile.contains("downloadPlan(")
        )

        val detail = source("com/kennyb1201/kbstream/ui/mobile/DetailScreenMobile.kt")
        assertFalse(
            "Detail must have no download action, no press handler and no " +
                "download re-resolve",
            detail.contains("MobileDownloadControl(") ||
                detail.contains("downloadPress(") ||
                detail.contains("downloadAutoplayPick")
        )
        assertTrue(
            "the DOWNLOADED badge stays as display only",
            detail.contains("DownloadedBadge()")
        )
    }
}
