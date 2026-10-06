package com.kennyb1201.kbstream.ui.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.ActivityNotFoundException
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kennyb1201.kbstream.data.format.DateFormats
import com.kennyb1201.kbstream.domain.streamengine.AutoPlayQuality
import com.kennyb1201.kbstream.data.history.WatchHistoryRepository
import com.kennyb1201.kbstream.data.sync.SupabaseSync
import com.kennyb1201.kbstream.data.library.HiddenTitles
import com.kennyb1201.kbstream.data.watched.WatchedStatusRepository
import com.kennyb1201.kbstream.data.update.AppUpdater
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.data.backup.BackupManager
import com.kennyb1201.kbstream.data.player.ExternalPlayer
import com.kennyb1201.kbstream.data.player.PlayerEngine
import com.kennyb1201.kbstream.data.settings.AppPreferences
import com.kennyb1201.kbstream.data.badges.StreamBadgeEngine
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.components.KBPasteChip
import com.kennyb1201.kbstream.ui.components.KBTextField
import com.kennyb1201.kbstream.ui.player.PlayerAudioTuning
import com.kennyb1201.kbstream.ui.player.PlayerTrackBridge
import com.kennyb1201.kbstream.ui.player.SubtitleModeRules
import com.kennyb1201.kbstream.ui.player.FrameRateDiagnostics
import com.kennyb1201.kbstream.ui.player.FrameRateMatch
import com.kennyb1201.kbstream.ui.player.displayReport
import com.kennyb1201.kbstream.ui.theme.DEFAULT_ACCENT_INDEX
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBAccentPalette
import com.kennyb1201.kbstream.ui.theme.KBDanger
import com.kennyb1201.kbstream.ui.theme.KBShapeChip
import com.kennyb1201.kbstream.ui.theme.KBShapePanel
import com.kennyb1201.kbstream.ui.theme.KBShapePill
import com.kennyb1201.kbstream.ui.theme.KBShapeSmall
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import com.kennyb1201.kbstream.ui.theme.refreshThemeMirrors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.kennyb1201.kbstream.data.runCatchingCancellable

// ── Settings IA: left nav rail + right content pane ─────────────
// The old single-scroll screen stacked 7 sections ~10 screens tall on a
// TV. Two panes now: a narrow D-pad-friendly rail on the left jumps
// straight to a section; the right pane shows one section at a time.
/**
 * Rail grouping.
 *
 * Ten flat rows told the user nothing about which entries were playback and
 * which were app plumbing, and the one no one remembers (Hidden Titles) sat
 * between two unrelated ones. These headings are drawn between the clusters
 * on the rail, in this order.
 */
internal enum class SettingsGroup(val label: String) {
    LIBRARY("Library & Sources"),
    PLAYBACK("Playback & Picture"),
    APP("App")
}

/**
 * One settings pane: its rail label, the group it sits under, and a one-line
 * blurb shown under the rail heading (and again under the pane title) so a TV
 * viewer knows what is inside before opening it.
 *
 * Declared group-major, and that ORDER is load-bearing: the rail draws a
 * group heading whenever the group changes while walking the list, so a pane
 * left in the wrong place would print its heading a second time. See
 * SettingsPaneTest.
 */
internal enum class SettingsPane(
    val label: String,
    val group: SettingsGroup,
    val blurb: String,
    /** The rail's leading glyph: a scan target, not decoration. */
    val icon: ImageVector
) {
    INTEGRATIONS(
        "Integrations",
        SettingsGroup.LIBRARY,
        "Profiles, add-ons, accounts and API keys",
        Icons.Filled.Extension
    ),
    HIDDEN(
        "Hidden Titles",
        SettingsGroup.LIBRARY,
        "Titles you hid from every screen",
        Icons.Filled.VisibilityOff
    ),
    PLAYBACK(
        "Playback",
        SettingsGroup.PLAYBACK,
        "Auto-play, skipping, binge grouping and stream picks",
        Icons.Filled.PlayArrow
    ),
    VIDEO(
        "Video & Audio",
        SettingsGroup.PLAYBACK,
        "Buffer, player engine, Dolby Vision and picture-in-picture",
        Icons.Filled.OndemandVideo
    ),
    LANGUAGE(
        "Language",
        SettingsGroup.PLAYBACK,
        "Preferred audio and subtitle language",
        Icons.Filled.Language
    ),
    SUBTITLES(
        "Subtitles",
        SettingsGroup.PLAYBACK,
        "Caption size, background and position",
        Icons.Filled.ClosedCaption
    ),
    INTERFACE(
        "Interface",
        SettingsGroup.APP,
        "Home rails, posters, theme and notifications",
        Icons.Filled.Palette
    ),
    DATA(
        "Data & Backup",
        SettingsGroup.APP,
        "Backup, restore, caches and clearing history",
        Icons.Filled.Storage
    ),
    SYNC(
        "Sync",
        SettingsGroup.APP,
        "Cloud sync health and what has been uploaded",
        Icons.Filled.Sync
    ),
    ABOUT(
        "About",
        SettingsGroup.APP,
        "Version, build and the in-app updater",
        Icons.Filled.Info
    );

    companion object {
        /**
         * A pane name read back from prefs, falling back to the first pane.
         *
         * Pure so the fallback is unit-tested: a name written by a build that
         * has since dropped a pane must not take out the screen the user opens
         * to fix things.
         */
        internal fun fromStored(raw: String?): SettingsPane =
            entries.firstOrNull { it.name == raw?.trim() } ?: entries.first()
    }
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAddons: () -> Unit = {},
    onOpenSimkl: () -> Unit = {},
    onOpenProfiles: () -> Unit = {}
) {
    val context = LocalContext.current

    var bufferMode by remember { mutableIntStateOf(AppPreferences.getDefaultBufferMode(context)) }
    var subtitleSize by remember { mutableIntStateOf(AppPreferences.getDefaultSubtitleSize(context)) }
    var subtitleBg by remember { mutableIntStateOf(AppPreferences.getDefaultSubtitleBackground(context)) }
    var subtitlePosition by remember { mutableIntStateOf(AppPreferences.getDefaultSubtitlePosition(context)) }
    var autoPlayNext by remember { mutableStateOf(AppPreferences.getAutoPlayNext(context)) }
    var nextEpisodePopup by remember {
        mutableStateOf(AppPreferences.getNextEpisodePopup(context))
    }
    // Tenths of a percent: the point moves in 0.5 steps.
    var nextEpisodePopupPoint by remember {
        mutableIntStateOf(AppPreferences.getNextEpisodePopupPointTenths(context))
    }
    var becauseYouWatched by remember {
        mutableStateOf(AppPreferences.getBecauseYouWatched(context))
    }
    var becauseYouWatchedPoint by remember {
        mutableIntStateOf(AppPreferences.getBecauseYouWatchedPointTenths(context))
    }
    var autoSkipIntro by remember { mutableStateOf(AppPreferences.getAutoSkipIntro(context)) }
    var autoSkipCredits by remember { mutableStateOf(AppPreferences.getAutoSkipCredits(context)) }
    var matchFrameRate by remember { mutableStateOf(AppPreferences.getMatchFrameRate(context)) }
    var bingeGroupPrefer by remember { mutableStateOf(AppPreferences.getBingeGroupPrefer(context)) }
    var bingeGroupReuse by remember { mutableStateOf(AppPreferences.getBingeGroupReuse(context)) }
    var bingeGroupFallback by remember { mutableStateOf(AppPreferences.getBingeGroupFallback(context)) }
    var stillTherePrompt by remember { mutableStateOf(AppPreferences.getStillTherePrompt(context)) }
    var stillThereEpisodes by remember { mutableLongStateOf(AppPreferences.getStillThereEpisodes(context)) }
    var newEpisodeNotifications by remember {
        mutableStateOf(AppPreferences.getNewEpisodeNotifications(context))
    }
    var liveReminderNotifications by remember {
        mutableStateOf(AppPreferences.getLiveReminderNotifications(context))
    }
    // Whether the OS will actually deliver an alert. Separate from the toggle:
    // the pref can be ON while the app's notifications are revoked in system
    // settings (or POST_NOTIFICATIONS was never granted on Android 13+), which
    // looks identical to "the feature is broken" from the couch.
    var notificationsAllowed by remember {
        mutableStateOf(
            com.kennyb1201.kbstream.data.notifications.NotificationCenter.canPost(context)
        )
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        notificationsAllowed =
            com.kennyb1201.kbstream.data.notifications.NotificationCenter.canPost(context)
        // The immediate check enqueued by the enabling tap can race the grant
        // dialog and bail out as "not permitted"; now that the answer is in,
        // re-arm it so the first alert isn't delayed to the next 12h round.
        if (
            notificationsAllowed &&
            (
                AppPreferences.getNewEpisodeNotifications(context) ||
                    com.kennyb1201.kbstream.data.notifications.AirReminderStore
                        .hasAny(context)
                )
        ) {
            runCatching {
                com.kennyb1201.kbstream.work.NewEpisodeWorker.syncScheduleForPrefs(
                    context,
                    runImmediate = true
                )
            }
        }
        // Same race for reminder alerts: the arm call that ran with the toggle
        // tap may have found notifications still unauthorized.
        if (notificationsAllowed && AppPreferences.getLiveReminderNotifications(context)) {
            runCatching {
                com.kennyb1201.kbstream.work.ReminderWorker.syncSchedule(context, enabled = true)
            }
        }
    }
    var autoSelectStream by remember { mutableStateOf(AppPreferences.getAutoSelectStream(context)) }
    var useStreamRanker by remember { mutableStateOf(AppPreferences.getUseStreamRanker(context)) }
    var maxAutoPlayQuality by remember { mutableStateOf(AppPreferences.getMaxAutoPlayQuality(context)) }
    var enableTunneling by remember { mutableStateOf(AppPreferences.getEnableTunneling(context)) }
    var enablePip by remember { mutableStateOf(AppPreferences.getEnablePip(context)) }
    // Fire TV OS doesn't support PiP for third-party apps; hide the toggle there.
    val isFireTv = android.os.Build.MANUFACTURER.equals("Amazon", ignoreCase = true)
    var audioDecoder by remember { mutableIntStateOf(AppPreferences.getAudioDecoder(context)) }
    // Audio downmix / dialogue / volume: the global defaults the player's own
    // panel offers to override per show (see PlayerAudioTuning).
    var audioDownmix by remember { mutableIntStateOf(AppPreferences.getAudioDownmix(context)) }
    var audioDialogueBoost by remember { mutableIntStateOf(AppPreferences.getAudioDialogueBoost(context)) }
    var audioVolumeBoostDb by remember { mutableIntStateOf(AppPreferences.getAudioVolumeBoostDb(context)) }
    // Decode vs passthrough (device-level, like the audio decoder above).
    var audioOutput by remember { mutableIntStateOf(AppPreferences.getAudioOutput(context)) }
    var heroTrailerAutoplay by remember { mutableStateOf(AppPreferences.getHeroTrailerAutoplay(context)) }
    var heroTrailerMuted by remember { mutableStateOf(AppPreferences.getHeroTrailerMuted(context)) }
    var heroTrailerDelay by remember { mutableStateOf(AppPreferences.getHeroTrailerDelayMs(context)) }
    var use24hClock by remember { mutableStateOf(AppPreferences.getUse24HourClock(context)) }
    var badgePackInput by remember { mutableStateOf(StreamBadgeEngine.getPackUrl(context)) }
    var railShowType by remember { mutableStateOf(AppPreferences.getHomeRailShowCatalogType(context)) }
    var railShowAddon by remember { mutableStateOf(AppPreferences.getHomeRailShowAddonName(context)) }
    var searchRailShowType by remember { mutableStateOf(AppPreferences.getSearchRailShowCatalogType(context)) }
    var searchRailShowAddon by remember { mutableStateOf(AppPreferences.getSearchRailShowAddonName(context)) }
    var captionTitle by remember { mutableStateOf(AppPreferences.getPosterCaptionTitle(context)) }
    var captionYear by remember { mutableStateOf(AppPreferences.getPosterCaptionYear(context)) }
    var captionRating by remember { mutableStateOf(AppPreferences.getPosterCaptionRating(context)) }
    var posterSizeIdx by remember { mutableStateOf(AppPreferences.getPosterSize(context).toInt()) }
    var railHideUpcoming by remember { mutableStateOf(AppPreferences.getHomeRailHideUpcoming(context)) }
    var browseEnglishOnly by remember { mutableStateOf(AppPreferences.getBrowseEnglishOnly(context)) }
    var landscapeCards by remember { mutableStateOf(AppPreferences.getHomeLandscapeCards(context)) }
    var landscapePosters by remember { mutableStateOf(AppPreferences.getLandscapePosters(context)) }
    var partialWatchBadge by remember { mutableStateOf(AppPreferences.getPosterPartialWatchBadge(context)) }
    var posterBorderIdx by remember { mutableIntStateOf(AppPreferences.getPosterBorderStrength(context)) }
    var posterEdgeIdx by remember { mutableIntStateOf(AppPreferences.getPosterEdge(context)) }
    var amoledBlack by remember { mutableStateOf(AppPreferences.getAmoledBlack(context)) }
    var pureBlackSurface by remember { mutableStateOf(AppPreferences.getPureBlackSurface(context)) }
    var clearingHistory by remember { mutableStateOf(false) }
    var historyClearedAt by remember { mutableStateOf<Long?>(null) }
    var showClearHistoryConfirm by remember { mutableStateOf(false) }
    var dvCompatMode by remember { mutableIntStateOf(AppPreferences.getDvCompatMode(context)) }
    var convertP5To81 by remember { mutableStateOf(AppPreferences.getConvertP5To81(context)) }
    // Coerced read: a stored "MPV" on a device without libmpv reports as
    // ExoPlayer, which is what this row then shows and highlights.
    var playerEngine by remember { mutableIntStateOf(PlayerEngine.selected(context)) }
    var mpvForAnime by remember { mutableStateOf(AppPreferences.getMpvForAnime(context)) }
    // External engine, device-local like the engine itself: which video apps
    // are installed is a property of this box, not of the account.
    val externalPlayers = remember { ExternalPlayer.installed(context) }
    var externalPackage by remember {
        mutableStateOf(
            AppPreferences.getExternalPlayerPackage(context)?.takeIf { stored ->
                externalPlayers.any { it.packageName == stored }
            }
        )
    }
    var externalAsk by remember { mutableStateOf(AppPreferences.getExternalPlayerAsk(context)) }
    var externalTrustReturn by remember {
        mutableStateOf(AppPreferences.getExternalTrustReturn(context))
    }
    // Set when the engine pill below is picked and there is more than one
    // installed player to hand a title to.
    var showExternalPlayerPicker by remember { mutableStateOf(false) }

    // True when this device advertises no Dolby Vision decoder, so Profile 5
    // must be stripped and color-corrected on the GPU: the conversion (and its
    // color path) is then not a choice, it is the only correct picture.
    val p5ConversionRequired = AppPreferences.isP5ConversionRequired(context)
    var stripHdr10Plus by remember { mutableStateOf(AppPreferences.getStripHdr10Plus(context)) }
    var aspectRatio by remember { mutableIntStateOf(AppPreferences.getDefaultAspectRatio(context)) }
    var preferredAudioLang by remember { mutableStateOf(AppPreferences.getPreferredAudioLanguage(context)) }
    var preferredSubtitleLang by remember { mutableStateOf(AppPreferences.getPreferredSubtitleLanguage(context)) }
    var subtitleMode by remember {
        mutableIntStateOf(SubtitleModeRules.normalized(AppPreferences.getSubtitleMode(context)))
    }
    var backupStatus by remember { mutableStateOf<String?>(null) }
    var mdbListKeyInput by remember { mutableStateOf(AppPreferences.getMdbListApiKey(context)) }
    var mdbListKeySaved by remember { mutableStateOf(false) }
    // Key-verification posture (SI-P2-3): MDBList is the ONLY tracker with an
    // opt-in VERIFY, so its status line can say "Connected". The other two have
    // no verification endpoint wired, and their feedback says "Saved" rather
    // than claiming a connection it has not tested. That wording is deliberate
    // and honest - the key really was saved - and adding VERIFY for them means
    // specifying a verification call per provider first.
    var subsKeyInput by remember { mutableStateOf(AppPreferences.getOpensubtitlesApiKey(context)) }
    var subsKeySaved by remember { mutableStateOf(false) }
    var torboxKeyInput by remember { mutableStateOf(AppPreferences.getTorboxApiKey(context)) }
    var torboxKeySaved by remember { mutableStateOf(false) }
    var autoFetchSubtitles by remember { mutableStateOf(AppPreferences.getAutoFetchSubtitles(context)) }
    var spoilerFree by remember { mutableStateOf(AppPreferences.getSpoilerFree(context)) }
    var torboxLibrarySync by remember { mutableStateOf(AppPreferences.getTorboxLibrarySync(context)) }

    // Reopen on the pane last read (device-local, deliberately not synced):
    // the rail is ten panes deep and the one being tuned is rarely the first.
    var selectedPane by remember {
        mutableStateOf(SettingsPane.fromStored(AppPreferences.getLastSettingsPane(context)))
    }

    val backupScope = rememberCoroutineScope()

    // ── Settings search ───────────────────────────────────────────────
    // The rail's query, and the row a jump is currently flashing. Navigation
    // only: a result switches pane, scrolls its row into view and highlights it
    // for a moment. Nothing here can change a setting.
    var settingsSearchQuery by remember { mutableStateOf("") }
    var flashingSetting by remember { mutableStateOf<SettingSearchEntry?>(null) }
    val settingsSearchScope = rememberCoroutineScope()
    // Focus is parked on this field while a query is up, and the picked result
    // card is what unmounts when the query clears - so this is the one node the
    // jump can hand focus back to deterministically.
    val settingsSearchFocus = remember { FocusRequester() }
    val jumpToSetting: (SettingSearchEntry) -> Unit = { entry ->
        selectedPane = entry.pane
        AppPreferences.setLastSettingsPane(context, entry.pane.name)
        settingsSearchQuery = ""
        // Clear the query FIRST, then catch focus, or the card that just
        // vanished takes the D-pad down with it: on a remote there is no
        // pointer to press from, and focus left nowhere is an app the viewer
        // cannot steer. Asking the field explicitly is what makes it
        // deterministic instead of "whatever Compose picks next".
        runCatching { settingsSearchFocus.requestFocus() }
        settingsSearchScope.launch {
            // BringIntoViewRequester is a no-op until its node is attached, and
            // the freshly selected pane has not composed yet when this runs, so
            // the earliest asks land on nothing rather than failing. Ask once
            // per frame until a time budget is spent - a frame count is not a
            // bound on anything (see SETTINGS_SEARCH_SCROLL_BUDGET_MS).
            val startedAt = withFrameNanos { it }
            while (true) {
                runCatching { entry.anchor.bringIntoView() }
                if (withFrameNanos { it } - startedAt >= SETTINGS_SEARCH_SCROLL_BUDGET_MS) {
                    break
                }
            }
            flashingSetting = entry
            delay(SETTINGS_SEARCH_FLASH_MS)
            if (flashingSetting === entry) flashingSetting = null
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            backupScope.launch {
                backupStatus = runCatchingCancellable {
                    BackupManager.export(context, uri)
                }.getOrElse { "Export failed: ${it.message}" }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            backupScope.launch {
                backupStatus = runCatchingCancellable {
                    BackupManager.import(context, uri)
                }.getOrElse { "Import failed: ${it.message}" }
            }
        }
    }

    val importGetContentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            backupScope.launch {
                backupStatus = runCatchingCancellable {
                    BackupManager.import(context, uri)
                }.getOrElse { "Import failed: ${it.message}" }
            }
        }
    }

    fun launchImportBackup() {
        val mimeTypes = arrayOf("application/json", "text/plain", "application/octet-stream")
        try {
            importLauncher.launch(mimeTypes)
        } catch (_: ActivityNotFoundException) {
            try {
                importGetContentLauncher.launch("*/*")
            } catch (_: ActivityNotFoundException) {
                backupStatus =
                    "No file picker on this device — import your backup on a " +
                        "phone/tablet, or move the file to a cloud URL"
            }
        }
    }

    BackHandler { onBack() }

    Row(modifier = Modifier.fillMaxSize().background(KBVoid)) {
        // The rail is taller than a 1080p TV once every section is on it:
        // the SETTINGS heading plus ten rows is ~620dp of content against
        // ~540dp of screen (1080p at 2px/dp), so its last entries - About -
        // ran off the bottom edge with nothing to scroll, i.e. unreachable
        // from the D-pad (there is no scrollbar and no way to swipe).
        //
        // The scroll wraps AROUND the rail rather than sitting on the rail's
        // own Column, and that placement is doing work: under the unbounded
        // height a verticalScroll hands its content, the rail's own
        // fillMaxHeight() falls back to its content height, which is exactly
        // what this container then scrolls. Focus needs no handling - a
        // focused row is brought into view by its scrollable ancestor, so
        // walking down the rail to About just works.
        //
        // The pane color is repeated here because the rail's background now
        // stops at its content on a panel tall enough to fit every row
        // (a 4K set), where this scrollable does not scroll at all.
        val railScroll = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .background(KBSurface)
                .verticalScroll(railScroll)
        ) {
            SettingsNavRail(
                selected = selectedPane,
                onSelect = { pane ->
                    selectedPane = pane
                    AppPreferences.setLastSettingsPane(context, pane.name)
                },
                searchQuery = settingsSearchQuery,
                onSearchQueryChange = { settingsSearchQuery = it },
                searchFocus = settingsSearchFocus,
                onPickResult = jumpToSetting
            )
        }
        SettingsContentHost(
            title = selectedPane.label,
            blurb = selectedPane.blurb,
            scrollable = selectedPane != SettingsPane.HIDDEN,
            flashing = flashingSetting
        ) {
                if (selectedPane == SettingsPane.INTEGRATIONS) {

                    SettingsSectionHeader("Sync", first = true)

                    com.kennyb1201.kbstream.ui.settings.SyncSection()

                    SettingsSectionHeader("Profiles & Accounts")

                    NavigationRow(
                        label = "Profiles",
                        description = "Create, rename, and switch viewing profiles",
                        onClick = onOpenProfiles
                    )

                    // Kids Mode "Lock add-ons": hide the management entry
                    // entirely. The deep-link path is gated in MainActivity;
                    // this hides the visible door.
                    // Collected rather than read as `.value`: reading a
                    // StateFlow's value inside composition never observes it,
                    // so switching to a kids profile left this row showing the
                    // previous profile's lock state (and new lint treats the
                    // `.value` call as an error).
                    val profile =
                        com.kennyb1201.kbstream.data.sync.ProfileManager
                            .activeProfile.collectAsStateWithLifecycle()
                            .value
                    val kidsAddonLock =
                        profile?.kidsMaxAge != null && profile.kidsHideAddons
                    if (!kidsAddonLock) {
                        NavigationRow(
                            label = "Add-ons",
                            description = "Manage add-ons, import collections, and arrange the home screen",
                            onClick = onOpenAddons
                        )
                    }

                    NavigationRow(
                        label = "Simkl",
                        description = "Connect your Simkl account for scrobbling",
                        onClick = onOpenSimkl
                    )
                SettingsSectionHeader("API Keys")

                // MDBList API key: mdblist.com key enables the critic ratings
                // row (IMDb / RT / Metacritic / TMDB / Trakt / Letterboxd / MAL)
                // on detail pages. Declared before the card so the card's click
                // can request focus — the field itself lives further down in the
                // card's content.
                val mdbListFocusRequester = remember { FocusRequester() }
                KBCard(
                    onClick = { mdbListFocusRequester.requestFocus() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(KBSurfaceRaised, KBShapeSmall)
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = if (mdbListKeySaved) "MDBList — Connected" else "MDBList — Not connected",
                            color = KBTextHi,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "Connects your mdblist.com account: critic ratings (IMDb, " +
                                "Rotten Tomatoes, Metacritic, Trakt), watched-history badges, " +
                                "resume-from-pause, scrobbling, and your personal lists + watchlist " +
                                "in the Library tab.",
                            color = KBTextLo,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 2.dp)
                        )

                        // Shared KB field: same look/behavior everywhere. Card OK
                        // requests focus (raises the IME); PASTE chip reads the
                        // clipboard; blur and Done both save.
                        val mdbListPaste: (String) -> Unit = { pasted ->
                            mdbListKeyInput = pasted.trim()
                            AppPreferences.setMdbListApiKey(context, mdbListKeyInput)
                            mdbListKeySaved = mdbListKeyInput.isNotBlank()
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .fillMaxWidth()
                        ) {
                            KBTextField(
                                value = mdbListKeyInput,
                                onValueChange = {
                                    mdbListKeyInput = it.trim()
                                    mdbListKeySaved = false
                                },
                                placeholder = "Paste key (e.g. 12345)",
                                modifier = Modifier.weight(1f),
                                focusRequester = mdbListFocusRequester,
                                onDone = {
                                    AppPreferences.setMdbListApiKey(context, mdbListKeyInput)
                                    mdbListKeySaved = mdbListKeyInput.isNotBlank()
                                },
                                onFocusChanged = { focusedNow ->
                                    // Save on focus loss too — remote users often
                                    // just navigate away after pasting.
                                    if (!focusedNow) {
                                        AppPreferences.setMdbListApiKey(context, mdbListKeyInput)
                                        mdbListKeySaved = mdbListKeyInput.isNotBlank()
                                    }
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            KBPasteChip(onPaste = mdbListPaste)
                        }

                        if (mdbListKeySaved) {
                            // Connection status line: the VERIFY action probes
                            // GET /user with the pasted key and shows the
                            // account name (or why the key was rejected).
                            var verifyState by remember {
                                mutableStateOf<Pair<String?, String?>?>(null)
                            }
                            var verifying by remember { mutableStateOf(false) }
                            val verifyScope = rememberCoroutineScope()
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(top = 6.dp)
                            ) {
                                Text(
                                    text = when {
                                        verifying -> "Verifying…"
                                        verifyState?.first != null ->
                                            "Connected as ${verifyState?.first}"
                                        verifyState?.second != null ->
                                            verifyState?.second ?: ""
                                        else -> "Key saved — ratings appear on the next title you open."
                                    },
                                    color = when {
                                        verifying || verifyState?.first != null -> KBAccent
                                        verifyState?.second != null -> KBTextLo
                                        else -> KBAccent
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                KBCard(
                                    onClick = {
                                        verifying = true
                                        verifyState = null
                                        verifyScope.launch {
                                            verifyState = com.kennyb1201.kbstream
                                                .data.mdblist.MdbListClient.verifyKey(context)
                                            verifying = false
                                        }
                                    },
                                    modifier = Modifier.width(120.dp)
                                ) {
                                    Text(
                                        text = if (verifying) "VERIFYING…" else "VERIFY",
                                        color = KBAccent,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier
                                            .background(KBSurface, KBShapeSmall)
                                            .padding(horizontal = 14.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                }

                if (selectedPane == SettingsPane.INTEGRATIONS) {
                // ── OPENSUBTITLES KEY (in-player subtitle search) ─────────
                val subsFocusRequester = remember { FocusRequester() }
                KBCard(
                    onClick = { subsFocusRequester.requestFocus() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(KBSurfaceRaised, KBShapeSmall)
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = "OpenSubtitles API Key",
                            color = KBTextHi,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "Free key from opensubtitles.com — adds SEARCH SUBTITLES " +
                                "to the player's subtitle picker for streams without subs.",
                            color = KBTextLo,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        val subsPaste: (String) -> Unit = { pasted ->
                            subsKeyInput = pasted.trim()
                            AppPreferences.setOpensubtitlesApiKey(context, subsKeyInput)
                            subsKeySaved = subsKeyInput.isNotBlank()
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .fillMaxWidth()
                        ) {
                            KBTextField(
                                value = subsKeyInput,
                                onValueChange = {
                                    subsKeyInput = it.trim()
                                    subsKeySaved = false
                                },
                                placeholder = "Paste key",
                                modifier = Modifier.weight(1f),
                                focusRequester = subsFocusRequester,
                                onDone = {
                                    AppPreferences.setOpensubtitlesApiKey(context, subsKeyInput)
                                    subsKeySaved = subsKeyInput.isNotBlank()
                                },
                                onFocusChanged = { focusedNow ->
                                    if (!focusedNow) {
                                        AppPreferences.setOpensubtitlesApiKey(context, subsKeyInput)
                                        subsKeySaved = subsKeyInput.isNotBlank()
                                    }
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            KBPasteChip(onPaste = subsPaste)
                        }
                        if (subsKeySaved) {
                            Text(
                                text = "Saved — the search entry appears in the player's subtitle picker.",
                                color = KBAccent,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 5.dp)
                            )
                        }
                    }
                }

                // ── AUTO-FETCH SUBTITLES (uses the OpenSubtitles key above) ───
                Spacer(modifier = Modifier.height(12.dp))
                ToggleRow(
                    label = "Auto-fetch Subtitles",
                    description = "When a stream carries no subtitle track, search " +
                        "OpenSubtitles for your preferred subtitle language and attach " +
                        "the best match automatically. Needs an OpenSubtitles key above " +
                        "and a preferred subtitle language set in Player settings.",
                    checked = autoFetchSubtitles,
                    onToggle = {
                        autoFetchSubtitles = it
                        AppPreferences.setAutoFetchSubtitles(context, it)
                    }
                )

                // ── TORBOX KEY (cached-status badges in the picker) ────
                Spacer(modifier = Modifier.height(12.dp))
                val torboxFocusRequester = remember { FocusRequester() }
                KBCard(
                    onClick = { torboxFocusRequester.requestFocus() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(KBSurfaceRaised, KBShapeSmall)
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = "TorBox API Key",
                            color = KBTextHi,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "Optional. From torbox.app → Account → API. Adds a \"Cached\" " +
                                "badge to sources your TorBox account already holds — those " +
                                "start instantly off the CDN, with no peers to find.",
                            color = KBTextLo,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                        val torboxPaste: (String) -> Unit = { pasted ->
                            torboxKeyInput = pasted.trim()
                            AppPreferences.setTorboxApiKey(context, torboxKeyInput)
                            torboxKeySaved = torboxKeyInput.isNotBlank()
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .fillMaxWidth()
                        ) {
                            KBTextField(
                                value = torboxKeyInput,
                                onValueChange = {
                                    torboxKeyInput = it.trim()
                                    torboxKeySaved = false
                                },
                                placeholder = "Paste key",
                                modifier = Modifier.weight(1f),
                                focusRequester = torboxFocusRequester,
                                onDone = {
                                    AppPreferences.setTorboxApiKey(context, torboxKeyInput)
                                    torboxKeySaved = torboxKeyInput.isNotBlank()
                                },
                                onFocusChanged = { focusedNow ->
                                    if (!focusedNow) {
                                        AppPreferences.setTorboxApiKey(context, torboxKeyInput)
                                        torboxKeySaved = torboxKeyInput.isNotBlank()
                                    }
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            KBPasteChip(onPaste = torboxPaste)
                        }
                        if (torboxKeySaved) {
                            Text(
                                text = "Saved — cached sources are badged in the stream picker.",
                                color = KBAccent,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 5.dp)
                            )
                        }
                    }
                }

                // ── TORBOX CLOUD → LIBRARY ────────────────────────────
                Spacer(modifier = Modifier.height(12.dp))
                ToggleRow(
                    label = "Add TorBox Cloud to Library",
                    description = "With a TorBox key above, add the torrents in your " +
                        "TorBox library to My List. Each is matched to a title on TMDB; " +
                        "anything that can't be matched confidently is left out.",
                    checked = torboxLibrarySync,
                    onToggle = {
                        torboxLibrarySync = it
                        AppPreferences.setTorboxLibrarySync(context, it)
                        com.kennyb1201.kbstream.work.TorBoxLibraryWorker
                            .syncScheduleForPrefs(context, runImmediate = it)
                    }
                )

                // ── STREAM BADGES (KB-compatible packs) ────────────────
                SettingsSectionHeader("Badges")

                val badgeFocusRequester = remember { FocusRequester() }
                var badgesAboveFile by remember {
                    mutableStateOf(AppPreferences.getBadgesAboveFile(context))
                }
                KBCard(
                    onClick = { badgeFocusRequester.requestFocus() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(KBSurfaceRaised, KBShapeSmall)
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = "Stream Badges",
                            color = KBTextHi,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "Import a KB-compatible badge pack JSON — matched " +
                                "badges show on sources and in the player overlay.",
                            color = KBTextLo,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 2.dp)
                        )

                        // Persistent status: seeded from the saved pack so the
                        // imported-state line survives leaving and re-entering
                        // settings (the OMDb "Saved" parity). Import/remove then
                        // update it live.
                        var badgeStatus by remember { mutableStateOf(
                            if (StreamBadgeEngine.hasPack(context)) {
                                "Badge pack imported — ${StreamBadgeEngine.filterCount(context)} filters active"
                            } else {
                                null
                            }
                        ) }
                        var badgeImporting by remember { mutableStateOf(false) }
                        val scope = rememberCoroutineScope()

                        fun importBadgePack() {
                            if (badgePackInput.isNotBlank() && !badgeImporting) {
                                badgeImporting = true
                                scope.launch {
                                    badgeStatus = StreamBadgeEngine
                                        .importFromUrl(context, badgePackInput)
                                        ?: "Badge pack imported — " +
                                            "${StreamBadgeEngine.filterCount(context)} filters active"
                                    badgeImporting = false
                                }
                            }
                        }

                        // Shared KB field: identical to OMDb (focus via card OK,
                        // PASTE chip, Enter = import, D-pad escape).
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .fillMaxWidth()
                        ) {
                            KBTextField(
                                value = badgePackInput,
                                onValueChange = {
                                    badgePackInput = it.trim()
                                    badgeStatus = null
                                },
                                placeholder = "https://…/stream-badges.json",
                                modifier = Modifier.weight(1f),
                                focusRequester = badgeFocusRequester,
                                onDone = { importBadgePack() }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            KBPasteChip(onPaste = { pasted ->
                                badgePackInput = pasted.trim()
                                badgeStatus = null
                            })
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            KBCard(
                                onClick = { importBadgePack() },
                                modifier = Modifier
                                    .background(KBSurfaceRaised, KBShapeSmall)
                            ) {
                                Text(
                                    text = if (badgeImporting) "Importing…" else "Import",
                                    color = KBTextHi,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                )
                            }
                            if (StreamBadgeEngine.hasPack(context)) {
                                KBCard(
                                    onClick = {
                                        StreamBadgeEngine.clearPack(context)
                                        badgeStatus = "Badge pack removed"
                                    },
                                    modifier = Modifier
                                        .padding(start = 8.dp)
                                        .background(KBSurfaceRaised, KBShapeSmall)
                                ) {
                                    Text(
                                        text = "Remove",
                                        color = KBTextLo,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }

                        badgeStatus?.let {
                            Text(
                                text = it,
                                color = if (it.startsWith("Badge pack imported")) KBAccent else KBTextLo,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 5.dp)
                            )
                        }
                    }
                }

                // One pref for both surfaces: this controls the chips in the
                // player's source picker and in the streams screen, since both
                // list the same sources. ON = above the file name (the default).
                ToggleRow(
                    label = "Badges above the file name",
                    description = "Show a source's badge chips over its name instead of under " +
                        "it, in the player's source picker and on the streams screen.",
                    checked = badgesAboveFile,
                    onToggle = {
                        badgesAboveFile = it
                        AppPreferences.setBadgesAboveFile(context, it)
                    }
                )
                }

                if (selectedPane == SettingsPane.DATA) {
                SettingsSectionHeader("Backup & Restore", first = true)

                NavigationRow(
                    label = "Export Backup",
                    description = "Save settings, add-ons & watched state to a file",
                    onClick = {
                        val stamp = DateFormats.now(DateFormats.FILE_STAMP)
                        exportLauncher.launch("kbstream-backup-$stamp.json")
                    }
                )

                NavigationRow(
                    label = "Import Backup",
                    description = "Restore from a KBStream backup file",
                    onClick = {
                        launchImportBackup()
                    }
                )

                SettingsSectionHeader("History")

                NavigationRow(
                    label = if (clearingHistory) "Clearing..." else "Clear Continue Watching",
                    description = if (clearingHistory)
                        "Erasing local resume positions and watched markers..."
                    else
                        "Reset all resume positions and watched markers (Simkl link is kept)",
                    onClick = {
                        if (clearingHistory) return@NavigationRow
                        showClearHistoryConfirm = true
                    }
                )

                if (historyClearedAt != null) {
                    Text(
                        text = "Continue watching cleared.",
                        color = KBAccent,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                    )
                }

                backupStatus?.let { message ->
                    Text(
                        text = message,
                        color = if (message.startsWith("Backup")) KBAccent else KBTextLo,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
                    )
                }
                }

                if (selectedPane == SettingsPane.PLAYBACK) {
                SettingsSectionHeader("Audio", first = true)

                Text(
                    text = "Audio Decoder",
                    color = KBTextHi,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        "Device decoders only",
                        "Prefer device decoders",
                        "Prefer app decoders (FFmpeg)"
                    ).forEachIndexed { index, label ->
                        KBCard(onClick = {
                            audioDecoder = index
                            AppPreferences.setAudioDecoder(context, index)
                        }) {
                            PillChip(label, audioDecoder == index)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = when (audioDecoder) {
                        AppPreferences.AUDIO_DECODER_DEVICE_ONLY ->
                            "Only built-in hardware decoders. Most compatible but may not support all formats."
                        AppPreferences.AUDIO_DECODER_PREFER_DEVICE ->
                            "Hardware when available, falls back to FFmpeg. Recommended for most devices."
                        AppPreferences.AUDIO_DECODER_PREFER_APP ->
                            "FFmpeg audio first — decodes DTS/TrueHD to PCM. Best format support but higher CPU usage."
                        else -> ""
                    },
                    color = KBTextLo,
                    style = MaterialTheme.typography.labelSmall
                )

                Spacer(modifier = Modifier.height(12.dp))

                AudioTuningRow(
                    label = "Audio Output",
                    description = when (audioOutput) {
                        PlayerAudioTuning.AUDIO_OUTPUT_PASSTHROUGH ->
                            "Send the original format (Dolby/DTS) to your receiver to decode. " +
                                "Best when an AVR or soundbar handles the surround, but Downmix, " +
                                "Dialogue boost and Volume boost are ignored while this is on."
                        PlayerAudioTuning.AUDIO_OUTPUT_DECODE ->
                            "Always decode in the app to PCM, so Downmix, Dialogue boost and " +
                                "Volume boost apply. Surround plays as multichannel PCM rather " +
                                "than a bitstream."
                        else ->
                            "Decode automatically when Downmix, Dialogue boost or Volume boost " +
                                "is in use (so they apply), otherwise pass the original format " +
                                "to your receiver. Recommended."
                    },
                    options = PlayerAudioTuning.AUDIO_OUTPUT_OPTIONS,
                    selected = audioOutput,
                    onSelect = {
                        audioOutput = it
                        AppPreferences.setAudioOutput(context, it)
                    }
                )

                Spacer(modifier = Modifier.height(10.dp))

                AudioTuningRow(
                    label = "Audio Downmix",
                    description = when (audioDownmix) {
                        PlayerAudioTuning.DOWNMIX_STEREO ->
                            "Fold 5.1/7.1 into stereo with the center channel (dialogue) lifted and " +
                                "the surrounds trimmed. Best for a TV's own speakers."
                        PlayerAudioTuning.DOWNMIX_SURROUND ->
                            "Keep 5.1 (7.1 folds into it). Use with an AVR or a device that really has " +
                                "six channels."
                        else ->
                            "Fold multichannel down to what this device can carry — stereo on a TV's " +
                                "own speakers, 5.1 kept on an AVR — lifting the center channel as it " +
                                "folds."
                    },
                    options = PlayerAudioTuning.DOWNMIX_OPTIONS,
                    selected = audioDownmix,
                    onSelect = {
                        audioDownmix = it
                        AppPreferences.setAudioDownmix(context, it)
                    }
                )

                Spacer(modifier = Modifier.height(10.dp))

                DialogueBoostRow(
                    level = audioDialogueBoost,
                    onLevelChange = {
                        audioDialogueBoost = it
                        AppPreferences.setAudioDialogueBoost(context, it)
                    },
                    chip = { label, selected -> PillChip(label, selected) }
                )

                Spacer(modifier = Modifier.height(10.dp))

                AudioTuningRow(
                    label = "Volume Boost",
                    description = "Extra gain for mixes that are simply too quiet, with a limiter that " +
                        "catches peaks so loud scenes do not distort.",
                    options = PlayerAudioTuning.VOLUME_OPTIONS,
                    selected = audioVolumeBoostDb,
                    onSelect = {
                        audioVolumeBoostDb = it
                        AppPreferences.setAudioVolumeBoostDb(context, it)
                    }
                )

                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Applies to the app's PCM audio path. Tunneled Playback is skipped while any of " +
                        "these are on (a tunnel bypasses the audio chain). All three apply while a " +
                        "film is playing, downmix included. Any title can override these from the " +
                        "player's own panel.",
                    color = KBTextLo,
                    style = MaterialTheme.typography.labelSmall
                )

                Spacer(modifier = Modifier.height(14.dp))

                SettingsSectionHeader("Player Engine")

                Text(
                    text = "Playback engine",
                    color = KBTextHi,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = "Which engine opens a title. ExoPlayer drives the full player panel, this " +
                        "app's Dolby Vision layer and its audio chain. MPV (libmpv) is the backup: it " +
                        "plays files this TV's decoders cannot handle at all, and renders ASS/SSA " +
                        "subtitles the way the fansub styled them.",
                    color = KBTextLo,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        AppPreferences.PLAYER_ENGINE_EXO to "ExoPlayer",
                        AppPreferences.PLAYER_ENGINE_EXO_ONLY to "ExoPlayer only",
                        AppPreferences.PLAYER_ENGINE_MPV to "MPV",
                        AppPreferences.PLAYER_ENGINE_EXTERNAL to "External player"
                    ).forEach { (value, label) ->
                        KBCard(onClick = {
                            playerEngine = value
                            AppPreferences.setPlayerEngine(context, value)
                            // Picking this engine IS picking a player, so ask
                            // which one right away when there is a choice: the
                            // alternative is handing the next title to whichever
                            // app the probe happened to find first.
                            if (value == AppPreferences.PLAYER_ENGINE_EXTERNAL &&
                                externalPlayers.size > 1
                            ) {
                                showExternalPlayerPicker = true
                            }
                        }) {
                            PillChip(label, playerEngine == value)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = when (playerEngine) {
                        AppPreferences.PLAYER_ENGINE_EXO ->
                            "ExoPlayer, switching to MPV on its own when a stream cannot be decoded at " +
                                "all \u2014 out of decoder resources, or a codec this TV has no decoder for."
                        AppPreferences.PLAYER_ENGINE_EXO_ONLY ->
                            "ExoPlayer only. A stream it cannot decode shows the error instead of " +
                                "changing engine mid-title."
                        AppPreferences.PLAYER_ENGINE_EXTERNAL ->
                            "Hands the title to any video app installed on this device - you " +
                                "pick which one below. Everything around playback stays here: " +
                                "watch history, Continue Watching, scrobbling and the Up Next / " +
                                "because-you-watched cards. Intros, recaps and credits are " +
                                "skipped before the hand-off when Auto-skip below is on (another " +
                                "app owns the screen, so there is nowhere to draw the SKIP " +
                                "button). Request headers and DRM cannot travel to another app, " +
                                "so a source that needs them plays in-app."
                        else ->
                            "MPV (libmpv) plays anything: its decoders fall back to software when the " +
                                "hardware ones refuse, so \"no decoder resources\" and unsupported codecs " +
                                "still play. The in-player panel (sources, Dolby Vision, audio tuning, " +
                                "remembered tracks) is ExoPlayer-only and is not available here."
                    },
                    color = KBTextLo,
                    style = MaterialTheme.typography.labelSmall
                )
                if (playerEngine == AppPreferences.PLAYER_ENGINE_EXTERNAL &&
                    externalPlayers.isEmpty()
                ) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "No video player app is installed on this device, so ExoPlayer " +
                            "plays instead. Install VLC, MX Player or Kodi to use the external " +
                            "engine.",
                        color = KBDanger,
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                // Only meaningful when this box has an app to hand a title to:
                // with none installed the picker above never opens the external
                // engine (see PlayerEngine.selected), so this would be a list of
                // nothing.
                if (externalPlayers.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = "External player",
                        color = KBTextHi,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                    Text(
                        text = "Which app plays a title when the engine above is \"External " +
                            "player\". KBStream keeps the session either way: the playhead is " +
                            "measured while the other app is in front, so watch history, Continue " +
                            "Watching, scrobbling and the Up Next / because-you-watched cards all " +
                            "keep working.",
                        color = KBTextLo,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        externalPlayers.forEach { player ->
                            KBCard(onClick = {
                                externalPackage = player.packageName
                                // Tapping one app is an answer to "which app?", so
                                // it turns asking off: the pick is remembered and
                                // every title goes straight to it from now on.
                                externalAsk = false
                                AppPreferences.setExternalPlayer(
                                    context,
                                    player.packageName,
                                    player.label
                                )
                                AppPreferences.setExternalPlayerAsk(context, false)
                            }) {
                                PillChip(player.label, externalPackage == player.packageName)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    // What happens on the next title, in one line: remembered and
                    // silent, asking every time, or nothing chosen yet.
                    val rememberedPlayer = externalPlayers
                        .firstOrNull { it.packageName == externalPackage }
                    Text(
                        text = when {
                            externalAsk ->
                                "Titles will ask which app to use, every time."
                            rememberedPlayer != null ->
                                "Remembered - titles play in ${rememberedPlayer.label} with no " +
                                    "prompt."
                            else ->
                                "Nothing picked yet: the first title opens the system chooser, " +
                                    "and whatever you pick there is remembered."
                        },
                        color = if (externalAsk) KBTextLo else KBAccent,
                        style = MaterialTheme.typography.labelSmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    ToggleRow(
                        label = "Ask every time",
                        description = "Show the system's own chooser for each title instead of " +
                            "going straight to the app picked above.",
                        checked = externalAsk,
                        onToggle = {
                            externalAsk = it
                            AppPreferences.setExternalPlayerAsk(context, it)
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    ToggleRow(
                        label = "Treat coming back as the end",
                        description = "Your player usually cannot say where you stopped, so " +
                            "KBStream measures the playhead against the clock while it is in " +
                            "front. Pause or scrub once and a title you watched through comes " +
                            "back short of the finish line - no Up Next / because-you-watched " +
                            "card, and not marked watched. With this on, returning from the " +
                            "player counts as finishing the title. A playhead your player does " +
                            "report is still believed first, but anything you back out of early " +
                            "counts too, so leave it off unless you watch external titles " +
                            "through to the end.",
                        checked = externalTrustReturn,
                        onToggle = {
                            externalTrustReturn = it
                            AppPreferences.setExternalTrustReturn(context, it)
                        }
                    )
                }

                if (!PlayerEngine.isMpvAvailable()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "This device runs Android ${Build.VERSION.RELEASE}. The MPV engine " +
                            "needs Android 8 or newer, so ExoPlayer is what plays here.",
                        color = KBDanger,
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                // Only meaningful where libmpv exists: on an older box the
                // warning above already says why MPV is not an option.
                if (PlayerEngine.isMpvAvailable()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    ToggleRow(
                        label = "Play Anime in MPV",
                        description = "Opens anime in the MPV engine even when the picker above " +
                            "says ExoPlayer (including \"ExoPlayer only\"). Anime is what this " +
                            "engine is for: libass renders fansub ASS/SSA typesetting the way it was " +
                            "authored, and its FFmpeg decoders play the 10-bit and 4:4:4 profiles " +
                            "release groups ship, which this TV's own decoders often refuse. A " +
                            "title counts as anime when it comes from an anime catalog, carries " +
                            "TMDB's \"anime\" keyword, or is Animation with a Japanese original " +
                            "language.",
                        checked = mpvForAnime,
                        onToggle = {
                            mpvForAnime = it
                            AppPreferences.setMpvForAnime(context, it)
                        }
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                SettingsSectionHeader("Dolby Vision & HDR")

                Text(
                    text = "Dolby Vision",
                    color = KBTextHi,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = "Handling for Dolby Vision files on TVs that don't support every profile",
                    color = KBTextLo,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        AppPreferences.DV_COMPAT_AUTO to "P7 \u2192 8.1",
                        AppPreferences.DV_COMPAT_OFF to "None",
                        AppPreferences.DV_COMPAT_ALL to "Strip All"
                    ).forEach { (value, label) ->
                        KBCard(onClick = {
                            dvCompatMode = value
                            AppPreferences.setDvCompatMode(context, value)
                        }) {
                            PillChip(label, dvCompatMode == value)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = when (dvCompatMode) {
                        AppPreferences.DV_COMPAT_AUTO -> "Rewrites Blu-ray Profile 7 remuxes to Profile 8.1 (RPU kept, enhancement layer dropped). P4/P8 play as Dolby Vision; P5 follows its toggle below"
                        AppPreferences.DV_COMPAT_OFF -> "Play files exactly as provided (device must handle DV)"
                        AppPreferences.DV_COMPAT_ALL -> "Strips every DV profile (P4/P5/P7/P8) \u2192 HDR10/HEVC for non-DV TVs. P5 colors are corrected on the GPU automatically"
                        else -> ""
                    },
                    color = KBTextLo,
                    style = MaterialTheme.typography.labelSmall
                )
                if (dvCompatMode != AppPreferences.DV_COMPAT_AUTO) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = when (dvCompatMode) {
                            AppPreferences.DV_COMPAT_OFF ->
                                "None passes everything through — the P5 \u2192 HDR10 toggle below is ignored"
                            else ->
                                "Strip All strips every profile — the P5 \u2192 HDR10 toggle below is ignored"
                        },
                        color = KBTextLo,
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                ToggleRow(
                    label = "P5 \u2192 HDR10",
                    description = if (p5ConversionRequired) {
                        "Always on: this device has no Dolby Vision decoder, so Profile 5 (ICtCp) is stripped to HDR10 and corrected on the GPU. Passing it through would show green and purple"
                    } else {
                        "Strips Profile 5 (ICtCp) to HDR10 and converts the colors on the GPU, for displays that cannot show ICtCp P5. Profile 5 has no HDR10 base layer, so relabeling it 8.1 alone shows green and purple. Off = P5 plays as native Dolby Vision. Applies only in P7 \u2192 8.1 mode"
                    },
                    checked = convertP5To81,
                    enabled = dvCompatMode == AppPreferences.DV_COMPAT_AUTO &&
                        !p5ConversionRequired,
                    onToggle = {
                        convertP5To81 = it
                        AppPreferences.setConvertP5To81(context, it)
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // No switch for the P5 color path any more: it runs exactly
                // while a P5 conversion does. As a standalone toggle it could
                // only misfire — with P5 left as Dolby Vision there is nothing
                // stripped for the shader to convert, and in "None" it hid the
                // player view with no renderer able to feed the GL view (a
                // black screen with audio).
                Text(
                    text = "P5 color correction has no separate switch: Profile 5 (ICtCp) is converted on the GPU whenever it is stripped — automatically in Strip All and on a device with no Dolby Vision decoder, or with the P5 → HDR10 toggle above",
                    color = KBTextLo,
                    style = MaterialTheme.typography.labelSmall
                )

                Spacer(modifier = Modifier.height(8.dp))

                ToggleRow(
                    label = "Strip HDR10+",
                    description = if (dvCompatMode == AppPreferences.DV_COMPAT_OFF) {
                        "Remove ST 2094-40 (HDR10+) metadata from plain-HDR10+ files for TVs that black-screen on it. Only this mode needs the switch — every DV rewrite below drops it automatically"
                    } else {
                        "Remove ST 2094-40 (HDR10+) metadata. Plain-HDR10+ files follow this switch; every DV rewrite (P7 \u2192 8.1, P5 \u2192 HDR10, Strip All) drops HDR10+ automatically, because those output static HDR10"
                    },
                    checked = stripHdr10Plus,
                    onToggle = {
                        stripHdr10Plus = it
                        AppPreferences.setStripHdr10Plus(context, it)
                    }
                )

                Spacer(modifier = Modifier.height(10.dp))

                SettingsSectionHeader("Stream Picks & Auto-play")

                ToggleRow(
                    label = "Auto-select Stream",
                    description = "Automatically play the top source when streams load",
                    checked = autoSelectStream,
                    onToggle = {
                        autoSelectStream = it
                        AppPreferences.setAutoSelectStream(context, it)
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Off is not just "the old way": an add-on that already filters
                // and sorts its own results (AIOStreams with a regex + SEL
                // config is the one people run) has an opinion this ranker is
                // only ever guessing at, so the description has to say which
                // side wins instead of implying the ranker is strictly better.
                ToggleRow(
                    label = "Stream Ranker",
                    description = "Reorder sources by availability, resolution and release type. " +
                        "Off keeps the exact order the add-on returned — the better choice when the " +
                        "add-on already sorts its own results, like AIOStreams with a SEL config",
                    checked = useStreamRanker,
                    onToggle = {
                        useStreamRanker = it
                        AppPreferences.setUseStreamRanker(context, it)
                    }
                )

                Spacer(modifier = Modifier.height(12.dp))

                // A ceiling on what auto-play starts, distinct from the ranker
                // above: the ranker weighs this device's decode headroom, this
                // is what the viewer says the *network* can take tonight. The
                // picker is untouched - every source stays listed and playable
                // by hand; only the automatic head is capped.
                Text(
                    text = "Max Auto-play Quality",
                    color = KBTextHi,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Auto", "4K", "1080p", "720p").forEachIndexed { index, label ->
                        KBCard(onClick = {
                            maxAutoPlayQuality = index
                            AppPreferences.setMaxAutoPlayQuality(context, index)
                        }) {
                            PillChip(label, maxAutoPlayQuality == index)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = when (maxAutoPlayQuality) {
                        AutoPlayQuality.CAP_2160 ->
                            "Auto-play may start anything up to 4K. Taller sources are still listed."
                        AutoPlayQuality.CAP_1080 ->
                            "Auto-play never starts above 1080p: a 4K remux stays in the picker, but the automatic pick takes the best 1080p or lower copy."
                        AutoPlayQuality.CAP_720 ->
                            "Auto-play never starts above 720p. The picker is unchanged, so a taller copy can still be chosen by hand."
                        else ->
                            "Auto-play takes whatever the ranker puts first, including 4K - handy to lift when the network is busy."
                    },
                    color = KBTextLo,
                    style = MaterialTheme.typography.labelSmall
                )

                Spacer(modifier = Modifier.height(8.dp))

                ToggleRow(
                    label = "Auto-play Next Episode",
                    description = "Continue to the next episode when one ends",
                    checked = autoPlayNext,
                    onToggle = {
                        autoPlayNext = it
                        AppPreferences.setAutoPlayNext(context, it)
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                ToggleRow(
                    label = "Next Episode Popup",
                    description = "Show the Up Next card as an episode's credits roll: the next episode, its still, and the countdown to it. Off ends the episode with no card \u2014 and, because that card is where Auto-play Next counts down and where the still-there prompt asks, with no auto-advance either.",
                    checked = nextEpisodePopup,
                    onToggle = {
                        nextEpisodePopup = it
                        AppPreferences.setNextEpisodePopup(context, it)
                    }
                )

                Spacer(modifier = Modifier.height(10.dp))

                EndPanelPointRow(
                    label = "Next Episode Popup Point",
                    hint = "How far into the episode the card opens, as a percentage of its runtime. The -0.5 / +0.5 chips nudge it in half-percent steps. A title that carries its own credits marker opens as the credits start instead.",
                    selected = nextEpisodePopupPoint,
                    enabled = nextEpisodePopup,
                    chip = { text, isSelected -> PillChip(text, isSelected) },
                    onPick = { point ->
                        nextEpisodePopupPoint = point
                        AppPreferences.setNextEpisodePopupPointTenths(context, point)
                    }
                )

                Spacer(modifier = Modifier.height(12.dp))

                ToggleRow(
                    label = "Because You Watched",
                    description = "When there is no next episode \u2014 a movie, or a finished finale \u2014 recommend what to watch instead as the credits roll, with PLAY and DETAILS on each pick.",
                    checked = becauseYouWatched,
                    onToggle = {
                        becauseYouWatched = it
                        AppPreferences.setBecauseYouWatched(context, it)
                    }
                )

                Spacer(modifier = Modifier.height(10.dp))

                EndPanelPointRow(
                    label = "Because You Watched Point",
                    hint = "How far into the title the picks open. The -0.5 / +0.5 chips nudge it in half-percent steps.",
                    selected = becauseYouWatchedPoint,
                    enabled = becauseYouWatched,
                    chip = { text, isSelected -> PillChip(text, isSelected) },
                    onPick = { point ->
                        becauseYouWatchedPoint = point
                        AppPreferences.setBecauseYouWatchedPointTenths(context, point)
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                ToggleRow(
                    label = "Prefer Binge Group",
                    description = "Auto-next picks the stream tagged with the same Stremio binge group as the episode you just finished — same link continues seamlessly.",
                    checked = bingeGroupPrefer,
                    onToggle = {
                        bingeGroupPrefer = it
                        AppPreferences.setBingeGroupPrefer(context, it)
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                ToggleRow(
                    label = "Reuse Binge Group",
                    description = "When no stream carries the previous group tag, reuse the same addon's stream anyway.",
                    checked = bingeGroupReuse,
                    onToggle = {
                        bingeGroupReuse = it
                        AppPreferences.setBingeGroupReuse(context, it)
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                ToggleRow(
                    label = "Binge Fallback",
                    description = "When no group or addon match exists, still auto-play the top-ranked stream. Off stops autoplay and shows the source picker instead.",
                    checked = bingeGroupFallback,
                    onToggle = {
                        bingeGroupFallback = it
                        AppPreferences.setBingeGroupFallback(context, it)
                    }
                )

                Spacer(modifier = Modifier.height(10.dp))

                ToggleRow(
                    label = "Are You Still There",
                    description = "After several auto-played episodes in a row, hold on a confirmation prompt so it doesn't play all night.",
                    checked = stillTherePrompt,
                    onToggle = {
                        stillTherePrompt = it
                        AppPreferences.setStillTherePrompt(context, it)
                    }
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Still-There Prompt After",
                    color = KBTextHi,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(2L, 3L, 5L, 8L).forEach { count ->
                        KBCard(onClick = {
                            stillThereEpisodes = count
                            AppPreferences.setStillThereEpisodes(context, count)
                        }) {
                            PillChip("$count eps", stillThereEpisodes == count)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))

                SettingsSectionHeader("Skipping & Frame Rate")

                ToggleRow(
                    label = "Auto-skip Intros",
                    description = "Skip intros and recaps the moment they start, using IntroDB timestamps. Off keeps the SKIP INTRO button, so the choice stays yours. In the External player engine, which has nowhere to draw that button, the segment is skipped before the hand-off instead.",
                    checked = autoSkipIntro,
                    onToggle = {
                        autoSkipIntro = it
                        AppPreferences.setAutoSkipIntro(context, it)
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                ToggleRow(
                    label = "Auto-skip Credits",
                    description = "Jump past end credits and stop on the post-credits scene when the title has one. A low-confidence timestamp is never skipped on its own. In the External player engine the credits are skipped before the hand-off instead of during playback.",
                    checked = autoSkipCredits,
                    onToggle = {
                        autoSkipCredits = it
                        AppPreferences.setAutoSkipCredits(context, it)
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                ToggleRow(
                    label = "Match Content Frame Rate",
                    description = "Ask the TV to switch its refresh rate to match the title \u2014 24 Hz for film, 50 Hz for 25 fps \u2014 which is what removes the stutter from slow pans. The app tells the TV the rate of what it is playing and the TV picks the mode; a switch that blanks the screen for a moment also needs the TV's own display setting to allow it. The panel is put back when playback ends. A TV that reports only the mode it is already in has nothing to switch to. Set per device, so it does not follow your profile to another TV.",
                    checked = matchFrameRate,
                    onToggle = {
                        matchFrameRate = it
                        AppPreferences.setMatchFrameRate(context, it)
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                FrameRateDiagnosticRow()

                }

                if (selectedPane == SettingsPane.INTERFACE) {
                SettingsSectionHeader("Notifications", first = true)

                ToggleRow(
                    label = "New Episode Notifications",
                    description = if (!notificationsAllowed) {
                        "Alerts when a new episode of a show you watch has aired. " +
                            "Blocked by the system — allow notifications for KBStream in your " +
                            "device settings."
                    } else {
                        "Alerts when a new episode of a show you watch has aired. " +
                            "Checked twice a day for the profile in use; each episode is " +
                            "announced once."
                    },
                    checked = newEpisodeNotifications,
                    onToggle = { enabled ->
                        newEpisodeNotifications = enabled
                        AppPreferences.setNewEpisodeNotifications(context, enabled)
                        // The pref alone changes nothing on its own: switching
                        // off has to cancel the scheduled round, and switching
                        // on should alert about an episode that already aired
                        // rather than waiting up to 12h for the next window.
                        runCatching {
                            com.kennyb1201.kbstream.work.NewEpisodeWorker.syncScheduleForPrefs(
                                context,
                                runImmediate = enabled
                            )
                        }
                        // Android 13+ needs the runtime grant; asking on the
                        // enabling tap is the only moment it makes sense.
                        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            runCatching {
                                notificationPermissionLauncher.launch(
                                    Manifest.permission.POST_NOTIFICATIONS
                                )
                            }
                        }
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                ToggleRow(
                    label = "Live TV Reminder Alerts",
                    description = if (!notificationsAllowed) {
                        "Alerts when a program you set a reminder for starts. " +
                            "Blocked by the system — allow notifications for KBStream in your " +
                            "device settings."
                    } else {
                        "Alerts when a program you set a reminder for starts (REMIND ME in " +
                            "the guide). Without this you'd only see the reminder if the " +
                            "guide happened to be open at that moment."
                    },
                    checked = liveReminderNotifications,
                    onToggle = { enabled ->
                        liveReminderNotifications = enabled
                        AppPreferences.setLiveReminderNotifications(context, enabled)
                        // Both halves of the toggle are real work: off cancels
                        // every armed alert, on re-arms the reminders that
                        // haven't started yet.
                        runCatching {
                            com.kennyb1201.kbstream.work.ReminderWorker.syncSchedule(
                                context,
                                enabled
                            )
                        }
                        if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            runCatching {
                                notificationPermissionLauncher.launch(
                                    Manifest.permission.POST_NOTIFICATIONS
                                )
                            }
                        }
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                SettingsSectionHeader("Theme")

                AccentColorPicker()

                Spacer(modifier = Modifier.height(8.dp))

                ToggleRow(
                    label = "AMOLED Black",
                    description = "True-black backgrounds for OLED/AMOLED screens — pixels turn fully off, saving power and boosting contrast. Applies instantly.",
                    checked = amoledBlack,
                    onToggle = {
                        amoledBlack = it
                        AppPreferences.setAmoledBlack(context, it)
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                ToggleRow(
                    label = "Pure Black Surface",
                    description = "Also make cards, panels and containers pure black. Pixels turn fully off, saving power and boosting contrast. Applies instantly. Requires AMOLED Black.",
                    checked = pureBlackSurface && amoledBlack,
                    checkedOverride = if (amoledBlack) null else false,
                    onToggle = {
                        pureBlackSurface = it
                        AppPreferences.setPureBlackSurface(context, it)
                    },
                    enabled = amoledBlack
                )

                Spacer(modifier = Modifier.height(8.dp))

                SettingsSectionHeader("Hero & Clock")

                ToggleRow(
                    label = "Hero Trailer Autoplay",
                    description = "Auto-play trailers on the Home hero after a short pause. Turn off to keep the static backdrop.",
                    checked = heroTrailerAutoplay,
                    onToggle = {
                        heroTrailerAutoplay = it
                        AppPreferences.setHeroTrailerAutoplay(context, it)
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                HeroTrailerDelayRow(
                    selectedMs = heroTrailerDelay,
                    enabled = heroTrailerAutoplay,
                    chip = { text, isSelected -> PillChip(text, isSelected) },
                    onPick = { ms ->
                        heroTrailerDelay = ms
                        AppPreferences.setHeroTrailerDelayMs(context, ms)
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                ToggleRow(
                    label = "Mute Hero Trailers",
                    description = "Play hero trailers silently (no background audio). Applies the next time a hero trailer starts.",
                    checked = heroTrailerMuted,
                    onToggle = {
                        heroTrailerMuted = it
                        AppPreferences.setHeroTrailerMuted(context, it)
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                ToggleRow(
                    label = "24-Hour Clock",
                    description = "Show the player clock in 24-hour format (off = 12-hour AM/PM).",
                    checked = use24hClock,
                    onToggle = {
                        use24hClock = it
                        AppPreferences.setUse24HourClock(context, it)
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                SettingsSectionHeader("Home Rails")

                ToggleRow(
                    label = "Show Catalog Type on Home Rails",
                    description = "Append Movies / Series / All after the rail name (e.g. \"Trending · Series\"). Applies when you return to Home.",
                    checked = railShowType,
                    onToggle = {
                        railShowType = it
                        AppPreferences.setHomeRailShowCatalogType(context, it)
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                ToggleRow(
                    label = "Show Addon Name on Home Rails",
                    description = "Prefix the rail title with its addon (e.g. \"AIOMetadata · Trending\"). Applies when you return to Home.",
                    checked = railShowAddon,
                    onToggle = {
                        railShowAddon = it
                        AppPreferences.setHomeRailShowAddonName(context, it)
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                ToggleRow(
                    label = "Show Catalog Type on Search Rails",
                    description = "Append Movies / Series / All after add-on search rail names (e.g. \"AIOMetadata · AI Search · Movie\"). Add-on search only.",
                    checked = searchRailShowType,
                    onToggle = {
                        searchRailShowType = it
                        AppPreferences.setSearchRailShowCatalogType(context, it)
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                ToggleRow(
                    label = "Show Addon Name on Search Rails",
                    description = "Prefix add-on search rail titles with their addon (e.g. \"AIOMetadata · Movies\"). Add-on search only.",
                    checked = searchRailShowAddon,
                    onToggle = {
                        searchRailShowAddon = it
                        AppPreferences.setSearchRailShowAddonName(context, it)
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                SettingsSectionHeader("Posters & Browsing")

                ToggleRow(
                    label = "Poster Titles",
                    description = "Show the title under posters. Collection folders: Rows and Grid only — Follow Home keeps its hero clean.",
                    checked = captionTitle,
                    onToggle = {
                        captionTitle = it
                        AppPreferences.setPosterCaptionTitle(context, it)
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                ToggleRow(
                    label = "Poster Years",
                    description = "Show the release year under posters. Collection folders: Rows and Grid only.",
                    checked = captionYear,
                    onToggle = {
                        captionYear = it
                        AppPreferences.setPosterCaptionYear(context, it)
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                ToggleRow(
                    label = "Poster Star Ratings",
                    description = "Show the star rating under posters. Collection folders: Rows and Grid only.",
                    checked = captionRating,
                    onToggle = {
                        captionRating = it
                        AppPreferences.setPosterCaptionRating(context, it)
                    }
                )

                Spacer(modifier = Modifier.height(12.dp))

                ToggleRow(
                    label = "Spoiler-free Episodes",
                    description = "In a series' episode list, hide the title, still and " +
                        "synopsis of episodes you have not started yet, so browsing a " +
                        "season cannot give away a plot you have not reached. Watched " +
                        "episodes and the one you are part-way through are unaffected.",
                    checked = spoilerFree,
                    onToggle = {
                        spoilerFree = it
                        AppPreferences.setSpoilerFree(context, it)
                    }
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Poster Size",
                    color = KBTextHi,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Small", "Medium", "Large").forEachIndexed { index, label ->
                        KBCard(onClick = {
                            posterSizeIdx = index
                            AppPreferences.setPosterSize(context, index.toLong())
                        }) {
                            PillChip(label, posterSizeIdx == index)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Poster Border",
                    color = KBTextHi,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = "A faint edge around every poster, so dark artwork separates on the near-black background.",
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.kennyb1201.kbstream.ui.components.PosterBorder.entries.forEachIndexed {
                            index,
                            option ->
                        KBCard(onClick = {
                            posterBorderIdx = index
                            AppPreferences.setPosterBorderStrength(context, index)
                        }) {
                            PillChip(option.label, posterBorderIdx == index)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "Poster Edges",
                    color = KBTextHi,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = "The corner shape of every poster tile — squared off, the usual rounded corners, or a fully rounded pill.",
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    com.kennyb1201.kbstream.ui.components.PosterEdge.entries.forEachIndexed {
                            index,
                            option ->
                        KBCard(onClick = {
                            posterEdgeIdx = index
                            AppPreferences.setPosterEdge(context, index)
                        }) {
                            PillChip(option.label, posterEdgeIdx == index)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                ToggleRow(
                    label = "Hide Unreleased Titles",
                    description = "Hides movies that are still in theaters or not yet digitally released, across all screens.",
                    checked = railHideUpcoming,
                    onToggle = {
                        railHideUpcoming = it
                        AppPreferences.setHomeRailHideUpcoming(context, it)
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                ToggleRow(
                    label = "English-Only Browse & Discover",
                    description = "Limits the Search browse chips (genres, keywords, services, networks, studios, decades) to English-language catalogs. Turn off for anime, Spanish-language networks, and other non-English catalogs.",
                    checked = browseEnglishOnly,
                    onToggle = {
                        browseEnglishOnly = it
                        AppPreferences.setBrowseEnglishOnly(context, it)
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                ToggleRow(
                    label = "Landscape Cards on Home Rails",
                    description = "Show 16:9 backdrop cards with a small clearlogo instead of posters. Also applies to collection folder items in Rows and Follow Home (Grid keeps posters).",
                    checked = landscapeCards,
                    onToggle = {
                        landscapeCards = it
                        AppPreferences.setHomeLandscapeCards(context, it)
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                ToggleRow(
                    label = "Landscape Posters Everywhere",
                    description = "Use 16:9 backdrop cards with a small clearlogo on every poster surface — search, library, collections, browse grids and recommendation rows — not just the Home rails.",
                    checked = landscapePosters,
                    onToggle = {
                        landscapePosters = it
                        AppPreferences.setLandscapePosters(context, it)
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                ToggleRow(
                    label = "Eye Badge for In-Progress Shows",
                    description = "Show an eye marker on posters for series you've started but not finished. The completed checkmark always wins when a show is fully watched.",
                    checked = partialWatchBadge,
                    onToggle = {
                        partialWatchBadge = it
                        AppPreferences.setPosterPartialWatchBadge(context, it)
                    }
                )
                }

                if (selectedPane == SettingsPane.HIDDEN) {
                    HiddenTitlesSection()
                }

                if (selectedPane == SettingsPane.VIDEO) {
                SettingsSectionHeader("Buffering & Playback", first = true)

                Text(
                    text = "Network Buffer",
                    color = KBTextHi,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                // Buffer mode: pref 0=Balanced, 1=LowLatency, 2=Auto
                val bufferPrefToIndex = intArrayOf(1, 2, 0)  // pref 0→UI 1, pref 1→UI 2, pref 2→UI 0
                val bufferIndexToPref = intArrayOf(2, 0, 1)  // UI 0→pref 2, UI 1→pref 0, UI 2→pref 1
                val selectedBufferIndex = bufferPrefToIndex.getOrElse(bufferMode) { 0 }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Auto", "Balanced (4K-ready)", "Low Latency").forEachIndexed { index, label ->
                        KBCard(onClick = {
                            bufferMode = bufferIndexToPref[index]
                            AppPreferences.setDefaultBufferMode(context, bufferMode)
                        }) {
                            PillChip(label, selectedBufferIndex == index)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = when (bufferMode) {
                        0 -> "15s/60s buffer \u2014 best for movies & series"
                        1 -> "2.5s/10s buffer \u2014 best for live IPTV"
                        else -> "Auto-detects IPTV vs movies & series"
                    },
                    color = KBTextLo,
                    style = MaterialTheme.typography.labelSmall
                )

                Spacer(modifier = Modifier.height(10.dp))

                KBCard(
                    onClick = {
                        enableTunneling = !enableTunneling
                        AppPreferences.setEnableTunneling(context, enableTunneling)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(KBSurfaceRaised, KBShapeSmall)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Tunneled Playback",
                                color = KBTextHi,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                text = "A/V sync for HDR + AVR setups",
                                color = KBTextLo,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        Text(
                            text = if (enableTunneling) "ON" else "OFF",
                            color = if (enableTunneling) KBVoid else KBTextHi,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier
                                .background(
                                    if (enableTunneling) KBAccent else KBSurface,
                                    KBShapeSmall
                                )
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (!isFireTv) {
                    KBCard(
                        onClick = {
                            enablePip = !enablePip
                            AppPreferences.setEnablePip(context, enablePip)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(KBSurfaceRaised, KBShapeSmall)
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Picture-in-Picture",
                                    color = KBTextHi,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = "Auto-enter PiP when pressing Home",
                                    color = KBTextLo,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            Text(
                                text = if (enablePip) "ON" else "OFF",
                                color = if (enablePip) KBVoid else KBTextHi,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier
                                    .background(
                                        if (enablePip) KBAccent else KBSurface,
                                        KBShapeSmall
                                    )
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                SettingsSectionHeader("Picture")

                Text(
                    text = "Default Aspect Ratio",
                    color = KBTextHi,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Fit", "Zoom", "Fill", "16:9", "4:3").forEachIndexed { index, label ->
                        KBCard(onClick = {
                            aspectRatio = index
                            AppPreferences.setDefaultAspectRatio(context, index)
                        }) {
                            PillChip(label, aspectRatio == index)
                        }
                    }
                }
                }

                if (selectedPane == SettingsPane.LANGUAGE) {
                SettingsSectionHeader("Audio", first = true)

                Text(
                    text = "Preferred Audio Language",
                    color = KBTextHi,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                LanguageChipGrid(
                    selected = preferredAudioLang,
                    onSelect = { code ->
                        preferredAudioLang = code
                        AppPreferences.setPreferredAudioLanguage(context, code)
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                SettingsSectionHeader("Subtitles")

                Text(
                    text = "Preferred Subtitle Language",
                    color = KBTextHi,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                LanguageChipGrid(
                    selected = preferredSubtitleLang,
                    onSelect = { code ->
                        preferredSubtitleLang = code
                        AppPreferences.setPreferredSubtitleLanguage(context, code)
                    }
                )
                }

                if (selectedPane == SettingsPane.SUBTITLES) {
                SettingsSectionHeader("Behavior", first = true)

                Text(
                    text = "Mode",
                    color = KBTextHi,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SubtitleModeRules.OPTIONS.forEach { (label, value) ->
                        KBCard(onClick = {
                            subtitleMode = value
                            AppPreferences.setSubtitleMode(context, value)
                        }) {
                            PillChip(label, subtitleMode == value)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Off never shows subtitles on its own; Forced only shows " +
                        "foreign dialogue and signs when the file marks them; On follows " +
                        "the preferred language below.",
                    color = KBTextLo,
                    style = MaterialTheme.typography.labelSmall
                )

                Spacer(modifier = Modifier.height(8.dp))

                SettingsSectionHeader("Appearance")

                Text(
                    text = "Default Size",
                    color = KBTextHi,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Small", "Normal", "Large").forEachIndexed { index, label ->
                        KBCard(onClick = {
                            subtitleSize = index
                            AppPreferences.setDefaultSubtitleSize(context, index)
                        }) {
                            PillChip(label, subtitleSize == index)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Default Background",
                    color = KBTextHi,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("None", "Semi", "Solid", "Text").forEachIndexed { index, label ->
                        KBCard(onClick = {
                            subtitleBg = index
                            AppPreferences.setDefaultSubtitleBackground(context, index)
                        }) {
                            PillChip(label, subtitleBg == index)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Position",
                    color = KBTextHi,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Low", "Mid", "High").forEachIndexed { index, label ->
                        KBCard(onClick = {
                            subtitlePosition = index
                            AppPreferences.setDefaultSubtitlePosition(context, index)
                        }) {
                            PillChip(label, subtitlePosition == index)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Lifts captions above letterbox bars or burned-in signage.",
                    color = KBTextLo,
                    style = MaterialTheme.typography.labelSmall
                )
                }

                if (selectedPane == SettingsPane.SYNC) {
                    // Anchored as a whole pane, not as a row: the sync pane has
                    // no setting of its own, so its index entry is the pane.
                    SyncHealthSection(
                        modifier = Modifier.searchAnchor(
                            SettingsSearchIndex.entryFor("${SECTION_KEY_PREFIX}sync-health")
                        )
                    )
                }

                if (selectedPane == SettingsPane.ABOUT) {
                    AboutSection(
                        modifier = Modifier.searchAnchor(
                            SettingsSearchIndex.entryFor("${SECTION_KEY_PREFIX}about-updates")
                        )
                    )
                }

        }
    }


    if (showExternalPlayerPicker) {
        SettingsExternalPlayerDialog(
            players = externalPlayers,
            selectedPackage = externalPackage,
            askEachTime = externalAsk,
            onPick = { player ->
                externalPackage = player.packageName
                externalAsk = false
                AppPreferences.setExternalPlayer(context, player.packageName, player.label)
                AppPreferences.setExternalPlayerAsk(context, false)
                showExternalPlayerPicker = false
            },
            onAskEachTime = {
                externalAsk = true
                AppPreferences.setExternalPlayerAsk(context, true)
                showExternalPlayerPicker = false
            },
            onDismiss = { showExternalPlayerPicker = false }
        )
    }

    if (showClearHistoryConfirm) {
        SettingsClearHistoryDialog(
            onDismiss = { showClearHistoryConfirm = false },
            onConfirm = {
                showClearHistoryConfirm = false
                if (clearingHistory) return@SettingsClearHistoryDialog
                clearingHistory = true
                backupScope.launch {
                    // Plain runCatching, deliberately: `clearingHistory = false`
                    // below has to run even when this coroutine is canceled, or
                    // the confirm button stays disabled for the rest of the
                    // session. A rethrow here would skip that reset.
                    runCatching {
                        // Clear the CLOUD half FIRST, and wait for it. A wipe
                        // that ran after the local clear left a window where
                        // the cloud still held the rows, and the pull merge (a
                        // remote row beats a DELETED local row, which has no
                        // timestamp) restored every resume bar + card and
                        // completed marker - which is why a show came back
                        // with its progress bar after a reset.
                        SupabaseSync.clearWatchStateForActiveProfile()
                        WatchHistoryRepository(context).clearAll()
                        WatchedStatusRepository(context)
                            .clearLocalWatchState(clearSimklAuth = false)
                    }
                    clearingHistory = false
                    historyClearedAt = System.currentTimeMillis()
                }
            }
        )
    }

}

// ── Two-pane scaffolding ────────────────────────────────────────

@Composable
private fun SettingsNavRail(
    selected: SettingsPane,
    onSelect: (SettingsPane) -> Unit,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    searchFocus: FocusRequester,
    onPickResult: (SettingSearchEntry) -> Unit
) {
    // TV entry point: focus lands on the first rail item once. Selecting a
    // pane must NOT move focus — the user stays on the rail to keep browsing.
    val firstItemFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        firstItemFocus.requestFocus()
    }
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(296.dp)
            .background(KBSurface)
            .padding(horizontal = 20.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = "SETTINGS",
            color = KBAccent,
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // Search filters the ROW list, not the pane list: the rail is only ten
        // entries, and "which pane is the frame-rate switch in" is the question
        // this answers. With a query up the grouped rail is replaced by flat
        // results grouped by pane. An empty query renders the rail exactly as
        // it always did - the field is the only thing added.
        KBTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = "Search settings",
            modifier = Modifier.fillMaxWidth(),
            // A picked result hands focus back here (see the jump), so the
            // field needs a requester of its own.
            focusRequester = searchFocus,
            leading = {
                Icon(
                    imageVector = Icons.Filled.Search,
                    contentDescription = null,
                    tint = KBTextLo,
                    modifier = Modifier.size(18.dp)
                )
            },
            // Manual IME, like the setup panel's fields: focus alone must not
            // raise the full-screen TV keyboard over the results. OK starts
            // editing, Done ends it and keeps focus so DOWN reaches the list.
            openKeyboardOnFocus = false
        )
        Spacer(modifier = Modifier.height(6.dp))

        if (searchQuery.isNotBlank()) {
            SettingsSearchResults(
                results = remember(searchQuery) { SettingsSearchIndex.search(searchQuery) },
                onPick = onPickResult
            )
            return@Column
        }

        // Group heading drawn inline, before the first row of each group. One
        // flat pass over the panes leaves the row layout and its braces below
        // untouched, and because the panes are declared group-major each
        // heading prints exactly once. The rail already scrolls (see the
        // wrapper in SettingsScreen), so the extra rows stay reachable.
        var drawnGroup: SettingsGroup? = null
        SettingsPane.entries.forEach { pane ->
            if (pane.group != drawnGroup) {
                drawnGroup = pane.group
                Text(
                    text = pane.group.label.uppercase(),
                    color = KBTextLo,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 2.dp)
                )
            }
            val selectedHere = pane == selected
            KBCard(
                onClick = { onSelect(pane) },
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        // The top row is where the TV entry focus lands; every
                        // other row is one D-pad press away.
                        if (pane == SettingsPane.entries.first()) {
                            Modifier.focusRequester(firstItemFocus)
                        } else {
                            Modifier
                        }
                    )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            when {
                                selectedHere -> KBAccent.copy(alpha = 0.22f)
                                else -> androidx.compose.ui.graphics.Color.Transparent
                            },
                            KBShapeChip
                        )
                        .border(
                            width = if (selectedHere) 1.dp else 0.dp,
                            color = if (selectedHere) KBAccent else androidx.compose.ui.graphics.Color.Transparent,
                            shape = KBShapeChip
                        )
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = pane.icon,
                            contentDescription = null,
                            tint = if (selectedHere) KBAccent else KBTextLo,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = pane.label,
                            color = if (selectedHere) KBAccent else KBTextHi,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    }
                    // Read-only status dot / count. Never focusable, so the
                    // D-pad order is exactly what it was.
                    SettingsRailBadge(pane)
                    if (selectedHere) {
                        Icon(
                            imageVector = Icons.Filled.ChevronRight,
                            contentDescription = null,
                            tint = KBAccent
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsContentHost(
    title: String,
    // What the pane holds, in the same words the rail used to sell it: the
    // pane then reads as a continuation of the rail rather than a new page.
    blurb: String,
    // False for the one pane that is itself a lazy scroll container (Hidden
    // Titles). A verticalScroll hands its content an unbounded height, and a
    // LazyColumn measured against that throws, so that pane scrolls itself
    // and this host only lays it out.
    scrollable: Boolean = true,
    // The row a search jump is flashing, so the pane's rows can highlight it
    // without every call site being handed the state (see LocalFlashingSetting).
    flashing: SettingSearchEntry? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    // No focus stealing here: switching panes keeps focus on the rail.
    val scroll = rememberScrollState()
    LaunchedEffect(title) {
        scroll.scrollTo(0)
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .then(if (scrollable) Modifier.verticalScroll(scroll) else Modifier)
            .padding(start = 40.dp, end = 64.dp, top = 32.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Deliberately NOT KBPageTitle, and deliberately not the 28sp every
        // top-level screen now uses. Settings is the app's only master-detail
        // screen: the rail draws the SCREEN's name above this ("SETTINGS" at
        // headlineMedium / 26sp), so a 28sp pane title would end up larger
        // than the name of the screen it belongs to. headlineSmall / 22sp is
        // what keeps "SETTINGS > About" reading in the right order. Library,
        // Add-ons and Live TV have no parent name on screen, so the same
        // argument does not apply to them.
        Text(
            text = title.uppercase(),
            color = KBAccent,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 2.dp)
        )
        Text(
            text = blurb,
            color = KBTextLo,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 14.dp)
        )
        CompositionLocalProvider(LocalFlashingSetting provides flashing) {
            content()
        }
    }
}

/**
 * The flat result list shown while the rail's search box has a query.
 *
 * Grouped by pane with a small pane label, because which pane a setting is in
 * is exactly what the viewer did not know. Selecting a row navigates; it can
 * never change anything.
 */
@Composable
private fun SettingsSearchResults(
    results: List<SettingSearchEntry>,
    onPick: (SettingSearchEntry) -> Unit
) {
    if (results.isEmpty()) {
        Text(
            text = "No settings match",
            color = KBTextLo,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 14.dp, top = 4.dp)
        )
        return
    }
    var drawnPane: SettingsPane? = null
    results.take(SETTINGS_SEARCH_MAX_RESULTS).forEach { entry ->
        if (entry.pane != drawnPane) {
            drawnPane = entry.pane
            Text(
                text = entry.pane.label.uppercase(),
                color = KBTextLo,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 2.dp)
            )
        }
        KBCard(
            onClick = { onPick(entry) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(KBSurfaceRaised, KBShapeSmall)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = entry.label,
                    color = KBTextHi,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = entry.pane.label,
                    color = KBTextLo,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
    }
}

/**
 * A rail row's status badge: an accent dot, a warning dot, or a count.
 *
 * Indicators only - nothing here is focusable, so D-pad order is untouched and
 * clicking still just selects the pane. Each badge collects the same StateFlow
 * the pane itself reads, so there is no second source of truth to drift.
 */
@Composable
private fun SettingsRailBadge(pane: SettingsPane) {
    when (pane) {
        SettingsPane.ABOUT -> {
            val state by AppUpdater.state.collectAsStateWithLifecycle()
            val pending = state is AppUpdater.UpdateState.Available ||
                state is AppUpdater.UpdateState.Downloading ||
                state is AppUpdater.UpdateState.ReadyToInstall
            if (pending) RailDot()
        }
        SettingsPane.SYNC -> {
            val syncing by SupabaseSync.isSyncing.collectAsStateWithLifecycle()
            val auth by SupabaseSync.authState.collectAsStateWithLifecycle()
            val pendingUploads by SupabaseSync.pendingOutboxCount.collectAsStateWithLifecycle()
            // A failed or signed-out session with rows still queued is the one
            // state a viewer cannot see anywhere else on this screen.
            val stalled = auth is SupabaseSync.AuthState.Error ||
                (auth is SupabaseSync.AuthState.SignedOut && pendingUploads > 0)
            when {
                syncing -> RailDot()
                stalled -> RailDot(color = KBDanger)
                else -> Unit
            }
        }
        SettingsPane.INTEGRATIONS -> {
            val context = LocalContext.current
            // The same singleton the Addons screen and Home share, so the count
            // is live rather than read once.
            val addonManager = remember(context) {
                com.kennyb1201.kbstream.data.addon.AddonManager
                    .getInstance(context.applicationContext)
            }
            val addons by addonManager.installedAddons.collectAsStateWithLifecycle()
            if (addons.isNotEmpty()) {
                Text(
                    text = addons.size.toString(),
                    color = KBTextLo,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }
        else -> Unit
    }
}

/** The 8dp status dot a rail badge uses, or nothing at all. */
@Composable
private fun RailDot(color: Color = KBAccent) {
    Box(
        modifier = Modifier
            .padding(start = 8.dp)
            .size(8.dp)
            .background(color, CircleShape)
    )
}

/**
 * A pane's group heading: the visual break between clusters of related rows.
 *
 * Presentational only. It carries no preference, is not focusable, and is not
 * in [SettingsSearchIndex] - a heading can never be a search result, a jump
 * target, or something the viewer can change by mistake. Uppercase at
 * [KBAccent] and small, so it reads as a caption over the rows rather than as
 * one of them. [first] drops the top gap for a pane's opening heading, where
 * the pane title and blurb above already supply it.
 */
@Composable
private fun ColumnScope.SettingsSectionHeader(text: String, first: Boolean = false) {
    Text(
        text = text.uppercase(),
        color = KBAccent,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(
            start = 4.dp,
            top = if (first) 0.dp else 16.dp,
            bottom = 2.dp
        )
    )
}

@Composable
private fun AboutSection(modifier: Modifier = Modifier) {
    // About pane: build identity + the in-app update flow. Updates live here
    // (not in Integrations) — it's app plumbing, not an integration.
    //
    // The version line is just the version. The run number and commit are for
    // bug reports (Diagnostics, Sentry), not for this pane: "0.5" is a version
    // a viewer reads, "0.5 (build 4631, 1a2b3c4)" is a log line.
    val versionLine = "KBStream ${com.kennyb1201.kbstream.BuildConfig.VERSION_NAME}"
    // The most recent release's changelog, on the same GitHub feed the update
    // row reads. Shown whichever build is current, so "what changed last" is
    // visible without waiting for an update to exist.
    val changelog by AppUpdater.latestChangelog.collectAsStateWithLifecycle()
    Column(modifier = modifier) {
        KBCard(onClick = {}, modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(KBSurfaceRaised, KBShapeSmall)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(
                    text = "VERSION",
                    color = KBTextLo,
                    style = MaterialTheme.typography.labelSmall
                )
                Text(
                    text = versionLine,
                    color = KBTextHi,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        UpdateRow()
        if (changelog.isNotBlank()) {
            Spacer(modifier = Modifier.height(10.dp))
            KBCard(onClick = {}, modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(KBSurfaceRaised, KBShapeSmall)
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = "WHAT'S NEW",
                        color = KBAccent,
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(
                        text = changelog,
                        color = KBTextHi,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "Updates are published with every build. Check manually above — " +
                "KBStream also checks on launch every 12 hours and installs " +
                "without leaving the app.",
            color = KBTextLo.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

/**
 * Sync health: what the sync layer is actually doing, instead of "did that
 * change make it to the other TV?". Pull and push are reported separately
 * ("nothing arrives" and "nothing uploads" are different failures), plus the
 * outbox backlog, realtime channel state, and a one-shot force resync.
 */
@Composable
private fun SyncHealthSection(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val sync = com.kennyb1201.kbstream.data.sync.SupabaseSync
    val profileManager = com.kennyb1201.kbstream.data.sync.ProfileManager

    val authState by sync.authState.collectAsStateWithLifecycle()
    val lastPull by sync.lastPullAtMs.collectAsStateWithLifecycle()
    val lastPush by sync.lastPushAtMs.collectAsStateWithLifecycle()
    val pendingUploads by sync.pendingOutboxCount.collectAsStateWithLifecycle()
    val realtime by sync.realtimeStatus.collectAsStateWithLifecycle()
    val channels by sync.realtimeChannelCount.collectAsStateWithLifecycle()
    val syncing by sync.isSyncing.collectAsStateWithLifecycle()
    val activeProfile by profileManager.activeProfile.collectAsStateWithLifecycle()
    val profiles by profileManager.profiles.collectAsStateWithLifecycle()

    // Local row counts are read on demand (Room suspends off the main thread
    // itself) and refreshed whenever a pull or push completes.
    var localRows by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(lastPull, lastPush) {
        localRows = runCatching {
            val db = com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
                .getInstanceScoped(context)
            "watched cache ${db.watchedStatusDao().getAll().size} · " +
                "history ${db.watchHistoryDao().getAll().size}"
        }.getOrNull()
    }

    val account = when (val state = authState) {
        is com.kennyb1201.kbstream.data.sync.SupabaseSync.AuthState.SignedIn -> state.email
        is com.kennyb1201.kbstream.data.sync.SupabaseSync.AuthState.SigningIn -> "signing in…"
        is com.kennyb1201.kbstream.data.sync.SupabaseSync.AuthState.Error -> state.message
        else -> "signed out"
    }

    Column(modifier = modifier) {
        Text(
            text = "SYNC HEALTH",
            color = KBAccent,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        SyncStatRow("ACCOUNT", account)
        SyncStatRow(
            "PROFILE",
            (activeProfile?.name ?: "none") +
                (if (profiles.size > 1) " (${profiles.size} profiles)" else "")
        )
        SyncStatRow("LAST PULL", syncRelativeTime(lastPull))
        SyncStatRow("LAST PUSH", syncRelativeTime(lastPush))
        SyncStatRow(
            "PENDING UPLOADS",
            if (pendingUploads == 0) "none — everything uploaded" else "$pendingUploads row(s)"
        )
        SyncStatRow("REALTIME", "$realtime ($channels channel(s))")
        SyncStatRow("LOCAL DATA", localRows ?: "reading…")
        SyncStatRow("CLEANUP", sync.poisonSweepStatus(context))

        Spacer(modifier = Modifier.height(10.dp))
        KBCard(
            onClick = {
                if (!syncing) sync.forceFullResync(context)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(KBSurfaceRaised, KBShapeSmall)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(
                    text = if (syncing) "SYNCING…" else "FORCE FULL RESYNC",
                    color = if (syncing) KBTextLo else KBAccent,
                    style = MaterialTheme.typography.labelSmall
                )
                Text(
                    text = "Uploads pending changes, then pulls the account's " +
                        "history, watched marks and settings for this profile.",
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        // Deterministic escape hatch for the eye badges. The automatic cleanup
        // can only delete rows it can PROVE were copied between profiles, which
        // leaves the phantom whose twin was already removed on another device;
        // this reset needs no proof — it clears this profile's in-progress
        // state outright. Two taps, no dialog, because the description has to
        // be readable before the press that does it.
        var confirmClearBadges by remember { mutableStateOf(false) }
        var clearBadgesStatus by remember { mutableStateOf<String?>(null) }
        KBCard(
            onClick = {
                if (confirmClearBadges) {
                    confirmClearBadges = false
                    clearBadgesStatus = "clearing…"
                    sync.clearInProgressForActiveProfile(context) { outcome ->
                        clearBadgesStatus = outcome
                    }
                } else {
                    confirmClearBadges = true
                    clearBadgesStatus = null
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(KBSurfaceRaised, KBShapeSmall)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(
                    text = if (confirmClearBadges) {
                        "TAP AGAIN TO CLEAR EYE BADGES"
                    } else {
                        "CLEAR EYE BADGES · ${activeProfile?.name ?: "this profile"}"
                    },
                    color = if (confirmClearBadges) KBAccent else KBAccent.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.labelSmall
                )
                Text(
                    text = if (confirmClearBadges) {
                        "Deletes this profile's started-but-unfinished (eye) badges " +
                            "and their resume positions — here and on the other " +
                            "devices. Completed history and checkmarks are kept. A " +
                            "show still in progress in this profile's own " +
                            "Simkl/MDBList comes back, because that badge is real."
                    } else {
                        clearBadgesStatus?.let { "Last run: $it" }
                            ?: "Removes the started-but-unfinished (eye) badges for " +
                            "this profile only. Use this when a badge survives the " +
                            "cleanup above."
                    },
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        val diagnosticsScope = rememberCoroutineScope()
        var diagnosticsStatus by remember { mutableStateOf<String?>(null) }
        KBCard(
            onClick = {
                diagnosticsScope.launch {
                    runCatchingCancellable {
                        val report = com.kennyb1201.kbstream.data.reporting.Diagnostics.build(context)
                        com.kennyb1201.kbstream.data.reporting.Diagnostics
                            .copyToClipboard(context, report)
                        diagnosticsStatus = "copied \u00b7 also logged as DIAGNOSTICS"
                    }.onFailure {
                        diagnosticsStatus = "failed: ${it.message}"
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(KBSurfaceRaised, KBShapeSmall)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(
                    text = "COPY DIAGNOSTICS",
                    color = KBAccent,
                    style = MaterialTheme.typography.labelSmall
                )
                Text(
                    text = diagnosticsStatus
                        ?: "Build, device, account, sync health, pending uploads " +
                        "and this session's caught errors — copied to the clipboard " +
                        "and written to logcat (tag DIAGNOSTICS).",
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = "Pull timestamps update when remote changes are merged; push " +
                "when local writes reach the cloud. \"Realtime\" is the live " +
                "change channel — if it reads stopped while signed in, use " +
                "Force full resync (remote changes still arrive on the next pull).",
            color = KBTextLo.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

@Composable
private fun SyncStatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // The LABEL keeps its intrinsic width on a single line and the VALUE
        // takes the weight. The order matters: in a Row the un-weighted
        // children are measured first with the whole row available, so a value
        // that has to wrap claims the full width and starves the weighted
        // label down to a one-glyph column. That is what turned "CLEANUP"
        // into letters stacked vertically over its own value — its sweep
        // detail ("completed - history=0 overrides=0 - ...") is the only sync
        // value long enough to wrap. Same failure as the add-on header and the
        // Settings chip grids.
        Text(
            text = label,
            color = KBTextLo,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            color = KBTextHi,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End
        )
    }
}

private fun syncRelativeTime(timestampMs: Long): String =
    if (timestampMs <= 0L) {
        "never"
    } else {
        android.text.format.DateUtils.getRelativeTimeSpanString(
            timestampMs,
            System.currentTimeMillis(),
            android.text.format.DateUtils.MINUTE_IN_MILLIS
        ).toString()
    }

@Composable
private fun UpdateRow() {
    // In-app update: checks GitHub releases, offers download + install.
    // State comes from AppUpdater so a launch-time auto-check is reflected
    // here too (row shows "Update available").
    val context = LocalContext.current
    val updateState by AppUpdater.state.collectAsStateWithLifecycle()
    // What the release says changed, from the release body the publish
    // workflow writes into it (see AppUpdater.releaseNotes). Blank for a
    // release with no body, which is why every use below is guarded.
    val notes = (updateState as? AppUpdater.UpdateState.Available)?.notes.orEmpty()
    val label = when (val s = updateState) {
        is AppUpdater.UpdateState.Available -> "Update available — ${s.versionName}"
        is AppUpdater.UpdateState.Downloading ->
            "Downloading update… ${s.percent}%"
        is AppUpdater.UpdateState.ReadyToInstall ->
            "Update ready — installing…"
        is AppUpdater.UpdateState.Checking -> "Checking for updates…"
        is AppUpdater.UpdateState.Updated -> "Updated to ${s.versionName}"
        is AppUpdater.UpdateState.Failed -> "Update check failed — tap to retry"
        AppUpdater.UpdateState.UpToDate -> "You're up to date — check again"
        AppUpdater.UpdateState.Idle -> "Check for updates"
    }
    val description = when (val s = updateState) {
        is AppUpdater.UpdateState.Available ->
            "New KBStream ${s.versionName} is available — select to download and install"
        is AppUpdater.UpdateState.Downloading ->
            "Fetching the new APK — the app relaunches when done"
        is AppUpdater.UpdateState.ReadyToInstall ->
            "Handing the file to the system installer…"
        is AppUpdater.UpdateState.Updated ->
            "The last install finished — this is the new build"
        is AppUpdater.UpdateState.Failed ->
            s.message
        else -> "KBStream updates are published with each build"
    }
    Column {
        KBCard(
            onClick = {
                when (val s = updateState) {
                    is AppUpdater.UpdateState.Available ->
                        AppUpdater.downloadAndInstall(context, s)
                    is AppUpdater.UpdateState.Downloading,
                    is AppUpdater.UpdateState.Checking,
                    is AppUpdater.UpdateState.Updated,
                    is AppUpdater.UpdateState.ReadyToInstall -> Unit
                    else -> AppUpdater.checkForUpdate(context)
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(KBSurfaceRaised, KBShapeSmall)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = label,
                        color = if (updateState is AppUpdater.UpdateState.Available) KBAccent else KBTextHi,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = description,
                        color = KBTextLo,
                        style = MaterialTheme.typography.labelSmall
                    )
                    if (notes.isNotBlank()) {
                        Text(
                            text = notes,
                            color = KBTextLo,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 6,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
                if (updateState is AppUpdater.UpdateState.Available) {
                    Icon(
                        imageVector = Icons.Filled.ChevronRight,
                        contentDescription = null,
                        tint = KBAccent,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsClearHistoryDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
            Column(
                modifier = Modifier
                    .width(560.dp)
                    .background(KBSurface, KBShapePanel)
                    .border(1.dp, KBAccent.copy(alpha = 0.38f), KBShapePanel)
                    .padding(horizontal = 22.dp, vertical = 20.dp)
            ) {
                Text(
                    text = "CLEAR CONTINUE WATCHING?",
                    color = KBAccent,
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = "This erases every resume position and watched marker " +
                        "on this device. It cannot be undone.",
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(top = 18.dp)
                ) {
                    KBCard(onClick = onConfirm) {
                        Text(
                            text = "CLEAR",
                            color = KBAccent,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                    }
                    KBCard(onClick = onDismiss) {
                        Text(
                            text = "CANCEL",
                            color = KBTextLo,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                        )
                    }
                }
            }
        }
}

// ── Helper composables ──────────────────────────────────────────

/**
 * A labeled option row for the audio-tuning settings: description under the
 * label, then the pills, four per line.
 */
/**
 * Which installed app the External engine hands a title to.
 *
 * Raised the moment that engine is picked while more than one candidate is
 * installed, because choosing the engine is choosing a player and a viewer who
 * never comes back to this section should not end up on whichever app the probe
 * found first. Every player the device exposes is listed - this app can only
 * see the apps its manifest <queries> declares - plus the system's own chooser,
 * which is the honest answer for someone who uses more than one.
 */
@Composable
private fun SettingsExternalPlayerDialog(
    players: List<ExternalPlayer.Installed>,
    selectedPackage: String?,
    askEachTime: Boolean,
    onPick: (ExternalPlayer.Installed) -> Unit,
    onAskEachTime: () -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(640.dp)
                .background(KBSurface, KBShapePanel)
                .border(1.dp, KBAccent.copy(alpha = 0.38f), KBShapePanel)
                .padding(horizontal = 22.dp, vertical = 20.dp)
        ) {
            Text(
                text = "PLAY TITLES IN WHICH APP?",
                color = KBAccent,
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = "Every video player installed on this device. Your pick is remembered " +
                    "for every title - no prompt each time - and KBStream keeps the session " +
                    "either way: watch history, scrobbling and the end-of-episode cards all " +
                    "stay here. Change it any time under Playback, or pick Ask every time to " +
                    "be shown the system chooser instead.",
                color = KBTextLo,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 16.dp)
            ) {
                players.forEach { player ->
                    KBCard(
                        onClick = { onPick(player) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        PillChip(player.label, selectedPackage == player.packageName)
                    }
                }
                KBCard(
                    onClick = onAskEachTime,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    PillChip("Ask every time (system chooser)", askEachTime)
                }
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 18.dp)
            ) {
                KBCard(onClick = onDismiss) {
                    Text(
                        text = "DONE",
                        color = KBAccent,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
}

/**
 * A labeled option row for the audio-tuning settings: description under the
 * label, then the pills, four per line.
 */
@Composable
private fun AudioTuningRow(
    label: String,
    description: String,
    options: List<Pair<String, Int>>,
    selected: Int,
    onSelect: (Int) -> Unit
) {
    Text(
        text = label,
        color = KBTextHi,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(bottom = 4.dp)
    )
    options.chunked(4).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { (name, value) ->
                KBCard(onClick = { onSelect(value) }) {
                    PillChip(name, selected == value)
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
    }
    Text(
        text = description,
        color = KBTextLo,
        style = MaterialTheme.typography.labelSmall
    )
}

/**
 * One language list, as a wrapping grid of chips.
 *
 * The list itself is [PlayerTrackBridge.LANGUAGE_OPTIONS] - the player's track
 * panel offers the same choices, and both used to spell them out separately,
 * which is how a language could end up selectable in one place and not the
 * other.
 *
 * The grid wraps because eleven chips are more than this pane is wide: the
 * pane is the screen minus the settings rail and its padding, about 560dp on a
 * 1080p set, while the chips in one line want roughly 840dp. A plain Row simply
 * ran out of width part way along and handed each chip after that whatever was
 * left - "Chinese" and the three behind it were squeezed into a column of
 * single letters, and the last of them to nothing at all. A FlowRow wraps
 * instead, so every language is on screen at its own width; there is no way to
 * scroll a row sideways with a TV remote, so nothing may be laid out off the
 * end of one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanguageChipGrid(
    selected: String,
    onSelect: (String) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PlayerTrackBridge.LANGUAGE_OPTIONS.forEach { (label, code) ->
            KBCard(onClick = { onSelect(code) }) {
                PillChip(label, selected == code)
            }
        }
    }
}

@Composable
private fun PillChip(label: String, selected: Boolean) {
    var focused by remember { mutableStateOf(false) }
    Text(
        text = label,
        color = if (selected) KBVoid else KBTextHi,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .then(
                if (focused) {
                    Modifier.background(
                        if (selected) KBAccent else KBAccent.copy(alpha = 0.3f),
                        KBShapeSmall
                    )
                } else {
                    Modifier.background(
                        if (selected) KBAccent else KBSurface,
                        KBShapeSmall
                    )
                }
            )
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}

/**
 * The global theme accent: a grid of the palette's colours, applied the moment
 * one is picked. The choice is stored - and synced - as an INDEX; the live
 * theme state is refreshed here so every screen repaints at once instead of
 * waiting for the next launch or profile switch.
 */
@Composable
private fun AccentColorPicker() {
    val context = LocalContext.current
    var selectedIndex by remember {
        mutableStateOf(AppPreferences.getAccentIndex(context, DEFAULT_ACCENT_INDEX))
    }
    Column {
        Text(
            text = "Accent Color",
            color = KBTextHi,
            style = MaterialTheme.typography.titleSmall
        )
        Text(
            text = "The highlight used across the whole app - buttons, badges, " +
                "progress bars and focus rings. Applies instantly.",
            color = KBTextLo,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.height(8.dp))
        AccentColorGrid(
            selectedIndex = selectedIndex,
            onSelect = { index ->
                selectedIndex = index
                AppPreferences.setAccentIndex(context, index)
                // The pref alone paints nothing: the live theme state is the
                // one the Compose tokens read, so it is re-mirrored here - the
                // same call the AMOLED toggles depend on.
                refreshThemeMirrors(context)
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccentColorGrid(
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        KBAccentPalette.forEachIndexed { index, entry ->
            KBCard(onClick = { onSelect(index) }) {
                AccentSwatch(
                    name = entry.name,
                    color = entry.color,
                    selected = selectedIndex == index
                )
            }
        }
    }
}

/** One palette entry: a colour chip plus its name, marked when it is the pick. */
@Composable
private fun AccentSwatch(name: String, color: Color, selected: Boolean) {
    var focused by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .onFocusChanged { focused = it.isFocused }
            .then(
                when {
                    selected -> Modifier.border(2.dp, KBAccent, KBShapeSmall)
                    focused -> Modifier.border(2.dp, KBAccent.copy(alpha = 0.5f), KBShapeSmall)
                    else -> Modifier
                }
            )
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .background(color, KBShapePill)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = name,
            color = KBTextHi,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

/** Whether [entry] is the row a search jump is highlighting right now. */
@Composable
private fun isFlashingSetting(entry: SettingSearchEntry?): Boolean {
    val flashing = LocalFlashingSetting.current
    return entry != null && flashing === entry
}

/**
 * The row's bring-into-view anchor, or nothing when it is not searchable.
 *
 * The requester lives on the index entry rather than the row so it survives
 * the pane being closed, which is exactly the case a search jump is in.
 */
private fun Modifier.searchAnchor(entry: SettingSearchEntry?): Modifier =
    if (entry == null) this else this.bringIntoViewRequester(entry.anchor)

@Composable
private fun ToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    enabled: Boolean = true,
    /** Forces the displayed state (e.g. showing OFF for a gated toggle) without touching the stored value. */
    checkedOverride: Boolean? = null,
    /**
     * An override for [SettingsSearchIndex], for a row whose displayed label
     * is not the one the index knows. An ordinary row needs nothing: it is
     * bound by its own [label], so every existing call site became searchable
     * without being touched.
     */
    searchKey: String? = null
) {
    val displayChecked = checkedOverride ?: checked
    val searchEntry = searchKey?.let { SettingsSearchIndex.entryFor(it) }
        ?: SettingsSearchIndex.entryForLabel(label)
    val flashing = isFlashingSetting(searchEntry)
    KBCard(
        onClick = { if (enabled) onToggle(!checked) },
        modifier = Modifier
            .fillMaxWidth()
            .searchAnchor(searchEntry)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (flashing) KBAccent.copy(alpha = 0.25f) else KBSurfaceRaised,
                    KBShapeSmall
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    color = if (enabled) KBTextHi else KBTextLo,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = description,
                    color = KBTextLo,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Text(
                text = if (displayChecked) "ON" else "OFF",
                color = if (displayChecked) KBVoid else KBTextHi,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .background(
                        if (displayChecked) KBAccent else KBSurface,
                        KBShapeSmall
                    )
                    .alpha(if (enabled) 1f else 0.35f)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
    }
}

/**
 * What the panel says it can do, and what the last playback asked it for.
 *
 * Read-only, and it sits under the frame-rate toggle because the whole failure
 * mode of matching is silence - a TV that refuses the request and a panel that
 * had nothing better to offer both look like nothing happening - so the numbers
 * that tell the two apart belong on the screen rather than in a logcat nobody
 * on a couch can reach. The panel line is live until something has played, and
 * then it is the panel as the last request saw it, which is the state worth
 * comparing against the answer below it.
 */
@Composable
private fun FrameRateDiagnosticRow() {
    val context = LocalContext.current
    val report by FrameRateDiagnostics.report.collectAsStateWithLifecycle()
    val livePanel = remember(context) { displayReport(context) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(KBSurfaceRaised, KBShapeSmall)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            text = "FRAME RATE DIAGNOSTICS",
            color = KBAccent,
            style = MaterialTheme.typography.labelSmall
        )
        FrameRateDiagnosticLine(
            label = "Panel",
            value = report.panel ?: livePanel ?: "this screen has no display to ask"
        )
        FrameRateDiagnosticLine(
            label = "Android path",
            value = FrameRateMatch.describePath(Build.VERSION.SDK_INT)
        )
        FrameRateDiagnosticLine(
            label = "Last request",
            value = report.request ?: "nothing has played on this device yet"
        )
        report.outcome?.let { answer ->
            FrameRateDiagnosticLine(label = "Panel's answer", value = answer)
        }
        Text(
            text = "A TV only blanks the screen to change rate when its own display " +
                "setting allows it - Google TV calls it \"Match content frame rate\", " +
                "Fire TV \"Match Original Frame Rate\". Without that, only a rate the " +
                "panel can reach without blanking is possible.",
            color = KBTextLo,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

/** One label/value pair of [FrameRateDiagnosticRow]. */
@Composable
private fun FrameRateDiagnosticLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
    ) {
        Text(
            text = label,
            color = KBTextLo,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(120.dp)
        )
        Text(
            text = value,
            color = KBTextHi,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun NavigationRow(
    label: String,
    description: String,
    onClick: () -> Unit,
    /**
     * An override for [SettingsSearchIndex], for a row whose displayed label
     * is not the one the index knows. An ordinary row needs nothing: it is
     * bound by its own [label], so every existing call site became searchable
     * without being touched.
     */
    searchKey: String? = null
) {
    val searchEntry = searchKey?.let { SettingsSearchIndex.entryFor(it) }
        ?: SettingsSearchIndex.entryForLabel(label)
    val flashing = isFlashingSetting(searchEntry)
    KBCard(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .searchAnchor(searchEntry)
    ) {
        // Same rhythm as ToggleRow: one row primitive, one shape, one padding.
        // It used to sit at 16/14 on KBShapeChip, which made the two halves of
        // the same pane look like two different screens.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (flashing) KBAccent.copy(alpha = 0.25f) else KBSurfaceRaised,
                    KBShapeSmall
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    color = KBTextHi,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = description,
                    color = KBTextLo,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = KBTextLo,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

/**
 * The way back from Hide.
 *
 * Hiding is a long-press on a poster, so the hidden title is gone from every
 * screen that could have offered that menu again - this pane is the only
 * door out of the state, and it lists the titles themselves rather than the
 * ids they were hidden under.
 */
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.HiddenTitlesSection() {
    val context = LocalContext.current
    val entries by HiddenTitles.entries.collectAsStateWithLifecycle()

    // Another screen's Hide row may have run since this pane was last built
    // (and a profile switch changes which file is read at all).
    LaunchedEffect(Unit) {
        HiddenTitles.ensureLoaded(context)
    }

    if (entries.isEmpty()) {
        Text(
            text = "Nothing is hidden. Long-press any poster and choose HIDE " +
                "to take that title off every screen.",
            color = KBTextLo,
            style = MaterialTheme.typography.bodySmall
        )
        return
    }

    Text(
        text = "${entries.size} hidden " +
            if (entries.size == 1) "title" else "titles",
        color = KBTextHi,
        style = MaterialTheme.typography.bodySmall
    )

    // Lazy, because this is the app's only list that grows with use: a heavy
    // hider has hundreds of rows here, and composing every one of them to
    // draw the six that fit is the same waste the browse submenu had. The
    // count stays above the list and the list scrolls under it, which is why
    // this pane opts out of the host's scroll (see SettingsContentHost):
    // weight(1f) can only hand the lazy column a definite height because
    // nothing here is unbounded.
    LazyColumn(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(top = 6.dp, bottom = 8.dp)
    ) {
        items(
            items = entries,
            // Entries never share a key - hide() merges on overlap - so this
            // is already unique; the timestamp is the tie-break if a corrupt
            // blob ever repeats one, because a duplicate key is a hard crash
            // in a lazy list.
            key = { entry -> entry.at.toString() + "\u0001" + entry.keys.joinToString("\u0001") }
        ) { entry ->
            KBCard(
                onClick = { HiddenTitles.unhide(context, entry.keys) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(KBSurfaceRaised, KBShapeSmall)
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = entry.title.ifBlank { "Untitled" },
                            color = KBTextHi,
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            text = if (entry.mediaType == "series") "Series" else "Movie",
                            color = KBTextLo,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                    Text(
                        text = "SHOW",
                        color = KBAccent,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(start = 12.dp)
                    )
                }
            }
        }

        // The only door out of the hidden state rides the list rather than
        // sitting under it, so it can never be pushed off the bottom of a
        // pane whose whole point is being reachable.
        item(key = "unhide-all") {
            NavigationRow(
                label = "Show everything again",
                description = "Unhide all ${entries.size} of them",
                onClick = { HiddenTitles.unhideAll(context) }
            )
        }
    }
}
