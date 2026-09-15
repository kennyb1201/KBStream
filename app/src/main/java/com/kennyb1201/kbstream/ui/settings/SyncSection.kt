package com.kennyb1201.kbstream.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.data.sync.SupabaseSync
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.components.KBTextField
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBDanger

/**
 * Settings → Sync section: Supabase-backed cross-device sync.
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

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(authState) {
        busy = authState is SupabaseSync.AuthState.SigningIn
    }

    SectionHeader(title = "Sync (Beta)")

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

            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp)
            ) {
                SyncActionButton(
                    label = "Sync now",
                    enabled = !busy
                ) {
                    SupabaseSync.syncNow(context)
                }
                SyncActionButton(
                    label = "Sign out",
                    enabled = true
                ) {
                    SupabaseSync.signOut(context)
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
            KBTextField(
                value = password,
                onValueChange = { password = it },
                placeholder = "Password (6+ characters)",
                keyboardType = KeyboardType.Password,
                visualTransformation = PasswordVisualTransformation(),
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
                SyncActionButton(
                    label = if (busy) "Signing in…" else "Sign in",
                    enabled = !busy && credentialsValid
                ) {
                    SupabaseSync.signIn(context, email, password)
                }
                SyncActionButton(
                    label = "Create account",
                    enabled = !busy && credentialsValid
                ) {
                    SupabaseSync.signUp(context, email, password)
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

/**
 * One TV-focusable pill button for the Sync section. Follows the app-wide
 * ActionButton pattern: a KBCard when enabled (D-pad focusable, OK fires
 * onClick), a dimmed non-focusable Surface when disabled. Text inherits the
 * card's content color (bright when idle, accent when focused).
 */
@Composable
private fun SyncActionButton(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    if (enabled) {
        KBCard(onClick = onClick) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
            )
        }
    } else {
        Surface(
            shape = RoundedCornerShape(12.dp),
            colors = SurfaceDefaults.colors(
                containerColor = KBSurface.copy(alpha = 0.50f),
                contentColor = KBTextLo.copy(alpha = 0.50f)
            )
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = KBTextLo.copy(alpha = 0.50f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
            )
        }
    }
}
