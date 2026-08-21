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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid

@Composable
fun SimklConnectScreen(
    modifier: Modifier = Modifier,
    onBackToHome: () -> Unit,
    vm: SimklViewModel = viewModel()
) {
    val uiState by vm.uiState.collectAsState()
    val scrollState = rememberScrollState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(KBVoid)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            KBSurface.copy(alpha = 0.92f),
                            KBVoid.copy(alpha = 0.98f),
                            KBVoid
                        )
                    )
                )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 760.dp)
                    .background(KBSurface, RoundedCornerShape(18.dp))
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = "SIMKL CONNECT",
                    color = KBAccent,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = "Connect your Simkl account to sync activity and continue watching data.",
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodyMedium
                )

                if (uiState.isConnected) {
                    SimklStatusPanel(
                        title = "Account connected",
                        message = "Your Simkl account is connected and ready."
                    )

                    if (uiState.isLoadingWatching) {
                        SimklStatusLine("Fetching continue watching…")
                    }

                    uiState.statusMessage?.let { message ->
                        SimklStatusLine(message)
                    }

                    uiState.errorMessage?.let { error ->
                        SimklErrorLine(error)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        KBCard(onClick = vm::refreshSimklNow) {
                            Text(
                                text = "REFRESH",
                                color = KBTextHi,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp)
                            )
                        }

                        KBCard(onClick = onBackToHome) {
                            Text(
                                text = "BACK TO HOME",
                                color = KBTextHi,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp)
                            )
                        }

                        KBCard(onClick = vm::disconnect) {
                            Text(
                                text = "DISCONNECT",
                                color = KBTextHi,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp)
                            )
                        }
                    }
                } else {
                    SimklStatusPanel(
                        title = "Device link required",
                        message = "Generate a code, then open the verification URL on your phone or computer and enter the code."
                    )

                    uiState.verificationUrl?.let { url ->
                        SimklInfoBlock(
                            label = "VERIFY AT",
                            value = url
                        )
                    }

                    uiState.userCode?.let { code ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(KBSurfaceRaised, RoundedCornerShape(14.dp))
                                .padding(horizontal = 18.dp, vertical = 20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "CODE",
                                color = KBAccent,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = code,
                                color = KBTextHi,
                                style = MaterialTheme.typography.displaySmall,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                    }

                    uiState.statusMessage?.let { message ->
                        SimklStatusLine(message)
                    }

                    uiState.errorMessage?.let { error ->
                        SimklErrorLine(error)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        KBCard(onClick = vm::generateCode) {
                            Text(
                                text = if (uiState.isLoading) "WORKING..." else "GENERATE CODE",
                                color = KBTextHi,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp)
                            )
                        }

                        KBCard(onClick = onBackToHome) {
                            Text(
                                text = "BACK TO HOME",
                                color = KBTextHi,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 11.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SimklStatusPanel(
    title: String,
    message: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(KBSurfaceRaised, RoundedCornerShape(14.dp))
            .padding(16.dp)
    ) {
        Text(
            text = title.uppercase(),
            color = KBTextHi,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = message,
            color = KBTextLo,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
private fun SimklInfoBlock(
    label: String,
    value: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(KBSurfaceRaised, RoundedCornerShape(14.dp))
            .padding(16.dp)
    ) {
        Text(
            text = label,
            color = KBAccent,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = value,
            color = KBTextHi,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

@Composable
private fun SimklStatusLine(message: String) {
    Text(
        text = message,
        color = KBTextLo,
        style = MaterialTheme.typography.bodyMedium
    )
}

@Composable
private fun SimklErrorLine(message: String) {
    Text(
        text = message,
        color = Color(0xFFFFB74D),
        style = MaterialTheme.typography.bodyMedium
    )
}
