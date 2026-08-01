package com.kennyb1201.kbstream.ui.simkl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage

@Composable
fun SimklConnectScreen(
    modifier: Modifier = Modifier,
    vm: SimklViewModel = viewModel()
) {
    val uiState by vm.uiState.collectAsState()
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF101010))
            .verticalScroll(scrollState)
            .padding(32.dp),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.88f)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Connect Simkl",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )

                if (uiState.isConnected) {
                    Text(
                        text = "Your Simkl account is connected.",
                        style = MaterialTheme.typography.bodyLarge
                    )

                    uiState.statusMessage?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    if (uiState.isLoadingWatching) {
                        Text(
                            text = "Loading continue watching...",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    uiState.errorMessage?.let { error ->
                        Text(
                            text = error,
                            color = Color.Red,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    if (uiState.continueWatching.isNotEmpty()) {
                        Text(
                            text = "Continue Watching",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        uiState.continueWatching.forEach { item ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (!item.posterUrl.isNullOrBlank()) {
                                    AsyncImage(
                                        model = item.posterUrl,
                                        contentDescription = item.title,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier
                                            .width(90.dp)
                                            .height(135.dp)
                                    )
                                } else {
                                    Surface(
                                        modifier = Modifier
                                            .width(90.dp)
                                            .height(135.dp)
                                    ) {
                                        BoxPlaceholder(title = item.title)
                                    }
                                }

                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = item.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )

                                    item.year?.let { year ->
                                        Text(
                                            text = year.toString(),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }

                                    item.upNextText?.let { upNext ->
                                        Text(
                                            text = upNext,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }

                                    item.lastWatchedAt?.let { lastWatched ->
                                        Text(
                                            text = "Last watched: $lastWatched",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.LightGray
                                        )
                                    }
                                }
                            }
                        }
                    } else if (!uiState.isLoadingWatching) {
                        Text(
                            text = "No continue watching items found.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(onClick = vm::loadContinueWatching) {
                            Text("Refresh")
                        }

                        Button(onClick = vm::disconnect) {
                            Text("Disconnect")
                        }
                    }
                } else {
                    Text(
                        text = "Generate a code, then open the URL on your phone or computer and enter the code.",
                        style = MaterialTheme.typography.bodyLarge
                    )

                    uiState.verificationUrl?.let { url ->
                        Text(
                            text = "Go to: $url",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    uiState.userCode?.let { code ->
                        Text(
                            text = code,
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    uiState.statusMessage?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    uiState.errorMessage?.let { error ->
                        Text(
                            text = error,
                            color = Color.Red,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    Button(onClick = vm::generateCode) {
                        Text(if (uiState.isLoading) "Working..." else "Generate Code")
                    }
                }
            }
        }
    }
}

@Composable
private fun BoxPlaceholder(title: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF2A2A2A)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "No Poster",
                style = MaterialTheme.typography.bodySmall,
                color = Color.LightGray
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White
            )
        }
    }
}
