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
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.kennyb1201.kbstream.data.tmdb.StudioSection
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.theme.KBTextLo

@Composable
fun TagScreen(
    tagId: Int,
    tagName: String,
    isKeyword: Boolean,
    onNavigateDetail: (String, String) -> Unit,
    viewModel: TagViewModel = viewModel()
) {
    var sections by remember { mutableStateOf<List<StudioSection>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(tagId, isKeyword) {
        isLoading = true
        sections = if (isKeyword) viewModel.loadByKeyword(tagId) else viewModel.loadByGenre(tagId)
        isLoading = false
    }

    Column(modifier = Modifier.fillMaxSize().padding(top = 16.dp)) {
        Text(
            tagName.uppercase(),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(start = 24.dp, bottom = 12.dp)
        )

        if (isLoading) {
            Box(Modifier.fillMaxSize().padding(24.dp)) { Text("Loading...") }
            return@Column
        }

        if (sections.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp)) { Text("Nothing found for this tag.") }
            return@Column
        }

        LazyColumn {
            items(items = sections, key = { it.title }) { section ->
                Text(
                    section.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = KBTextLo,
                    modifier = Modifier.padding(start = 24.dp, top = 16.dp, bottom = 8.dp)
                )
                LazyRow(
                    contentPadding = PaddingValues(start = 24.dp, end = 24.dp)
                ) {
                    items(items = section.items, key = { it.item.id }) { studioItem ->
                        KBCard(
                            onClick = {
                                viewModel.resolveAndNavigate(studioItem.item.id, studioItem.mediaType, onNavigateDetail)
                            },
                            modifier = Modifier.width(124.dp).height(180.dp).padding(end = 12.dp)
                        ) {
                            AsyncImage(
                                model = studioItem.item.posterPath?.let { TmdbRepository.PROFILE_BASE + it },
                                contentDescription = studioItem.item.title ?: studioItem.item.name,
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
