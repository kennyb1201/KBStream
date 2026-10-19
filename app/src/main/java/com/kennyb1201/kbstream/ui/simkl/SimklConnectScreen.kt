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
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.data.simkl.SimklWatchedCounts
import com.kennyb1201.kbstream.ui.browse.landFocusOn
import com.kennyb1201.kbstream.ui.components.KBButton
import com.kennyb1201.kbstream.ui.components.KBDialogPanel
import com.kennyb1201.kbstream.ui.components.KBPageTitle
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBRust
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBShapePanel
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid

@Composable
fun SimklConnectScreen(
    modifier: Modifier = Modifier,
    // Just "back": the page carries its own return destination now (Settings'
    // tracker row and onboarding's setup card open it from different places),
    // so a label naming one of them was wrong on the other.
    onBack: () -> Unit,
    vm: SimklViewModel = viewModel()
) {
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()
    // Raised by DISCONNECT; see the button's own note for what it is protecting.
    var showDisconnectConfirm by remember { mutableStateOf(false) }
    // m10 (audit): unlinking wipes the local watch corpus, so a kids profile
    // does not get the button at all - confirmation or not, that is a parent's
    // action, and the parent has a profile of their own to do it from.
    val kidsProfile = com.kennyb1201.kbstream.data.sync.ProfileManager
        .activeProfile.collectAsStateWithLifecycle().value
        ?.kidsMaxAge != null

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
                    .background(KBSurface, KBShapePanel)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // The screen's name, through the shared page title (Batch F
                // item 38), accent-colored like Library's: it was a labelLarge
                // caption, so this screen - the one place the app asks the
                // viewer to connect an account - named itself smaller than a
                // rail heading did.
                KBPageTitle(text = "SIMKL CONNECT", color = KBAccent)

                Text(
                    text = "Connect your Simkl account to sync activity and continue watching data.",
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodyMedium
                )

                if (uiState.isConnected) {
                    SimklStatusPanel(
                        title = "Account connected",
                        message = uiState.accountName?.let {
                            "Signed in as $it."
                        } ?: "Your Simkl account is connected and ready."
                    )

                    uiState.watchedCounts?.let { counts ->
                        SimklWatchedCountsRow(counts = counts)
                    }

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
                        // The app's one button (KBButton), not a hand-rolled
                        // KBCard + titleMedium label: the same "REFRESH" was a
                        // size bigger and a pixel taller here than in every
                        // other panel.
                        KBButton(label = "REFRESH", onClick = vm::refreshSimklNow)

                        KBButton(label = "BACK", onClick = onBack)

                        // The destructive one carries the danger tint: it was
                        // drawn in the same bright label as REFRESH, so the row
                        // that unlinks the account looked like the row that
                        // refreshes it. It also asks first now: `disconnect()`
                        // does not merely unlink the account — it clears every
                        // local resume position, watched marker and completed
                        // marker (and the TV launcher's rows with them), which
                        // is the entire local watch corpus. One tap with no
                        // confirmation, under a label that reads like an
                        // unlink, was the app's most destructive button. Its
                        // sibling "Clear Continue Watching" in Settings has
                        // always confirmed; this now matches it.
                        if (!kidsProfile) {
                            KBButton(
                                label = "DISCONNECT",
                                danger = true,
                                onClick = { showDisconnectConfirm = true }
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
                        SimklStatusLine(message)
                    }

                    uiState.errorMessage?.let { error ->
                        SimklErrorLine(error)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        KBButton(
                            label = if (uiState.isLoading) "WORKING…" else "GENERATE CODE",
                            onClick = vm::generateCode
                        )

                        KBButton(label = "BACK", onClick = onBack)
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }

    if (showDisconnectConfirm) {
        DisconnectConfirmDialog(
            onConfirm = {
                showDisconnectConfirm = false
                vm.disconnect()
            },
            onDismiss = { showDisconnectConfirm = false }
        )
    }
}

/**
 * The DISCONNECT gate.
 *
 * Wording names the wipe, not just the unlink, because that is what the button
 * actually does (see SimklViewModel.disconnect). CANCEL takes focus through the
 * app's own verified retry ([landFocusOn]) — the dialog enters the tree this
 * same frame and a fresh FocusRequester answers false until it is attached.
 */
@Composable
private fun DisconnectConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val cancelFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { landFocusOn(cancelFocusRequester) }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        KBDialogPanel(title = "Disconnect from Simkl?") {
            Text(
                text = "This unlinks your Simkl account and clears every resume " +
                    "position and watched marker stored on this device, along " +
                    "with the launcher's Continue Watching rows. It cannot be " +
                    "undone.",
                color = KBTextLo,
                style = MaterialTheme.typography.bodyMedium
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 8.dp)
            ) {
                KBButton(label = "DISCONNECT", danger = true, onClick = onConfirm)
                KBButton(
                    label = "CANCEL",
                    modifier = Modifier.focusRequester(cancelFocusRequester),
                    onClick = onDismiss
                )
            }
        }
    }
}

@Composable
private fun SimklWatchedCountsRow(counts: SimklWatchedCounts) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        SimklWatchedCountTile(
            label = "MOVIES WATCHED",
            value = counts.movies,
            modifier = Modifier.weight(1f)
        )
        SimklWatchedCountTile(
            label = "SERIES WATCHED",
            value = counts.series,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SimklWatchedCountTile(
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
private fun SimklStatusPanel(
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

@Composable
private fun SimklInfoBlock(
    label: String,
    value: String
) {
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
        color = KBRust,
        style = MaterialTheme.typography.bodyMedium
    )
}
