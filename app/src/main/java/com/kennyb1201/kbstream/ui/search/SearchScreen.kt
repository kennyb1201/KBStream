package com.kennyb1201.kbstream.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.data.addon.MetaPreview
import com.kennyb1201.kbstream.ui.components.PosterCard

@Composable
fun SearchScreen(
    onItemClick: (MetaPreview) -> Unit,
    viewModel: SearchViewModel = viewModel()
) {
    var query by remember { mutableStateOf("") }
    val results by viewModel.results.collectAsStateWithLifecycle()
    val watchedKeys by viewModel.watchedKeys.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Column {
            Text("Search", modifier = Modifier.padding(bottom = 12.dp))

            BasicTextField(
                value = query,
                onValueChange = { 
                    query = it
                    viewModel.search(it)
                },
                singleLine = true,
                textStyle = TextStyle(color = Color.White),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1B3A57))
                    .padding(12.dp)
            )

            if (isLoading) {
                Text(
                    "Searching...",
                    color = Color.LightGray,
                    modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)
                )
            }

            LazyColumn(
                modifier = Modifier.padding(top = 16.dp)
            ) {
                items(
                    items = results,
                    key = { it.id }
                ) { meta ->
                    val watched = viewModel.watchedKey(meta.id, meta.type) in watchedKeys

                    Card(
                        onClick = { onItemClick(meta) },
                        colors = CardDefaults.colors(
                            containerColor = Color(0xFF1B3A57),
                            contentColor = Color.White
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                    ) {
                        Row(modifier = Modifier.padding(12.dp)) {
                            PosterCard(
                                posterUrl = meta.poster,
                                contentDescription = meta.name,
                                isWatched = watched,
                                onClick = { onItemClick(meta) },
                                modifier = Modifier
                                    .width(90.dp)
                                    .height(135.dp)
                            )

                            Column(
                                modifier = Modifier
                                    .padding(start = 12.dp)
                                    .fillMaxWidth()
                            ) {
                                Text(
                                    text = meta.name,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )

                                Text(
                                    text = meta.type.uppercase(),
                                    color = Color.LightGray,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
