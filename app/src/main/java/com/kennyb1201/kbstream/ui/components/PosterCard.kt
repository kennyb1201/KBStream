package com.kennyb1201.kbstream.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.kennyb1201.kbstream.ui.settings.AppPreferences
import com.kennyb1201.kbstream.ui.theme.KBSurface
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid

@Composable
fun WatchedCheckBadge(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(KBVoid.copy(alpha = 0.8f))
            .border(1.dp, KBTextHi.copy(alpha = 0.95f), CircleShape)
            // The badge is the only place this state is stated, so it needs a
            // spoken label: without it a screen reader announces the bare
            // "✓" glyph (or nothing) beside the poster's title.
            .clearAndSetSemantics { contentDescription = "Watched" },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "✓",
            color = KBTextHi,
            fontSize = 13.sp
        )
    }
}

/**
 * Eye badge for shows the user has STARTED but not finished. Identical
 * treatment to [WatchedCheckBadge] — same circle, same scrim, same warm
 * white for border and glyph — so the two read as one marker family; the
 * eye shape itself is what distinguishes started-but-unfinished from the
 * completed check.
 */
@Composable
fun WatchedEyeBadge(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(KBVoid.copy(alpha = 0.8f))
            .border(1.dp, KBTextHi.copy(alpha = 0.95f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Visibility,
            contentDescription = "Started, not finished",
            tint = KBTextHi,
            modifier = Modifier.size(13.dp)
        )
    }
}

/**
 * Shared poster tile for every screen that renders a catalog/meta poster
 * (Home rails, search, detail recommendations, etc.). Wraps KBCard with the
 * image and, when isWatched is true, a small checkmark badge in the corner.
 * A show that is started-but-not-finished (isPartiallyWatched) shows the
 * eye badge instead — the completed checkmark always wins.
 */
@Composable
fun PosterCard(
    posterUrl: String?,
    contentDescription: String?,
    isWatched: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    isPartiallyWatched: Boolean = false,
    onPosterError: ((Throwable?) -> Unit)? = null,
    overlayContent: (@Composable BoxScope.() -> Unit)? = null 
) {
    val context = LocalContext.current
    var hasError by remember(posterUrl) { mutableStateOf(false) }

    // Settings toggle: the eye badge (started-but-not-finished shows) can be
    // switched off app-wide; the completed checkmark is always unaffected.
    val showEyeBadge = isPartiallyWatched &&
        AppPreferences.getPosterPartialWatchBadge(context)

    KBCard(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(KBSurface) // Visual fallback background container
        ) {
            if (!posterUrl.isNullOrBlank() && !hasError) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(posterUrl).build(),
                    contentDescription = contentDescription,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    onError = { state -> 
                        hasError = true
                        onPosterError?.invoke(state.result.throwable) 
                    }
                )
            } else {
                // Fallback text view when URL is missing or failed to load
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = contentDescription ?: "No Image",
                        color = KBTextLo,
                        fontSize = 12.sp,
                        maxLines = 3
                    )
                }
            }

            if (isWatched) {
                WatchedCheckBadge(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                )
            } else if (showEyeBadge) {
                WatchedEyeBadge(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                )
            }
                 overlayContent?.invoke(this)
        }
        
    }
}
