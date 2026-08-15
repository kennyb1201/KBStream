package com.kennyb1201.kbstream.ui.iptv

import android.content.res.ColorStateList
import android.graphics.Color as AndroidColor
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.widget.EditText
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.kennyb1201.kbstream.data.iptv.IptvChannelWithEpg
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
    val lineup by viewModel.lineup.collectAsState()
    val visibleChannels by viewModel.visibleChannels.collectAsState()
    val groups by viewModel.groups.collectAsState()
    val selectedGroup by viewModel.selectedGroup.collectAsState()
    val selectedChannel by viewModel.selectedChannel.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val playlistUrl by viewModel.playlistUrl.collectAsState()
    val epgUrl by viewModel.epgUrl.collectAsState()
    val playlistName by viewModel.playlistName.collectAsState()

    val channelListState = rememberLazyListState()
    val groupRowFocusRequester = remember { FocusRequester() }
    val channelListFocusRequester = remember { FocusRequester() }

    var showSetup by remember { mutableStateOf(true) }

    LaunchedEffect(defaultPlaylistUrl, defaultEpgUrl, defaultPlaylistName) {
        if (lineup == null && !isLoading && defaultPlaylistUrl.isNotBlank()) {
            viewModel.onPlaylistUrlChanged(defaultPlaylistUrl)
            if (defaultEpgUrl.isNotBlank()) viewModel.onEpgUrlChanged(defaultEpgUrl)
            if (defaultPlaylistName.isNotBlank()) viewModel.onPlaylistNameChanged(defaultPlaylistName)
            viewModel.load(
                playlistUrl = defaultPlaylistUrl,
                epgUrlOverride = defaultEpgUrl,
                playlistName = defaultPlaylistName
            )
            showSetup = false
        }
    }

    LaunchedEffect(lineup) {
        if (lineup != null) {
            showSetup = false
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
            isLoading && lineup == null -> {
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
                        .padding(horizontal = 24.dp, vertical = 20.dp)
                ) {
                    GuideHeader(
                        title = lineup?.playlist?.name?.ifBlank { "IPTV" } ?: "IPTV",
                        channelCount = visibleChannels.size,
                        selectedGroup = selectedGroup
                    )

                    Spacer(modifier = Modifier.height(18.dp))

                    SetupPanel(
                        playlistUrl = playlistUrl,
                        epgUrl = epgUrl,
                        playlistName = playlistName,
                        isExpanded = showSetup || lineup == null,
                        isLoading = isLoading,
                        error = error,
                        onToggleExpanded = { showSetup = !showSetup },
                        onPlaylistUrlChanged = viewModel::onPlaylistUrlChanged,
                        onEpgUrlChanged = viewModel::onEpgUrlChanged,
                        onPlaylistNameChanged = viewModel::onPlaylistNameChanged,
                        onLoad = { viewModel.load() },
                        onReload = { viewModel.refresh() },
                        onClear = {
                            viewModel.reset()
                            viewModel.onPlaylistUrlChanged("")
                            viewModel.onEpgUrlChanged("")
                            viewModel.onPlaylistNameChanged("")
                            showSetup = true
                        }
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    if (lineup == null) {
                        CenterMessage(
                            title = "Enter a playlist",
                            message = "Enter your M3U URL above, then optionally add an XMLTV EPG URL.",
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        )
                    } else {
                        if (groups.isNotEmpty()) {
                            LazyRow(
                                contentPadding = PaddingValues(end = 12.dp),
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier
                                    .focusGroup()
                                    .focusRestorer()
                                    .focusRequester(groupRowFocusRequester)
                            ) {
                                itemsIndexed(groups, key = { _, item -> item }) { _, group ->
                                    GroupChip(
                                        name = group,
                                        selected = group == selectedGroup,
                                        onClick = { viewModel.selectGroup(group) }
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        Row(
                            modifier = Modifier.fillMaxSize()
                        ) {
                            LazyColumn(
                                state = channelListState,
                                contentPadding = PaddingValues(bottom = 24.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier
                                    .width(360.dp)
                                    .fillMaxHeight()
                                    .focusGroup()
                                    .focusRestorer()
                                    .focusRequester(channelListFocusRequester)
                            ) {
                                itemsIndexed(
                                    items = visibleChannels,
                                    key = { _, item -> item.channel.id }
                                ) { index, item ->
                                    ChannelRowCard(
                                        item = item,
                                        selected = selectedChannel?.channel?.id == item.channel.id,
                                        onClick = {
                                            viewModel.selectChannel(index)
                                            onPlayChannel?.invoke(item)
                                        },
                                        onFocused = {
                                            viewModel.selectChannel(index)
                                        }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(18.dp))

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            ) {
                                if (selectedChannel != null) {
                                    GuideDetailPanel(
                                        item = selectedChannel!!,
                                        onPlay = { onPlayChannel?.invoke(selectedChannel!!) }
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

                if (error != null && lineup != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(24.dp)
                    ) {
                        InlineErrorChip(message = error.orEmpty())
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
    isExpanded: Boolean,
    isLoading: Boolean,
    error: String?,
    onToggleExpanded: () -> Unit,
    onPlaylistUrlChanged: (String) -> Unit,
    onEpgUrlChanged: (String) -> Unit,
    onPlaylistNameChanged: (String) -> Unit,
    onLoad: () -> Unit,
    onReload: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(KBSurface, RoundedCornerShape(18.dp))
            .padding(18.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "PLAYLIST SETUP",
                    color = KBAccent,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (playlistUrl.isBlank()) {
                        "Enter an M3U URL to load channels"
                    } else {
                        playlistUrl
                    },
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            KBCard(
                onClick = onToggleExpanded,
                modifier = Modifier.padding(start = 10.dp)
            ) {
                Text(
                    text = if (isExpanded) "HIDE" else "SETUP",
                    color = KBTextHi,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        }

        if (!isExpanded) return

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

        Spacer(modifier = Modifier.height(16.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KBCard(onClick = onLoad) {
                Text(
                    text = if (isLoading) "LOADING..." else "LOAD PLAYLIST",
                    color = KBTextHi,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)
                )
            }

            KBCard(onClick = onReload) {
                Text(
                    text = "RELOAD",
                    color = KBTextHi,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)
                )
            }

            KBCard(onClick = onClear) {
                Text(
                    text = "CLEAR",
                    color = KBTextHi,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)
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
        modifier = modifier.height(56.dp),
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
                modifier = Modifier.padding(top = 4.dp)
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
                style = MaterialTheme.typography.titleMedium,
                color = KBTextHi,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
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
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (selected) KBAccent else KBTextLo.copy(alpha = 0.45f))
            )

            Text(
                text = name,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) KBAccent else KBTextHi,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 10.dp)
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
    KBCard(
        onClick = onClick,
        modifier = modifier.onFocusChanged {
            if (it.isFocused) onFocused()
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (selected) KBSurfaceRaised.copy(alpha = 0.96f) else KBSurface)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ChannelLogo(item = item)

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.channel.displayName,
                    color = if (selected) KBAccent else KBTextHi,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                val nowTitle = item.now?.title ?: "No program data"
                Text(
                    text = nowTitle,
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }

            item.channel.tvgChno?.takeIf { it.isNotBlank() }?.let { chno ->
                androidx.tv.material3.Surface(
                    shape = RoundedCornerShape(6.dp),
                    colors = androidx.tv.material3.SurfaceDefaults.colors(
                        containerColor = KBVoid.copy(alpha = 0.72f),
                        contentColor = KBTextHi
                    )
                ) {
                    Text(
                        text = chno,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
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
            .width(52.dp)
            .height(52.dp)
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
                    .padding(8.dp)
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
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(KBSurface, RoundedCornerShape(18.dp))
            .padding(20.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            ChannelLogo(item = item)

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.channel.displayName,
                    color = KBTextHi,
                    style = MaterialTheme.typography.headlineMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                val subline = buildList {
                    item.channel.groupTitle?.takeIf { it.isNotBlank() }?.let { add(it) }
                    item.channel.tvgId?.takeIf { it.isNotBlank() }?.let { add(it) }
                }.joinToString("  •  ")

                if (subline.isNotBlank()) {
                    Text(
                        text = subline,
                        color = KBTextLo,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            KBCard(onClick = onPlay) {
                Text(
                    text = "PLAY",
                    color = KBTextHi,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)
                )
            }
        }

        Spacer(modifier = Modifier.height(18.dp))

        NowNextPanel(item = item)

        Spacer(modifier = Modifier.height(18.dp))

        Text(
            text = "UPCOMING",
            color = KBTextLo,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(bottom = 10.dp)
        )

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(bottom = 12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            val upcoming = item.upcoming.take(12)

            if (upcoming.isEmpty()) {
                item {
                    CenterMessage(
                        title = "No guide data",
                        message = "Program information is not available for this channel.",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 32.dp)
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
                                .padding(horizontal = 14.dp, vertical = 12.dp)
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
                                modifier = Modifier.padding(top = 4.dp)
                            )

                            program.description?.takeIf { it.isNotBlank() }?.let { desc ->
                                Text(
                                    text = desc,
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
        horizontalArrangement = Arrangement.spacedBy(14.dp)
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
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Text(
                text = label,
                color = KBAccent,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )

            Text(
                text = title,
                color = KBTextHi,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp)
            )

            if (!time.isNullOrBlank()) {
                Text(
                    text = time,
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            description?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp)
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
                .padding(horizontal = 24.dp, vertical = 20.dp)
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
            SimpleDateFormat("EEE, h:mm a", Locale.getDefault()).format(Date())
        )
    }

    LaunchedEffect(Unit) {
        val formatter = SimpleDateFormat("EEE, h:mm a", Locale.getDefault())
        while (true) {
            timeLabel = formatter.format(Date())
            delay(30_000)
        }
    }

    return timeLabel
}

private fun formatTimeRange(startMillis: Long, endMillis: Long): String {
    val formatter = SimpleDateFormat("h:mm a", Locale.getDefault())
    return "${formatter.format(Date(startMillis))} - ${formatter.format(Date(endMillis))}"
}
