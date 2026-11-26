package com.kennyb1201.kbstream.ui.home

import kotlin.math.roundToInt

/**
 * The Home rail card sizes, in dp, spelled once (spec 2026-11-16, scrolling
 * round three F3).
 *
 * Two callers need them for different reasons and must never disagree:
 *
 *  - the rail composables, which size the card (and therefore the artwork
 *    request Coil resolves from the card's constraints);
 *  - [HomeViewModel]'s tile warm, which decodes a neighbour's bitmap INTO the
 *    memory cache at the size the card will draw so that entry is the one the
 *    card reads - a size mismatch writes a second, useless entry.
 *
 * That is why they are numbers rather than `Dp`: the ViewModel has no Compose
 * and converts with the display density, exactly as the card's constraints do.
 */
internal object HomeRailMetrics {
    /** [GHOST_CARD_TOP_PICKS] landscape rails: 16:9-ish card art. */
    const val LANDSCAPE_WIDTH_DP = 210f
    const val LANDSCAPE_HEIGHT_DP = 118f

    /** Poster-shaped rails (the default), the same 2:3 tile cards use. */
    const val POSTER_WIDTH_DP = 124f
    const val POSTER_HEIGHT_DP = 180f

    fun widthDp(landscapeCards: Boolean): Float =
        if (landscapeCards) LANDSCAPE_WIDTH_DP else POSTER_WIDTH_DP

    fun heightDp(landscapeCards: Boolean): Float =
        if (landscapeCards) LANDSCAPE_HEIGHT_DP else POSTER_HEIGHT_DP

    /**
     * The card's artwork box in PIXELS - the one size the warm and the card's
     * own request must both use (2026-11-26, art-decode audit).
     *
     * The tile warm decodes a neighbour into the memory cache at this size, and
     * Coil's memory cache is keyed by URL *and* size, so the card's request has
     * to resolve to the same numbers or the warm writes an entry the card never
     * reads - the audit's warm/cache size mismatch. Both callers therefore take
     * the numbers from here: the ViewModel from the display metrics, the rail
     * from the same display metrics (not Compose's density), so the two cannot
     * round differently on a TV whose density is fractional.
     *
     * The card's request used to carry no explicit size, leaving Coil to resolve
     * it from the laid-out constraints - the same dp box, but a second place for
     * the arithmetic to drift. The card now asks for this size outright.
     */
    fun artSizePx(landscapeCards: Boolean, density: Float): RailArtPx {
        val width = (widthDp(landscapeCards) * density).roundToInt().coerceAtLeast(1)
        val height = (heightDp(landscapeCards) * density).roundToInt().coerceAtLeast(1)
        return RailArtPx(width, height)
    }
}

/** A rail card's artwork request size in decoded pixels (both dimensions > 0). */
internal data class RailArtPx(val width: Int, val height: Int)
