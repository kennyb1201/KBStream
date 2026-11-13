package com.kennyb1201.kbstream.ui.remote

import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kennyb1201.kbstream.data.remote.RemoteClientProvider
import com.kennyb1201.kbstream.data.remote.RemoteClientState
import com.kennyb1201.kbstream.data.remote.RemoteDevice
import com.kennyb1201.kbstream.data.remote.RemotePhase
import com.kennyb1201.kbstream.data.remote.RemoteRules
import com.kennyb1201.kbstream.ui.components.touch.KBButton
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid

/**
 * The phone's remote control (spec 2026-11-13, F2).
 *
 * Reached from the phone's Settings row - deliberately not Home and not the
 * bottom bar - and drawn in three states: finding a TV, asking for the TV's PIN,
 * and the keys. Nothing here knows what the TV is showing: a press sends a key
 * over the channel and the TV behaves exactly as it does for its own remote.
 *
 * A disconnected remote is never a dead screen: discovery says "no TV found on
 * this Wi-Fi" with a retry, and a dropped socket says "reconnecting" while it
 * retries by itself.
 */
@Composable
internal fun RemoteScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val client = remember(context) { RemoteClientProvider.get(context) }
    val state by client.stateFlow.collectAsStateWithLifecycle()

    // Discovery starts on entry and stops on exit: no background scanning, no
    // socket left open behind the viewer's back.
    LaunchedEffect(client) { client.start() }
    DisposableEffect(client) {
        onDispose { client.stop() }
    }
    BackHandler(onBack = onBack)

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(KBVoid)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "REMOTE",
                color = KBTextHi,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Box(
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "DONE",
                    color = KBAccent,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Text(
            text = statusLine(state),
            color = if (state.phase == RemotePhase.CONNECTED) KBAccent else KBTextLo,
            style = MaterialTheme.typography.bodySmall
        )
        state.message?.takeIf { it.isNotBlank() }?.let { line ->
            Spacer(Modifier.height(4.dp))
            Text(
                text = line,
                color = KBTextLo,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(Modifier.height(16.dp))

        when (state.phase) {
            RemotePhase.IDLE,
            RemotePhase.SEARCHING -> SearchingPanel(
                state = state,
                onPick = client::connect
            )

            RemotePhase.NO_DEVICE -> NoDevicePanel(
                state = state,
                onRetry = client::retry,
                onPick = client::connect
            )

            RemotePhase.CONNECTING -> MobileStatus("Connecting to ${state.device?.name ?: "your TV"}…")

            RemotePhase.RECONNECTING -> MobileStatus(
                "Reconnecting to ${state.device?.name ?: "your TV"}…"
            )

            RemotePhase.NEEDS_PIN -> PinPanel(
                tvName = state.device?.name ?: "your TV",
                rejected = state.pinRejected,
                onForget = client::forgetTv,
                onSubmit = client::pair
            )

            RemotePhase.CONNECTED -> KeysPanel(
                onKey = client::press
            )
        }

        if (state.devices.size > 1 && state.phase != RemotePhase.CONNECTED) {
            Spacer(Modifier.height(16.dp))
            Text(
                text = "OTHER TVs ON THIS WI-FI",
                color = KBTextLo,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold
            )
            state.devices.forEach { device ->
                DeviceRow(device = device) { client.connect(device) }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

// ── Panels ────────────────────────────────────────────────────────────────

@Composable
private fun SearchingPanel(state: RemoteClientState, onPick: (RemoteDevice) -> Unit) {
    MobileStatus("Looking for your TV on this Wi-Fi…")
    state.devices.forEach { device ->
        DeviceRow(device = device) { onPick(device) }
    }
}

@Composable
private fun NoDevicePanel(
    state: RemoteClientState,
    onRetry: () -> Unit,
    onPick: (RemoteDevice) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "No TV found on this Wi-Fi",
            color = KBTextHi,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Text(
            text = "Open KBStream on the TV and keep both devices on the same network.",
            color = KBTextLo,
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center
        )
        KBButton(label = "TRY AGAIN", modifier = Modifier.fillMaxWidth()) { onRetry() }
    }
    state.devices.forEach { device ->
        DeviceRow(device = device) { onPick(device) }
    }
}

/**
 * The pairing prompt.
 *
 * The PIN is the TV's, shown on its screen under Settings → Remote. Four digits,
 * numeric keyboard, and the only state that ever needs the viewer: after this
 * the phone presents its token and the TV never asks again.
 */
@Composable
private fun PinPanel(
    tvName: String,
    rejected: Boolean,
    onForget: () -> Unit,
    onSubmit: (String) -> Unit
) {
    var pin by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = "Enter the 4-digit code shown on $tvName",
            color = KBTextHi,
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = "Settings → Remote on the TV shows it. It expires after five minutes.",
            color = KBTextLo,
            style = MaterialTheme.typography.bodySmall
        )
        OutlinedTextField(
            value = pin,
            onValueChange = { typed ->
                pin = typed.filter { it.isDigit() }.take(RemoteRules.PIN_DIGITS)
            },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            label = { Text("PIN") },
            modifier = Modifier.fillMaxWidth()
        )
        if (rejected) {
            Text(
                text = "That PIN was not accepted.",
                color = KBAccent,
                style = MaterialTheme.typography.bodySmall
            )
        }
        KBButton(
            label = "PAIR",
            modifier = Modifier.fillMaxWidth()
        ) { onSubmit(pin) }
        Text(
            text = "Forget this TV",
            color = KBTextLo,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onForget)
                .padding(vertical = 6.dp),
            textAlign = TextAlign.Center
        )
    }
}

/**
 * The keys.
 *
 * The D-pad, OK, Back and Home first (what a viewer navigates with), then the
 * transport and volume rows. Every press is haptic: this is a remote, and the
 * phone is usually held away from the eyes.
 */
@Composable
private fun KeysPanel(onKey: (String) -> Unit) {
    val view = LocalView.current

    fun press(code: String) {
        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        onKey(code)
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.size(KEY_SIZE))
            RemoteKey("▲", "Up") { press("UP") }
            Spacer(Modifier.size(KEY_SIZE))
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RemoteKey("◀", "Left") { press("LEFT") }
            RemoteKey("OK", "OK", accent = true) { press("OK") }
            RemoteKey("▶", "Right") { press("RIGHT") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Spacer(Modifier.size(KEY_SIZE))
            RemoteKey("▼", "Down") { press("DOWN") }
            Spacer(Modifier.size(KEY_SIZE))
        }

        Spacer(Modifier.height(4.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RemoteKey("BACK", "Back", wide = true) { press("BACK") }
            RemoteKey("HOME", "Home", wide = true) { press("HOME") }
        }

        Spacer(Modifier.height(4.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RemoteKey("⏪ 10s", "Back 10 seconds", wide = true) { press("SEEK_BACK") }
            RemoteKey("⏯", "Play or pause", wide = true) { press("PLAY_PAUSE") }
            RemoteKey("10s ⏩", "Forward 10 seconds", wide = true) { press("SEEK_FORWARD") }
        }

        Spacer(Modifier.height(4.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RemoteKey("VOL −", "Volume down", wide = true) { press("VOLUME_DOWN") }
            RemoteKey("MUTE", "Mute", wide = true) { press("MUTE") }
            RemoteKey("VOL +", "Volume up", wide = true) { press("VOLUME_UP") }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Keys go to whatever the TV is showing. Transport and volume only " +
                "matter while something is playing.",
            color = KBTextLo,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun RemoteKey(
    label: String,
    description: String,
    accent: Boolean = false,
    wide: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            // The label is the visible key; the description is what a screen
            // reader announces ("Up", not "▲").
            .semantics { contentDescription = description }
            .then(if (wide) Modifier.width(KEY_WIDE) else Modifier.size(KEY_SIZE))
            .height(KEY_SIZE)
            .background(
                if (accent) KBAccent.copy(alpha = 0.22f) else KBSurfaceRaised,
                RoundedCornerShape(14.dp)
            )
            .border(
                1.dp,
                if (accent) KBAccent else KBTextLo.copy(alpha = 0.28f),
                RoundedCornerShape(14.dp)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = if (accent) KBAccent else KBTextHi,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun DeviceRow(device: RemoteDevice, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .background(KBSurface, KBShapeCard)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = device.name,
            color = KBTextHi,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = device.host,
            color = KBTextLo,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun MobileStatus(text: String) {
    Text(
        text = text,
        color = KBTextLo,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

private fun statusLine(state: RemoteClientState): String = when (state.phase) {
    RemotePhase.CONNECTED -> "Connected to ${state.tvName ?: state.device?.name ?: "your TV"}"
    RemotePhase.CONNECTING -> "Connecting…"
    RemotePhase.RECONNECTING -> "Reconnecting…"
    RemotePhase.NEEDS_PIN -> "Not paired yet"
    RemotePhase.NO_DEVICE -> "Not connected"
    RemotePhase.SEARCHING -> "Searching this Wi-Fi"
    RemotePhase.IDLE -> ""
}

private val KEY_SIZE = 64.dp
private val KEY_WIDE = 96.dp
