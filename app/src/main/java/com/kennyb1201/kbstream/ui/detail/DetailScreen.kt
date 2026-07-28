package com.kennyb1201.kbstream.ui.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.Text
import coil3.compose.AsyncImage

@Composable
fun DetailScreen(
    type: String,
    id: String,
    viewModel: DetailViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    LaunchedEffect(id) {
        viewModel.load(type, id)
    }

    val meta by viewModel.meta.collectAsState()
    val streams by viewModel.streams.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        when {
            isLoading -> Text("Loading...")
            error != null -> Text("Error: $error")
            meta != null -> {
                val m = meta!!
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item {
                        AsyncImage(
                            model = m.background ?: m.poster,
                            contentDescription = m.name,
                            modifier = Modifier.fillMaxWidth().height(200.dp)
                        )
                        Text(m.name, modifier = Modifier.padding(top = 12.dp))

                        val metaLine = listOfNotNull(
                            m.releaseInfo,
                            m.runtime,
                            m.imdbRating?.let { "IMDb $it" }
                        ).joinToString("  •  ")
                        if (metaLine.isNotBlank()) {
                            Text(metaLine, modifier = Modifier.padding(top = 4.dp))
                        }

                        m.genres?.takeIf { it.isNotEmpty() }?.let {
                            Text(it.joinToString(", "), modifier = Modifier.padding(top = 4.dp))
                        }

                        m.description?.let {
                            Text(it, modifier = Modifier.padding(top = 12.dp))
                        }

                        m.director?.takeIf { it.isNotEmpty() }?.let {
                            Text("Director: ${it.joinToString(", ")}", modifier = Modifier.padding(top = 12.dp))
                        }
                        m.cast?.takeIf { it.isNotEmpty() }?.let {
                            Text("Cast: ${it.joinToString(", ")}", modifier = Modifier.padding(top = 4.dp))
                        }
                        m.country?.let {
                            Text("Country: $it", modifier = Modifier.padding(top = 4.dp))
                        }
                        m.language?.let {
                            Text("Language: $it", modifier = Modifier.padding(top = 4.dp))
                        }
                        m.awards?.let {
                            Text("Awards: $it", modifier = Modifier.padding(top = 4.dp))
                        }

                        Text(
                            if (streams.isEmpty()) "No stream source configured yet" else "Streams",
                            modifier = Modifier.padding(top = 20.dp, bottom = 8.dp)
                        )
                    }
                    items(streams) { stream ->
                        Card(
                            onClick = { /* TODO: play stream */ },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        ) {
                            Text(
                                stream.title ?: stream.name ?: "Unnamed stream",
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
