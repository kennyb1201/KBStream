package com.kennyb1201.kbstream.ui.components

import androidx.compose.foundation.border
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.kennyb1201.kbstream.data.settings.AppPreferences
import com.kennyb1201.kbstream.ui.theme.KBTextHi

/**
 * How strong the faint outline around a poster tile is.
 *
 * Posters are drawn on a near-black surface, and dark artwork next to dark
 * artwork runs together at the edges - a grid of them reads as one mass with
 * no tile boundaries. A hairline edge separates them without framing the art
 * in a box, so the strength is a matter of taste and lives in Settings →
 * Interface (see the "Poster Border" row) rather than being fixed here.
 *
 * Only the faintness changes between levels: the stroke stays one pixel wide,
 * because a thicker line stops reading as an edge and starts reading as a
 * frame. [OFF] draws nothing at all.
 */
enum class PosterBorder(val label: String, val alpha: Float) {
    OFF("Off", 0f),
    SUBTLE("Subtle", 0.16f),
    MEDIUM("Medium", 0.32f),
    STRONG("Strong", 0.5f);

    val isOn: Boolean get() = this != OFF

    companion object {
        /** The level a fresh install starts at: a faint edge, not none. */
        val DEFAULT = SUBTLE

        /** Tolerant read: an out-of-range stored value falls back, never throws. */
        fun fromStored(raw: Int): PosterBorder =
            entries.getOrElse(raw) { DEFAULT }
    }
}

/**
 * The border a poster tile draws, from the saved strength.
 *
 * A single pixel of [KBTextHi] at the level's alpha, on the tile's own corner
 * radius (the chosen [PosterEdge]) so it follows the artwork's shape. Returns
 * an empty modifier for [PosterBorder.OFF], so a viewer who wants no edge
 * pays for no draw.
 *
 * Read through the sync revision, not once and not "directly": the strength is
 * a SYNCED pref, so a level picked on the other TV has to redraw the tiles
 * already on screen. A bare read is not the same thing - Compose recomposes on
 * observed STATE, and a SharedPreferences lookup is not state, so the edge only
 * followed the setting when something else happened to invalidate the tile
 * (the same reason [PosterCard] and [LandscapeCard] read their eye-badge toggle
 * the same way).
 *
 * [shape] is for the surfaces that draw a tile in a shape of their OWN rather
 * than the poster edge setting - the episode cards, which keep the app's card
 * shape whatever the viewer picked for posters. Left out, the border follows the
 * poster edge, which is what a poster tile wants. Either way the STRENGTH is the
 * one saved setting: an edge turned off turns it off everywhere.
 */
@Composable
fun posterBorderModifier(shape: Shape? = null): Modifier {
    val context = LocalContext.current
    val syncedRevision = posterTileRevision()
    val border = remember(syncedRevision) {
        PosterBorder.fromStored(AppPreferences.getPosterBorderStrength(context))
    }
    if (!border.isOn) return Modifier
    return Modifier.border(
        width = 1.dp,
        color = KBTextHi.copy(alpha = border.alpha),
        shape = shape ?: posterEdgeShape()
    )
}
