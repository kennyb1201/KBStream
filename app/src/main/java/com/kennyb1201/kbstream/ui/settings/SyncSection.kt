package com.kennyb1201.kbstream.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.data.sync.SupabaseSync
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo

/**
 * Settings → Sync section: Supabase-backed cross-device sync.
 * TV-friendly email/password sign-in (D-pad between two fields + OK),
 * live status, manual "Sync now", and sign-out.
 */
@Composable
fun SyncSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val authState by SupabaseSync.authState.collectAsStateWithLifecycle()
    val lastSync by SupabaseSync.lastSyncAtMs.collectAsStateWithLifecycle()

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(authState) {
        busy = authState is SupabaseSync.AuthState.SigningIn
    }

    SectionHeader(title = "Sync (Beta)")

    when (val state = authState) {
        is SupabaseSync.AuthState.SignedIn -> {
            Text(
                text = "Signed in as ${state.email}",
                color = KBTextHi
            )
            Text(
                text = if (lastSync > 0L) {
                    "Last sync: " + android.text.format.DateFormat.getTimeFormat(context)
                        .format(lastSync)
                } else {
                    "Syncing…"
                },
                color = KBTextLo
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(top = 8.dp)
            ) {
                Button(
                    onClick = { SupabaseSync.syncNow(context) },
                    colors = ButtonDefaults.buttonColors(containerColor = KBAccent)
                ) {
                    Text("Sync now", color = Color.Black)
                }
                OutlinedButton(onClick = { SupabaseSync.signOut(context) }) {
                    Text("Sign out", color = KBTextHi)
                }
            }

            Text(
                text = "Syncs watch history, resume positions, watched status, " +
                    "add-ons, IPTV sources, Simkl sign-in, and display settings. " +
                    "Playback/decoder settings stay per-device.",
                color = KBTextLo,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        else -> {
            if (state is SupabaseSync.AuthState.Error) {
                Text(
                    text = state.message,
                    color = Color(0xFFE57373)
                )
            }

            OutlinedTextField(
                value = email,
                onValueChange = { email = it },
                label = { Text("Email") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 6.dp)
            ) {
                Button(
                    onClick = {
                        if (email.isNotBlank() && password.length >= 6) {
                            SupabaseSync.signIn(context, email, password)
                        }
                    },
                    enabled = !busy && email.isNotBlank() && password.length >= 6,
                    colors = ButtonDefaults.buttonColors(containerColor = KBAccent)
                ) {
                    Text(if (busy) "Signing in…" else "Sign in", color = Color.Black)
                }
                OutlinedButton(
                    onClick = {
                        if (email.isNotBlank() && password.length >= 6) {
                            SupabaseSync.signUp(context, email, password)
                        }
                    },
                    enabled = !busy && email.isNotBlank() && password.length >= 6
                ) {
                    Text("Create account", color = KBTextHi)
                }
            }

            Text(
                text = "One account keeps every device in step. New here? " +
                    "Create an account with any email + password (6+ chars).",
                color = KBTextLo,
                modifier = Modifier.padding(top = 6.dp)
            )
        }
    }
}
