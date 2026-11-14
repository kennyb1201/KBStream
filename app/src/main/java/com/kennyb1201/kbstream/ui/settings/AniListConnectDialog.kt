package com.kennyb1201.kbstream.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.data.anime.AniListConnectionState
import com.kennyb1201.kbstream.data.anime.AniListLinks
import com.kennyb1201.kbstream.ui.components.KBQrCode
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import kotlinx.coroutines.launch

/**
 * AniList's connect (spec 2026-11-13 phase 2, F1; the code grant by spec
 * 2026-11-14, F3).
 *
 * THE FLOW IS A BROWSER ROUND TRIP THAT COMES BACK ON ITS OWN. A phone opens
 * AniList's authorize page, the viewer approves KBStream, and AniList redirects
 * to `kbstream://oauth/anilist` - `MainActivity` captures the code and exchanges
 * it, so the ordinary path pastes nothing (S1). The TV cannot do that, so it
 * prints the same URL and a QR of it: the PHONE approves, the phone exchanges,
 * and the session reaches the TV through the profile's `anilist_auth` sync (S2).
 *
 * THE FIELD IS A FALLBACK, not the flow. If the browser cannot hand the code
 * back, the viewer pastes the code (or the whole redirect URL) and the same
 * exchange runs from here (S3). A TOKEN pasted here is refused with its own
 * sentence: the old implicit-grant flow handed tokens out, and AniList removed
 * that grant, so a token is a stale instruction rather than a code.
 *
 * THE SECRET IS THE BUILD'S, never typed. It rides the exchange POST only; this
 * dialog never sees it, logs it or says it. A build without one says so and
 * offers no button that could not work.
 *
 * The public-username row stays, because the phase-1 read needs no account at
 * all and a viewer who only wants their public list keeps it.
 */
@Composable
internal fun AniListConnectDialog(
    state: AniListConnectionState,
    authorizeUrl: String?,
    currentUsername: String?,
    /**
     * True on the phone, whose plate, buttons and fields are all the touch twins
     * (see [AnimeDialogPanel]).
     */
    phone: Boolean = false,
    /**
     * An app id with no secret beside it (spec 2026-11-14, F1): the connect cannot
     * run, and saying "no app id" would be a lie about a different problem.
     */
    missingClientSecret: Boolean = false,
    onDismiss: () -> Unit,
    onConnect: suspend (token: String) -> String?,
    onDisconnect: () -> Unit,
    onSaveUsername: (String?) -> Unit
) {
    var token by remember { mutableStateOf("") }
    var username by remember { mutableStateOf(currentUsername.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    // A phone with nothing that handles https (rare, but possible on a stripped
    // build) falls back to the link and QR rather than a button that does nothing.
    var browserUnavailable by remember { mutableStateOf(false) }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        AnimeDialogPanel(phone = phone, title = "AniList") {
            Text(
                text = "AniList: ${state.label}. " +
                    if (state == AniListConnectionState.Connected) {
                        "Progress and ratings are written to your AniList list. " +
                            "Disconnecting stops the writes and keeps everything local."
                    } else {
                        "Connect to write progress and ratings to your AniList list, " +
                            "and to read a private list."
                    },
                color = KBTextLo,
                style = MaterialTheme.typography.bodyMedium
            )

            if (state != AniListConnectionState.Connected) {
                Spacer(modifier = Modifier.height(10.dp))
                when {
                    authorizeUrl == null && missingClientSecret -> Text(
                        text = "This build has no AniList app secret, so connecting is " +
                            "unavailable here - the code exchange needs it. The " +
                            "public-profile read below still works.",
                        color = KBTextLo,
                        style = MaterialTheme.typography.labelMedium
                    )
                    authorizeUrl == null -> Text(
                        text = "This build has no AniList app id, so connecting is " +
                            "unavailable here. The public-profile read below still works.",
                        color = KBTextLo,
                        style = MaterialTheme.typography.labelMedium
                    )
                    else -> {
                        // The code and the link are the same URL. Typing it with a
                        // remote was the only way in before, and it is a long URL
                        // that carries a client id - a phone camera is that step
                        // without the keyboard, and the QR panel prints the URL
                        // itself as well.
                        //
                        // TV ONLY. On the phone CONNECT opens the authorize page in
                        // the browser, so a code there would be scanned by the very
                        // device holding it.
                        if (phone) {
                            Text(
                                text = if (browserUnavailable) {
                                    "This device has no browser to open the authorize " +
                                        "page. Open the link below on another device and " +
                                        "approve there - the session arrives through " +
                                        "your profile."
                                } else {
                                    "Approve KBStream on AniList, and AniList sends you " +
                                        "straight back here. Nothing has to be typed."
                                },
                                color = KBTextHi,
                                style = MaterialTheme.typography.labelMedium,
                                fontSize = 12.sp
                            )
                        }
                        if (!phone || browserUnavailable) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "1. Open this link on your phone (or scan the " +
                                        "code) and approve KBStream:\n$authorizeUrl\n" +
                                        "2. The connected session reaches this device " +
                                        "through your profile.",
                                    color = KBTextHi,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontSize = 12.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                if (!phone) {
                                    KBQrCode(url = authorizeUrl)
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        // The fallback, not the flow: only needed when the browser
                        // could not hand the code back to this device.
                        AnimeDialogField(
                            phone = phone,
                            value = token,
                            onValueChange = { entered -> token = entered },
                            placeholder = "AniList code (only if the browser did not return)",
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            error?.let { message ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = message,
                    color = KBTextHi,
                    style = MaterialTheme.typography.labelMedium
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (state == AniListConnectionState.Connected) {
                    AnimeDialogButton(
                        phone = phone,
                        onClick = {
                            keyboard?.hide()
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
                } else if (authorizeUrl != null) {
                    AnimeDialogButton(
                        phone = phone,
                        onClick = {
                            if (busy) return@AnimeDialogButton
                            // THE PHONE STARTS THE FLOW IN THE BROWSER (S1): the
                            // state is stored, AniList's page opens, and the code
                            // comes back to the app on its own. The typed field is
                            // the fallback, so an empty one here is not an error.
                            if (phone && !browserUnavailable && token.isBlank()) {
                                keyboard?.hide()
                                error = null
                                if (!AniListLinks.openAuthorize(context)) {
                                    browserUnavailable = true
                                }
                                return@AnimeDialogButton
                            }
                            val pasted = token.trim()
                            if (pasted.isEmpty()) {
                                error = if (phone) {
                                    "Approve in the browser, or paste the code AniList " +
                                        "sent back"
                                } else {
                                    "Paste the code AniList sent back, or approve on " +
                                        "your phone"
                                }
                                return@AnimeDialogButton
                            }
                            keyboard?.hide()
                            busy = true
                            error = null
                            scope.launch {
                                val failure = onConnect(pasted)
                                // The pasted value does not outlive this attempt,
                                // win or lose.
                                token = ""
                                busy = false
                                if (failure == null) {
                                    onDismiss()
                                } else {
                                    error = failure
                                }
                            }
                        }
                    ) {
                        Text(
                            text = if (busy) "CONNECTING…" else "CONNECT",
                            color = KBTextHi,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))

                AnimeDialogButton(phone = phone, onClick = onDismiss) {
                    Text(
                        text = "CANCEL",
                        color = KBTextLo,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Or read a PUBLIC AniList profile - no account needed.",
                color = KBTextLo,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 11.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            AnimeDialogField(
                phone = phone,
                value = username,
                onValueChange = { entered -> username = entered },
                placeholder = "AniList username",
                modifier = Modifier.fillMaxWidth(),
                onDone = {
                    keyboard?.hide()
                    onSaveUsername(username.trim().takeIf { it.isNotEmpty() })
                }
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AnimeDialogButton(
                    phone = phone,
                    onClick = {
                        keyboard?.hide()
                        onSaveUsername(username.trim().takeIf { it.isNotEmpty() })
                    }
                ) {
                    Text(
                        text = "SAVE USERNAME",
                        color = KBTextHi,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                    )
                }
                AnimeDialogButton(
                    phone = phone,
                    onClick = {
                        keyboard?.hide()
                        username = ""
                        onSaveUsername(null)
                    }
                ) {
                    Text(
                        text = "CLEAR",
                        color = KBTextHi,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                    )
                }
            }
        }
    }
}
