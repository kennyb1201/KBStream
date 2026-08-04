package com.kennyb1201.kbstream.ui.streams

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.data.addon.Stream
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.player.PlayerActivity
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import android.util.Log

@Composable
fun StreamsScreen(
    contentType: String,
    streamId: String,
    title: String,
    parentId: String,
    parentType: String,
    season: Int?,
    episode: Int?,
    displayName: String,
    itemPoster: String?,
    resumePositionMs: Long,
    viewModel: StreamsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val context = LocalContext.current
    val streams by viewModel.streams.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val debug by viewModel.debug.collectAsState()

    LaunchedEffect(streamId) {
        viewModel.load(contentType, streamId)
    }

    fun streamLabel(stream: Stream): String =
        stream.title?.takeIf { it.isNotBlank() }
            ?: stream.description?.takeIf { it.isNotBlank() }
            ?: stream.name?.takeIf { it.isNotBlank() }
            ?: "Unnamed stream"

    fun playStream(stream: Stream) {
        val url = stream.url ?: return

        Log.e(
    "KBStream",
    "launch player parentId=$parentId parentType=$parentType contentType=$contentType season=$season episode=$episode streamId=$streamId resumePositionMs=$resumePositionMs"
)
        val intent = Intent(context, PlayerActivity::class.java).apply {
            putExtra("stream_url", url)
            putExtra("parent_id", parentId)
            putExtra("parent_type", parentType)
            season?.let { putExtra("season", it) }
            episode?.let { putExtra("episode", it) }
            if (contentType == "series" && season != null && episode != null) {
    putExtra("episode_stream_id", "$parentId:$season:$episode")
            }
            putExtra("item_name", displayName)
            putExtra("item_poster", itemPoster)
            putExtra("start_position_ms", resumePositionMs)
        }
        context.startActivity(intent)
    }

    Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Column {
            Text(title, style = MaterialTheme.typography.displayLarge, modifier = Modifier.padding(bottom = 16.dp))

            when {
                isLoading -> Text("Loading streams...")
                streams.isEmpty() -> {
                    Text("No streams found", color = KBTextLo, modifier = Modifier.padding(bottom = 12.dp))
                    Text("Why:", color = KBTextLo, modifier = Modifier.padding(bottom = 4.dp))
                    debug.forEach { line ->
                        Text(line, fontFamily = FontFamily.Monospace, color = KBTextLo, modifier = Modifier.padding(bottom = 2.dp))
                    }
                }
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(streams) { stream: Stream ->
                        val playable = stream.url != null
                        KBCard(
                            onClick = { if (playable) playStream(stream) },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(streamLabel(stream), fontFamily = FontFamily.Monospace, color = if (playable) KBTextHi else KBTextLo)
                                if (!playable) {
                                    Text("Torrent link — direct playback not supported yet", color = KBTextLo)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
