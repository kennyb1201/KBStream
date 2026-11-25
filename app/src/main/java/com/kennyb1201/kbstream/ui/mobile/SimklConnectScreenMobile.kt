package com.kennyb1201.kbstream.ui.mobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.kennyb1201.kbstream.data.sync.ProfileManager
import com.kennyb1201.kbstream.ui.components.KBQrCode
import com.kennyb1201.kbstream.ui.components.touch.KBButton
import com.kennyb1201.kbstream.ui.components.touch.KBDialogPanel
import com.kennyb1201.kbstream.ui.components.touch.KBTextField
import com.kennyb1201.kbstream.ui.simkl.SimklDisconnectCopy
import com.kennyb1201.kbstream.ui.simkl.SimklLinkOfferCopy
import com.kennyb1201.kbstream.ui.simkl.SimklParentAction
import com.kennyb1201.kbstream.ui.simkl.SimklParentPinCopy
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
 * Both controls are drawn on a kids profile too, and both sit behind the parent
 * PIN (spec 2026-11-16, kids-profile disconnect/reconnect F1-F3). This used to
 * read "a kids profile gets no DISCONNECT button at all" on the grounds that
 * unlinking destroyed local history - that stopped being true at playback spec
 * C4/D1, and the restriction was never about the wipe anyway: it is about who
 * may act. DISCONNECT still goes through its confirmation after the PIN. The TV
 * dialog's focus retry has no touch twin - the buttons are plain tap targets.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SimklConnectScreenMobile(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    vm: SimklViewModel = hiltViewModel()
) {
    // Leaving ends the attempt (spec audit 2026-11-18, Onboarding M1) - see
    // SimklConnectScreen's twin.
    DisposableEffect(Unit) {
        onDispose { vm.stopPolling() }
    }
    // The phone twin answers Back itself, exactly as the television page does
    // (see SimklConnectScreen): the shell's global handler stands down for a
    // migrated kind, so a page without this one would let Back finish the app.
    BackHandler(onBack = onBack)
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    var showDisconnectConfirm by remember { mutableStateOf(false) }
    // A kids profile gets the account controls, but not without a parent: the
    // ViewModel asks for the PIN before it starts a reconnect or a disconnect
    // (F1/F2). (This used to be phrased as "the destructive button" because
    // disconnect() also wiped local history - it no longer does, see
    // SimklViewModel.disconnect.)
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
                    } else if (uiState.requiresParentPin) {
                        // Before the connected branch (F1/F2): a connected kids
                        // profile is exactly where RECONNECT and DISCONNECT are,
                        // so the gate has to be able to replace that branch.
                        SimklParentPinGateMobile(
                            vm = vm,
                            onBack = onBack,
                            isConnected = uiState.isConnected
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

                        // The legacy state (spec 2026-11-16, F4): the TV page's
                        // sentence and button, so both surfaces say the same thing
                        // about the same session.
                        if (uiState.isLegacySession) {
                            Text(
                                text = "This device is signed in with the old Simkl " +
                                    "sign-in. Reconnect to keep syncing after Simkl " +
                                    "retires it.",
                                color = KBTextLo,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            KBButton(
                                label = "REFRESH",
                                modifier = Modifier.fillMaxWidth(),
                                onClick = vm::refreshSimklNow
                            )
                            // Drawn on a kids profile too (F1): the ViewModel
                            // is what asks for the parent PIN.
                            if (uiState.isLegacySession) {
                                KBButton(
                                    label = "RECONNECT",
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = vm::generateCode
                                )
                            }
                            // And DISCONNECT on a kids profile as well (F2): the
                            // PIN comes first, the confirmation after it.
                            KBButton(
                                label = "DISCONNECT",
                                danger = true,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    if (kidsProfile) {
                                        vm.requestDisconnect()
                                    } else {
                                        showDisconnectConfirm = true
                                    }
                                }
                            )
                        }
                    } else {
                        // F3, the phone's half: the TV's two lines and the same
                        // single action, so both surfaces say one thing about one
                        // state.
                        uiState.linkedElsewhereName?.let { name ->
                            Text(
                                text = SimklLinkOfferCopy.line(name),
                                color = KBTextHi,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = SimklLinkOfferCopy.EXPLANATION,
                                color = KBTextLo,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }

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
                                // The code's own lifetime, counting down (F1).
                                uiState.secondsRemaining?.let { left ->
                                    Text(
                                        text = "expires in " +
                                            "${left / 60}:${(left % 60).toString().padStart(2, '0')}",
                                        color = KBTextLo,
                                        style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.padding(top = 8.dp)
                                    )
                                }
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
                            // The stranded-code way out (spec 2026-11-16, F4):
                            // this code is still valid, and it may already be
                            // approved on the web - resume it rather than make the
                            // viewer re-approve a new one.
                            if (uiState.canResumePoll) {
                                KBButton(
                                    label = "RETRY",
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = vm::retryPoll
                                )
                            }
                            KBButton(
                                label = when {
                                    uiState.isLoading -> "WORKING…"
                                    uiState.linkedElsewhereName != null ->
                                        SimklLinkOfferCopy.ACTION
                                    else -> "GENERATE CODE"
                                },
                                enabled = !uiState.isLoading,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = vm::generateCode
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))
            }

            // A PIN accepted for DISCONNECT does not disconnect by itself (F2):
            // it hands the approval back here so the confirmation below runs
            // first, exactly as it does for a non-kids profile.
            LaunchedEffect(uiState.approvedParentAction) {
                if (uiState.approvedParentAction == SimklParentAction.DISCONNECT) {
                    showDisconnectConfirm = true
                    vm.consumeParentApproval()
                }
            }

            if (showDisconnectConfirm) {
                Dialog(onDismissRequest = { showDisconnectConfirm = false }) {
                    // The SHORT title (spec 2026-11-16, phone dialog F2): the
                    // shared heading is deliberately one line with an ellipsis, so
                    // "Disconnect from Simkl?" read as "DISCONNECT FROM S…" on a
                    // narrow panel. Shortening the string fixes this dialog without
                    // changing every other phone dialog's heading.
                    KBDialogPanel(title = SimklDisconnectCopy.TITLE_PHONE) {
                        Text(
                            // The wipe this used to promise is gone (playback spec
                            // C4/D1): unlinking drops the account's tracker state
                            // and leaves this device's history alone. The second
                            // half of the sentence is version-specific now - V2
                            // revokes its own grant, V1 never could (F3).
                            text = SimklDisconnectCopy.body(uiState.isLegacySession),
                            color = KBTextLo,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        // The buttons no longer split the panel in half (F1).
                        // Each is sized by its own label, and a FlowRow puts them
                        // side by side while both fit and on two lines when they do
                        // not - which is the only answer that holds at every font
                        // scale. Half a panel each was exactly the reported wrap:
                        // ten uppercase characters do not fit in half of a phone
                        // panel at a large scale, and "DISCONNECT" broke in two.
                        //
                        // CANCEL stays FIRST: the destructive choice must not sit
                        // under the thumb's resting place.
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            KBButton(
                                label = "CANCEL",
                                onClick = { showDisconnectConfirm = false }
                            )
                            KBButton(
                                label = "DISCONNECT",
                                danger = true,
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
    onBack: () -> Unit,
    // CANCEL returns to the CONNECTED page when there is one (F3): the gate
    // replaces that branch now, so leaving the screen is a bigger step than
    // cancel asks for.
    isConnected: Boolean
) {
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    // F1/F4 (2026-11-17): no parent PIN on this device opens the CREATE prompt
    // rather than the sentence - on the phone too, which works when a PIN exists
    // and had the same gap when it did not.
    if (uiState.parentPinCreate) {
        SimklParentPinCreateGateMobile(vm = vm, onBack = onBack, isConnected = isConnected)
        return
    }
    var pin by remember { mutableStateOf("") }

    SimklStatusPanel(
        title = "Parent PIN required",
        // "Linking or disconnecting": this gate covers both acts now (F2).
        message = "Linking or disconnecting an account on a kids profile is " +
            "a parent's action."
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
                if (!isConnected) onBack()
            }
        )
    }
}

/**
 * The CREATE half of the parent-PIN gate, phone (F1/F2/F4, 2026-11-17).
 *
 * Same rule as the TV's: a device with no PIN is offered one (new + confirm)
 * through the shared prompt rules, and the act that opened the gate runs once it
 * is written. The visuals are this screen's gate - the same panel, field and error
 * line the VERIFY branch draws.
 */
@Composable
private fun SimklParentPinCreateGateMobile(
    vm: SimklViewModel,
    onBack: () -> Unit,
    isConnected: Boolean
) {
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }

    SimklStatusPanel(
        title = "Set a parent PIN",
        // F3: the sentence is the prompt's reason now, not the whole screen.
        message = SimklParentPinCopy.MISSING
    )
    KBTextField(
        value = newPin,
        onValueChange = {
            newPin = it.filter(Char::isDigit).take(4)
            vm.clearPinError()
        },
        placeholder = "New PIN",
        keyboardType = KeyboardType.NumberPassword,
        visualTransformation = PasswordVisualTransformation()
    )
    KBTextField(
        value = confirmPin,
        onValueChange = {
            confirmPin = it.filter(Char::isDigit).take(4)
            vm.clearPinError()
        },
        placeholder = "Confirm PIN",
        keyboardType = KeyboardType.NumberPassword,
        visualTransformation = PasswordVisualTransformation()
    )
    uiState.parentPinMessage?.let { message ->
        Text(
            text = message,
            color = KBRust,
            style = MaterialTheme.typography.bodyMedium
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        KBButton(
            label = "SET PIN",
            modifier = Modifier.fillMaxWidth(),
            onClick = { vm.submitParentPinCreation(newPin, confirmPin) }
        )
        KBButton(
            label = "CANCEL",
            modifier = Modifier.fillMaxWidth(),
            onClick = {
                vm.cancelParentPin()
                if (!isConnected) onBack()
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
