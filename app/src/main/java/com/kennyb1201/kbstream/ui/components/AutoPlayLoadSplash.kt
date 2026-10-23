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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
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
import com.kennyb1201.kbstream.data.images.ImageOverrides
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
 * "No logo art" here means no logo URL at all: that branch draws the name
 * immediately. When a URL exists the slot stays empty while it loads and the
 * name appears only if the fetch fails. See [PulsingClearLogo].
 */
@Composable
fun AutoPlayLoadSplash(
    backdropUrl: String?,
    clearLogoUrl: String?,
    title: String,
    subtitle: String? = null,
    /**
     * The TMDB id the viewer's image choices for this title are filed under,
     * or null when this launch has no TMDB title behind it.
     *
     * The splash draws the SAME title the detail hero and the streams picker
     * do, so it honors the still a long press on the Media row chose for it
     * (see [ImageOverrides.SCREEN_PLAYER_SPLASH]). The id is resolved by the
     * caller rather than parsed here, because only the caller knows what its
     * launch is: a live channel launch carries its own id namespace, which
     * must never reach a TMDB title's choice. A null id keeps [backdropUrl].
     */
    tmdbId: Int? = null
) {
    // Full resolution, and not one of absoluteTmdbArt's sized bases: this is
    // the one backdrop slot that is the entire screen, so w1280 - the size a
    // card wants - is a visible upscale on a 4K panel. The logo keeps its
    // sized base (see LOGO_BASE for why "original" is wrong for wordmarks).
    //
    // The chosen still is applied at the same master size, for the same
    // reason: a path picked on the Backdrops page is resized to whichever
    // surface draws it, and this one fills the frame.
    val overridePaths = rememberImageOverridePaths()
    val resolvedBackdrop = imageOverrideBackdropUrl(
        paths = overridePaths,
        tmdbId = tmdbId,
        screen = ImageOverrides.SCREEN_PLAYER_SPLASH,
        fallbackUrl = TmdbRepository.fullResBackdropUrl(backdropUrl)
    )
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

/** Coil's own fade for the art when the fetch succeeds. */
private const val ART_FADE_MS = 120

/** The three states of the logo slot. */
private enum class ClearLogoState { LOADING, PAINTED, FAILED }

/**
 * Title graphic for the loading splash: the clearlogo art breathing in place
 * (1.0 -> 1.08, alpha 0.7 -> 1.0).
 *
 * The slot has three states, keyed on [url] so a new title starts clean:
 *
 *  - **loading** — empty. No name, no art. The field saw the title's text flash
 *    for a frame before the clearlogo started pulsing on both pre-playback
 *    splashes; a name standing in until the art paints is exactly that flash.
 *  - **painted** — the art fades in with Coil and breathes. This is the whole
 *    point of the slot, and it is unchanged.
 *  - **failed** — the name renders in the slot with the same styling the
 *    no-URL branch uses. An eternally empty slot on a dead fetch is worse than
 *    the name, and this is the only case the name appears when a URL exists.
 *
 * The name used to cover the load deliberately (a cold image cache once read as
 * a failed screen), and it remains a parameter for that fallback — but only the
 * failure path draws it now. Backdrop, subtitle and the slot's standing size
 * are untouched, so the subtitle cannot jump as the state changes.
 */
@Composable
fun PulsingClearLogo(
    url: String,
    title: String,
    contentDescription: String
) {
    var artState by remember(url) { mutableStateOf(ClearLogoState.LOADING) }

    /*
     * Reduced motion: hold the art still, at full opacity, instead of breathing.
     * The pulse is the app's own idea of "still loading" and it was one of the
     * handful of animations that ignored the platform's animator-duration scale
     * entirely - a reduced-motion viewer got motion on both pre-playback
     * splashes, which are the first thing every session shows them.
     *
     * The values stay State so the caller's graphicsLayer reads them at draw
     * time; reading them into plain Floats here would recompose the splash for
     * every frame of the pulse.
     */
    val scale: State<Float>
    val alpha: State<Float>
    if (rememberReducedMotion()) {
        scale = remember { mutableStateOf(1f) }
        alpha = remember { mutableStateOf(1f) }
    } else {
        val pulse = rememberInfiniteTransition(label = "clearLogoPulse")
        scale = pulse.animateFloat(
            initialValue = 1f,
            targetValue = 1.08f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200),
                repeatMode = RepeatMode.Reverse
            ),
            label = "clearLogoScale"
        )
        alpha = pulse.animateFloat(
            initialValue = 0.7f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(1200),
                repeatMode = RepeatMode.Reverse
            ),
            label = "clearLogoAlpha"
        )
    }

    // One slot at the logo's standing size, so none of the three states can
    // move the subtitle underneath it. The ART is composed while loading too -
    // it is the fetch - but Coil draws nothing until a drawable is ready, so
    // the loading state is visually empty.
    Box(
        modifier = Modifier.heightIn(min = LOGO_HEIGHT),
        contentAlignment = Alignment.Center
    ) {
        if (artState == ClearLogoState.FAILED) {
            Text(
                text = title,
                color = KBTextHi,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }

        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(url)
                .crossfade(ART_FADE_MS)
                .build(),
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            onSuccess = { artState = ClearLogoState.PAINTED },
            onError = { artState = ClearLogoState.FAILED },
            modifier = Modifier
                .width(LOGO_WIDTH)
                .height(LOGO_HEIGHT)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                    this.alpha = alpha.value
                }
        )
    }
}
