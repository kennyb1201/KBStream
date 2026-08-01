package com.kennyb1201.kbstream.ui.simkl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun SimklConnectScreen(
    modifier: Modifier = Modifier,
    vm: SimklViewModel = viewModel()
) {
    val uiState by vm.uiState.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.7f),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Connect Simkl",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                if (uiState.isConnected) {
                    Text(
                        text = "Your Simkl account is connected.",
                        style = MaterialTheme.typography.bodyLarge
                    )

                    uiState.statusMessage?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    Button(
                        onClick = vm::disconnect
                    ) {
                        Text("Disconnect")
                    }
                } else {
                    Text(
                        text = "Generate a code, then open the URL on your phone or computer and enter the code.",
                        style = MaterialTheme.typography.bodyLarge
                    )

                    uiState.verificationUrl?.let {
                        Text(
                            text = "Go to: $it",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    uiState.userCode?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    uiState.statusMessage?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    uiState.errorMessage?.let {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    Button(
                        onClick = vm::generateCode
                    ) {
                        Text(
                            if (uiState.isLoading) "Working..." else "Generate Code"
                        )
                    }
                }
            }
        }
    }
}
