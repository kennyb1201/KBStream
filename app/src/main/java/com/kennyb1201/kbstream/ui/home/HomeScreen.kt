package com.kennyb1201.kbstream.ui.home

import android.util.Log
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.kennyb1201.kbstream.data.addon.MetaPreview
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.components.PosterCard
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBTextLo

@Composable
fun HomeScreen(
    onItemClick: (MetaPreview) -> Unit,
    onManageAddons: () -> Unit,
    onSearch: () -> Unit = {},
    onOpenSimkl: () -> Unit,
    viewModel: HomeViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val context = LocalContext.current

    val rails by viewModel.rails.collectAsStateWithLifecycle()
    val watchedIds by viewModel.watchedIds.collectAsStateWithLifecycle()
    val upNext by viewModel.upNext.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    // Re-check Up Next (local history + Simkl) every time Home comes back into view.
    // This is what picks up a freshly completed Simkl auth, since observeRecent()
    // alone only re-fires when the local watch_history table changes.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshUpNext()
    }

    Log.e(
        "HOME_UI",
        "HomeScreen composed, upNext=${upNext.size}, rails=${rails.size}, isLoading=$isLoading, error=$error"
    )
    Log.e(
        "HOME_UI",
        "vmClass=${viewModel::class.qualifiedName}, vmHash=${System.identityHashCode(viewModel)}"
    )

    fun openUpNext(item: UpNextItem) {
        val url = item.streamUrl

        if (!url.isNullOrBlank()) {
            onItemClick(
                MetaPreview(
                    id = item.parentId ?: item.id,
                    type = item.parentType ?: "movie",
                    name = item.title,
                    poster = item.poster
                )
            )
            return
        }

        val parentId = item.parentId
        val parentType = item.parentType

        if (!parentId.isNullOrBlank() && !parentType.isNullOrBlank()) {
            Log.e(
                "HOME_UI",
                "Up next fallback to detail, title=${item.title}, parentType=$parentType, parentId=$parentId"
            )

            onItemClick(
                MetaPreview(
                    id = parentId,
                    type = parentType,
                    name = item.title,
                    poster = item.poster
                )
            )
            return
        }

        Log.e(
            "HOME_UI",
            "Up next click ignored, no stream or detail target for title=${item.title}"
        )
    }

    fun badgeColor(badge: UpNextBadge): Color = when (badge) {
        UpNextBadge.CONTINUE_WATCHING -> KBAccent
        UpNextBadge.NEXT_UP -> Color(0xFF2E5BFF)
        UpNextBadge.NEW_EPISODE -> Color(0xFF2E7D32)
        UpNextBadge.NEW_SEASON -> Color(0xFF6A1B9A)
    }

    fun badgeText(badge: UpNextBadge): String = when (badge) {
        UpNextBadge.CONTINUE_WATCHING -> "CONTINUE"
        UpNextBadge.NEXT_UP -> "NEXT UP"
        UpNextBadge.NEW_EPISODE -> "NEW EPISODE"
        UpNextBadge.NEW_SEASON -> "NEW SEASON"
    }

    fun buildPosterRequest(url: String?): ImageRequest {
        return ImageRequest.Builder(context)
            .data(url)
            .build()
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item(key = "actions") {
            Row(modifier = Modifier.padding(24.dp)) {
                KBCard(
                    onClick = onSearch,
                    modifier = Modifier.padding(end = 10.dp)
                ) {
                    Text("SEARCH", modifier = Modifier.padding(10.dp))
                }

                KBCard(
                    onClick = onManageAddons,
                    modifier = Modifier.padding(end = 10.dp)
                ) {
                    Text("ADD-ONS", modifier = Modifier.padding(10.dp))
                }

                KBCard(onClick = onOpenSimkl) {
                    Text("SIMKL", modifier = Modifier.padding(10.dp))
                }
            }
        }

        item(key = "up_next_rail") {
            Column(modifier = Modifier.padding(start = 24.dp, bottom = 20.dp)) {
                Text(
                    "UP NEXT",
                    color = KBTextLo,
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                if (upNext.isEmpty()) {
                    Text(
                        "No continue watching items yet.",
                        color = KBTextLo
                    )
                } else {
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
                                            model = buildPosterRequest(item.poster),
                                            contentDescription = item.title,
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize(),
                                            onError = { state ->
                                                Log.e(
                                                    "HOME_UI",
                                                    "Poster load failed, title=${item.title}, poster=${item.poster}, error=${state.result.throwable.message}"
                                                )
                                            }
                                        )

                                        if (
                                            item.badge != UpNextBadge.NEXT_UP &&
                                            item.badge != UpNextBadge.CONTINUE_WATCHING
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.TopStart)
                                                    .padding(6.dp)
                                                    .background(badgeColor(item.badge))
                                                    .padding(horizontal = 6.dp, vertical = 3.dp)
                                            ) {
                                                Text(
                                                    text = badgeText(item.badge),
                                                    color = Color.White,
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Clip
                                                )
                                            }
                                        }

                                        val progress = item.progressPercent
                                        if (
                                            item.badge == UpNextBadge.CONTINUE_WATCHING &&
                                            progress != null &&
                                            progress > 0f
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .align(Alignment.BottomStart)
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 8.dp, vertical = 8.dp)
                                                    .height(6.dp)
                                                    .background(Color.White.copy(alpha = 0.28f))
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth(progress.coerceIn(0f, 1f))
                                                        .height(6.dp)
                                                        .background(KBAccent)
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

                                    item.subtitle?.let { subtitle ->
                                        Text(
                                            text = subtitle,
                                            color = KBTextLo,
                                            maxLines = 2,
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
                                key = { meta -> meta.id }
                            ) { meta ->
                                PosterCard(
                                    posterUrl = meta.poster,
                                    contentDescription = meta.name,
                                    isWatched = meta.id in watchedIds,
                                    onClick = { onItemClick(meta) },
                                    modifier = Modifier
                                        .width(150.dp)
                                        .height(230.dp)
                                        .padding(end = 14.dp),
                                    onPosterError = { throwable ->
                                        Log.e(
                                            "HOME_UI",
                                            "Catalog poster load failed, title=${meta.name}, poster=${meta.poster}, error=${throwable?.message}"
                                        )
                                    }
                                )
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
