package com.kennyb1201.kbstream.ui.mobile

import android.content.ActivityNotFoundException
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kennyb1201.kbstream.BuildConfig
import com.kennyb1201.kbstream.data.backup.BackupManager
import com.kennyb1201.kbstream.data.badges.StreamBadgeEngine
import com.kennyb1201.kbstream.data.runCatchingCancellable
import com.kennyb1201.kbstream.data.history.WatchHistoryRepository
import com.kennyb1201.kbstream.data.library.HiddenTitles
import com.kennyb1201.kbstream.data.mdblist.MdbListClient
import com.kennyb1201.kbstream.data.player.ExternalPlayer
import com.kennyb1201.kbstream.data.player.PlayerEngine
import com.kennyb1201.kbstream.data.settings.AppPreferences
import com.kennyb1201.kbstream.data.settings.ResumeAuthority
import com.kennyb1201.kbstream.data.sync.PrefsPayloadApplier
import com.kennyb1201.kbstream.data.sync.ProfileManager
import com.kennyb1201.kbstream.data.sync.SupabaseSync
import com.kennyb1201.kbstream.data.update.AppUpdater
import com.kennyb1201.kbstream.data.watched.WatchedStatusRepository
import com.kennyb1201.kbstream.domain.streamengine.AutoPlayQuality
import com.kennyb1201.kbstream.ui.components.touch.KBButton
import com.kennyb1201.kbstream.ui.components.touch.KBCard
import com.kennyb1201.kbstream.ui.components.touch.KBDialogPanel
import com.kennyb1201.kbstream.ui.components.touch.KBPasteChip
import com.kennyb1201.kbstream.ui.components.touch.KBTextField
import com.kennyb1201.kbstream.ui.player.PlayerAudioTuning
import com.kennyb1201.kbstream.ui.player.PlayerTrackBridge
import com.kennyb1201.kbstream.ui.player.SubtitleModeRules
import com.kennyb1201.kbstream.ui.settings.SettingsGroup
import com.kennyb1201.kbstream.ui.settings.SettingsPane
import com.kennyb1201.kbstream.ui.settings.syncRelativeTime
import com.kennyb1201.kbstream.ui.theme.CUSTOM_ACCENT_INDEX
import com.kennyb1201.kbstream.ui.theme.DEFAULT_ACCENT_INDEX
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBAccentPalette
import com.kennyb1201.kbstream.ui.theme.KBDanger
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBShapePill
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import com.kennyb1201.kbstream.ui.theme.MobileTheme
import com.kennyb1201.kbstream.ui.theme.argbFromHsv
import com.kennyb1201.kbstream.ui.theme.hsvFromArgb
import com.kennyb1201.kbstream.ui.theme.kbCustomAccentState
import com.kennyb1201.kbstream.ui.theme.refreshThemeMirrors
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/*
 * Settings, for a finger.
 *
 * The TV page is a nav rail plus one pane at a time. A phone keeps both halves
 * of that shape - the same `SettingsPane` / `SettingsGroup` IA (labels, order,
 * blurbs) and one pane at a time - but the rail becomes the page, and a pane is
 * pushed over it with BACK (the system gesture, or the on-screen BACK).
 *
 * Every control writes the same `AppPreferences` key the TV control writes, and
 * the synced ones are re-seeded on `PrefsPayloadApplier.revision` exactly as on
 * TV, so a toggle flipped on another box moves here too. The API-key fields are
 * hidden on a kids profile, as they are there.
 *
 * The Sync pane is ported too (see `SyncPaneMobile`): the same `SupabaseSync`
 * flows, the same relative timestamps, force-resync, two-tap eye-badge clear and
 * COPY DIAGNOSTICS. Its rail dot has no phone rail to sit on; the health rows
 * themselves are the surface here.
 *
 * The player-facing gaps are ported too: the dialogue and volume boosts and the
 * external-player engine (detected players, the hand-picked-app fallback, Ask
 * every time and Treat coming back as the end), all on the same prefs the TV
 * Playback pane writes. The External engine is hidden on a kids profile, the
 * same C8 rule the TV applies.
 *
 * The MPV video-output row and the stream-badge pack URL are ported too, on the
 * same `AppPreferences` / `StreamBadgeEngine` calls the TV rows make.
 *
 * Frame-rate diagnostics are deliberately NOT ported: they report what an HDMI
 * panel answered and which display mode it switched to, which a phone has no
 * equivalent of - the same reason Match Content Frame Rate is TV-only.
 *
 * The accent mixer is ported as the same palette grid plus three touch sliders
 * over the theme's own HSV maths (`hsvFromArgb` / `argbFromHsv`), so a colour
 * mixed on a phone is the same ARGB the TV bars would produce. With it, no
 * Settings control is deferred any more - only the TV-only surfaces remain
 * different (frame rate, tunnelled playback).
 *
 * The numeric choices are ported as chip rows instead of steppers
 * (`EndPanelPointMobile`, `HeroTrailerDelayMobile`): the next-episode and
 * Because-You-Watched points, the still-there episode count and the hero
 * trailer delay all write the same stored values through the same
 * `AppPreferences` option lists and formatters the TV rows use, so both
 * form factors agree on what each chip means.
 */

@Composable
fun SettingsScreenMobile(
    onBack: () -> Unit,
    onOpenAddons: () -> Unit = {},
    onOpenSimkl: () -> Unit = {},
    onOpenTrakt: () -> Unit = {},
    onOpenProfiles: () -> Unit = {},
    onOpenCatalogManager: () -> Unit = {},
    onOpenCatalogBuilder: () -> Unit = {},
    onOpenStats: () -> Unit = {}
) {
    var pane by remember { mutableStateOf<SettingsPane?>(null) }

    BackHandler {
        if (pane != null) pane = null else onBack()
    }

    MobileTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(KBVoid)
        ) {
            val open = pane
            if (open == null) {
                SettingsHomeMobile(onOpen = { pane = it })
            } else {
                PaneHeaderMobile(pane = open, onBack = { pane = null })
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    when (open) {
                        SettingsPane.INTEGRATIONS -> IntegrationsPaneMobile(
                            onOpenAddons = onOpenAddons,
                            onOpenSimkl = onOpenSimkl,
                            onOpenTrakt = onOpenTrakt,
                            onOpenProfiles = onOpenProfiles,
                            onOpenStats = onOpenStats
                        )

                        SettingsPane.COLLECTIONS -> CollectionsPaneMobile(
                            onOpenCatalogManager = onOpenCatalogManager,
                            onOpenCatalogBuilder = onOpenCatalogBuilder
                        )

                        SettingsPane.HIDDEN -> HiddenTitlesPaneMobile()

                        SettingsPane.PLAYBACK -> PlaybackPaneMobile()

                        SettingsPane.VIDEO -> VideoPaneMobile()

                        SettingsPane.LANGUAGE -> LanguagePaneMobile()

                        SettingsPane.SUBTITLES -> SubtitlesPaneMobile()

                        SettingsPane.INTERFACE -> InterfacePaneMobile()

                        SettingsPane.DATA -> DataPaneMobile(onOpenStats = onOpenStats)

                        SettingsPane.ABOUT -> AboutPaneMobile()

                        SettingsPane.SYNC -> SyncPaneMobile()
                    }
                }
            }
        }
    }
}

/** The rail's replacement: grouped rows, one per pane, in the TV's own order. */
@Composable
private fun SettingsHomeMobile(onOpen: (SettingsPane) -> Unit) {
    val panes = remember { SettingsPane.entries }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item(key = "header") {
            Column(modifier = Modifier.padding(bottom = 6.dp)) {
                Text(
                    text = "SETTINGS",
                    color = KBAccent,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Each group opens its own page.",
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }

        SettingsGroup.entries.forEach { group ->
            item(key = "group_${group.name}") {
                Text(
                    text = group.label.uppercase(),
                    color = KBTextLo,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
                )
            }
            items(
                items = panes.filter { it.group == group },
                key = { it.name }
            ) { pane ->
                KBCard(
                    onClick = { onOpen(pane) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Text(
                            text = pane.label,
                            color = KBTextHi,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = pane.blurb,
                            color = KBTextLo,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PaneHeaderMobile(pane: SettingsPane, onBack: () -> Unit) {
    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            KBButton(label = "BACK", onClick = onBack)
            Spacer(modifier = Modifier.padding(start = 12.dp))
            Text(
                text = pane.label,
                color = KBAccent,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        Text(
            text = pane.blurb,
            color = KBTextLo,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
private fun PaneScroll(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        content()
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun SettingsSectionMobile(title: String, first: Boolean = false) {
    Text(
        text = title.uppercase(),
        color = KBAccent,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = if (first) 2.dp else 18.dp, bottom = 6.dp)
    )
}

@Composable
private fun SettingsCaptionMobile(text: String) {
    Text(
        text = text,
        color = KBTextLo,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

@Composable
private fun SettingsToggleMobile(
    label: String,
    description: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
        ) {
            Text(
                text = label,
                color = KBTextHi,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = description,
                color = KBTextLo,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = KBVoid,
                checkedTrackColor = KBAccent,
                uncheckedThumbColor = KBTextLo,
                uncheckedTrackColor = KBTextHi.copy(alpha = 0.18f)
            )
        )
    }
}

@Composable
private fun SettingsNavRowMobile(
    label: String,
    description: String,
    onClick: () -> Unit
) {
    KBCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Text(
                text = label,
                color = KBTextHi,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = description,
                color = KBTextLo,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsChoiceMobile(
    label: String,
    options: List<Pair<String, Int>>,
    selected: Int,
    onSelect: (Int) -> Unit,
    description: String? = null
) {
    Text(
        text = label,
        color = KBTextHi,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (optionLabel, value) ->
            SettingsPillMobile(
                label = optionLabel,
                selected = selected == value,
                onClick = { onSelect(value) }
            )
        }
    }
    description?.let {
        Text(
            text = it,
            color = KBTextLo,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/** The same chip row for a choice stored as a string (MPV's vo modes). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsChoiceStringMobile(
    label: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    description: String? = null
) {
    Text(
        text = label,
        color = KBTextHi,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (optionLabel, value) ->
            SettingsPillMobile(
                label = optionLabel,
                selected = selected == value,
                onClick = { onSelect(value) }
            )
        }
    }
    description?.let {
        Text(
            text = it,
            color = KBTextLo,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsLanguageChoiceMobile(
    label: String,
    selected: String,
    onSelect: (String) -> Unit
) {
    Text(
        text = label,
        color = KBTextHi,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PlayerTrackBridge.LANGUAGE_OPTIONS.forEach { (optionLabel, code) ->
            SettingsPillMobile(
                label = optionLabel,
                selected = selected == code,
                onClick = { onSelect(code) }
            )
        }
    }
}

/**
 * The Dolby Vision mode copy, shared with the TV page's wording.
 *
 * These chips used to read "P7 → 8.1" for the AUTO mode, which named a
 * conversion the mode only performs on some displays - the TV page calls the
 * same value "Auto" because that is what it is: the mode chooses by display.
 * The pairs below are the TV page's own, with only the audience noun adapted
 * ("TVs" reads "displays" on a phone), so the two screens cannot drift apart
 * again without a test failing.
 */
internal fun dvCompatChipLabel(mode: Int): String =
    when (mode) {
        AppPreferences.DV_COMPAT_AUTO -> "Auto"
        AppPreferences.DV_COMPAT_OFF -> "None"
        AppPreferences.DV_COMPAT_ALL -> "Strip All"
        else -> ""
    }

/** The explanation of the SELECTED mode, exactly as the TV page explains it. */
internal fun dvCompatDescription(mode: Int): String =
    when (mode) {
        AppPreferences.DV_COMPAT_AUTO ->
            "Chooses by your display: Dolby Vision passthrough on DV displays " +
                "(P7 → 8.1 for compatibility); HDR10 with HDR10+ removed on non-DV " +
                "displays. P5 follows its toggle below"
        AppPreferences.DV_COMPAT_OFF ->
            "Play files exactly as provided (device must handle DV)"
        AppPreferences.DV_COMPAT_ALL ->
            "Strips every DV profile (P4/P5/P7/P8) → HDR10/HEVC for non-DV " +
                "displays. P5 colors are corrected on the GPU automatically"
        else -> ""
    }

@Composable
private fun SettingsPillMobile(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
                softWrap = false
            )
        },
        shape = KBShapeCard,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = KBTextHi.copy(alpha = 0.06f),
            labelColor = KBTextLo,
            selectedContainerColor = KBAccent.copy(alpha = 0.24f),
            selectedLabelColor = KBAccent
        )
    )
}

@Composable
private fun IntegrationsPaneMobile(
    onOpenAddons: () -> Unit,
    onOpenSimkl: () -> Unit,
    onOpenTrakt: () -> Unit,
    onOpenProfiles: () -> Unit,
    onOpenStats: () -> Unit
) {
    val context = LocalContext.current
    val activeProfile by ProfileManager.activeProfile.collectAsStateWithLifecycle()
    val kidsAddonLock =
        activeProfile?.kidsMaxAge != null && activeProfile?.kidsHideAddons == true
    val keysHidden = activeProfile?.kidsMaxAge != null
    // Resume Source is a behaviour pref, not a secret: it re-seeds on the sync
    // revision like every other synced row, and it is NOT gated by keysHidden.
    val syncedRevision by PrefsPayloadApplier.revision.collectAsStateWithLifecycle()
    var resumeAuthority by remember(syncedRevision) {
        mutableStateOf(AppPreferences.getResumeAuthority(context))
    }
    // Device-local, so bare: whether a launch opens on Who's Watching is about
    // how this phone is shared, not the account (see
    // PrefsPayloadBuilder.EXCLUDED_PREF_KEYS).
    var showProfilePickerOnLaunch by remember {
        mutableStateOf(AppPreferences.getShowProfilePicker(context))
    }

    PaneScroll {
        SettingsSectionMobile("Profiles & Accounts", first = true)
        SettingsNavRowMobile(
            label = "Profiles",
            description = "Create, rename, and switch viewing profiles",
            onClick = onOpenProfiles
        )
        // The TV row's twin: on keeps "every session starts under a chosen
        // identity", off goes straight into the last used profile. A
        // PIN-protected profile still gets the picker either way.
        SettingsToggleMobile(
            label = "Who's Watching on Launch",
            description = if (showProfilePickerOnLaunch) {
                "Open Who's Watching every time the app starts."
            } else {
                "Start in the last used profile instead."
            },
            checked = showProfilePickerOnLaunch,
            onChange = { enabled ->
                showProfilePickerOnLaunch = enabled
                AppPreferences.setShowProfilePicker(context, enabled)
            }
        )
        if (!kidsAddonLock) {
            SettingsNavRowMobile(
                label = "Add-ons",
                description = "Manage add-ons, import collections, and arrange the home screen",
                onClick = onOpenAddons
            )
        }
        SettingsNavRowMobile(
            label = "Viewing stats",
            description = "Finished episodes, movies, runtime and your streak",
            onClick = onOpenStats
        )

        // ── Trackers ─────────────────────────────────────────────
        // The three services that hold this account's viewing state, plus the
        // resume tie-break they feed - the TV pane's section, in the same order.
        SettingsSectionMobile("Trackers")
        SettingsNavRowMobile(
            label = "Simkl",
            description = "Connect your Simkl account for scrobbling",
            onClick = onOpenSimkl
        )
        SettingsNavRowMobile(
            label = "Trakt",
            description = "Connect your Trakt account to scrobble playback and sync watched state",
            onClick = onOpenTrakt
        )
        // The card hides its own field on a kids profile, exactly as it did in
        // API Keys: the key is a secret, the row is not.
        if (keysHidden) {
            SettingsCaptionMobile(
                "The MDBList key is hidden on a kids profile. Switch to a parent " +
                    "profile to view or change it."
            )
        } else {
            KeyCardMobile(
                title = "MDBList API Key",
                description = "mdblist.com key: critic ratings, watched-history badges, " +
                    "resume-from-pause, scrobbling, and your lists in the Library tab.",
                placeholder = "Paste key (e.g. 12345)",
                loadedValue = AppPreferences.getMdbListApiKey(context),
                onSave = { key -> AppPreferences.setMdbListApiKey(context, key) },
                verify = { MdbListClient.verifyKey(context) },
                savedHint = "Connected as",
                savedDescription = "Key saved — ratings appear on the next title you open."
            )
        }
        ResumeAuthorityMobile(
            selected = resumeAuthority,
            onPick = { authority ->
                resumeAuthority = authority
                AppPreferences.setResumeAuthority(context, authority)
            }
        )

        SettingsSectionMobile("API Keys")
        if (keysHidden) {
            SettingsCaptionMobile(
                "API keys are hidden on a kids profile. Switch to a parent profile " +
                    "to view or change them."
            )
        } else {
            KeyCardMobile(
                title = "OMDb API Key",
                description = "Free key from omdbapi.com — adds an AWARDS fact to detail " +
                    "pages. The free tier allows 1,000 lookups a day.",
                placeholder = "Paste key",
                loadedValue = AppPreferences.getOmdbApiKey(context),
                onSave = { key -> AppPreferences.setOmdbApiKey(context, key) },
                savedDescription = "Saved — the AWARDS fact appears on the next title you open."
            )
            KeyCardMobile(
                title = "OpenSubtitles API Key",
                description = "Enables subtitle search in the player.",
                placeholder = "Paste key",
                loadedValue = AppPreferences.getOpensubtitlesApiKey(context),
                onSave = { key -> AppPreferences.setOpensubtitlesApiKey(context, key) },
                savedDescription = "Saved — subtitle search is available in the player."
            )
            KeyCardMobile(
                title = "TorBox API Key",
                description = "Adds your TorBox cloud library to the Debrid rows.",
                placeholder = "Paste key",
                loadedValue = AppPreferences.getTorboxApiKey(context),
                onSave = { key -> AppPreferences.setTorboxApiKey(context, key) },
                savedDescription = "Saved — the TorBox library syncs with the active profile."
            )

            SettingsSectionMobile("Subtitles & Debrid")
            SyncedToggleMobile(
                label = "Auto-fetch subtitles",
                description = "Look for a matching subtitle when a title starts without one.",
                get = AppPreferences::getAutoFetchSubtitles,
                set = AppPreferences::setAutoFetchSubtitles
            )
        SyncedToggleMobile(
            label = "TorBox library sync",
            description = "Keep the TorBox cloud library in step with the active profile.",
            get = AppPreferences::getTorboxLibrarySync,
            set = AppPreferences::setTorboxLibrarySync
        )

        // ── Stream badges ────────────────────────────────────────────────
        // The same pack URL field and import/remove calls the TV pane uses; the
        // only change is that the field sits beside the keyboard here, so it
        // does not need the TV's tap-the-card-to-focus dance.
        SettingsSectionMobile("Badges")
        var badgePackInput by remember {
            mutableStateOf(StreamBadgeEngine.getPackUrl(context))
        }
        var badgeStatus by remember {
            mutableStateOf(
                if (StreamBadgeEngine.hasPack(context)) {
                    "Badge pack imported — ${StreamBadgeEngine.filterCount(context)} " +
                        "filters active"
                } else {
                    null
                }
            )
        }
        var badgeImporting by remember { mutableStateOf(false) }
        val badgeScope = rememberCoroutineScope()

        fun importBadgePack() {
            if (badgePackInput.isNotBlank() && !badgeImporting) {
                badgeImporting = true
                badgeScope.launch {
                    badgeStatus = StreamBadgeEngine.importFromUrl(context, badgePackInput)
                        ?: "Badge pack imported — " +
                        "${StreamBadgeEngine.filterCount(context)} filters active"
                    badgeImporting = false
                }
            }
        }

        Text(
            text = "Stream badges",
            color = KBTextHi,
            style = MaterialTheme.typography.bodyMedium
        )
        SettingsCaptionMobile(
            "Import a KB-compatible badge pack JSON — matched badges show on " +
                "sources and in the player overlay."
        )
        KBTextField(
            value = badgePackInput,
            onValueChange = {
                badgePackInput = it.trim()
                badgeStatus = null
            },
            placeholder = "https://…/stream-badges.json",
            modifier = Modifier.fillMaxWidth(),
            onDone = { importBadgePack() }
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            KBPasteChip(onPaste = { pasted ->
                badgePackInput = pasted.trim()
                badgeStatus = null
            })
            KBButton(
                label = if (badgeImporting) "IMPORTING…" else "IMPORT",
                enabled = !badgeImporting && badgePackInput.isNotBlank(),
                onClick = { importBadgePack() }
            )
            if (StreamBadgeEngine.hasPack(context)) {
                KBButton(
                    label = "REMOVE",
                    onClick = {
                        StreamBadgeEngine.clearPack(context)
                        badgeStatus = "Badge pack removed"
                    }
                )
            }
        }
        badgeStatus?.let { text ->
            Text(
                text = text,
                color = if (text.startsWith("Badge pack imported")) KBAccent else KBTextLo,
                style = MaterialTheme.typography.labelSmall
            )
        }
        SyncedToggleMobile(
            label = "Badges above the file name",
            description = "Show a source's badge chips over its name instead of under it, " +
                "in the player's source picker and on the streams screen.",
            get = AppPreferences::getBadgesAboveFile,
            set = AppPreferences::setBadgesAboveFile
        )
    }
}
}

@Composable
private fun CollectionsPaneMobile(
    onOpenCatalogManager: () -> Unit,
    onOpenCatalogBuilder: () -> Unit
) {
    PaneScroll {
        SettingsSectionMobile("Collections", first = true)
        SettingsNavRowMobile(
            label = "Home / Collections",
            description = "Import a collections profile and arrange, pin or hide the " +
                "rails Home draws",
            onClick = onOpenCatalogManager
        )
        SettingsSectionMobile("Rule-based Catalogs")
        SettingsNavRowMobile(
            label = "Catalogs",
            description = "Build a catalog from filters and sort order, and Home draws " +
                "it as its own rail",
            onClick = onOpenCatalogBuilder
        )
    }
}

@Composable
private fun HiddenTitlesPaneMobile() {
    val context = LocalContext.current
    val entries by HiddenTitles.entries.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        HiddenTitles.ensureLoaded(context)
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        if (entries.isEmpty()) {
            item(key = "empty") {
                SettingsCaptionMobile(
                    "Nothing is hidden. Long-press any poster and choose HIDE to take " +
                        "that title off every screen."
                )
            }
        } else {
            item(key = "count") {
                Text(
                    text = "${entries.size} hidden " +
                        if (entries.size == 1) "title" else "titles",
                    color = KBTextHi,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            items(
                items = entries,
                key = { entry ->
                    entry.at.toString() + "\u0001" + entry.keys.joinToString("\u0001")
                }
            ) { entry ->
                KBCard(
                    onClick = { HiddenTitles.unhide(context, entry.keys) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 10.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = entry.title.ifBlank { "Untitled" },
                                color = KBTextHi,
                                style = MaterialTheme.typography.bodyMedium
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
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    }
                }
            }
            item(key = "unhide-all") {
                SettingsNavRowMobile(
                    label = "Show everything again",
                    description = "Unhide all ${entries.size} of them",
                    onClick = { HiddenTitles.unhideAll(context) }
                )
            }
        }
    }
}

@Composable
private fun PlaybackPaneMobile() {
    val context = LocalContext.current
    val syncedRevision by PrefsPayloadApplier.revision.collectAsStateWithLifecycle()

    var playerEngine by remember { mutableStateOf(PlayerEngine.selected(context)) }
    var mpvForAnime by remember { mutableStateOf(AppPreferences.getMpvForAnime(context)) }
    var mpvVoMode by remember { mutableStateOf(AppPreferences.getMpvVoMode(context)) }
    var dvMode by remember { mutableStateOf(AppPreferences.getDvCompatMode(context)) }
    var convertP5 by remember { mutableStateOf(AppPreferences.getConvertP5To81(context)) }
    var stripHdr10Plus by remember { mutableStateOf(AppPreferences.getStripHdr10Plus(context)) }

    var autoPlayNext by remember(syncedRevision) {
        mutableStateOf(AppPreferences.getAutoPlayNext(context))
    }
    var nextEpisodePopup by remember(syncedRevision) {
        mutableStateOf(AppPreferences.getNextEpisodePopup(context))
    }
    var becauseYouWatched by remember(syncedRevision) {
        mutableStateOf(AppPreferences.getBecauseYouWatched(context))
    }
    var bingePrefer by remember(syncedRevision) {
        mutableStateOf(AppPreferences.getBingeGroupPrefer(context))
    }
    var bingeReuse by remember(syncedRevision) {
        mutableStateOf(AppPreferences.getBingeGroupReuse(context))
    }
    var bingeFallback by remember(syncedRevision) {
        mutableStateOf(AppPreferences.getBingeGroupFallback(context))
    }
    var stillThere by remember(syncedRevision) {
        mutableStateOf(AppPreferences.getStillTherePrompt(context))
    }
    // The three numeric choices behind those toggles. They are the same stored
    // tenths/ms/episode values the TV steppers write - only the control differs
    // (a chip row instead of a D-pad stepper).
    var stillThereEpisodes by remember(syncedRevision) {
        mutableStateOf(AppPreferences.getStillThereEpisodes(context))
    }
    var nextEpisodePoint by remember(syncedRevision) {
        mutableStateOf(AppPreferences.getNextEpisodePopupPointTenths(context))
    }
    var becausePoint by remember(syncedRevision) {
        mutableStateOf(AppPreferences.getBecauseYouWatchedPointTenths(context))
    }
    var autoSkipIntro by remember(syncedRevision) {
        mutableStateOf(AppPreferences.getAutoSkipIntro(context))
    }
    var autoSkipCredits by remember(syncedRevision) {
        mutableStateOf(AppPreferences.getAutoSkipCredits(context))
    }
    var autoSelectStream by remember { mutableStateOf(AppPreferences.getAutoSelectStream(context)) }
    var useStreamRanker by remember(syncedRevision) {
        mutableStateOf(AppPreferences.getUseStreamRanker(context))
    }
    // NOT keyed on the sync revision: the cap is device-local (see
    // PrefsPayloadBuilder.EXCLUDED_PREF_KEYS), so a pull from another device
    // must not re-read it here. The picker's own tap updates the state.
    var qualityCap by remember {
        mutableStateOf(AppPreferences.getMaxAutoPlayQuality(context))
    }

    var audioDecoder by remember { mutableStateOf(AppPreferences.getAudioDecoder(context)) }
    var audioOutput by remember { mutableStateOf(AppPreferences.getAudioOutput(context)) }
    var audioDownmix by remember { mutableStateOf(AppPreferences.getAudioDownmix(context)) }
    var audioDialogueBoost by remember {
        mutableStateOf(AppPreferences.getAudioDialogueBoost(context))
    }
    var audioVolumeBoost by remember {
        mutableStateOf(AppPreferences.getAudioVolumeBoostDb(context))
    }

    // External engine, device-local like the engine itself: which video apps are
    // installed is a property of this phone, not of the account.
    val externalPlayers = remember { ExternalPlayer.installed(context) }
    var externalPackage by remember {
        mutableStateOf(
            AppPreferences.getExternalPlayerPackage(context)?.takeIf { stored ->
                externalPlayers.any { it.packageName == stored }
            }
        )
    }
    var customExternalPackage by remember {
        mutableStateOf(AppPreferences.getCustomExternalPlayerPackage(context))
    }
    var customExternalLabel by remember {
        mutableStateOf(AppPreferences.getCustomExternalPlayerLabel(context))
    }
    var externalAsk by remember { mutableStateOf(AppPreferences.getExternalPlayerAsk(context)) }
    var externalTrustReturn by remember {
        mutableStateOf(AppPreferences.getExternalTrustReturn(context))
    }
    var showCustomPlayerPicker by remember { mutableStateOf(false) }
    // Set when the engine pill is picked and there is more than one installed
    // player to hand a title to.
    var showExternalPlayerPicker by remember { mutableStateOf(false) }
    // C8: the External engine cannot run on a kids profile - the time locks live
    // inside the app's own player activities - so the option is not offered, and
    // a stored External choice falls back to ExoPlayer as it does on TV.
    val kidsProfile = ProfileManager.activeProfile
        .collectAsStateWithLifecycle().value
        ?.kidsMaxAge != null

    PaneScroll {
        SettingsSectionMobile("Player Engine", first = true)
        SettingsChoiceMobile(
            label = "Engine",
            options = listOf(
                "ExoPlayer" to AppPreferences.PLAYER_ENGINE_EXO,
                "ExoPlayer only" to AppPreferences.PLAYER_ENGINE_EXO_ONLY,
                "MPV" to AppPreferences.PLAYER_ENGINE_MPV,
                "External player" to AppPreferences.PLAYER_ENGINE_EXTERNAL
            ).filterNot { (_, value) ->
                kidsProfile && value == AppPreferences.PLAYER_ENGINE_EXTERNAL
            },
            selected = if (
                kidsProfile && playerEngine == AppPreferences.PLAYER_ENGINE_EXTERNAL
            ) {
                AppPreferences.PLAYER_ENGINE_EXO
            } else {
                playerEngine
            },
            onSelect = { value ->
                playerEngine = value
                AppPreferences.setPlayerEngine(context, value)
                // Picking this engine IS picking a player: ask which one right
                // away when there is a choice, instead of handing the next
                // title to whichever app the probe found first.
                if (value == AppPreferences.PLAYER_ENGINE_EXTERNAL &&
                    externalPlayers.size > 1
                ) {
                    showCustomPlayerPicker = false
                    showExternalPlayerPicker = true
                }
            },
            description = "ExoPlayer uses the device's own decoders; MPV brings " +
                "libass typesetting and software decoders; External hands the title " +
                "to any video app installed here."
        )
        SettingsToggleMobile(
            label = "Open anime in MPV",
            description = "Use MPV for anime even when the engine above is ExoPlayer.",
            checked = mpvForAnime,
            onChange = {
                mpvForAnime = it
                AppPreferences.setMpvForAnime(context, it)
            }
        )
        // Only meaningful where libmpv exists; on an older device the TV page's
        // own gate keeps the row off, and the preference is left untouched.
        if (PlayerEngine.isMpvAvailable()) {
            SettingsChoiceStringMobile(
                label = "MPV video output",
                options = listOf(
                    "Auto" to AppPreferences.MPV_VO_AUTO,
                    "GPU" to AppPreferences.MPV_VO_GPU,
                    "MediaCodec" to AppPreferences.MPV_VO_MEDIACODEC_EMBED,
                    "Vulkan" to AppPreferences.MPV_VO_GPU_VULKAN
                ),
                selected = mpvVoMode,
                onSelect = { value ->
                    mpvVoMode = value
                    AppPreferences.setMpvVoMode(context, value)
                },
                description = "Which video output mpv opens with. Auto picks by " +
                    "device; if MPV plays sound over a black picture, try MediaCodec " +
                    "or Vulkan. Applies the next time a title opens in MPV."
            )
        }

        SettingsSectionMobile("Dolby Vision & HDR")
        SettingsChoiceMobile(
            label = "Dolby Vision compatibility",
            options = listOf(
                AppPreferences.DV_COMPAT_AUTO,
                AppPreferences.DV_COMPAT_OFF,
                AppPreferences.DV_COMPAT_ALL
            ).map { mode -> dvCompatChipLabel(mode) to mode },
            selected = dvMode,
            onSelect = { value ->
                dvMode = value
                AppPreferences.setDvCompatMode(context, value)
            },
            // Per-mode, like the TV page: one static sentence could only ever
            // describe some of the three modes, and described Auto wrongly.
            description = dvCompatDescription(dvMode)
        )
        SettingsToggleMobile(
            label = "Convert Profile 5 to 8.1",
            description = "Best-effort conversion for displays that accept 8.1 but not ICtCp P5.",
            checked = convertP5,
            onChange = {
                convertP5 = it
                AppPreferences.setConvertP5To81(context, it)
            }
        )
        SettingsToggleMobile(
            label = "Strip HDR10+ metadata",
            description = "Drop HDR10+ dynamic metadata, for players that mishandle it.",
            checked = stripHdr10Plus,
            onChange = {
                stripHdr10Plus = it
                AppPreferences.setStripHdr10Plus(context, it)
            }
        )
        // "Match display frame rate" is deliberately absent: it switches the
        // HDMI panel's display mode and blanks the screen to do it, which is a
        // TV feature (the TV page's own diagnostics say so). The preference is
        // untouched on a phone, so a profile that syncs between a phone and a
        // TV keeps the rate matching its TV had; there is simply no row here
        // that could only ever be a no-op.

        SettingsSectionMobile("Stream Picks & Auto-play")
        SettingsToggleMobile(
            label = "Auto-select a source",
            description = "Start the best source without opening the picker.",
            checked = autoSelectStream,
            onChange = {
                autoSelectStream = it
                AppPreferences.setAutoSelectStream(context, it)
            }
        )
        SettingsToggleMobile(
            label = "Rank sources",
            description = "Order sources by quality, size, seeders and cached status.",
            checked = useStreamRanker,
            onChange = {
                useStreamRanker = it
                AppPreferences.setUseStreamRanker(context, it)
            }
        )
        SettingsChoiceMobile(
            label = "Maximum auto-play quality",
            options = listOf(
                "Auto" to AutoPlayQuality.CAP_AUTO,
                "4K" to AutoPlayQuality.CAP_2160,
                "1080p" to AutoPlayQuality.CAP_1080,
                "720p" to AutoPlayQuality.CAP_720
            ),
            selected = qualityCap,
            onSelect = { value ->
                qualityCap = value
                AppPreferences.setMaxAutoPlayQuality(context, value)
            },
            // Device-local, and the description says so: a ceiling picked here
            // does not follow the profile onto a TV (see
            // PrefsPayloadBuilder.EXCLUDED_PREF_KEYS).
            description = "A ceiling on what auto-play may start on this device only, " +
                "so it never opens a stream it cannot decode."
        )
        SettingsToggleMobile(
            label = "Auto-play next episode",
            description = "Start the next episode when one finishes.",
            checked = autoPlayNext,
            onChange = {
                autoPlayNext = it
                AppPreferences.setAutoPlayNext(context, it)
            }
        )
        SettingsToggleMobile(
            label = "Next-episode popup",
            description = "Offer the next episode before the current one ends.",
            checked = nextEpisodePopup,
            onChange = {
                nextEpisodePopup = it
                AppPreferences.setNextEpisodePopup(context, it)
            }
        )
        EndPanelPointMobile(
            label = "Next-episode popup point",
            hint = "How far into the episode the card opens, as a percentage of its " +
                "runtime. A title with its own credits marker opens as the credits " +
                "start instead.",
            selected = nextEpisodePoint,
            enabled = nextEpisodePopup,
            onPick = { point ->
                nextEpisodePoint = point
                AppPreferences.setNextEpisodePopupPointTenths(context, point)
            }
        )
        SettingsToggleMobile(
            label = "Because You Watched",
            description = "Add a personalised row to Home.",
            checked = becauseYouWatched,
            onChange = {
                becauseYouWatched = it
                AppPreferences.setBecauseYouWatched(context, it)
            }
        )
        EndPanelPointMobile(
            label = "Because You Watched point",
            hint = "How far into the title the picks open.",
            selected = becausePoint,
            enabled = becauseYouWatched,
            onPick = { point ->
                becausePoint = point
                AppPreferences.setBecauseYouWatchedPointTenths(context, point)
            }
        )
        SettingsToggleMobile(
            label = "Prefer binge-group sources",
            description = "Prefer a source that matches the one the episode you just " +
                "finished came from.",
            checked = bingePrefer,
            onChange = {
                bingePrefer = it
                AppPreferences.setBingeGroupPrefer(context, it)
            }
        )
        SettingsToggleMobile(
            label = "Reuse the binge group",
            description = "Keep using that source's release group for the next episode.",
            checked = bingeReuse,
            onChange = {
                bingeReuse = it
                AppPreferences.setBingeGroupReuse(context, it)
            }
        )
        SettingsToggleMobile(
            label = "Binge-group fallback",
            description = "Fall back to the ordinary ranking when the group has no link.",
            checked = bingeFallback,
            onChange = {
                bingeFallback = it
                AppPreferences.setBingeGroupFallback(context, it)
            }
        )
        SettingsToggleMobile(
            label = "Still-there prompt",
            description = "Ask before carrying on when nothing has been played for a while.",
            checked = stillThere,
            onChange = {
                stillThere = it
                AppPreferences.setStillTherePrompt(context, it)
            }
        )
        Text(
            text = "Prompt after",
            color = if (stillThere) KBTextHi else KBTextLo,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(2L, 3L, 5L, 8L).forEach { count ->
                SettingsPillMobile(
                    label = "$count eps",
                    selected = stillThereEpisodes == count,
                    onClick = {
                        stillThereEpisodes = count
                        AppPreferences.setStillThereEpisodes(context, count)
                    }
                )
            }
        }
        SettingsCaptionMobile(
            "How many back-to-back auto-played episodes may run before the prompt " +
                "asks whether anyone is still there."
        )
        SettingsToggleMobile(
            label = "Auto-skip intros",
            description = "Skip intro segments when a matching marker exists.",
            checked = autoSkipIntro,
            onChange = {
                autoSkipIntro = it
                AppPreferences.setAutoSkipIntro(context, it)
            }
        )
        SettingsToggleMobile(
            label = "Auto-skip credits",
            description = "Jump to the next episode at the credits marker.",
            checked = autoSkipCredits,
            onChange = {
                autoSkipCredits = it
                AppPreferences.setAutoSkipCredits(context, it)
            }
        )

        SettingsSectionMobile("Audio")
        SettingsChoiceMobile(
            label = "Decoder priority",
            options = listOf(
                "Device only" to AppPreferences.AUDIO_DECODER_DEVICE_ONLY,
                "Prefer device" to AppPreferences.AUDIO_DECODER_PREFER_DEVICE,
                "Prefer app" to AppPreferences.AUDIO_DECODER_PREFER_APP
            ),
            selected = audioDecoder,
            onSelect = { value ->
                audioDecoder = value
                AppPreferences.setAudioDecoder(context, value)
            },
            description = "Prefer app decoding for formats the device refuses."
        )
        SettingsChoiceMobile(
            label = "Output",
            // The shared option lists, not a second copy of the values: this is
            // the same list the TV row and the in-player panel draw.
            options = PlayerAudioTuning.AUDIO_OUTPUT_OPTIONS,
            selected = audioOutput,
            onSelect = { value ->
                audioOutput = value
                AppPreferences.setAudioOutput(context, value)
            },
            description = "Passthrough sends the bitstream to an AVR; decode lets the " +
                "device mix it."
        )
        SettingsChoiceMobile(
            label = "Downmix",
            options = PlayerAudioTuning.DOWNMIX_OPTIONS,
            selected = audioDownmix,
            onSelect = { value ->
                audioDownmix = value
                AppPreferences.setAudioDownmix(context, value)
            },
            description = "Stereo folds surround into two channels for a TV's own speakers."
        )
        DialogueBoostMobile(
            level = audioDialogueBoost,
            onLevelChange = {
                audioDialogueBoost = it
                AppPreferences.setAudioDialogueBoost(context, it)
            }
        )
        SettingsChoiceMobile(
            label = "Volume boost",
            options = PlayerAudioTuning.VOLUME_OPTIONS,
            selected = audioVolumeBoost,
            onSelect = { value ->
                audioVolumeBoost = value
                AppPreferences.setAudioVolumeBoostDb(context, value)
            },
            description = "Extra gain for quiet mixes, with a limiter so loud scenes " +
                "do not distort. Applies to the app's PCM path; a title can override " +
                "it from the player's panel."
        )

        if (kidsProfile && playerEngine == AppPreferences.PLAYER_ENGINE_EXTERNAL) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "This profile uses a kids lock, so titles open in ExoPlayer: the " +
                    "time limits are enforced inside the app's own player and cannot " +
                    "follow a title into another app.",
                color = KBDanger,
                style = MaterialTheme.typography.labelSmall
            )
        }

        // The external-player choices appear when there is a player to pick, a
        // hand-picked app to show, or the engine IS External. Same rule as the
        // TV subsection, so the two screens offer it in the same states.
        if (!kidsProfile &&
            (externalPlayers.isNotEmpty() ||
                customExternalPackage != null ||
                playerEngine == AppPreferences.PLAYER_ENGINE_EXTERNAL)
        ) {
            SettingsSectionMobile("External player")
            Text(
                text = "Which app plays a title when the engine above is External. " +
                    "KBStream keeps the session either way: watch history, Continue " +
                    "Watching, scrobbling and the end-of-episode cards still work.",
                color = KBTextLo,
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                externalPlayers.forEach { player ->
                    SettingsPillMobile(
                        label = player.label,
                        selected = customExternalPackage == null &&
                            externalPackage == player.packageName,
                        onClick = {
                            externalPackage = player.packageName
                            // A hand-picked app outranks the probe, so touching a
                            // detected chip drops it - otherwise the chip would
                            // stay unselected after the press.
                            customExternalPackage = null
                            customExternalLabel = null
                            AppPreferences.setCustomExternalPlayer(context, null, null)
                            externalAsk = false
                            AppPreferences.setExternalPlayer(
                                context,
                                player.packageName,
                                player.label
                            )
                            AppPreferences.setExternalPlayerAsk(context, false)
                        }
                    )
                }
                SettingsPillMobile(
                    label = customExternalLabel ?: "Choose custom app…",
                    selected = customExternalPackage != null,
                    onClick = {
                        showCustomPlayerPicker = true
                        showExternalPlayerPicker = false
                    }
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            val rememberedPlayer = externalPlayers
                .firstOrNull { it.packageName == externalPackage }
            val rememberedName = customExternalPackage
                ?.let { customExternalLabel ?: it }
                ?: rememberedPlayer?.label
            Text(
                text = when {
                    externalAsk -> "Titles will ask which app to use, every time."
                    rememberedName != null ->
                        "Remembered - titles play in $rememberedName with no prompt."
                    else ->
                        "Nothing picked yet: the first title opens the system chooser, " +
                            "and whatever you pick there is remembered."
                },
                color = if (externalAsk) KBTextLo else KBAccent,
                style = MaterialTheme.typography.labelSmall
            )
            SettingsToggleMobile(
                label = "Ask every time",
                description = "Show the system's own chooser for each title instead of " +
                    "going straight to the app picked above.",
                checked = externalAsk,
                onChange = {
                    externalAsk = it
                    AppPreferences.setExternalPlayerAsk(context, it)
                }
            )
            SettingsToggleMobile(
                label = "Treat coming back as the end",
                description = "Your player usually cannot say where you stopped, so " +
                    "KBStream measures the playhead while it is in front. With this " +
                    "on, returning from the player counts as finishing the title.",
                checked = externalTrustReturn,
                onChange = {
                    externalTrustReturn = it
                    AppPreferences.setExternalTrustReturn(context, it)
                }
            )

            if (externalPlayers.isEmpty() && customExternalPackage == null) {
                Text(
                    text = "No video player app was detected, so ExoPlayer plays " +
                        "instead. Install VLC, MX Player or Kodi, or pick the app by hand.",
                    color = KBDanger,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

        if (showExternalPlayerPicker) {
            ExternalPlayerPickerMobile(
                players = externalPlayers,
                selectedPackage = externalPackage,
                askEachTime = externalAsk,
                onPick = { player ->
                    externalPackage = player.packageName
                    customExternalPackage = null
                    customExternalLabel = null
                    AppPreferences.setCustomExternalPlayer(context, null, null)
                    AppPreferences.setExternalPlayer(
                        context,
                        player.packageName,
                        player.label
                    )
                    externalAsk = false
                    AppPreferences.setExternalPlayerAsk(context, false)
                    showExternalPlayerPicker = false
                },
                onAskEachTime = {
                    externalAsk = true
                    AppPreferences.setExternalPlayerAsk(context, true)
                    showExternalPlayerPicker = false
                },
                onChooseCustom = {
                    showExternalPlayerPicker = false
                    showCustomPlayerPicker = true
                },
                onDismiss = { showExternalPlayerPicker = false }
            )
        }

        if (showCustomPlayerPicker) {
            CustomPlayerPickerMobile(
                apps = ExternalPlayer.allApps(context),
                selectedPackage = customExternalPackage,
                onPick = { app ->
                    customExternalPackage = app.packageName
                    customExternalLabel = app.label
                    AppPreferences.setCustomExternalPlayer(
                        context,
                        app.packageName,
                        app.label
                    )
                    externalAsk = false
                    AppPreferences.setExternalPlayerAsk(context, false)
                    showCustomPlayerPicker = false
                },
                onClear = {
                    customExternalPackage = null
                    customExternalLabel = null
                    AppPreferences.setCustomExternalPlayer(context, null, null)
                    showCustomPlayerPicker = false
                },
                onDismiss = { showCustomPlayerPicker = false }
            )
        }
    }
}

@Composable
private fun VideoPaneMobile() {
    val context = LocalContext.current
    var bufferMode by remember { mutableStateOf(AppPreferences.getDefaultBufferMode(context)) }
    var pip by remember { mutableStateOf(AppPreferences.getEnablePip(context)) }
    var aspectRatio by remember { mutableStateOf(AppPreferences.getDefaultAspectRatio(context)) }

    PaneScroll {
        SettingsSectionMobile("Buffering & Playback", first = true)
        SettingsChoiceMobile(
            label = "Network buffer",
            options = listOf("Auto" to 2, "Balanced (4K-ready)" to 0, "Low latency" to 1),
            selected = bufferMode,
            onSelect = {
                bufferMode = it
                AppPreferences.setDefaultBufferMode(context, it)
            },
            description = when (bufferMode) {
                0 -> "15s/60s buffer — best for movies & series"
                1 -> "2.5s/10s buffer — best for live IPTV"
                else -> "Auto-detects IPTV vs movies & series"
            }
        )
        // "Tunneled playback" is deliberately absent: it exists to keep A/V in
        // sync over an HDMI link into an AVR, which a phone does not have. The
        // stored preference is left exactly as it was, so the TV that shares
        // the profile keeps its setting.
        SettingsToggleMobile(
            label = "Picture-in-Picture",
            description = "Auto-enter PiP when leaving the app.",
            checked = pip,
            onChange = {
                pip = it
                AppPreferences.setEnablePip(context, it)
            }
        )

        SettingsSectionMobile("Picture")
        SettingsChoiceMobile(
            label = "Default aspect ratio",
            options = listOf(
                "Fit" to 0,
                "Zoom" to 1,
                "Fill" to 2,
                "16:9" to 3,
                "4:3" to 4
            ),
            selected = aspectRatio,
            onSelect = {
                aspectRatio = it
                AppPreferences.setDefaultAspectRatio(context, it)
            }
        )
    }
}

@Composable
private fun LanguagePaneMobile() {
    val context = LocalContext.current
    val syncedRevision by PrefsPayloadApplier.revision.collectAsStateWithLifecycle()
    var audioLang by remember(syncedRevision) {
        mutableStateOf(AppPreferences.getPreferredAudioLanguage(context))
    }
    var subtitleLang by remember(syncedRevision) {
        mutableStateOf(AppPreferences.getPreferredSubtitleLanguage(context))
    }

    PaneScroll {
        SettingsSectionMobile("Audio Language", first = true)
        SettingsLanguageChoiceMobile(
            label = "Preferred audio language",
            selected = audioLang,
            onSelect = { code ->
                audioLang = code
                AppPreferences.setPreferredAudioLanguage(context, code)
            }
        )

        SettingsSectionMobile("Subtitles")
        SettingsLanguageChoiceMobile(
            label = "Preferred subtitle language",
            selected = subtitleLang,
            onSelect = { code ->
                subtitleLang = code
                AppPreferences.setPreferredSubtitleLanguage(context, code)
            }
        )
    }
}

@Composable
private fun SubtitlesPaneMobile() {
    val context = LocalContext.current
    val syncedRevision by PrefsPayloadApplier.revision.collectAsStateWithLifecycle()
    var mode by remember(syncedRevision) {
        mutableStateOf(AppPreferences.getSubtitleMode(context))
    }
    var size by remember(syncedRevision) {
        mutableStateOf(AppPreferences.getDefaultSubtitleSize(context))
    }
    var background by remember(syncedRevision) {
        mutableStateOf(AppPreferences.getDefaultSubtitleBackground(context))
    }
    var position by remember(syncedRevision) {
        mutableStateOf(AppPreferences.getDefaultSubtitlePosition(context))
    }
    var cleanSdh by remember(syncedRevision) {
        mutableStateOf(AppPreferences.getCleanSdhCaptions(context))
    }

    PaneScroll {
        SettingsSectionMobile("Behavior", first = true)
        SettingsChoiceMobile(
            label = "Mode",
            options = SubtitleModeRules.OPTIONS,
            selected = mode,
            onSelect = {
                mode = it
                AppPreferences.setSubtitleMode(context, it)
            },
            description = "Off never shows subtitles on its own; Forced only shows " +
                "foreign dialogue and signs when the file marks them; On follows the " +
                "preferred language."
        )

        SettingsSectionMobile("Appearance")
        SettingsChoiceMobile(
            label = "Default size",
            options = listOf("Small" to 0, "Normal" to 1, "Large" to 2),
            selected = size,
            onSelect = {
                size = it
                AppPreferences.setDefaultSubtitleSize(context, it)
            }
        )
        SettingsChoiceMobile(
            label = "Default background",
            options = listOf("None" to 0, "Semi" to 1, "Solid" to 2, "Text" to 3),
            selected = background,
            onSelect = {
                background = it
                AppPreferences.setDefaultSubtitleBackground(context, it)
            }
        )
        SettingsChoiceMobile(
            label = "Position",
            options = listOf("Low" to 0, "Mid" to 1, "High" to 2),
            selected = position,
            onSelect = {
                position = it
                AppPreferences.setDefaultSubtitlePosition(context, it)
            },
            description = "Lifts captions above letterbox bars or burned-in signage."
        )
        SettingsToggleMobile(
            label = "Clean SDH",
            description = "Keep dialogue and drop [sound effects], ♪ lyrics ♪ and " +
                "SPEAKER: names from captions for the deaf and hard of hearing.",
            checked = cleanSdh,
            onChange = {
                cleanSdh = it
                AppPreferences.setCleanSdhCaptions(context, it)
            }
        )
    }
}

@Composable
private fun InterfacePaneMobile() {
    val context = LocalContext.current
    val syncedRevision by PrefsPayloadApplier.revision.collectAsStateWithLifecycle()

    var newEpisodeNotifications by remember(syncedRevision) {
        mutableStateOf(AppPreferences.getNewEpisodeNotifications(context))
    }
    var liveReminders by remember(syncedRevision) {
        mutableStateOf(AppPreferences.getLiveReminderNotifications(context))
    }
    var amoled by remember(syncedRevision) {
        mutableStateOf(AppPreferences.getAmoledBlack(context))
    }
    var pureBlack by remember(syncedRevision) {
        mutableStateOf(AppPreferences.getPureBlackSurface(context))
    }
    var heroAutoplay by remember(syncedRevision) {
        mutableStateOf(AppPreferences.getHeroTrailerAutoplay(context))
    }
    var heroMuted by remember(syncedRevision) {
        mutableStateOf(AppPreferences.getHeroTrailerMuted(context))
    }
    var heroDelay by remember(syncedRevision) {
        mutableStateOf(AppPreferences.getHeroTrailerDelayMs(context))
    }
    var use24h by remember(syncedRevision) {
        mutableStateOf(AppPreferences.getUse24HourClock(context))
    }
    var railType by remember(syncedRevision) {
        mutableStateOf(AppPreferences.getHomeRailShowCatalogType(context))
    }
    var railAddon by remember(syncedRevision) {
        mutableStateOf(AppPreferences.getHomeRailShowAddonName(context))
    }
    var searchRailType by remember(syncedRevision) {
        mutableStateOf(AppPreferences.getSearchRailShowCatalogType(context))
    }
    var searchRailAddon by remember(syncedRevision) {
        mutableStateOf(AppPreferences.getSearchRailShowAddonName(context))
    }
    var captionTitle by remember(syncedRevision) {
        mutableStateOf(AppPreferences.getPosterCaptionTitle(context))
    }
    var captionYear by remember(syncedRevision) {
        mutableStateOf(AppPreferences.getPosterCaptionYear(context))
    }
    var captionRating by remember(syncedRevision) {
        mutableStateOf(AppPreferences.getPosterCaptionRating(context))
    }
    var spoilerFree by remember(syncedRevision) {
        mutableStateOf(AppPreferences.getSpoilerFree(context))
    }
    var episodeRail by remember(syncedRevision) {
        mutableStateOf(AppPreferences.getDetailEpisodeRail(context))
    }
    var posterSize by remember(syncedRevision) {
        mutableStateOf(AppPreferences.getPosterSize(context).toInt())
    }
    var posterBorder by remember(syncedRevision) {
        mutableStateOf(AppPreferences.getPosterBorderStrength(context))
    }
    var posterEdge by remember(syncedRevision) {
        mutableStateOf(AppPreferences.getPosterEdge(context))
    }
    var landscapeCards by remember(syncedRevision) {
        mutableStateOf(AppPreferences.getHomeLandscapeCards(context))
    }
    var landscapePosters by remember(syncedRevision) {
        mutableStateOf(AppPreferences.getLandscapePosters(context))
    }
    var partialBadge by remember(syncedRevision) {
        mutableStateOf(AppPreferences.getPosterPartialWatchBadge(context))
    }
    var hideUpcoming by remember(syncedRevision) {
        mutableStateOf(AppPreferences.getHomeRailHideUpcoming(context))
    }
    var englishOnly by remember(syncedRevision) {
        mutableStateOf(AppPreferences.getBrowseEnglishOnly(context))
    }

    PaneScroll {
        SettingsSectionMobile("Notifications", first = true)
        SettingsToggleMobile(
            label = "New episode notifications",
            description = "Tell me when a followed show has a new episode.",
            checked = newEpisodeNotifications,
            onChange = {
                newEpisodeNotifications = it
                AppPreferences.setNewEpisodeNotifications(context, it)
            }
        )
        SettingsToggleMobile(
            label = "Live TV reminders",
            description = "Tell me when a program I set a reminder for is starting.",
            checked = liveReminders,
            onChange = {
                liveReminders = it
                AppPreferences.setLiveReminderNotifications(context, it)
            }
        )

        SettingsSectionMobile("Theme")
        SettingsToggleMobile(
            label = "AMOLED black",
            description = "Use true black for the app background.",
            checked = amoled,
            onChange = {
                amoled = it
                AppPreferences.setAmoledBlack(context, it)
            }
        )
        SettingsToggleMobile(
            label = "Pure black surfaces",
            description = "Paint card plates black as well as the background.",
            checked = pureBlack,
            onChange = {
                pureBlack = it
                AppPreferences.setPureBlackSurface(context, it)
            }
        )
        AccentColorPickerMobile()

        SettingsSectionMobile("Hero & Clock")
        SettingsToggleMobile(
            label = "Hero trailer autoplay",
            description = "Play a trailer in the Home hero after a pause.",
            checked = heroAutoplay,
            onChange = {
                heroAutoplay = it
                AppPreferences.setHeroTrailerAutoplay(context, it)
            }
        )
        HeroTrailerDelayMobile(
            selectedMs = heroDelay,
            enabled = heroAutoplay,
            onPick = { ms ->
                heroDelay = ms
                AppPreferences.setHeroTrailerDelayMs(context, ms)
            }
        )
        SettingsToggleMobile(
            label = "Mute hero trailers",
            description = "Play the Home hero trailer without sound.",
            checked = heroMuted,
            onChange = {
                heroMuted = it
                AppPreferences.setHeroTrailerMuted(context, it)
            }
        )
        SettingsToggleMobile(
            label = "24-hour clock",
            description = "Show the player overlay clock in 24-hour time.",
            checked = use24h,
            onChange = {
                use24h = it
                AppPreferences.setUse24HourClock(context, it)
            }
        )

        SettingsSectionMobile("Home Rails")
        SettingsToggleMobile(
            label = "Home rail type",
            description = "Show each rail's catalog type after its name.",
            checked = railType,
            onChange = {
                railType = it
                AppPreferences.setHomeRailShowCatalogType(context, it)
            }
        )
        SettingsToggleMobile(
            label = "Home rail add-on name",
            description = "Show the add-on that provides each rail.",
            checked = railAddon,
            onChange = {
                railAddon = it
                AppPreferences.setHomeRailShowAddonName(context, it)
            }
        )
        SettingsToggleMobile(
            label = "Search rail type",
            description = "Show each search rail's catalog type after its name.",
            checked = searchRailType,
            onChange = {
                searchRailType = it
                AppPreferences.setSearchRailShowCatalogType(context, it)
            }
        )
        SettingsToggleMobile(
            label = "Search rail add-on name",
            description = "Show the add-on that provides each search rail.",
            checked = searchRailAddon,
            onChange = {
                searchRailAddon = it
                AppPreferences.setSearchRailShowAddonName(context, it)
            }
        )
        SettingsToggleMobile(
            label = "Hide the Upcoming rail",
            description = "Take the Upcoming row off Home.",
            checked = hideUpcoming,
            onChange = {
                hideUpcoming = it
                AppPreferences.setHomeRailHideUpcoming(context, it)
            }
        )

        SettingsSectionMobile("Posters & Browsing")
        SettingsToggleMobile(
            label = "Poster titles",
            description = "Draw each poster's title under it.",
            checked = captionTitle,
            onChange = {
                captionTitle = it
                AppPreferences.setPosterCaptionTitle(context, it)
            }
        )
        SettingsToggleMobile(
            label = "Poster years",
            description = "Draw each poster's year under it.",
            checked = captionYear,
            onChange = {
                captionYear = it
                AppPreferences.setPosterCaptionYear(context, it)
            }
        )
        SettingsToggleMobile(
            label = "Poster ratings",
            description = "Draw each poster's rating under it.",
            checked = captionRating,
            onChange = {
                captionRating = it
                AppPreferences.setPosterCaptionRating(context, it)
            }
        )
        SettingsToggleMobile(
            label = "Spoiler-free mode",
            description = "Hide episode names and stills you have not started.",
            checked = spoilerFree,
            onChange = {
                spoilerFree = it
                AppPreferences.setSpoilerFree(context, it)
            }
        )
        // The Detail page's episode area: vertical rows or the TV-style
        // sideways rail. Device-local (see AppPreferences.getDetailEpisodeRail),
        // but a viewing preference, so it sits with the display rows instead of
        // as a control on every season the viewer opens.
        SettingsChoiceMobile(
            label = "Episode layout",
            options = listOf("List" to 0, "Rail" to 1),
            selected = if (episodeRail) 1 else 0,
            onSelect = { value ->
                val rail = value == 1
                episodeRail = rail
                AppPreferences.setDetailEpisodeRail(context, rail)
            },
            description = "Detail page: vertical episode rows, or the television's sideways rail."
        )
        SettingsChoiceMobile(
            label = "Poster size",
            options = listOf("Small" to 0, "Medium" to 1, "Large" to 2),
            selected = posterSize,
            onSelect = { value ->
                posterSize = value
                AppPreferences.setPosterSize(context, value.toLong())
            }
        )
        SettingsChoiceMobile(
            label = "Poster border",
            options = listOf(
                "Off" to 0,
                "Subtle" to 1,
                "Medium" to 2,
                "Strong" to 3
            ),
            selected = posterBorder,
            onSelect = { value ->
                posterBorder = value
                AppPreferences.setPosterBorderStrength(context, value)
            }
        )
        SettingsChoiceMobile(
            label = "Poster edge",
            options = listOf("Straight" to 0, "Rounded" to 1, "Pill" to 2),
            selected = posterEdge,
            onSelect = { value ->
                posterEdge = value
                AppPreferences.setPosterEdge(context, value)
            }
        )
        SettingsToggleMobile(
            label = "Landscape Home cards",
            description = "Draw Home's cards as backdrop + clearlogo.",
            checked = landscapeCards,
            onChange = {
                landscapeCards = it
                AppPreferences.setHomeLandscapeCards(context, it)
            }
        )
        SettingsToggleMobile(
            label = "Landscape posters",
            description = "Use landscape artwork for poster tiles app-wide.",
            checked = landscapePosters,
            onChange = {
                landscapePosters = it
                AppPreferences.setLandscapePosters(context, it)
            }
        )
        SettingsToggleMobile(
            label = "Partially-watched eye badge",
            description = "Mark started-but-unfinished titles with an eye.",
            checked = partialBadge,
            onChange = {
                partialBadge = it
                AppPreferences.setPosterPartialWatchBadge(context, it)
            }
        )
        SettingsToggleMobile(
            label = "English-only browse",
            description = "Hide non-English titles from the browse pages.",
            checked = englishOnly,
            onChange = {
                englishOnly = it
                AppPreferences.setBrowseEnglishOnly(context, it)
            }
        )
    }
}

@Composable
private fun DataPaneMobile(onOpenStats: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var clearing by remember { mutableStateOf(false) }
    var clearedAt by remember { mutableStateOf<Long?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                status = runCatching { BackupManager.export(context, uri) }
                    .getOrElse { "Export failed: ${it.message}" }
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                status = runCatching { BackupManager.import(context, uri) }
                    .getOrElse { "Import failed: ${it.message}" }
            }
        }
    }
    val importGetContent = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                status = runCatching { BackupManager.import(context, uri) }
                    .getOrElse { "Import failed: ${it.message}" }
            }
        }
    }

    PaneScroll {
        SettingsSectionMobile("Backup & Restore", first = true)
        SettingsNavRowMobile(
            label = "Export backup",
            description = "Save settings, add-ons & watched state to a file",
            onClick = {
                try {
                    exportLauncher.launch("kbstream-backup-${System.currentTimeMillis()}.json")
                } catch (_: ActivityNotFoundException) {
                    status = "No file picker on this device."
                }
            }
        )
        SettingsNavRowMobile(
            label = "Import backup",
            description = "Restore from a KBStream backup file",
            onClick = {
                try {
                    importLauncher.launch(
                        arrayOf("application/json", "text/plain", "application/octet-stream")
                    )
                } catch (_: ActivityNotFoundException) {
                    try {
                        importGetContent.launch("*/*")
                    } catch (_: ActivityNotFoundException) {
                        status = "No file picker on this device."
                    }
                }
            }
        )

        SettingsSectionMobile("History")
        SettingsNavRowMobile(
            label = "Viewing stats",
            description = "Finished episodes, movies, runtime and your streak",
            onClick = onOpenStats
        )
        SettingsNavRowMobile(
            label = if (clearing) "Clearing…" else "Clear Continue Watching",
            description = "Reset all resume positions and watched markers " +
                "(your Simkl link is kept)",
            onClick = {
                if (!clearing) confirmClear = true
            }
        )
        if (clearedAt != null) {
            Text(
                text = "Continue watching cleared.",
                color = KBAccent,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        status?.let { message ->
            Text(
                text = message,
                color = when {
                    message.contains("failed", ignoreCase = true) -> KBDanger
                    message.startsWith("Backup") -> KBAccent
                    else -> KBTextLo
                },
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }

    if (confirmClear) {
        Dialog(onDismissRequest = { confirmClear = false }) {
            KBDialogPanel(title = "Clear Continue Watching?") {
                Text(
                    text = "This erases every resume position and watched marker on " +
                        "this account — every device signed in loses the same state. " +
                        "Your Simkl link is kept.",
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    KBButton(
                        label = "CANCEL",
                        modifier = Modifier.weight(1f),
                        onClick = { confirmClear = false }
                    )
                    KBButton(
                        label = "CLEAR",
                        danger = true,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            confirmClear = false
                            if (clearing) return@KBButton
                            clearing = true
                            scope.launch {
                                // Cloud first, and awaited: a local wipe followed
                                // by a pull merge restored every resume bar and
                                // completed marker (see the TV confirm's note).
                                runCatching {
                                    SupabaseSync.clearWatchStateForActiveProfile()
                                    WatchHistoryRepository(context).clearAll()
                                    WatchedStatusRepository(context)
                                        .clearLocalWatchState(clearSimklAuth = false)
                                }
                                clearing = false
                                clearedAt = System.currentTimeMillis()
                            }
                        }
                    )
                }
            }
        }
    }
}

/** The hue bar's sweep, flattened to a gradient - the same colours as the TV bar. */
private val AccentHueBrushMobile = Brush.horizontalGradient(
    listOf(
        Color(0xFFFF0000),
        Color(0xFFFFFF00),
        Color(0xFF00FF00),
        Color(0xFF00FFFF),
        Color(0xFF0000FF),
        Color(0xFFFF00FF),
        Color(0xFFFF0000)
    )
)

/**
 * The global theme accent, for a finger.
 *
 * The same palette and the same stored index the TV grid writes, with the
 * custom colour mixed on three bars - touch sliders instead of D-pad bars,
 * because a phone has a pointer. The mixing maths are the theme's own
 * (`hsvFromArgb` / `argbFromHsv`), so a colour mixed here is byte-identical to
 * the one the TV would produce from the same bars.
 *
 * A slider drag updates the preview only; the pref is written on release (the
 * TV writes per key step, which is a handful of discrete presses - a drag would
 * be hundreds of writes). Picking a palette swatch applies immediately.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AccentColorPickerMobile() {
    val context = LocalContext.current
    val syncedRevision by PrefsPayloadApplier.revision.collectAsStateWithLifecycle()
    var selectedIndex by remember(syncedRevision) {
        mutableStateOf(AppPreferences.getAccentIndex(context, DEFAULT_ACCENT_INDEX))
    }
    // Seeded once: an edit in progress must not be yanked out from under the
    // viewer by a sync arriving mid-drag. The seed reads the theme's mirrored
    // custom colour (refreshed at launch and on every apply), not the pref
    // directly - a bare pref read here would be a synced read frozen at first
    // composition, the exact bug SyncedPrefComposeReadsContractTest exists for.
    val seed = remember {
        hsvFromArgb((kbCustomAccentState.value ?: KBAccent).toArgb())
    }
    var hue by remember { mutableFloatStateOf(seed.hue) }
    var saturation by remember { mutableFloatStateOf(seed.saturation) }
    var brightness by remember { mutableFloatStateOf(seed.brightness) }
    val storedCustom = kbCustomAccentState.value
    val mixed = Color(argbFromHsv(hue, saturation, brightness))

    fun applyCustom() {
        AppPreferences.setCustomAccent(context, argbFromHsv(hue, saturation, brightness))
        AppPreferences.setAccentIndex(context, CUSTOM_ACCENT_INDEX)
        selectedIndex = CUSTOM_ACCENT_INDEX
        refreshThemeMirrors(context)
    }

    Text(
        text = "Accent color",
        color = KBTextHi,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 10.dp)
    )
    SettingsCaptionMobile(
        "The highlight used across the whole app - buttons, badges, progress bars " +
            "and focus rings. Applies instantly."
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        KBAccentPalette.forEachIndexed { index, entry ->
            AccentSwatchMobile(
                name = entry.name,
                color = entry.color,
                selected = selectedIndex == index,
                onClick = {
                    selectedIndex = index
                    AppPreferences.setAccentIndex(context, index)
                    refreshThemeMirrors(context)
                }
            )
        }
        AccentSwatchMobile(
            name = "Custom",
            color = if (selectedIndex == CUSTOM_ACCENT_INDEX) {
                mixed
            } else {
                storedCustom ?: KBTextLo
            },
            selected = selectedIndex == CUSTOM_ACCENT_INDEX,
            onClick = {
                // Picking Custom adopts the bars' current colour, so the entry
                // is never a no-op that leaves the previous accent in place.
                applyCustom()
            }
        )
    }

    Text(
        text = "Custom color",
        color = KBTextHi,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 12.dp)
    )
    SettingsCaptionMobile("Drag the bars until the highlight looks right - no color code needed.")

    AccentSliderMobile(
        label = "Hue",
        valueText = "${hue.roundToInt()}\u00b0",
        value = hue,
        valueRange = 0f..359f,
        trackBrush = AccentHueBrushMobile,
        onValueChange = { hue = it },
        onFinished = { applyCustom() }
    )
    AccentSliderMobile(
        label = "Saturation",
        valueText = "${(saturation * 100f).roundToInt()}%",
        value = saturation,
        valueRange = 0f..1f,
        trackBrush = Brush.horizontalGradient(
            listOf(Color.Gray, Color(argbFromHsv(hue, 1f, brightness)))
        ),
        onValueChange = { saturation = it },
        onFinished = { applyCustom() }
    )
    AccentSliderMobile(
        label = "Brightness",
        valueText = "${(brightness * 100f).roundToInt()}%",
        value = brightness,
        valueRange = 0f..1f,
        trackBrush = Brush.horizontalGradient(
            listOf(Color.Black, Color(argbFromHsv(hue, saturation, 1f)))
        ),
        onValueChange = { brightness = it },
        onFinished = { applyCustom() }
    )
}

/** One palette entry: a colour dot, its name, and a ring when it is the pick. */
@Composable
private fun AccentSwatchMobile(
    name: String,
    color: Color,
    selected: Boolean,
    onClick: () -> Unit
) {
    KBCard(
        onClick = onClick,
        shape = KBShapePill,
        modifier = if (selected) {
            Modifier.border(2.dp, KBAccent, KBShapePill)
        } else {
            Modifier
        }
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .background(color, KBShapePill)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = name,
                color = KBTextHi,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
        }
    }
}

/**
 * One mixing bar. The track is the gradient the bar produces, so the viewer
 * sees what it does; [onFinished] is where the pref write happens, so a drag is
 * one write rather than one per frame.
 */
@Composable
private fun AccentSliderMobile(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    trackBrush: Brush,
    onValueChange: (Float) -> Unit,
    onFinished: () -> Unit
) {
    Column(modifier = Modifier.padding(top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                color = KBTextHi,
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = valueText,
                color = KBTextLo,
                style = MaterialTheme.typography.labelSmall
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onFinished,
            valueRange = valueRange,
            // Transparent tracks: the box's gradient IS the bar, and Material's
            // own track would paint over it. The thumb stays opaque so it reads
            // against every hue.
            colors = SliderDefaults.colors(
                thumbColor = KBTextHi,
                activeTrackColor = Color.Transparent,
                inactiveTrackColor = Color.Transparent
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .background(trackBrush, KBShapePill)
        )
    }
}

/**
 * The touch twin of `ui/settings/DialogueBoostRow.kt`: a level, minus and plus,
 * with the same [PlayerAudioTuning.dialogueLevelText] wording so the value on
 * screen is the value being heard.
 */
@Composable
private fun DialogueBoostMobile(level: Int, onLevelChange: (Int) -> Unit) {
    Text(
        text = "Dialogue boost",
        color = KBTextHi,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SettingsPillMobile(
            label = "-",
            selected = false,
            onClick = { onLevelChange((level - 1).coerceAtLeast(0)) }
        )
        Text(
            text = PlayerAudioTuning.dialogueLevelText(level),
            color = KBTextHi,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(96.dp)
        )
        SettingsPillMobile(
            label = "+",
            selected = false,
            onClick = {
                onLevelChange((level + 1).coerceAtMost(PlayerAudioTuning.DIALOGUE_MAX))
            }
        )
    }
    SettingsCaptionMobile(
        "Each step lifts voices over score and effects, and trims the surrounds to " +
            "match. Off is the untouched mix; a title can override this from the " +
            "player's own panel."
    )
}

/** Which installed video app receives a title when the engine is External. */
@Composable
private fun ExternalPlayerPickerMobile(
    players: List<ExternalPlayer.Installed>,
    selectedPackage: String?,
    askEachTime: Boolean,
    onPick: (ExternalPlayer.Installed) -> Unit,
    onAskEachTime: () -> Unit,
    onChooseCustom: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        KBDialogPanel(title = "Play titles in which app?") {
            Text(
                text = "Every video player detected on this device. Your pick is " +
                    "remembered for every title. KBStream keeps the session either " +
                    "way: history, scrobbling and the end-of-episode cards stay here.",
                color = KBTextLo,
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            players.forEach { player ->
                KBButton(
                    label = player.label,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { onPick(player) }
                )
            }
            KBButton(
                label = "Choose custom app…",
                modifier = Modifier.fillMaxWidth(),
                onClick = onChooseCustom
            )
            KBButton(
                label = if (askEachTime) {
                    "ASK EVERY TIME (ON)"
                } else {
                    "ASK EVERY TIME (SYSTEM CHOOSER)"
                },
                modifier = Modifier.fillMaxWidth(),
                onClick = onAskEachTime
            )
            KBButton(
                label = "DONE",
                modifier = Modifier.fillMaxWidth(),
                onClick = onDismiss
            )
        }
    }
}

/**
 * Every app installed here, for the hand-picked choice - the way in for a player
 * the video probe cannot see. The list is the whole device, so it scrolls inside
 * a bounded height; the first row puts the probe back in charge, so the override
 * is never a one-way door.
 */
@Composable
private fun CustomPlayerPickerMobile(
    apps: List<ExternalPlayer.Installed>,
    selectedPackage: String?,
    onPick: (ExternalPlayer.Installed) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        KBDialogPanel(title = "Pick the app by hand") {
            Text(
                text = "Every app installed on this device, including one the picker " +
                    "cannot see. KBStream hands titles to the app you pick; an app that " +
                    "cannot take a video link just opens, and you choose the title there.",
                color = KBTextLo,
                style = MaterialTheme.typography.bodySmall
            )
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .padding(top = 10.dp)
            ) {
                if (selectedPackage != null) {
                    item(key = "clear") {
                        KBButton(
                            label = "USE THE DETECTED PLAYER",
                            modifier = Modifier.fillMaxWidth(),
                            onClick = onClear
                        )
                    }
                }
                items(apps, key = { it.packageName }) { app ->
                    KBButton(
                        label = if (app.packageName == selectedPackage) {
                            "${app.label} · SELECTED"
                        } else {
                            app.label
                        },
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { onPick(app) }
                    )
                }
            }
            KBButton(
                label = "DONE",
                modifier = Modifier.fillMaxWidth(),
                onClick = onDismiss
            )
        }
    }
}

/**
 * The touch twin of `ui/settings/EndPanelPointRow.kt`.
 *
 * Same value, same unit (tenths of a percent) and the same two ways to reach
 * it: the quick jumps (99/98/97/95/90) and the 0.5 fine step, which is why the
 * option list and both formatters come from [AppPreferences] rather than a
 * second copy of the band. The control that differs is the chip row - a phone
 * has no D-pad to step a value with.
 *
 * Grayed out while the panel it configures is off, as on TV: a point nothing
 * opens at means nothing. The pills stay inert in that state.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EndPanelPointMobile(
    label: String,
    hint: String,
    selected: Int,
    enabled: Boolean,
    onPick: (Int) -> Unit
) {
    val dim = if (enabled) 1f else 0.35f
    Text(
        text = label,
        color = if (enabled) KBTextHi else KBTextLo,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.alpha(dim)
    ) {
        AppPreferences.END_PANEL_PERCENT_OPTIONS.forEach { percent ->
            val tenths = percent * 10
            SettingsPillMobile(
                label = "$percent%",
                selected = enabled && selected == tenths,
                onClick = { if (enabled) onPick(tenths) }
            )
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .padding(top = 8.dp)
            .alpha(dim)
    ) {
        SettingsPillMobile(
            label = "-0.5",
            selected = false,
            onClick = {
                if (enabled) onPick(AppPreferences.stepEndPanelPoint(selected, -1))
            }
        )
        Text(
            text = AppPreferences.endPanelPointLabel(selected),
            color = if (enabled) KBTextHi else KBTextLo,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.width(64.dp)
        )
        SettingsPillMobile(
            label = "+0.5",
            selected = false,
            onClick = {
                if (enabled) onPick(AppPreferences.stepEndPanelPoint(selected, 1))
            }
        )
    }
    SettingsCaptionMobile(hint)
}

/**
 * The touch twin of `ui/settings/HeroTrailerDelayRow.kt`: the same option list
 * and labels from [AppPreferences], greyed out while hero autoplay is off.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeroTrailerDelayMobile(
    selectedMs: Long,
    enabled: Boolean,
    onPick: (Long) -> Unit
) {
    val dim = if (enabled) 1f else 0.35f
    Text(
        text = "Hero trailer delay",
        color = if (enabled) KBTextHi else KBTextLo,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.alpha(dim)
    ) {
        AppPreferences.HERO_TRAILER_DELAY_OPTIONS_MS.forEach { ms ->
            SettingsPillMobile(
                label = AppPreferences.heroTrailerDelayLabel(ms),
                selected = enabled && selectedMs == ms,
                onClick = { if (enabled) onPick(ms) }
            )
        }
    }
    SettingsCaptionMobile(
        "How long focus must rest on a title before the hero starts its trailer. A " +
            "longer wait means scrolling a rail never starts one by accident; \"Now\" " +
            "starts it the moment a title is focused."
    )
}

/**
 * The touch twin of `ui/settings/ResumeAuthorityRow.kt`: the same labels, and
 * the same promise that the other trackers keep scrobbling.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResumeAuthorityMobile(
    selected: ResumeAuthority,
    onPick: (ResumeAuthority) -> Unit
) {
    Text(
        text = "Resume source",
        color = KBTextHi,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ResumeAuthority.entries.forEach { authority ->
            SettingsPillMobile(
                label = authority.label,
                selected = authority == selected,
                onClick = { onPick(authority) }
            )
        }
    }
    SettingsCaptionMobile(
        "Which tracker's position Continue Watching shows when more than one knows a " +
            "title. Every linked tracker still scrobbles and gets your watched marks; " +
            "the others fill in titles this one has not seen."
    )
}

/**
 * Sync health, for a finger.
 *
 * The same `SupabaseSync` flows and the same readouts the TV pane shows: who is
 * signed in, which profile is active, when the last pull and push landed, how
 * many rows are queued, the realtime channel state, the local row counts and
 * the cleanup's last sweep. A phone has no rail for the TV pane's status dot, so
 * the rows are the whole report.
 *
 * The three actions are the TV pane's three: a full resync, the two-tap eye
 * badge clear (a badge can survive the automatic cleanup, and this is the
 * deterministic escape hatch), and COPY DIAGNOSTICS. Each sits behind its own
 * description, in the same order as on TV.
 */
@Composable
private fun SyncPaneMobile() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val authState by SupabaseSync.authState.collectAsStateWithLifecycle()
    val lastPull by SupabaseSync.lastPullAtMs.collectAsStateWithLifecycle()
    val lastPush by SupabaseSync.lastPushAtMs.collectAsStateWithLifecycle()
    val pendingUploads by SupabaseSync.pendingOutboxCount.collectAsStateWithLifecycle()
    val realtime by SupabaseSync.realtimeStatus.collectAsStateWithLifecycle()
    val channels by SupabaseSync.realtimeChannelCount.collectAsStateWithLifecycle()
    val syncing by SupabaseSync.isSyncing.collectAsStateWithLifecycle()
    val activeProfile by ProfileManager.activeProfile.collectAsStateWithLifecycle()
    val profiles by ProfileManager.profiles.collectAsStateWithLifecycle()

    // Local row counts are read on demand and refreshed whenever a pull or push
    // completes, exactly as on TV.
    var localRows by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(lastPull, lastPush) {
        localRows = runCatching {
            val db = com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
                .getInstanceScoped(context)
            "watched cache ${db.watchedStatusDao().getAll().size} \u00b7 " +
                "history ${db.watchHistoryDao().getAll().size}"
        }.getOrNull()
    }

    val account = when (val state = authState) {
        is SupabaseSync.AuthState.SignedIn -> state.email
        is SupabaseSync.AuthState.SigningIn -> "signing in…"
        is SupabaseSync.AuthState.Error -> state.message
        else -> "signed out"
    }

    var confirmClearBadges by remember { mutableStateOf(false) }
    var clearBadgesStatus by remember { mutableStateOf<String?>(null) }
    var diagnosticsStatus by remember { mutableStateOf<String?>(null) }

    PaneScroll {
        SettingsSectionMobile("Sync health", first = true)
        SyncStatRowMobile("ACCOUNT", account)
        SyncStatRowMobile(
            "PROFILE",
            (activeProfile?.name ?: "none") +
                (if (profiles.size > 1) " (${profiles.size} profiles)" else "")
        )
        SyncStatRowMobile("LAST PULL", syncRelativeTime(lastPull))
        SyncStatRowMobile("LAST PUSH", syncRelativeTime(lastPush))
        SyncStatRowMobile(
            "PENDING UPLOADS",
            if (pendingUploads == 0) "none — everything uploaded" else "$pendingUploads row(s)"
        )
        SyncStatRowMobile("REALTIME", "$realtime ($channels channel(s))")
        SyncStatRowMobile("LOCAL DATA", localRows ?: "reading…")
        SyncStatRowMobile("CLEANUP", SupabaseSync.poisonSweepStatus(context))

        SettingsSectionMobile("Actions")
        KBButton(
            label = if (syncing) "SYNCING…" else "FORCE FULL RESYNC",
            enabled = !syncing,
            modifier = Modifier.fillMaxWidth(),
            onClick = { SupabaseSync.forceFullResync(context) }
        )
        SettingsCaptionMobile(
            "Uploads pending changes, then pulls the account's history, watched " +
                "marks and settings for this profile."
        )

        Spacer(modifier = Modifier.height(10.dp))
        KBButton(
            label = if (confirmClearBadges) {
                "TAP AGAIN TO CONFIRM"
            } else {
                "CLEAR EYE BADGES \u00b7 ${activeProfile?.name ?: "this profile"}"
            },
            danger = confirmClearBadges,
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                if (confirmClearBadges) {
                    confirmClearBadges = false
                    clearBadgesStatus = "clearing…"
                    SupabaseSync.clearInProgressForActiveProfile(context) { outcome ->
                        clearBadgesStatus = outcome
                    }
                } else {
                    confirmClearBadges = true
                    clearBadgesStatus = null
                }
            }
        )
        SettingsCaptionMobile(
            clearBadgesStatus?.let { "Last run: $it" }
                ?: "Removes the started-but-unfinished (eye) badges for this profile " +
                "only, here and on the other devices. Completed history and " +
                "checkmarks are kept. Use this when a badge survives the cleanup. " +
                "The name above says which profile it clears."
        )

        Spacer(modifier = Modifier.height(10.dp))
        KBButton(
            label = "COPY DIAGNOSTICS",
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                scope.launch {
                    runCatchingCancellable {
                        val report = com.kennyb1201.kbstream.data.reporting.Diagnostics
                            .build(context)
                        com.kennyb1201.kbstream.data.reporting.Diagnostics
                            .copyToClipboard(context, report)
                        diagnosticsStatus = "copied \u00b7 also logged as DIAGNOSTICS"
                    }.onFailure {
                        diagnosticsStatus = "failed: ${it.message}"
                    }
                }
            }
        )
        SettingsCaptionMobile(
            diagnosticsStatus
                ?: "Build, device, account, sync health, pending uploads and this " +
                "session's caught errors — copied to the clipboard and written to " +
                "logcat (tag DIAGNOSTICS)."
        )

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Pull timestamps update when remote changes are merged; push " +
                "when local writes reach the cloud. \"Realtime\" is the live change " +
                "channel — if it reads stopped while signed in, use Force full " +
                "resync (remote changes still arrive on the next pull).",
            color = KBTextLo.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

/** One sync readout: the label keeps its width, the value takes the rest. */
@Composable
private fun SyncStatRowMobile(label: String, value: String) {
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

@Composable
private fun AboutPaneMobile() {
    val context = LocalContext.current
    val state by AppUpdater.state.collectAsStateWithLifecycle()
    val changelog by AppUpdater.latestChangelog.collectAsStateWithLifecycle()

    val label = when (val s = state) {
        is AppUpdater.UpdateState.Available -> "Update available — ${s.versionName}"
        is AppUpdater.UpdateState.Downloading -> "Downloading update… ${s.percent}%"
        is AppUpdater.UpdateState.ReadyToInstall -> "Update ready — installing…"
        is AppUpdater.UpdateState.Checking -> "Checking for updates…"
        is AppUpdater.UpdateState.Updated -> "Updated to ${s.versionName}"
        is AppUpdater.UpdateState.Failed -> "Update check failed — tap to retry"
        AppUpdater.UpdateState.UpToDate -> "You're up to date — check again"
        AppUpdater.UpdateState.Idle -> "Check for updates"
    }
    val description = when (val s = state) {
        is AppUpdater.UpdateState.Available ->
            "New KBStream ${s.versionName} is available — select to download and install"
        is AppUpdater.UpdateState.Downloading ->
            "Fetching the new APK — the app relaunches when done"
        is AppUpdater.UpdateState.ReadyToInstall -> "Handing the file to the system installer…"
        is AppUpdater.UpdateState.Updated -> "The last install finished — this is the new build"
        is AppUpdater.UpdateState.Failed -> s.message
        else -> "KBStream updates are published with each build"
    }

    PaneScroll {
        SettingsSectionMobile("Version", first = true)
        // A plain plate, not a card: this is read, never pressed (the TV About
        // pane makes the same choice for the same reason).
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(KBSurface, KBShapeCard)
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            SettingsCaptionMobile("VERSION")
            Text(
                text = "KBStream ${BuildConfig.VERSION_NAME}",
                color = KBTextHi,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        if (!BuildConfig.PLAY_BUILD && !BuildConfig.DEBUG) {
            Spacer(modifier = Modifier.height(10.dp))
            KBCard(
                onClick = {
                    when (val s = state) {
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
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Text(
                        text = label,
                        color = if (state is AppUpdater.UpdateState.Available) KBAccent else KBTextHi,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = description,
                        color = KBTextLo,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                    (state as? AppUpdater.UpdateState.Available)?.notes
                        ?.takeIf { it.isNotBlank() }
                        ?.let { notes ->
                            Text(
                                text = notes,
                                color = KBTextLo,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 8,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                }
            }
        }

        if (changelog.isNotBlank()) {
            SettingsSectionMobile("What changed")
            SettingsCaptionMobile(changelog)
        }

        SettingsSectionMobile("Data sources")
        SettingsCaptionMobile(
            "This product uses the TMDB API but is not endorsed or certified by TMDB."
        )
        SettingsCaptionMobile("Movie, TV and anime data from Simkl.")

        // The credit the pane ends on, exactly as the TV About pane does:
        // centred because it is about the app's author rather than the install,
        // and read-only like every other line in this pane.
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = "Made with ❤️ by Kenny B",
            color = KBTextLo.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/**
 * A settings toggle whose key is a SYNCED pref: reads go through the sync
 * revision so a flip on another box moves this control, and writes go straight
 * through to the store exactly as the TV row's do.
 */
@Composable
private fun SyncedToggleMobile(
    label: String,
    description: String,
    get: (android.content.Context) -> Boolean,
    set: (android.content.Context, Boolean) -> Unit
) {
    val context = LocalContext.current
    val revision by PrefsPayloadApplier.revision.collectAsStateWithLifecycle()
    var checked by remember(revision) { mutableStateOf(get(context)) }
    SettingsToggleMobile(
        label = label,
        description = description,
        checked = checked,
        onChange = {
            checked = it
            set(context, it)
        }
    )
}

/**
 * One API-key card: the field, PASTE, SAVE, and - when the key has a probe -
 * VERIFY with the answer the probe gave.
 */
@Composable
private fun KeyCardMobile(
    title: String,
    description: String,
    placeholder: String,
    loadedValue: String,
    onSave: (String) -> Unit,
    savedDescription: String,
    verify: (suspend () -> Pair<String?, String?>)? = null,
    savedHint: String = "Connected as"
) {
    val scope = rememberCoroutineScope()
    var value by remember { mutableStateOf(loadedValue) }
    var saved by remember { mutableStateOf(loadedValue.isNotBlank()) }
    var verifying by remember { mutableStateOf(false) }
    var verifyResult by remember { mutableStateOf<Pair<String?, String?>?>(null) }

    fun save() {
        onSave(value.trim())
        value = value.trim()
        saved = value.isNotBlank()
        verifyResult = null
    }

    Column(modifier = Modifier.padding(bottom = 14.dp)) {
        Text(
            text = title,
            color = KBTextHi,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = description,
            color = KBTextLo,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(top = 2.dp)
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
        ) {
            KBTextField(
                value = value,
                onValueChange = {
                    value = it
                    saved = false
                },
                placeholder = placeholder,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.weight(1f),
                onDone = { save() }
            )
            Spacer(modifier = Modifier.padding(start = 8.dp))
            KBPasteChip(onPaste = {
                value = it.trim()
                save()
            })
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 8.dp)
        ) {
            KBButton(
                label = "SAVE",
                enabled = value.isNotBlank() && !saved,
                onClick = { save() }
            )
            if (saved) {
                Text(
                    text = "SAVED",
                    color = KBAccent,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
        if (saved) {
            Text(
                text = savedDescription,
                color = KBAccent,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp)
            )
            if (verify != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 6.dp)
                ) {
                    Text(
                        text = when {
                            verifying -> "Verifying…"
                            verifyResult?.first != null -> "$savedHint ${verifyResult?.first}"
                            verifyResult?.second != null -> verifyResult?.second ?: ""
                            else -> "Not verified yet."
                        },
                        color = if (verifyResult?.second != null) KBTextLo else KBAccent,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.padding(start = 8.dp))
                    KBButton(
                        label = if (verifying) "VERIFYING…" else "VERIFY",
                        enabled = !verifying,
                        onClick = {
                            verifying = true
                            verifyResult = null
                            scope.launch {
                                verifyResult = verify.invoke()
                                verifying = false
                            }
                        }
                    )
                }
            }
        }
    }
}

