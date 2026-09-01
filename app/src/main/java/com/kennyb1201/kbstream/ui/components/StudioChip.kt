package com.kennyb1201.kbstream.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.ui.theme.KBAccent
import com.kennyb1201.kbstream.ui.theme.KBTextHi

/**
 * Compact studio/network chip for the detail screen.
 * White background with dark logos — the original look.
 */
@Composable
fun StudioChip(
    name: String,
    logoPath: String?,
    onClick: () -> Unit
) {
    val cardShape = RoundedCornerShape(10.dp)

    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(
            shape = cardShape
        ),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White,
            contentColor = Color.Black,
            focusedContainerColor = Color.White,
            focusedContentColor = Color.Black,
            pressedContainerColor = Color.White,
            pressedContentColor = Color.Black
        ),
        scale = ClickableSurfaceDefaults.scale(
            focusedScale = 1.08f
        ),
        border = ClickableSurfaceDefaults.border(
            border = Border(
                border = BorderStroke(
                    1.dp,
                    KBTextHi.copy(alpha = 0.35f)
                ),
                shape = cardShape
            ),
            focusedBorder = Border(
                border = BorderStroke(
                    3.dp,
                    KBAccent
                ),
                shape = cardShape
            )
        ),
        glow = ClickableSurfaceDefaults.glow(
            focusedGlow = Glow(
                elevationColor = KBAccent,
                elevation = 12.dp
            )
        ),
        modifier = Modifier
            .width(120.dp)
            .height(54.dp)
            .padding(end = 8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    horizontal = 10.dp,
                    vertical = 8.dp
                ),
            contentAlignment = Alignment.Center
        ) {
            if (logoPath != null) {
                AsyncImage(
                    model = TmdbRepository.LOGO_BASE + logoPath,
                    contentDescription = name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(30.dp)
                )
            } else {
                Text(
                    text = name,
                    color = Color.Black,
                    style =
                        MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
