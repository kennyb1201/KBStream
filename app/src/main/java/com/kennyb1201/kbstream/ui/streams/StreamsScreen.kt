package com.kennyb1201.kbstream.ui.streams

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.data.addon.Stream
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import androidx.compose.foundation.BorderStroke

@Composable
fun StreamsScreen(
    onStreamSelected: (selected: Stream, allSources: List<Stream>) -> Unit,
    viewModel: StreamsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val streams by viewModel.streams.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val debugLines by viewModel.debug.collectAsStateWithLifecycle()

    var showDebug by remember { mutableStateOf(false) }

    val streamCountLabel = when {
        isLoading -> "Searching for the best sources"
        streams.isNotEmpty() -> "${streams.size} sources found"
        else -> "Choose a source to begin playback"
    }

    Box(
        modifier = Modifier
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

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 20.dp)
        ) {
            StreamsHeader(
                streamCountLabel = streamCountLabel,
                isLoading = isLoading,
                streamCount = streams.size,
                showDebugToggle = debugLines.isNotEmpty(),
                showDebug = showDebug,
                onToggleDebug = { showDebug = !showDebug }
            )

            Spacer(modifier = Modifier.height(16.dp))

            when {
                isLoading -> {
                    StreamsHeroState(
                        title = "FINDING SOURCES",
                        message = "Scraping add-ons, ranking results, and preparing the cleanest playback options."
                    )
                }

                streams.isEmpty() -> {
                    StreamsHeroState(
                        title = "NO SOURCES FOUND",
                        message = "Try another add-on, refresh the request, or check that the provider is still online."
                    )
                }

                else -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 20.dp),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .focusGroup()
                    ) {
                        items(
                            items = streams,
                            key = { stream ->
                                listOf(
                                    stream.url.orEmpty(),
                                    stream.title.orEmpty(),
                                    stream.name.orEmpty(),
                                    stream.infoHash.orEmpty(),
                                    stream.fileIdx?.toString().orEmpty()
                                ).joinToString("|")
                            }
                        ) { stream ->
                            StreamCard(
                                stream = stream,
                                onClick = { onStreamSelected(stream, streams) }
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(visible = debugLines.isNotEmpty() && showDebug) {
                Column {
                    Spacer(modifier = Modifier.height(12.dp))
                    DebugPanel(lines = debugLines)
                }
            }
        }
    }
}

@Composable
private fun StreamsHeader(
    streamCountLabel: String,
    isLoading: Boolean,
    streamCount: Int,
    showDebugToggle: Boolean,
    showDebug: Boolean,
    onToggleDebug: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "STREAMS",
                color = KBAccent,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = streamCountLabel,
                color = KBTextLo,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (streamCount > 0) {
                InfoChip(
                    label = "$streamCount READY",
                    accent = true
                )
            }

            if (isLoading) {
                InfoChip(
                    label = "LIVE SCAN",
                    accent = false
                )
            }

            if (showDebugToggle) {
                KBCard(onClick = onToggleDebug) {
                    Text(
                        text = if (showDebug) "HIDE DEBUG" else "DEBUG",
                        color = KBTextHi,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoChip(
    label: String,
    accent: Boolean
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        colors = SurfaceDefaults.colors(
            containerColor = if (accent) KBAccent.copy(alpha = 0.16f) else KBSurfaceRaised.copy(alpha = 0.96f),
            contentColor = if (accent) KBAccent else KBTextLo
        ),
        border = Border(
            border = BorderStroke(
                1.dp,
                if (accent) KBAccent.copy(alpha = 0.32f) else KBTextLo.copy(alpha = 0.14f)
            ),
            shape = RoundedCornerShape(999.dp)
        )
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun StreamCard(
    stream: Stream,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.012f else 1f,
        animationSpec = tween(durationMillis = 140),
        label = "streamCardScale"
    )
    val alpha by animateFloatAsState(
        targetValue = if (isFocused) 1f else 0.97f,
        animationSpec = tween(durationMillis = 140),
        label = "streamCardAlpha"
    )

    val title = stream.title?.takeIf { it.isNotBlank() }
        ?: stream.name?.takeIf { it.isNotBlank() }
        ?: "Unknown source"

    val sourceKind = when {
        !stream.url.isNullOrBlank() -> "Direct stream"
        !stream.infoHash.isNullOrBlank() -> "Torrent source"
        else -> "Unknown type"
    }

    val detailLine = buildList {
        stream.description?.takeIf { it.isNotBlank() }?.let(::add)
        stream.infoHash?.takeIf { it.isNotBlank() }?.let { add("Hash: ${it.take(10)}...") }
        stream.fileIdx?.let { add("File index $it") }
    }.joinToString("  •  ")

    val chips = buildList {
        stream.name?.takeIf { it.isNotBlank() }?.let(::add)
        if (!stream.infoHash.isNullOrBlank()) add("P2P")
        if (!stream.url.isNullOrBlank()) add("HTTP")
        if (stream.fileIdx != null) add("Indexed")
    }.distinct()

    KBCard(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
            .onFocusChanged { isFocused = it.isFocused }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(if (isFocused) KBSurfaceRaised.copy(alpha = 0.96f) else KBSurface)
                .border(
                    width = if (isFocused) 1.dp else 0.dp,
                    color = if (isFocused) KBAccent.copy(alpha = 0.28f) else Color.Transparent,
                    shape = RoundedCornerShape(14.dp)
                )
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(KBVoid.copy(alpha = 0.56f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (!stream.url.isNullOrBlank()) Icons.Filled.Link else Icons.Filled.Bolt,
                    contentDescription = null,
                    tint = if (isFocused) KBAccent else KBTextLo,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = if (isFocused) KBAccent else KBTextHi,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = sourceKind,
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp)
                )

                if (detailLine.isNotBlank()) {
                    Text(
                        text = detailLine,
                        color = KBTextLo,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                if (chips.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        chips.take(4).forEach { chip ->
                            StreamMetaChip(label = chip, focused = isFocused)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(KBVoid.copy(alpha = if (isFocused) 0.58f else 0.42f))
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = if (isFocused) KBAccent else KBTextLo,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "PLAY",
                        color = if (isFocused) KBAccent else KBTextLo,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun StreamMetaChip(
    label: String,
    focused: Boolean
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (focused) KBVoid.copy(alpha = 0.56f) else KBVoid.copy(alpha = 0.42f))
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        Text(
            text = label,
            color = if (focused) KBAccent else KBTextLo,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun StreamsHeroState(
    title: String,
    message: String
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .background(KBSurface, RoundedCornerShape(18.dp))
                .border(1.dp, KBTextLo.copy(alpha = 0.14f), RoundedCornerShape(18.dp))
                .padding(horizontal = 26.dp, vertical = 22.dp)
        ) {
            Text(
                text = title,
                color = KBAccent,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
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
private fun DebugPanel(lines: List<String>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(210.dp)
            .background(KBSurfaceRaised.copy(alpha = 0.97f), RoundedCornerShape(14.dp))
            .border(1.dp, KBTextLo.copy(alpha = 0.14f), RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Text(
            text = "DEBUG LOG",
            color = KBAccent,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(lines) { line ->
                Text(
                    text = line,
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
