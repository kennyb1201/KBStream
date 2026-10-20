package com.kennyb1201.kbstream.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kennyb1201.kbstream.data.trakt.TraktWatchedCounts
import com.kennyb1201.kbstream.ui.components.KBQrCode
import com.kennyb1201.kbstream.ui.components.touch.KBButton
import com.kennyb1201.kbstream.ui.components.touch.KBDialogPanel
import com.kennyb1201.kbstream.ui.components.touch.KBTextField
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBRust
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBShapePanel
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import com.kennyb1201.kbstream.ui.theme.MobileTheme
import com.kennyb1201.kbstream.ui.trakt.TraktViewModel

/**
 * Trakt connect, for a finger.
 *
 * The same activity-scoped [TraktViewModel] as the TV page, so the device-code
 * request, the poll loop, the parent-PIN gate and the disconnect are one
 * implementation with two layouts - not a copy. The only touch-specific parts
 * are the widgets (the touch twins) and the absence of D-pad focus plumbing.
 *
 * DISCONNECT keeps a confirmation even though this one no longer wipes local
 * data: unlinking an account by mis-tap is still worth one question, and the
 * dialog says what is kept.
 */
@Composable
fun TraktConnectScreenMobile(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    vm: TraktViewModel = viewModel()
) {
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    var showDisconnectConfirm by remember { mutableStateOf(false) }

    // Once per entry into the connected state, never per recomposition.
    LaunchedEffect(uiState.isConnected) {
        if (uiState.isConnected) vm.refreshWatchedCounts()
    }

    MobileTheme {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(KBVoid)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(KBSurface, KBShapePanel)
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        text = "TRAKT CONNECT",
                        color = KBAccent,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )

                    Text(
                        text = "Connect your Trakt account to scrobble playback and " +
                            "keep watched state in sync. Your watch history stays on " +
                            "this device if you disconnect.",
                        color = KBTextLo,
                        style = MaterialTheme.typography.bodyMedium
                    )

                    when {
                        !uiState.isConfigured -> {
                            TraktStatusPanel(
                                title = "Not available in this build",
                                message = "This build has no Trakt client id, so Trakt " +
                                    "cannot be connected. Set TRAKT_CLIENT_ID for the " +
                                    "build and reinstall."
                            )
                            KBButton(
                                label = "BACK",
                                modifier = Modifier.fillMaxWidth(),
                                onClick = onBack
                            )
                        }

                        uiState.isConnected -> {
                            TraktStatusPanel(
                                title = "Account connected",
                                message = uiState.accountSlug?.let { "Signed in as $it." }
                                    ?: "Your Trakt account is connected and ready."
                            )
                            uiState.watchedCounts?.let { counts ->
                                TraktWatchedCountsRow(counts = counts)
                            }

                            uiState.statusMessage?.let { TraktLine(it) }
                            uiState.errorMessage?.let { TraktErrorLine(it) }

                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                KBButton(
                                    label = "DISCONNECT",
                                    danger = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = { showDisconnectConfirm = true }
                                )
                                KBButton(
                                    label = "BACK",
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = onBack
                                )
                            }
                        }

                        uiState.requiresParentPin -> {
                            TraktParentPinGateMobile(vm = vm, onBack = onBack)
                        }

                        else -> {
                            TraktStatusPanel(
                                title = "Device link required",
                                message = "Generate a code, open the verification " +
                                    "address on your phone or computer, and enter the code."
                            )

                            // Linking only: the connected branch has no URL.
                            // Bigger than the TV panel: this page scrolls by
                            // finger, so height is not the constraint.
                            uiState.verificationUrl?.let { url ->
                                KBQrCode(url = url, size = 200.dp)
                            }

                            uiState.userCode?.let { code ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(KBSurfaceRaised, KBShapeCard)
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

                            uiState.statusMessage?.let { TraktLine(it) }
                            uiState.errorMessage?.let { TraktErrorLine(it) }

                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                KBButton(
                                    label = if (uiState.isLoading || uiState.isPolling) {
                                        "WORKING…"
                                    } else {
                                        "GENERATE CODE"
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = vm::startConnect
                                )
                                KBButton(
                                    label = "BACK",
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = onBack
                                )
                            }
                        }
                    }
                }
            }
        }

        if (showDisconnectConfirm) {
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { showDisconnectConfirm = false }
            ) {
                KBDialogPanel(title = "Disconnect from Trakt?") {
                    Text(
                        text = "This unlinks your Trakt account. Your local watch " +
                            "history, resume positions and watched badges are kept.",
                        color = KBTextLo,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        KBButton(
                            label = "DISCONNECT",
                            danger = true,
                            onClick = {
                                showDisconnectConfirm = false
                                vm.disconnect()
                            }
                        )
                        KBButton(
                            label = "CANCEL",
                            onClick = { showDisconnectConfirm = false }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TraktParentPinGateMobile(
    vm: TraktViewModel,
    onBack: () -> Unit
) {
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    var pin by remember { mutableStateOf("") }

    TraktStatusPanel(
        title = "Parent PIN required",
        message = "Linking an account on a kids profile is a parent's action."
    )
    KBTextField(
        value = pin,
        onValueChange = {
            pin = it.filter(Char::isDigit).take(4)
            vm.clearError()
        },
        placeholder = "••••",
        keyboardType = KeyboardType.NumberPassword,
        visualTransformation = PasswordVisualTransformation()
    )
    if (uiState.pinError) {
        TraktErrorLine("Wrong PIN — try again")
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        KBButton(
            label = "UNLOCK",
            modifier = Modifier.fillMaxWidth(),
            onClick = { vm.submitParentPin(pin) }
        )
        KBButton(
            label = "CANCEL",
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                vm.cancelParentPin()
                onBack()
            }
        )
    }
}

@Composable
private fun TraktStatusPanel(
    title: String,
    message: String
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(KBSurfaceRaised, KBShapeCard)
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

/** Simkl's two tiles in the same shape; Trakt's own noun is SHOWS. */
@Composable
private fun TraktWatchedCountsRow(counts: TraktWatchedCounts) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        TraktWatchedCountTile(
            label = "MOVIES WATCHED",
            value = counts.movies,
            modifier = Modifier.weight(1f)
        )
        TraktWatchedCountTile(
            label = "SHOWS WATCHED",
            value = counts.shows,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun TraktWatchedCountTile(
    label: String,
    value: Int,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .background(KBSurfaceRaised, KBShapeCard)
            .padding(horizontal = 14.dp, vertical = 14.dp)
    ) {
        Text(
            text = value.toString(),
            color = KBAccent,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Text(
            text = label,
            color = KBTextLo,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

@Composable
private fun TraktLine(message: String) {
    Text(
        text = message,
        color = KBTextLo,
        style = MaterialTheme.typography.bodyMedium
    )
}

@Composable
private fun TraktErrorLine(message: String) {
    Text(
        text = message,
        color = KBRust,
        style = MaterialTheme.typography.bodyMedium
    )
}
