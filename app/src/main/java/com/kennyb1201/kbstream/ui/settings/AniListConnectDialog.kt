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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.data.anime.AniListConnectionState
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import kotlinx.coroutines.launch

/**
 * AniList's connect (spec 2026-11-13 phase 2, F1).
 *
 * ONE FLOW FOR BOTH SURFACES, and it is text fields because that is what a
 * D-pad can drive: the viewer opens the authorize link on a phone (the TV shows
 * it as text), approves KBStream on AniList, and AniList's auth-pin page shows
 * them an ACCESS TOKEN. That token is pasted here.
 *
 * WHY NO PASSWORD AND NO SECRET: AniList's Authorization Code grant needs the
 * app's client secret in a token exchange, and a secret compiled into an APK is
 * recoverable from every published build (`SimklClientConfigTest` fails the build
 * for one). AniList's own docs send a client that cannot store a secret to the
 * implicit grant, which returns the token to the viewer directly - so nothing
 * secret exists to leak, and no token exchange exists to fail.
 *
 * THE PASTED VALUE IS THE CREDENTIAL. It is never logged, never written to a
 * pref, and only reaches the store after AniList itself has accepted it.
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
                    authorizeUrl == null -> Text(
                        text = "This build has no AniList app id, so connecting is " +
                            "unavailable here. The public-profile read below still works.",
                        color = KBTextLo,
                        style = MaterialTheme.typography.labelMedium
                    )
                    else -> {
                        Text(
                            text = "1. Open this link on your phone (or any browser) and " +
                                "approve KBStream:\n$authorizeUrl\n" +
                                "2. AniList shows a token. Copy it and paste it below.",
                            color = KBTextHi,
                            style = MaterialTheme.typography.labelMedium,
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        AnimeDialogField(
                            phone = phone,
                            value = token,
                            onValueChange = { entered -> token = entered },
                            placeholder = "AniList token",
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
                            val pasted = token.trim()
                            if (pasted.isEmpty()) {
                                error = "Paste the token AniList showed you"
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
