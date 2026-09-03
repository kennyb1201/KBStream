package com.kennyb1201.kbstream.ui.iptv

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color as AndroidColor
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.widget.EditText
import androidx.compose.material3.Text as Material3Text
import androidx.tv.material3.Border
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.kennyb1201.kbstream.data.iptv.EpgMatchType
import com.kennyb1201.kbstream.data.iptv.IptvChannelWithEpg
import com.kennyb1201.kbstream.data.iptv.IptvPlaylist
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.components.SuppressImeWhileFocused
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
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
    defaultPlaylistUrl: String = "",
    defaultEpgUrl: String = "",
    defaultPlaylistName: String = "",
    onPlayChannel: ((IptvChannelWithEpg) -> Unit)? = null
) {
    val playlist by viewModel.playlist.collectAsState()
    val visibleChannels by viewModel.visibleChannels.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val isImportingGuide by viewModel.isImportingGuide.collectAsState()
    val error by viewModel.error.collectAsState()
    val guideError by viewModel.guideError.collectAsState()
    val playlistUrl by viewModel.playlistUrl.collectAsState()
    val epgUrl by viewModel.epgUrl.collectAsState()
    val playlistName by viewModel.playlistName.collectAsState()

    val channelListState = rememberLazyListState()
    val firstChannelFocusRequester = remember { FocusRequester() }
    val allTabFocusRequester = remember { FocusRequester() }
    val groupChipFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    val latestOnPlayChannel by rememberUpdatedState(onPlayChannel)
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val guidePreferences = remember(appContext) {
        appContext.getSharedPreferences("iptv_guide_preferences", Context.MODE_PRIVATE)
    }
    var favorites by remember {
        mutableStateOf(guidePreferences.getStringSet("favorites", emptySet())?.toSet().orEmpty())
    }
    
    var hiddenGroups by remember {
        mutableStateOf(guidePreferences.getStringSet("hidden_groups", emptySet())?.toSet().orEmpty())
    }
    var menuItem by remember { mutableStateOf<IptvChannelWithEpg?>(null) }
    
    var moveFocusToChannelList by remember { mutableStateOf(false) }

    fun channelKey(item: IptvChannelWithEpg): String =
        item.channel.id.ifBlank { item.channel.streamUrl }
    fun favoriteKey(item: IptvChannelWithEpg): String = channelKey(item)
    fun saveSet(key: String, values: Set<String>) {
        guidePreferences.edit().putStringSet(key, values).apply()
    }
    fun withFavoriteFlag(item: IptvChannelWithEpg): IptvChannelWithEpg =
        item.copy(isFavorite = favoriteKey(item) in favorites)

    val unhiddenChannels = remember(visibleChannels, hiddenGroups) {
    visibleChannels.filter { item ->
        val group = item.channel.groupTitle?.trim().orEmpty()
        group !in hiddenGroups
    }
}
    val hiddenChannelIds by viewModel.hiddenChannelIds.collectAsState()
    val groups = remember(unhiddenChannels, favorites) {
        buildList {
            add("All")
            if (unhiddenChannels.any { favoriteKey(it) in favorites }) add("Favorites")
            val seenGroups = LinkedHashSet<String>()
            unhiddenChannels.forEach { item ->
                item.channel.groupTitle?.trim()?.takeIf { it.isNotBlank() }?.let(seenGroups::add)
            }
            addAll(seenGroups)
        }
    }
    var selectedGroup by remember { mutableStateOf("All") }
    val groupedChannels = remember(unhiddenChannels, selectedGroup, favorites) {
        when (selectedGroup) {
            "All" -> unhiddenChannels
            "Favorites" -> unhiddenChannels.filter { favoriteKey(it) in favorites }
            else -> unhiddenChannels.filter { it.channel.groupTitle?.trim() == selectedGroup }
        }
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

    var showSetup by remember { mutableStateOf(playlist == null) }
    var showHiddenManager by remember { mutableStateOf(false) }

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
  LaunchedEffect(groupedChannelMembership) {
    selectedChannelId = groupedChannels.firstOrNull()?.channel?.id
    channelListState.scrollToItem(0)
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

  // Nothing has real D-pad focus on first entry (the "All" tab is only
  // *visually* selected via selectedGroup's default value), so the very
  // first key press falls through to the platform's default focus
  // resolution instead of landing on the tabs row -- claim it explicitly
  // once so Down from the tabs row is deterministic from the start.
  LaunchedEffect(Unit) {
    awaitFrame()
    runCatching { allTabFocusRequester.requestFocus() }
}

    // Both the inline (no-playlist) and overlay (showSetup) placements of the
    // setup form share identical wiring - only the surrounding modifier differs.
    val renderSetupPanel: @Composable (Modifier) -> Unit = { panelModifier ->
        SetupPanel(
            playlistUrl = playlistUrl,
            epgUrl = epgUrl,
            playlistName = playlistName,
            isLoading = isLoading,
            isImportingGuide = isImportingGuide,
            error = error,
            guideError = guideError,
            playlist = playlist,
            channelCount = visibleChannels.size,
            onPlaylistUrlChanged = viewModel::onPlaylistUrlChanged,
            onEpgUrlChanged = viewModel::onEpgUrlChanged,
            onPlaylistNameChanged = viewModel::onPlaylistNameChanged,
            onLoad = { viewModel.load() },
            onReload = { viewModel.load() },
            onImportGuide = { viewModel.importGuide() },
            onManageHidden = { showHiddenManager = true },
            onClear = {
                viewModel.onPlaylistUrlChanged("")
                viewModel.onEpgUrlChanged("")
                viewModel.onPlaylistNameChanged("")
                showSetup = true
            },
            modifier = panelModifier
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(KBVoid)
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
                        onSetupClick = { showSetup = !showSetup }
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    if (playlist == null) {
                        renderSetupPanel(Modifier)
                    } else {
                        if (groups.isNotEmpty()) {
                            LazyRow(
    contentPadding = PaddingValues(end = 8.dp),
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
                                    contentPadding = PaddingValues(bottom = 20.dp),
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

                                        ChannelRowCard(
                                            item = item,
                                            selected = selectedChannelIndex == index,
                                            onClick = {
                                                selectedChannelId = item.channel.id
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
    // requestFocus() fired.
    if (index == 0) {
        Modifier.focusRequester(firstChannelFocusRequester)
    } else {
        Modifier
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
            // The platform's default spatial search has no notion of
            // "your" group chip, so it was landing wherever was nearest
            // on screen; anchor it to the chip for selectedGroup instead.
            if (index == 0) {
                val target = groupChipFocusRequesters[selectedGroup]
                if (target != null) {
                    runCatching { target.requestFocus() }
                    true
                } else {
                    false
                }
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
                                    if (detailItem != null) {
                                        key(
                                            detailItem.channel.id,
                                            detailItem.now?.startUtcMillis,
                                            detailItem.now?.endUtcMillis,
                                            detailItem.next?.startUtcMillis,
                                            detailItem.next?.endUtcMillis,
                                            detailItem.upcoming.firstOrNull()?.startUtcMillis
                                        ) {
                                            GuideDetailPanel(item = detailItem)
                                        }
                                    } else {
                                        CenterMessage(
                                            title = "No channel selected",
                                            message = "Choose a channel from the list to view program details.",
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (showSetup && playlist != null) {
                    renderSetupPanel(
                        Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 104.dp, end = 24.dp)
                            .width(500.dp)
                    )
                }

                menuItem?.let { rawItem ->
                    val item = withFavoriteFlag(rawItem)
                    ChannelActionsDialog(
                        item = item,
                        onDismiss = { menuItem = null },
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
private fun SetupPanel(
    playlistUrl: String,
    epgUrl: String,
    playlistName: String,
    isLoading: Boolean,
    isImportingGuide: Boolean,
    error: String?,
    guideError: String?,
    playlist: IptvPlaylist?,
    channelCount: Int,
    onPlaylistUrlChanged: (String) -> Unit,
    onEpgUrlChanged: (String) -> Unit,
    onPlaylistNameChanged: (String) -> Unit,
    onLoad: () -> Unit,
    onReload: () -> Unit,
    onImportGuide: () -> Unit,
    onManageHidden: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
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
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        NativeUrlField(
            value = epgUrl,
            label = "EPG URL (optional)",
            onValueChange = onEpgUrlChanged,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        var playlistNameFocused by remember { mutableStateOf(false) }
        val focusManager = LocalFocusManager.current
        val keyboardController = LocalSoftwareKeyboardController.current
        // Same TV-safe input as everywhere else: no leanback IME over the
        // screen; atvTools "Send text" types straight into the focused box.
        SuppressImeWhileFocused(playlistNameFocused)

        OutlinedTextField(
            value = playlistName,
            onValueChange = onPlaylistNameChanged,
            label = { Material3Text("Playlist name") },
            singleLine = true,
            colors = setupTextFieldColors(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    keyboardController?.hide()
                    focusManager.clearFocus()
                }
            ),
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { playlistNameFocused = it.isFocused }
        )

        if (!error.isNullOrBlank()) {
            Text(
                text = error,
                color = Color(0xFFFF8A80),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 12.dp)
            )
        }

        if (!guideError.isNullOrBlank()) {
            Text(
                text = guideError,
                color = Color(0xFFFFB74D),
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
                    isImportingGuide = isImportingGuide
                )
            )
            Spacer(modifier = Modifier.height(14.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KBCard(onClick = onLoad) {
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

            if (epgUrl.isNotBlank()) {
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
    modifier: Modifier = Modifier
) {
    AndroidView(
        modifier = modifier.height(54.dp),
        factory = { context ->
            EditText(context).apply {
                hint = label
                setSingleLine(true)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                setTextColor(AndroidColor.WHITE)
                setHintTextColor(AndroidColor.LTGRAY)
                textSize = 16f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(18, 0, 18, 0)
                setShowSoftInputOnFocus(false)
                backgroundTintList = ColorStateList.valueOf(AndroidColor.rgb(120, 120, 120))

                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        onValueChange(s?.toString().orEmpty())
                    }
                    override fun afterTextChanged(s: Editable?) = Unit
                })
            }
        },
        update = { editText ->
            if (editText.text.toString() != value) {
                editText.setText(value)
                editText.setSelection(editText.text.length)
            }
        }
    )
}

@Composable
private fun setupTextFieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = KBSurfaceRaised,
    unfocusedContainerColor = KBSurfaceRaised,
    disabledContainerColor = KBSurfaceRaised,
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    cursorColor = KBAccent,
    focusedIndicatorColor = KBAccent,
    unfocusedIndicatorColor = KBTextLo.copy(alpha = 0.4f),
    focusedLabelColor = KBAccent,
    unfocusedLabelColor = KBTextLo
)

@Composable
private fun GuideHeader(
    title: String,
    channelCount: Int,
    selectedGroup: String,
    onSetupClick: () -> Unit,
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
    isImportingGuide: Boolean
): String {
    return buildList {
        add(if (playlistUrl.isBlank()) "Playlist missing" else "Playlist ready")
        add(if (epgUrl.isBlank()) "EPG optional" else "EPG provided")
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
    modifier: Modifier = Modifier
){
    KBCard(
    onClick = onClick,
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
    modifier = modifier
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

                val nowTitle = item.now?.title ?: "No program data"
                Text(
                    text = nowTitle,
                    color = if (isFocused) KBTextHi.copy(alpha = 0.82f) else KBTextLo.copy(alpha = if (selected) 0.96f else 1f),
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
    modifier: Modifier = Modifier
) {
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
        NowNextPanel(item = item)
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
                    title = if (item.epgMatchType == EpgMatchType.NO_MATCH) "Guide not matched" else "No guide data",
                    message = if (item.epgMatchType == EpgMatchType.NO_MATCH) {
                        "This channel did not match the XMLTV guide. Check tvg-id, tvg-name, or channel name alignment."
                    } else {
                        "Program information is not available for this channel."
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
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        ProgramCard(
            label = "NOW",
            title = item.now?.title ?: "Nothing airing right now",
            time = item.now?.let { formatTimeRange(it.startUtcMillis, it.endUtcMillis) },
            description = item.now?.description,
            modifier = Modifier.weight(1f)
        )

        ProgramCard(
            label = "NEXT",
            title = item.next?.title ?: "No next program listed",
            time = item.next?.let { formatTimeRange(it.startUtcMillis, it.endUtcMillis) },
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
                    text = time,
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 3.dp)
                )
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
    onDismiss: () -> Unit,
    onToggleFavorite: () -> Unit,
    onHideChannel: () -> Unit,
    onHideGroup: () -> Unit
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
