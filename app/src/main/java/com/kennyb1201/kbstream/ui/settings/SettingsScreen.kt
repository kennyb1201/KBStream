package com.kennyb1201.kbstream.ui.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.ActivityNotFoundException
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kennyb1201.kbstream.data.history.WatchHistoryRepository
import com.kennyb1201.kbstream.data.watched.WatchedStatusRepository
import com.kennyb1201.kbstream.data.update.AppUpdater
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.data.backup.BackupManager
import com.kennyb1201.kbstream.data.player.PlayerEngine
import com.kennyb1201.kbstream.data.badges.StreamBadgeEngine
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.components.KBPasteChip
import com.kennyb1201.kbstream.ui.components.KBTextField
import com.kennyb1201.kbstream.ui.player.PlayerAudioTuning
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBDanger
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

// ── Settings IA: left nav rail + right content pane ─────────────
// The old single-scroll screen stacked 7 sections ~10 screens tall on a
// TV. Two panes now: a narrow D-pad-friendly rail on the left jumps
// straight to a section; the right pane shows one section at a time.
internal enum class SettingsPane(val label: String) {
    INTEGRATIONS("Integrations"),
    PLAYBACK("Playback"),
    INTERFACE("Interface"),
    VIDEO("Video & Audio"),
    LANGUAGE("Language"),
    SUBTITLES("Subtitles"),
    DATA("Data & Backup"),
    SYNC("Sync"),
    ABOUT("About")
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
    var autoSkipIntro by remember { mutableStateOf(AppPreferences.getAutoSkipIntro(context)) }
    var autoSkipCredits by remember { mutableStateOf(AppPreferences.getAutoSkipCredits(context)) }
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
        if (notificationsAllowed && AppPreferences.getNewEpisodeNotifications(context)) {
            runCatching {
                com.kennyb1201.kbstream.work.NewEpisodeWorker.syncSchedule(
                    context,
                    enabled = true,
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
    var heroTrailerAutoplay by remember { mutableStateOf(AppPreferences.getHeroTrailerAutoplay(context)) }
    var heroTrailerMuted by remember { mutableStateOf(AppPreferences.getHeroTrailerMuted(context)) }
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
    var partialWatchBadge by remember { mutableStateOf(AppPreferences.getPosterPartialWatchBadge(context)) }
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

    // True when this device advertises no Dolby Vision decoder, so Profile 5
    // must be stripped and color-corrected on the GPU: the conversion (and its
    // color path) is then not a choice, it is the only correct picture.
    val p5ConversionRequired = AppPreferences.isP5ConversionRequired(context)
    var stripHdr10Plus by remember { mutableStateOf(AppPreferences.getStripHdr10Plus(context)) }
    var aspectRatio by remember { mutableIntStateOf(AppPreferences.getDefaultAspectRatio(context)) }
    var preferredAudioLang by remember { mutableStateOf(AppPreferences.getPreferredAudioLanguage(context)) }
    var preferredSubtitleLang by remember { mutableStateOf(AppPreferences.getPreferredSubtitleLanguage(context)) }
    var backupStatus by remember { mutableStateOf<String?>(null) }
    var mdbListKeyInput by remember { mutableStateOf(AppPreferences.getMdbListApiKey(context)) }
    var mdbListKeySaved by remember { mutableStateOf(false) }
    var subsKeyInput by remember { mutableStateOf(AppPreferences.getOpensubtitlesApiKey(context)) }
    var subsKeySaved by remember { mutableStateOf(false) }

    var selectedPane by remember { mutableStateOf(SettingsPane.INTEGRATIONS) }

    val backupScope = rememberCoroutineScope()

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            backupScope.launch {
                backupStatus = runCatching {
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
                backupStatus = runCatching {
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
                backupStatus = runCatching {
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
        SettingsNavRail(
            selected = selectedPane,
            onSelect = { selectedPane = it }
        )
        SettingsContentHost(
            title = selectedPane.label
        ) {
                if (selectedPane == SettingsPane.INTEGRATIONS) {

                    com.kennyb1201.kbstream.ui.settings.SyncSection()

                    NavigationRow(
                        label = "Profiles",
                        description = "Create, rename, and switch viewing profiles",
                        onClick = onOpenProfiles
                    )

                    // Kids Mode "Lock add-ons": hide the management entry
                    // entirely. The deep-link path is gated in MainActivity;
                    // this hides the visible door.
                    val profile = com.kennyb1201.kbstream.data.sync.ProfileManager
                        .activeProfile.value
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
                            .background(KBSurfaceRaised, RoundedCornerShape(8.dp))
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
                                            .background(KBSurface, RoundedCornerShape(6.dp))
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
                            .background(KBSurfaceRaised, RoundedCornerShape(8.dp))
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

                // ── STREAM BADGES (KB-compatible packs) ────────────────
                val badgeFocusRequester = remember { FocusRequester() }
                KBCard(
                    onClick = { badgeFocusRequester.requestFocus() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
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
                                    .background(KBSurfaceRaised, RoundedCornerShape(6.dp))
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
                                        .background(KBSurfaceRaised, RoundedCornerShape(6.dp))
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
                }

                if (selectedPane == SettingsPane.DATA) {
                NavigationRow(
                    label = "Export Backup",
                    description = "Save settings, add-ons & watched state to a file",
                    onClick = {
                        val stamp = SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US).format(Date())
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
                    label = "Audio Downmix",
                    description = when (audioDownmix) {
                        PlayerAudioTuning.DOWNMIX_STEREO ->
                            "Fold 5.1/7.1 into stereo with the centre channel (dialogue) lifted and " +
                                "the surrounds trimmed. Best for a TV's own speakers."
                        PlayerAudioTuning.DOWNMIX_SURROUND ->
                            "Keep 5.1 (7.1 folds into it). Use with an AVR or a device that really has " +
                                "six channels."
                        else ->
                            "Fold multichannel down to what this device can carry — stereo on a TV's " +
                                "own speakers, 5.1 kept on an AVR — lifting the centre channel as it " +
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

                AudioTuningRow(
                    label = "Dialogue Boost",
                    description = "Lifts voices (the centre channel, or the phantom centre of a stereo " +
                        "track) over score, ambience and explosions. Off is the untouched mix.",
                    options = PlayerAudioTuning.DIALOGUE_OPTIONS,
                    selected = audioDialogueBoost,
                    onSelect = {
                        audioDialogueBoost = it
                        AppPreferences.setAudioDialogueBoost(context, it)
                    }
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
                        AppPreferences.PLAYER_ENGINE_MPV to "MPV"
                    ).forEach { (value, label) ->
                        KBCard(onClick = {
                            playerEngine = value
                            AppPreferences.setPlayerEngine(context, value)
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
                        else ->
                            "MPV (libmpv) plays anything: its decoders fall back to software when the " +
                                "hardware ones refuse, so \"no decoder resources\" and unsupported codecs " +
                                "still play. The in-player panel (sources, Dolby Vision, audio tuning, " +
                                "remembered tracks) is ExoPlayer-only and is not available here."
                    },
                    color = KBTextLo,
                    style = MaterialTheme.typography.labelSmall
                )
                if (!PlayerEngine.isMpvAvailable()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "This device runs Android ${Build.VERSION.RELEASE}. The MPV engine " +
                            "needs Android 8 or newer, so ExoPlayer is what plays here.",
                        color = KBDanger,
                        style = MaterialTheme.typography.labelSmall
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

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

                ToggleRow(
                    label = "Stream Ranker",
                    description = "Reorder sources by quality and reliability. Off keeps the order addons return them in",
                    checked = useStreamRanker,
                    onToggle = {
                        useStreamRanker = it
                        AppPreferences.setUseStreamRanker(context, it)
                    }
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

                ToggleRow(
                    label = "Auto-skip Intros",
                    description = "Skip intros and recaps the moment they start, using IntroDB timestamps. Off keeps the SKIP INTRO button, so the choice stays yours.",
                    checked = autoSkipIntro,
                    onToggle = {
                        autoSkipIntro = it
                        AppPreferences.setAutoSkipIntro(context, it)
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                ToggleRow(
                    label = "Auto-skip Credits",
                    description = "Jump past end credits and stop on the post-credits scene when the title has one. A low-confidence timestamp is never skipped on its own.",
                    checked = autoSkipCredits,
                    onToggle = {
                        autoSkipCredits = it
                        AppPreferences.setAutoSkipCredits(context, it)
                    }
                )

                }

                if (selectedPane == SettingsPane.INTERFACE) {
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
                            com.kennyb1201.kbstream.work.NewEpisodeWorker.syncSchedule(
                                context,
                                enabled,
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
                        "Alerts when a programme you set a reminder for starts. " +
                            "Blocked by the system — allow notifications for KBStream in your " +
                            "device settings."
                    } else {
                        "Alerts when a programme you set a reminder for starts (REMIND ME in " +
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
                    label = "Eye Badge for In-Progress Shows",
                    description = "Show an eye marker on posters for series you've started but not finished. The completed checkmark always wins when a show is fully watched.",
                    checked = partialWatchBadge,
                    onToggle = {
                        partialWatchBadge = it
                        AppPreferences.setPosterPartialWatchBadge(context, it)
                    }
                )
                }

                if (selectedPane == SettingsPane.VIDEO) {
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
                            .background(KBSurfaceRaised, RoundedCornerShape(8.dp))
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
                                    RoundedCornerShape(6.dp)
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
                                .background(KBSurfaceRaised, RoundedCornerShape(8.dp))
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
                                        RoundedCornerShape(6.dp)
                                    )
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

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
                Text(
                    text = "Preferred Audio Language",
                    color = KBTextHi,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Auto" to "", "English" to "en", "Spanish" to "es", "French" to "fr", "German" to "de", "Japanese" to "ja", "Korean" to "ko", "Chinese" to "zh", "Portuguese" to "pt", "Italian" to "it", "Russian" to "ru").forEach { (label, code) ->
                        KBCard(onClick = {
                            preferredAudioLang = code
                            AppPreferences.setPreferredAudioLanguage(context, code)
                        }) {
                            PillChip(label, preferredAudioLang == code)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Preferred Subtitle Language",
                    color = KBTextHi,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Auto" to "", "English" to "en", "Spanish" to "es", "French" to "fr", "German" to "de", "Japanese" to "ja", "Korean" to "ko", "Chinese" to "zh", "Portuguese" to "pt", "Italian" to "it", "Russian" to "ru").forEach { (label, code) ->
                        KBCard(onClick = {
                            preferredSubtitleLang = code
                            AppPreferences.setPreferredSubtitleLanguage(context, code)
                        }) {
                            PillChip(label, preferredSubtitleLang == code)
                        }
                    }
                }
                }

                if (selectedPane == SettingsPane.SUBTITLES) {
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
                    SyncHealthSection()
                }

                if (selectedPane == SettingsPane.ABOUT) {
                    AboutSection()
                }

        }
    }


    if (showClearHistoryConfirm) {
        SettingsClearHistoryDialog(
            onDismiss = { showClearHistoryConfirm = false },
            onConfirm = {
                showClearHistoryConfirm = false
                if (clearingHistory) return@SettingsClearHistoryDialog
                clearingHistory = true
                backupScope.launch {
                    runCatching {
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
    onSelect: (SettingsPane) -> Unit
) {
    var focusedIndex by remember { mutableIntStateOf(-1) }
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
            modifier = Modifier.padding(bottom = 18.dp)
        )
        SettingsPane.entries.forEachIndexed { index, pane ->
            val selectedHere = pane == selected
            val itemModifier = if (index == 0) {
                Modifier.focusRequester(firstItemFocus)
            } else {
                Modifier
            }
            KBCard(
                onClick = { onSelect(pane) },
                modifier = itemModifier
                    .fillMaxWidth()
                    .onFocusChanged { focusedIndex = if (it.isFocused) index else -1 }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            when {
                                selectedHere -> KBAccent.copy(alpha = 0.22f)
                                else -> androidx.compose.ui.graphics.Color.Transparent
                            },
                            RoundedCornerShape(10.dp)
                        )
                        .border(
                            width = if (selectedHere) 1.dp else 0.dp,
                            color = if (selectedHere) KBAccent else androidx.compose.ui.graphics.Color.Transparent,
                            shape = RoundedCornerShape(10.dp)
                        )
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = pane.label,
                        color = if (selectedHere) KBAccent else KBTextHi,
                        style = MaterialTheme.typography.titleSmall
                    )
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
            .verticalScroll(scroll)
            .padding(start = 40.dp, end = 64.dp, top = 32.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = title.uppercase(),
            color = KBAccent,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        content()
    }
}

@Composable
private fun AboutSection() {
    // About pane: build identity + the in-app update flow. Updates live here
    // (not in Integrations) — it's app plumbing, not an integration.
    val versionLine = "KBStream ${com.kennyb1201.kbstream.BuildConfig.VERSION_NAME} " +
        "(build ${com.kennyb1201.kbstream.BuildConfig.VERSION_CODE}, " +
        com.kennyb1201.kbstream.BuildConfig.GIT_SHA.take(7) + ")"
    Column {
        KBCard(onClick = {}, modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(KBSurfaceRaised, RoundedCornerShape(8.dp))
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
private fun SyncHealthSection() {
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

    Column {
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
                    .background(KBSurfaceRaised, RoundedCornerShape(8.dp))
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
                    .background(KBSurfaceRaised, RoundedCornerShape(8.dp))
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
                    runCatching {
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
                    .background(KBSurfaceRaised, RoundedCornerShape(8.dp))
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
        Text(
            text = label,
            color = KBTextLo,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            color = KBTextHi,
            style = MaterialTheme.typography.bodySmall
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
    val label = when (val s = updateState) {
        is AppUpdater.UpdateState.Available ->
            "Update available — ${s.versionName} (build ${s.versionCode})"
        is AppUpdater.UpdateState.Downloading ->
            "Downloading update… ${s.percent}%"
        is AppUpdater.UpdateState.ReadyToInstall ->
            "Update ready — installing…"
        is AppUpdater.UpdateState.Checking -> "Checking for updates…"
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
                    is AppUpdater.UpdateState.ReadyToInstall -> Unit
                    else -> AppUpdater.checkForUpdate(context)
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(KBSurfaceRaised, RoundedCornerShape(10.dp))
                    .padding(horizontal = 16.dp, vertical = 14.dp),
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
                    .background(KBSurface, RoundedCornerShape(18.dp))
                    .border(1.dp, KBAccent.copy(alpha = 0.38f), RoundedCornerShape(18.dp))
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
 * A labelled option row for the audio-tuning settings: description under the
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
                        RoundedCornerShape(6.dp)
                    )
                } else {
                    Modifier.background(
                        if (selected) KBAccent else KBSurface,
                        RoundedCornerShape(6.dp)
                    )
                }
            )
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}

@Composable
private fun ToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    enabled: Boolean = true,
    /** Forces the displayed state (e.g. showing OFF for a gated toggle) without touching the stored value. */
    checkedOverride: Boolean? = null
) {
    val displayChecked = checkedOverride ?: checked
    KBCard(
        onClick = { if (enabled) onToggle(!checked) },
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(KBSurfaceRaised, RoundedCornerShape(8.dp))
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
                        RoundedCornerShape(6.dp)
                    )
                    .alpha(if (enabled) 1f else 0.35f)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun NavigationRow(
    label: String,
    description: String,
    onClick: () -> Unit
) {
    KBCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(KBSurfaceRaised, RoundedCornerShape(10.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
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
