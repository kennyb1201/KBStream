package com.kennyb1201.kbstream.ui.player

import android.app.Activity
import android.os.Build
import android.util.Log
import android.view.Display
import kotlin.math.abs
import kotlin.math.round

/**
 * Panel-side frame-rate matching, behind Settings → Playback → Match Content
 * Frame Rate. Off by default: it changes what the whole screen does, and the
 * choice is opted into rather than assumed.
 *
 * Why it exists: a TV panel shows one fixed rate — overwhelmingly 60 Hz — while
 * film is 23.976 fps. 24 frames into 60 slots is not a whole number, so the
 * panel repeats some frames and drops others, which is the judder people
 * describe when a slow pan "stutters" or a film looks subtly wrong. Asking the
 * panel for a mode that is an exact multiple of the content rate (24, 48 or
 * 50 Hz) removes it.
 *
 * Split the way [Trickplay] is split: every decision lives in [FrameRateMatch]
 * as pure arithmetic over [DisplayModeInfo], so it is unit tested without a
 * device, and [FrameRateMatcher] is the thin Android shell that reads the panel
 * once and writes one window attribute.
 *
 * Two deliberate limits, both chosen to fail quiet:
 *  - A mode only counts as a match when it is a near-exact multiple of the
 *    content rate. 23.976 fps content on a 60 Hz-only panel has no integer
 *    between them, so nothing is switched: the panel is left exactly as it was
 *    rather than flashed for no gain. Many boxes report only the mode they are
 *    already in, which lands in the same place.
 *  - Whatever is switched is switched back when the player exits. A panel left
 *    at 24 Hz would make the whole TV interface — menus, launcher, everything —
 *    look wrong, which is far worse than the judder this fixes.
 */
internal data class DisplayModeInfo(val id: Int, val refreshRate: Double)

internal object FrameRateMatch {

    /** Below this a "rate" is a misparse or a telecine artifact, not content. */
    private const val MIN_CONTENT_FPS = 10.0

    /** Above this nothing real is being described. */
    private const val MAX_CONTENT_FPS = 121.0

    /**
     * Standard rates, used to settle the noise in what the player reports.
     * Extractors disagree about the same film — 23.976, 23.98 and 23.976023
     * all appear for one file — and the arithmetic below is much easier to
     * reason about against a fixed set.
     */
    private val STANDARD_RATES = doubleArrayOf(
        23.976, 24.0, 25.0, 29.97, 30.0,
        47.952, 48.0, 50.0, 59.94, 60.0,
        100.0, 119.88, 120.0
    )

    /**
     * How far a panel mode may sit from an exact multiple and still be worth
     * switching to, as a fraction of that multiple.
     *
     * 0.5% accepts a true 24.000 mode for 23.976 content — 0.1% off, and the
     * standard answer: the 0.024 fps drift is one frame per 41 seconds, which
     * the panel's own pulldown absorbs invisibly — and rejects 60 Hz for film
     * content, which is 150% off.
     */
    private const val TOLERANCE = 0.005

    /**
     * [contentFps] snapped onto a standard rate when it is close enough to one,
     * or null when it does not describe content at all.
     */
    fun normalise(contentFps: Double): Double? {
        if (!contentFps.isFinite()) return null
        if (contentFps < MIN_CONTENT_FPS || contentFps > MAX_CONTENT_FPS) return null
        val nearest = STANDARD_RATES.minByOrNull { abs(it - contentFps) } ?: return contentFps
        return if (abs(nearest - contentFps) / nearest <= 0.01) nearest else contentFps
    }

    /**
     * The panel mode to switch to, or null when the panel must be left alone.
     *
     * A mode qualifies when its rate is an exact multiple of the content rate
     * within [TOLERANCE] — 50 Hz for 25 fps content, 60 Hz for 29.97 — and the
     * smallest such multiple wins, because that is the one with the fewest
     * repeated frames. Ties break on the smaller rate difference, then on the
     * lower mode id so the answer does not depend on the order the box reports
     * its modes in. Null also covers "already there": [currentModeId] matching
     * the winner means there is nothing to do.
     */
    fun chooseMode(
        contentFps: Double,
        modes: List<DisplayModeInfo>,
        currentModeId: Int
    ): DisplayModeInfo? {
        val content = normalise(contentFps) ?: return null

        var best: DisplayModeInfo? = null
        var bestMultiple = 0
        var bestDiff = 0.0

        modes.forEach { mode ->
            if (!mode.refreshRate.isFinite() || mode.refreshRate <= 0.0) return@forEach
            // An integer multiple, never a fraction: a mode SLOWER than the
            // content cannot show every frame and is not a match.
            val multiple = round(mode.refreshRate / content).toInt().coerceAtLeast(1)
            val ideal = content * multiple
            val diff = abs(mode.refreshRate - ideal)
            if (diff / ideal > TOLERANCE) return@forEach

            val better = when {
                best == null -> true
                multiple != bestMultiple -> multiple < bestMultiple
                else -> diff < bestDiff - 1e-9 ||
                    (diff <= bestDiff + 1e-9 && mode.id < best!!.id)
            }
            if (better) {
                best = mode
                bestMultiple = multiple
                bestDiff = diff
            }
        }

        return best?.takeIf { it.id != currentModeId }
    }
}

/**
 * The Android half: reads the panel's modes and asks for one.
 *
 * One instance per player session, created before the player can report a
 * video track, because the value it restores is captured at construction — the
 * window attribute as it was before this class ever touched it. A second
 * instance created after a switch would capture the switched value and restore
 * the panel to it, leaving the UI at film rate after the title ended.
 */
internal class FrameRateMatcher(private val activity: Activity) {

    private val originalPreferredModeId = activity.window.attributes.preferredDisplayModeId

    /** The mode this session asked for, or [UNSET] when it has asked for none. */
    private var appliedModeId = UNSET

    /**
     * Offers one content frame rate. Safe to call as often as the player likes:
     * a rate that needs no switch, a panel that cannot do it, and a repeat of a
     * switch already made all resolve to doing nothing.
     */
    fun onContentFrameRate(contentFps: Double) {
        runCatching { apply(contentFps) }
            .onFailure { Log.w(TAG, "frame-rate match failed", it) }
    }

    /** Puts the panel back. Called from the player's teardown. */
    fun release() {
        if (appliedModeId == UNSET) return
        appliedModeId = UNSET
        runCatching {
            val attributes = activity.window.attributes
            attributes.preferredDisplayModeId = originalPreferredModeId
            activity.window.attributes = attributes
            Log.i(TAG, "restored display mode preference $originalPreferredModeId")
        }.onFailure { Log.w(TAG, "could not restore the display mode", it) }
    }

    private fun apply(contentFps: Double) {
        val display = displayOf() ?: return
        // What the panel is running NOW, not what the window asked for: a box
        // that ignores the request keeps reporting its old mode, so the next
        // call re-asks instead of believing it succeeded.
        val currentModeId = runCatching { display.mode.modeId }.getOrNull() ?: return
        val modes = runCatching {
            display.supportedModes.map { DisplayModeInfo(it.modeId, it.refreshRate.toDouble()) }
        }.getOrDefault(emptyList())

        val chosen = FrameRateMatch.chooseMode(contentFps, modes, currentModeId) ?: return

        val attributes = activity.window.attributes
        attributes.preferredDisplayModeId = chosen.id
        activity.window.attributes = attributes
        appliedModeId = chosen.id
        Log.i(
            TAG,
            "matched ${"%.3f".format(contentFps)} fps to mode ${chosen.id} " +
                "at ${chosen.refreshRate} Hz (was $currentModeId)"
        )
    }

    @Suppress("DEPRECATION")
    private fun displayOf(): Display? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display
        // getDefaultDisplay is the only route below API 30, where this app still
        // ships (minSdk 23).
        else activity.windowManager.defaultDisplay

    private companion object {
        const val TAG = "PLAYER_AFR"
        const val UNSET = -1
    }
}
