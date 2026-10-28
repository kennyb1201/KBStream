package com.kennyb1201.kbstream.ui.player

/**
 * The one mapping from the app's subtitle settings to what an IMAGE-based
 * renderer can honour.
 *
 * Text cues are restyled directly (size, background, gravity). The other two
 * subtitle paths in the Exo player are pictures with authored pixels: Media3's
 * bitmap cues (PGS today) and the libass overlay that typesets an ASS/SSA
 * script. Neither can be re-typeset by the app, so the honest mapping of the
 * two settings that DO mean something for a picture is:
 *
 *  - SIZE is a scale on the view, anchored bottom-centre - where a subtitle
 *    sits - so growing it pushes up from the same baseline instead of sinking
 *    off screen;
 *  - POSITION is a vertical lift, in the same low / mid / high bands the text
 *    renderer's gravity uses.
 *
 * Kept as a top-level object with no Android types so the rule is testable on
 * the JVM, and shared by both paths so a bitmap cue and an ASS line cannot
 * disagree about what "large" or "high" means (audit U-F14).
 */
internal object SubtitleOverlayStyle {

    /** The size steps, as the preference stores them. */
    const val SIZE_SMALL = 0
    const val SIZE_NEUTRAL = 1
    const val SIZE_LARGE = 2

    /** The position steps, as the preference stores them. */
    const val POSITION_LOW = 0
    const val POSITION_MID = 1
    const val POSITION_HIGH = 2

    /** +/-15/20% around the authored picture, matching the text steps. */
    const val SCALE_SMALL = 0.85f
    const val SCALE_LARGE = 1.2f

    /** How far one position step lifts the picture, in dp. */
    const val POSITION_STEP_DP = 40f

    /**
     * The view scale for a size index. An out-of-range index (a synced blob
     * written by another build) reads as neutral rather than throwing on the
     * first cue of every launch.
     */
    fun scaleForSize(sizeIndex: Int): Float = when (sizeIndex) {
        SIZE_SMALL -> SCALE_SMALL
        SIZE_LARGE -> SCALE_LARGE
        else -> 1f
    }

    /** The lift for a position index, in dp (negative is up). */
    fun liftDpForPosition(positionIndex: Int): Float = when (positionIndex) {
        POSITION_HIGH -> -POSITION_STEP_DP * 2f
        POSITION_MID -> -POSITION_STEP_DP
        else -> 0f
    }

    /**
     * The lift in pixels for a view of [viewHeightPx], scaled so the same
     * setting reads the same on a 720p and a 4K surface instead of a fixed
     * pixel nudge that disappears on a big screen.
     */
    fun liftPxForPosition(positionIndex: Int, viewHeightPx: Int): Float {
        val height = viewHeightPx.takeIf { it > 0 }?.toFloat() ?: 0f
        return (liftDpForPosition(positionIndex) / REFERENCE_HEIGHT_DP) * height
    }

    /** The height [liftPxForPosition] is expressed against. */
    private const val REFERENCE_HEIGHT_DP = 360f
}
