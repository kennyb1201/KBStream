package com.kennyb1201.kbstream.ui.streams

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.data.addon.Stream
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBTextLo

@Composable
fun StreamsScreen(
    onStreamSelected: (Stream) -> Unit,
    viewModel: StreamsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val streams by viewModel.streams.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val debugLines by viewModel.debug.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(24.dp)
    ) {
        Text(
            text = "Available Streams",
            color = Color.White,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        when {
            isLoading -> {
                Text(
                    text = "Scraping and ranking streams...",
                    color = KBTextLo,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }
            streams.isEmpty() -> {
                Text(
                    text = "No streams found.",
                    color = KBTextLo,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    items(streams) { stream ->
                        val title = stream.title ?: stream.name ?: "Unknown Stream"
                        val description = stream.description ?: ""

                        KBCard(
                            onClick = { onStreamSelected(stream) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = title,
                                    color = Color.White
                                )
                                if (description.isNotBlank()) {
                                    Text(
                                        text = description,
                                        color = KBTextLo,
                                        modifier = Modifier.padding(top = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (debugLines.isNotEmpty()) {
            Text(
                text = "Debug Logs:",
                color = KBAccent,
                modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
            )
            LazyColumn(
                modifier = Modifier
                    .weight(0.5f)
                    .fillMaxWidth()
                    .background(Color.DarkGray.copy(alpha = 0.3f))
                    .padding(8.dp)
            ) {
                items(debugLines) { line ->
                    Text(
                        text = line,
                        color = KBTextLo,
                        modifier = Modifier.padding(vertical = 2.dp)
                    )
                }
            }
        }
    }
}
