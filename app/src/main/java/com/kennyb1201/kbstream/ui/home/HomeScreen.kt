package com.kennyb1201.kbstream.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.kennyb1201.kbstream.data.addon.MetaPreview

@Composable
fun HomeScreen(
    onItemClick: (MetaPreview) -> Unit,
    viewModel: HomeViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val catalog by viewModel.catalog.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        when {
            isLoading -> Text("Loading catalog...")
            error != null -> Text("Error: $error")
            else -> Column {
                Text("Top Movies", modifier = Modifier.padding(bottom = 12.dp))
                LazyRow {
                    items(catalog) { meta ->
                        Card(
                            onClick = { onItemClick(meta) },
                            modifier = Modifier
                                .width(140.dp)
                                .height(220.dp)
                                .padding(end = 12.dp)
                        ) {
                            Column {
                                AsyncImage(
                                    model = meta.poster,
                                    contentDescription = meta.name,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
