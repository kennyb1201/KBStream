package com.kennyb1201.kbstream.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.data.sync.SupabaseSync
import com.kennyb1201.kbstream.ui.components.KBButton
import com.kennyb1201.kbstream.ui.components.KBSecretField
import com.kennyb1201.kbstream.ui.components.KBTextField
import com.kennyb1201.kbstream.ui.theme.KBDanger
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo

/**
 * Settings → Sync → Account: Supabase-backed cross-device sign-in.
 *
 * It used to render in the Integrations pane under a "Sync (Beta)" heading the
 * pane above drew. Both moved: the pane above no longer draws a heading for it,
 * and the SYNC pane draws "Account" as its first section (settings reorg §10.1,
 * Q1) - an account belongs beside sync health, not above Profiles.
 *
 * TV input model: the sign-in fields use the app's [KBTextField] (bright
 * text, leanback-keyboard friendly, D-pad up/down between fields) and the
 * actions are [KBCard]-based buttons. The previous material3
 * Button/OutlinedTextField combo was phone-style — its buttons never took
 * D-pad focus here (sign in / create account were unclickable) and the
 * typed text rendered in a dark color on the dark surface.
 */
@Composable
fun SyncSection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val authState by SupabaseSync.authState.collectAsStateWithLifecycle()
    val lastSync by SupabaseSync.lastSyncAtMs.collectAsStateWithLifecycle()
    val syncError by SupabaseSync.syncError.collectAsStateWithLifecycle()

    // Read once for the row below (and stable across the section's
    // recompositions): a kids profile does not get the account controls.
    val kidsProfile = com.kennyb1201.kbstream.data.sync.ProfileManager
        .activeProfile.collectAsStateWithLifecycle().value
        ?.kidsMaxAge != null

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(authState) {
        busy = authState is SupabaseSync.AuthState.SigningIn
    }

    // The pane above this section already draws its "SYNC (BETA)" heading (see
    // SettingsScreen's Integrations pane), so a second heading here stacked two
    // labels on the same section.

    val credentialsValid =
        email.isNotBlank() && password.length >= 6

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
            syncError?.let { err ->
                Text(
                    text = err,
                    color = KBDanger,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp)
            ) {
                KBButton(
                    label = "SYNC NOW",
                    onClick = { SupabaseSync.syncNow(context) },
                    enabled = !busy
                )
                // m10 (audit): signing the household out of the sync account
                // is not a kids-profile action - it is the account the parent
                // set up, and the press is one tap with no confirmation. Not
                // drawn here; an adult profile still signs out normally.
                if (!kidsProfile) {
                    KBButton(
                        label = "SIGN OUT",
                        onClick = { SupabaseSync.signOut(context) }
                    )
                }
            }

            Text(
                text = "Syncs watch history, resume positions, watched status, " +
                    "add-ons, IPTV sources, Simkl sign-in, display settings, and " +
                    "your subtitle style + audio/subtitle language choices. " +
                    "Decoder settings stay per-device.",
                color = KBTextLo,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        else -> {
            if (state is SupabaseSync.AuthState.Error) {
                Text(
                    text = state.message,
                    color = KBDanger
                )
            }

            KBTextField(
                value = email,
                onValueChange = { email = it },
                placeholder = "Email",
                keyboardType = KeyboardType.Email,
                // Modal-style form on a scrolling page: committing a field
                // must not drop focus or the next Down escapes into the
                // page scroll behind the form.
                keepFocusOnDone = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            )
            // A masked KBSecretField rather than a hand-rolled password box:
            // every API key field in this app is a KBSecretField with a reveal
            // chip, and this - the one field a viewer types a password into with
            // a remote - was the only masked input with no way to check what was
            // entered.
            // keepFocusOnDone carries over: this is a modal-style form on a
            // scrolling page, so committing must not drop focus.
            KBSecretField(
                value = password,
                onValueChange = { password = it },
                placeholder = "Password (6+ characters)",
                keepFocusOnDone = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 6.dp)
            ) {
                KBButton(
                    label = if (busy) "SIGNING IN…" else "SIGN IN",
                    onClick = { SupabaseSync.signIn(context, email, password) },
                    enabled = !busy && credentialsValid
                )
                KBButton(
                    label = "CREATE ACCOUNT",
                    onClick = { SupabaseSync.signUp(context, email, password) },
                    enabled = !busy && credentialsValid
                )
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



