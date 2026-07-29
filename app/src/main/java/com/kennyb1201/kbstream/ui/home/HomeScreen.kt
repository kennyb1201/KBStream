package com.kennyb1201.kbstream.ui.home

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.kennyb1201.kbstream.data.addon.MetaPreview
import com.kennyb1201.kbstream.data.history.WatchHistoryEntity
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.player.PlayerActivity
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid

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

    val heroItem = rails.firstOrNull()?.items?.firstOrNull()

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Box(modifier = Modifier.fillMaxWidth().height(340.dp)) {
                if (heroItem?.background != null) {
                    AsyncImage(
                        model = heroItem.background,
                        contentDescription = heroItem.name,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        modifier = Modifier.fillMaxSize().background(
                            Brush.verticalGradient(listOf(Color.Transparent, KBVoid))
                        )
                    )
                }
                Column(
                    modifier = Modifier.align(Alignment.BottomStart).padding(24.dp)
                ) {
                    Row {
                        KBCard(onClick = onSearch, modifier = Modifier.padding(end = 10.dp)) {
                            Text("SEARCH", style = MaterialTheme.typography.titleMedium, color = KBAccent, modifier = Modifier.padding(10.dp))
                        }
                        KBCard(onClick = onManageAddons) {
                            Text("ADD-ONS", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(10.dp))
                        }
                    }
                    if (heroItem != null) {
                        Text(
                            heroItem.name,
                            style = MaterialTheme.typography.displayLarge,
                            modifier = Modifier.padding(top = 20.dp)
                        )
                    }
                }
            }
        }

        if (continueWatching.isNotEmpty()) {
            item {
                Column(modifier = Modifier.padding(start = 24.dp, top = 24.dp, bottom = 8.dp)) {
                    Text("CONTINUE WATCHING", style = MaterialTheme.typography.titleMedium, color = KBTextLo)
                }
            }
            item {
                LazyRow(modifier = Modifier.padding(start = 24.dp, bottom = 24.dp)) {
                    items(continueWatching) { entry ->
                        KBCard(
                            onClick = { resume(entry) },
                            modifier = Modifier.width(150.dp).height(230.dp).padding(end = 14.dp)
                        ) {
                            AsyncImage(
                                model = entry.poster,
                                contentDescription = entry.name,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                }
            }
        }

        when {
            isLoading -> item { Text("Loading catalogs...", modifier = Modifier.padding(24.dp)) }
            error != null -> item { Text("Error: $error", modifier = Modifier.padding(24.dp)) }
            rails.isEmpty() -> item { Text("No catalogs available. Add an addon to get started.", modifier = Modifier.padding(24.dp)) }
            else -> items(rails) { rail ->
                Column {
                    Text(
                        "${rail.catalogName.uppercase()} · ${rail.addonName}",
                        style = MaterialTheme.typography.titleMedium,
                        color = KBTextLo,
                        modifier = Modifier.padding(start = 24.dp, top = 8.dp, bottom = 8.dp)
                    )
                    LazyRow(modifier = Modifier.padding(start = 24.dp, bottom = 20.dp)) {
                        items(rail.items) { meta ->
                            KBCard(
                                onClick = { onItemClick(meta) },
                                modifier = Modifier.width(150.dp).height(230.dp).padding(end = 14.dp)
                            ) {
                                AsyncImage(
                                    model = meta.poster,
                                    contentDescription = meta.name,
                                    contentScale = ContentScale.Crop,
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
