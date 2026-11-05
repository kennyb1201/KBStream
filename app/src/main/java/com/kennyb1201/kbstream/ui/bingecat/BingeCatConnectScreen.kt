package com.kennyb1201.kbstream.ui.bingecat

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.data.bingecat.BingeCatConfig
import com.kennyb1201.kbstream.data.bingecat.bingecatActivationUrlForQr
import com.kennyb1201.kbstream.ui.components.KBButton
import com.kennyb1201.kbstream.ui.components.KBPageTitle
import com.kennyb1201.kbstream.ui.components.KBQrCode
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBShapePanel
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid

/**
 * The BingeCat connect page.
 *
 * The same shape as the Trakt page beside it - a code the viewer types on
 * another device, a status line, and one destructive action at the bottom -
 * because that is the only flow a remote can complete. What BingeCat adds is
 * the step after approval: the grant is account-level, so this page then asks
 * WHICH BingeCat profile this KBStream profile reads, and an unanswered
 * question means no data at all (spec F2).
 *
 * One screen for both form factors, laid out as a single scrolling column: the
 * content is three lines and a code, and a separate touch layout would be the
 * same content with different padding.
 */
@Composable
fun BingeCatConnectScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    /**
     * Settings → BingeCat → Browse public lists (step 6). The browser is its own
     * surface with its own level stack; this page is its entry, which is where
     * the spec puts it ("alongside the connect screen").
     */
    onBrowsePublicLists: () -> Unit = {},
    vm: BingeCatViewModel = viewModel()
) {
    val uiState by vm.uiState.collectAsStateWithLifecycle()
    // Whether the mapped screen is showing the picker again. Re-mapping is a
    // deliberate action, so it never replaces a working mapping silently: the
    // picker only appears once CHANGE PROFILE asks for it.
    var showPicker by remember { mutableStateOf(false) }

    // The store is the truth on entry: a session saved by an earlier attempt
    // (even one that died right after saving) shows as connected.
    LaunchedEffect(Unit) { vm.refreshConnectionState() }

    LaunchedEffect(showPicker) {
        if (showPicker) vm.loadProfiles()
    }

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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 760.dp)
                    .focusGroup()
                    .background(KBSurface, KBShapePanel)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                KBPageTitle(text = "BINGECAT", color = KBAccent)

                // Both write directions are disclosed, because the grant now
                // carries both: what KBStream sends is what the viewer is
                // approving, and "records what you watch" alone would leave the
                // saving half of the connection unmentioned.
                Text(
                    text = "Reads your BingeCat watchlist, saved list, lists, and watched " +
                        "history for the profile you pick, and records what you watch there - " +
                        "finished plays and the titles you mark watched or unwatched. It " +
                        "can also save and unsave titles: the Library button adds a title " +
                        "to that profile's watchlist, and the add-to-list dialog adds to " +
                        "and removes from its lists.",
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodyMedium
                )

                when {
                    !uiState.isConfigured -> {
                        BingeCatPanel(
                            title = "Not available in this build",
                            message = "This build has no BingeCat client id, so BingeCat " +
                                "cannot be connected. Set BINGECAT_CLIENT_ID for the build " +
                                "and reinstall."
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            KBButton(label = "BACK", onClick = onBack)
                        }
                    }

                    uiState.isLinking || uiState.userCode != null -> {
                        BingeCatLinkingPanel(uiState = uiState)
                        uiState.errorMessage?.let { BingeCatErrorLine(it) }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            KBButton(label = "CANCEL") { vm.cancelCode() }
                            KBButton(label = "BACK", onClick = onBack)
                        }
                    }

                    uiState.isConnected && uiState.isMapped -> {
                        if (uiState.needsReapproval) {
                            // A grant issued under fewer scopes than KBStream
                            // now asks for (step 1's reads, or step 2's
                            // reads + history:write). Writing cannot work with
                            // it, and no retry can change that: the fix is a
                            // fresh approval (the mapping and cached rows
                            // survive it).
                            BingeCatPanel(
                                title = "Writing needs a reconnect",
                                message = "Part of BingeCat is off - this connection was " +
                                    "approved before KBStream asked to record plays and to " +
                                    "save titles to your watchlist and lists. Reconnect to " +
                                    "let KBStream record what you watch and save what you " +
                                    "add; your profile choice and cached rows are kept."
                            )
                        } else {
                            BingeCatPanel(
                                title = "Account connected",
                                message = uiState.mappedProfileName?.let {
                                    "Reading and recording BingeCat profile $it."
                                } ?: "This KBStream profile is mapped to a BingeCat profile."
                            )
                        }
                        uiState.statusMessage?.let { BingeCatStatusLine(it) }
                        uiState.errorMessage?.let { BingeCatErrorLine(it) }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            if (uiState.needsReapproval) {
                                KBButton(
                                    label = if (uiState.isRequestingCode) {
                                        "REQUESTING…"
                                    } else {
                                        "RECONNECT"
                                    },
                                    enabled = !uiState.isRequestingCode
                                ) { vm.requestCode() }
                            }
                            KBButton(label = "CHANGE PROFILE") {
                                // Reopening the picker is a mapping change, not a
                                // disconnect: the grant stays.
                                showPicker = true
                            }
                            // Entry to the public-lists browser. It sits in the
                            // connected section (it reads with this session) and
                            // needs no mapping to BROWSE; the import inside asks
                            // for the mapping and the `lists:write` scope itself.
                            KBButton(label = "BROWSE PUBLIC LISTS") { onBrowsePublicLists() }
                            KBButton(
                                label = if (uiState.isDisconnecting) "DISCONNECTING…" else "DISCONNECT",
                                enabled = !uiState.isDisconnecting,
                                danger = true
                            ) { vm.disconnect() }
                            KBButton(label = "BACK", onClick = onBack)
                        }
                        if (showPicker) {
                            BingeCatProfilePicker(
                                state = uiState,
                                onPick = { profile ->
                                    vm.pickProfile(profile)
                                    showPicker = false
                                },
                                onReload = { vm.loadProfiles() }
                            )
                            uiState.profilesError?.let { BingeCatErrorLine(it) }
                        }
                    }

                    uiState.isConnected -> {
                        BingeCatPanel(
                            title = "Choose a BingeCat profile",
                            message = "The connection is account-level. Pick the BingeCat " +
                                "profile this KBStream profile reads - until you do, no " +
                                "BingeCat data is requested."
                        )
                        BingeCatProfilePicker(
                            state = uiState,
                            onPick = { vm.pickProfile(it) },
                            onReload = { vm.loadProfiles() }
                        )
                        uiState.profilesError?.let { BingeCatErrorLine(it) }
                        uiState.statusMessage?.let { BingeCatStatusLine(it) }
                        uiState.errorMessage?.let { BingeCatErrorLine(it) }
                        // Browse is available before the mapping (reading public
                        // lists takes no profile); only importing needs one.
                        KBButton(label = "BROWSE PUBLIC LISTS") { onBrowsePublicLists() }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            KBButton(label = "NOT NOW") { vm.skipMapping() }
                            KBButton(
                                label = if (uiState.isDisconnecting) "DISCONNECTING…" else "DISCONNECT",
                                enabled = !uiState.isDisconnecting,
                                danger = true
                            ) { vm.disconnect() }
                            KBButton(label = "BACK", onClick = onBack)
                        }
                    }

                    else -> {
                        BingeCatPanel(
                            title = "Device link required",
                            message = "Generate a code, open the verification address on " +
                                "your phone or computer, and enter the code."
                        )
                        uiState.statusMessage?.let { BingeCatStatusLine(it) }
                        uiState.errorMessage?.let { BingeCatErrorLine(it) }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            KBButton(
                                label = if (uiState.isRequestingCode) "REQUESTING…" else "GENERATE CODE",
                                enabled = !uiState.isRequestingCode
                            ) { vm.requestCode() }
                            KBButton(label = "BACK", onClick = onBack)
                        }
                    }
                }
            }
        }
    }
}

/** The code, the address it goes to, and how long it lives. */
@Composable
private fun BingeCatLinkingPanel(uiState: BingeCatUiState) {
    val code = uiState.userCode
    if (code == null) {
        BingeCatPanel(
            title = "Requesting a code",
            message = "Asking BingeCat for a device code…"
        )
        return
    }
    val url = uiState.verificationUrl ?: BingeCatConfig.ACTIVATE_URL
    // The QR opens the approval page WITH this code in it: BingeCat's activation
    // page takes `user_code` as a query parameter (see
    // bingecatActivationUrlForQr), unlike Trakt's and Simkl's, which are a page
    // plus a code the viewer types. A scan that lands on the bare page would
    // still make the viewer type the code - the path that failed twice on the
    // TV with invalid_user_code - so the code travels in the URL. It is keyed on
    // the code as well as the URL, so a fresh code can never reuse the old QR.
    val qrUrl = remember(url, code) { bingecatActivationUrlForQr(url, code) }
    BingeCatPanel(
        title = "Scan to connect BingeCat",
        message = "Point your phone's camera at the code and approve it there. " +
            "No camera handy? Enter the code below at $url."
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        KBQrCode(url = qrUrl, size = 140.dp)
        Spacer(modifier = Modifier.width(18.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = code,
                color = KBTextHi,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Waiting for approval · ${
                    uiState.secondsLeft.coerceAtLeast(0)
                }s left",
                color = KBTextLo,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}

/** One row per BingeCat profile, plus the reload that makes an empty list a state. */
@Composable
private fun BingeCatProfilePicker(
    state: BingeCatUiState,
    onPick: (com.kennyb1201.kbstream.data.bingecat.BingeCatProfile) -> Unit,
    onReload: (() -> Unit)? = null
) {
    val profiles = state.profiles.filter { (it.id ?: 0L) > 0L }
    if (state.isLoadingProfiles) {
        BingeCatPanel(title = "Reading profiles", message = "Asking BingeCat…")
        return
    }
    if (profiles.isEmpty()) {
        BingeCatPanel(
            title = "No profiles to choose",
            message = "BingeCat returned no profiles for this account."
        )
        onReload?.let { reload ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                KBButton(label = "RELOAD PROFILES") { reload() }
            }
        }
        return
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        profiles.forEach { profile ->
            val name = profile.name?.takeIf { it.isNotBlank() } ?: "Profile ${profile.id}"
            Row(verticalAlignment = Alignment.CenterVertically) {
                KBButton(
                    label = if (profile.isMain) "$name (main)" else name,
                    enabled = profile.id != state.mappedProfileId
                ) { onPick(profile) }
                if (profile.id == state.mappedProfileId) {
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
private fun BingeCatPanel(title: String, message: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            color = KBTextHi,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = message,
            color = KBTextLo,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 3.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
    }
}

@Composable
private fun BingeCatStatusLine(message: String) {
    Text(
        text = message,
        color = KBAccent,
        style = MaterialTheme.typography.bodySmall
    )
}

@Composable
private fun BingeCatErrorLine(message: String) {
    Text(
        text = message,
        color = KBTextHi,
        style = MaterialTheme.typography.bodySmall
    )
}
