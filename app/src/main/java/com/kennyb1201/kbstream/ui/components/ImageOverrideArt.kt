package com.kennyb1201.kbstream.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kennyb1201.kbstream.data.images.ImageOverrides
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository

/**
 * The viewer's image choices, as [ImageOverrides] publishes them: `"<tmdbId>:<type>:<screen>"`
 * -> the chosen `file_path`.
 *
 * Every surface that draws a title's own art reads the choices through here, so
 * a choice written by the Media row's long press repaints the surface on the
 * next frame wherever it is - a rail, a hero, the streams screen - with no
 * plumbing between the two. The read is a mirror of one small table, not a
 * query per card (see [ImageOverrides.paths]).
 *
 * Calling this also starts the mirror's load for the active profile if it has
 * not run yet (see [ImageOverrides.ensureLoaded]), which is why a screen only
 * has to remember the paths to get them.
 */
@Composable
internal fun rememberImageOverridePaths(): Map<String, String> {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        ImageOverrides.ensureLoaded(context)
    }
    val paths by ImageOverrides.paths.collectAsStateWithLifecycle()
    return paths
}

/**
 * The backdrop [screen] should draw: the still the viewer chose at full
 * resolution, or [fallbackUrl] when they have chosen nothing.
 *
 * For the surfaces that fill the frame - the detail hero, the streams screen,
 * the Home hero - which is why the choice is upgraded to TMDB's master size
 * exactly the way those surfaces upgrade their own art (see
 * [TmdbRepository.fullResBackdropUrl]).
 */
internal fun imageOverrideBackdropUrl(
    paths: Map<String, String>,
    tmdbId: Int?,
    screen: String,
    fallbackUrl: String?
): String? {
    val chosen = ImageOverrides.pathFor(
        paths = paths,
        tmdbId = tmdbId,
        type = ImageOverrides.TYPE_BACKDROP,
        screen = screen
    )
    return chosen?.let { TmdbRepository.fullResBackdropUrl(it) } ?: fallbackUrl
}

/**
 * The backdrop a LANDSCAPE CARD should draw, or [fallbackUrl].
 *
 * Card size rather than the full size above: a rail draws many of them at once
 * and none of them is bigger than the w1280 the rest of the app's card art uses
 * (see [TmdbRepository.BACKDROP_BASE]).
 */
internal fun imageOverrideCardBackdropUrl(
    paths: Map<String, String>,
    rawId: String?,
    fallbackUrl: String?
): String? {
    val chosen = ImageOverrides.pathFor(
        paths = paths,
        rawId = rawId,
        type = ImageOverrides.TYPE_BACKDROP,
        screen = ImageOverrides.SCREEN_LANDSCAPE_CARD
    )
    return chosen?.let { TmdbRepository.BACKDROP_BASE + it } ?: fallbackUrl
}

/**
 * The poster a card should draw, or [fallbackUrl] - one choice covers every
 * surface a title's poster appears on (see [ImageOverrides.SCREEN_POSTER]).
 */
internal fun imageOverridePosterUrl(
    paths: Map<String, String>,
    rawId: String?,
    fallbackUrl: String?
): String? {
    val chosen = ImageOverrides.pathFor(
        paths = paths,
        rawId = rawId,
        type = ImageOverrides.TYPE_POSTER,
        screen = ImageOverrides.SCREEN_POSTER
    )
    return chosen?.let { TmdbRepository.POSTER_BASE + it } ?: fallbackUrl
}
