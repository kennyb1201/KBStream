package com.kennyb1201.kbstream.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import com.kennyb1201.kbstream.ui.theme.KBFocusChip
import com.kennyb1201.kbstream.ui.theme.KBFocusGlow
import com.kennyb1201.kbstream.ui.theme.KBFocusPressed
import com.kennyb1201.kbstream.ui.theme.KBShapeChip
import com.kennyb1201.kbstream.ui.theme.KBTextHi

/**
 * Compact studio/network chip for the detail screen.
 * White background with dark logos — the original look.
 *
 * [onLongClick] is the chip's context menu (the detail screen offers "add
 * this network / company to Home"); the press behaviour itself is unchanged,
 * because [rememberLongPressModifier] only swallows a long hold and lets a
 * short one fall through to [onClick].
 */
@Composable
fun StudioChip(
    name: String,
    logoPath: String?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val cardShape = KBShapeChip
    // A chip recomposes on every focus move - that is what a rail walk is - and
    // its chrome was rebuilt each time. All of it is constant here, so the plain
    // builders and the two Border objects are remembered; `shape` and `colors`
    // are @Composable defaults calls and stay inline (perf spec 2026-11-22,
    // item 4).
    val chipScale = remember {
        ClickableSurfaceDefaults.scale(
            focusedScale = KBFocusChip,
            pressedScale = KBFocusPressed
        )
    }
    val chipBorder = remember(cardShape) {
        Border(
            border = BorderStroke(
                1.dp,
                KBTextHi.copy(alpha = 0.35f)
            ),
            shape = cardShape
        )
    }
    val chipFocusedBorder = remember(cardShape) {
        Border(
            border = BorderStroke(
                3.dp,
                KBAccent
            ),
            shape = cardShape
        )
    }
    val chipGlow = remember {
        ClickableSurfaceDefaults.glow(
            focusedGlow = Glow(
                elevationColor = KBAccent,
                elevation = KBFocusGlow
            )
        )
    }

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
        scale = chipScale,
        border = ClickableSurfaceDefaults.border(
            border = chipBorder,
            focusedBorder = chipFocusedBorder
        ),
        glow = chipGlow,
        modifier = modifier
            .width(120.dp)
            .height(54.dp)
            .then(rememberLongPressModifier(onLongClick))
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
                    // A studio's wordmark: transparent art, so 8888 (see
                    // [logoRequest]) - a 565 decode draws it as a block.
                    model = logoRequest(LocalContext.current, TmdbRepository.LOGO_BASE + logoPath),
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
