package com.kennyb1201.kbstream.ui.onboarding

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Sync
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.data.sync.SupabaseSync
import com.kennyb1201.kbstream.ui.components.KBCard
import com.kennyb1201.kbstream.ui.components.KBTextField
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBDanger
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBSurfaceRaised
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid

/** First-run completion flag — lives in its own prefs file. */
object OnboardingPrefs {

    private const val PREFS = "kbstream_onboarding"
    private const val KEY_COMPLETE = "complete"

    fun isComplete(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_COMPLETE, false)

    fun setComplete(context: Context, complete: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_COMPLETE, complete)
            .apply()
    }
}

/**
 * First-run welcome screen shown until the user taps "Start Browsing".
 * It's a guided shortcut row into the three real setup surfaces — Add-ons,
 * Live TV (Guide), and Simkl — and never blocks: every action hands off to
 * the actual screens, and the user can finish setup later from Settings.
 */
@Composable
fun OnboardingScreen(
    onOpenAddons: () -> Unit,
    onOpenSimkl: () -> Unit,
    onOpenGuide: () -> Unit,
    onFinish: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(KBVoid)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 56.dp, vertical = 44.dp)
        ) {
            Spacer(modifier = Modifier.weight(0.55f))

            Text(
                text = "KBSTREAM",
                color = KBAccent,
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold,
                letterSpacing = 6.sp
            )
            Text(
                text = "Welcome — set up your sources in a couple of minutes",
                color = KBTextHi,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp)
            )
            Text(
                text = "Add streaming sources, live TV, and scrobbling. Everything here can be changed later in Settings.",
                color = KBTextLo,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 6.dp)
            )

            Spacer(modifier = Modifier.height(34.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                OnboardingCard(
                    title = "Add Add-ons",
                    description = "Stremio manifests for movies, series & more",
                    icon = Icons.Filled.Add,
                    onClick = onOpenAddons,
                    modifier = Modifier.weight(1f)
                )
                OnboardingCard(
                    title = "Live TV",
                    description = "Import an M3U playlist and EPG guide",
                    icon = Icons.Filled.LiveTv,
                    onClick = onOpenGuide,
                    modifier = Modifier.weight(1f)
                )
                OnboardingCard(
                    title = "Connect Simkl",
                    description = "Scrobble and sync your watched state",
                    icon = Icons.Filled.Sync,
                    onClick = onOpenSimkl,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(26.dp))

            // Account step. Sync is the one part of setup that needs an
            // ACCOUNT rather than a source, and it used to be reachable only
            // from Settings → Sync — so a new install on a second TV had no
            // obvious way to get its history, add-ons and settings from the
            // first one. Sign in or create the KBStream account right here; it
            // is optional like everything else on this screen.
            OnboardingAccountPanel()

            Spacer(modifier = Modifier.height(30.dp))

            androidx.tv.material3.Surface(
                onClick = onFinish,
                shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(14.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = KBAccent,
                    contentColor = KBVoid,
                    focusedContainerColor = KBAccent,
                    focusedContentColor = KBVoid,
                    pressedContainerColor = KBAccent.copy(alpha = 0.85f),
                    pressedContentColor = KBVoid
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
                glow = ClickableSurfaceDefaults.glow(
                    focusedGlow = Glow(elevationColor = KBAccent, elevation = 10.dp)
                ),
                modifier = Modifier.width(360.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = KBVoid,
                        modifier = Modifier.size(20.dp)
                    )
                    Text(
                        text = "START BROWSING",
                        color = KBVoid,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 10.dp)
                    )
                }
            }

            Text(
                text = "You can also skip everything and browse now.",
                color = KBTextLo,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 12.dp)
            )

            Spacer(modifier = Modifier.weight(0.55f))
        }
    }
}

/**
 * KBStream sign in / create account, inline on the welcome screen.
 *
 * "KBStream account" is the user-facing name for it — the backend behind it is
 * an implementation detail the user never has to know. Mirrors the Settings →
 * Sync form (same calls, same email + 6-character-password rule) so the two
 * cannot drift; the panel swaps itself for a confirmation strip once signed in,
 * and the app's own auth hooks start the first sync from there.
 */
@Composable
private fun OnboardingAccountPanel() {
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
    val shape = RoundedCornerShape(20.dp)
    // Only ever "the KBStream account" in front of the user: which backend
    // stores it is our business, not theirs.

    androidx.tv.material3.Surface(
        // Plain (non-clickable) Surface: it takes a Shape, SurfaceColors and a
        // Border, not the Clickable*Defaults variants the cards below use.
        shape = shape,
        colors = SurfaceDefaults.colors(
            containerColor = KBSurface.copy(alpha = 0.95f),
            contentColor = KBTextHi
        ),
        border = Border(
            border = BorderStroke(1.dp, KBAccent.copy(alpha = 0.28f)),
            shape = shape
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 22.dp, vertical = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(40.dp)
                        .background(KBAccent.copy(alpha = 0.16f), RoundedCornerShape(12.dp))
                ) {
                    Icon(
                        imageVector = if (signedIn != null) {
                            Icons.Filled.Check
                        } else {
                            Icons.Filled.AccountCircle
                        },
                        contentDescription = null,
                        tint = KBAccent,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Column(modifier = Modifier.padding(start = 14.dp)) {
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
                            "${signedIn.email} — watch history, resume positions, " +
                                "add-ons, IPTV and settings now follow this account " +
                                "to every device."
                        } else {
                            "One KBStream account keeps every device in step: watch " +
                                "history, resume positions, watched status, add-ons, " +
                                "IPTV and settings. Sign in with the one you already " +
                                "use on your other TVs, or make one here."
                        },
                        color = KBTextLo,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
            }

            if (signedIn == null) {
                (authState as? SupabaseSync.AuthState.Error)?.let { error ->
                    Text(
                        text = error.message,
                        color = KBDanger,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }
                syncError?.takeIf { authState !is SupabaseSync.AuthState.Error }?.let { err ->
                    Text(
                        text = err,
                        color = KBDanger,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 12.dp)
                ) {
                    KBTextField(
                        value = email,
                        onValueChange = { email = it },
                        placeholder = "Email",
                        keyboardType = KeyboardType.Email,
                        // Committing a field must not drop focus, or the next
                        // D-pad Down escapes the form (same as Settings → Sync).
                        keepFocusOnDone = true,
                        modifier = Modifier.weight(1f)
                    )
                    KBTextField(
                        value = password,
                        onValueChange = { password = it },
                        placeholder = "Password (6+ characters)",
                        keyboardType = KeyboardType.Password,
                        visualTransformation = PasswordVisualTransformation(),
                        keepFocusOnDone = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 12.dp)
                ) {
                    OnboardingActionButton(
                        label = if (busy) "Signing in…" else "SIGN IN",
                        enabled = !busy && credentialsValid
                    ) {
                        SupabaseSync.signIn(context, email, password)
                    }
                    OnboardingActionButton(
                        label = "CREATE ACCOUNT",
                        enabled = !busy && credentialsValid
                    ) {
                        SupabaseSync.signUp(context, email, password)
                    }
                    Text(
                        text = "Any email + password (6+ characters). Already made " +
                            "one on another TV? Sign in with the same email.",
                        color = KBTextLo.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        }
    }
}

/** Same shape as the Settings → Sync buttons: a KBCard when enabled, a dimmed
 * non-focusable surface when not. */
@Composable
private fun OnboardingActionButton(
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
        androidx.tv.material3.Surface(
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

@Composable
private fun OnboardingCard(
    title: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    androidx.tv.material3.Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(20.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = KBSurface.copy(alpha = 0.95f),
            contentColor = KBTextHi,
            focusedContainerColor = KBSurfaceRaised,
            focusedContentColor = KBAccent,
            pressedContainerColor = KBSurfaceRaised,
            pressedContentColor = KBAccent
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.03f),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, KBAccent.copy(alpha = 0.28f)),
                shape = RoundedCornerShape(20.dp)
            ),
            focusedBorder = Border(
                border = BorderStroke(2.dp, KBAccent),
                shape = RoundedCornerShape(20.dp)
            )
        ),
        glow = ClickableSurfaceDefaults.glow(
            focusedGlow = Glow(elevationColor = KBAccent, elevation = 8.dp)
        ),
        modifier = modifier
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 26.dp)
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(58.dp)
                    .background(KBAccent.copy(alpha = 0.16f), RoundedCornerShape(16.dp))
                    .border(1.dp, KBAccent.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = KBAccent,
                    modifier = Modifier.size(30.dp)
                )
            }
            Text(
                text = title,
                color = KBTextHi,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 16.dp)
            )
            Text(
                text = description,
                color = KBTextLo,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 5.dp)
            )
        }
    }
}