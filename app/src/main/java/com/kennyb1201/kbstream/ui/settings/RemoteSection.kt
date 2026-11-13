package com.kennyb1201.kbstream.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.data.remote.RemoteControlRuntime
import com.kennyb1201.kbstream.data.remote.RemotePairings
import com.kennyb1201.kbstream.data.remote.RemotePhone
import com.kennyb1201.kbstream.data.remote.RemoteRules
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBShapeSmall
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import kotlinx.coroutines.delay

/**
 * The TV's side of the phone remote, in Settings (spec 2026-11-13, F1).
 *
 * This is where the pairing code lives, because a code has to be READ on the TV
 * and typed on the phone - the only shared secret in the feature. The row also
 * lists the phones that have paired and lets the viewer forget one, which is the
 * TV's half of "Forget the phone on the TV - the next connect asks for a PIN
 * again" (S5).
 *
 * Everything here is device-local: pairings are not profile state and never
 * sync, so a second TV in the house doesn't inherit this one's phones.
 */
@Composable
internal fun RemoteSection() {
    val context = LocalContext.current
    var pin by remember { mutableStateOf<String?>(null) }
    var phones by remember { mutableStateOf<List<RemotePhone>>(emptyList()) }

    // The PIN is minted when this row is looked at (five minutes from then), and
    // refreshed as it expires, so a code on screen is always one that works. A
    // phone asking to pair while the row is closed mints one too - this ticker
    // then picks it up on the next look.
    LaunchedEffect(context) {
        while (true) {
            pin = RemotePairings.livePin() ?: RemotePairings.ensurePin()
            phones = RemotePairings.phones(context)
            delay(2_000)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(KBSurfaceRaised, KBShapeSmall)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Pair code",
                    color = KBTextHi,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = if (RemoteControlRuntime.isServing) {
                        "This TV is discoverable on your Wi-Fi."
                    } else {
                        "This TV has no network address right now."
                    },
                    color = KBTextLo,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Text(
                text = pin ?: "----",
                color = KBAccent,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(Modifier.height(6.dp))

        Text(
            text = "Enter this code once in KBStream on your phone: Settings → Remote. " +
                "It expires after ${RemoteRules.PIN_TTL_MS / 60_000} minutes.",
            color = KBTextLo,
            style = MaterialTheme.typography.labelSmall
        )

        if (phones.isEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "No phones paired yet.",
                color = KBTextLo,
                style = MaterialTheme.typography.labelSmall
            )
        } else {
            Spacer(Modifier.height(10.dp))
            Text(
                text = "PAIRED PHONES",
                color = KBAccent,
                style = MaterialTheme.typography.labelSmall
            )
            phones.forEach { phone ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = phone.name,
                        color = KBTextHi,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    KBCard(onClick = { RemotePairings.forget(context, phone.id) }) {
                        Text(
                            text = "FORGET",
                            color = KBTextLo,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}
