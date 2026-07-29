package com.kennyb1201.kbstream.ui.actor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.kennyb1201.kbstream.data.tmdb.TmdbPersonCredit
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import kotlinx.coroutines.launch

@Composable
fun ActorScreen(
    personId: Int,
    onBack: () -> Unit,
    onNavigateDetail: (String, String) -> Unit,
    viewModel: ActorViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val scope = rememberCoroutineScope()
    val person by viewModel.person.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    LaunchedEffect(personId) {
        viewModel.load(personId)
    }

    Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        when {
            isLoading -> Text("Loading...")
            person == null -> Text("Not found")
            else -> {
                val p = person!!
                LazyColumn {
                    item {
                        AsyncImage(
                            model = p.profilePath?.let { "${TmdbRepository.PROFILE_BASE}$it" },
                            contentDescription = p.name,
                            modifier = Modifier.height(220.dp)
                        )
                        Text(p.name, modifier = Modifier.padding(top = 12.dp))
                        p.biography?.takeIf { it.isNotBlank() }?.let {
                            Text(it, modifier = Modifier.padding(top = 8.dp))
                        }
                        Text("Filmography", modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
                    }
                    item {
                        LazyRow {
                            items(p.combinedCredits?.cast.orEmpty()) { credit: TmdbPersonCredit ->
                                Card(
                                    onClick = {
                                        val mediaType = if (credit.mediaType == "tv") "series" else "movie"
                                        scope.launch {
                                            val imdbId = viewModel.resolveImdbId(credit.id, mediaType)
                                            if (imdbId != null) onNavigateDetail(mediaType, imdbId)
                                        }
                                    },
                                    modifier = Modifier.width(110.dp).height(160.dp).padding(end = 10.dp)
                                ) {
                                    AsyncImage(
                                        model = credit.posterPath?.let { "${TmdbRepository.PROFILE_BASE}$it" },
                                        contentDescription = credit.title ?: credit.name,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }
                        }
                    }
                    item {
                        Card(
                            onClick = onBack,
                            colors = CardDefaults.colors(containerColor = Color(0xFF1B3A57), contentColor = Color.White),
                            modifier = Modifier.padding(top = 20.dp)
                        ) {
                            Text("Back", modifier = Modifier.padding(12.dp))
                        }
                    }
                }
            }
        }
    }
}
