package com.kennyb1201.kbstream.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import com.kennyb1201.kbstream.ui.theme.KBTextHi
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
 *
 * "No logo art" here means no logo URL, NOT a logo that has not arrived yet:
 * the name stands in until the art has actually painted. See
 * [PulsingClearLogo] for why that distinction is the whole point.
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
                    title = title,
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
                    // KBTextHi, not the dim tone this started as. Nothing here
                    // is scrimmed: the backdrop is whatever widescreen art the
                    // title has, and a mid-grey subtitle on a bright or busy
                    // frame is the one piece of text on the splash a viewer
                    // cannot read - reported from the field on the
                    // "Finding sources" hand-off, which is the splash most
                    // people ever see. Hierarchy is carried by size and weight
                    // (headlineMedium vs bodyMedium) instead of by dimming a
                    // line that has to sit on artwork.
                    color = KBTextHi,
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
 * The logo slot's standing size, and so the size the name has to fit in.
 *
 * Grown from 240x80: on a TV that read as a thumbnail in the middle of a
 * full-screen backdrop, smaller than the clearlogo the player's own info panel
 * draws (400dp) even though the splash is the one moment the title graphic is
 * the whole point. The 3:1 box stays, so the art's own aspect is preserved and
 * the name stand-in still fits.
 */
private val LOGO_WIDTH = 420.dp
private val LOGO_HEIGHT = 140.dp

/** How long the name takes to hand over to the art, once the art is in. */
private const val NAME_HANDOVER_MS = 220

/**
 * Coil's own fade for the art. Named because the name's fade is scheduled
 * against it: the art fades up from nothing over this window, and the name is
 * held for it so the two overlap instead of the slot going briefly dark.
 */
private const val ART_FADE_MS = 120

/**
 * Title graphic for the loading splash: the clearlogo art breathing in place
 * (1.0 -> 1.08, alpha 0.7 -> 1.0), with the title's NAME standing in until the
 * art has actually painted — and staying if it never does.
 *
 * The stand-in is why `title` is a parameter. The logo is a remote image, and
 * it is fetched again whenever Coil's decoded-bitmap cache has been dropped
 * ([com.kennyb1201.kbstream.data.memory.releaseImageMemoryCache] does exactly
 * that under memory pressure, which is the field TV's normal state) or when the
 * splash is the first thing in the session to ask for that art. A splash that
 * is up for part of a second can therefore outlive its own artwork. Choosing
 * the branch by whether a logo URL EXISTS — as this did — leaves the title slot
 * empty in that case, and an empty title slot on the pre-playback splash reads
 * as a screen that failed to load. Reported from the field as the "Finding
 * sources" splash sometimes showing no logo, with the logo back on the NEXT
 * splash (that one a cache hit).
 *
 * So the name covers the load, and the art takes over the moment it paints,
 * with the crossfade in [ART_FADE_MS] run underneath the name's fade so the
 * handover never shows an empty slot. A failed fetch leaves the name up, which
 * is a better answer than an empty slot and is what a logo-less title shows
 * anyway.
 */
@Composable
fun PulsingClearLogo(
    url: String,
    title: String,
    contentDescription: String
) {
    // Keyed on the url: a splash for a different title starts its own handover
    // rather than inheriting the previous one's "already painted".
    var artPainted by remember(url) { mutableStateOf(false) }

    val nameAlpha by animateFloatAsState(
        targetValue = if (artPainted) 0f else 1f,
        animationSpec = tween(
            durationMillis = NAME_HANDOVER_MS,
            delayMillis = if (artPainted) ART_FADE_MS else 0
        ),
        label = "clearLogoName"
    )

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

    // One slot for both, at the logo's standing size, so the switch from name
    // to art cannot move the subtitle underneath it: a two-line name is shorter
    // than the logo, and a wider one is centered in a wider box (see
    // LOGO_HEIGHT / LOGO_WIDTH).
    Box(
        modifier = Modifier.heightIn(min = LOGO_HEIGHT),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            color = KBTextHi,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            // Faded rather than removed from the composition: the slot keeps its
            // size for the art that is about to fill it.
            modifier = Modifier.graphicsLayer { this.alpha = nameAlpha }
        )

        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(url)
                .crossfade(ART_FADE_MS)
                .build(),
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            onSuccess = { artPainted = true },
            modifier = Modifier
                .width(LOGO_WIDTH)
                .height(LOGO_HEIGHT)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    this.alpha = alpha
                }
        )
    }
}
