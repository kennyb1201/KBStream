package com.kennyb1201.kbstream.ui.components

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import com.kennyb1201.kbstream.data.settings.AppPreferences

/**
 * One poster surface's tile, in whichever shape the viewer chose.
 *
 * The app has two card vocabularies: the 2:3 [PosterCard] every catalog screen
 * was built on, and the 16:9 [LandscapeCard] the Home rails and KB folders
 * offer behind their own "Landscape Cards on Home Rails" switch. The global
 * "Landscape Posters" setting is meant to reach every screen, not just those
 * two, so the shape question lives here in one place instead of being answered
 * again at each call site - and a screen converts by swapping its `PosterCard`
 * for this, passing the backdrop/clearlogo it already has.
 *
 * The size is taken as a pair rather than left in [modifier] because the two
 * shapes cannot share it: a 124x186 poster becomes 124x70 when landscape, and
 * a caller can only express that by letting the card own its own bounds.
 * Portrait keeps the caller's exact box; landscape derives 16:9 from the width.
 *
 * [backdropUrl] falls back to [posterUrl] when the item carries no backdrop, so
 * turning the setting on always changes the shape even for a source that ships
 * no landscape art - a cropped poster reads better than a blank 16:9 hole.
 */
@Composable
fun GlobalPosterCard(
    posterUrl: String?,
    contentDescription: String?,
    isWatched: Boolean,
    onClick: () -> Unit,
    posterWidth: Dp,
    posterHeight: Dp,
    modifier: Modifier = Modifier,
    backdropUrl: String? = null,
    logoUrl: String? = null,
    isPartiallyWatched: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onPosterError: ((Throwable?) -> Unit)? = null,
    /**
     * Override for a surface with its own rule. Null asks the global
     * preference ([AppPreferences.landscapePostersActive]). The KB folder Grid
     * passes only the *global* switch here, so a viewer who turned on the
     * Home-rails landscape mode does not also reshape the Grid - the original
     * "Grid keeps posters" behaviour - while the new global setting does.
     */
    landscape: Boolean? = null
) {
    val context = LocalContext.current
    val useLandscape = landscape ?: AppPreferences.landscapePostersActive(context)
    if (useLandscape) {
        LandscapeCard(
            backdropUrl = backdropUrl?.takeIf { it.isNotBlank() } ?: posterUrl,
            logoUrl = logoUrl,
            fallbackTitle = contentDescription,
            contentDescription = contentDescription,
            isWatched = isWatched,
            isPartiallyWatched = isPartiallyWatched,
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier
                .width(posterWidth)
                .height(posterWidth * 9f / 16f)
        )
    } else {
        PosterCard(
            posterUrl = posterUrl,
            contentDescription = contentDescription,
            isWatched = isWatched,
            isPartiallyWatched = isPartiallyWatched,
            onClick = onClick,
            onLongClick = onLongClick,
            onPosterError = onPosterError,
            modifier = modifier
                .width(posterWidth)
                .height(posterHeight)
        )
    }
}
