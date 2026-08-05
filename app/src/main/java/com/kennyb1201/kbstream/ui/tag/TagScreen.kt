package com.kennyb1201.kbstream.ui.tag

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.ui.components.PosterCard
import com.kennyb1201.kbstream.ui.theme.KBTextLo

@Composable
fun TagScreen(
    tagId: Int,
    tagName: String,
    isKeyword: Boolean,
    onNavigateDetail: (String, String) -> Unit,
    viewModel: TagViewModel = viewModel()
) {
    val sections by viewModel.sections.collectAsState()
    val watchedKeys by viewModel.watchedKeys.collectAsState()
    val resolvedIds by viewModel.resolvedIds.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    LaunchedEffect(tagId, isKeyword) {
        if (isKeyword) {
            viewModel.loadKeyword(tagId)
        } else {
            viewModel.loadGenre(tagId)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 16.dp)
    ) {
        Text(
            text = tagName.uppercase(),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(start = 24.dp, bottom = 12.dp)
        )

        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
            ) {
                Text("Loading...")
            }
            return@Column
        }

        if (sections.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp)
            ) {
                Text("Nothing found for this tag.")
            }
            return@Column
        }

        LazyColumn {
            items(sections.size, key = { sections[it].title }) { sectionIndex ->
                val section = sections[sectionIndex]

                Text(
                    text = section.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = KBTextLo,
                    modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 8.dp)
                )

                LazyRow(
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp)
                ) {
                    itemsIndexed(
                        items = section.items,
                        key = { _, item -> "${item.mediaType.lowercase()}::${item.item.id}" }
                    ) { index, studioItem ->
                        if (
                            index >= section.items.lastIndex - 6 &&
                            !section.isLoadingMore &&
                            section.hasMore
                        ) {
                            LaunchedEffect(section.title, section.items.size) {
                                viewModel.loadMore(section.title)
                            }
                        }

                        val mediaType = studioItem.mediaType.lowercase()
                        val imdbId = resolvedIds[
                            viewModel.lookupKey(studioItem.item.id, mediaType)
                        ]

                        val watched = imdbId?.let {
                            viewModel.watchedKey(it, mediaType) in watchedKeys
                        } == true

                        PosterCard(
                            posterUrl = studioItem.item.posterPath?.let {
                                TmdbRepository.PROFILE_BASE + it
                            },
                            contentDescription = studioItem.item.title ?: studioItem.item.name,
                            isWatched = watched,
                            onClick = {
                                viewModel.resolveAndNavigate(
                                    studioItem.item.id,
                                    mediaType,
                                    onNavigateDetail
                                )
                            },
                            modifier = Modifier
                                .width(124.dp)
                                .height(180.dp)
                                .padding(end = 12.dp)
                        )
                    }

                    if (section.isLoadingMore) {
                        item(key = section.title + "_loading") {
                            Box(
                                modifier = Modifier
                                    .width(124.dp)
                                    .height(180.dp)
                                    .padding(end = 12.dp)
                            ) {
                                Text(
                                    text = "Loading...",
                                    color = KBTextLo,
                                    modifier = Modifier.padding(12.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
