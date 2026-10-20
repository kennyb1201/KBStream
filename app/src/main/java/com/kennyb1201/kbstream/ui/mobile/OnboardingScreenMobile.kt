package com.kennyb1201.kbstream.ui.mobile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kennyb1201.kbstream.data.sync.SupabaseSync
import com.kennyb1201.kbstream.ui.components.touch.KBButton
import com.kennyb1201.kbstream.ui.components.touch.KBCard
import com.kennyb1201.kbstream.ui.components.touch.KBTextField
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBDanger
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBShapePanel
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid
import com.kennyb1201.kbstream.ui.theme.MobileTheme

/**
 * First-run setup, for a finger.
 *
 * The TV page is a two-column `Row` - the account form in a fixed 392dp pane on
 * the left, the three setup rows in the weight pane on the right - and that is
 * exactly what broke on a phone. On a 411dp-wide screen the fixed pane takes the
 * whole width, so the source rows were laid out past the right edge with nothing
 * to scroll towards them, and the only thing a new phone user could see was the
 * sign-in form. Worse, the fields it did show were the TV `KBTextField`, a
 * D-pad-driven `BasicTextField`: a tap focused it but the leanback IME never
 * opened one, so onboarding was a dead end on touch.
 *
 * So this is ONE vertical scroll stacking everything in reading order - welcome
 * copy, account panel, Add Add-ons, Live TV, Connect Simkl, START BROWSING, the
 * skip note - with every control a full-width touch target. The keyboard fix is
 * the field itself: [KBTextField] here is the touch twin, a Material 3
 * `OutlinedTextField`, so the platform IME opens on tap because that is what a
 * tap on an `OutlinedTextField` does.
 *
 * Nothing about the contract changes: the same four callbacks, and completion
 * still goes through `OnboardingPrefs`, so finishing here satisfies the TV's
 * check and vice versa. Signing in makes the same `SupabaseSync` calls as
 * Settings → Sync, and the panel still swaps itself for the "signed in" strip -
 * the TV's re-aiming of focus has no touch twin, because there is no focus to
 * lose.
 */
@Composable
fun OnboardingScreenMobile(
    onOpenAddons: () -> Unit,
    onOpenSimkl: () -> Unit,
    onOpenGuide: () -> Unit,
    onFinish: () -> Unit
) {
    MobileTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(KBVoid)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Column {
                    Text(
                        text = "KBSTREAM",
                        color = KBAccent,
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 4.sp
                    )
                    Text(
                        text = "Welcome — set up your sources in a couple of minutes",
                        color = KBTextHi,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    Text(
                        text = "None of this is required, and every part of it can be " +
                            "changed later in Settings.",
                        color = KBTextLo,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                OnboardingAccountPanelMobile()

                // The three source hand-offs, in the same order as the TV page:
                // the account is the one step that can arrive already finished,
                // these are for an install with nothing to inherit and for adding
                // a source afterwards.
                OnboardingSourceRowMobile(
                    title = "Add Add-ons",
                    description = "Stremio manifests for movies, series & more",
                    icon = Icons.Filled.Add,
                    onClick = onOpenAddons
                )
                OnboardingSourceRowMobile(
                    title = "Live TV",
                    description = "Import an M3U playlist and EPG guide",
                    icon = Icons.Filled.LiveTv,
                    onClick = onOpenGuide
                )
                OnboardingSourceRowMobile(
                    title = "Connect Simkl",
                    description = "Scrobble and sync your watched state",
                    icon = Icons.Filled.Sync,
                    onClick = onOpenSimkl
                )

                Spacer(modifier = Modifier.height(2.dp))

                // The accent plate is the one primary action on this page, so it
                // is a filled Material 3 button on the theme's own primary
                // (KBAccent with the void label) rather than the raised-plate
                // KBButton every secondary action uses.
                Button(
                    onClick = onFinish,
                    shape = KBShapeCard,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = KBAccent,
                        contentColor = KBVoid
                    ),
                    contentPadding = PaddingValues(horizontal = 22.dp, vertical = 16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 54.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "START BROWSING",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 10.dp)
                    )
                }

                Text(
                    text = "You can also skip everything and browse now.",
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

/**
 * KBStream sign in / create account, inline on the welcome screen.
 *
 * Same calls and same 6-character-password rule as the TV panel and Settings →
 * Sync, so the three cannot drift. What is dropped is the TV panel's focus
 * choreography: nothing here has to re-aim a D-pad when the form is replaced by
 * the signed-in strip, so the panel just reads the auth state and swaps.
 */
@Composable
private fun OnboardingAccountPanelMobile() {
    val context = LocalContext.current
    val authState by SupabaseSync.authState.collectAsStateWithLifecycle()
    val syncError by SupabaseSync.syncError.collectAsStateWithLifecycle()

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(authState) {
        busy = authState is SupabaseSync.AuthState.SigningIn
    }

    val signedIn = authState as? SupabaseSync.AuthState.SignedIn
    val credentialsValid = email.isNotBlank() && password.length >= 6

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(KBSurface, KBShapePanel)
            .border(1.dp, KBAccent.copy(alpha = 0.28f), KBShapePanel)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(38.dp)
                    .background(KBAccent.copy(alpha = 0.16f), KBShapeCard)
            ) {
                Icon(
                    imageVector = if (signedIn != null) {
                        Icons.Filled.Check
                    } else {
                        Icons.Filled.AccountCircle
                    },
                    contentDescription = null,
                    tint = KBAccent,
                    modifier = Modifier.size(21.dp)
                )
            }
            Column(modifier = Modifier.padding(start = 12.dp)) {
                Text(
                    text = if (signedIn != null) {
                        "SIGNED IN — SYNC IS ON"
                    } else {
                        "SIGN IN TO SYNC (OPTIONAL)"
                    },
                    color = if (signedIn != null) KBAccent else KBTextHi,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = if (signedIn != null) {
                        "${signedIn.email} — watch history, resume positions, watched " +
                            "status, add-ons, IPTV and settings now follow this account " +
                            "to every device."
                    } else {
                        "One account keeps every device in step: watch history, resume " +
                            "positions, watched status, add-ons, IPTV and settings."
                    },
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }

        if (signedIn == null) {
            (authState as? SupabaseSync.AuthState.Error)?.let { error ->
                Text(
                    text = error.message,
                    color = KBDanger,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            syncError?.takeIf { authState !is SupabaseSync.AuthState.Error }?.let { err ->
                Text(
                    text = err,
                    color = KBDanger,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            KBTextField(
                value = email,
                onValueChange = { email = it },
                placeholder = "Email",
                keyboardType = KeyboardType.Email,
                modifier = Modifier.fillMaxWidth()
            )
            KBTextField(
                value = password,
                onValueChange = { password = it },
                placeholder = "Password (6+ characters)",
                keyboardType = KeyboardType.Password,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                KBButton(
                    label = if (busy) "SIGNING IN…" else "SIGN IN",
                    enabled = credentialsValid && !busy,
                    modifier = Modifier.weight(1f)
                ) {
                    SupabaseSync.signIn(context, email, password)
                }
                KBButton(
                    label = "CREATE ACCOUNT",
                    enabled = credentialsValid && !busy,
                    modifier = Modifier.weight(1f)
                ) {
                    SupabaseSync.signUp(context, email, password)
                }
            }

            Text(
                text = "Any email + password (6+ characters). Already made one on " +
                    "another device? Sign in with the same email.",
                color = KBTextLo.copy(alpha = 0.8f),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

/**
 * One setup hand-off: a full-width tappable row rather than the TV's compact
 * strip, because on a phone the row IS the target and a thumb wants the whole
 * width of it.
 */
@Composable
private fun OnboardingSourceRowMobile(
    title: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    KBCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = KBShapePanel
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(42.dp)
                    .background(KBAccent.copy(alpha = 0.16f), KBShapeCard)
                    .border(1.dp, KBAccent.copy(alpha = 0.4f), KBShapeCard)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = KBAccent,
                    modifier = Modifier.size(22.dp)
                )
            }
            Column(modifier = Modifier.padding(start = 14.dp)) {
                Text(
                    text = title,
                    color = KBTextHi,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = description,
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}
