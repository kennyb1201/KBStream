package com.kennyb1201.kbstream.ui.mobile

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kennyb1201.kbstream.data.sync.ProfileManager
import com.kennyb1201.kbstream.ui.components.touch.KBButton
import com.kennyb1201.kbstream.ui.components.touch.KBDialogPanel
import com.kennyb1201.kbstream.ui.simkl.SimklViewModel
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

/**
 * Simkl connect, for a finger.
 *
 * Same activity-scoped [SimklViewModel] as the TV page, so the device-code
 * flow, the polling, the account/counts refresh and the destructive
 * disconnect are the same code, not a second copy:
 *
 *  - CONNECTED: the account name, the watched counts, the continue-watching
 *    fetch state, the view model's status/error lines, and REFRESH / BACK /
 *    DISCONNECT buttons;
 *  - NOT CONNECTED: the verification URL, the code in the same big type, the
 *    status/error lines, and GENERATE CODE / BACK.
 *
 * Both safety rules are kept: a kids profile gets no DISCONNECT button at all
 * (unlinking clears the local watch corpus, which is a parent's action), and
 * DISCONNECT goes through a confirmation that names the wipe, not just the
 * unlink. The TV dialog's focus retry has no touch twin - the buttons are plain
 * tap targets.
 */
@Composable
fun SimklConnectScreenMobile(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    vm: SimklViewModel = viewModel()
) {
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    var showDisconnectConfirm by remember { mutableStateOf(false) }
    // A kids profile never gets the destructive button: `disconnect()` clears
    // every resume position, watched marker and Continue Watching row on this
    // device, and the parent has a profile of their own to do that from.
    val kidsProfile = ProfileManager.activeProfile
        .collectAsStateWithLifecycle().value
        ?.kidsMaxAge != null

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
                        text = "SIMKL CONNECT",
                        color = KBAccent,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )

                    Text(
                        text = "Connect your Simkl account to sync activity and " +
                            "continue watching data.",
                        color = KBTextLo,
                        style = MaterialTheme.typography.bodyMedium
                    )

                    if (uiState.isConnected) {
                        SimklStatusPanel(
                            title = "Account connected",
                            message = uiState.accountName?.let { "Signed in as $it." }
                                ?: "Your Simkl account is connected and ready."
                        )

                        uiState.watchedCounts?.let { counts ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                SimklCountTile(
                                    label = "MOVIES WATCHED",
                                    value = counts.movies,
                                    modifier = Modifier.weight(1f)
                                )
                                SimklCountTile(
                                    label = "SERIES WATCHED",
                                    value = counts.series,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }

                        if (uiState.isLoadingWatching) {
                            Text(
                                text = "Fetching continue watching…",
                                color = KBTextLo,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        uiState.statusMessage?.let { message ->
                            Text(
                                text = message,
                                color = KBTextLo,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        uiState.errorMessage?.let { error ->
                            Text(
                                text = error,
                                color = KBRust,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            KBButton(
                                label = "REFRESH",
                                modifier = Modifier.fillMaxWidth(),
                                onClick = vm::refreshSimklNow
                            )
                            KBButton(
                                label = "BACK",
                                modifier = Modifier.fillMaxWidth(),
                                onClick = onBack
                            )
                            if (!kidsProfile) {
                                KBButton(
                                    label = "DISCONNECT",
                                    danger = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = { showDisconnectConfirm = true }
                                )
                            }
                        }
                    } else {
                        SimklStatusPanel(
                            title = "Device link required",
                            message = "Generate a code, then open the verification " +
                                "URL on your phone or computer and enter the code."
                        )

                        uiState.verificationUrl?.let { url ->
                            SimklInfoBlock(label = "VERIFY AT", value = url)
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

                        uiState.statusMessage?.let { message ->
                            Text(
                                text = message,
                                color = KBTextLo,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        uiState.errorMessage?.let { error ->
                            Text(
                                text = error,
                                color = KBRust,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            KBButton(
                                label = if (uiState.isLoading) "WORKING…" else "GENERATE CODE",
                                enabled = !uiState.isLoading,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = vm::generateCode
                            )
                            KBButton(
                                label = "BACK",
                                modifier = Modifier.fillMaxWidth(),
                                onClick = onBack
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
            }

            if (showDisconnectConfirm) {
                Dialog(onDismissRequest = { showDisconnectConfirm = false }) {
                    KBDialogPanel(title = "Disconnect from Simkl?") {
                        Text(
                            text = "This unlinks your Simkl account and clears every " +
                                "resume position and watched marker stored on this " +
                                "device, along with the launcher's Continue Watching " +
                                "rows. It cannot be undone.",
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
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    showDisconnectConfirm = false
                                    vm.disconnect()
                                }
                            )
                            KBButton(
                                label = "CANCEL",
                                modifier = Modifier.weight(1f),
                                onClick = { showDisconnectConfirm = false }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SimklStatusPanel(title: String, message: String) {
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

@Composable
private fun SimklInfoBlock(label: String, value: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(KBSurfaceRaised, KBShapeCard)
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
private fun SimklCountTile(label: String, value: Int, modifier: Modifier = Modifier) {
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
