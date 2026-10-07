package com.kennyb1201.kbstream.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kennyb1201.kbstream.data.settings.AppPreferences

/**
 * App-wide poster tile sizing, chosen in Settings → Display ("Poster Size").
 * Every poster grid and rail shares one width so screens feel consistent;
 * heights keep the 2:3 poster aspect. Medium is the default.
 */
enum class PosterSize(val width: Dp, val height: Dp) {
    SMALL(110.dp, 165.dp),
    MEDIUM(124.dp, 186.dp),
    LARGE(140.dp, 210.dp);

    companion object {
        /** 0 = small, 1 = medium (default), 2 = large (matches Settings). */
        fun fromPref(value: Long): PosterSize =
            when (value.toInt()) {
                0 -> SMALL
                2 -> LARGE
                else -> MEDIUM
            }
    }
}

/** Current poster size from prefs, remembered for the composition. */
@Composable
fun rememberPosterSize(): PosterSize {
    val context = LocalContext.current
    return remember { PosterSize.fromPref(AppPreferences.getPosterSize(context)) }
}

/**
 * How much wider a landscape tile is than the poster it replaces.
 *
 * The Home rails draw a 210dp landscape card where a poster would be 124dp, so
 * every screen that honors the "Landscape Posters" setting keeps that
 * proportion instead of collapsing to a thumbnail: at Medium the two are
 * identical, and Small/Large scale with the viewer's chosen poster size. It
 * lives beside the poster sizes themselves because the tile AND the container
 * a caller puts around it both have to agree on the number - a caption pinned
 * to the poster width under a landscape tile reads as a mistake.
 */
internal const val LANDSCAPE_TILE_WIDTH_RATIO = 210f / 124f

/** Landscape (16:9) tile width a poster of [posterWidth] becomes. */
internal fun landscapeTileWidth(posterWidth: Dp): Dp =
    posterWidth * LANDSCAPE_TILE_WIDTH_RATIO

/** Landscape (16:9) tile height a poster of [posterWidth] becomes. */
internal fun landscapeTileHeight(posterWidth: Dp): Dp =
    landscapeTileWidth(posterWidth) * 9f / 16f

/**
 * The width a tile occupies right now: the landscape width when the global
 * "Landscape Posters" setting is on, the poster width otherwise.
 *
 * For a screen that pinned a container - or a caption line - to the poster
 * width, so it follows the shape [GlobalPosterCard] is actually drawing instead
 * of clipping it back down to a poster box.
 */
@Composable
fun rememberPosterTileWidth(posterWidth: Dp): Dp =
    if (rememberGlobalLandscape()) landscapeTileWidth(posterWidth) else posterWidth

/**
 * Whether this HOME surface draws landscape tiles: the everywhere switch, or the
 * Home-rails one (see [AppPreferences.homeLandscapeActive]).
 *
 * The Home sibling of [rememberGlobalLandscape], for the surfaces that hang off
 * Home - Home's own rails, and the catalog grid a rail's long-press opens -
 * where the Home-rails switch is allowed to matter. It lives here rather than
 * beside [rememberGlobalLandscape] on purpose: the shared card must not know
 * Home's rule at all (pinned by LandscapeScopeContractTest), and this is the
 * file the tile arithmetic already lives in, so the rule and the shape it
 * implies stay together.
 *
 * A surface asks this ONCE and sizes its cell, its card and its caption from
 * that one answer. Home's grid asked only the global switch while its cards
 * followed the same global switch, so a viewer whose Home is all landscape got
 * posters; sizing its cell from the raw poster width then drew those landscape
 * cards as squares (see CatalogGridScreen).
 */
@Composable
fun rememberHomeLandscape(): Boolean {
    val context = LocalContext.current
    return remember { AppPreferences.homeLandscapeActive(context) }
}

