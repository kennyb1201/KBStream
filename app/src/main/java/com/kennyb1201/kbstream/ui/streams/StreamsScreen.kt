package com.kennyb1201.kbstream.ui.streams

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.data.addon.Stream
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid

@Composable
fun StreamsScreen(
    onStreamSelected: (selected: Stream, allSources: List<Stream>) -> Unit,
    viewModel: StreamsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val streams by viewModel.streams.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val debugLines by viewModel.debug.collectAsStateWithLifecycle()

    var showDebug by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(KBVoid)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 20.dp)
        ) {
            Text(
                text = "STREAMS",
                color = KBAccent,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Text(
                text = if (streams.isNotEmpty()) {
                    "${streams.size} sources found"
                } else {
                    "Choose a source to begin playback"
                },
                color = KBTextLo,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(18.dp))

            when {
                isLoading -> {
                    CenterStatus(
                        title = "FINDING SOURCES",
                        message = "Scraping and ranking streams..."
                    )
                }

                streams.isEmpty() -> {
                    CenterStatus(
                        title = "NO SOURCES FOUND",
                        message = "Try another add-on or check its connection."
                    )
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
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
                                onClick = {
                                    onStreamSelected(stream, streams)
                                }
                            )
                        }
                    }
                }
            }

            if (debugLines.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))

                KBCard(
                    onClick = { showDebug = !showDebug },
                    modifier = Modifier.width(156.dp)
                ) {
                    Box(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (showDebug) "HIDE DEBUG" else "DEBUG",
                            color = KBTextHi,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }

                if (showDebug) {
                    Spacer(modifier = Modifier.height(10.dp))

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(170.dp)
                            .background(
                                color = KBSurfaceRaised.copy(alpha = 0.96f),
                                shape = RoundedCornerShape(12.dp)
                            )
                            .padding(12.dp)
                    ) {
                        items(debugLines) { line ->
                            Text(
                                text = line,
                                color = KBTextLo,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(vertical = 3.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamCard(
    stream: Stream,
    onClick: () -> Unit
) {
    val title = stream.title?.takeIf { it.isNotBlank() }
        ?: stream.name?.takeIf { it.isNotBlank() }
        ?: "Unknown source"

    val description = buildList {
        stream.description?.takeIf { it.isNotBlank() }?.let(::add)
        if (stream.url.isNullOrBlank() && !stream.infoHash.isNullOrBlank()) {
            add("Torrent source")
        }
    }.joinToString(" · ")

    KBCard(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(KBSurface)
                .padding(horizontal = 18.dp, vertical = 15.dp)
        ) {
            Text(
                text = title,
                color = KBTextHi,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (description.isNotBlank()) {
                Text(
                    text = description,
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 5.dp)
                )
            }
        }
    }
}

@Composable
private fun CenterStatus(
    title: String,
    message: String
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .background(KBSurface, RoundedCornerShape(16.dp))
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                color = KBAccent,
                style = MaterialTheme.typography.titleMedium
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
