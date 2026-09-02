package com.kennyb1201.kbstream.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest

@Composable
fun WatchedCheckBadge(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(Color(0xCC111111))
            .border(1.dp, Color.White.copy(alpha = 0.95f), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "✓",
            color = Color.White,
            fontSize = 13.sp
        )
    }
}

/**
 * Shared poster tile for every screen that renders a catalog/meta poster
 * (Home rails, search, detail recommendations, etc.). Wraps KBCard with the
 * image and, when isWatched is true, a small checkmark badge in the corner.
 */
@Composable
fun PosterCard(
    posterUrl: String?,
    contentDescription: String?,
    isWatched: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    onPosterError: ((Throwable?) -> Unit)? = null,
    overlayContent: (@Composable BoxScope.() -> Unit)? = null 
) {
    val context = LocalContext.current
    var hasError by remember(posterUrl) { mutableStateOf(false) }

    KBCard(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF1B3A57)) // Visual fallback background container
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
                        color = Color.LightGray,
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
            }
                 overlayContent?.invoke(this)
        }
        
    }
}
