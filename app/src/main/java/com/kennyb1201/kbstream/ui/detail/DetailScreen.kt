package com.kennyb1201.kbstream.ui.detail

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.kennyb1201.kbstream.data.addon.Stream
import com.kennyb1201.kbstream.data.addon.VideoEntry
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.width
import com.kennyb1201.kbstream.ui.player.PlayerActivity

@Composable
fun DetailScreen(
    type: String,
    id: String,
    viewModel: DetailViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val context = LocalContext.current
    var selectedVideoId by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(id) {
        viewModel.load(type, id)
    }

    val meta by viewModel.meta.collectAsState()
    val streams by viewModel.streams.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val streamsLoading by viewModel.streamsLoading.collectAsState()
    val tmdbDetail by viewModel.tmdbDetail.collectAsState()
    val error by viewModel.error.collectAsState()

    fun playStream(stream: Stream) {
        val url = stream.url ?: return
        val intent = Intent(context, PlayerActivity::class.java).apply {
            putExtra("stream_url", url)
            putExtra("item_id", selectedVideoId ?: id)
            putExtra("item_type", type)
            putExtra("item_name", meta?.name ?: "")
            putExtra("item_poster", meta?.poster)
        }
        context.startActivity(intent)
    }

    fun streamLabel(stream: Stream): String =
        stream.title?.takeIf { it.isNotBlank() }
            ?: stream.description?.takeIf { it.isNotBlank() }
            ?: stream.name?.takeIf { it.isNotBlank() }
            ?: "Unnamed stream"

    Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        when {
            isLoading -> Text("Loading...")
            error != null -> Text("Error: $error")
            meta != null -> {
                val m = meta!!
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item {
                        AsyncImage(
                            model = m.background ?: m.poster,
                            contentDescription = m.name,
                            modifier = Modifier.fillMaxWidth().height(200.dp)
                        )
                        Text(m.name, modifier = Modifier.padding(top = 12.dp))

                        val metaLine = listOfNotNull(
                            m.releaseInfo,
                            m.runtime,
                            m.imdbRating?.let { "IMDb $it" }
                        ).joinToString("  •  ")
                        if (metaLine.isNotBlank()) {
                            Text(metaLine, modifier = Modifier.padding(top = 4.dp))
                        }
                        m.genres?.takeIf { it.isNotEmpty() }?.let {
                            Text(it.joinToString(", "), modifier = Modifier.padding(top = 4.dp))
                        }
                        m.description?.let {
                            Text(it, modifier = Modifier.padding(top = 12.dp))
                        }
                        m.director?.takeIf { it.isNotEmpty() }?.let {
                            Text("Director: ${it.joinToString(", ")}", modifier = Modifier.padding(top = 12.dp))
                        }
                        m.cast?.takeIf { it.isNotEmpty() }?.let {
                            Text("Cast: ${it.joinToString(", ")}", modifier = Modifier.padding(top = 4.dp))
                        }
                        m.country?.let { Text("Country: $it", modifier = Modifier.padding(top = 4.dp)) }
                        m.language?.let { Text("Language: $it", modifier = Modifier.padding(top = 4.dp)) }
                        m.awards?.let { Text("Awards: $it", modifier = Modifier.padding(top = 4.dp)) }
                    }

                    if (type == "series" && !m.videos.isNullOrEmpty()) {
                        item { Text("Episodes", modifier = Modifier.padding(top = 20.dp, bottom = 8.dp)) }
                        items(m.videos!!) { video: VideoEntry ->
                            Card(
                                onClick = {
                                    selectedVideoId = video.id
                                    viewModel.loadStreamsFor(video.id)
                                },
                                colors = CardDefaults.colors(containerColor = Color(0xFF1B3A57), contentColor = Color.White),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            ) {
                                val label = listOfNotNull(
                                    video.season?.let { s -> video.episode?.let { e -> "S${s}E$e" } },
                                    video.title
                                ).joinToString(" - ")
                                Text(label.ifBlank { video.id }, modifier = Modifier.padding(12.dp))
                            }
                        }
                    }

                    item {
                        Text(
                            when {
                                streamsLoading -> "Loading streams..."
                                streams.isEmpty() -> "No streams yet"
                                else -> "Streams"
                            },
                            modifier = Modifier.padding(top = 20.dp, bottom = 8.dp)
                        )
                    }
                    items(streams) { stream: Stream ->
                        Card(
                            onClick = { playStream(stream) },
                            colors = CardDefaults.colors(containerColor = Color(0xFF1B3A57), contentColor = Color.White),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Text(
                                streamLabel(stream),
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
