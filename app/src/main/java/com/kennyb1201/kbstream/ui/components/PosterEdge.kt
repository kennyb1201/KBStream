package com.kennyb1201.kbstream.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import com.kennyb1201.kbstream.data.settings.AppPreferences
import com.kennyb1201.kbstream.ui.theme.CardShape
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
 * Read directly rather than remembered, like the poster border: the pref is a
 * cheap SharedPreferences hit, and this keeps the setting honest for a screen
 * that is already on screen when it changes.
 */
@Composable
fun posterEdgeShape(): Shape {
    val context = LocalContext.current
    return PosterEdge.fromStored(AppPreferences.getPosterEdge(context)).shape()
}
