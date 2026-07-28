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
            meta != null -> Column {
                AsyncImage(
                    model = meta!!.background ?: meta!!.poster,
                    contentDescription = meta!!.name,
                    modifier = Modifier.fillMaxWidth().height(200.dp)
                )
                Text(meta!!.name, modifier = Modifier.padding(top = 12.dp))
                meta!!.description?.let {
                    Text(it, modifier = Modifier.padding(top = 8.dp))
                }
                Text("Streams", modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
                LazyColumn {
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
