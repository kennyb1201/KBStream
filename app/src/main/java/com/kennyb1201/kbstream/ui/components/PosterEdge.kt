package com.kennyb1201.kbstream.ui.components

import android.content.Context
import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import com.kennyb1201.kbstream.data.settings.AppPreferences
import com.kennyb1201.kbstream.ui.theme.CardShape
import com.kennyb1201.kbstream.ui.theme.KBCornerCardDp
import com.kennyb1201.kbstream.ui.theme.KBCornerSoftPillDp
import com.kennyb1201.kbstream.ui.theme.KBShapeSoftPill

/**
 * How round a poster tile's corners are, chosen in Settings → Display
 * ("Poster Edges").
 *
 * The rounded corner is the app's long-standing card shape ([CardShape]), so
 * [ROUNDED] is the default and nothing changes for an install that never
 * opens the row. [STRAIGHT] squares the tile off for a wall-to-wall look, and
 * [PILL] curves the corners well past [ROUNDED] without becoming a capsule
 * (a capsule rounds a portrait poster's short side away). The choice reaches every
 * surface that draws a [PosterCard] or [LandscapeCard]; the surrounding
 * chrome (rows, buttons) keeps its own shape.
 *
 * The stored value is this enum's ordinal, spelled as a plain Int in the
 * data layer because the store must not reach into the UI type that draws
 * the shape. [AppPreferences.DEFAULT_POSTER_EDGE] is pinned to [DEFAULT] by a
 * unit test.
 */
enum class PosterEdge(val label: String) {
    STRAIGHT("Straight"),
    ROUNDED("Rounded"),
    PILL("Pill");

    /** The Compose shape this edge draws a tile with. */
    fun shape(): Shape =
        when (this) {
            STRAIGHT -> RectangleShape
            ROUNDED -> CardShape
            // A firm radius, not the theme's capsule: KBShapePill halves a
            // portrait poster's short side and rounds the artwork away.
            PILL -> KBShapeSoftPill
        }

    /**
     * The same corner as [shape], as a radius in dp.
     *
     * The player's end-of-episode artwork is built from classic Views - the
     * because-you-watched posters and the Up Next thumbnail - and a View clips
     * through an outline, which takes a radius rather than a Shape. Reading it
     * from the theme's two dp values keeps the View side and the Compose side
     * drawing one corner instead of two numbers that can drift apart.
     */
    val radiusDp: Float
        get() = when (this) {
            STRAIGHT -> 0f
            ROUNDED -> KBCornerCardDp.value
            PILL -> KBCornerSoftPillDp.value
        }

    companion object {
        /** The shape a fresh install starts at: the app's usual rounded card. */
        val DEFAULT = ROUNDED

        /** Tolerant read: an out-of-range stored value falls back, never throws. */
        fun fromStored(raw: Int): PosterEdge =
            entries.getOrElse(raw) { DEFAULT }
    }
}

/**
 * The shape a poster tile draws right now, from the saved edge setting.
 *
 * Read through the sync revision rather than "directly", like the poster
 * border: the shape is a SYNCED pref, so an edge chosen on the other TV has to
 * redraw the tiles already on screen, and a plain SharedPreferences read cannot
 * do that on its own - Compose recomposes on observed state, and a pref read is
 * not state, so the corners only followed the setting when the tile was
 * invalidated for some other reason.
 */
@Composable
fun posterEdgeShape(): Shape {
    val context = LocalContext.current
    val syncedRevision = posterTileRevision()
    return remember(syncedRevision) {
        PosterEdge.fromStored(AppPreferences.getPosterEdge(context)).shape()
    }
}

/**
 * The corner radius a View-based poster clip draws right now, in pixels.
 *
 * Read once, when the artwork is built, rather than through
 * [posterTileRevision] like the Compose tile: these views belong to one raise of
 * an end-of-episode panel and are thrown away with it, so there is nothing on
 * screen to keep in step with a setting changed elsewhere.
 */
fun posterEdgeRadiusPx(context: Context): Float =
    PosterEdge.fromStored(AppPreferences.getPosterEdge(context)).radiusDp *
        context.resources.displayMetrics.density

/**
 * Clips a View-based piece of poster artwork to the saved [PosterEdge].
 *
 * The player's because-you-watched posters and Up Next thumbnail are classic
 * Views, not Compose tiles, so they cannot take [posterEdgeShape] - which left
 * the posters clipped at a hardcoded 12dp (the Rounded default, so Straight and
 * Pill were both silently ignored there) and the thumbnail not clipped at all.
 *
 * [PosterEdge.STRAIGHT] turns the clip off rather than clipping to a zero
 * radius, so a viewer who wants square artwork pays for no clip.
 *
 * A rounded OUTLINE rather than a rounded background: the theme pass re-tints
 * these views' backgrounds, which would wipe a background-carried radius.
 */
fun clipToPosterEdge(view: View) {
    val radiusPx = posterEdgeRadiusPx(view.context)
    if (radiusPx <= 0f) {
        view.clipToOutline = false
        return
    }
    val current = view.outlineProvider
    if (current !is PosterEdgeOutline || current.radiusPx != radiusPx) {
        view.outlineProvider = PosterEdgeOutline(radiusPx)
    }
    view.clipToOutline = true
}

/** The round-rect outline [clipToPosterEdge] clips to. */
private class PosterEdgeOutline(val radiusPx: Float) : ViewOutlineProvider() {
    override fun getOutline(view: View, outline: Outline) {
        outline.setRoundRect(0, 0, view.width, view.height, radiusPx)
    }
}
