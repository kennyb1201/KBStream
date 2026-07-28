package com.kennyb1201.kbstream.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.data.addon.MetaPreview

@Composable
fun SearchScreen(
    onItemClick: (MetaPreview) -> Unit,
    viewModel: SearchViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    var query by remember { mutableStateOf("") }
    val results by viewModel.results.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    Box(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Column {
            Text("Search", modifier = Modifier.padding(bottom = 12.dp))
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = Color.White),
                modifier = Modifier.fillMaxWidth().background(Color(0xFF1B3A57)).padding(12.dp)
            )
            Card(
                onClick = { viewModel.search(query) },
                colors = CardDefaults.colors(containerColor = Color(0xFF4FC3F7), contentColor = Color.Black),
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
            ) {
                Text(if (isLoading) "Searching..." else "Search", modifier = Modifier.padding(12.dp))
            }
            LazyColumn {
                items(results) { meta ->
                    Card(
                        onClick = { onItemClick(meta) },
                        colors = CardDefaults.colors(containerColor = Color(0xFF1B3A57), contentColor = Color.White),
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    ) {
                        Text(meta.name, modifier = Modifier.padding(12.dp))
                    }
                }
            }
        }
    }
}
