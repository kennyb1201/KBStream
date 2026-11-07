package com.kennyb1201.kbstream.ui.player

/**
 * What the bitmap subtitle path has to remember between two cue groups so a
 * MID-CUE size change can rescale the line already on screen.
 *
 * The scale itself is [SubtitleOverlayStyle.bitmapScaleForSize], and it needs
 * two numbers that only a cue group carries: the authored height of the bitmap
 * being drawn, and the height of the frame it was authored against. The cue
 * handler is handed both in `showBitmapCues`, applies the geometry there, and
 * is asked to re-apply it from `refreshStyle` when the viewer moves the size
 * setting - at which point there is no cue group in hand. Without this holder
 * the setting would only take effect at the NEXT cue, which is the "changing
 * size does nothing until the next subtitle" report.
 *
 * Pure (no Android types) so the retention itself is JVM-testable, and so the
 * handler has one place that answers "what is the current bitmap scale" rather
 * than a second copy of the arithmetic.
 */
internal class BitmapCueGeometry {

    /** The tallest authored bitmap line-block drawn for the current track, 0 when none. */
    var bitmapHeightPx: Int = 0
        private set

    /** The playing video's height the bitmap was authored against, 0 when unknown. */
    var frameHeightPx: Int = 0
        private set

    /** True while a bitmap group has supplied the geometry (see `clear`). */
    val hasBitmap: Boolean
        get() = bitmapHeightPx > 0

    /**
     * Remembers the group about to be drawn. A zero/negative height keeps the
     * values empty, which [scaleFor] answers with 1.0 (authored size) rather
     * than a fabricated scale.
     */
    fun remember(bitmapHeightPx: Int, frameHeightPx: Int) {
        this.bitmapHeightPx = bitmapHeightPx.coerceAtLeast(0)
        this.frameHeightPx = frameHeightPx.coerceAtLeast(0)
    }

    /**
     * Drops the retained geometry - the bitmap frame it belonged to is gone
     * (a text group arrived, the track changed, the player was rebuilt), so a
     * later text line must not inherit its scale.
     */
    fun clear() {
        bitmapHeightPx = 0
        frameHeightPx = 0
    }

    /**
     * The scale for the retained bitmap at [sizeIndex], or 1.0 when nothing
     * has been drawn yet. [fallbackFrameHeightPx] is the view's own height,
     * used when the player had not reported a video height when the group
     * arrived.
     */
    fun scaleFor(
        sizeIndex: Int,
        scaledDensity: Float,
        fallbackFrameHeightPx: Int
    ): Float {
        if (!hasBitmap) return 1f
        val frame = frameHeightPx.takeIf { it > 0 } ?: fallbackFrameHeightPx
        return SubtitleOverlayStyle.bitmapScaleForSize(
            sizeIndex = sizeIndex,
            bitmapHeightPx = bitmapHeightPx,
            frameHeightPx = frame,
            scaledDensity = scaledDensity
        )
    }
}
