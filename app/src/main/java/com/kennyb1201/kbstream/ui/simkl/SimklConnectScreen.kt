package com.kennyb1201.kbstream.ui.simkl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
    onBackToHome: () -> Unit,
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

                    if (uiState.isLoadingWatching) {
                        Text(
                            text = "Fetching continue watching…",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.LightGray
                        )
                    }

                    uiState.statusMessage?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White
                        )
                    }

                    uiState.errorMessage?.let { error ->
                        Text(
                            text = error,
                            color = Color.Red,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Button(onClick = vm::refreshSimklNow) {
                            Text("Refresh")
                        }

                        Button(onClick = onBackToHome) {
                            Text("Back to Ho