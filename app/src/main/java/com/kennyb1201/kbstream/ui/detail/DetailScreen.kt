package com.kennyb1201.kbstream.ui.detail

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

@Composable
fun DetailScreen(
    viewModel: DetailViewModel,
    onNavigateDetail: (String, String) -> Unit,
    onNavigateStream: (String, Int, Int) -> Unit
) {
    val scope = rememberCoroutineScope()
    val recs by viewModel.recommendations.collectAsState(initial = emptyList())
    val watchProgress by viewModel.watchProgress.collectAsState(initial = null)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Action Buttons Row (Trailer & Dynamic Play/Resume)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { viewModel.playTrailer() }
            ) {
                Text("Trailer")
            }

            Button(
                onClick = { 
                    val season = watchProgress?.season ?: 1
                    val episode = watchProgress?.episode ?: 1
                    viewModel.playContent(season, episode)
                }
            ) {
                val buttonText = if (watchProgress != null && (watchProgress!!.season > 0 || watchProgress!!.episode > 0)) {
                    "Resume S${watchProgress!!.season} E${watchProgress!!.episode}"
                } else {
                    "Play S1 E1"
                }
                Text(buttonText)
            }
        }

        // Recommendations Row
        if (recs.isNotEmpty()) {
            Text("MORE LIKE THIS", style = MaterialTheme.typography.titleMedium)
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, top = 20.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(recs.take(15)) { rec ->
                    K8Card(
                        onClick = {
                            scope.launch {
                                val imdbId = viewModel.resolveImdbId(rec.id, "series")
                                if (imdbId != null) onNavigateDetail("series", imdbId)
                            }
                        },
                        modifier = Modifier.width(120.dp).height(170.dp).padding(end = 10.dp)
                    ) {
                        AsyncImage(
                            model = rec.posterPath?.let { "${TmdbRepository.PROFILE_BASE}$it" },
                            contentDescription = rec.title ?: rec.name,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}
