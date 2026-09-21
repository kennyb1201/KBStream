package com.kennyb1201.kbstream.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.ui.theme.KBTextHi
import com.kennyb1201.kbstream.ui.theme.KBTextLo
import com.kennyb1201.kbstream.ui.theme.KBVoid

/**
 * The loading splash shown for every pre-playback hand-off: the title's
 * backdrop with its pulsing clearlogo (or the name when no logo art exists)
 * over a dark base.
 *
 * It exists so a load that is *not* buffering never reads as one. The player
 * uses the same treatment for its first load, and the bare centered spinner it
 * uses for mid-playback rebuffers means "the video is buffering" — so any
 * spinner shown while a title is still being resolved or handed to the player
 * is a false signal that reads as a stalled/failed playback.
 *
 * Used by:
 *  - the autoselect "Finding sources" overlay over the current screen, and
 *  - the Continue Watching / Up Next deep link, which opens DetailScreen only
 *    to hand the player its backdrop/cast and auto-plays as soon as metadata
 *    lands (a spinner on black for that whole load looked like a failure).
 */
@Composable
fun AutoPlayLoadSplash(
    backdropUrl: String?,
    clearLogoUrl: String?,
    title: String,
    subtitle: String? = null
) {
    val resolvedBackdrop = absoluteTmdbArt(backdropUrl, TmdbRepository.BACKDROP_BASE)
    val resolvedLogo = absoluteTmdbArt(clearLogoUrl, TmdbRepository.LOGO_BASE)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(KBVoid)
    ) {
        if (resolvedBackdrop != null) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(resolvedBackdrop)
                    .crossfade(true)
                    .build(),
                contentDescription = title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }

        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (resolvedLogo != null) {
                PulsingClearLogo(
                    url = resolvedLogo,
                    contentDescription = title
                )
            } else {
                Text(
                    text = title,
                    color = KBTextHi,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            if (!subtitle.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = subtitle,
                    color = KBTextLo,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

/**
 * Art handed to a launch is not always absolute: the detail screen's own
 * clearlogo is a full URL, but addon metas and history rows can carry a bare
 * TMDB path. Coil cannot load a path, which would leave the splash without its
 * backdrop (or without any logo art at all, hiding the name fallback too), so
 * a path is resolved against the TMDB size that fits the slot.
 */
private fun absoluteTmdbArt(url: String?, base: String): String? =
    url?.takeIf { it.isNotBlank() }?.let { raw ->
        if (raw.startsWith("http://") || raw.startsWith("https://")) raw
        else base + if (raw.startsWith("/")) raw else "/$raw"
    }

/**
 * Title graphic for the loading splash: the clearlogo art breathing in place
 * (1.0 -> 1.08, alpha 0.7 -> 1.0). A title with no logo art gets the name text
 * instead — see [AutoPlayLoadSplash].
 */
@Composable
fun PulsingClearLogo(
    url: String,
    contentDescription: String
) {
    val pulse = rememberInfiniteTransition(label = "clearLogoPulse")
    val scale by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "clearLogoScale"
    )
    val alpha by pulse.animateFloat(
        initialValue = 0.7f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "clearLogoAlpha"
    )
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current)
            .data(url)
            .crossfade(true)
            .build(),
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .width(240.dp)
            .height(80.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                this.alpha = alpha
            }
    )
}
