package com.kennyb1201.kbstream.ui.components.touch

import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kennyb1201.kbstream.data.settings.AppPreferences
import com.kennyb1201.kbstream.data.sync.PrefsPayloadApplier
import com.kennyb1201.kbstream.ui.components.imageOverrideCardBackdropUrl
import com.kennyb1201.kbstream.ui.components.imageOverridePosterUrl
import com.kennyb1201.kbstream.ui.components.isCollectionArtType
import com.kennyb1201.kbstream.ui.components.landscapeTileHeight
import com.kennyb1201.kbstream.ui.components.landscapeTileWidth
import com.kennyb1201.kbstream.ui.components.rememberGlobalLandscapeArt
import com.kennyb1201.kbstream.ui.components.rememberImageOverridePaths

/**
 * The touch twin of `ui/components/GlobalPosterCard.kt`: one poster surface, in
 * whichever shape the viewer chose.
 *
 * The mobile screens were built on [PosterCard] before the global "Landscape
 * Posters" switch existed, and every one of them kept calling it directly - so
 * the switch reached Home's rails (which draw their own cards) and nothing
 * else on a phone: library, search, browse sections, actor filmographies,
 * collections, KB folders and Detail's rails all stayed 2:3. Converting them
 * here, at the card, is the same move the TV side made: the shape question is
 * answered once, and a screen converts by swapping its card.
 *
 * [TouchGlobalPosterCard] keeps the TV card's sizing contract - a poster box in,
 * a landscape tile scaled out of it - for a row of fixed-width tiles.
 * [TouchGlobalPosterTile] is for a cell whose width the LAYOUT owns (an adaptive
 * grid): it takes the width and sets only the height, 2:3 or 16:9.
 *
 * Both resolve a landscape card's backdrop and clearlogo from [artId]/[artType]
 * through the shared resolver, and both honour the viewer's "Set as Poster" /
 * "Set as Landscape card" choice through the same image-override helpers, so a
 * phone and a TV show the same artwork for the same title.
 */
@Composable
fun TouchGlobalPosterCard(
    posterUrl: String?,
    contentDescription: String?,
    isWatched: Boolean,
    onClick: () -> Unit,
    posterWidth: Dp,
    posterHeight: Dp,
    modifier: Modifier = Modifier,
    backdropUrl: String? = null,
    artId: String? = null,
    artType: String? = null,
    isPartiallyWatched: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onPosterError: ((Throwable?) -> Unit)? = null,
    /** Override for a surface with its own rule; null asks the global pref. */
    landscape: Boolean? = null
) {
    val context = LocalContext.current
    val syncedRevision by PrefsPayloadApplier.revision.collectAsStateWithLifecycle()
    // Read unconditionally and let [landscape] win afterwards: a `remember`
    // behind `?:` is a CONDITIONAL call, and Compose's slot table is positional
    // (the same rule LandscapeCard's eye badge follows).
    val prefLandscape = remember(syncedRevision) {
        AppPreferences.landscapePostersActive(context)
    }
    val useLandscape = landscape ?: prefLandscape
    val art = rememberGlobalLandscapeArt(
        enabled = useLandscape,
        addonId = artId,
        addonType = artType,
        addonBackdrop = backdropUrl
    )
    val overridePaths = rememberImageOverridePaths()
    if (useLandscape) {
        LandscapeCard(
            backdropUrl = imageOverrideCardBackdropUrl(
                paths = overridePaths,
                // A collection's id is a different id space from a title's, so
                // no "Set as ..." choice may be read for one; see
                // [GlobalPosterCard].
                rawId = artId.takeUnless { isCollectionArtType(artType) },
                fallbackUrl = art.first?.takeIf { it.isNotBlank() } ?: posterUrl
            ),
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
            posterUrl = imageOverridePosterUrl(
                paths = overridePaths,
                rawId = artId,
                fallbackUrl = posterUrl
            ),
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
 * [TouchGlobalPosterCard] for a cell whose width the LAYOUT owns - a grid cell,
 * a full-width slot - with the aspect switching 2:3 <-> 16:9.
 *
 * A landscape tile of the same width is SHORTER than a poster, so the calling
 * grid should raise its column width when the switch is on
 * ([com.kennyb1201.kbstream.ui.components.rememberGlobalLandscape]) if it wants
 * the wider tiles Home draws; without that the cards are honest 16:9 at the
 * column's width, which is what the poster box allowed.
 */
@Composable
fun TouchGlobalPosterTile(
    posterUrl: String?,
    contentDescription: String?,
    isWatched: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    backdropUrl: String? = null,
    artId: String? = null,
    artType: String? = null,
    isPartiallyWatched: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onPosterError: ((Throwable?) -> Unit)? = null,
    /** Override for a surface with its own rule; null asks the global pref. */
    landscape: Boolean? = null
) {
    val context = LocalContext.current
    val syncedRevision by PrefsPayloadApplier.revision.collectAsStateWithLifecycle()
    val prefLandscape = remember(syncedRevision) {
        AppPreferences.landscapePostersActive(context)
    }
    val useLandscape = landscape ?: prefLandscape
    val art = rememberGlobalLandscapeArt(
        enabled = useLandscape,
        addonId = artId,
        addonType = artType,
        addonBackdrop = backdropUrl
    )
    val overridePaths = rememberImageOverridePaths()
    // The caller's width, this tile's height.
    val shaped = modifier.aspectRatio(if (useLandscape) 16f / 9f else 2f / 3f)
    if (useLandscape) {
        LandscapeCard(
            backdropUrl = imageOverrideCardBackdropUrl(
                paths = overridePaths,
                rawId = artId.takeUnless { isCollectionArtType(artType) },
                fallbackUrl = art.first?.takeIf { it.isNotBlank() } ?: posterUrl
            ),
            logoUrl = art.second,
            fallbackTitle = contentDescription,
            contentDescription = contentDescription,
            isWatched = isWatched,
            isPartiallyWatched = isPartiallyWatched,
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = shaped
        )
    } else {
        PosterCard(
            posterUrl = imageOverridePosterUrl(
                paths = overridePaths,
                rawId = artId,
                fallbackUrl = posterUrl
            ),
            contentDescription = contentDescription,
            isWatched = isWatched,
            isPartiallyWatched = isPartiallyWatched,
            onClick = onClick,
            onLongClick = onLongClick,
            onPosterError = onPosterError,
            modifier = shaped
        )
    }
}
