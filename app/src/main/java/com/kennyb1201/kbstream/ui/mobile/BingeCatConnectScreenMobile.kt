package com.kennyb1201.kbstream.ui.mobile

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kennyb1201.kbstream.data.bingecat.BingeCatConfig
import com.kennyb1201.kbstream.data.bingecat.BingeCatProfile
import com.kennyb1201.kbstream.data.bingecat.bingecatActivationUrlForQr
import com.kennyb1201.kbstream.ui.bingecat.BingeCatUiState
import com.kennyb1201.kbstream.ui.bingecat.BingeCatViewModel
import com.kennyb1201.kbstream.ui.components.KBQrCode
import com.kennyb1201.kbstream.ui.components.touch.KBButton
import com.kennyb1201.kbstream.ui.components.touch.KBDialogPanel
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
 * BingeCat connect, for a finger.
 *
 * The same activity-scoped [BingeCatViewModel] as the TV page, so the device
 * flow, the poll, the mapping step and the disconnect are one implementation
 * with two layouts - not a copy. The TV page's buttons are cards that answer
 * the D-pad, which is why this twin exists at all: on a phone that page took
 * no press, so the connection could not be made from it (see
 * [com.kennyb1201.kbstream.ui.bingecat.BingeCatConnectScreen]).
 *
 * Two things are touch-specific rather than merely re-laid-out:
 *
 *  - **The QR is not the way in.** A code shown on THIS screen cannot be
 *    scanned with the same phone, so the approval page opens in the browser
 *    instead (the URL already carries the code - see [bingecatApprovalUrl]),
 *    exactly as Trakt's phone page does.
 *  - **DISCONNECT asks first.** Unlinking an account by mis-tap is worth one
 *    question even though local data is untouched, and the dialog says what is
 *    kept.
 */
@Composable
fun BingeCatConnectScreenMobile(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onBrowsePublicLists: () -> Unit = {},
    vm: BingeCatViewModel = viewModel()
) {
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    // Re-mapping is deliberate: the picker only appears once CHANGE PROFILE asks
    // for it, and never replaces a working mapping silently.
    var showPicker by remember { mutableStateOf(false) }
    var showDisconnectConfirm by remember { mutableStateOf(false) }

    // The store is the truth on entry, as on the TV page: a session saved by an
    // earlier attempt shows as connected rather than as that attempt's failure.
    LaunchedEffect(Unit) { vm.refreshConnectionState() }

    LaunchedEffect(showPicker) {
        if (showPicker) vm.loadProfiles()
    }

    // A session can arrive from the account with no mapping beside it. That
    // state draws the picker, so the picker has to be FETCHED too - drawn over
    // loaded profiles it read "BingeCat has no profiles" and left the step
    // looking like a dead end (spec F2).
    LaunchedEffect(uiState.isConnected, uiState.isMapped) {
        if (uiState.isConnected && !uiState.isMapped) vm.loadProfiles()
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
                // One back control for the whole page, top-left of the header:
                // the same arrow as every other mobile screen.
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
                        text = "BINGECAT",
                        color = KBAccent,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )

                    // Both write directions are disclosed, because the grant
                    // carries both: what KBStream sends is what the viewer is
                    // approving.
                    Text(
                        text = "Reads your BingeCat watchlist, saved list, lists and watched " +
                            "history for the profile you pick, and records what you watch " +
                            "there - finished plays and the titles you mark watched or " +
                            "unwatched. It can also save and unsave titles.",
                        color = KBTextLo,
                        style = MaterialTheme.typography.bodyMedium
                    )

                    when {
                        !uiState.isConfigured -> {
                            BingeCatPanelMobile(
                                title = "Not available in this build",
                                message = "This build has no BingeCat client id, so BingeCat " +
                                    "cannot be connected. Set BINGECAT_CLIENT_ID for the " +
                                    "build and reinstall."
                            )
                        }

                        uiState.isLinking || uiState.userCode != null -> {
                            BingeCatLinkingMobile(uiState = uiState)
                            uiState.errorMessage?.let { BingeCatErrorMobile(it) }
                            KBButton(
                                label = "CANCEL",
                                modifier = Modifier.fillMaxWidth(),
                                onClick = vm::cancelCode
                            )
                        }

                        uiState.isConnected && uiState.isMapped -> {
                            if (uiState.needsReapproval) {
                                // A grant issued under fewer scopes than KBStream
                                // now asks for cannot write, and no retry changes
                                // that: the fix is a fresh approval (the mapping
                                // and cached rows survive it).
                                BingeCatPanelMobile(
                                    title = "Writing needs a reconnect",
                                    message = "This connection was approved before KBStream " +
                                        "asked to record plays and to save titles. " +
                                        "Reconnect to let it record what you watch; your " +
                                        "profile choice and cached rows are kept."
                                )
                            } else {
                                BingeCatPanelMobile(
                                    title = "Account connected",
                                    message = uiState.mappedProfileName?.let {
                                        "Reading and recording BingeCat profile $it."
                                    } ?: "This KBStream profile is mapped to a BingeCat profile."
                                )
                            }
                            uiState.statusMessage?.let { BingeCatLineMobile(it) }
                            uiState.errorMessage?.let { BingeCatErrorMobile(it) }

                            Column(
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                if (uiState.needsReapproval) {
                                    KBButton(
                                        label = if (uiState.isRequestingCode) {
                                            "REQUESTING…"
                                        } else {
                                            "RECONNECT"
                                        },
                                        enabled = !uiState.isRequestingCode,
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = vm::requestCode
                                    )
                                }
                                KBButton(
                                    label = "CHANGE PROFILE",
                                    modifier = Modifier.fillMaxWidth(),
                                    // Reopening the picker is a mapping change,
                                    // not a disconnect: the grant stays.
                                    onClick = { showPicker = true }
                                )
                                KBButton(
                                    label = "BROWSE PUBLIC LISTS",
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = onBrowsePublicLists
                                )
                                KBButton(
                                    label = if (uiState.isDisconnecting) {
                                        "DISCONNECTING…"
                                    } else {
                                        "DISCONNECT"
                                    },
                                    enabled = !uiState.isDisconnecting,
                                    danger = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = { showDisconnectConfirm = true }
                                )
                            }
                            if (showPicker) {
                                BingeCatProfilePickerMobile(
                                    state = uiState,
                                    onPick = { profile ->
                                        vm.pickProfile(profile)
                                        showPicker = false
                                    },
                                    onReload = vm::loadProfiles
                                )
                                uiState.profilesError?.let { BingeCatErrorMobile(it) }
                            }
                        }

                        uiState.isConnected -> {
                            BingeCatPanelMobile(
                                title = "Choose a BingeCat profile",
                                message = "The connection is account-level. Pick the BingeCat " +
                                    "profile this KBStream profile reads - until you do, no " +
                                    "BingeCat data is requested."
                            )
                            BingeCatProfilePickerMobile(
                                state = uiState,
                                onPick = vm::pickProfile,
                                onReload = vm::loadProfiles
                            )
                            uiState.profilesError?.let { BingeCatErrorMobile(it) }
                            uiState.statusMessage?.let { BingeCatLineMobile(it) }
                            uiState.errorMessage?.let { BingeCatErrorMobile(it) }

                            Column(
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                // Browse needs no mapping (reading public lists
                                // takes no profile); only importing does.
                                KBButton(
                                    label = "BROWSE PUBLIC LISTS",
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = onBrowsePublicLists
                                )
                                KBButton(
                                    label = "NOT NOW",
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = vm::skipMapping
                                )
                                KBButton(
                                    label = if (uiState.isDisconnecting) {
                                        "DISCONNECTING…"
                                    } else {
                                        "DISCONNECT"
                                    },
                                    enabled = !uiState.isDisconnecting,
                                    danger = true,
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = { showDisconnectConfirm = true }
                                )
                            }
                        }

                        else -> {
                            // A mapped profile whose session was refused lands
                            // here (`isConnected` is false by then), and "Device
                            // link required" would read as first-time setup: the
                            // session existed and stopped working, which is the
                            // state that must not pass for "connected" (F2).
                            BingeCatPanelMobile(
                                title = if (uiState.needsReconnect) {
                                    "Reconnect required"
                                } else {
                                    "Device link required"
                                },
                                message = if (uiState.needsReconnect) {
                                    "BingeCat no longer accepts this device's session, so " +
                                        "your watchlist, saved list and lists cannot be " +
                                        "read until you sign in again. Your profile choice " +
                                        "and pinned rails are kept. Generate a code to " +
                                        "reconnect."
                                } else {
                                    "Generate a code, then approve it on BingeCat's " +
                                        "page in this phone's browser."
                                }
                            )
                            uiState.statusMessage?.let { BingeCatLineMobile(it) }
                            uiState.errorMessage?.let { BingeCatErrorMobile(it) }
                            KBButton(
                                label = if (uiState.isRequestingCode) {
                                    "REQUESTING…"
                                } else {
                                    "GENERATE CODE"
                                },
                                enabled = !uiState.isRequestingCode,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = vm::requestCode
                            )
                        }
                    }
                }
            }
        }

        if (showDisconnectConfirm) {
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { showDisconnectConfirm = false }
            ) {
                KBDialogPanel(title = "Disconnect from BingeCat?") {
                    Text(
                        text = "This unlinks your BingeCat account and stops reading its " +
                            "lists. Your My List, watch history and resume rows stay on " +
                            "this device.",
                        color = KBTextLo,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        // CANCEL first: on a destructive confirm the safe choice
                        // is the left-hand one, as everywhere else.
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

/**
 * The code, the address it goes to, and how long it lives - laid out for a
 * phone, with the QR kept as a secondary way in (it is still scannable with
 * another device's camera).
 */
@Composable
private fun BingeCatLinkingMobile(uiState: BingeCatUiState) {
    val code = uiState.userCode
    if (code == null) {
        BingeCatPanelMobile(
            title = "Requesting a code",
            message = "Asking BingeCat for a device code…"
        )
        return
    }
    val url = uiState.verificationUrl ?: BingeCatConfig.ACTIVATE_URL
    // Keyed on the code as well as the URL, so a fresh code can never reuse the
    // old QR - or open the old approval page.
    val approvalUrl = remember(url, code) { bingecatActivationUrlForQr(url, code) }

    BingeCatPanelMobile(
        title = "Approve this device",
        message = "Open BingeCat's approval page in this phone's browser - the code " +
            "travels with the link - or scan the code from another device."
    )
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
        Text(
            text = "Waiting for approval · ${uiState.secondsLeft.coerceAtLeast(0)}s left",
            color = KBTextLo,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 6.dp)
        )
        Spacer(modifier = Modifier.height(10.dp))
        KBQrCode(url = approvalUrl, size = 180.dp)
    }

    val context = LocalContext.current
    KBButton(
        label = "OPEN APPROVAL PAGE",
        modifier = Modifier.fillMaxWidth(),
        onClick = {
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(approvalUrl)))
            }
        }
    )
}

/** One tile per BingeCat profile, plus the reload that makes an empty list a state. */
@Composable
private fun BingeCatProfilePickerMobile(
    state: BingeCatUiState,
    onPick: (BingeCatProfile) -> Unit,
    onReload: (() -> Unit)? = null
) {
    val profiles = state.profiles.filter { (it.id ?: 0L) > 0L }
    if (state.isLoadingProfiles) {
        BingeCatPanelMobile(title = "Reading profiles", message = "Asking BingeCat…")
        return
    }
    if (profiles.isEmpty()) {
        BingeCatPanelMobile(
            title = "No profiles to choose",
            message = "BingeCat returned no profiles for this account."
        )
        onReload?.let { reload ->
            KBButton(
                label = "RELOAD PROFILES",
                modifier = Modifier.fillMaxWidth(),
                onClick = reload
            )
        }
        return
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        profiles.forEach { profile ->
            val name = profile.name?.takeIf { it.isNotBlank() } ?: "Profile ${profile.id}"
            val mapped = profile.id == state.mappedProfileId
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                KBButton(
                    label = if (profile.isMain) "$name (main)" else name,
                    // The mapped row stays visible but inert: pressing it would
                    // re-select the same profile.
                    enabled = !mapped,
                    modifier = Modifier.weight(1f),
                    onClick = { onPick(profile) }
                )
                if (mapped) {
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "MAPPED",
                        color = KBAccent,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun BingeCatPanelMobile(title: String, message: String) {
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
private fun BingeCatLineMobile(message: String) {
    Text(
        text = message,
        color = KBTextLo,
        style = MaterialTheme.typography.bodyMedium
    )
}

@Composable
private fun BingeCatErrorMobile(message: String) {
    Text(
        text = message,
        color = KBRust,
        style = MaterialTheme.typography.bodyMedium
    )
}
