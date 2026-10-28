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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kennyb1201.kbstream.data.sync.ProfileManager
import com.kennyb1201.kbstream.ui.components.KBQrCode
import com.kennyb1201.kbstream.ui.components.touch.KBButton
import com.kennyb1201.kbstream.ui.components.touch.KBDialogPanel
import com.kennyb1201.kbstream.ui.components.touch.KBTextField
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
    // A kids profile never gets the account controls: linking or unlinking the
    // household's tracker account is the parent's decision, and they have a
    // profile of their own to do it from. (This used to be phrased as "the
    // destructive button" because disconnect() also wiped local history - it
    // no longer does, see SimklViewModel.disconnect.)
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
                // The screen's one back control, top-left of its header - the
                // same arrow every other mobile page draws (see
                // MobileBackButton). It replaces the per-state word buttons
                // that used to sit at the bottom of each branch.
                Row(modifier = Modifier.fillMaxWidth()) {
                    MobileBackButton(onBack = onBack)
                }

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

                    if (!uiState.isConfigured) {
                        // The branch Trakt's phone page already had: a build with
                        // no client id says so instead of offering a code request
                        // that can only fail (trackers I2).
                        SimklStatusPanel(
                            title = "Not available in this build",
                            message = "This build has no Simkl client id, so Simkl cannot " +
                                "be connected. Set SIMKL_CLIENT_ID for the build and " +
                                "reinstall."
                        )
                    } else if (uiState.isConnected) {
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
                            if (!kidsProfile) {
                                KBButton(
                                    label = "DISCONNECT",
                                    danger = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = { showDisconnectConfirm = true }
                                )
                            }
                        }
                    } else if (uiState.requiresParentPin) {
                        // The gate Trakt's phone page has always had (trackers
                        // C1): a kids profile links no account without a parent.
                        SimklParentPinGateMobile(vm = vm, onBack = onBack)
                    } else {
                        SimklStatusPanel(
                            title = "Device link required",
                            message = "Generate a code, then open the verification " +
                                "URL on your phone or computer and enter the code."
                        )

                        // Linking only: no QR on the connected branch above.
                        // Bigger than the TV panel: this page scrolls by
                        // finger, so height is not the constraint.
                        uiState.verificationUrl?.let { url ->
                            KBQrCode(url = url, size = 200.dp)
                            // The QR is for another device; on this phone it is
                            // unscannable, so the page it names is one tap away
                            // instead of a typed URL.
                            val context = LocalContext.current
                            KBButton(
                                label = "OPEN VERIFICATION PAGE",
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    runCatching {
                                        context.startActivity(
                                            android.content.Intent(
                                                android.content.Intent.ACTION_VIEW,
                                                android.net.Uri.parse(url)
                                            )
                                        )
                                    }
                                }
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
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
            }

            if (showDisconnectConfirm) {
                Dialog(onDismissRequest = { showDisconnectConfirm = false }) {
                    KBDialogPanel(title = "Disconnect from Simkl?") {
                        Text(
                            // The wipe this used to promise is gone (playback spec
                            // C4/D1): unlinking drops the account's tracker state
                            // and leaves this device's history alone.
                            text = "This unlinks your Simkl account on this device. " +
                                "Your watch history, resume positions and Continue " +
                                "Watching stay here. Simkl keeps this app in your " +
                                "connected apps until you remove it at simkl.com " +
                                "— the app cannot revoke its own access there.",
                            color = KBTextLo,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            // CANCEL first: unlinking is still the irreversible
                            // half (the app cannot revoke its own grant), so the
                            // safe choice must not sit under the thumb's
                            // resting place.
                            KBButton(
                                label = "CANCEL",
                                modifier = Modifier.weight(1f),
                                onClick = { showDisconnectConfirm = false }
                            )
                            KBButton(
                                label = "DISCONNECT",
                                danger = true,
                                modifier = Modifier.weight(1f),
                                onClick = {
                                    showDisconnectConfirm = false
                                    vm.disconnect()
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SimklParentPinGateMobile(
    vm: SimklViewModel,
    onBack: () -> Unit
) {
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    var pin by remember { mutableStateOf("") }

    SimklStatusPanel(
        title = "Parent PIN required",
        message = "Linking an account on a kids profile is a parent's action."
    )
    KBTextField(
        value = pin,
        onValueChange = {
            pin = it.filter(Char::isDigit).take(4)
            vm.clearPinError()
        },
        placeholder = "••••",
        keyboardType = KeyboardType.NumberPassword,
        visualTransformation = PasswordVisualTransformation()
    )
    if (uiState.pinError) {
        Text(
            text = "Wrong PIN — try again",
            color = KBRust,
            style = MaterialTheme.typography.bodyMedium
        )
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
