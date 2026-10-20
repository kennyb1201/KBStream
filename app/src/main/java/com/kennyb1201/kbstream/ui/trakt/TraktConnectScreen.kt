package com.kennyb1201.kbstream.ui.trakt

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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.ui.browse.landFocusOn
import com.kennyb1201.kbstream.ui.components.KBButton
import com.kennyb1201.kbstream.ui.components.KBDialogPanel
import com.kennyb1201.kbstream.ui.components.KBPageTitle
import com.kennyb1201.kbstream.ui.components.KBTextField
import com.kennyb1201.kbstream.ui.mobile.TraktConnectScreenMobile
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBShapePanel
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid

/**
 * The Trakt connect page.
 *
 * Deliberately the same shape as the Simkl page beside it: a code the viewer
 * types on another device, a status line, and one destructive action at the
 * bottom. The differences are all Trakt's flow - a real expiry it has to report,
 * a "still waiting" state that is normal rather than an error, and a parent PIN
 * before a kids profile may link an account.
 *
 * Disconnect here is NOT Simkl's. It unlinks the account and leaves every local
 * resume position, watched marker and badge exactly where it was; the confirm
 * dialog says so instead of warning about a wipe that no longer happens.
 */
@Composable
fun TraktConnectScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    vm: TraktViewModel = viewModel()
) {
    // The phone entry: same ViewModel and flow, laid out for touch.
    if (!com.kennyb1201.kbstream.FormFactor.isTv) {
        TraktConnectScreenMobile(modifier = modifier, onBack = onBack, vm = vm)
        return
    }

    val uiState by vm.uiState.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()
    var showDisconnectConfirm by remember { mutableStateOf(false) }

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
                KBPageTitle(text = "TRAKT CONNECT", color = KBAccent)

                Text(
                    text = "Connect your Trakt account to scrobble playback and keep " +
                        "watched state in sync. Your watch history stays on this device " +
                        "if you disconnect.",
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodyMedium
                )

                if (!uiState.isConfigured) {
                    TraktStatusPanel(
                        title = "Not available in this build",
                        message = "This build has no Trakt client id, so Trakt cannot be " +
                            "connected. Set TRAKT_CLIENT_ID for the build and reinstall."
                    )
                } else if (uiState.isConnected) {
                    TraktStatusPanel(
                        title = "Account connected",
                        message = uiState.accountSlug?.let { "Signed in as $it." }
                            ?: "Your Trakt account is connected and ready."
                    )
                    uiState.statusMessage?.let { TraktStatusLine(it) }
                    uiState.errorMessage?.let { TraktErrorLine(it) }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        KBButton(label = "DISCONNECT", danger = true) {
                            showDisconnectConfirm = true
                        }
                        KBButton(label = "BACK", onClick = onBack)
                    }
                } else if (uiState.requiresParentPin) {
                    TraktParentPinGate(vm = vm, onBack = onBack)
                } else {
                    TraktStatusPanel(
                        title = "Device link required",
                        message = "Generate a code, open the verification address on your " +
                            "phone or computer, and enter the code."
                    )

                    uiState.verificationUrl?.let { url ->
                        TraktInfoBlock(label = "VERIFY AT", value = url)
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

                    uiState.statusMessage?.let { TraktStatusLine(it) }
                    uiState.errorMessage?.let { TraktErrorLine(it) }

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        KBButton(
                            label = if (uiState.isLoading || uiState.isPolling) {
                                "WORKING…"
                            } else {
                                "GENERATE CODE"
                            },
                            onClick = vm::startConnect
                        )
                        KBButton(label = "BACK", onClick = onBack)
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
        }
    }

    if (showDisconnectConfirm) {
        TraktDisconnectConfirmDialog(
            onConfirm = {
                showDisconnectConfirm = false
                vm.disconnect()
            },
            onDismiss = { showDisconnectConfirm = false }
        )
    }
}

/**
 * A kids profile may not link an account without the parent PIN.
 *
 * The same field the lock veil uses (digits only, four of them), because that is
 * the credential this app already asked parents to set.
 */
@Composable
private fun TraktParentPinGate(
    vm: TraktViewModel,
    onBack: () -> Unit
) {
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    var pin by remember { mutableStateOf("") }
    val pinField = remember { FocusRequester() }
    LaunchedEffect(Unit) { landFocusOn(pinField) }

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
        focusRequester = pinField,
        keyboardType = KeyboardType.NumberPassword,
        visualTransformation = PasswordVisualTransformation(),
        keepFocusOnDone = true
    )
    if (uiState.pinError) {
        TraktErrorLine("Wrong PIN — try again")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        KBButton(
            label = "UNLOCK",
            onClick = { vm.submitParentPin(pin) }
        )
        KBButton(label = "CANCEL", onClick = {
            vm.cancelParentPin()
            onBack()
        })
    }
}

@Composable
private fun TraktDisconnectConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val cancelFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { landFocusOn(cancelFocusRequester) }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        KBDialogPanel(title = "Disconnect from Trakt?") {
            Text(
                text = "This unlinks your Trakt account. Your local watch history, " +
                    "resume positions and watched badges are kept exactly as they are.",
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

@Composable
private fun TraktInfoBlock(
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
private fun TraktStatusLine(message: String) {
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
        color = com.kennyb1201.kbstream.ui.theme.KBDanger,
        style = MaterialTheme.typography.bodyMedium
    )
}
