package com.kennyb1201.kbstream.ui.home

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.kennyb1201.kbstream.data.addon.MetaPreview
import com.kennyb1201.kbstream.data.history.WatchHistoryEntity
import com.kennyb1201.kbstream.ui.player.PlayerActivity

@Composable
fun HomeScreen(
    onItemClick: (MetaPreview) -> Unit,
    onManageAddons: () -> Unit,
    onSearch: () -> Unit = {},
    viewModel: HomeViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val context = LocalContext.current
    val rails by viewModel.rails.collectAsState()
    val continueWatching by viewModel.continueWatching.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    fun resume(entry: WatchHistoryEntity) {
        val url = entry.streamUrl ?: return
        val intent = Intent(context, PlayerActivity::class.java).apply {
            putExtra("stream_url", url)
            putExtra("item_id", entry.id)
            putExtra("item_type", entry.type)
            putExtra("item_name", entry.name)
            putExtra("item_poster", entry.poster)
            putExtra("start_position_ms", entry.positionMs)
        }
        context.startActivity(intent)
    }

    Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Column {
            Row {
                Card(
                    onClick = onSearch,
                    colors = CardDefaults.colors(containerColor = Color(0xFF4FC3F7), contentColor = Color.Black),
                    modifier = Modifier.padding(end = 12.dp, bottom = 16.dp)
                ) {
                    Text("Search", modifier = Modifier.padding(12.dp))
                }
                Card(
                    onClick = onManageAddons,
                    colors = CardDefaults.colors(containerColor = Color(0xFF1B3A57), contentColor = Color.White),
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    Text("Manage Add-ons", modifier = Modifier.padding(12.dp))
                }
            }

            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (continueWatching.isNotEmpty()) {
                    item {
                        Column(modifier = Modifier.padding(bottom = 20.dp)) {
                            Text("Continue Watching", modifier = Modifier.padding(bottom = 8.dp))
                            LazyRow {
                                items(continueWatching) { entry ->
                                    Card(
                                        onClick = { resume(entry) },
                                        modifier = Modifier.width(140.dp).height(220.dp).padding(end = 12.dp)
                                    ) {
                                        AsyncImage(
                                            model = entry.poster,
                                            contentDescription = entry.name,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                when {
                    isLoading -> item { Text("Loading catalogs...") }
                    error != null -> item { Text("Error: $error") }
                    rails.isEmpty() -> item { Text("No catalogs available. Add an addon to get started.") }
                    else -> items(rails) { rail ->
                        Column(modifier = Modifier.padding(bottom = 20.dp)) {
                            Text("${rail.catalogName} (${rail.addonName})", modifier = Modifier.padding(bottom = 8.dp))
                            LazyRow {
                                items(rail.items) { meta ->
                                    Card(
                                        onClick = { onItemClick(meta) },
                                        modifier = Modifier.width(140.dp).height(220.dp).padding(end = 12.dp)
                                    ) {
                                        AsyncImage(
                                            model = meta.poster,
                                            contentDescription = meta.name,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
