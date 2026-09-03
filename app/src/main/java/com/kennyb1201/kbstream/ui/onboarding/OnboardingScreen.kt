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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Sync
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.kennyb1201.kbstream.ui.theme.KBAccent
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

            Spacer(modifier = Modifier.height(34.dp))

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