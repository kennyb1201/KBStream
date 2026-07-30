package com.kennyb1201.kbstream.ui.detail

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

@Composable
fun DetailScreen(
    type: String,
    id: String,
    onNavigateDetail: (String, String) -> Unit,
    onNavigateActor: (Long) -> Unit,
    onNavigateStudio: (Long, String, Boolean) -> Unit
) {
    val scope = rememberCoroutineScope()
    val watchProgress by remember { mutableStateOf<PreserveProgress?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { }
            ) {
                Text("Trailer")
            }

            Button(
                onClick = { 
                    val season = watchProgress?.season ?: 1
                    val episode = watchProgress?.episode ?: 1
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
    }
}

data class PreserveProgress(val season: Int, val episode: Int)
