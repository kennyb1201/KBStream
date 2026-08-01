package com.kennyb1201.kbstream.ui.home

import android.content.Intent
import androidx.compose.foundation.background
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.kennyb1201.kbstream.data.addon.MetaPreview
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
    val upNext by viewModel.upNext.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    fun openUpNext(item: UpNextItem) {
        val url = item.streamUrl ?: return
        val intent = Intent(context, PlayerActivity::class.java).apply {
            putExtra("stream_url", url)
            item.parentId?.let { putExtra("parent_id", it) }
            item.parentType?.let { putExtra("parent_type", it) }
            item.season?.let { putExtra("season", it) }
            item.episode?.let { putExtra("episode", it) }
            item.episodeStreamId?.let { putExtra("episode_stream_id", it) }
            putExtra("item_name", item.title)
            putExtra("item_poster", item.poster)
            putExtra("start_position_ms", item.startPositionMs)
        }
        context.startActivity(intent)
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Row(modifier = Modifier.padding(24.dp)) {
                KBCard(
                    onClick = onSearch,
                    modifier = Modifier.padding(end = 10.dp)
                ) {
                    Text("SEARCH", modifier = Modifier.padding(10.dp))
                }

                KBCard(onClick = onManageAddons) {
                    Text("ADD-ONS", modifier = Modifier.padding(10.dp))
                }
            }
        }

        if (upNext.isNotEmpty()) {
            item(key = "up_next_rail") {
                Column(modifier = Modifier.padding(start = 24.dp, bottom = 20.dp)) {
                    Text(
                        "UP NEXT",
                        color = KBTextLo,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    LazyRow {
                        items(
                            items = upNext,
                            key = { it.id }
                        ) { item ->
                            KBCard(
                                onClick = { openUpNext(item) },
                                modifier = Modifier
                                    .width(150.dp)
                                    .padding(end = 14.dp)
                            ) {
                                Column {
                                    Box(
                                        modifier = Modifier
                                            .width(150.dp)
                                            .height(230.dp)
                                    ) {
                                        AsyncImage(
                                            model = item.poster,
                                            contentDescription = item.title,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )

                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopStart)
                                                .padding(8.dp)
                                                .background(
                                                    color = when (item.badge) {
                                                        UpNextBadge.CONTINUE_WATCHING -> KBAccent
                                                        UpNextBadge.NEXT_UP -> Color(0xFF2E5BFF)
                                                        UpNextBadge.NEW_EPISODE -> Color(0xFF2E7D32)
                                                        UpNextBadge.NEW_SEASON -> Color(0xFF6A1B9A)
                                                    }
                                                )
                                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                        ) {
                                            Text(
                                                text = when (item.badge) {
                                                    UpNextBadge.CONTINUE_WATCHING -> "CONTINUE"
                                                    UpNextBadge.NEXT_UP -> "NEXT UP"
                                                    UpNextBadge.NEW_EPISODE -> "NEW EPISODE"
                                                    UpNextBadge.NEW_SEASON -> "NEW SEASON"
                                                },
                                                color = Color.White
                                            )
                                        }

                                        if ((item.progressPercent ?: 0f) > 0f) {
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.BottomStart)
                                                    .padding(horizontal = 8.dp, vertical = 8.dp)
                                                    .background(Color.Black.copy(alpha = 0.65f))
                                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                            ) {
                                                Text(
                                                    "${item.progressPercent.toInt()}%",
                                                    color = Color.White
                                                )
                                            }
                                        }
                                    }

                                    Text(
                                        text = item.title,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.padding(top = 8.dp)
                                    )

                                    item.subtitle?.let {
                                        Text(
                                            text = it,
                                            color = KBTextLo,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(top = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        when {
            isLoading -> {
                item(key = "loading") {
                    Text(
                        "Loading catalogs...",
                        modifier = Modifier.padding(24.dp)
                    )
                }
            }

            error != null -> {
                item(key = "error") {
                    Text(
                        "Error: $error",
                        modifier = Modifier.padding(24.dp)
                    )
                }
            }

            rails.isEmpty() -> {
                item(key = "empty") {
                    Text(
                        "No catalogs available. Add an addon to get started.",
                        modifier = Modifier.padding(24.dp)
                    )
                }
            }

            else -> {
                items(
                    items = rails,
                    key = { "${it.addonName}:${it.catalogName}:${it.type}" }
                ) { rail ->
                    Column(modifier = Modifier.padding(start = 24.dp, bottom = 20.dp)) {
                        Text(
                            "${rail.catalogName.uppercase()} · ${rail.addonName}",
                            color = KBTextLo,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        LazyRow {
                            items(
                                items = rail.items,
                                key = { it.id }
                            ) { meta ->
                                KBCard(
                                    onClick = { onItemClick(meta) },
                                    modifier = Modifier
                                        .width(150.dp)
                                        .height(230.dp)
                                        .padding(end = 14.dp)
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

        item(key = "bottom_spacer") {
            Box(modifier = Modifier.height(48.dp))
        }
    }
}
