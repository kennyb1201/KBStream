package com.kennyb1201.kbstream.ui.settings

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.kennyb1201.kbstream.data.history.WatchHistoryRepository
import com.kennyb1201.kbstream.data.watched.WatchedStatusRepository
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.data.backup.BackupManager
import com.kennyb1201.kbstream.data.badges.StreamBadgeEngine
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAddons: () -> Unit = {},
    onOpenSimkl: () -> Unit = {},
    onOpenNuvioManager: () -> Unit = {}
) {
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }

    var bufferMode by remember { mutableIntStateOf(AppPreferences.getDefaultBufferMode(context)) }
    var subtitleSize by remember { mutableIntStateOf(AppPreferences.getDefaultSubtitleSize(context)) }
    var subtitleBg by remember { mutableIntStateOf(AppPreferences.getDefaultSubtitleBackground(context)) }
    var autoPlayNext by remember { mutableStateOf(AppPreferences.getAutoPlayNext(context)) }
    var autoSelectStream by remember { mutableStateOf(AppPreferences.getAutoSelectStream(context)) }
    var useStreamRanker by remember { mutableStateOf(AppPreferences.getUseStreamRanker(context)) }
    var enableTunneling by remember { mutableStateOf(AppPreferences.getEnableTunneling(context)) }
    var enablePip by remember { mutableStateOf(AppPreferences.getEnablePip(context)) }
    // Fire TV OS doesn't support PiP for third-party apps; hide the toggle there.
    val isFireTv = android.os.Build.MANUFACTURER.equals("Amazon", ignoreCase = true)
    var audioDecoder by remember { mutableIntStateOf(AppPreferences.getAudioDecoder(context)) }
    var heroTrailerAutoplay by remember { mutableStateOf(AppPreferences.getHeroTrailerAutoplay(context)) }
    var use24hClock by remember { mutableStateOf(AppPreferences.getUse24HourClock(context)) }
    var badgePackInput by remember { mutableStateOf(StreamBadgeEngine.getPackUrl(context)) }
    var railShowType by remember { mutableStateOf(AppPreferences.getHomeRailShowCatalogType(context)) }
    var railShowAddon by remember { mutableStateOf(AppPreferences.getHomeRailShowAddonName(context)) }
    var searchRailShowType by remember { mutableStateOf(AppPreferences.getSearchRailShowCatalogType(context)) }
    var searchRailShowAddon by remember { mutableStateOf(AppPreferences.getSearchRailShowAddonName(context)) }
    var captionTitle by remember { mutableStateOf(AppPreferences.getPosterCaptionTitle(context)) }
    var captionYear by remember { mutableStateOf(AppPreferences.getPosterCaptionYear(context)) }
    var captionRating by remember { mutableStateOf(AppPreferences.getPosterCaptionRating(context)) }
    var railHideUpcoming by remember { mutableStateOf(AppPreferences.getHomeRailHideUpcoming(context)) }
    var landscapeCards by remember { mutableStateOf(AppPreferences.getHomeLandscapeCards(context)) }
    var clearingHistory by remember { mutableStateOf(false) }
    var historyClearedAt by remember { mutableStateOf<Long?>(null) }
    var dvCompatMode by remember { mutableIntStateOf(AppPreferences.getDvCompatMode(context)) }
    var convertP5To81 by remember { mutableStateOf(AppPreferences.getConvertP5To81(context)) }
    var stripHdr10Plus by remember { mutableStateOf(AppPreferences.getStripHdr10Plus(context)) }
    var aspectRatio by remember { mutableIntStateOf(AppPreferences.getDefaultAspectRatio(context)) }
    var preferredAudioLang by remember { mutableStateOf(AppPreferences.getPreferredAudioLanguage(context)) }
    var preferredSubtitleLang by remember { mutableStateOf(AppPreferences.getPreferredSubtitleLanguage(context)) }
    var backupStatus by remember { mutableStateOf<String?>(null) }
    var omdbKeyInput by remember { mutableStateOf(AppPreferences.getOmdbApiKey(context)) }
    var omdbKeySaved by remember { mutableStateOf(false) }

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

    BackHandler { onBack() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KBVoid)
            .padding(horizontal = 64.dp, vertical = 40.dp)
            .focusRequester(focusRequester)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "SETTINGS",
            color = KBAccent,
            style = MaterialTheme.typography.headlineMedium
        )
        Text(
            text = "Player defaults \u2014 applied to every new playback session",
            color = KBTextLo,
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.height(16.dp))

        // ── INTEGRATIONS ────────────────────────────────────────
        SectionHeader("INTEGRATIONS")

        NavigationRow(
            label = "Add-ons",
            description = "Manage Stremio add-ons and catalogs",
            onClick = onOpenAddons
        )

        NavigationRow(
            label = "Simkl",
            description = "Connect your Simkl account for scrobbling",
            onClick = onOpenSimkl
        )

        NavigationRow(
            label = "Collections",
            description = "Import Nuvio collections and arrange home rails",
            onClick = onOpenNuvioManager
        )

        // OMDb API key: free key from omdbapi.com enables the critic
        // ratings row (Rotten Tomatoes / Metacritic / IMDb) on detail pages.
        KBCard(
            onClick = { },
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(KBSurfaceRaised, RoundedCornerShape(8.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Text(
                    text = "OMDb API Key",
                    color = KBTextHi,
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "Free key from omdbapi.com — enables Rotten Tomatoes " +
                        "and Metacritic ratings on detail pages.",
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 2.dp)
                )

                var omdbFieldFocused by remember { mutableStateOf(false) }
                BasicTextField(
                    value = omdbKeyInput,
                    onValueChange = {
                        omdbKeyInput = it.trim()
                        omdbKeySaved = false
                    },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = KBTextHi,
                        fontSize = MaterialTheme.typography.bodyMedium.fontSize
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            AppPreferences.setOmdbApiKey(context, omdbKeyInput)
                            omdbKeySaved = omdbKeyInput.isNotBlank()
                        }
                    ),
                    cursorBrush = SolidColor(KBAccent),
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .fillMaxWidth()
                                .background(KBSurface, RoundedCornerShape(8.dp))
                                .border(
                                    if (omdbFieldFocused) 2.dp else 1.dp,
                                    if (omdbFieldFocused) KBAccent
                                    else KBTextLo.copy(alpha = 0.25f),
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            if (omdbKeyInput.isBlank()) {
                                Text(
                                    text = "Paste key (e.g. a1b2c3d4)",
                                    color = KBTextLo.copy(alpha = 0.7f),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            innerTextField()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged {
                            omdbFieldFocused = it.isFocused
                            // Save on focus loss too — remote users often
                            // just navigate away after pasting.
                            if (!it.isFocused) {
                                AppPreferences.setOmdbApiKey(context, omdbKeyInput)
                                omdbKeySaved = omdbKeyInput.isNotBlank()
                            }
                        }
                )

                if (omdbKeySaved) {
                    Text(
                        text = "Saved — ratings appear the next time you open a title.",
                        color = KBAccent,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 5.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ── STREAM BADGES (Nuvio-compatible packs) ────────────────
        KBCard(
            onClick = { },
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
                    text = "Import a Nuvio-compatible badge pack JSON — matched " +
                        "badges show on sources and in the player overlay.",
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 2.dp)
                )

                var badgeFieldFocused by remember { mutableStateOf(false) }
                var badgeStatus by remember { mutableStateOf<String?>(null) }
                var badgeImporting by remember { mutableStateOf(false) }
                val scope = rememberCoroutineScope()

                BasicTextField(
                    value = badgePackInput,
                    onValueChange = {
                        badgePackInput = it.trim()
                        badgeStatus = null
                    },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = KBTextHi,
                        fontSize = MaterialTheme.typography.bodyMedium.fontSize
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (badgePackInput.isNotBlank() && !badgeImporting) {
                                badgeImporting = true
                                scope.launch {
                                    badgeStatus = StreamBadgeEngine
                                        .importFromUrl(context, badgePackInput)
                                        ?: "Badge pack imported"
                                    badgeImporting = false
                                }
                            }
                        }
                    ),
                    cursorBrush = SolidColor(KBAccent),
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier
                                .padding(top = 6.dp)
                                .fillMaxWidth()
                                .background(KBSurface, RoundedCornerShape(8.dp))
                                .border(
                                    if (badgeFieldFocused) 2.dp else 1.dp,
                                    if (badgeFieldFocused) KBAccent
                                    else KBTextLo.copy(alpha = 0.25f),
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            if (badgePackInput.isBlank()) {
                                Text(
                                    text = "https://…/stream-badges.json",
                                    color = KBTextLo.copy(alpha = 0.7f),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            innerTextField()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { badgeFieldFocused = it.isFocused }
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    KBCard(
                        onClick = {
                            if (badgePackInput.isNotBlank() && !badgeImporting) {
                                badgeImporting = true
                                scope.launch {
                                    badgeStatus = StreamBadgeEngine
                                        .importFromUrl(context, badgePackInput)
                                        ?: "Badge pack imported"
                                    badgeImporting = false
                                }
                            }
                        },
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

        Spacer(modifier = Modifier.height(12.dp))

        // ── DATA ──────────────────────────────────────────────────
        SectionHeader("DATA")

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
                importLauncher.launch(
                    arrayOf("application/json", "text/plain", "application/octet-stream")
                )
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
                clearingHistory = true
                backupScope.launch {
                    runCatching {
                        WatchHistoryRepository(context).clearAll()
                        WatchedStatusRepository(context).clearLocalWatchState(clearSimklAuth = false)
                    }
                    clearingHistory = false
                    historyClearedAt = System.currentTimeMillis()
                }
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

        Spacer(modifier = Modifier.height(12.dp))

        // ── PLAYBACK ──────────────────────────────────────────────
        SectionHeader("PLAYBACK")

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

        Spacer(modifier = Modifier.height(10.dp))

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
                AppPreferences.DV_COMPAT_AUTO -> "Convert Blu-ray Profile 7 remuxes to Profile 8.1; P4/P5/P8 play as Dolby Vision"
                AppPreferences.DV_COMPAT_OFF -> "Play files exactly as provided (device must handle DV)"
                AppPreferences.DV_COMPAT_ALL -> "Convert every DV profile (P4/P5/P7/P8) \u2192 HDR10/HEVC — for non-DV TVs (P5 colors may be off)"
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
                        "None passes everything through — the P5 \u2192 8.1 toggle below is ignored"
                    else ->
                        "Strip All strips every profile — the P5 \u2192 8.1 toggle below is ignored"
                },
                color = KBTextLo,
                style = MaterialTheme.typography.labelSmall
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        ToggleRow(
            label = "P5 \u2192 8.1",
            description = "Convert Profile 5 (ICtCp) streams to Profile 8.1 — colors corrected for display",
            checked = convertP5To81,
            enabled = dvCompatMode == AppPreferences.DV_COMPAT_AUTO,
            onToggle = {
                convertP5To81 = it
                AppPreferences.setConvertP5To81(context, it)
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        ToggleRow(
            label = "Strip HDR10+",
            description = "Remove ST 2094-40 metadata (HDR10+ & DV+HDR10+ files) for TVs that black-screen on it",
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

        Spacer(modifier = Modifier.height(12.dp))

        // ── INTERFACE ─────────────────────────────────────────
        SectionHeader("INTERFACE")

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
            description = "Show the title under posters on every screen except Home rails.",
            checked = captionTitle,
            onToggle = {
                captionTitle = it
                AppPreferences.setPosterCaptionTitle(context, it)
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        ToggleRow(
            label = "Poster Years",
            description = "Show the release year under posters on every screen except Home rails (where the screen has it).",
            checked = captionYear,
            onToggle = {
                captionYear = it
                AppPreferences.setPosterCaptionYear(context, it)
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        ToggleRow(
            label = "Poster Star Ratings",
            description = "Show the star rating under posters on every screen except Home rails (where the screen has it).",
            checked = captionRating,
            onToggle = {
                captionRating = it
                AppPreferences.setPosterCaptionRating(context, it)
            }
        )

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
            label = "Landscape Cards on Home Rails",
            description = "Show 16:9 backdrop cards with a small clearlogo instead of posters.",
            checked = landscapeCards,
            onToggle = {
                landscapeCards = it
                AppPreferences.setHomeLandscapeCards(context, it)
            }
        )

        Spacer(modifier = Modifier.height(12.dp))

        // ── VIDEO ──────────────────────────────────────────────
        SectionHeader("VIDEO")

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
            listOf("Fit", "Zoom", "Fill").forEachIndexed { index, label ->
                KBCard(onClick = {
                    aspectRatio = index
                    AppPreferences.setDefaultAspectRatio(context, index)
                }) {
                    PillChip(label, aspectRatio == index)
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ── LANGUAGE ──────────────────────────────────────────────
        SectionHeader("LANGUAGE")

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

        Spacer(modifier = Modifier.height(12.dp))

        // ── SUBTITLES ──────────────────────────────────────────
        SectionHeader("SUBTITLES")

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
            listOf("None", "Semi", "Solid").forEachIndexed { index, label ->
                KBCard(onClick = {
                    subtitleBg = index
                    AppPreferences.setDefaultSubtitleBackground(context, index)
                }) {
                    PillChip(label, subtitleBg == index)
                }
            }        }

        Spacer(modifier = Modifier.height(24.dp))
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}

// ── Helper composables ──────────────────────────────────────────

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        color = KBAccent,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(bottom = 6.dp)
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
    enabled: Boolean = true
) {
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
                text = if (checked) "ON" else "OFF",
                color = if (checked) KBVoid else KBTextHi,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .background(
                        if (checked) KBAccent else KBSurface,
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
