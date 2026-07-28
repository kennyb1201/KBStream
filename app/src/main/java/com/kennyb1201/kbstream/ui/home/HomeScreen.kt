package com.kennyb1201.kbstream.ui.home

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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.kennyb1201.kbstream.data.addon.MetaPreview

@Composable
fun HomeScreen(
    onItemClick: (MetaPreview) -> Unit,
    onManageAddons: () -> Unit,
    onSearch: () -> Unit = {},
    viewModel: HomeViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val rails by viewModel.rails.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Column {
            Card(
                onClick = onManageAddons,
                colors = CardDefaults.colors(containerColor = Color(0xFF1B3A57), contentColor = Color.White),
                modifier = Modifier.padding(bottom = 16.dp)
            ) {
                Text("Manage Add-ons", modifier = Modifier.padding(12.dp))
            }

            when {
                isLoading -> Text("Loading catalogs...")
                error != null -> Text("Error: $error")
                rails.isEmpty() -> Text("No catalogs available. Add an addon to get started.")
                else -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(rails) { rail ->
                        Column(modifier = Modifier.padding(bottom = 20.dp)) {
                            Text("${rail.catalogName} (${rail.addonName})", modifier = Modifier.padding(bottom = 8.dp))
                            LazyRow {
                                items(rail.items) { meta ->
                                    Card(
                                        onClick = { onItemClick(meta) },
                                        modifier = Modifier
                                            .width(140.dp)
                                            .height(220.dp)
                                            .padding(end = 12.dp)
                                    ) {
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
    }
}
