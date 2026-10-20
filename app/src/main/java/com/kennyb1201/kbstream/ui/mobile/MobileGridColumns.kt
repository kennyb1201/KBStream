package com.kennyb1201.kbstream.ui.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * How many columns a phone poster grid draws, derived from the width it has
 * rather than from the orientation it is in.
 *
 * The phone grids used `GridCells.Adaptive(minSize = poster width)` with the
 * viewer's Poster Size as the minimum. That is right on a television and wrong
 * in a hand: 360dp minus the 16dp gutters leaves 328dp, and a Medium poster's
 * 124dp tile fits only two of them - which is how every phone grid came to open
 * with two oversized posters in portrait. A phone's portrait grid is three
 * posters wide; rotate the same screen and the extra width buys more columns
 * instead of wider posters, because the tile keeps its chosen 2:3 (or 16:9)
 * shape and simply fills a narrower cell.
 *
 * So the Poster Size setting is the *target* width, not a floor:
 *
 *  - a portrait phone (360-430dp) always shows at least three poster columns,
 *    even when the viewer chose Large;
 *  - a landscape phone or a tablet (600dp+) fits as many target-width tiles as
 *    the width allows, so the setting still means something there;
 *  - landscape cards (16:9) start at two columns, because three 16:9 cards in a
 *    portrait hand are thumbnails, not posters.
 *
 * The result is capped so a very wide window cannot produce an unreadable
 * ribbon of artwork. Pure, so the boundary is testable without a device.
 */
internal fun mobileGridColumns(
    availableWidth: Dp,
    preferredTileWidth: Dp,
    spacing: Dp,
    landscape: Boolean
): Int {
    val minColumns = if (landscape) 2 else 3
    if (availableWidth <= 0.dp || preferredTileWidth <= 0.dp) return minColumns
    val fit = ((availableWidth + spacing) / (preferredTileWidth + spacing)).toInt()
    return fit.coerceIn(minColumns, MAX_COLUMNS)
}

/**
 * [mobileGridColumns] for the window this composition is in.
 *
 * Read from `LocalConfiguration.screenWidthDp`, which is the window's width, not
 * the panel's: a rotation, a foldable unfold and a split-screen resize all
 * recompose here with the new number. The 16dp gutter on each side is the
 * content padding every phone grid already uses, so it is subtracted before the
 * columns are counted instead of being discovered after the cards overflow.
 */
@Composable
internal fun rememberMobileGridColumns(
    preferredTileWidth: Dp,
    landscape: Boolean,
    spacing: Dp = 12.dp,
    gutter: Dp = 16.dp
): Int {
    val windowWidthDp = LocalConfiguration.current.screenWidthDp
    return remember(windowWidthDp, preferredTileWidth, landscape, spacing, gutter) {
        mobileGridColumns(
            availableWidth = windowWidthDp.dp - gutter * 2,
            preferredTileWidth = preferredTileWidth,
            spacing = spacing,
            landscape = landscape
        )
    }
}

/** Beyond this a "grid" is a contact sheet; no phone window reaches it. */
private const val MAX_COLUMNS = 8
