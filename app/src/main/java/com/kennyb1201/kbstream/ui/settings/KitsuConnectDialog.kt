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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.data.kitsu.KitsuConnectionState
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.components.KBTextField
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import kotlinx.coroutines.launch

/**
 * Kitsu's connect (spec 2026-11-13 phase 2, F2).
 *
 * Kitsu's connect is the resource-owner password grant: an email and a password,
 * exchanged once for a token. That is what makes it the one tracker whose connect
 * works identically on a TV and a phone - there is no browser to open, no
 * redirect to catch and no QR code to scan, only text fields a D-pad can drive.
 *
 * THE PASSWORD IS TYPED ONCE AND NEVER STORED. It lives in this dialog's own
 * state until CONNECT is pressed, is handed to [onConnect], and is dropped with
 * the dialog; nothing here logs it, saves it or writes it to a file. What
 * survives the connect is the token, in the encrypted per-profile store - so the
 * account reaches the viewer's other devices through sync without the password
 * ever leaving this screen.
 *
 * The public-username row is still here, because the phase-1 read needs no
 * account at all and a viewer who only wants their public list keeps it.
 */
@Composable
internal fun KitsuConnectDialog(
    state: KitsuConnectionState,
    currentUsername: String?,
    /** True on the phone, whose plate is the touch twin (see [AnimeDialogPanel]). */
    phone: Boolean = false,
    onDismiss: () -> Unit,
    onConnect: suspend (email: String, password: String) -> String?,
    onDisconnect: () -> Unit,
    onSaveUsername: (String?) -> Unit
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var username by remember { mutableStateOf(currentUsername.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val connectFocusRequester = remember { FocusRequester() }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        AnimeDialogPanel(phone = phone, title = "Kitsu") {
            Text(
                text = "Kitsu: ${state.label}. " +
                    if (state == KitsuConnectionState.Connected) {
                        "Progress and ratings are written to your Kitsu library. " +
                            "Disconnecting stops the writes and keeps everything local."
                    } else {
                        "Connecting stores an access token for this profile, encrypted " +
                            "and synced - the email and password are used once and never " +
                            "saved."
                    },
                color = KBTextLo,
                style = MaterialTheme.typography.bodyMedium
            )

            if (state != KitsuConnectionState.Connected) {
                Spacer(modifier = Modifier.height(10.dp))
                KBTextField(
                    value = email,
                    onValueChange = { entered -> email = entered },
                    placeholder = "Kitsu email",
                    modifier = Modifier.fillMaxWidth(),
                    keyboardType = KeyboardType.Email
                )
                Spacer(modifier = Modifier.height(8.dp))
                KBTextField(
                    value = password,
                    onValueChange = { entered -> password = entered },
                    placeholder = "Kitsu password",
                    modifier = Modifier.fillMaxWidth(),
                    keyboardType = KeyboardType.Password,
                    // The password is never drawn on the screen it is typed on.
                    visualTransformation = PasswordVisualTransformation()
                )
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
                if (state == KitsuConnectionState.Connected) {
                    KBCard(
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
                } else {
                    KBCard(
                        onClick = {
                            if (busy) return@KBCard
                            val typedEmail = email.trim()
                            if (typedEmail.isEmpty() || password.isEmpty()) {
                                error = "Enter your Kitsu email and password"
                                return@KBCard
                            }
                            keyboard?.hide()
                            busy = true
                            error = null
                            scope.launch {
                                // The password goes straight into the exchange's
                                // frame; it is not copied anywhere that outlives it.
                                val failure = onConnect(typedEmail, password)
                                password = ""
                                busy = false
                                if (failure == null) {
                                    onDismiss()
                                } else {
                                    error = failure
                                }
                            }
                        },
                        modifier = Modifier.focusRequester(connectFocusRequester)
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

                KBCard(onClick = onDismiss) {
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
                text = "Or read a PUBLIC Kitsu profile - no account needed.",
                color = KBTextLo,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 11.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            KBTextField(
                value = username,
                onValueChange = { entered -> username = entered },
                placeholder = "Kitsu username",
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
                KBCard(
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
                KBCard(
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
