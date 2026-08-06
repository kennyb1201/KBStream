package com.kennyb1201.kbstream.ui.studio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.data.tmdb.StudioItem
import com.kennyb1201.kbstream.data.tmdb.StudioSection
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.components.PosterCard
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import kotlinx.coroutines.delay

@Composable
fun StudioScreen(
    id: Int,
    name: String,
    isNetwork: Boolean,
    onBack: () -> Unit = {},
    onNavigateDetail: (String, String) -> Unit = { _, _ -> },
    viewModel: StudioViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val sections by viewModel.sections.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val watchedKeys by viewModel.watchedKeys.collectAsState()
    val resolvedIds by viewModel.resolvedIds.collectAsState()
    val firstItemFocusRequester = remember { FocusRequester() }

    LaunchedEffect(id, isNetwork) {
        viewModel.load(id, isNetwork)
    }

    LaunchedEffect(sections, isLoading) {
        if (!isLoading && sections.any { it.items.isNotEmpty() }) {
            delay(100)
            runCatching { firstItemFocusRequester.requestFocus() }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Column {
            Text(
                text = name,
                style = MaterialTheme.typography.displayLarge,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            when {
                isLoading -> {
                    Text("Loading...")
                }

                sections.isEmpty() -> {
                    Text("Nothing found for $name")
                }

                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(
                            items = sections,
                            key = { section: StudioSection -> section.title }
                        ) { section ->
                            Column(modifier = Modifier.padding(bottom = 20.dp)) {
                                Text(
                                    text = section.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = KBTextLo,
                                    modifier = Modifier.padding(bottom = 8.dp)
                                )

                                LazyRow {
                                    items(
                                        items = section.items,
                                        key = { item: StudioItem ->
                                            "${item.mediaType}:${item.item.id}"
                                        }
                                    ) { studioItem ->
                                        val tmdbId = studioItem.item.id
                                        val rawMediaType = studioItem.mediaType
                                        val normalizedType = when (rawMediaType.lowercase()) {
                                            "tv", "series" -> "series"
                                            else -> "movie"
                                        }

                                        val imdbId = resolvedIds[
                                            viewModel.lookupKey(tmdbId, normalizedType)
                                        ]

                                        val watched = imdbId?.let {
                                            viewModel.watchedKey(it, normalizedType) in watchedKeys
                                        } == true

                                        val isFirstItem =
                                            section == sections.firstOrNull() &&
                                                studioItem == section.items.firstOrNull()

                                        PosterCard(
                                            posterUrl = studioItem.item.posterPath
                                                ?.let { "${TmdbRepository.POSTER_BASE}$it" },
                                            contentDescription = studioItem.item.title
                                                ?: studioItem.item.name,
                                            isWatched = watched,
                                            onClick = {
                                                viewModel.resolveAndNavigate(
                                                    tmdbId = studioItem.item.id,
                                                    mediaType = rawMediaType,
                                                    onNavigateDetail = onNavigateDetail
                                                )
                                            },
                                            modifier = Modifier
                                                .width(140.dp)
                                                .height(210.dp)
                                                .padding(end = 12.dp)
                                                .then(
                                                    if (isFirstItem) {
                                                        Modifier.focusRequester(firstItemFocusRequester)
                                                    } else {
                                                        Modifier
                                                    }
                                                )
                                        )
                                    }
                                }
                            }
                        }

                        item(key = "back_button") {
                            KBCard(
                                onClick = onBack,
                                modifier = Modifier.padding(top = 8.dp)
                            ) {
                                Text(
                                    text = "BACK",
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(
                                        horizontal = 16.dp,
                                        vertical = 10.dp
                                    )
                                )
                            }
                        }

                        item(key = "bottom_spacer") {
                            Box(modifier = Modifier.height(24.dp))
                        }
                    }
                }
            }
        }
    }
}
