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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.kennyb1201.kbstream.ui.theme.KBFocusButton
import com.kennyb1201.kbstream.ui.theme.KBFocusCard
import com.kennyb1201.kbstream.ui.theme.KBFocusGlowSmall
import com.kennyb1201.kbstream.ui.theme.KBFocusPressed
import com.kennyb1201.kbstream.ui.theme.KBShapeCard
import com.kennyb1201.kbstream.ui.theme.KBShapePanel
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
 *
 * Two panes rather than one column, and that is a layout fix, not a taste one.
 * The TV canvas is ~960x540dp whatever the panel resolution (1080p reports at
 * 2px/dp, 4K at 4), so a single column stacking the welcome copy, the three
 * setup cards, the account form and the start button came to roughly 800dp of
 * content against 540dp of screen. Everything past the first setup card ran off
 * the bottom edge with nothing to scroll — the account form and "Start
 * Browsing" itself were unreachable from the D-pad, the same failure the
 * Settings rail documents. The TV is wide and short, so the fix is to use the
 * width it has: the account form on the left, the source rows in their own
 * column on the right, and both fit with room to spare.
 *
 * The account step is the FIRST of the four options, ahead of the three source
 * hand-offs. It is the only one that can arrive already finished — on a second
 * TV, signing in brings this device's add-ons, IPTV and settings *from* the
 * account, so the source rows are usually not needed at all. They stay for an
 * install with nothing to inherit, and for adding a source afterwards.
 *
 * It never blocks: every action hands off to a real screen, signing in is
 * optional, and the user can finish setup later from Settings. The scroll is
 * insurance for a larger system font scale rather than something anyone should
 * need — focus scrolls the focused control into view on its own.
 */
@Composable
fun OnboardingScreen(
    onOpenAddons: () -> Unit,
    onOpenSimkl: () -> Unit,
    onOpenGuide: () -> Unit,
    onFinish: () -> Unit
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(KBVoid)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 48.dp, vertical = 24.dp)
        ) {
            Column(modifier = Modifier.width(392.dp)) {
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

                Spacer(modifier = Modifier.height(18.dp))

                // The account step comes FIRST, ahead of the three source
                // hand-offs, because it is the only step that can arrive
                // already finished: signing in brings this device's add-ons,
                // IPTV and settings from the account. It also used to be
                // reachable only from Settings → Sync, so a new install on a
                // second TV had no obvious way to get any of it.
                OnboardingAccountPanel()
            }

            Spacer(modifier = Modifier.width(34.dp))

            Column(modifier = Modifier.weight(1f)) {
                OnboardingSourceRow(
                    title = "Add Add-ons",
                    description = "Stremio manifests for movies, series & more",
                    icon = Icons.Filled.Add,
                    onClick = onOpenAddons
                )
                Spacer(modifier = Modifier.height(10.dp))
                OnboardingSourceRow(
                    title = "Live TV",
                    description = "Import an M3U playlist and EPG guide",
                    icon = Icons.Filled.LiveTv,
                    onClick = onOpenGuide
                )
                Spacer(modifier = Modifier.height(10.dp))
                OnboardingSourceRow(
                    title = "Connect Simkl",
                    description = "Scrobble and sync your watched state",
                    icon = Icons.Filled.Sync,
                    onClick = onOpenSimkl
                )

                Spacer(modifier = Modifier.height(22.dp))

                androidx.tv.material3.Surface(
                    onClick = onFinish,
                    shape = ClickableSurfaceDefaults.shape(shape = KBShapeCard),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = KBAccent,
                        contentColor = KBVoid,
                        focusedContainerColor = KBAccent,
                        focusedContentColor = KBVoid,
                        pressedContainerColor = KBAccent.copy(alpha = 0.85f),
                        pressedContentColor = KBVoid
                    ),
                    scale = ClickableSurfaceDefaults.scale(
                        focusedScale = KBFocusButton,
                        pressedScale = KBFocusPressed
                    ),
                    glow = ClickableSurfaceDefaults.glow(
                        focusedGlow = Glow(elevationColor = KBAccent, elevation = KBFocusGlowSmall)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 22.dp, vertical = 13.dp)
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
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
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
    val shape = KBShapePanel

    androidx.tv.material3.Surface(
        // Plain (non-clickable) Surface: it takes a Shape, SurfaceColors and a
        // Border, not the Clickable*Defaults variants the rows beside it use.
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
        Column(modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
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
                            "${signedIn.email} — watch history, resume positions, " +
                                "watched status, add-ons, IPTV and settings now " +
                                "follow this account to every device."
                        } else {
                            "One account keeps every device in step: watch " +
                                "history, resume positions, watched status, " +
                                "add-ons, IPTV and settings."
                        },
                        color = KBTextLo,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 2.dp)
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

                // Stacked, like Settings → Sync: two fields side by side in a
                // column this narrow would put the password's own placeholder
                // out of view.
                KBTextField(
                    value = email,
                    onValueChange = { email = it },
                    placeholder = "Email",
                    keyboardType = KeyboardType.Email,
                    // Committing a field must not drop focus, or the next
                    // D-pad Down escapes the form (same as Settings → Sync).
                    keepFocusOnDone = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
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
                        .padding(top = 10.dp)
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 12.dp)
                ) {
                    OnboardingActionButton(
                        label = if (busy) "SIGNING IN…" else "SIGN IN",
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
                }

                Text(
                    text = "Any email + password (6+ characters). Already made one " +
                        "on another TV? Sign in with the same email.",
                    color = KBTextLo.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
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
            shape = KBShapeCard,
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

/**
 * One setup hand-off: a compact row rather than a tall card, so three of them
 * plus the welcome copy still fit above the start button on a 540dp canvas.
 */
@Composable
private fun OnboardingSourceRow(
    title: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    androidx.tv.material3.Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = KBShapePanel),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = KBSurface.copy(alpha = 0.95f),
            contentColor = KBTextHi,
            focusedContainerColor = KBSurfaceRaised,
            focusedContentColor = KBAccent,
            pressedContainerColor = KBSurfaceRaised,
            pressedContentColor = KBAccent
        ),
        scale = ClickableSurfaceDefaults.scale(
            focusedScale = KBFocusCard,
            pressedScale = KBFocusPressed
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, KBAccent.copy(alpha = 0.28f)),
                shape = KBShapePanel
            ),
            focusedBorder = Border(
                border = BorderStroke(2.dp, KBAccent),
                shape = KBShapePanel
            )
        ),
        glow = ClickableSurfaceDefaults.glow(
            focusedGlow = Glow(elevationColor = KBAccent, elevation = KBFocusGlowSmall)
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
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
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = description,
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}
