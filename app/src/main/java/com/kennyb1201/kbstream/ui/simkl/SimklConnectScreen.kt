package com.kennyb1201.kbstream.ui.simkl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text

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
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.7f)
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
                            text = "Loading your watching list...",
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

                    if (uiState.watching.isNotEmpty()) {
                        Text(
                            text = "Watching now:",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        uiState.watching.take(10).forEach { item ->
                            val title = item.show?.title ?: "Untitled show"
                            val status = item.status ?: "unknown"

                            Text(
                                text = "$title ($status)",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }

                    Button(onClick = vm::loadWatchingShows) {
                        Text("Refresh Watching")
                    }

                    Button(onClick = vm::disconnect) {
                        Text("Disconnect")
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
