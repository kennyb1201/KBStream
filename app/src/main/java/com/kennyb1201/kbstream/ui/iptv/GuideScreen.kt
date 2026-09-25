package com.kennyb1201.kbstream.ui.iptv

import android.content.Context
import android.view.KeyEvent
import androidx.tv.material3.Border
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.DisposableEffect
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.kennyb1201.kbstream.data.iptv.CatchupProgram
import com.kennyb1201.kbstream.data.iptv.EpgMatchType
import com.kennyb1201.kbstream.data.iptv.IptvChannelWithEpg
import com.kennyb1201.kbstream.data.iptv.IptvPlaylist
import com.kennyb1201.kbstream.data.iptv.IptvReminderStore
import com.kennyb1201.kbstream.data.iptv.LiveChannelZapRegistry
import com.kennyb1201.kbstream.data.iptv.PendingChannelTune
import com.kennyb1201.kbstream.data.iptv.db.EpgProgramRow
import com.kennyb1201.kbstream.data.notifications.ReminderRules
import com.kennyb1201.kbstream.work.ReminderWorker
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.components.KBPasteChip
import com.kennyb1201.kbstream.ui.components.KBTextField
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import com.kennyb1201.kbstream.ui.theme.KBDanger
import com.kennyb1201.kbstream.ui.theme.KBRust
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.android.awaitFrame
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged

private const val GUIDE_PREFETCH_BEFORE_COUNT = 12
private const val GUIDE_PREFETCH_AFTER_COUNT = 36
private const val MAX_GUIDE_CHANNEL_REQUEST_SIZE = 48

// Reused rather than allocated per-call/per-row; both are only ever touched
// from the main thread (composition + the clock's own LaunchedEffect), so a
// shared mutable SimpleDateFormat is safe here.
private val clockLabelFormatter = SimpleDateFormat("EEE, h:mm a", Locale.US)
private val programTimeFormatter = SimpleDateFormat("h:mm a", Locale.US)

@Composable
fun GuideScreen(
    viewModel: IptvViewModel = viewModel(),
    modifier: Modifier = Modifier,
    /**
     * Leaves the guide. Back uses it for the two states that have nothing of
     * their own to close: the setup form with no playlist yet (where the form
     * IS the screen) and the plain guide. Wired by MainActivity to Home.
     */
    onBack: () -> Unit = {},
    defaultPlaylistUrl: String = "",
    defaultEpgUrl: String = "",
    defaultPlaylistName: String = "",
    onPlayChannel: ((IptvChannelWithEpg) -> Unit)? = null,
    onPlayCatchup: ((IptvChannelWithEpg, CatchupProgram) -> Unit)? = null
) {
    val playlist by viewModel.playlist.collectAsState()
    val visibleChannels by viewModel.visibleChannels.collectAsState()
    // Channels whose guide row has actually been queried. Used to keep a
    // channel that is merely still loading from being labelled "No program
    // data" (see resolvedGuideChannelIds in the ViewModel).
    val resolvedGuideIds by viewModel.resolvedGuideChannelIds.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val isImportingGuide by viewModel.isImportingGuide.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val guideError by viewModel.guideError.collectAsStateWithLifecycle()
    val playlistUrl by viewModel.playlistUrl.collectAsStateWithLifecycle()
    val epgUrl by viewModel.epgUrl.collectAsState()
    val playlistName by viewModel.playlistName.collectAsStateWithLifecycle()
    val extraPlaylistUrls by viewModel.extraPlaylistUrls.collectAsStateWithLifecycle()
    val extraEpgUrls by viewModel.extraEpgUrls.collectAsStateWithLifecycle()

    val channelListState = rememberLazyListState()
    val firstChannelFocusRequester = remember { FocusRequester() }
    val allTabFocusRequester = remember { FocusRequester() }
    val groupChipFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val latestOnPlayChannel by rememberUpdatedState(onPlayChannel)
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    // Per-profile scoping: favorites/hidden groups belong to the active
    // profile's playlist (a different profile can stream a different M3U,
    // so channel IDs are not interchangeable). Also re-keyed on the active
    // profile so a switch reloads the incoming profile's sets.
    val activeProfileId by com.kennyb1201.kbstream.data.sync.ProfileManager
        .activeProfile.collectAsState()
    val guidePreferences = remember(appContext, activeProfileId) {
        appContext.getSharedPreferences(
            com.kennyb1201.kbstream.data.sync.ProfileStorage.prefsName(
                appContext, "iptv_guide_preferences"
            ),
            Context.MODE_PRIVATE
        )
    }
    var favorites by remember(activeProfileId) {
        mutableStateOf(guidePreferences.getStringSet("favorites", emptySet())?.toSet().orEmpty())
    }
    
    var hiddenGroups by remember(activeProfileId) {
        mutableStateOf(guidePreferences.getStringSet("hidden_groups", emptySet())?.toSet().orEmpty())
    }

    // Hidden groups and favorites are also written by the sync applier. Without
    // a listener the screen keeps the set it composed with, so a synced hide
    // never appears — and the next local edit saves that stale set back, which
    // reverts the cloud copy for the other devices too. The listener fires on
    // whichever thread wrote the value (the applier runs off the main one), so
    // the state update is posted to the main thread.
    DisposableEffect(guidePreferences) {
        val main = Handler(Looper.getMainLooper())
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "hidden_groups" -> main.post {
                    hiddenGroups = guidePreferences
                        .getStringSet("hidden_groups", emptySet())?.toSet().orEmpty()
                }
                "favorites" -> main.post {
                    favorites = guidePreferences
                        .getStringSet("favorites", emptySet())?.toSet().orEmpty()
                }
            }
        }
        guidePreferences.registerOnSharedPreferenceChangeListener(listener)
        onDispose { guidePreferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    var menuItem by remember { mutableStateOf<IptvChannelWithEpg?>(null) }
    // Catch-up (DVR): long-press a channel → CATCH-UP TV. The channel is
    // held here while the program list dialog is up; programs stream in
    // from the ViewModel (empty until the provider answers).
    var catchupChannel by remember { mutableStateOf<IptvChannelWithEpg?>(null) }
    val catchupPrograms by viewModel.catchupPrograms.collectAsStateWithLifecycle()

    // Programme reminders: set from the channel menu ("REMIND ME: <next>").
    // A poller fires an in-guide banner when a reminder's programme starts;
    // WATCH NOW zaps via the same path as a normal channel click.
    var reminders by remember(activeProfileId) {
        mutableStateOf(IptvReminderStore.load(guidePreferences))
    }
    var reminderBanner by remember { mutableStateOf<IptvReminderStore.Reminder?>(null) }
    LaunchedEffect(reminders) {
        while (true) {
            delay(15_000)
            val now = System.currentTimeMillis()
            if (reminderBanner == null) {
                reminderBanner = reminders.firstOrNull {
                    it.startUtcMillis in 1..now && now < it.endUtcMillis
                }
            }
            // Prune reminders whose programme ended more than 10 minutes ago.
            val staleCutoff = now - 10 * 60_000L
            if (reminders.any { it.endUtcMillis in 1..staleCutoff }) {
                reminders.filter { it.endUtcMillis in 1..staleCutoff }.forEach { stale ->
                    IptvReminderStore.remove(guidePreferences, stale.channelId, stale.startUtcMillis)
                }
                reminders = reminders.filter { it.endUtcMillis > staleCutoff }
            }
        }
    }
    
    var moveFocusToChannelList by remember { mutableStateOf(false) }

    // Channel search: D-pad SEARCH/MENU (or the header button) opens a
    // text overlay that filters the channel list live.
    var showSearch by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // Last-viewed persistence: reopening the guide drops you back on the
    // group/channel you were on instead of "All" + top of the list. The
    // pending* fields are consumed once by the membership effect below the
    // moment the restored channel's list content first exists.
    val savedGroup = remember(activeProfileId) {
        guidePreferences.getString("last_group", null)
    }
    val savedChannelKey = remember(activeProfileId) {
        guidePreferences.getString("last_channel", null)
    }
    var pendingChannelKey by remember(activeProfileId) { mutableStateOf<String?>(savedChannelKey) }
    var pendingFocusChannel by remember(activeProfileId) { mutableStateOf(!savedChannelKey.isNullOrBlank()) }
    var membershipBump by remember { mutableStateOf(0) }
    var digitEntry by remember { mutableStateOf("") }
    // Set when Up is pressed from the channel list's top row. The
    // Up-handler can't focus the group chip directly because the chip may
    // not be composed yet (outside the LazyRow viewport -> no requester);
    // the pendingGroupChipFocus effect consumes this after the chips row
    // has snapped to the selected group and the chip exists.
    var pendingGroupChipFocus by remember { mutableStateOf(false) }
    val channelRowFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }

    fun channelKey(item: IptvChannelWithEpg): String =
        item.channel.id.ifBlank { item.channel.streamUrl }
    fun favoriteKey(item: IptvChannelWithEpg): String = channelKey(item)
    fun saveSet(key: String, values: Set<String>) {
        guidePreferences.edit().putStringSet(key, values).apply()
        // Sync push: favorites and hidden groups must cross devices. Only
        // these two ride saveSet (recents write directly), so enqueue the
        // fresh IPTV payload — a no-op outbox entry when signed out.
        com.kennyb1201.kbstream.data.sync.SupabaseSync.enqueuePrefs(
            appContext,
            com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder.KEY_IPTV,
            com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder.buildIptv(appContext)
        )
    }
    fun withFavoriteFlag(item: IptvChannelWithEpg): IptvChannelWithEpg =
        item.copy(isFavorite = favoriteKey(item) in favorites)

    // Recently-played channels: a queue of channel keys updated on PLAY
    // (not mere focus), newest first, capped at 8. Rendered as a
    // "RECENT" group at the front of the groups strip.
    var recentChannelKeys by remember(activeProfileId) {
        mutableStateOf(
            guidePreferences.getStringSet("recent_channels", emptySet())
                ?.toSet().orEmpty().toList().take(8)
        )
    }
    LaunchedEffect(recentChannelKeys) {
        guidePreferences.edit()
            .putStringSet("recent_channels", recentChannelKeys.toSet())
            .apply()
    }

    val unhiddenChannels = remember(visibleChannels, hiddenGroups) {
    visibleChannels.filter { item ->
        val group = item.channel.groupTitle?.trim().orEmpty()
        group !in hiddenGroups
    }
}
    val hiddenChannelIds by viewModel.hiddenChannelIds.collectAsStateWithLifecycle()
    val groups = remember(unhiddenChannels, favorites, recentChannelKeys) {
        buildList {
            add("All")
            if (recentChannelKeys.isNotEmpty()) add("Recent")
            if (unhiddenChannels.any { favoriteKey(it) in favorites }) add("Favorites")
            val seenGroups = LinkedHashSet<String>()
            unhiddenChannels.forEach { item ->
                item.channel.groupTitle?.trim()?.takeIf { it.isNotBlank() }?.let(seenGroups::add)
            }
            addAll(seenGroups)
        }
    }
    var selectedGroup by remember(activeProfileId) { mutableStateOf(savedGroup?.takeIf { it.isNotBlank() } ?: "All") }
    val groupedChannels = remember(unhiddenChannels, selectedGroup, favorites, recentChannelKeys) {
        when (selectedGroup) {
            "All" -> unhiddenChannels
            "Favorites" -> unhiddenChannels.filter { favoriteKey(it) in favorites }
            "Recent" -> {
                // Newest-first over the persisted play order; keys that no
                // longer resolve (channel removed/hidden) drop out.
                val byKey = unhiddenChannels.associateBy(::channelKey)
                recentChannelKeys.mapNotNull(byKey::get)
            }
            else -> unhiddenChannels.filter { it.channel.groupTitle?.trim() == selectedGroup }
        }
    }

    // Publish the list being browsed as the zapping lineup. UP/DOWN in the
    // player walks THIS list, so the group you are in — and its ordering, how
    // "Favorites"/"Recent" are built included — is what changes channels;
    // zapping across the whole playlist was what made it uselessly long. The
    // same lineup resolves a typed channel number in the player, so both stay
    // in step by construction rather than by a second filtering rule.
    LaunchedEffect(selectedGroup, groupedChannels, epgUrl) {
        // An empty list is always transient here (the lineup flow restarts
        // empty on resubscribe); publishing it would blank a lineup that is
        // still correct for the moment the user is playing from.
        if (groupedChannels.isEmpty()) return@LaunchedEffect
        LiveChannelZapRegistry.set(
            channels = groupedChannels.map { item ->
                LiveChannelZapRegistry.ZapChannel(
                    channelId = channelKey(item),
                    name = item.channel.displayName.ifBlank { "Live Channel" },
                    streamUrl = item.channel.streamUrl,
                    logoUrl = item.channel.logoUrl ?: item.epgChannel?.iconUrl,
                    headers = item.channel.headers,
                    chno = item.channel.tvgChno?.trim()?.takeIf { it.isNotBlank() },
                    epgChannelId = item.epgChannel?.id,
                    epgUrl = epgUrl.trim().takeIf { it.isNotBlank() }
                )
            },
            browsingGroup = selectedGroup
        )
    }

    // A reminder notification's tap asked for a specific channel; play it as
    // soon as the lineup can resolve it, exactly once (see PendingChannelTune
    // for why a channel that no longer exists just lands on the guide).
    LaunchedEffect(unhiddenChannels.size, activeProfileId) {
        if (unhiddenChannels.isEmpty()) return@LaunchedEffect
        val wanted = PendingChannelTune.consume() ?: return@LaunchedEffect
        val item = unhiddenChannels.firstOrNull { channelKey(it) == wanted }
            ?: return@LaunchedEffect
        latestOnPlayChannel?.invoke(item)
    }

    fun moveSelectedGroup(direction: Int) {
    if (groups.isEmpty()) return

    val currentIndex = groups.indexOf(selectedGroup).takeIf { it >= 0 } ?: 0
    val newIndex = (currentIndex + direction).coerceIn(0, groups.lastIndex)

    if (newIndex != currentIndex) {
        selectedGroup = groups[newIndex]
    }
    }
    
    val groupedChannelIds = remember(groupedChannels) {
    groupedChannels.map { it.channel.id }
    }
    var selectedChannelId by remember { mutableStateOf<String?>(null) }
    val selectedChannelIndex = groupedChannels.indexOfFirst { item ->
        item.channel.id == selectedChannelId
    }.takeIf { it >= 0 } ?: if (groupedChannels.isNotEmpty()) 0 else -1
    val selectedChannel = groupedChannels.getOrNull(selectedChannelIndex)

    // If the restored/selected group no longer exists (playlist changed or
    // the group got hidden since last visit) fall back to "All" instead of
    // leaving an empty guide.
    LaunchedEffect(groups) {
        if (groups.isNotEmpty() && selectedGroup !in groups) selectedGroup = "All"
    }

    LaunchedEffect(selectedGroup) {
        guidePreferences.edit().putString("last_group", selectedGroup).apply()
    }

    LaunchedEffect(selectedChannelId) {
        val item = groupedChannels.firstOrNull { it.channel.id == selectedChannelId }
        if (item != null) {
            guidePreferences.edit().putString("last_channel", channelKey(item)).apply()
        }
    }

    // Persist the landing spot the moment a channel is played instead of
    // waiting for the selection effect above. The player is a separate screen,
    // so this guide — and its in-memory selection — is torn down while it is
    // up; a state effect can lose that race and then Back from the player
    // dropped the user at the top of the list rather than on the channel they
    // clicked. Group is saved with it so the restore lands in the same
    // group's lineup (and therefore the same channel order) too.
    fun rememberGuidePosition(item: IptvChannelWithEpg) {
        guidePreferences.edit()
            .putString("last_group", selectedGroup)
            .putString("last_channel", channelKey(item))
            .apply()
    }

    fun resolveChannelNumber(entry: String) {
        if (entry.isBlank()) return
        val target = unhiddenChannels.firstOrNull { it.channel.tvgChno?.trim() == entry } ?: return
        pendingChannelKey = channelKey(target)
        pendingFocusChannel = true
        val targetGroup = target.channel.groupTitle?.trim().orEmpty().ifBlank { "All" }
        if (targetGroup != selectedGroup) selectedGroup = targetGroup
        membershipBump += 1
    }

    var showSetup by remember { mutableStateOf(playlist == null) }
    var showHiddenManager by remember { mutableStateOf(false) }
    val groupRowState = rememberLazyListState()

    val dismissSetup = {
        showSetup = false
    }
    val dismissSearch = {
        showSearch = false
        searchQuery = ""
    }

    // Back contract for the guide, in priority order:
    //  1. an open search overlay closes;
    //  2. with no playlist loaded the setup form IS the screen — there is
    //     nothing behind it to reveal, so Back must LEAVE the guide. It used
    //     to be handled by falling through to MainActivity, which only worked
    //     while nothing else consumed the press; that made this screen a
    //     one-way door whenever a focused field ate Back.
    //  3. the setup overlay over a loaded guide closes, staying put;
    //  4. anything else leaves the guide (the same place MainActivity routes
    //     Back to from the guide).
    //
    // Two entry points share this one decision:
    //  - the root key handler, which claims Back on the way DOWN the tree so no
    //    focused child can swallow it first (the setup form's own fields used
    //    to be able to);
    //  - the BackHandler, which covers the frames where nothing in the guide
    //    holds focus at all, so the key never reaches a key handler.
    val handleGuideBack: () -> Boolean = {
        when {
            showSearch -> {
                dismissSearch()
                true
            }
            // Before a playlist exists the setup form IS this screen, so Back
            // is the only way out of it.
            playlist == null -> {
                onBack()
                true
            }
            // Playlist loaded: the form is an overlay over the guide, so Back
            // closes the overlay and stays here.
            showSetup -> {
                dismissSetup()
                true
            }
            else -> {
                onBack()
                true
            }
        }
    }
    BackHandler(enabled = showSearch || showSetup || playlist == null) {
        handleGuideBack()
    }

    // Live-filtered channel list for the search overlay: case-insensitive
    // contains on display name and channel number.
    val searchResults = remember(searchQuery, unhiddenChannels) {
        val q = searchQuery.trim()
        when {
            q.isBlank() -> emptyList()
            else -> unhiddenChannels.filter { item ->
                item.channel.displayName.contains(q, ignoreCase = true) ||
                    item.channel.name.contains(q, ignoreCase = true) ||
                    item.channel.tvgChno?.trim() == q
            }.take(40)
        }
    }

    // Channel hits answer "what's on it?" before it is opened: the rows come
    // from the same guide lookup the grid uses, queued for the hits this query
    // produced (same per-request cap the grid's own rows use). A channel with
    // no rows yet simply keeps the group line it always had.
    val loadedGuideItems by viewModel.loadedGuideItems.collectAsStateWithLifecycle()
    LaunchedEffect(showSearch, searchResults) {
        if (!showSearch || searchResults.isEmpty()) return@LaunchedEffect
        viewModel.updateGuideChannels(
            searchResults.map { item -> channelKey(item) }
                .take(MAX_GUIDE_CHANNEL_REQUEST_SIZE)
        )
    }

    // EPG program hits for the same query ("what's on with X tonight").
    // The ViewModel debounces the query and searches the EPG table; here the
    // hits are mapped onto VISIBLE channels only, so a channel the user hid
    // cannot resurface through a program match.
    val programSearchRows by viewModel.programSearchResults.collectAsStateWithLifecycle()
    LaunchedEffect(searchQuery, showSearch) {
        if (!showSearch) return@LaunchedEffect
        viewModel.searchPrograms(searchQuery)
    }
    val programSearchHits = remember(programSearchRows, unhiddenChannels) {
        if (programSearchRows.isEmpty()) {
            emptyList()
        } else {
            val byEpgId = unhiddenChannels
                .mapNotNull { item -> item.epgChannel?.id?.let { id -> id to item } }
                .toMap()
            programSearchRows
                .mapNotNull { row -> byEpgId[row.channelId]?.let { item -> GuideProgramHit(item, row) } }
                .take(30)
        }
    }

LaunchedEffect(defaultPlaylistUrl, defaultEpgUrl, defaultPlaylistName) {
    if (
        playlist == null &&
        !isLoading &&
        playlistUrl.isBlank() &&
        defaultPlaylistUrl.isNotBlank()
    ) {
        viewModel.onPlaylistUrlChanged(defaultPlaylistUrl)
        if (defaultEpgUrl.isNotBlank()) viewModel.onEpgUrlChanged(defaultEpgUrl)
        if (defaultPlaylistName.isNotBlank()) viewModel.onPlaylistNameChanged(defaultPlaylistName)
        viewModel.load()
        showSetup = false
    }
}

LaunchedEffect(playlist) {
    if (playlist != null) showSetup = false
}

LaunchedEffect(groupedChannels) {
    val currentStillExists = groupedChannels.any { it.channel.id == selectedChannelId }

    if (!currentStillExists) {
        selectedChannelId = groupedChannels.firstOrNull()?.channel?.id
    }
}

// Keep the selected group chip FULLY in view on every group change
// (left/right from the channel list, focus walks along the chips row,
// restores). "Fully" matters: visibleItemsInfo also reports chips clipped
// to a sliver at the row edge, so the old any-index check let a
// highlighted chip sit mostly offscreen with an unreadable selection --
// it only self-corrected once the target was completely off the row.
// Now: a chip clipped by the end edge scrolls in by exactly the overflow
// (so chips walks still slide minimally), a chip truly clipped by the
// start edge (or not composed at all) snaps flush, and a fully visible
// chip leaves the row alone. scrollToItem (not animateScrollToItem): an
// animated scroll is slow enough that the whole rail visibly flashes past
// intermediate chips on every group change, and during the animation the
// target chip is not yet composed, which the Up-from-list focus flow
// below depends on (an uncomposed chip has no FocusRequester and default
// spatial focus then lands on whichever chip IS visible, silently
// switching the group).
LaunchedEffect(selectedGroup, groups) {
    val chipIndex = groups.indexOf(selectedGroup)
    if (chipIndex < 0) return@LaunchedEffect

    val layout = groupRowState.layoutInfo
    val info = layout.visibleItemsInfo.firstOrNull { it.index == chipIndex }
    // viewportStartOffset is negative while beforeContentPadding is
    // showing; a chip tucked under it is still fully on screen, so only
    // offsets beyond that count as clipped. viewportEndOffset is the
    // documented "not fully visible past this" bound (after padding).
    val startClipped = info == null || info.offset < layout.viewportStartOffset
    val endOverflow = info?.let { (it.offset + it.size) - layout.viewportEndOffset } ?: 0
    when {
        startClipped ->
            groupRowState.scrollToItem(chipIndex.coerceIn(0, groups.lastIndex))
        endOverflow > 0 ->
            groupRowState.dispatchRawDelta(endOverflow.toFloat())
    }
}

// Up from the channel list's top row: after the snap effect above has
// scrolled the selected group's chip into the viewport (making its
// FocusRequester exist), grab focus on it. Retries across a few frames
// because scrollToItem + composition of the newly visible chip complete
// asynchronously. Consumes the flag whether or not it succeeds so a
// missing chip (group removed mid-flight) can't wedge the row.
LaunchedEffect(pendingGroupChipFocus) {
    if (!pendingGroupChipFocus) return@LaunchedEffect
    val chipIndex = groups.indexOf(selectedGroup)
    if (chipIndex < 0) {
        pendingGroupChipFocus = false
        return@LaunchedEffect
    }

    var focused = false
    var attempts = 0
    while (!focused && attempts < 8) {
        awaitFrame()
        val requester = groupChipFocusRequesters[selectedGroup]
        if (requester != null) {
            focused = runCatching { requester.requestFocus() }.isSuccess
        }
        attempts++
    }
    pendingGroupChipFocus = false
}

LaunchedEffect(channelListState, groupedChannelIds) {
    snapshotFlow {
        val visibleItems = channelListState.layoutInfo.visibleItemsInfo

        when {
            groupedChannelIds.isEmpty() -> emptyList()

            visibleItems.isEmpty() -> groupedChannelIds
                .take(MAX_GUIDE_CHANNEL_REQUEST_SIZE)

            else -> {
                val firstVisible = visibleItems.first().index
                val lastVisible = visibleItems.last().index

                val start = (firstVisible - GUIDE_PREFETCH_BEFORE_COUNT)
                    .coerceAtLeast(0)

                val endExclusive = (lastVisible + GUIDE_PREFETCH_AFTER_COUNT + 1)
                    .coerceAtMost(groupedChannelIds.size)

                groupedChannelIds
                    .subList(start, endExclusive)
                    .distinct()
                    .take(MAX_GUIDE_CHANNEL_REQUEST_SIZE)
            }
        }
    }
        .distinctUntilChanged()
        .debounce(400)
        .collectLatest { channelIds ->
            if (channelIds.isNotEmpty()) {
                viewModel.updateGuideChannels(channelIds)
            }
        }
}
  // Key on the channel membership itself rather than the list reference:
  // EPG data arriving while browsing rebuilds the channel objects (new list
  // instance every time), which used to re-fire this effect and yank the
  // channel list back to the top mid-scroll. Membership only changes when
  // the group content actually changes, so scrolling survives EPG updates.
  val groupedChannelMembership = remember(groupedChannels) {
    selectedGroup + "|" + groupedChannels.joinToString("|") { it.channel.id }
  }
  LaunchedEffect(groupedChannelMembership, membershipBump) {
    if (groupedChannels.isEmpty()) return@LaunchedEffect

    // A group switch swaps in a whole screenful of channels the guide window
    // has never covered, and the scroll-driven prefetch below is debounced
    // (400ms) to stop scroll churn from re-querying on every frame. Fine
    // while scrolling, but on a group change that debounce stacked on top of
    // the lineup query, so the new group sat there with no programme data for
    // over a second. Queueing the incoming group's window here starts the
    // query on the first frame instead.
    viewModel.updateGuideChannels(
        groupedChannelIds.take(MAX_GUIDE_CHANNEL_REQUEST_SIZE)
    )

    // A pending channel key (restore-on-open or channel-number jump) wins
    // over the default "select first row" reset.
    val pending = pendingChannelKey
    pendingChannelKey = null
    val wasPendingFocus = pendingFocusChannel
    pendingFocusChannel = false

    val target = pending?.let { key ->
        groupedChannels.firstOrNull { channelKey(it) == key }
    }
    val targetId = target?.channel?.id
        ?: groupedChannels.firstOrNull()?.channel?.id
    selectedChannelId = targetId

    val targetIndex = groupedChannels.indexOfFirst { it.channel.id == targetId }
    channelListState.scrollToItem(if (targetIndex > 0) targetIndex else 0)

    if (target != null && wasPendingFocus) {
        val rowRequester = channelRowFocusRequesters[channelKey(target)]
        if (rowRequester != null) {
            var focused = false
            var attempts = 0
            while (!focused && attempts < 6) {
                awaitFrame()
                focused = runCatching { rowRequester.requestFocus() }
                    .getOrDefault(false)
                attempts++
            }
        }
    } else if (pending != null && target == null) {
        // Saved channel vanished (playlist changed) -- make sure something
        // is focused instead of leaving the screen focusless.
        runCatching { allTabFocusRequester.requestFocus() }
    }
  }

  LaunchedEffect(moveFocusToChannelList, groupedChannels) {
    if (moveFocusToChannelList && groupedChannels.isNotEmpty()) {
        selectedChannelId = groupedChannels.first().channel.id
        channelListState.scrollToItem(0)

        // Switching groups swaps the entire channel list's content (a
        // heavier layout pass than the down-from-tabs case), so a single
        // awaitFrame() isn't always enough for the new top row to have
        // attached yet -- requestFocus() would then silently miss its
        // target and focus escapes somewhere else entirely (often back up
        // into the tabs row). Retry across a few frames instead of
        // assuming one is enough.
        var focused = false
        var attempts = 0
        while (!focused && attempts < 5) {
            awaitFrame()
            focused = runCatching {
                firstChannelFocusRequester.requestFocus()
            }.isSuccess
            attempts++
        }

        moveFocusToChannelList = false
    }
}

  // Channel-number entry: digits accumulate in digitEntry and resolve after
  // a short pause (classic TV remote behavior -- more digits can follow).
  LaunchedEffect(digitEntry) {
    if (digitEntry.isBlank()) return@LaunchedEffect
    delay(1200)
    resolveChannelNumber(digitEntry)
    digitEntry = ""
  }

  // Nothing has real D-pad focus on first entry (the "All" tab is only
  // *visually* selected via selectedGroup's default value), so the very
  // first key press falls through to the platform's default focus
  // resolution instead of landing on the tabs row -- claim it explicitly
  // once so Down from the tabs row is deterministic from the start.
  // When restoring the last-viewed channel, the row focus (in the
  // membership effect above) takes over instead of the "All" chip.
  // The restore case has to be read from the SAVED key, not from the pending
  // focus flag: that flag is cleared the moment the membership effect above
  // starts, so a guide whose channels are already cached (the usual case)
  // cleared it before this effect ran, this grabbed the chips row on the next
  // frame, and returning from the player left focus on the tabs row instead of
  // on the channel you clicked.
  LaunchedEffect(Unit) {
    awaitFrame()
    val restoringChannel = savedChannelKey
        ?.takeIf { key -> groupedChannels.any { channelKey(it) == key } } != null
    if (!pendingFocusChannel && !restoringChannel) {
        runCatching { allTabFocusRequester.requestFocus() }
    }
}

    // Both the inline (no-playlist) and overlay (showSetup) placements of the
    // setup form share identical wiring - only the surrounding modifier differs.
    val renderSetupPanel: @Composable (Modifier, Boolean) -> Unit = { panelModifier, containFocus ->
        SetupPanel(
            playlistUrl = playlistUrl,
            epgUrl = epgUrl,
            playlistName = playlistName,
            extraPlaylistUrls = extraPlaylistUrls,
            extraEpgUrls = extraEpgUrls,
            isLoading = isLoading,
            isImportingGuide = isImportingGuide,
            error = error,
            guideError = guideError,
            playlist = playlist,
            channelCount = visibleChannels.size,
            onPlaylistUrlChanged = viewModel::onPlaylistUrlChanged,
            onEpgUrlChanged = viewModel::onEpgUrlChanged,
            onPlaylistNameChanged = viewModel::onPlaylistNameChanged,
            onExtraPlaylistUrlsChanged = viewModel::onExtraPlaylistUrlsChanged,
            onExtraEpgUrlsChanged = viewModel::onExtraEpgUrlsChanged,
            onLoad = { viewModel.load() },
            onReload = { viewModel.load() },
            onImportGuide = { viewModel.importGuide() },
            onManageHidden = { showHiddenManager = true },
            onClear = {
                viewModel.onPlaylistUrlChanged("")
                viewModel.onEpgUrlChanged("")
                viewModel.onPlaylistNameChanged("")
                viewModel.onExtraPlaylistUrlsChanged("")
                viewModel.onExtraEpgUrlsChanged("")
                showSetup = true
            },
            containFocus = containFocus,
            modifier = panelModifier
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(KBVoid)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                // Back is claimed here, on the way down the tree, so nothing
                // focused inside the screen can get first refusal on it — the
                // playlist setup form's URL fields were able to, which is what
                // made that screen feel like a trap. (An OPEN IME window is
                // the one thing this cannot see: it owns Back itself, which is
                // why the setup fields never open the keyboard on focus.)
                if (event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_BACK) {
                    return@onPreviewKeyEvent handleGuideBack()
                }

                // Channel-number entry only when the guide owns the stage:
                // never while the setup form, hidden-items manager, or
                // channel menu is up, so digits keep reaching those controls.
                val digitsAllowed = playlist != null && !showSetup &&
                    menuItem == null && !showHiddenManager && !showSearch
                if (!digitsAllowed) return@onPreviewKeyEvent false

                // Remote search button opens the channel-search overlay.
                when (event.nativeKeyEvent.keyCode) {
                    KeyEvent.KEYCODE_SEARCH,
                    KeyEvent.KEYCODE_PROG_YELLOW -> {
                        showSearch = true
                        return@onPreviewKeyEvent true
                    }
                }

                val code = event.nativeKeyEvent.keyCode
                val digit = when (code) {
                    in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ->
                        code - KeyEvent.KEYCODE_0
                    in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 ->
                        code - KeyEvent.KEYCODE_NUMPAD_0
                    else -> return@onPreviewKeyEvent false
                }
                if (digitEntry.length >= 4) return@onPreviewKeyEvent true
                digitEntry += digit.toString()
                true
            }
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            KBSurface.copy(alpha = 0.92f),
                            KBVoid.copy(alpha = 0.98f),
                            KBVoid
                        )
                    )
                )
        )

        when {
            isLoading && playlist == null -> {
                CenterMessage(
                    title = "Loading guide",
                    message = "Fetching playlist and program data...",
                    modifier = Modifier.fillMaxSize(),
                    showSpinner = true
                )
            }

            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp, vertical = 18.dp)
                ) {
                    GuideHeader(
                        title = playlist?.name?.ifBlank { "IPTV" } ?: "IPTV",
                        channelCount = groupedChannels.size,
                        selectedGroup = selectedGroup,
                        onSetupClick = { showSetup = !showSetup },
                        onSearchClick = { showSearch = true }
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    if (playlist == null) {
                        // Same treatment as the overlay placement below:
                        // cap the height and let the panel scroll. With no
                        // playlist loaded this inline panel is the whole
                        // screen, and once a URL is typed the diagnostics
                        // block pushes it past the display height — the
                        // lower URL fields and action row would clip off
                        // ("half the screen disappears").
                        val inlinePanelMaxHeight =
                            LocalConfiguration.current.screenHeightDp.dp - 120.dp
                        renderSetupPanel(
                            Modifier
                                .heightIn(max = inlinePanelMaxHeight)                                    .verticalScroll(rememberScrollState()),
                            // Inline: no guide behind the panel to escape into,
                            // and the header has to stay reachable.
                            false
                        )
                    } else {
                        if (groups.isNotEmpty()) {
                            LazyRow(
    state = groupRowState,
    // Start padding keeps the first chip (and any chip the auto-reveal
    // snaps to the left edge) from sitting flush where its focused
    // border/glow clips.
    contentPadding = PaddingValues(start = 8.dp, end = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    modifier = Modifier
        .focusGroup()
        .onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

            when (event.key) {
                Key.DirectionDown -> {
                    moveFocusToChannelList = true
                    true
                }
                else -> false
            }
        }
) {
    itemsIndexed(groups, key = { _, item -> item }) { index, group ->
        val chipFocusRequester = remember(group) {
            FocusRequester().also { groupChipFocusRequesters[group] = it }
        }
        GroupChip(
            name = group,
            selected = group == selectedGroup,
            onClick = { selectedGroup = group },
            onFocus = { if (!moveFocusToChannelList) selectedGroup = group },
            onLongClick = {
                // Quick-hide straight from the chips row (same storage the
                // hidden-items manager uses). If the current group hides
                // itself, the groups fallback effect returns to "All".
                hiddenGroups = hiddenGroups + group
                saveSet("hidden_groups", hiddenGroups)
            },
            modifier = Modifier
                .focusRequester(chipFocusRequester)
                .let { base ->
                    if (index == 0) {
                        base.focusRequester(allTabFocusRequester)
                    } else {
                        base
                    }
                }
        )
    }
}

Spacer(modifier = Modifier.height(14.dp))

                            Row(modifier = Modifier.fillMaxSize()) {
                                LazyColumn(
                                    state = channelListState,
                                    // Top inset gives the first row's focused
                                    // border + glow room above the viewport
                                    // edge (LazyColumn clips children to its
                                    // bounds); bottom mirrors the old inset.
                                    contentPadding = PaddingValues(top = 8.dp, bottom = 20.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                    modifier = Modifier
                                        .width(348.dp)
                                        .fillMaxHeight()
                                        .focusGroup()
                                ) {itemsIndexed(
    items = groupedChannels,
    key = { _, item -> channelKey(item) }
) { index, rawItem ->
                                        val item = withFavoriteFlag(rawItem)
                                        val rowFocusRequester =
                                            remember(item.channel.id) {
                                                FocusRequester().also {
                                                    channelRowFocusRequesters[channelKey(item)] = it
                                                }
                                            }

                                        ChannelRowCard(
                                            item = item,
                                            selected = selectedChannelIndex == index,
                                            guidePending = item.channel.id !in resolvedGuideIds,
                                            onClick = {
                                                selectedChannelId = item.channel.id
                                                // Track into the Recent group
                                                // (newest first, capped).
                                                recentChannelKeys =
                                                    (listOf(channelKey(item)) + recentChannelKeys)
                                                        .distinct()
                                                        .take(8)
                                                rememberGuidePosition(item)
                                                latestOnPlayChannel?.invoke(item)
                                            },
                                            onFocused = {
                                                selectedChannelId = item.channel.id
                                            },
                                            onLongClick = {
    selectedChannelId = item.channel.id
    menuItem = item
},modifier = (
    // firstChannelFocusRequester is only ever targeted right after code
    // resets selection to the top of the list (scrollToItem(0) +
    // selectedChannelId = first item), so it must anchor to index 0
    // deterministically -- attaching it via selectedChannelIndex instead
    // raced against that same reset (selectedChannelId hadn't caught up
    // yet), leaving the requester's target detached at the moment
    // requestFocus() fired. rowFocusRequester is the per-row handle the
    // restore / channel-number-jump flows use to land D-pad focus exactly.
    if (index == 0) {
        Modifier
            .focusRequester(rowFocusRequester)
            .focusRequester(firstChannelFocusRequester)
    } else {
        Modifier.focusRequester(rowFocusRequester)
    }
).onPreviewKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

    when (event.key) {
        Key.DirectionLeft -> {
            moveSelectedGroup(-1)
            moveFocusToChannelList = true
            true
        }
        Key.DirectionRight -> {
            moveSelectedGroup(1)
            moveFocusToChannelList = true
            true
        }
        Key.DirectionUp -> {
            // Only override Up on the list's top row -- deeper rows
            // should still move focus to the row above them normally.
            // The chip for selectedGroup may not be composed right now (it
            // sits outside the LazyRow viewport), so its FocusRequester
            // would be null and default spatial focus would land on
            // whichever chip IS visible -- silently switching the group.
            // Instead, queue a pending focus request: the snap effect
            // below scrolls the chip into view first, and the effect key
            // fires again once it exists to grab focus for real.
            if (index == 0) {
                pendingGroupChipFocus = true
                true
            } else {
                false
            }
        }
        else -> false
    }
}
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(16.dp))

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight()
                                ) {
                                    val detailItem = selectedChannel?.let(::withFavoriteFlag)
                                    val detailGuidePending = detailItem != null &&
                                        detailItem.channel.id !in resolvedGuideIds
                                    if (detailItem != null) {
                                        key(
                                            detailItem.channel.id,
                                            detailGuidePending,
                                            detailItem.now?.startUtcMillis,
                                            detailItem.now?.endUtcMillis,
                                            detailItem.next?.startUtcMillis,
                                            detailItem.next?.endUtcMillis,
                                            detailItem.upcoming.firstOrNull()?.startUtcMillis
                                        ) {
                                            GuideDetailPanel(
                                                item = detailItem,
                                                guidePending = detailGuidePending
                                            )
                                        }
                                    } else {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            modifier = Modifier.fillMaxSize()
                                        ) {
                                            CenterMessage(
                                                title = if (groupedChannels.isEmpty()) {
                                                    "No channels in \"$selectedGroup\""
                                                } else {
                                                    "No channel selected"
                                                },
                                                message = if (groupedChannels.isEmpty()) {
                                                    "This view has nothing to show right now."
                                                } else {
                                                    "Choose a channel from the list to view program details."
                                                },
                                                modifier = Modifier.weight(1f).fillMaxWidth()
                                            )
                                            if (groupedChannels.isEmpty()) {
                                                KBCard(onClick = { selectedGroup = "All" }) {
                                                    Text(
                                                        text = "SHOW ALL CHANNELS",
                                                        color = KBTextHi,
                                                        style = MaterialTheme.typography.titleSmall,
                                                        modifier = Modifier.padding(
                                                            horizontal = 16.dp,
                                                            vertical = 11.dp
                                                        )
                                                    )
                                                }
                                                Spacer(modifier = Modifier.height(24.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (showSearch) {
                    ChannelSearchDialog(
                        query = searchQuery,
                        results = searchResults,
                        guideItems = loadedGuideItems,
                        programHits = programSearchHits,
                        channelKey = ::channelKey,
                        onQueryChanged = { searchQuery = it },
                        onPlay = { item ->
                            selectedChannelId = item.channel.id
                            recentChannelKeys =
                                (listOf(channelKey(item)) + recentChannelKeys)
                                    .distinct()
                                    .take(8)
                            rememberGuidePosition(item)
                            latestOnPlayChannel?.invoke(item)
                            dismissSearch()
                        },
                        onDismiss = { dismissSearch() }
                    )
                }

                if (showSetup && playlist != null) {
                    // Overlay placement: once the diagnostics block appears
                    // the panel is taller than the screen allows, so cap its
                    // height and let the content scroll — otherwise the
                    // action row at the bottom is unreachable with the
                    // D-pad. Focus order inside the panel does the rest
                    // (moveFocus(Down) walks the whole form).
                    val panelMaxHeight = LocalConfiguration.current.screenHeightDp.dp - 140.dp
                    renderSetupPanel(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 104.dp, end = 24.dp)
                            .width(500.dp)
                            .heightIn(max = panelMaxHeight)
                            .verticalScroll(rememberScrollState()),
                        // This placement sits OVER the guide, so it keeps every
                        // D-pad press inside itself rather than letting one
                        // land on the group chips behind it — except while a
                        // dialog opened FROM the panel (hidden items, channel
                        // menu, search, catch-up: all reachable from here)
                        // owns focus.
                        !showHiddenManager && menuItem == null && !showSearch &&
                            catchupChannel == null && reminderBanner == null
                    )
                }

                menuItem?.let { rawItem ->
                    val item = withFavoriteFlag(rawItem)
                    val itemReminderActive = item.next != null && IptvReminderStore.has(
                        guidePreferences, item.channel.id, item.next.startUtcMillis
                    )
                    ChannelActionsDialog(
                        item = item,
                        hasCatchup = item.channel.catchupSource != null || item.channel.catchup != null,
                        onDismiss = { menuItem = null },
                        onToggleReminder = item.next?.let { nxt ->
                            {
                                val reminderKey = ReminderRules.reminderKey(
                                    item.channel.id, nxt.startUtcMillis
                                )
                                if (itemReminderActive) {
                                    IptvReminderStore.remove(guidePreferences, item.channel.id, nxt.startUtcMillis)
                                    // Drop the armed alert too, or the reminder
                                    // the user just cancelled still buzzes.
                                    ReminderWorker.cancel(appContext, reminderKey)
                                    reminders = IptvReminderStore.load(guidePreferences)
                                } else {
                                    IptvReminderStore.add(
                                        guidePreferences,
                                        IptvReminderStore.Reminder(
                                            channelId = item.channel.id,
                                            channelName = item.channel.displayName,
                                            logoUrl = item.channel.logoUrl,
                                            programmeTitle = nxt.title,
                                            startUtcMillis = nxt.startUtcMillis,
                                            endUtcMillis = nxt.endUtcMillis
                                        )
                                    )
                                    // Arm the alert at the programme's start: the
                                    // guide's own banner only exists while the
                                    // guide is on screen, so without this a
                                    // reminder fires only if you happen to be
                                    // sitting in the guide at that moment.
                                    if (nxt.startUtcMillis > System.currentTimeMillis()) {
                                        ReminderWorker.arm(
                                            appContext, reminderKey, nxt.startUtcMillis
                                        )
                                    }
                                    reminders = IptvReminderStore.load(guidePreferences)
                                }
                                menuItem = null
                            }
                        },
                        onToggleFavorite = {
                            val key = favoriteKey(item)
                            favorites = if (key in favorites) favorites - key else favorites + key
                            saveSet("favorites", favorites)
                            menuItem = null
                        },
                        onHideChannel = {
    viewModel.hideChannel(channelKey(item))
    menuItem = null
},
                        onHideGroup = {
                            item.channel.groupTitle?.trim()?.takeIf { it.isNotBlank() }?.let { group ->
                                hiddenGroups = hiddenGroups + group
                                saveSet("hidden_groups", hiddenGroups)
                            }
                            menuItem = null
                        },
                        onOpenCatchup = {
                            catchupChannel = item
                            viewModel.loadCatchupPrograms(item.channel)
                            menuItem = null
                        }
                    )
                }

                // Programme-started banner: fires when a reminder's window
                // opens while the guide is open. WATCH NOW reuses the exact
                // channel-click path (recent list + onPlayChannel).
                reminderBanner?.let { hit ->
                    val bannerChannel = unhiddenChannels.firstOrNull {
                        it.channel.id == hit.channelId
                    }
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 24.dp)
                            .width(560.dp)
                            .background(KBSurfaceRaised, RoundedCornerShape(16.dp))
                            .border(1.dp, KBAccent.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "NOW ON: ${hit.channelName}",
                            color = KBAccent,
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            text = hit.programmeTitle,
                            color = KBTextHi,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(top = 12.dp)
                        ) {
                            KBCard(
                                onClick = {
                                    bannerChannel?.let { target ->
                                        recentChannelKeys =
                                            (listOf(channelKey(target)) + recentChannelKeys)
                                                .distinct()
                                                .take(8)
                                        rememberGuidePosition(target)
                                        latestOnPlayChannel?.invoke(target)
                                    }
                                    reminderBanner = null
                                }
                            ) {
                                Text(
                                    "WATCH NOW",
                                    color = KBTextHi,
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                                )
                            }
                            KBCard(
                                onClick = { reminderBanner = null }
                            ) {
                                Text(
                                    "DISMISS",
                                    color = KBTextLo,
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                                )
                            }
                        }
                    }
                }

                catchupChannel?.let { catchupItem ->
                    CatchupDialog(
                        channelName = catchupItem.channel.displayName.ifBlank { "Live Channel" },
                        programs = catchupPrograms,
                        onDismiss = { catchupChannel = null },
                        onPlay = { program ->
                            catchupChannel = null
                            onPlayCatchup?.invoke(catchupItem, program)
                        }
                    )
                }

                if (showHiddenManager) {
                    HiddenItemsDialog(
    playlist = playlist,
    hiddenChannels = hiddenChannelIds,
    hiddenGroups = hiddenGroups,
                        channelKey = ::channelKey,
                        onDismiss = { showHiddenManager = false },
                        onHideGroup = { group ->
                            hiddenGroups = hiddenGroups + group
                            saveSet("hidden_groups", hiddenGroups)
                        },
                        onUnhideGroup = { group ->
                            hiddenGroups = hiddenGroups - group
                            saveSet("hidden_groups", hiddenGroups)
                        },
                        onHideAllGroups = { groups ->
                            hiddenGroups = groups
                            saveSet("hidden_groups", hiddenGroups)
                        },
                        onUnhideChannel = { channelId ->
    viewModel.unhideChannel(channelId)
},
                        onUnhideAll = {
    viewModel.unhideAllChannels()
    hiddenGroups = emptySet()
    saveSet("hidden_groups", hiddenGroups)
}
                    )
                }

                if (digitEntry.isNotEmpty()) {
                    val digitMatch = unhiddenChannels.firstOrNull {
                        it.channel.tvgChno?.trim() == digitEntry
                    }
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        colors = SurfaceDefaults.colors(
                            containerColor = KBSurfaceRaised.copy(alpha = 0.97f),
                            contentColor = KBTextHi
                        ),
                        border = Border(
                            border = BorderStroke(1.dp, KBAccent.copy(alpha = 0.55f)),
                            shape = RoundedCornerShape(12.dp)
                        ),
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(24.dp)
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                            Text(
                                text = "CH $digitEntry",
                                color = KBAccent,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = digitMatch?.channel?.displayName ?: "Enter channel number",
                                color = KBTextLo,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                }

                if ((error != null || guideError != null) && playlist != null && !showSetup) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(24.dp)
                    ) {
                        InlineErrorChip(message = error ?: guideError.orEmpty())
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
private fun SetupPanel(
    playlistUrl: String,
    epgUrl: String,
    playlistName: String,
    extraPlaylistUrls: String,
    extraEpgUrls: String,
    isLoading: Boolean,
    isImportingGuide: Boolean,
    error: String?,
    guideError: String?,
    playlist: IptvPlaylist?,
    channelCount: Int,
    onPlaylistUrlChanged: (String) -> Unit,
    onEpgUrlChanged: (String) -> Unit,
    onPlaylistNameChanged: (String) -> Unit,
    onExtraPlaylistUrlsChanged: (String) -> Unit,
    onExtraEpgUrlsChanged: (String) -> Unit,
    onLoad: () -> Unit,
    onReload: () -> Unit,
    onImportGuide: () -> Unit,
    onManageHidden: () -> Unit,
    onClear: () -> Unit,
    /**
     * Keeps every D-pad press inside the panel. True for the overlay placement
     * (which sits over the guide) and false for the inline one, where the panel
     * is the whole screen and the header must stay reachable.
     */
    containFocus: Boolean = false,
    modifier: Modifier = Modifier
) {    val firstFieldFocusRequester = remember { FocusRequester() }
    // Recovery target. Deliberately the LOAD card and NOT a text field: see
    // the recovery block below — re-focusing a field re-opens the IME.
    val recoveryFocusRequester = remember { FocusRequester() }
    val panelFocusManager = LocalFocusManager.current

    // Initial focus grab with retry. A single awaitFrame + requestFocus
    // silently misses when the field hasn't attached yet (large overlay,
    // busy first frame), and focus then falls through to controls BEHIND
    // the panel (group chips) on the next key press. Retry a few frames —
    // the same pattern the guide's row-focus flows use.
    // NOTE: requestFocus() returns false (no exception) when focus can't
    // transfer to an unattached/not-yet-focusable node, so success is the
    // RETURN VALUE, not runCatching's isSuccess — that only ever means "no
    // exception thrown", which is always true, so the loop used to exit
    // after ONE frame with focus still stranded and the action row
    // unreachable by D-pad.
    LaunchedEffect(Unit) {
        var focused = false
        var attempts = 0
        while (!focused && attempts < 12) {
            awaitFrame()
            focused = runCatching { firstFieldFocusRequester.requestFocus() }
                .getOrDefault(false)
            attempts++
        }
    }

    // Focus recovery. The grab above only covers the first frames: on Fire TV
    // the leanback IME takes window focus while it is open, and when it closes
    // Compose can come back with no focused node at all — the next D-pad press
    // then starts a fresh search from the root and lands on the group chips
    // behind the panel (which the containment below cannot stop, because that
    // press never starts from inside the panel).
    //
    // Two rules keep this from turning into a trap:
    //  - A loss that arrives while another window owns focus is ignored. The
    //    leanback IME is a window of its own, so "nothing focused" then is
    //    just the keyboard being up — acting on it fights the field the user
    //    is typing into.
    //  - Focus goes back to a CARD, never a text field. A field that gains
    //    focus opens the IME, so restoring onto one made Back only ever close
    //    the keyboard again — the panel looked impossible to leave, and every
    //    further Back press re-opened it.
    //
    // Window focus is read in composition (it is snapshot state), so the
    // effect re-runs when the IME gives focus back and the restore still
    // happens then — without that, the one shot would be spent on the ignored
    // loss and focus would stay stranded.
    //
    // The two-frame grace matters: a hand-off between two of the panel's own
    // children can report "nothing focused" for a frame, and restoring on that
    // would yank focus on every ordinary D-pad move.
    var panelHasFocus by remember { mutableStateOf(false) }
    var focusRestores by remember { mutableStateOf(0) }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(focusRestores, windowFocused) {
        if (focusRestores == 0) return@LaunchedEffect
        if (!windowFocused) return@LaunchedEffect
        awaitFrame()
        awaitFrame()
        // Only when this panel is the one that should own focus: inline it
        // shares the screen with the header (Up must still reach it), and a
        // dialog opened from the panel owns focus until it closes.
        if (containFocus && !panelHasFocus) {
            runCatching { recoveryFocusRequester.requestFocus() }
        }
    }

    // When the panel goes away (SETUP toggled, playlist loaded) its fields
    // must not keep view focus: a still-focused (now invisible) field keeps
    // consuming D-pad events and the screen appears dead to navigation.
    DisposableEffect(Unit) {
        onDispose { panelFocusManager.clearFocus(force = true) }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .focusGroup()
            .onFocusChanged { state ->
                panelHasFocus = state.hasFocus
                if (!state.hasFocus && containFocus) focusRestores++
            }
            .then(
                if (containFocus) {
                    // The overlay covers the guide but does not own its focus, so
                    // a search past the panel's edge used to land on the group
                    // chips behind it. Cancelling the exit keeps the press
                    // inside — the panel leaves with Back, not with the D-pad.
                    Modifier.focusProperties { onExit = { cancelFocusChange() } }
                } else {
                    Modifier
                }
            )
            .background(KBSurface, RoundedCornerShape(18.dp))
            .padding(18.dp)
    ) {
        Text(
            text = "PLAYLIST SETUP",
            color = KBAccent,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )

        Text(
            text = if (playlistUrl.isBlank()) "Enter an M3U URL to load channels" else playlistUrl,
            color = KBTextLo,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        NativeUrlField(
            value = playlistUrl,
            label = "Playlist URL",
            onValueChange = onPlaylistUrlChanged,
            modifier = Modifier.fillMaxWidth(),
            focusRequester = firstFieldFocusRequester,
            keepFocusOnDone = true
        )

        Spacer(modifier = Modifier.height(12.dp))

        NativeUrlField(
            value = epgUrl,
            label = "EPG URL (optional)",
            onValueChange = onEpgUrlChanged,
            modifier = Modifier.fillMaxWidth(),
            keepFocusOnDone = true
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Secondary EPG sources, one per line, merged with the primary
        // guide so channels missing from one source can still match the
        // other. Matching keys off each source's own URL, exactly like the
        // extra-playlists merge below.
        Text(
            text = "Extra EPG URLs (optional) — one per line",
            color = KBTextLo,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 2.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            KBTextField(
                value = extraEpgUrls,
                onValueChange = onExtraEpgUrlsChanged,
                placeholder = "http://host/second-guide.xml",
                keepFocusOnDone = true,
                openKeyboardOnFocus = false,
                keyboardType = KeyboardType.Uri,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            KBPasteChip(onPaste = { onExtraEpgUrlsChanged(it) })
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Optional extra M3U sources, one per line: "<url>|<name>" to give
        // a source a display name. Merged into the lineup after the
        // primary playlist loads; a source that fails to load is skipped
        // without taking down the rest.
        Text(
            text = "Extra playlists (optional) — one URL per line",
            color = KBTextLo,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 2.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            KBTextField(
                value = extraPlaylistUrls,
                onValueChange = onExtraPlaylistUrlsChanged,
                placeholder = "http://host/other.m3u|Name",
                keepFocusOnDone = true,
                openKeyboardOnFocus = false,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            KBPasteChip(onPaste = { onExtraPlaylistUrlsChanged(it) })
        }

        Spacer(modifier = Modifier.height(12.dp))

        // TV-safe input, shared KB field: leanback IME allowed, D-pad
        // escape, Enter = Done. PASTE chip for the URL fields below.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            KBTextField(
                value = playlistName,
                onValueChange = onPlaylistNameChanged,
                placeholder = "Playlist name",
                keepFocusOnDone = true,
                openKeyboardOnFocus = false,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(8.dp))
            KBPasteChip(onPaste = { onPlaylistNameChanged(it) })
        }

        if (!error.isNullOrBlank()) {
            Text(
                text = error,
                color = KBDanger,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 12.dp)
            )
        }

        if (!guideError.isNullOrBlank()) {
            Text(
                text = guideError,
                color = KBRust,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        if (playlistUrl.isNotBlank()) {
            CompactSetupDiagnostics(
                diagnosticsText = buildSetupDiagnosticsText(
                    playlistUrl = playlistUrl,
                    epgUrl = epgUrl,
                    playlistName = playlistName,
                    playlist = playlist,
                    channelCount = channelCount,
                    isImportingGuide = isImportingGuide,
                    extraEpgUrls = extraEpgUrls
                )
            )
            Spacer(modifier = Modifier.height(14.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // Carries the recovery requester: this card is where focus lands
            // when the panel has lost it entirely (see the recovery block).
            KBCard(onClick = onLoad, modifier = Modifier.focusRequester(recoveryFocusRequester)) {
                Text(
                    text = if (isLoading) "LOADING..." else "LOAD",
                    color = KBTextHi,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp)
                )
            }

            KBCard(onClick = onReload) {
                Text(
                    text = "RELOAD",
                    color = KBTextHi,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp)
                )
            }

            if (epgUrl.isNotBlank() || extraEpgUrls.isNotBlank()) {
                KBCard(onClick = onImportGuide) {
                    Text(
                        text = if (isImportingGuide) "IMPORTING..." else "IMPORT EPG",
                        color = KBTextHi,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp)
                    )
                }
            }

            if (playlist != null) {
                KBCard(onClick = onManageHidden) {
                    Text(
                        text = "HIDDEN ITEMS",
                        color = KBTextHi,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp)
                    )
                }
            }

            KBCard(onClick = onClear) {
                Text(
                    text = "CLEAR",
                    color = KBTextHi,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp)
                )
            }
        }
    }
}

@Composable
private fun NativeUrlField(
    value: String,
    label: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    keepFocusOnDone: Boolean = false
) {
    // One shared KB field everywhere: same look, IME behavior, D-pad
    // escape, and Enter handling (see KBTextField).
    //
    // Manual IME mode (focus alone never opens the keyboard), like the
    // profile editor's long TV forms. This screen is auto-focused onto this
    // field, and an editable field that takes focus makes the leanback IME
    // open by itself — a full-screen window that answers Back by closing
    // itself and then reopening, because the field is still focused and
    // still editable. The form looked impossible to leave. Now focus lands
    // on it silently, OK starts editing, and Back/Done ends editing for
    // good (readOnly again), so the next Back reaches the screen.
    KBTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = label,
        modifier = modifier,
        focusRequester = focusRequester,
        keepFocusOnDone = keepFocusOnDone,
        openKeyboardOnFocus = false,
        keyboardType = KeyboardType.Uri
    )
}


@Composable
private fun GuideHeader(
    title: String,
    channelCount: Int,
    selectedGroup: String,
    onSetupClick: () -> Unit,
    onSearchClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineLarge,
                color = KBTextHi,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            val meta = buildList {
                add("$channelCount channels")
                if (selectedGroup.isNotBlank()) add(selectedGroup)
            }.joinToString("  •  ")

            Text(
                text = meta,
                style = MaterialTheme.typography.bodyMedium,
                color = KBTextLo,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (onSearchClick != null) {
                KBCard(onClick = onSearchClick) {
                    Text(
                        text = "SEARCH",
                        color = KBTextHi,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
                    )
                }
            }
            KBCard(onClick = onSetupClick) {
                Text(
                    text = "SETUP",
                    color = KBTextHi,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
                )
            }

            GuideHeaderClock()
        }
    }
}

@Composable
private fun GuideHeaderClock(modifier: Modifier = Modifier) {
    val time = rememberCurrentTimeLabel()

    Column(modifier = modifier, horizontalAlignment = Alignment.End) {
        Text(
            text = "LIVE GUIDE",
            style = MaterialTheme.typography.labelLarge,
            color = KBAccent,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = time,
            style = MaterialTheme.typography.titleSmall,
            color = KBTextHi,
            modifier = Modifier.padding(top = 3.dp)
        )
    }
}

@Composable
private fun CompactSetupDiagnostics(
    diagnosticsText: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = SurfaceDefaults.colors(
            containerColor = KBVoid.copy(alpha = 0.30f),
            contentColor = KBTextLo
        )
    ) {
        Text(
            text = diagnosticsText,
            color = KBTextLo,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)
        )
    }
}

private fun buildSetupDiagnosticsText(
    playlistUrl: String,
    epgUrl: String,
    playlistName: String,
    playlist: IptvPlaylist?,
    channelCount: Int,
    isImportingGuide: Boolean,
    extraEpgUrls: String = ""
): String {
    return buildList {
        add(if (playlistUrl.isBlank()) "Playlist missing" else "Playlist ready")
        add(if (epgUrl.isBlank()) "EPG optional" else "EPG provided")
        val extraEpgCount = extraEpgUrls.split('\n', ';').count { it.isNotBlank() }
        if (extraEpgCount > 0) add("Extra EPG x$extraEpgCount")
        if (playlist != null) add("Channels $channelCount")
        if (isImportingGuide) add("EPG importing")
        if (playlistName.isNotBlank()) add("Name: $playlistName")
    }.joinToString("  •  ")
}
@Composable
private fun GroupChip(
    name: String,
    selected: Boolean,
    onClick: () -> Unit,
    onFocus: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
){
    KBCard(
    onClick = onClick,
    onLongClick = onLongClick,
    modifier = modifier.onFocusChanged {
        if (it.isFocused) onFocus()
    }
) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(
                horizontal = if (selected) 12.dp else 13.dp,
                vertical = if (selected) 8.dp else 9.dp
            )
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) KBAccent else KBTextHi,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ChannelRowCard(
    item: IptvChannelWithEpg,
    selected: Boolean,
    /** Its guide row has not been queried yet, so "no programme" is unknown. */
    guidePending: Boolean = false,
    onClick: () -> Unit,
    onFocused: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.015f else 1f,
        animationSpec = tween(durationMillis = 140),
        label = "channelRowScale"
    )
    val alpha by animateFloatAsState(
        targetValue = if (isFocused || selected) 1f else 0.96f,
        animationSpec = tween(durationMillis = 140),
        label = "channelRowAlpha"
    )
    val accentAlpha by animateFloatAsState(
        targetValue = when {
            isFocused -> 1f
            selected -> 0.82f
            else -> 0f
        },
        animationSpec = tween(durationMillis = 140),
        label = "channelRowAccentAlpha"
    )
    val rowShape = RoundedCornerShape(12.dp)

    KBCard(
    onClick = onClick,
    onLongClick = onLongClick,
    // Horizontal inset keeps the focused border + glow from clipping
    // against the channel list's viewport edges (LazyColumn clips
    // children to its bounds; the glow paints outside the card bounds).
    modifier = modifier
        .padding(horizontal = 4.dp)
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            this.alpha = alpha
        }
        .onFocusChanged {
            isFocused = it.isFocused
            if (it.isFocused) onFocused()
        }
) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(rowShape)
                .background(
                    when {
                        isFocused -> KBSurfaceRaised.copy(alpha = 0.96f)
                        selected -> KBSurfaceRaised.copy(alpha = 0.84f)
                        else -> KBSurface
                    }
                )
                .border(
                    width = if (isFocused) 1.dp else 0.dp,
                    color = if (isFocused) KBAccent.copy(alpha = 0.32f) else Color.Transparent,
                    shape = rowShape
                )
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(34.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(KBAccent.copy(alpha = accentAlpha))
            )

            Spacer(modifier = Modifier.width(8.dp))

            ChannelLogo(item = item)

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.channel.displayName,
                        color = if (isFocused) KBAccent else KBTextHi,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = if (selected || isFocused) FontWeight.Medium else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    if (item.isFavorite) {
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = "Favorite channel",
                            tint = KBAccent,
                            modifier = Modifier
                                .padding(start = 6.dp)
                                .size(15.dp)
                        )
                    }
                }

                // Three states, not two: a programme, a channel whose guide
                // was queried and genuinely has nothing on, and a channel
                // whose guide row is still being queried. The last one used
                // to read "No program data" as well, which made every group
                // switch (and first entry) look like the guide had lost all
                // its data for the second the query took to land.
                val nowTitle = item.now?.title
                    ?: if (guidePending) "Loading guide..." else "No program data"
                Text(
                    text = nowTitle,
                    color = when {
                        guidePending && item.now == null -> KBTextLo.copy(alpha = 0.55f)
                        isFocused -> KBTextHi.copy(alpha = 0.82f)
                        else -> KBTextLo.copy(alpha = if (selected) 0.96f else 1f)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp)
                )
            }

            item.channel.tvgChno?.takeIf { it.isNotBlank() }?.let { chno ->
                Surface(
                    shape = RoundedCornerShape(5.dp),
                    colors = SurfaceDefaults.colors(
                        containerColor = if (isFocused) KBVoid.copy(alpha = 0.54f) else KBVoid.copy(alpha = 0.44f),
                        contentColor = KBTextLo.copy(alpha = if (isFocused) 0.9f else 0.78f)
                    ),
                    border = Border(
                        border = BorderStroke(
                            1.dp,
                            if (isFocused) KBAccent.copy(alpha = 0.18f) else KBTextLo.copy(alpha = 0.08f)
                        ),
                        shape = RoundedCornerShape(5.dp)
                    )
                ) {
                    Text(
                        text = chno,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelLogo(
    item: IptvChannelWithEpg,
    modifier: Modifier = Modifier
) {
    val logoUrl = item.channel.logoUrl ?: item.epgChannel?.iconUrl

    Box(
        modifier = modifier
            .width(48.dp)
            .height(48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(KBVoid.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center
    ) {
        if (!logoUrl.isNullOrBlank()) {
            AsyncImage(
                model = logoUrl,
                contentDescription = item.channel.displayName,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .padding(7.dp)
            )
        } else {
            Text(
                text = item.channel.displayName
                    .split(" ")
                    .mapNotNull { it.firstOrNull()?.uppercaseChar() }
                    .take(2)
                    .joinToString(""),
                color = KBTextLo,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun GuideDetailPanel(
    item: IptvChannelWithEpg,
    /** Its guide row has not been queried yet, so "no data" is unknown. */
    guidePending: Boolean = false,
    modifier: Modifier = Modifier
) {
    val nowMillis = rememberNowMillis()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(KBSurface, RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            ChannelLogo(item = item, modifier = Modifier.size(44.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.channel.displayName,
                        color = KBTextHi,
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (item.isFavorite) {
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = "Favorite channel",
                            tint = KBAccent,
                            modifier = Modifier.padding(start = 6.dp).size(16.dp)
                        )
                    }
                }
                val subline = item.channel.groupTitle?.trim().orEmpty()
                if (subline.isNotBlank()) {
                    Text(
                        text = subline,
                        color = KBTextLo,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        NowNextPanel(item = item, nowMillis = nowMillis, guidePending = guidePending)
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = "UPCOMING",
            color = KBTextLo,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        val upcoming = item.upcoming.take(4)
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (upcoming.isEmpty()) {
                CenterMessage(
                    title = when {
                        guidePending -> "Loading guide..."
                        item.epgMatchType == EpgMatchType.NO_MATCH -> "Guide not matched"
                        else -> "No guide data"
                    },
                    message = when {
                        guidePending -> "Program information for this channel is still loading."
                        item.epgMatchType == EpgMatchType.NO_MATCH ->
                            "This channel did not match the XMLTV guide. Check tvg-id, tvg-name, or channel name alignment."
                        else -> "Program information is not available for this channel."
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 24.dp)
                )
            } else {
                upcoming.forEach { program ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        colors = SurfaceDefaults.colors(
                            containerColor = KBSurfaceRaised.copy(alpha = 0.95f),
                            contentColor = KBTextHi
                        ),
                        border = Border(
                            border = BorderStroke(1.dp, KBTextLo.copy(alpha = 0.18f)),
                            shape = RoundedCornerShape(10.dp)
                        )
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
    text = formatTimeRange(program.startUtcMillis, program.endUtcMillis),
    color = KBAccent,
    style = MaterialTheme.typography.labelMedium,
    fontWeight = FontWeight.SemiBold,
    maxLines = 1,
    softWrap = false,
    overflow = TextOverflow.Clip,
    modifier = Modifier.width(132.dp)
)
                            
                            Text(
                                text = program.title,
                                color = KBTextHi,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )

                            Text(
                                text = formatStartsInLabel(program.startUtcMillis - nowMillis),
                                color = KBTextLo,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                modifier = Modifier.padding(start = 10.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NowNextPanel(
    item: IptvChannelWithEpg,
    nowMillis: Long,
    /** Its guide row has not been queried yet, so "nothing on" is unknown. */
    guidePending: Boolean = false,
    modifier: Modifier = Modifier
) {
    val nowProgram = item.now
    val nowProgress: Float? = nowProgram?.let { program ->
        val duration = (program.endUtcMillis - program.startUtcMillis).coerceAtLeast(1L)
        val fraction = (nowMillis - program.startUtcMillis).toFloat() / duration
        fraction.takeIf { it >= 0f && it <= 1f }
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ProgramCard(
            label = "NOW",
            title = item.now?.title
                ?: if (guidePending) "Loading guide..." else "Nothing airing right now",
            time = item.now?.let { formatTimeRange(it.startUtcMillis, it.endUtcMillis) },
            badge = nowProgram?.let { formatRemainingLabel(it.endUtcMillis - nowMillis) },
            progress = nowProgress,
            description = item.now?.description,
            modifier = Modifier.weight(1f)
        )

        ProgramCard(
            label = "NEXT",
            title = item.next?.title
                ?: if (guidePending) "Loading guide..." else "No next program listed",
            time = item.next?.let { formatTimeRange(it.startUtcMillis, it.endUtcMillis) },
            badge = item.next?.let { formatStartsInLabel(it.startUtcMillis - nowMillis) },
            description = item.next?.description,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ProgramCard(
    label: String,
    title: String,
    time: String?,
    badge: String? = null,
    progress: Float? = null,
    description: String?,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = SurfaceDefaults.colors(
            containerColor = KBSurfaceRaised,
            contentColor = KBTextHi
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 13.dp, vertical = 11.dp)
        ) {
            Text(
                text = label,
                color = KBAccent,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )

            Text(
                text = title,
                color = KBTextHi,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )

            if (!time.isNullOrBlank()) {
                Text(
                    text = if (badge.isNullOrBlank()) time else "$time  •  $badge",
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp)
                )
            }

            progress?.let { fraction ->
                Box(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(KBVoid.copy(alpha = 0.55f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(999.dp))
                            .background(KBAccent)
                    )
                }
            }

            description?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun CenterMessage(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    showSpinner: Boolean = false
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .background(KBSurface, RoundedCornerShape(16.dp))
                .padding(horizontal = 22.dp, vertical = 18.dp)
        ) {
            if (showSpinner) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = KBAccent,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.height(10.dp))
            }
            Text(
                text = title,
                color = KBTextHi,
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = message,
                color = KBTextLo,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun InlineErrorChip(
    message: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        colors = SurfaceDefaults.colors(
            containerColor = KBSurfaceRaised.copy(alpha = 0.96f),
            contentColor = KBTextHi
        ),
        border = Border(
            border = BorderStroke(1.dp, KBAccent.copy(alpha = 0.45f)),
            shape = RoundedCornerShape(999.dp)
        )
    ) {
        Text(
            text = message,
            color = KBTextHi,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun rememberNowMillis(): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // 30s cadence: guide progress / remaining labels don't need sub-minute
    // precision, and a slow tick keeps recomposition cost negligible.
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    return now
}

private fun formatRemainingLabel(msUntilEnd: Long): String? {
    if (msUntilEnd <= 0L) return null
    val totalMinutes = ((msUntilEnd + 59_999L) / 60_000L).toInt()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0 && minutes > 0 -> "$hours hr $minutes min left"
        hours > 0 -> "$hours hr left"
        else -> "$minutes min left"
    }
}

private fun formatStartsInLabel(msUntilStart: Long): String =
    when {
        msUntilStart <= 0L -> "starting"
        msUntilStart < 60_000L -> "in <1 min"
        msUntilStart < 3_600_000L -> "in ${msUntilStart / 60_000L} min"
        msUntilStart < 86_400_000L -> "in ${msUntilStart / 3_600_000L} hr"
        else -> "in ${msUntilStart / 86_400_000L} d"
    }

@Composable
private fun rememberCurrentTimeLabel(): String {
    var timeLabel by remember {
        mutableStateOf(clockLabelFormatter.format(Date()))
    }

    LaunchedEffect(Unit) {
        while (true) {
            timeLabel = clockLabelFormatter.format(Date())
            delay(30_000)
        }
    }

    return timeLabel
}

private fun formatTimeRange(startMillis: Long, endMillis: Long): String {
    return "${programTimeFormatter.format(Date(startMillis))} - ${programTimeFormatter.format(Date(endMillis))}"
}


@Composable
private fun ChannelActionsDialog(
    item: IptvChannelWithEpg,
    hasCatchup: Boolean,
    onDismiss: () -> Unit,
    onToggleFavorite: () -> Unit,
    onHideChannel: () -> Unit,
    onHideGroup: () -> Unit,
    onOpenCatchup: () -> Unit,
    onToggleReminder: (() -> Unit)? = null,
    reminderActive: Boolean = false
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(430.dp)
                .background(KBSurfaceRaised, RoundedCornerShape(18.dp))
                .border(1.dp, KBAccent.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(item.channel.displayName, color = KBTextHi, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Channel options", color = KBTextLo, style = MaterialTheme.typography.bodyMedium)
            if (hasCatchup) {
                KBCard(onClick = onOpenCatchup, modifier = Modifier.fillMaxWidth()) {
                    Text("CATCH-UP TV", color = KBTextHi, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                }
            }
            onToggleReminder?.let { toggle ->
                KBCard(onClick = toggle, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (reminderActive) "REMOVE REMINDER" else "REMIND ME: ${item.next?.title ?: "next program"}",
                        color = KBTextHi,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            }
            KBCard(onClick = onToggleFavorite, modifier = Modifier.fillMaxWidth()) {
                Text(if (item.isFavorite) "REMOVE FROM FAVORITES" else "ADD TO FAVORITES", color = KBTextHi, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            }
            KBCard(onClick = onHideChannel, modifier = Modifier.fillMaxWidth()) {
                Text("HIDE CHANNEL", color = KBTextHi, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            }
            item.channel.groupTitle?.trim()?.takeIf { it.isNotBlank() }?.let { group ->
                KBCard(onClick = onHideGroup, modifier = Modifier.fillMaxWidth()) {
                    Text("HIDE GROUP: $group", color = KBTextHi, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                }
            }
            KBCard(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("CANCEL", color = KBTextLo, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            }
        }
    }
}

/**
 * Catch-up (DVR) program list for one channel: fully-aired programmes the
 * provider's DVR template can still serve, newest first. Empty state
 * explains why the list can be empty (provider advertises catch-up but has
 * no guide history in the window).
 */
@Composable
private fun CatchupDialog(
    channelName: String,
    programs: List<CatchupProgram>,
    onDismiss: () -> Unit,
    onPlay: (CatchupProgram) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(560.dp)
                .background(KBSurfaceRaised, RoundedCornerShape(18.dp))
                .border(1.dp, KBAccent.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "CATCH-UP TV",
                color = KBAccent,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = channelName,
                color = KBTextHi,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (programs.isEmpty()) {
                Text(
                    text = "No recent programs available. The channel advertises catch-up, " +
                        "but the guide has no aired program history for it yet \u2014 try again " +
                        "after the EPG imports.",
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            } else {
                Column(
                    modifier = Modifier
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    programs.forEach { program ->
                        KBCard(onClick = { onPlay(program) }, modifier = Modifier.fillMaxWidth()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 10.dp)
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = program.title,
                                        color = KBTextHi,
                                        style = MaterialTheme.typography.titleMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    program.description?.takeIf { it.isNotBlank() }?.let { desc ->
                                        Text(
                                            text = desc,
                                            color = KBTextLo,
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(top = 2.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = formatCatchupWindow(program.startUtcMillis, program.endUtcMillis),
                                    color = KBAccent,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    }
                }
            }

            KBCard(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("CLOSE", color = KBTextLo, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            }
        }
    }
}

private fun formatCatchupWindow(startUtcMillis: Long, endUtcMillis: Long): String {
    fun fmt(millis: Long): String {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = millis
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
        val h12 = if (hour % 12 == 0) 12 else hour % 12
        return String.format(
            java.util.Locale.US,
            "%d:%02d%s",
            h12,
            cal.get(java.util.Calendar.MINUTE),
            if (hour >= 12) "pm" else "am"
        )
    }
    val now = java.util.Calendar.getInstance()
    val dayStart = now.clone() as java.util.Calendar
    dayStart.set(java.util.Calendar.HOUR_OF_DAY, 0)
    dayStart.set(java.util.Calendar.MINUTE, 0)
    dayStart.set(java.util.Calendar.SECOND, 0)
    dayStart.set(java.util.Calendar.MILLISECOND, 0)
    val dayLabel: String = when {
        startUtcMillis < dayStart.timeInMillis -> "Yesterday"
        startUtcMillis < dayStart.timeInMillis + 86_400_000L -> "Today"
        else -> java.text.SimpleDateFormat("EEE", java.util.Locale.US).format(java.util.Date(startUtcMillis))
    }
    return "$dayLabel \u00b7 ${fmt(startUtcMillis)}\u2013${fmt(endUtcMillis)}"
}

/** One EPG title match bound to the visible channel it airs on. */
private data class GuideProgramHit(
    val item: IptvChannelWithEpg,
    val program: EpgProgramRow
)

@Composable
private fun ChannelSearchDialog(
    query: String,
    results: List<IptvChannelWithEpg>,
    /** Guide rows resolved so far, so a hit can show its now/next programme. */
    guideItems: Map<String, IptvChannelWithEpg> = emptyMap(),
    programHits: List<GuideProgramHit> = emptyList(),
    channelKey: (IptvChannelWithEpg) -> String,
    onQueryChanged: (String) -> Unit,
    onPlay: (IptvChannelWithEpg) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(560.dp)
                .background(KBSurfaceRaised, RoundedCornerShape(18.dp))
                .border(1.dp, KBAccent.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "SEARCH GUIDE",
                color = KBAccent,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            KBTextField(
                value = query,
                onValueChange = onQueryChanged,
                placeholder = "Channel, number or program…",
                modifier = Modifier.fillMaxWidth(),
                onDone = onDismiss
            )
            if (results.isEmpty() && programHits.isEmpty()) {
                Text(
                    text = if (query.isBlank()) {
                        "Type to search channels and programs"
                    } else {
                        "No channels or programs match"
                    },
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(
                        items = results,
                        key = { _, item -> channelKey(item) }
                    ) { _, item ->
                        val logoUrl = item.channel.logoUrl ?: item.epgChannel?.iconUrl
                        KBCard(onClick = { onPlay(item) }, modifier = Modifier.fillMaxWidth()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                if (!logoUrl.isNullOrBlank()) {
                                    AsyncImage(
                                        model = logoUrl,
                                        contentDescription = item.channel.displayName,
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier.size(width = 44.dp, height = 30.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.channel.displayName,
                                        color = KBTextHi,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val resolved = guideItems[channelKey(item)]
                                    val onNow = resolved?.now
                                    val onNext = resolved?.next
                                    if (onNow != null) {
                                        // What is on, and what follows: the two
                                        // things a viewer decides a channel on.
                                        Text(
                                            text = formatTimeRange(
                                                onNow.startUtcMillis,
                                                onNow.endUtcMillis
                                            ) + "  " + onNow.title,
                                            color = KBTextHi,
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        onNext?.let { following ->
                                            Text(
                                                text = "Next  " +
                                                    formatTimeRange(
                                                        following.startUtcMillis,
                                                        following.endUtcMillis
                                                    ) + "  " + following.title,
                                                color = KBTextLo,
                                                style = MaterialTheme.typography.labelSmall,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    } else {
                                        item.channel.groupTitle?.trim()
                                            ?.takeIf { it.isNotBlank() }
                                            ?.let { group ->
                                                Text(
                                                    text = group,
                                                    color = KBTextLo,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                    }
                                }
                                item.channel.tvgChno?.trim()
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let { chno ->
                                        Text(
                                            text = chno,
                                            color = KBTextLo,
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                    }
                            }
                        }
                    }
                }
            }
            if (programHits.isNotEmpty()) {
                Text(
                    text = "PROGRAMS",
                    color = KBTextLo,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 4.dp)
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    itemsIndexed(
                        items = programHits,
                        key = { _, hit ->
                            "program|${hit.item.channel.id}|${hit.program.startUtcMillis}"
                        }
                    ) { _, hit ->
                        KBCard(
                            onClick = { onPlay(hit.item) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = hit.program.title,
                                    color = KBTextHi,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = hit.item.channel.displayName + " · " +
                                        formatTimeRange(
                                            hit.program.startUtcMillis,
                                            hit.program.endUtcMillis
                                        ),
                                    color = KBTextLo,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
            KBCard(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "CLOSE",
                    color = KBTextLo,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
        }
    }
}

private enum class HiddenItemsTab { GROUPS, CHANNELS }

@Composable
private fun HiddenItemsDialog(
    playlist: IptvPlaylist?,
    hiddenChannels: Set<String>,
    hiddenGroups: Set<String>,
    channelKey: (IptvChannelWithEpg) -> String,
    onDismiss: () -> Unit,
    onHideGroup: (String) -> Unit,
    onUnhideGroup: (String) -> Unit,
    onHideAllGroups: (Set<String>) -> Unit,
    onUnhideChannel: (String) -> Unit,
    onUnhideAll: () -> Unit
) {
    val allGroups = remember(playlist) {
        playlist?.channels
            ?.asSequence()
            ?.mapNotNull { it.groupTitle?.trim()?.takeIf(String::isNotBlank) }
            ?.distinct()
            ?.sorted()
            ?.toList()
            .orEmpty()
    }
    val hiddenChannelItems = remember(playlist, hiddenChannels) {
        playlist?.channels
            ?.map { channel ->
                IptvChannelWithEpg(
                    channel = channel,
                    epgChannel = null,
                    epgMatchType = EpgMatchType.NO_MATCH,
                    now = null,
                    next = null,
                    upcoming = emptyList()
                )
            }
            ?.filter { channelKey(it) in hiddenChannels }
            .orEmpty()
    }
    var selectedTab by remember { mutableStateOf(HiddenItemsTab.GROUPS) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .width(720.dp)
                .height(650.dp)
                .background(KBSurface, RoundedCornerShape(18.dp))
                .border(1.dp, KBAccent.copy(alpha = 0.45f), RoundedCornerShape(18.dp))
                .padding(20.dp)
        ) {
            Text(
                text = "HIDDEN ITEMS",
                color = KBAccent,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Choose which groups and channels appear in the guide.",
                color = KBTextLo,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                HiddenItemsTabButton(
                    label = "GROUPS (${allGroups.size})",
                    selected = selectedTab == HiddenItemsTab.GROUPS,
                    onClick = { selectedTab = HiddenItemsTab.GROUPS }
                )
                HiddenItemsTabButton(
                    label = "CHANNELS (${hiddenChannelItems.size})",
                    selected = selectedTab == HiddenItemsTab.CHANNELS,
                    onClick = { selectedTab = HiddenItemsTab.CHANNELS }
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            when (selectedTab) {
                HiddenItemsTab.GROUPS -> HiddenGroupsTab(
                    allGroups = allGroups,
                    hiddenGroups = hiddenGroups,
                    onHideGroup = onHideGroup,
                    onUnhideGroup = onUnhideGroup,
                    onHideAllGroups = onHideAllGroups
                )
                HiddenItemsTab.CHANNELS -> HiddenChannelsTab(
                    hiddenChannelItems = hiddenChannelItems,
                    channelKey = channelKey,
                    onUnhideChannel = onUnhideChannel
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                KBCard(onClick = onDismiss) {
                    Text(
                        text = "DONE",
                        color = KBTextHi,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 11.dp)
                    )
                }
                if (hiddenChannels.isNotEmpty() || hiddenGroups.isNotEmpty()) {
                    KBCard(onClick = onUnhideAll) {
                        Text(
                            text = "SHOW ALL",
                            color = KBTextHi,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 11.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HiddenItemsTabButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    KBCard(onClick = onClick) {
        Text(
            text = label,
            color = if (selected) KBAccent else KBTextHi,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun HiddenGroupsTab(
    allGroups: List<String>,
    hiddenGroups: Set<String>,
    onHideGroup: (String) -> Unit,
    onUnhideGroup: (String) -> Unit,
    onHideAllGroups: (Set<String>) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KBCard(onClick = { onHideAllGroups(allGroups.toSet()) }) {
                Text(
                    text = "HIDE ALL GROUPS",
                    color = KBTextHi,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
                )
            }
            if (hiddenGroups.isNotEmpty()) {
                KBCard(onClick = { hiddenGroups.forEach(onUnhideGroup) }) {
                    Text(
                        text = "SHOW ALL GROUPS",
                        color = KBTextHi,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (allGroups.isEmpty()) {
            CenterMessage(
                title = "No groups found",
                message = "Load a playlist to manage its groups.",
                modifier = Modifier.weight(1f).fillMaxWidth()
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 10.dp),
                modifier = Modifier.weight(1f).fillMaxWidth().focusGroup()
            ) {
                itemsIndexed(allGroups, key = { _, group -> "group-$group" }) { _, group ->
                    val isHidden = group in hiddenGroups
                    HiddenManagerRow(
                        title = group,
                        subtitle = if (isHidden) "Hidden from guide" else "Shown in guide",
                        actionLabel = if (isHidden) "SHOW" else "HIDE",
                        onAction = {
                            if (isHidden) onUnhideGroup(group) else onHideGroup(group)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun HiddenChannelsTab(
    hiddenChannelItems: List<IptvChannelWithEpg>,
    channelKey: (IptvChannelWithEpg) -> String,
    onUnhideChannel: (String) -> Unit
) {
    if (hiddenChannelItems.isEmpty()) {
        CenterMessage(
            title = "No hidden channels",
            message = "Individually hidden channels will appear here.",
            modifier = Modifier.fillMaxSize()
        )
    } else {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 10.dp),
            modifier = Modifier.fillMaxSize().focusGroup()
        ) {
            itemsIndexed(
    hiddenChannelItems,
    key = { index, item -> "channel-${channelKey(item)}#$index" }
) { _, item ->
                HiddenManagerRow(
                    title = item.channel.displayName,
                    subtitle = item.channel.groupTitle?.trim()?.takeIf { it.isNotBlank() } ?: "Channel",
                    actionLabel = "UNHIDE",
                    onAction = { onUnhideChannel(channelKey(item)) }
                )
            }
        }
    }
}

@Composable
private fun HiddenManagerRow(
    title: String,
    subtitle: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(KBSurfaceRaised, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = KBTextHi,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                color = KBTextLo,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        KBCard(onClick = onAction) {
            Text(
                text = actionLabel,
                color = KBTextHi,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
            )
        }
    }
}
