package com.kennyb1201.kbstream.ui.home

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
}
