package com.kennyb1201.kbstream.ui.player

/**
 * The arithmetic behind the phone player's touch layer, as pure functions.
 *
 * The players were built for a remote: transport is a key, a seam between two
 * frames is a scrub, and the only pointer on the screen is the D-pad's ring.
 * A phone has none of that, so the touch layer adds the three gestures a phone
 * viewer reaches for - tap for the chrome, horizontal drag for a seek, vertical
 * drag for brightness (left half) or volume (right half), pinch for the aspect
 * modes - and this file is every decision those gestures make, kept out of the
 * activities so it can be pinned without a device.
 *
 * Nothing here touches a player. The activities hold the state; this holds the
 * rule. That split is the same one [FrameRateMatch] and [PlaybackRecoveryRules]
 * use, and it is why the tests can assert the numbers a finger produces.
 *
 * The seek rule has one invariant borrowed verbatim from the TV scrub: a seek
 * must not decide whether the film is playing. [playAfterSeek] returns the state
 * the gesture found, and the wiring commit seeks without calling play or pause,
 * so a swipe while paused lands paused and a swipe while playing keeps playing.
 */
internal object PlayerTouchRules {

    /** Which half of the video a vertical drag started in. */
    enum class Zone { LEFT, RIGHT }

    /** What a drag past the slop has been read as. */
    enum class Axis { SEEK, VERTICAL }

    /** The darkest the window is allowed to go: black is a black video frame. */
    const val MIN_BRIGHTNESS = 0.02f

    /**
     * A full-width drag moves this much of the film, clamped: short enough to
     * reach the next scene, long enough that the whole width is not a single
     * frame, and never more than the duration itself.
     */
    const val MIN_SEEK_SPAN_MS = 30_000L
    const val MAX_SEEK_SPAN_MS = 120_000L

    /** How far a drag has to move before a lift is a tap rather than a drag. */
    const val TAP_SLOP_FRACTION = 0.005f

    fun zoneFor(x: Float, widthPx: Int): Zone =
        if (x < widthPx / 2f) Zone.LEFT else Zone.RIGHT

    /**
     * Reads a drag as a seek or a vertical control, or null while it is still
     * inside the slop (which is what keeps a wobbling tap a tap).
     *
     * The seek wins a diagonal because the caller that most often wobbles
     * diagonally is a thumb reaching for the scrub, and a seek is the gesture
     * that keeps its promise either way.
     */
    fun axisFor(dx: Float, dy: Float, slop: Float): Axis? {
        val adx = kotlin.math.abs(dx)
        val ady = kotlin.math.abs(dy)
        if (adx < slop && ady < slop) return null
        return if (adx > ady) Axis.SEEK else Axis.VERTICAL
    }

    /** The film distance a drag across the whole width covers. */
    fun seekSpanMs(durationMs: Long): Long =
        durationMs.coerceIn(MIN_SEEK_SPAN_MS, MAX_SEEK_SPAN_MS)

    /**
     * The position a horizontal drag of [dxFraction] of the width lands on, or
     * null when there is nothing to seek: a live stream reports no duration, and
     * a drag that never left the slop has no target.
     */
    fun seekTarget(positionMs: Long, durationMs: Long, dxFraction: Float): Long? {
        if (durationMs <= 0L) return null
        if (kotlin.math.abs(dxFraction) < TAP_SLOP_FRACTION) return null
        val delta = (seekSpanMs(durationMs) * dxFraction).toLong()
        return (positionMs + delta).coerceIn(0L, durationMs)
    }

    /** The signed move the hint names, or null where [seekTarget] is null. */
    fun seekDeltaMs(positionMs: Long, durationMs: Long, dxFraction: Float): Long? =
        seekTarget(positionMs, durationMs, dxFraction)?.minus(positionMs)

    /**
     * Brightness after a vertical drag of [dyFraction] of the height. Up is
     * brighter, because that is the direction the finger pulls the level.
     */
    fun brightnessAfter(start: Float, dyFraction: Float): Float =
        (start - dyFraction).coerceIn(MIN_BRIGHTNESS, 1f)

    /** Volume after the same drag: the system's own stream, 0..1. */
    fun volumeAfter(start: Float, dyFraction: Float): Float =
        (start - dyFraction).coerceIn(0f, 1f)

    /**
     * The play state a seek must leave behind: the one it found.
     *
     * Never the state after the seek, never a resume: the D-pad scrub learned
     * this the hard way, and a finger gets the same promise.
     */
    fun playAfterSeek(playWhenReadyBefore: Boolean): Boolean = playWhenReadyBefore

    /** "Brightness 62%" / "Volume 40%": the feedback a vertical drag gets. */
    fun levelHint(label: String, fraction: Float): String {
        val percent = (fraction.coerceIn(0f, 1f) * 100f).toInt()
        return "$label $percent%"
    }

    /** "+1:23 -- 12:40" for a seek: the move, then where it lands. */
    fun seekHint(deltaMs: Long, targetMs: Long): String {
        val sign = if (deltaMs < 0) "-" else "+"
        val shown = kotlin.math.abs(deltaMs)
        return "$sign${clock(shown)}  ${clock(targetMs)}"
    }

    fun clock(ms: Long): String {
        val total = (ms.coerceAtLeast(0L) / 1000L)
        val hours = total / 3600L
        val minutes = (total % 3600L) / 60L
        val seconds = total % 60L
        return if (hours > 0L) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }

    /**
     * The height of the video band in a portrait player: the picture keeps its
     * 16:9 shape at the top of the screen and the chrome gets the rest, instead
     * of a landscape picture letterboxed into a sliver with the controls drawn
     * over it.
     */
    fun portraitBandHeightPx(widthPx: Int, heightPx: Int): Int {
        if (widthPx <= 0 || heightPx <= 0) return 0
        val band = (widthPx * 9) / 16
        return band.coerceIn(0, heightPx)
    }

    /**
     * Whether the portrait stage applies: phones only, and only upright. TV
     * keeps its full-panel picture, and a landscape phone keeps the picture
     * behind the chrome exactly as the remote does.
     */
    fun portraitStageApplies(isTv: Boolean, portrait: Boolean): Boolean =
        !isTv && portrait
}
