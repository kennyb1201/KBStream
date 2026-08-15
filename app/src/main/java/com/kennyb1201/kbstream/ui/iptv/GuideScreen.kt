package com.kennyb1201.kbstream.ui.iptv

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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

    val channelListState = rememberLazyListState()
    val groupRowFocusRequester = remember { FocusRequester() }
    val channelListFocusRequester = remember { FocusRequester() }

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
                    message = "Fetching playlist and programme data...",
                    modifier = Modifier.fillMaxSize()
                )
            }

            error != null && lineup == null -> {
                ErrorState(
                    error = error.orEmpty(),
                    onRetry = {
                        viewModel.load(
                            playlistUrl = playlistUrl,
                            epgUrlOverride = epgUrl,
                            playlistName = lineup?.playlist?.name.orEmpty()
                        )
                    },
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
                                    message = "Choose a channel from the list to view programme details.",
                                    modifier = Modifier.fillMaxSize()
                                )
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
                add("$cha
