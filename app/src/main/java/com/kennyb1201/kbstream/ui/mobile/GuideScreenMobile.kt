package com.kennyb1201.kbstream.ui.mobile

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsSoccer
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.kennyb1201.kbstream.data.iptv.CatchupProgram
import com.kennyb1201.kbstream.data.notifications.ReminderRules
import com.kennyb1201.kbstream.data.iptv.GuideImportProgress
import com.kennyb1201.kbstream.data.iptv.GuideRefreshStatus
import com.kennyb1201.kbstream.data.iptv.IptvChannel
import com.kennyb1201.kbstream.data.iptv.IptvReminderStore
import com.kennyb1201.kbstream.data.iptv.PendingChannelTune
import com.kennyb1201.kbstream.data.iptv.db.EpgProgramRow
import com.kennyb1201.kbstream.data.iptv.IptvChannelWithEpg
import com.kennyb1201.kbstream.data.iptv.IptvPlaylist
import com.kennyb1201.kbstream.data.iptv.LiveChannelZapRegistry
import com.kennyb1201.kbstream.data.iptv.PlaylistSources
import com.kennyb1201.kbstream.data.iptv.XmltvProgram
import com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder
import com.kennyb1201.kbstream.data.sync.ProfileManager
import com.kennyb1201.kbstream.data.sync.ProfileStorage
import com.kennyb1201.kbstream.data.sync.SupabaseSync
import com.kennyb1201.kbstream.ui.components.touch.KBButton
import com.kennyb1201.kbstream.ui.components.touch.KBDialogPanel
import com.kennyb1201.kbstream.ui.components.touch.KBPanelHeading
import com.kennyb1201.kbstream.ui.components.touch.KBPasteChip
import com.kennyb1201.kbstream.ui.components.touch.KBSheetContent
import com.kennyb1201.kbstream.ui.components.touch.KBTextField
import com.kennyb1201.kbstream.ui.components.touch.MobileFilterChip
import com.kennyb1201.kbstream.ui.iptv.IptvViewModel
import com.kennyb1201.kbstream.ui.iptv.formatCatchupWindow
import com.kennyb1201.kbstream.ui.iptv.formatRemainingLabel
import com.kennyb1201.kbstream.ui.iptv.formatStartsInLabel
import com.kennyb1201.kbstream.ui.iptv.formatTimeRange
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBDanger
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBShapePanel
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import com.kennyb1201.kbstream.ui.theme.MobileTheme
import com.kennyb1201.kbstream.ui.theme.OswaldFamily
import com.kennyb1201.kbstream.work.ReminderWorker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Live TV, for a finger.
 *
 * The TV guide is a D-pad grid: a focus-driven group-chip row, a channel column
 * whose focused row opens the now/next panel, a search overlay with its own
 * Back contract, and a setup panel that exists to keep focus inside itself.
 * None of that transfers. This screen keeps the *data* exactly where it is -
 * the same [IptvViewModel] flows, the same playlist/EPG configuration, the same
 * `updateGuideChannels` windowing and the same tap-to-play callback - and
 * rebuilds the presentation for touch:
 *
 *  - a persistent search field (a phone has no D-pad to summon an overlay),
 *    filtering off the main thread like the TV's own filter;
 *  - one scrolling channel list with what is on now, how far through it is, and
 *    what follows, so the guide answers "what's on?" without opening a row;
 *  - EPG rows requested from the list's visible window, so a 10k-channel
 *    playlist queries the same bounded set the TV grid does;
 *  - a setup page of plain fields and buttons for the playlist/EPG URLs.
 *
 * Group chips are here: the reserved All / Recent / Favorites plus one per
 * playlist group, with hidden groups and hidden channels already excluded and
 * the same labels and membership rules the TV chips use (see
 * MobileGuideGroups). Tuning a channel from this list feeds the Recent group,
 * and the zapping lineup the player reads is published per group with the
 * group on screen selected.
 *
 * A long press is the TV channel menu: favourite (written to the same store and
 * synced), a reminder for the next program (stored where the background worker
 * reads it and armed for the program's start), hide this channel, hide its
 * group, and catch-up TV. Every destructive row has an undo on this screen -
 * playlist setup lists the hidden channels and the groups manager restores a
 * hidden group - because a row with no way back would be a one-way door on a
 * phone. Catch-up is offered only when the provider advertises it and the route
 * can actually play the recording.
 *
 * Search covers channel names AND EPG program titles; a program hit tunes its
 * channel. Reminders also surface in-guide as a "starting now" banner with
 * WATCH, and a reminder notification's tap (`PendingChannelTune`) tunes through
 * the same path.
 *
 * The zapping NUMBER PAD is deliberately not here: it exists on TV because a
 * remote has no text entry, and on a phone the search field takes the number
 * (it matches a channel's number exactly). Tracked, with the rest of the port,
 * in `docs/mobile_port.md`.
 */
@Composable
fun GuideScreenMobile(
    onBack: () -> Unit,
    onPlayChannel: ((IptvChannelWithEpg) -> Unit)? = null,
    onPlayCatchup: ((IptvChannelWithEpg, CatchupProgram) -> Unit)? = null,
    onOpenSports: (() -> Unit)? = null,
    defaultPlaylistUrl: String = "",
    defaultEpgUrl: String = "",
    defaultPlaylistName: String = "",
    viewModel: IptvViewModel
) {
    MobileTheme {
        val playlist by viewModel.playlist.collectAsStateWithLifecycle()
        val visibleChannels by viewModel.visibleChannels.collectAsStateWithLifecycle()
        val resolvedGuideIds by viewModel.resolvedGuideChannelIds.collectAsStateWithLifecycle()
        val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
        val isImportingGuide by viewModel.isImportingGuide.collectAsStateWithLifecycle()
        val guideImport by viewModel.guideImport.collectAsStateWithLifecycle()
        val guideStatus by viewModel.guideStatus.collectAsStateWithLifecycle()
        val error by viewModel.error.collectAsStateWithLifecycle()
        val guideError by viewModel.guideError.collectAsStateWithLifecycle()
        val catchupPrograms by viewModel.catchupPrograms.collectAsStateWithLifecycle()
        val catchupLoading by viewModel.catchupLoading.collectAsStateWithLifecycle()
        val playlistUrl by viewModel.playlistUrl.collectAsStateWithLifecycle()
        val epgUrl by viewModel.epgUrl.collectAsStateWithLifecycle()
        val playlistName by viewModel.playlistName.collectAsStateWithLifecycle()
        val extraPlaylistUrls by viewModel.extraPlaylistUrls.collectAsStateWithLifecycle()
        val extraEpgUrls by viewModel.extraEpgUrls.collectAsStateWithLifecycle()
        // The built-in free lineup - the same states the TV form reads.
        val freeLineupInstalled by viewModel.freeLineupInstalled.collectAsStateWithLifecycle()
        val freeLineupInstalling by viewModel.freeLineupInstalling.collectAsStateWithLifecycle()
        val freeLineupFailed by viewModel.freeLineupFailed.collectAsStateWithLifecycle()
        val programSearchRows by viewModel.programSearchResults.collectAsStateWithLifecycle()

        // Per-profile guide prefs: favorites, hidden groups and the recent-play
        // queue are the TV screen's own stores, keyed per profile because a
        // different profile can stream a different M3U.
        val appContext = LocalContext.current.applicationContext
        val activeProfileId by ProfileManager.activeProfile.collectAsStateWithLifecycle()
        val guidePrefs = remember(appContext, activeProfileId) {
            appContext.getSharedPreferences(
                ProfileStorage.prefsName(appContext, GUIDE_PREFS_BASE),
                Context.MODE_PRIVATE
            )
        }
        var favorites by remember(activeProfileId) {
            mutableStateOf(
                guidePrefs.getStringSet("favorites", emptySet())?.toSet().orEmpty()
            )
        }
        var hiddenGroups by remember(activeProfileId) {
            mutableStateOf(
                guidePrefs.getStringSet("hidden_groups", emptySet())?.toSet().orEmpty()
            )
        }
        // The TV stores this as an unordered Set, so a restored queue's order is
        // best-effort - the same fidelity the TV guide has. Within a session the
        // list is ordered and every play moves its channel to the front.
        var recentKeys by remember(activeProfileId) {
            mutableStateOf(
                guidePrefs.getStringSet("recent_channels", emptySet())
                    ?.toList().orEmpty().take(GUIDE_RECENT_CAP)
            )
        }
        var selectedGroup by remember(activeProfileId) {
            mutableStateOf(
                guidePrefs.getString("last_group", null)?.takeIf { it.isNotBlank() }
                    ?: GUIDE_GROUP_ALL
            )
        }
        // Program reminders, from the store the background worker also reads.
        // Declared with the other prefs-backed state because the channel sheet's
        // toggle writes it, below.
        var reminders by remember(activeProfileId) {
            mutableStateOf(IptvReminderStore.load(guidePrefs))
        }
        var reminderBanner by remember { mutableStateOf<IptvReminderStore.Reminder?>(null) }
        LaunchedEffect(recentKeys) {
            guidePrefs.edit()
                .putStringSet("recent_channels", recentKeys.toSet())
                .apply()
        }
        // Favorites and hidden groups are also written by the sync applier, from
        // another thread. Without a listener this screen keeps the set it
        // composed with, so a synced hide never lands and the next local write
        // reverts it for every device.
        DisposableEffect(guidePrefs) {
            val main = Handler(Looper.getMainLooper())
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                when (key) {
                    "hidden_groups" -> main.post {
                        hiddenGroups = guidePrefs
                            .getStringSet("hidden_groups", emptySet())
                            ?.toSet().orEmpty()
                    }
                    "favorites" -> main.post {
                        favorites = guidePrefs
                            .getStringSet("favorites", emptySet())
                            ?.toSet().orEmpty()
                    }
                }
            }
            guidePrefs.registerOnSharedPreferenceChangeListener(listener)
            onDispose { guidePrefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }

        // Favorites are the TV guide's own set: written here in the same store,
        // pushed through the same prefs payload, so a star set on the phone is a
        // star on the TV (and the Favorites chip follows, via the listener).
        fun toggleFavorite(item: IptvChannelWithEpg) {
            val key = item.channelKey()
            val next = if (key in favorites) favorites - key else favorites + key
            favorites = next
            guidePrefs.edit().putStringSet("favorites", next).apply()
            SupabaseSync.enqueuePrefs(
                appContext,
                PrefsPayloadBuilder.KEY_IPTV,
                PrefsPayloadBuilder.buildIptv(appContext)
            )
        }

        // Hidden groups are the TV guide's own set too: same store, same sync
        // payload, so a group hidden on the phone is hidden on the TV as well.
        fun setHiddenGroups(next: Set<String>) {
            hiddenGroups = next
            guidePrefs.edit().putStringSet("hidden_groups", next).apply()
            SupabaseSync.enqueuePrefs(
                appContext,
                PrefsPayloadBuilder.KEY_IPTV,
                PrefsPayloadBuilder.buildIptv(appContext)
            )
        }

        // A reminder is stored where the background worker reads it, and armed
        // for the program's start - so it fires with the app closed, not only
        // while this screen happens to be open.
        fun toggleReminder(item: IptvChannelWithEpg) {
            val next = item.next ?: return
            val key = ReminderRules.reminderKey(item.channel.id, next.startUtcMillis)
            if (IptvReminderStore.has(guidePrefs, item.channel.id, next.startUtcMillis)) {
                IptvReminderStore.remove(guidePrefs, item.channel.id, next.startUtcMillis)
                ReminderWorker.cancel(appContext, key)
            } else {
                IptvReminderStore.add(
                    guidePrefs,
                    IptvReminderStore.Reminder(
                        channelId = item.channel.id,
                        channelName = item.channel.displayName,
                        logoUrl = item.channel.logoUrl,
                        programTitle = next.title,
                        startUtcMillis = next.startUtcMillis,
                        endUtcMillis = next.endUtcMillis
                    )
                )
                ReminderWorker.arm(appContext, key, next.startUtcMillis)
            }
            reminders = IptvReminderStore.load(guidePrefs)
        }

        // The lineup with hidden groups removed, then the chips and the members
        // of the chip on screen - the TV guide's own rules, in MobileGuideGroups.
        val unhiddenChannels = remember(visibleChannels, hiddenGroups) {
            visibleChannels.filter { item ->
                item.channel.groupTitle?.trim().orEmpty() !in hiddenGroups
            }
        }
        val groups = remember(unhiddenChannels, favorites, recentKeys) {
            guideGroupLabels(unhiddenChannels, favorites, recentKeys)
        }
        // Every group title the playlist holds, hidden ones included - the
        // groups manager needs the hidden ones to be able to restore them.
        val allGroupTitles = remember(visibleChannels) {
            visibleChannels
                .mapNotNull { it.channel.groupTitle?.trim()?.takeIf(String::isNotBlank) }
                .distinct()
        }
        // A saved group the lineup no longer has (removed, hidden, or left by
        // another profile) falls back to All rather than to an empty screen.
        val currentGroup = if (selectedGroup in groups) selectedGroup else GUIDE_GROUP_ALL
        LaunchedEffect(currentGroup) {
            if (currentGroup != selectedGroup) selectedGroup = currentGroup
            guidePrefs.edit().putString("last_group", currentGroup).apply()
        }
        val groupChannels = remember(unhiddenChannels, currentGroup, favorites, recentKeys) {
            guideGroupChannels(unhiddenChannels, currentGroup, favorites, recentKeys)
        }

        var showSetup by remember { mutableStateOf(false) }
        var query by remember { mutableStateOf("") }
        // The channel whose long-press sheet is open, and the one whose catch-up
        // program list is open (the sheet closes as it opens).
        var actionsChannel by remember { mutableStateOf<IptvChannelWithEpg?>(null) }
        var catchupChannel by remember { mutableStateOf<IptvChannelWithEpg?>(null) }
        var showHidden by remember { mutableStateOf(false) }
        var showGroups by remember { mutableStateOf(false) }
        val hiddenChannelIds by viewModel.hiddenChannelIds.collectAsStateWithLifecycle()
        // The undo for hiding a channel: hidden ids resolved to names from the
        // playlist, so the setup page can offer each one back.
        val hiddenChannels = remember(playlist, hiddenChannelIds) {
            playlist?.channels.orEmpty().filter { it.id in hiddenChannelIds }
        }

        // A configured default playlist comes up without the viewer typing it -
        // the same one-shot the TV runs, and for the same reason: a profile can
        // ship its own lineup.
        LaunchedEffect(defaultPlaylistUrl, defaultEpgUrl, defaultPlaylistName) {
            if (
                playlist == null &&
                !isLoading &&
                playlistUrl.isBlank() &&
                defaultPlaylistUrl.isNotBlank()
            ) {
                viewModel.onPlaylistUrlChanged(defaultPlaylistUrl)
                if (defaultEpgUrl.isNotBlank()) viewModel.onEpgUrlChanged(defaultEpgUrl)
                if (defaultPlaylistName.isNotBlank()) {
                    viewModel.onPlaylistNameChanged(defaultPlaylistName)
                }
                viewModel.load()
                showSetup = false
            }
        }

        // A lineup that just landed takes the setup page with it, the same
        // hand-off the TV panel makes: the point of pressing LOAD is to get to
        // the guide, not to stay on the form that loaded it.
        LaunchedEffect(playlist) {
            if (playlist != null) showSetup = false
        }

        // Back: the setup page opened OVER a loaded lineup closes, and otherwise
        // Back leaves Live TV. Before a lineup exists the setup page IS the
        // screen, so it has nothing to close and Back must leave.
        BackHandler {
            if (showSetup && playlist != null) showSetup = false else onBack()
        }

        // The clock the guide's labels are relative to. Program titles are
        // static, but "how much is left" is not, so the screen re-renders its
        // now/next lines on the same 30s beat the TV tick uses.
        val nowMs by produceState(initialValue = System.currentTimeMillis()) {
            while (true) {
                value = System.currentTimeMillis()
                delay(GUIDE_TICK_MS)
            }
        }
        val guideImportLabel = GuideImportProgress.label(guideImport, nowMs)

        // Reminders use the same store the TV guide and the background worker
        // share, so one set here alerts even with the app closed. The banner is
        // the in-guide "it just started" affordance; stale entries are pruned on
        // the same beat, exactly as the TV screen does.
        LaunchedEffect(reminders) {
            while (true) {
                val now = System.currentTimeMillis()
                if (reminderBanner == null) {
                    reminderBanner = reminders.firstOrNull {
                        it.startUtcMillis in 1..now && now < it.endUtcMillis
                    }
                }
                val staleCutoff = now - REMINDER_GRACE_MS
                val stale = reminders.filter { it.endUtcMillis in 1..staleCutoff }
                if (stale.isNotEmpty()) {
                    stale.forEach {
                        IptvReminderStore.remove(guidePrefs, it.channelId, it.startUtcMillis)
                    }
                    reminders = IptvReminderStore.load(guidePrefs)
                }
                delay(15_000L)
            }
        }

        // A reminder notification's tap asks for one channel; play it as soon as
        // the lineup can resolve it, exactly once (see PendingChannelTune).
        LaunchedEffect(unhiddenChannels.size) {
            if (unhiddenChannels.isEmpty()) return@LaunchedEffect
            val wanted = PendingChannelTune.consume() ?: return@LaunchedEffect
            val item = unhiddenChannels.firstOrNull { it.channelKey() == wanted }
                ?: return@LaunchedEffect
            onPlayChannel?.invoke(item)
        }

        val setupVisible = playlist == null || showSetup
        if (setupVisible) {
            MobileGuideSetup(
                playlist = playlist,
                playlistUrl = playlistUrl,
                epgUrl = epgUrl,
                playlistName = playlistName,
                isLoading = isLoading,
                isImportingGuide = isImportingGuide,
                guideImportLabel = guideImportLabel,
                guideStatus = guideStatus,
                error = error,
                guideError = guideError,
                channelCount = visibleChannels.size,
                hiddenChannels = hiddenChannels,
                onManageHidden = { showHidden = true },
                extraPlaylistUrls = extraPlaylistUrls,
                extraEpgUrls = extraEpgUrls,
                groupTitles = allGroupTitles,
                hiddenGroupCount = hiddenGroups.size,
                onManageGroups = { showGroups = true },
                onPlaylistUrlChanged = viewModel::onPlaylistUrlChanged,
                onEpgUrlChanged = viewModel::onEpgUrlChanged,
                onPlaylistNameChanged = viewModel::onPlaylistNameChanged,
                onExtraPlaylistUrlsChanged = viewModel::onExtraPlaylistUrlsChanged,
                onExtraEpgUrlsChanged = viewModel::onExtraEpgUrlsChanged,
                freeLineupInstalled = freeLineupInstalled,
                freeLineupInstalling = freeLineupInstalling,
                freeLineupFailed = freeLineupFailed,
                onInstallFreeLineup = { viewModel.installFreeLineup() },
                onRemoveFreeLineup = { viewModel.removeFreeLineup() },
                // The installed sources as rows, over the same storage the
                // fields below edit (see PlaylistSources). The free lineup is
                // not among them: its block above administers it.
                sources = PlaylistSources.rows(
                    playlistUrl = playlistUrl,
                    playlistName = playlistName,
                    extraPlaylistUrls = extraPlaylistUrls
                ),
                onRemoveSource = { source ->
                    if (source.isMain) viewModel.removeMainPlaylist()
                    else viewModel.removeExtraPlaylist(source.url)
                },
                onLoad = { viewModel.load() },
                onImportGuide = { viewModel.importGuide() },
                onBack = { if (playlist == null) onBack() else showSetup = false }
            )
            return@MobileTheme
        }

        // Channel-name search: case-insensitive contains on the display name,
        // the raw name and the channel number, scanned off the main thread. It
        // searches the WHOLE unhidden lineup, as the TV overlay does - a query
        // is a shortcut across the playlist, not a filter inside one group.
        val filtered by produceState(
            initialValue = groupChannels,
            groupChannels,
            unhiddenChannels,
            query
        ) {
            val q = query.trim()
            value = if (q.isBlank()) {
                groupChannels
            } else {
                withContext(Dispatchers.Default) {
                    unhiddenChannels.filter { item ->
                        item.channel.displayName.contains(q, ignoreCase = true) ||
                            item.channel.name.contains(q, ignoreCase = true) ||
                            item.channel.tvgChno?.trim() == q
                    }.take(GUIDE_SEARCH_LIMIT)
                }
            }
        }

        // Publish the zapping lineup the player works from, exactly as the TV
        // guide does: without it the live player opened from here has no CH+/CH-
        // lineup and its in-player guide has nothing to switch between. Every
        // group is published, with the group on screen selected, so zapping
        // walks the lineup the viewer is actually browsing. Search results are a
        // shortcut, not a lineup, so a query does not narrow it.
        val guideSourceUrls = remember(epgUrl, extraEpgUrls) {
            (epgUrl.trim().takeIf(String::isNotEmpty)?.let(::listOf).orEmpty() +
                extraEpgUrls.split('\n', ';').mapNotNull { it.trim().takeIf(String::isNotEmpty) })
                .distinct()
        }
        LaunchedEffect(groups, currentGroup, unhiddenChannels, favorites, recentKeys, guideSourceUrls) {
            // An empty list is transient while the lineup flow restarts;
            // publishing it would blank a lineup that is still correct.
            if (unhiddenChannels.isEmpty()) return@LaunchedEffect
            val zapByChannelId =
                HashMap<String, LiveChannelZapRegistry.ZapChannel>(unhiddenChannels.size)

            fun zapChannelOf(item: IptvChannelWithEpg): LiveChannelZapRegistry.ZapChannel =
                zapByChannelId.getOrPut(item.channelKey()) {
                    LiveChannelZapRegistry.ZapChannel(
                        channelId = item.channelKey(),
                        name = item.channel.displayName.ifBlank { "Live Channel" },
                        streamUrl = item.channel.streamUrl,
                        logoUrl = item.channel.logoUrl ?: item.epgChannel?.iconUrl,
                        headers = item.channel.headers,
                        chno = item.channel.tvgChno?.trim()?.takeIf { it.isNotBlank() },
                        epgChannelId = item.epgChannel?.id,
                        epgUrl = guideSourceUrls.firstOrNull(),
                        epgUrls = guideSourceUrls,
                        tvgId = item.channel.tvgId,
                        tvgName = item.channel.tvgName,
                        providerChannelId = item.channel.providerChannelId
                    )
                }

            LiveChannelZapRegistry.setGroups(
                groups = groups.map { label ->
                    LiveChannelZapRegistry.ZapGroup(
                        label = label,
                        channels = guideGroupChannels(
                            channels = unhiddenChannels,
                            label = label,
                            favorites = favorites,
                            recentKeys = recentKeys
                        ).map(::zapChannelOf)
                    )
                },
                selected = currentGroup
            )
        }

        // EPG program search: "what's on with X", not just channel names. The
        // ViewModel debounces and queries the guide tables; hits are mapped onto
        // UNHIDDEN channels only, so a hidden channel cannot resurface through a
        // program match.
        LaunchedEffect(query) {
            if (query.isBlank()) return@LaunchedEffect
            viewModel.searchPrograms(query)
        }
        val programHits by produceState(
            initialValue = emptyList<MobileGuideProgramHit>(),
            programSearchRows,
            unhiddenChannels
        ) {
            value = if (programSearchRows.isEmpty()) {
                emptyList()
            } else {
                withContext(Dispatchers.Default) {
                    val byEpgId = unhiddenChannels
                        .mapNotNull { item -> item.epgChannel?.id?.let { id -> id to item } }
                        .toMap()
                    programSearchRows
                        .mapNotNull { row ->
                            byEpgId[row.channelId]?.let { item ->
                                MobileGuideProgramHit(item, row)
                            }
                        }
                        .take(GUIDE_PROGRAM_HIT_LIMIT)
                }
            }
        }

        val listState = rememberLazyListState()
        // EPG windowing: ask the ViewModel for the rows the viewer can actually
        // see, and only those. Keyed by ROW KEY rather than index, because the
        // list also carries program-hit rows while a search is up - an index
        // would then point at the wrong channel.
        val channelIdsByRowKey = remember(filtered) {
            filtered.associate { it.channelKey() to it.channel.id }
        }
        val latestChannelIds by rememberUpdatedState(channelIdsByRowKey)
        LaunchedEffect(listState) {
            snapshotFlow {
                listState.layoutInfo.visibleItemsInfo.mapNotNull { info ->
                    (info.key as? String)?.let { key -> latestChannelIds[key] }
                }
            }.collect { ids ->
                if (ids.isNotEmpty()) viewModel.updateGuideChannels(ids)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(KBVoid)
        ) {
            MobileGuideTopBar(
                title = playlist?.name?.takeIf { it.isNotBlank() } ?: "Live TV",
                channelCount = unhiddenChannels.size,
                groupLabel = currentGroup,
                nowMs = nowMs,
                onBack = onBack,
                onSetup = { showSetup = true },
                onOpenSports = onOpenSports
            )

            reminderBanner?.let { hit ->
                MobileReminderBanner(
                    reminder = hit,
                    onWatch = {
                        val item = unhiddenChannels.firstOrNull { it.channel.id == hit.channelId }
                        reminderBanner = null
                        if (item != null) onPlayChannel?.invoke(item)
                    },
                    onDismiss = { reminderBanner = null }
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                KBTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "Search channels",
                    keyboardType = KeyboardType.Text,
                    onClear = { query = "" },
                    leading = {
                        Icon(
                            imageVector = Icons.Filled.Search,
                            contentDescription = null,
                            tint = KBTextLo,
                            modifier = Modifier.size(18.dp)
                        )
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // The TV group strip, for a thumb. A horizontal scroll is the right
            // control here too: a wrapped row of a provider's forty categories
            // would push the channels off the screen.
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(groups, key = { "group:" + it }) { label ->
                    MobileFilterChip(
                        label = label,
                        selected = label == currentGroup,
                        onClick = { selectedGroup = label }
                    )
                }
            }

            // The line keeps showing after the pass ends, because a finished
            // import now has something to say ("Imported N programs" /
            // "Guide unchanged on server ..."). It used to be gated on the
            // pass being open, so a finished import silently returned to 0.
            if (guideImportLabel.isNotBlank()) {
                Text(
                    text = if (isImportingGuide) {
                        "Importing guide · $guideImportLabel"
                    } else {
                        guideImportLabel
                    },
                    color = KBAccent,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )
            }
            // Which source last landed here, and whether the background
            // refresh has given up: the two facts that say a guide is going
            // stale BEFORE it reads blank, beside the button that fixes it.
            if (guideStatus.lastGoodLabel().isNotBlank()) {
                Text(
                    text = guideStatus.lastGoodLabel(),
                    color = KBTextLo,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )
            }
            if (guideStatus.failing) {
                Text(
                    text = GuideRefreshStatus.FAILING_LABEL,
                    color = KBDanger,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )
            }
            guideError?.takeIf { it.isNotBlank() }?.let { message ->
                MobileGuideErrorLine(message)
            }

            when {
                isLoading && visibleChannels.isEmpty() -> MobileGuideStatus("Loading playlist…")
                query.isNotBlank() && filtered.isEmpty() && programHits.isEmpty() -> {
                    MobileGuideStatus("No channel or program matches \"${query.trim()}\"")
                }
                query.isBlank() && filtered.isEmpty() &&
                    currentGroup != GUIDE_GROUP_ALL -> {
                    MobileGuideStatus("No channels in $currentGroup")
                }
                query.isBlank() && filtered.isEmpty() -> {
                    MobileGuideStatus("This playlist has no channels")
                }
                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(top = 4.dp, bottom = 32.dp)
                    ) {
                        if (query.isNotBlank() && programHits.isNotEmpty()) {
                            item(key = "programsHeader") {
                                MobileListHeading("PROGRAMS")
                            }
                            itemsIndexed(
                                items = programHits,
                                key = { index, hit ->
                                    "prog:" + hit.item.channel.id + ":" +
                                        hit.program.startUtcMillis + ":" + index
                                }
                            ) { _, hit ->
                                MobileProgramHitRow(
                                    hit = hit,
                                    onClick = {
                                        recentKeys = nextRecentKeys(
                                            recentKeys,
                                            hit.item.channelKey()
                                        )
                                        onPlayChannel?.invoke(hit.item)
                                    }
                                )
                            }
                            if (filtered.isNotEmpty()) {
                                item(key = "channelsHeader") {
                                    MobileListHeading("CHANNELS")
                                }
                            }
                        }
                        items(
                            items = filtered,
                            key = { item -> item.channelKey() }
                        ) { item ->
                            MobileGuideChannelRow(
                                item = item,
                                nowMs = nowMs,
                                resolved = resolvedGuideIds.contains(item.channelKey()),
                                onClick = onPlayChannel?.let { play ->
                                    {
                                        // Tuning feeds the Recent group, the way
                                        // the TV queue is fed on play - never on
                                        // merely scrolling past a row.
                                        recentKeys = nextRecentKeys(
                                            recentKeys,
                                            item.channelKey()
                                        )
                                        play(item)
                                    }
                                },
                                // Long-press is the TV's channel menu (favorite,
                                // hide, catch-up).
                                onLongClick = { actionsChannel = item }
                            )
                        }
                    }
                }
            }
        }

        actionsChannel?.let { item ->
            MobileChannelActionsSheet(
                channelName = item.channel.displayName.ifBlank { item.channel.name },
                isFavorite = item.channelKey() in favorites,
                hasCatchup = onPlayCatchup != null &&
                    (item.channel.catchupSource != null || item.channel.catchup != null),
                groupTitle = item.channel.groupTitle?.trim()?.takeIf { it.isNotBlank() },
                reminderTitle = item.next?.title,
                reminderActive = item.next?.let { next ->
                    IptvReminderStore.has(
                        guidePrefs,
                        item.channel.id,
                        next.startUtcMillis
                    )
                } ?: false,
                onToggleFavorite = { toggleFavorite(item) },
                onToggleReminder = { toggleReminder(item) },
                onToggleGroup = {
                    val group = item.channel.groupTitle?.trim()?.takeIf { it.isNotBlank() }
                    if (group != null) {
                        setHiddenGroups(hiddenGroups + group)
                    }
                    actionsChannel = null
                },
                onHide = {
                    viewModel.hideChannel(item.channel.id)
                    actionsChannel = null
                },
                onCatchup = {
                    catchupChannel = item
                    actionsChannel = null
                    viewModel.loadCatchupPrograms(item.channel)
                },
                onDismiss = { actionsChannel = null }
            )
        }

        if (showGroups) {
            MobileGroupsDialog(
                groupTitles = allGroupTitles,
                hiddenGroups = hiddenGroups,
                onToggle = { group ->
                    setHiddenGroups(
                        if (group in hiddenGroups) hiddenGroups - group
                        else hiddenGroups + group
                    )
                },
                onShowAll = { setHiddenGroups(emptySet()) },
                onDismiss = { showGroups = false }
            )
        }

        if (showHidden) {
            MobileHiddenChannelsDialog(
                channels = hiddenChannels,
                onUnhide = { id -> viewModel.unhideChannel(id) },
                onUnhideAll = {
                    viewModel.unhideAllChannels()
                    showHidden = false
                },
                onDismiss = { showHidden = false }
            )
        }

        catchupChannel?.let { item ->
            MobileCatchupDialog(
                channelName = item.channel.displayName.ifBlank { item.channel.name },
                programs = catchupPrograms,
                loading = catchupLoading,
                onDismiss = { catchupChannel = null },
                onPlay = { program ->
                    catchupChannel = null
                    onPlayCatchup?.invoke(item, program)
                }
            )
        }
    }
}

private const val GUIDE_TICK_MS = 30_000L

/** How long a finished program's reminder is kept before it is pruned. */
private const val REMINDER_GRACE_MS = 10 * 60_000L

/** How many EPG program hits a query shows - a shortcut, not a second lineup. */
private const val GUIDE_PROGRAM_HIT_LIMIT = 30

/** One EPG search hit, with the channel it would tune. */
private data class MobileGuideProgramHit(
    val item: IptvChannelWithEpg,
    val program: EpgProgramRow
)

/** The TV guide's own prefs store: favorites, hidden groups, Recent live here. */
private const val GUIDE_PREFS_BASE = "iptv_guide_preferences"

/** The TV overlay's own cap on channel hits; past this a list is not a shortcut. */
private const val GUIDE_SEARCH_LIMIT = 60

/** The id the ViewModel's guide cache is keyed by (see MobileGuideGroups). */
private fun IptvChannelWithEpg.channelKey(): String = guideChannelKey(this)

@Composable
private fun MobileGuideTopBar(
    title: String,
    channelCount: Int,
    groupLabel: String,
    nowMs: Long,
    onBack: () -> Unit,
    onSetup: () -> Unit,
    onOpenSports: (() -> Unit)?
) {
    val clock = remember { SimpleDateFormat("h:mm a", Locale.getDefault()) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 4.dp, end = 4.dp, top = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MobileBackButton(onBack = onBack)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = KBTextHi,
                fontFamily = OswaldFamily,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildList {
                    add("$channelCount channels")
                    if (groupLabel.isNotBlank()) add(groupLabel)
                    add(clock.format(Date(nowMs)))
                }.joinToString("  ·  "),
                color = KBTextLo,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1
            )
        }
        if (onOpenSports != null) {
            IconButton(onClick = onOpenSports) {
                Icon(
                    imageVector = Icons.Filled.SportsSoccer,
                    contentDescription = "Sports hub",
                    tint = KBAccent
                )
            }
        }
        IconButton(onClick = onSetup) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = "Playlist setup",
                tint = KBTextHi
            )
        }
    }
}

/**
 * One channel: logo, number and name, what is on now with its progress, and
 * what follows. Tapping the row tunes the channel - the same callback the TV
 * row uses on OK.
 */
@Composable
private fun MobileGuideChannelRow(
    item: IptvChannelWithEpg,
    nowMs: Long,
    resolved: Boolean,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)? = null
) {
    val now = item.now
    val channel = item.channel

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                enabled = onClick != null || onLongClick != null,
                onClick = { onClick?.invoke() },
                onLongClick = onLongClick
            )
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        MobileChannelLogo(
            logoUrl = channel.logoUrl,
            name = channel.displayName.ifBlank { channel.name }
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                channel.tvgChno?.takeIf { it.isNotBlank() }?.let { number ->
                    Text(
                        text = number,
                        color = KBAccent,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
                Text(
                    text = channel.displayName.ifBlank { channel.name },
                    color = KBTextHi,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            when {
                now != null -> {
                    Text(
                        text = now.title,
                        color = KBTextHi,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    MobileProgramProgress(program = now, nowMs = nowMs)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatTimeRange(now.startUtcMillis, now.endUtcMillis),
                            color = KBTextLo,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            modifier = Modifier.weight(1f)
                        )
                        formatRemainingLabel(now.endUtcMillis - nowMs)?.let { label ->
                            Text(
                                text = label,
                                color = KBTextLo,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1
                            )
                        }
                    }
                }
                // A row whose lineup query has not answered yet is loading, not
                // empty: only a RESOLVED channel with no match may say "no
                // program data" (the same distinction the TV rows draw).
                resolved -> Text(
                    text = "No program data",
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1
                )
                else -> Text(
                    text = "Loading guide…",
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1
                )
            }

            item.next?.let { next ->
                Text(
                    text = "Next · ${next.title} " +
                        "(${formatStartsInLabel(next.startUtcMillis - nowMs)})",
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** How far through the program on air the viewer is, as a thin accent bar. */
@Composable
private fun MobileProgramProgress(program: XmltvProgram, nowMs: Long) {
    val span = program.endUtcMillis - program.startUtcMillis
    if (span <= 0L) return
    val fraction = ((nowMs - program.startUtcMillis).toFloat() / span.toFloat())
        .coerceIn(0f, 1f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(KBTextLo.copy(alpha = 0.22f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .fillMaxHeight()
                .background(KBAccent)
        )
    }
}

@Composable
private fun MobileChannelLogo(logoUrl: String?, name: String) {
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(KBSurface),
        contentAlignment = Alignment.Center
    ) {
        if (logoUrl.isNullOrBlank()) {
            Text(
                text = name.take(2).uppercase(),
                color = KBTextLo,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
        } else {
            AsyncImage(
                model = logoUrl,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp)
            )
        }
    }
}

@Composable
private fun MobileListHeading(text: String) {
    Text(
        text = text,
        color = KBTextLo,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 18.dp, top = 10.dp, bottom = 4.dp)
    )
}

/**
 * One EPG program hit: what is on, on which channel, and when. Tapping tunes
 * that channel - the program is the reason the channel is being offered.
 */
@Composable
private fun MobileProgramHitRow(hit: MobileGuideProgramHit, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = hit.program.title,
            color = KBTextHi,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = hit.item.channel.displayName.ifBlank { hit.item.channel.name } + "  ·  " +
                formatTimeRange(hit.program.startUtcMillis, hit.program.endUtcMillis),
            color = KBTextLo,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * "It just started": the in-guide half of a program reminder.
 *
 * The other half is the worker's notification, which fires whether or not this
 * screen is open; WATCH reuses the ordinary channel-tune path.
 */
@Composable
private fun MobileReminderBanner(
    reminder: IptvReminderStore.Reminder,
    onWatch: () -> Unit,
    onDismiss: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(KBShapePanel)
            .background(KBAccent.copy(alpha = 0.14f))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = "STARTING NOW",
                color = KBAccent,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = reminder.programTitle,
                color = KBTextHi,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = reminder.channelName,
                color = KBTextLo,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        KBButton(label = "WATCH") { onWatch() }
        Text(
            text = "DISMISS",
            color = KBTextLo,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(KBShapeCard)
                .clickable(onClick = onDismiss)
                .padding(horizontal = 8.dp, vertical = 6.dp)
        )
    }
}

/**
 * The groups manager: every group title the playlist has, hidden or not, with
 * the switch that takes it in or out of the guide.
 *
 * This is the undo for the channel sheet's "Hide group" row - and the only
 * phone-side way to hide a group without hiding each of its channels.
 */
@Composable
private fun MobileGroupsDialog(
    groupTitles: List<String>,
    hiddenGroups: Set<String>,
    onToggle: (String) -> Unit,
    onShowAll: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        KBDialogPanel(
            title = "Groups",
            subtitle = "${hiddenGroups.size} hidden"
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                groupTitles.forEach { group ->
                    val hidden = group in hiddenGroups
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = group,
                            color = if (hidden) KBTextLo else KBTextHi,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        KBButton(label = if (hidden) "SHOW" else "HIDE") {
                            onToggle(group)
                        }
                    }
                }
            }

            if (hiddenGroups.isNotEmpty()) {
                KBButton(
                    label = "SHOW ALL",
                    modifier = Modifier.fillMaxWidth()
                ) { onShowAll() }
            }
            KBButton(
                label = "CLOSE",
                modifier = Modifier.fillMaxWidth()
            ) { onDismiss() }
        }
    }
}

@Composable
private fun MobileGuideStatus(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            color = KBTextLo,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun MobileGuideErrorLine(message: String) {
    Text(
        text = message,
        color = KBDanger,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
    )
}

/**
 * The playlist/EPG configuration, as a phone form.
 *
 * Same fields and the same ViewModel calls as the TV's `SetupPanel`, without
 * its focus containment: a touch form has no D-pad to trap, the soft keyboard
 * is an ordinary IME, and the only Back contract it needs is "leave this page"
 * or "go back to the guide" - which the caller owns.
 */
@Composable
private fun MobileGuideSetup(
    playlist: IptvPlaylist?,
    playlistUrl: String,
    epgUrl: String,
    playlistName: String,
    isLoading: Boolean,
    isImportingGuide: Boolean,
    guideImportLabel: String,
    guideStatus: GuideRefreshStatus,
    error: String?,
    guideError: String?,
    channelCount: Int,
    hiddenChannels: List<IptvChannel>,
    onManageHidden: () -> Unit,
    extraPlaylistUrls: String,
    extraEpgUrls: String,
    groupTitles: List<String>,
    hiddenGroupCount: Int,
    onManageGroups: () -> Unit,
    onPlaylistUrlChanged: (String) -> Unit,
    onEpgUrlChanged: (String) -> Unit,
    onPlaylistNameChanged: (String) -> Unit,
    onExtraPlaylistUrlsChanged: (String) -> Unit,
    onExtraEpgUrlsChanged: (String) -> Unit,
    freeLineupInstalled: Boolean = false,
    freeLineupInstalling: Boolean = false,
    freeLineupFailed: Boolean = false,
    onInstallFreeLineup: () -> Unit = {},
    onRemoveFreeLineup: () -> Unit = {},
    /** The installed sources, one row each - see [PlaylistSources]. */
    sources: List<PlaylistSources.Source> = emptyList(),
    onRemoveSource: (PlaylistSources.Source) -> Unit = {},
    onLoad: () -> Unit,
    onImportGuide: () -> Unit,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(KBVoid)
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 8.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MobileBackButton(onBack = onBack)
            Text(
                text = "LIVE TV SETUP",
                color = KBTextHi,
                fontFamily = OswaldFamily,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleLarge
            )
        }

        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Point KBStream at an M3U playlist, and optionally an XMLTV " +
                    "guide so the channel rows can say what is on.",
                color = KBTextLo,
                style = MaterialTheme.typography.bodySmall
            )

            // The free lineup: the offer when absent, the installed entry with
            // its Remove action when present. Same ViewModel calls as the TV
            // form - this is only the phone's own door to them.
            if (freeLineupInstalled) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = com.kennyb1201.kbstream.data.iptv.FreeLineup.INSTALLED_LABEL,
                        color = KBTextHi,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = if (freeLineupInstalling) {
                            "Loading the free channels…"
                        } else {
                            // The same US-only line the TV form carries: the
                            // lineup's own filter (see FreeLineup.isUsChannel).
                            "US channels only: news, local stations and free channels."
                        },
                        color = KBTextLo,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                KBButton(
                    label = "REMOVE FREE TV",
                    modifier = Modifier.fillMaxWidth()
                ) { onRemoveFreeLineup() }
                if (freeLineupFailed && playlist == null) {
                    MobileGuideErrorLine(
                        "The free lineup didn't load. Check the connection and try " +
                            "again, or add your own playlist below."
                    )
                }
            } else {
                KBButton(
                    label = if (freeLineupInstalling) {
                        "INSTALLING FREE TV…"
                    } else if (playlist == null) {
                        "WATCH FREE CHANNELS"
                    } else {
                        "ADD FREE TV (BUILT-IN)"
                    },
                    enabled = !freeLineupInstalling,
                    modifier = Modifier.fillMaxWidth()
                ) { onInstallFreeLineup() }
                Text(
                    text = "US channels only: news, local stations and free sports, " +
                        "built in. Media links only - add your own playlist any time.",
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodySmall
                )
                if (playlist == null) {
                    // The secondary action needs no button here: the phone form
                    // puts the playlist fields directly below the offer, so
                    // "add your playlist" is the next thing on screen. What it
                    // does need is to say so.
                    Text(
                        text = "ADD YOUR PLAYLIST",
                        color = KBTextHi,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                if (freeLineupFailed) {
                    MobileGuideErrorLine(
                        "The free lineup didn't load. Check the connection and try " +
                            "again, or add your own playlist below."
                    )
                }
            }

            // What is installed, above the fields that edit it. One Remove per
            // source, one tap, no confirmation - the reload that follows is the
            // feedback. Same rows as the TV form, same ViewModel calls.
            if (sources.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    sources.forEach { source ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = source.label,
                                    color = KBTextHi,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = if (source.isMain) {
                                        "MAIN PLAYLIST"
                                    } else {
                                        "EXTRA PLAYLIST"
                                    },
                                    color = KBTextLo,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            KBButton(label = "REMOVE", danger = true) {
                                onRemoveSource(source)
                            }
                        }
                    }
                }
            }

            SetupField(
                label = "PLAYLIST URL",
                value = playlistUrl,
                placeholder = "https://example.com/playlist.m3u",
                keyboardType = KeyboardType.Uri,
                onValueChange = onPlaylistUrlChanged
            )

            SetupField(
                label = "PLAYLIST NAME (OPTIONAL)",
                value = playlistName,
                placeholder = "My provider",
                keyboardType = KeyboardType.Text,
                onValueChange = onPlaylistNameChanged
            )

            SetupField(
                label = "EPG URL",
                value = epgUrl,
                placeholder = "https://example.com/guide.xml",
                keyboardType = KeyboardType.Uri,
                onValueChange = onEpgUrlChanged
            )

            // Extra sources: the ViewModel merges additional M3Us into the
            // lineup and matches guide data against every EPG source. Both
            // fields take several entries, one per line (or ';'-separated, an
            // older spelling that keeps parsing). Both are multiline because
            // the label has always promised "one per line" and a single-line
            // field could never deliver it - Enter was the keyboard's Done.
            SetupField(
                label = "EXTRA PLAYLISTS (OPTIONAL)",
                value = extraPlaylistUrls,
                placeholder = "One URL per line",
                keyboardType = KeyboardType.Uri,
                multiline = true,
                onValueChange = onExtraPlaylistUrlsChanged
            )

            SetupField(
                label = "EXTRA EPG SOURCES (OPTIONAL)",
                value = extraEpgUrls,
                placeholder = "One URL per line",
                keyboardType = KeyboardType.Uri,
                multiline = true,
                onValueChange = onExtraEpgUrlsChanged
            )

            KBButton(
                label = if (playlist == null) "LOAD PLAYLIST" else "RELOAD PLAYLIST",
                // A free-lineup install leaves the primary slot empty on
                // purpose, so the reload must stay available for a profile
                // whose only sources are extras.
                enabled = !isLoading && (playlistUrl.isNotBlank() || freeLineupInstalled),
                modifier = Modifier.fillMaxWidth()
            ) { onLoad() }

            KBButton(
                label = if (isImportingGuide) "IMPORTING…" else "IMPORT GUIDE",
                enabled = !isImportingGuide && !isLoading &&
                    (epgUrl.isNotBlank() || extraEpgUrls.isNotBlank()),
                modifier = Modifier.fillMaxWidth()
            ) { onImportGuide() }

            if (guideImportLabel.isNotBlank()) {
                Text(
                    text = guideImportLabel,
                    color = KBAccent,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            error?.takeIf { it.isNotBlank() }?.let { message ->
                MobileGuideErrorLine(message)
            }
            guideError?.takeIf { it.isNotBlank() }?.let { message ->
                MobileGuideErrorLine(message)
            }

            playlist?.let { loaded ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = loaded.name?.takeIf { it.isNotBlank() } ?: "Playlist loaded",
                        color = KBTextHi,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "$channelCount channels",
                        color = KBTextLo,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                KBButton(
                    label = "BACK TO THE GUIDE",
                    modifier = Modifier.fillMaxWidth()
                ) { onBack() }

                // The undo for the channel menu's hide row. Only shown when
                // something is actually hidden - an always-visible "(0)" button
                // is noise on a form this short.
                if (hiddenChannels.isNotEmpty()) {
                    KBButton(
                        label = "HIDDEN CHANNELS (${hiddenChannels.size})",
                        modifier = Modifier.fillMaxWidth()
                    ) { onManageHidden() }
                }

                // The groups manager is also how a group hidden FROM the channel
                // sheet is restored.
                if (groupTitles.isNotEmpty()) {
                    KBButton(
                        label = if (hiddenGroupCount > 0) {
                            "GROUPS ($hiddenGroupCount HIDDEN)"
                        } else {
                            "GROUPS"
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { onManageGroups() }
                }
            }

            if (isLoading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    CircularProgressIndicator(
                        color = KBAccent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Loading playlist…",
                        color = KBTextLo,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/** One labelled URL field, with the paste shortcut a phone form still wants. */
@Composable
private fun SetupField(
    label: String,
    value: String,
    placeholder: String,
    keyboardType: KeyboardType,
    onValueChange: (String) -> Unit,
    /** True for the two "one per line" list fields; single-value fields stay one line. */
    multiline: Boolean = false
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            color = KBTextLo,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            KBTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = placeholder,
                keyboardType = keyboardType,
                multiline = multiline,
                modifier = Modifier.weight(1f)
            )
            KBPasteChip(onPaste = onValueChange)
        }
    }
}

/**
 * The TV channel menu, as a bottom sheet: favourite, catch-up, hide.
 *
 * The TV menu is a focus-anchored dialog with the same rows plus reminders and
 * a zap entry; a sheet is what a thumb can reach and what the platform already
 * knows how to dismiss (tap the scrim, swipe down, Back), so none of the focus
 * plumbing comes across.
 *
 * Catch-up is offered only when the provider actually advertises it AND the
 * route has somewhere to send the recording - an action that cannot play must
 * not be on the menu.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MobileChannelActionsSheet(
    channelName: String,
    isFavorite: Boolean,
    hasCatchup: Boolean,
    groupTitle: String?,
    reminderTitle: String?,
    reminderActive: Boolean,
    onToggleFavorite: () -> Unit,
    onToggleReminder: () -> Unit,
    onToggleGroup: () -> Unit,
    onHide: () -> Unit,
    onCatchup: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        shape = KBShapePanel,
        containerColor = KBSurfaceRaised,
        contentColor = KBTextHi,
        scrimColor = Color.Black.copy(alpha = 0.6f),
        dragHandle = {
            BottomSheetDefaults.DragHandle(color = KBTextLo.copy(alpha = 0.6f))
        }
    ) {
        KBSheetContent {
            KBPanelHeading(title = channelName)
            Spacer(modifier = Modifier.height(2.dp))

            GuideSheetRow(
                label = if (isFavorite) "Remove from Favorites" else "Add to Favorites",
                description = if (isFavorite) {
                    "The Favorites chip stops listing this channel."
                } else {
                    "Pins this channel to the Favorites chip."
                },
                onClick = onToggleFavorite
            )

            if (hasCatchup) {
                GuideSheetRow(
                    label = "Catch-up TV",
                    description = "Play something this channel already aired.",
                    onClick = onCatchup
                )
            }

            if (reminderTitle != null) {
                GuideSheetRow(
                    label = if (reminderActive) {
                        "Remove reminder"
                    } else {
                        "Remind me: $reminderTitle"
                    },
                    description = if (reminderActive) {
                        "The alert for this program is cancelled."
                    } else {
                        "Alerts when this program starts, even if the app is closed."
                    },
                    onClick = onToggleReminder
                )
            }

            if (groupTitle != null) {
                GuideSheetRow(
                    label = "Hide group: $groupTitle",
                    description = "Removes every channel in this group from the guide.",
                    isDestructive = true,
                    onClick = onToggleGroup
                )
            }

            GuideSheetRow(
                label = "Hide this channel",
                description = "Removes it from the guide. Playlist setup lists hidden " +
                    "channels so it can be brought back.",
                isDestructive = true,
                onClick = onHide
            )

            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

/** One sheet row: the label, its consequence, and a full-width tap target. */
@Composable
private fun GuideSheetRow(
    label: String,
    description: String?,
    onClick: () -> Unit,
    isDestructive: Boolean = false
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(KBShapeCard)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(
            text = label,
            color = if (isDestructive) KBDanger else KBTextHi,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        description?.takeIf { it.isNotBlank() }?.let { line ->
            Text(
                text = line,
                color = KBTextLo,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

/**
 * The catch-up program list: what this provider can still serve, newest first.
 *
 * Empty and loading are distinct states on purpose - a provider that advertises
 * catch-up but has no guide history in the window lands on the empty message,
 * which explains itself rather than looking broken.
 */
@Composable
private fun MobileCatchupDialog(
    channelName: String,
    programs: List<CatchupProgram>,
    loading: Boolean,
    onDismiss: () -> Unit,
    onPlay: (CatchupProgram) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        KBDialogPanel(title = "Catch-up TV", subtitle = channelName) {
            when {
                loading -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.padding(vertical = 10.dp)
                ) {
                    CircularProgressIndicator(
                        color = KBAccent,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "Loading catch-up programs…",
                        color = KBTextLo,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                programs.isEmpty() -> Text(
                    text = "This channel advertises catch-up, but the guide has no " +
                        "aired programs for it in the window yet.",
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodySmall
                )

                else -> Column(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    programs.forEach { program ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(KBShapeCard)
                                .clickable { onPlay(program) }
                                .background(KBSurfaceRaised.copy(alpha = 0.7f))
                                .padding(horizontal = 12.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = program.title,
                                color = KBTextHi,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = formatCatchupWindow(
                                    program.startUtcMillis,
                                    program.endUtcMillis
                                ),
                                color = KBTextLo,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                }
            }

            KBButton(
                label = "CLOSE",
                modifier = Modifier.fillMaxWidth()
            ) { onDismiss() }
        }
    }
}

/**
 * The way back from "Hide this channel": the players' hidden ids resolved to
 * names, each with UNHIDE, plus UNHIDE ALL.
 *
 * Hiding is only offered on the sheet because this exists - a destructive row
 * with no undo on the phone would be a one-way door.
 */
@Composable
private fun MobileHiddenChannelsDialog(
    channels: List<IptvChannel>,
    onUnhide: (String) -> Unit,
    onUnhideAll: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        KBDialogPanel(title = "Hidden channels", subtitle = "${channels.size} hidden") {
            Column(
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                channels.forEach { channel ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = channel.displayName.ifBlank { channel.name },
                            color = KBTextHi,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        KBButton(label = "UNHIDE") { onUnhide(channel.id) }
                    }
                }
            }

            KBButton(
                label = "UNHIDE ALL",
                modifier = Modifier.fillMaxWidth()
            ) { onUnhideAll() }
            KBButton(
                label = "CLOSE",
                modifier = Modifier.fillMaxWidth()
            ) { onDismiss() }
        }
    }
}
