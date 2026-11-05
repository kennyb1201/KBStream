package com.kennyb1201.kbstream.ui.player

/**
 * The one mapping from the app's subtitle settings to what each renderer can
 * honour - and the one list of text sizes both engines answer to.
 *
 * Two questions live here, because they are the same question asked twice:
 * what a size index means to an IMAGE-based renderer (the scale and lift
 * below), and what it means to mpv's own text renderer ([mpvFontSize], which
 * is stated in the main player's sp steps so the two engines cannot disagree
 * about how big "large" is).
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

    /**
     * The text-cue steps of the MAIN player, in sp - `renderText`'s own list.
     *
     * One list, two engines: the MPV engine's regular subtitles are sized from
     * these same three numbers (see [mpvFontSize]) instead of carrying a second
     * set of its own, because one shared setting cannot mean 18sp on one engine
     * and something else on the other.
     */
    private val EXO_TEXT_SP = floatArrayOf(11f, 14f, 18f)

    /** The Exo size step for a size index, in sp. Out of range reads as neutral. */
    fun exoTextSp(sizeIndex: Int): Float =
        EXO_TEXT_SP.getOrElse(sizeIndex) { EXO_TEXT_SP[SIZE_NEUTRAL] }

    /**
     * The height mpv's `sub-font-size` is read against: mpv's own reference is
     * a WINDOW height of 720 (mpv's manual: the unit is "the size in scaled
     * pixels at a window height of 720", and `--sub-scale-by-window` /
     * `--sub-scale-with-window` both default to yes), so the same number on a
     * 1080-tall window renders 1.5x what it does on a 720-tall one.
     *
     * A model constant, not a measurement: if a field check shows the two
     * engines differ by one factor at every height, this is the one number to
     * move - never a per-resolution table.
     */
    const val MPV_REFERENCE_HEIGHT_PX = 720f

    /**
     * mpv's `sub-font-size` for a size index and the height of the WINDOW mpv
     * is drawing into - the surface, not the file.
     *
     * [scaledDensity] is the sp-to-pixel factor `COMPLEX_UNIT_SP` resolves
     * through (user font scale included), which is what [exoTextSp] is in; those
     * pixels are then re-expressed against mpv's 720-reference window, which is
     * the scale mpv draws in. So the same setting is the same rendered height
     * on both engines - Exo's 11 / 14 / 18sp, at whatever density the screen
     * reports - on every title, whatever its resolution.
     *
     * [windowHeightPx] is the SURFACE height, and it is the one number this
     * must use. Sizing against the VIDEO's height (`video-params/h`, which this
     * did) is wrong by exactly the two heights' ratio, because mpv scales its
     * cues with the window: a 2160p title on a 1080-tall surface came out HALF
     * size, and a 720p one on the same surface half again too large. That is
     * the "subtitles are tiny in mpv" report, and DV/HDR remuxes - usually
     * 2160p - were the titles it showed on.
     *
     * 0 means the surface has not been measured yet (or there is none); that is
     * answered with [mpvFallbackFontSize] rather than a guess.
     */
    fun mpvFontSize(sizeIndex: Int, scaledDensity: Float, windowHeightPx: Int): Double {
        if (windowHeightPx <= 0) return mpvFallbackFontSize(sizeIndex)
        val targetPx = exoTextSp(sizeIndex) * scaledDensity
        return (targetPx * MPV_REFERENCE_HEIGHT_PX / windowHeightPx).toDouble()
    }

    /**
     * mpv's fixed steps for a size index, used only while the video's height is
     * unknown: mpv's own 55 default +/-20%, which is what this engine applied
     * unconditionally before the size was computed.
     */
    fun mpvFallbackFontSize(sizeIndex: Int): Double = when (sizeIndex) {
        SIZE_SMALL -> 44.0
        SIZE_LARGE -> 66.0
        else -> 55.0
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
