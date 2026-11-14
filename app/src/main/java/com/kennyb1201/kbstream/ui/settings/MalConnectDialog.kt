package com.kennyb1201.kbstream.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.data.mal.MalAuth
import com.kennyb1201.kbstream.data.mal.MalConnectionState
import com.kennyb1201.kbstream.data.mal.MalLinks
import com.kennyb1201.kbstream.data.mal.MalStartOutcome
import com.kennyb1201.kbstream.ui.components.KBQrCode
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo

/**
 * MyAnimeList's Settings row copy, and the one dialog behind it
 * (spec 2026-11-13, MAL row F1).
 *
 * The row's three states are the ones [MalConfig]'s doc already promised, and
 * they are deliberately three different sentences: a build with no client id
 * says so (Connect can do nothing, and nothing in Settings can fix it), a
 * disconnected profile is offered the connect, and a session MAL refused (401)
 * says it needs a reconnect instead of looking finished. "connected as <name>" is
 * the integration rows' display rule; the name comes from MAL's own `/users/@me`
 * read, cached per profile, so the row never invents a second profile source.
 *
 * THE FLOW IS THE LANDED ONE. `MalLinks.openAuthorize` runs `MalAuth.beginConnect`
 * (PKCE verifier stored before the URL exists) and opens MAL's authorize page;
 * MAL returns to `kbstream://oauth/mal`, which `MainActivity` already exchanges.
 * On a TV there is no usable browser for that round trip, so the dialog shows
 * `MalLinks.startLink()` as a QR instead: scanning it opens KBStream on the phone,
 * the phone runs the connect, and the session reaches the TV through the
 * profile's already-enrolled `KEY_MAL_AUTH`.
 *
 * NO CLIENT ID AND NO SECRET IS EVER TYPED HERE. MAL issues no secret to an
 * android client, and the app's client id is a build value.
 */
@Composable
internal fun MalConnectDialog(
    state: MalConnectionState,
    viewerName: String?,
    phone: Boolean,
    onDismiss: () -> Unit,
    onDisconnect: () -> Unit
) {
    val context = LocalContext.current
    // A phone with nothing to open https (rare, but possible on a stripped
    // build) falls back to the QR rather than a button that does nothing.
    var browserUnavailable by remember { mutableStateOf(false) }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        AnimeDialogPanel(phone = phone, title = "MyAnimeList") {
            Text(
                text = malDialogCopy(state, viewerName),
                color = KBTextLo,
                style = MaterialTheme.typography.bodyMedium
            )

            val canConnect = state == MalConnectionState.Disconnected ||
                state == MalConnectionState.NeedsReconnect

            if (canConnect && phone) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = if (browserUnavailable) {
                        "This device has no browser to open the authorize page. Scan " +
                            "the code below from another device instead."
                    } else {
                        "Approve KBStream on MyAnimeList, and MAL sends you straight " +
                            "back here. Nothing is typed on this screen."
                    },
                    color = KBTextHi,
                    style = MaterialTheme.typography.labelMedium,
                    fontSize = 12.sp
                )
            }

            if (canConnect && (!phone || browserUnavailable)) {
                Spacer(modifier = Modifier.height(10.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    KBQrCode(url = MalLinks.startLink())
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Scan with your phone: it opens KBStream and approves " +
                            "MAL there. The connected session reaches this device " +
                            "through your profile.",
                        color = KBTextLo,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 11.sp
                    )
                    // spec 2026-11-14, F2a: the one thing this device cannot see.
                    // A phone build without the app id opens from the scan and can
                    // do nothing, and the viewer would read that as the QR being
                    // broken.
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "The phone needs KBStream built with the MAL app id - " +
                            "a build without one can only say it is not configured.",
                        color = KBTextLo,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (canConnect && phone && !browserUnavailable) {
                    AnimeDialogButton(
                        phone = phone,
                        onClick = {
                            // The flow starts HERE: the verifier is stored before
                            // the URL is handed to the browser, so a redirect into
                            // a fresh process still has what the exchange needs.
                            browserUnavailable =
                                MalLinks.openAuthorize(context) == MalStartOutcome.NoBrowser
                        }
                    ) {
                        Text(
                            text = if (state == MalConnectionState.NeedsReconnect) {
                                "RECONNECT"
                            } else {
                                "CONNECT"
                            },
                            color = KBTextHi,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                        )
                    }
                }
                if (state == MalConnectionState.Connected ||
                    state == MalConnectionState.NeedsReconnect
                ) {
                    AnimeDialogButton(
                        phone = phone,
                        onClick = {
                            MalAuth.disconnect(context)
                            onDisconnect()
                            onDismiss()
                        }
                    ) {
                        Text(
                            text = "DISCONNECT",
                            color = KBTextHi,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                        )
                    }
                }
                AnimeDialogButton(phone = phone, onClick = onDismiss) {
                    Text(
                        text = "CLOSE",
                        color = KBTextLo,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
}

/** The row's label, per the integration rows' "connected as" rule. */
internal fun malRowLabel(state: MalConnectionState, viewerName: String?): String {
    val name = viewerName?.trim().orEmpty()
    return when (state) {
        MalConnectionState.NotConfigured -> "MyAnimeList — not configured"
        MalConnectionState.NeedsReconnect -> "MyAnimeList — reconnect needed"
        MalConnectionState.Connected ->
            if (name.isEmpty()) "MyAnimeList — connected" else "MyAnimeList — connected as $name"
        MalConnectionState.Disconnected -> "MyAnimeList"
    }
}

/**
 * The row's description.
 *
 * The disconnected sentence is the spec's own: the row has to say what MAL feeds
 * and what it writes back, because that is the whole reason to connect. The
 * connected sentence says whose account it is and that disconnecting is local
 * history-safe; the not-configured sentence is the build problem, which no
 * setting can fix.
 */
internal fun malRowDescription(state: MalConnectionState, viewerName: String?): String =
    when (state) {
        MalConnectionState.NotConfigured ->
            "This build has no MyAnimeList app id, so MAL cannot be connected here. " +
                "Nothing in Settings can add one."
        MalConnectionState.NeedsReconnect ->
            "MyAnimeList refused this profile's session. Reconnect to keep writing " +
                "progress, status and scores to your anime list."
        MalConnectionState.Connected ->
            "Reading and writing your MyAnimeList anime list" +
                (viewerName?.trim()?.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: "") +
                ". Open to disconnect - your local history and every other tracker " +
                "stay as they are."
        MalConnectionState.Disconnected ->
            "Connect your MyAnimeList account to feed Continue Watching, Upcoming " +
                "and Calendar from your anime list, and write back progress, status " +
                "and scores."
    }

/** The dialog's opening sentence - the row's state, spelled out for the flow. */
internal fun malDialogCopy(state: MalConnectionState, viewerName: String?): String =
    when (state) {
        MalConnectionState.NotConfigured ->
            "MyAnimeList is not configured in this build: the app has no client id, " +
                "so the connect cannot start. This is a build problem, not a setting."
        MalConnectionState.NeedsReconnect ->
            "MyAnimeList refused this profile's session token, so nothing is being " +
                "read or written until you reconnect."
        MalConnectionState.Connected ->
            "Connected" +
                (viewerName?.trim()?.takeIf { it.isNotEmpty() }?.let { " as $it" } ?: "") +
                ". Progress, status and scores are written to your MAL anime list. " +
                "Disconnecting stops the writes and keeps everything local."
        MalConnectionState.Disconnected ->
            "Connect your MyAnimeList account to write progress, status and scores, " +
                "and to feed Continue Watching, Upcoming and Calendar from your list."
    }
