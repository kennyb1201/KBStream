package com.kennyb1201.kbstream.ui.iptv

import android.content.res.ColorStateList
import android.graphics.Color as AndroidColor
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.widget.EditText
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.kennyb1201.kbstream.data.iptv.EpgMatchType
import com.kennyb1201.kbstream.data.iptv.IptvChannelWithEpg
import com.kennyb1201.kbstream.data.iptv.IptvPlaylist
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
import kotlinx.coroutines.delay

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
    val latestOnPlayChannel by rememberUpdatedState(onPlayChannel)

    val groups = remember(visibleChannels) {
        buildList {
            add("All")
            addAll(
                visibleChannels.mapNotNull { it.channel.groupTitle?.trim()?.takeIf(String::isNotBlank) }
                    .distinct()
                    .sorted()
            )
        }
    }
    var selectedGroup by remember { mutableStateOf("All") }
    val groupedChannels = remember(visibleChannels, selectedGroup) {
        if (selectedGroup == "All") visibleChannels
        else visibleChannels.filter { it.channel.groupTitle?.trim() == selectedGroup }
    }
    var selectedChannelIndex by remember(groupedChannels) { mutableStateOf(if (groupedChannels.isNotEmpty()) 0 else -1) }
    val selectedChannel = groupedChannels.getOrNull(selectedChannelIndex)

    var favorites by remember { mutableStateOf(setOf<String>()) }
    fun favoriteKey(item: IptvChannelWithEpg) = item.channel.favoriteKey
    fun withFavoriteFlag(item: IptvChannelWithEpg): IptvChannelWithEpg {
        return item.copy(isFavorite = favoriteKey(item) in favorites)
    }

    var showSetup by remember { mutableStateOf(playlist == null) }

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

    LaunchedEffect(selectedGroup, groupedChannels.size) {
        if (groupedChannels.isEmpty()) {
            selectedChannelIndex = -1
        } else if (selectedChannelIndex !in groupedChannels.indices) {
            selectedChannelIndex = 0
        }
    }

    LaunchedEffect(selectedChannelIndex, groupedChannels.size) {
        if (groupedChannels.isNotEmpty() && selectedChannelIndex in groupedChannels.indices) {
            channelListState.scrollToItem(selectedChannelIndex)
        }
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
                    modifier = Modifier.fillMaxSize()
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
                            onClear = {
                                viewModel.onPlaylistUrlChanged("")
                                viewModel.onEpgUrlChanged("")
                                viewModel.onPlaylistNameChanged("")
                                showSetup = true
                            }
                        )


                    } else {
                        if (groups.isNotEmpty()) {
                            LazyRow(
                                contentPadding = PaddingValues(end = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.focusGroup()
                            ) {
                                itemsIndexed(groups, key = { _, item -> item }) { _, group ->
                                    GroupChip(
                                        name = group,
                                        selected = group == selectedGroup,
                                        onClick = { selectedGroup = group }
                                    )
                                }
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
                            ) {
                                itemsIndexed(
                                    items = groupedChannels,
                                    key = { index, item -> "${index}:${item.channel.id}" }
                                ) { index, rawItem ->
                                    val item = withFavoriteFlag(rawItem)
                                    ChannelRowCard(
                                        item = item,
                                        selected = selectedChannel?.channel?.id == item.channel.id,
                                        onClick = {
                                            selectedChannelIndex = index
                                            latestOnPlayChannel?.invoke(item)
                                        },
                                        onFocused = {
                                            if (selectedChannel?.channel?.id != item.channel.id) {
                                                selectedChannelIndex = index
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
                                    GuideDetailPanel(
                                        item = detailItem,
                                        onPlay = { latestOnPlayChannel?.invoke(detailItem) },
                                        onToggleFavorite = {
                                            val key = favoriteKey(detailItem)
                                            favorites = if (key in favorites) favorites - key else favorites + key
                                        }
                                    )
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

                if (showSetup && playlist != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 104.dp, end = 24.dp)
                            .width(500.dp)
                    ) {
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
                            onClear = {
                                viewModel.onPlaylistUrlChanged("")
                                viewModel.onEpgUrlChanged("")
                                viewModel.onPlaylistNameChanged("")
                                showSetup = true
                            }
                        )
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

        OutlinedTextField(
            value = playlistName,
            onValueChange = onPlaylistNameChanged,
            label = { androidx.compose.material3.Text("Playlist name") },
            singleLine = true,
            colors = setupTextFieldColors(),
            modifier = Modifier.fillMaxWidth()
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
    val time = rememberCurrentTimeLabel()

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

            Column(horizontalAlignment = Alignment.End) {
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
    }
}

@Composable
private fun CompactSetupDiagnostics(
    diagnosticsText: String,
    modifier: Modifier = Modifier
) {
    androidx.tv.material3.Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = androidx.tv.material3.SurfaceDefaults.colors(
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
    modifier: Modifier = Modifier
) {
    KBCard(
        onClick = onClick,
        modifier = modifier
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
                androidx.tv.material3.Surface(
                    shape = RoundedCornerShape(5.dp),
                    colors = androidx.tv.material3.SurfaceDefaults.colors(
                        containerColor = if (isFocused) KBVoid.copy(alpha = 0.54f) else KBVoid.copy(alpha = 0.44f),
                        contentColor = KBTextLo.copy(alpha = if (isFocused) 0.9f else 0.78f)
                    ),
                    border = androidx.tv.material3.Border(
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
    onPlay: () -> Unit,
    onToggleFavorite: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(KBSurface, RoundedCornerShape(18.dp))
            .padding(16.dp)
    ) {
        Row(
            verticalAlignment = Alignment.Top
        ) {
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
                            modifier = Modifier
                                .padding(start = 6.dp)
                                .size(16.dp)
                        )
                    }
                }

                val subline = buildList {
                    item.channel.groupTitle?.takeIf { it.isNotBlank() }?.let { add(it) }
                    item.channel.tvgId?.takeIf { it.isNotBlank() }?.let { add(it) }
                    when (item.epgMatchType) {
                        EpgMatchType.ID_MATCH -> add("EPG by ID")
                        EpgMatchType.NAME_MATCH -> add("EPG by Name")
                        EpgMatchType.NO_MATCH -> Unit
                    }
                }.joinToString("  •  ")

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

            Spacer(modifier = Modifier.width(10.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KBCard(onClick = onToggleFavorite) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = if (item.isFavorite) "Favorite channel" else "Add favorite",
                            tint = if (item.isFavorite) KBAccent else KBTextHi,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }

                KBCard(onClick = onPlay) {
                    Text(
                        text = "PLAY",
                        color = KBTextHi,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
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

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            val upcoming = item.upcoming.take(12)

            if (upcoming.isEmpty()) {
                item {
                    CenterMessage(
                        title = if (item.epgMatchType == EpgMatchType.NO_MATCH) "Guide not matched" else "No guide data",
                        message = if (item.epgMatchType == EpgMatchType.NO_MATCH) {
                            "This channel did not match the XMLTV guide. Check tvg-id, tvg-name, or channel name alignment."
                        } else {
                            "Program information is not available for this channel."
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 24.dp)
                    )
                }
            } else {
                itemsIndexed(upcoming, key = { index, program ->
                    "${program.channelId}:${program.startUtcMillis}:$index"
                }) { _, program ->
                    androidx.tv.material3.Surface(
                        shape = RoundedCornerShape(12.dp),
                        colors = androidx.tv.material3.SurfaceDefaults.colors(
                            containerColor = KBSurfaceRaised.copy(alpha = 0.95f),
                            contentColor = KBTextHi
                        ),
                        border = androidx.tv.material3.Border(
                            border = BorderStroke(1.dp, KBTextLo.copy(alpha = 0.18f)),
                            shape = RoundedCornerShape(12.dp)
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 13.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = formatTimeRange(program.startUtcMillis, program.endUtcMillis),
                                color = KBAccent,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )

                            Text(
                                text = program.title,
                                color = KBTextHi,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 3.dp)
                            )

                            program.description?.takeIf { it.isNotBlank() }?.let { desc ->
                                Text(
                                    text = desc,
                                    color = KBTextLo,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 5.dp)
                                )
                            }
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
    androidx.tv.material3.Surface(
        shape = RoundedCornerShape(14.dp),
        colors = androidx.tv.material3.SurfaceDefaults.colors(
            containerColor = KBSurfaceRaised,
            contentColor = KBTextHi
        )
    ) {
        Column(
            modifier = modifier
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
    modifier: Modifier = Modifier
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
    androidx.tv.material3.Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        colors = androidx.tv.material3.SurfaceDefaults.colors(
            containerColor = KBSurfaceRaised.copy(alpha = 0.96f),
            contentColor = KBTextHi
        ),
        border = androidx.tv.material3.Border(
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
        mutableStateOf(
            SimpleDateFormat("EEE, h:mm a", Locale.US).format(Date())
        )
    }

    LaunchedEffect(Unit) {
        val formatter = SimpleDateFormat("EEE, h:mm a", Locale.US)
        while (true) {
            timeLabel = formatter.format(Date())
            delay(30_000)
        }
    }

    return timeLabel
}

private fun formatTimeRange(startMillis: Long, endMillis: Long): String {
    val formatter = SimpleDateFormat("h:mm a", Locale.US)
    return "${formatter.format(Date(startMillis))} - ${formatter.format(Date(endMillis))}"
}
