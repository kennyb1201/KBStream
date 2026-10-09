package com.kennyb1201.kbstream.ui.components

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
 * shapes cannot share it, and a caller can only express that by letting the card
 * own its own bounds. Portrait keeps the caller's exact box; landscape keeps the
 * Home rails' proportion to it - a 124dp poster becomes a 210x118 landscape
 * card (see [landscapeTileWidth]), not the 124x70 thumbnail a plain 16:9 crop
 * of the same width used to give. The tile is therefore WIDER than the poster,
 * so a caller that pins its own container to the poster width has to widen it
 * too; [rememberGlobalLandscape] and [landscapeTileWidth] are there for that.
 *
 * [backdropUrl] is what the caller already has. When it is missing, landscape
 * mode resolves the item's own art from [artId]/[artType] through
 * [rememberGlobalLandscapeArt] - the shared backdrop + clearlogo resolver - so a
 * surface that ships only a poster still draws a real backdrop with a corner
 * clearlogo. The clearlogo is TMDB's English wordmark or nothing: an add-on's
 * own `logo` is a bare URL with no language on it, so it is not drawn (a
 * localized catalog would put its locale's wordmark on the card). Whatever
 * backdrop is still absent falls back to [posterUrl], because a cropped poster
 * reads better than a blank 16:9 hole.
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
    /**
     * The item's raw id and type for the shared landscape-art resolver (any id
     * `TmdbRepository.fetchEnrichedMetaCached` can resolve: "tt...",
     * "tmdb:...", or a bare numeric TMDB id). Null skips the lookup and keeps
     * [backdropUrl] and [posterUrl] as the whole story.
     */
    artId: String? = null,
    artType: String? = null,
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
    // Landscape mode resolves the item's own backdrop + clearlogo when the
    // caller's data carries none; portrait never looks anything up.
    val art = rememberGlobalLandscapeArt(
        enabled = useLandscape,
        addonId = artId,
        addonType = artType,
        addonBackdrop = backdropUrl
    )
    if (useLandscape) {
        LandscapeCard(
            backdropUrl = art.first?.takeIf { it.isNotBlank() } ?: posterUrl,
            logoUrl = art.second,
            fallbackTitle = contentDescription,
            contentDescription = contentDescription,
            isWatched = isWatched,
            isPartiallyWatched = isPartiallyWatched,
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier
                .width(landscapeTileWidth(posterWidth))
                .height(landscapeTileHeight(posterWidth))
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

/**
 * Whether the global "Landscape Posters" setting is on, for a caller that has to
 * size its own container to match the tile [GlobalPosterCard] is about to draw.
 *
 * [GlobalPosterCard] makes the same read internally; this exists only so a
 * wrapper (a Column pinned to the poster width, a caption line under the card)
 * does not disagree with the card about the shape it is drawing.
 */
@Composable
fun rememberGlobalLandscape(): Boolean {
    val context = LocalContext.current
    return remember { AppPreferences.landscapePostersActive(context) }
}

