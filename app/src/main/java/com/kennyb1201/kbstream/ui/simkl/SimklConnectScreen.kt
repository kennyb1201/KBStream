package com.kennyb1201.kbstream.ui.simkl

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
import com.kennyb1201.kbstream.ui.components.KBQrCode
import com.kennyb1201.kbstream.ui.components.KBTextField
import com.kennyb1201.kbstream.ui.mobile.SimklConnectScreenMobile
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
    // The phone entry: the same ViewModel, device-code flow, counts and
    // disconnect gate, laid out for touch. One branch; the TV page below is
    // unchanged (see SimklConnectScreenMobile).
    if (!com.kennyb1201.kbstream.FormFactor.isTv) {
        SimklConnectScreenMobile(
            modifier = modifier,
            onBack = onBack,
            vm = vm
        )
        return
    }

    val uiState by vm.uiState.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()
    // Raised by DISCONNECT; see the button's own note for what it is protecting.
    var showDisconnectConfirm by remember { mutableStateOf(false) }
    // The pairing row is a focus stop of its own (it is not a button), so it
    // needs to say when it holds the D-pad: without a ring, focus would look lost.
    var pairingFocused by remember { mutableStateOf(false) }
    // A kids profile gets BOTH controls now, and both behind the parent PIN
    // (spec 2026-11-16, kids-profile disconnect/reconnect F1-F3).
    //
    // The old note here claimed DISCONNECT destroyed local history, which is why
    // a kids profile got no button at all. That stopped being true at playback
    // spec C4/D1: `clearAuth()` revokes the V2 grant and drops the tracker
    // session, and local watch data is not part of it. What is left is the rule
    // that always mattered - this is a parent's action - and the app already has
    // the apparatus for that: the same PIN gate the first-time link uses.
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
                    // Directional search stays on this page instead of drifting
                    // into the nav rail, so Down walks the content and scrolls
                    // it, rather than needing Right to get there first.
                    .focusGroup()
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

                if (!uiState.isConfigured) {
                    // Trakt's page has always had this branch; Simkl's missing
                    // client id surfaced only as the code request's thrown error
                    // (trackers I2).
                    SimklStatusPanel(
                        title = "Not available in this build",
                        message = "This build has no Simkl client id, so Simkl cannot be " +
                            "connected. Set SIMKL_CLIENT_ID for the build and reinstall."
                    )
                    // A control for the D-pad: without one this branch is a dead
                    // end with nothing to focus below the panel.
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        KBButton(label = "BACK", onClick = onBack)
                    }
                } else if (uiState.requiresParentPin) {
                    // The gate comes BEFORE the connected branch (F1/F2): a kids
                    // profile that is already connected is exactly where RECONNECT
                    // and DISCONNECT live, so the gate has to be able to replace
                    // that branch rather than only the linking one. It used to sit
                    // after it, where it was unreachable from a connected kids
                    // profile.
                    SimklParentPinGate(vm = vm, onBack = onBack, isConnected = uiState.isConnected)
                } else if (uiState.isConnected) {
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

                    // The legacy state (spec 2026-11-16, F4): a session minted by
                    // AUTH V1 keeps working until Simkl retires it, and this is
                    // where the viewer is told - and offered the way forward -
                    // without a forced sign-out.
                    if (uiState.isLegacySession) {
                        SimklStatusLine(
                            "This device is signed in with the old Simkl sign-in. " +
                                "Reconnect to keep syncing after Simkl retires it."
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        // The app's one button (KBButton), not a hand-rolled
                        // KBCard + titleMedium label: the same "REFRESH" was a
                        // size bigger and a pixel taller here than in every
                        // other panel.
                        KBButton(label = "REFRESH", onClick = vm::refreshSimklNow)

                        // Legacy only: the same device flow every new sign-in
                        // uses, one button away from where the state is named.
                        // Drawn on a kids profile too (F1) - the ViewModel is
                        // what asks for the parent PIN, so the button and the
                        // gate cannot disagree.
                        if (uiState.isLegacySession) {
                            KBButton(label = "RECONNECT", onClick = vm::generateCode)
                        }

                        KBButton(label = "BACK", onClick = onBack)

                        // The destructive one carries the danger tint: it was
                        // drawn in the same bright label as REFRESH, so the row
                        // that unlinks the account looked like the row that
                        // refreshes it. It also asks first: unlink drops the
                        // account's tracker state and the app has no revoke to
                        // call (Simkl keeps the grant until the viewer removes
                        // it on simkl.com), so the confirmation is where that is
                        // said. Local watch data is NOT part of it any more
                        // (playback spec C4/D1: disconnect never wipes).
                        // Drawn on a kids profile as well (F2). The PIN, when
                        // that profile needs one, comes first and the
                        // confirmation still comes after it - the Dialog is not
                        // a substitute for the gate.
                        KBButton(
                            label = "DISCONNECT",
                            danger = true,
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
                    // F3: the profile is V2-linked on ANOTHER device and this one
                    // holds no session. Named here, directly above the button
                    // that acts on it - the offer is a line on a screen the
                    // viewer opened, never a popup, and nothing starts by itself.
                    uiState.linkedElsewhereName?.let { name ->
                        SimklStatusLine(SimklLinkOfferCopy.line(name))
                        SimklStatusLine(SimklLinkOfferCopy.EXPLANATION)
                    }

                    SimklStatusPanel(
                        title = "Device link required",
                        message = "Generate a code, then open the verification URL on your phone or computer and enter the code."
                    )

                    // Only while linking: the connected branch above has no
                    // address left to open, so it never draws a QR. Side by
                    // side, not stacked: a tall QR above the code pushed the
                    // code below the fold, and a D-pad cannot scroll to it.
                    if (uiState.verificationUrl != null || uiState.userCode != null) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            // A landing target above the buttons: the view lands
                            // here on Down and brings the code into view even
                            // when the page is scrolled.
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusable()
                                .onFocusChanged { pairingFocused = it.isFocused }
                                .background(
                                    if (pairingFocused) {
                                        KBAccent.copy(alpha = 0.08f)
                                    } else {
                                        Color.Transparent
                                    },
                                    KBShapeCard
                                )
                                .border(
                                    1.dp,
                                    if (pairingFocused) {
                                        KBAccent.copy(alpha = 0.55f)
                                    } else {
                                        Color.Transparent
                                    },
                                    KBShapeCard
                                )
                        ) {
                            uiState.verificationUrl?.let { url ->
                                KBQrCode(url = url, size = 140.dp)
                            }

                            uiState.userCode?.let { code ->
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
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
                                    // The code's own lifetime, counting down: a
                                    // dead code left on screen was the old flow's
                                    // worst state (spec F1).
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
                        }
                    }

                    uiState.statusMessage?.let { message ->
                        SimklStatusLine(message)
                    }

                    uiState.errorMessage?.let { error ->
                        SimklErrorLine(error)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        // The stranded-code way out (spec 2026-11-16, F4), and
                        // first in the row: a code that is still valid, and may
                        // already be approved on the web, resumes here.
                        if (uiState.canResumePoll) {
                            KBButton(label = "RETRY", onClick = vm::retryPoll)
                        }

                        // The same control either way: when the offer above is
                        // showing, this IS "Connect this device" - one tap, one
                        // code, one approval through the flow that already
                        // exists. No second sign-in path.
                        KBButton(
                            label = when {
                                uiState.isLoading -> "WORKING…"
                                uiState.linkedElsewhereName != null ->
                                    SimklLinkOfferCopy.ACTION
                                else -> "GENERATE CODE"
                            },
                            onClick = vm::generateCode
                        )

                        KBButton(label = "BACK", onClick = onBack)
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }

    // A PIN accepted for DISCONNECT does not disconnect by itself (F2): it hands
    // the approval back here so the confirmation below runs first, exactly as it
    // does for a non-kids profile.
    LaunchedEffect(uiState.approvedParentAction) {
        if (uiState.approvedParentAction == SimklParentAction.DISCONNECT) {
            showDisconnectConfirm = true
            vm.consumeParentApproval()
        }
    }

    if (showDisconnectConfirm) {
        DisconnectConfirmDialog(
            onConfirm = {
                showDisconnectConfirm = false
                vm.disconnect()
            },
            onDismiss = { showDisconnectConfirm = false },
            isLegacySession = uiState.isLegacySession
        )
    }
}

/**
 * A kids profile may not link an account without the parent PIN.
 *
 * The same field the lock veil uses (digits only, four of them), because that is
 * the credential this app already asked parents to set - and the same gate
 * Trakt's page draws, so both trackers state one rule (trackers C1).
 */
@Composable
private fun SimklParentPinGate(
    vm: SimklViewModel,
    onBack: () -> Unit,
    // CANCEL goes back to the CONNECTED page when there is one (F3): the gate
    // replaces that branch now, so leaving the screen would be a bigger step
    // than cancel asks for.
    isConnected: Boolean
) {
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    var pin by remember { mutableStateOf("") }
    val pinField = remember { FocusRequester() }
    LaunchedEffect(Unit) { landFocusOn(pinField) }

    SimklStatusPanel(
        title = "Parent PIN required",
        // "Changing" rather than "linking": this gate now covers disconnect too
        // (F2), and the old sentence would be a lie in front of that button.
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
        focusRequester = pinField,
        keyboardType = KeyboardType.NumberPassword,
        visualTransformation = PasswordVisualTransformation(),
        keepFocusOnDone = true
    )
    if (uiState.pinError) {
        SimklErrorLine("Wrong PIN — try again")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        KBButton(
            label = "UNLOCK",
            onClick = { vm.submitParentPin(pin) }
        )
        KBButton(label = "CANCEL", onClick = {
            vm.cancelParentPin()
            if (!isConnected) onBack()
        })
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
    onDismiss: () -> Unit,
    // Which body sentence to state: a V2 session revokes its own grant, a V1
    // one cannot (see SimklDisconnectCopy).
    isLegacySession: Boolean
) {
    val cancelFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { landFocusOn(cancelFocusRequester) }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        KBDialogPanel(title = SimklDisconnectCopy.TITLE_TV) {
            Text(
                // No local-data consequence any more, because there is none:
                // disconnect unlinks the account and drops its tracker state,
                // and this device's history, resume rows, markers and Continue
                // Watching stay (playback spec C4/D1). The sentence after that
                // is version-specific: V2 revokes its own grant, V1 never could
                // (spec 2026-11-16, phone dialog F3). This panel is wide enough
                // for the naming title, so it keeps it.
                text = SimklDisconnectCopy.body(isLegacySession),
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
