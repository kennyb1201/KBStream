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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.data.tmdb.StudioItem
import com.kennyb1201.kbstream.data.tmdb.StudioSection
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.components.PosterCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun StudioScreen(
    id: Int,
    name: String,
    isNetwork: Boolean,
    onNavigateDetail: (String, String) -> Unit,
    viewModel: StudioViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val scope = rememberCoroutineScope()
    val sections by viewModel.sections.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val firstItemFocusRequester = remember { FocusRequester() }

    LaunchedEffect(id) {
        viewModel.load(id, isNetwork)
    }

    LaunchedEffect(sections) {
        if (sections.isNotEmpty()) {
            delay(100)
            runCatching { firstItemFocusRequester.requestFocus() }
        }
    }

    Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Column {
            Text(name, style = MaterialTheme.typography.displayLarge, modifier = Modifier.padding(bottom = 16.dp))

            when {
                isLoading -> Text("Loading...")
                sections.isEmpty() -> Text("Nothing found for $name")
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(sections) { sectionIndex, section: StudioSection ->
                        Column(modifier = Modifier.padding(bottom = 20.dp)) {
                            Text(
                                section.title,
                                style = MaterialTheme.typography.titleMedium,
                                color = KBTextLo,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                            LazyRow {
                                itemsIndexed(section.items) { itemIndex, studioItem: StudioItem ->
                                    PosterCard(
    posterUrl = studioItem.item.posterPath?.let { "${TmdbRepository.PROFILE_BASE}$it" },
    contentDescription = studioItem.item.title ?: studioItem.item.name,
    isWatched = false,
    onClick = {
        scope.launch {
            val imdbId = viewModel.resolveImdbId(studioItem.item.id, studioItem.mediaType)
            if (imdbId != null) onNavigateDetail(studioItem.mediaType, imdbId)
        }
    },
    modifier = Modifier
        .width(140.dp)
        .height(210.dp)
        .padding(end = 12.dp)
        .let {
            if (sectionIndex == 0 && itemIndex == 0) it.focusRequester(firstItemFocusRequester) else it
        }
)
     {
                                    
                                        
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
