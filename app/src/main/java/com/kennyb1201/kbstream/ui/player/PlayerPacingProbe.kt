package com.kennyb1201.kbstream.ui.player

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.Display
import androidx.media3.common.Format
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.video.VideoFrameMetadataListener
import com.kennyb1201.kbstream.data.reporting.PlaybackSessionTrace
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * What the player's frame-release path is ACTUALLY doing once a matched mode is
 * on the panel.
 *
 * The 2026-10-30 field table (Onn 4K Plus → TCL) isolates the judder to
 * matching itself: 24.000 and 23.976 both judder, 60 Hz is smooth with either
 * tunneling setting, and the switch demonstrably lands (`afr panel moved`). What
 * that table cannot say is *why*. Its lead suspect is that the player's frame
 * release keeps pacing against the display it sampled BEFORE the mode move -
 * 23.976 content released on a 60 Hz cadence into a 23.976 Hz panel drops and
 * repeats frames from the first second of playback, at either matched rate,
 * which is exactly the shape reported.
 *
 * This probe is how that question gets answered from a field log, using no
 * private API:
 *
 *  - the DISPLAY as the panel and the platform report it (`Display.mode`,
 *    `Display.getRefreshRate`), re-read on the system's own display-change
 *    broadcast, so the moment of the move is on the record;
 *  - the APP's vsync, measured from consecutive `Choreographer` frames. This is
 *    the compositor's real cadence and it is deliberately not the number the
 *    display claims: a box that reports a 23.976 mode while still composing at
 *    60 Hz looks identical to a healthy one from `Display` alone, and that case
 *    is a different defect (nothing the player does can be smooth on it);
 *  - the PLAYER's release cadence, from `VideoFrameMetadataListener`, which is
 *    handed the release timestamp of every frame the renderer actually releases.
 *    Consecutive release timestamps are one release grid, so their spacing says
 *    which cadence the release path snaps to, and comparing that grid with the
 *    measured vsync is the whole test: `pacing=follows` closes the pacing
 *    suspect, `pacing=not-follows` confirms it.
 *
 * It writes ONE line per engaged session, plus a line whenever the display
 * changes while engaged. The session ring holds 24 lines and the diagnostics a
 * report quotes are short: a window-per-line probe would push the AFR and
 * `player.stats` lines a report needs out of it.
 *
 * Read-only by construction: it never writes a surface rate, never a window
 * attribute, and never a player setting. A diagnostics change must not be able
 * to change what it measures.
 *
 * Why this is a probe and not a fix, stated where the next reader will look for
 * the missing call: the pinned media3 (1.11.1) has NO way to tell the player a
 * display moved. `VideoFrameReleaseControl` (the object that owns pacing) is
 * private to `MediaCodecVideoRenderer`, is not accepted by any constructor,
 * factory or builder, and exposes no "re-sample the display" method; the only
 * thing in that library that changes what the release path does with a display
 * is `Surface.setFrameRate` - which is exactly the write the 2026-10-22 bisect
 * convicted under a panel the window had already switched, and which this class
 * therefore must not make. The release helper's own vsync sampler follows a
 * display change on its own (a DisplayListener below API 33, a 500 ms
 * FrameTimeline poll above it), so "the player was never told" is only half a
 * mechanism: what has to be shown first is that the releases do NOT follow the
 * new grid, and that is what the line below says.
 */
internal class PlayerPacingProbe(context: Context) {

    private val appContext = context.applicationContext

    private val displayManager: DisplayManager? =
        appContext.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager

    private val handler = Handler(Looper.getMainLooper())

    /** The player this probe is attached to, so [stop] can detach for certain. */
    private var player: ExoPlayer? = null

    private var started = false

    /** The mode the matcher engaged, and the rate that mode reported. */
    private var engagedModeId = NO_MODE
    private var engagedRefreshRate = 0.0

    /**
     * The engaged mode as this line spells it.
     *
     * Built from what the MATCHER confirmed, never from `Display` at summary
     * time: the 2026-10-31 Onn report wrote "mode 79 at 59.94 Hz, app vsync
     * 41.71 ms" for a session that ran at mode 85 / 23.976 Hz, because the box's
     * display object answered with the mode that was on screen BEFORE the move.
     * The measurement was right and the label contradicted it, which is worse
     * than no label at all.
     */
    private var engagedModeText = ""

    /** The compositor's measured vsync period, in ns. Zero until first sampled. */
    @Volatile
    private var vsyncPeriodNs = 0L

    // ---- the release path's own cadence -----------------------------------
    //
    // Written on the player's playback thread, read on the main thread when the
    // summary is built, so every one of these is behind [lock].

    private val lock = Any()
    private var releasedFrames = 0
    private var releaseDeltaCount = 0
    private var releaseDeltaSumNs = 0L
    private var releaseDeltaMinNs = Long.MAX_VALUE
    private var releaseDeltaMaxNs = Long.MIN_VALUE
    private var lastReleaseNs = UNSET
    private var contentDeltaCount = 0
    private var contentDeltaSumUs = 0L
    private var lastPresentationUs = UNSET
    private var gridChecked = 0
    private var gridFrames = 0
    private var gaps = 0

    /** The display's own mode when the probe last looked, for the change line. */
    private var lastSeenModeId = NO_MODE

    private val frameListener = object : VideoFrameMetadataListener {
        override fun onVideoFrameAboutToBeRendered(
            presentationTimeUs: Long,
            releaseTimeNs: Long,
            format: Format,
            mediaFormat: MediaFormat?
        ) {
            synchronized(lock) { recordFrame(presentationTimeUs, releaseTimeNs) }
        }
    }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit

        override fun onDisplayRemoved(displayId: Int) = Unit

        /**
         * The move itself, as the system reports it. One line per mode, not per
         * callback: a switch renegotiates HDMI and the display can announce the
         * same mode more than once.
         */
        override fun onDisplayChanged(displayId: Int) {
            if (!started || displayId != Display.DEFAULT_DISPLAY) return
            val display = displayManager?.getDisplay(Display.DEFAULT_DISPLAY) ?: return
            val mode = runCatching { display.mode }.getOrNull() ?: return
            val rate = runCatching { display.refreshRate.toDouble() }.getOrDefault(0.0)
            if (mode.modeId == lastSeenModeId) return
            lastSeenModeId = mode.modeId
            val line = "pacing display changed: ${engagedLabel()}, display now " +
                "mode=${mode.modeId} at ${ms(mode.refreshRate.toDouble())} Hz " +
                "(display says ${ms(rate)} Hz)"
            PlaybackSessionTrace.note(line)
            Log.i(TAG, line)
        }
    }

    /**
     * Begins (or restarts) the measurement for one engaged mode.
     *
     * Called when the mode move is CONFIRMED on the panel, and again when the
     * hold tick has to re-ask for a mode the panel left. A restart on the same
     * mode keeps the window running instead of starting a second one: the
     * re-ask is part of the same engagement, and a fresh window would lose the
     * frames either side of it.
     */
    fun start(player: ExoPlayer?, modeId: Int, refreshRateHz: Double) {
        if (player == null) return
        if (started && modeId == engagedModeId) return
        if (started) stop()
        this.player = player
        engagedModeId = modeId
        engagedRefreshRate = refreshRateHz
        engagedModeText = "mode $modeId at ${ms(refreshRateHz)} Hz"
        synchronized(lock) {
            releasedFrames = 0
            releaseDeltaCount = 0
            releaseDeltaSumNs = 0L
            releaseDeltaMinNs = Long.MAX_VALUE
            releaseDeltaMaxNs = Long.MIN_VALUE
            lastReleaseNs = UNSET
            contentDeltaCount = 0
            contentDeltaSumUs = 0L
            lastPresentationUs = UNSET
            gridChecked = 0
            gridFrames = 0
            gaps = 0
        }
        started = true
        lastSeenModeId = runCatching {
            displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.mode?.modeId
        }.getOrNull() ?: NO_MODE
        runCatching { player.setVideoFrameMetadataListener(frameListener) }
            .onFailure { Log.w(TAG, "could not observe frame release times", it) }
        runCatching { displayManager?.registerDisplayListener(displayListener, handler) }
        beginVsyncBurst()
        Log.i(
            TAG,
            "pacing probe started: mode=$modeId at ${ms(refreshRateHz)} Hz"
        )
    }

    /** Ends the measurement and writes its one summary line. */
    fun stop() {
        if (!started) return
        started = false
        runCatching { player?.clearVideoFrameMetadataListener(frameListener) }
        runCatching { displayManager?.unregisterDisplayListener(displayListener) }
        handler.removeCallbacks(burstStarter)
        choreographer?.let { c -> runCatching { c.removeFrameCallback(vsyncCallback) } }
        player = null
        val line = summary()
        PlaybackSessionTrace.note(line)
        Log.i(TAG, line)
        engagedModeId = NO_MODE
        engagedRefreshRate = 0.0
    }

    // ---- the release path's measurements ---------------------------------

    private fun recordFrame(presentationTimeUs: Long, releaseTimeNs: Long) {
        releasedFrames++
        val previous = lastReleaseNs
        lastReleaseNs = releaseTimeNs
        if (previous != UNSET) {
            val delta = releaseTimeNs - previous
            // A pause, a seek or a stall is not a pacing sample: one gap of a
            // few seconds would swamp an average of ~40 ms samples and report a
            // defect in a session that played cleanly. Counted, not averaged.
            if (delta > GAP_NS) {
                gaps++
            } else if (delta > 0) {
                releaseDeltaCount++
                releaseDeltaSumNs += delta
                if (delta < releaseDeltaMinNs) releaseDeltaMinNs = delta
                if (delta > releaseDeltaMaxNs) releaseDeltaMaxNs = delta
                val grid = vsyncPeriodNs
                if (grid > 0) {
                    gridChecked++
                    val slots = (delta.toDouble() / grid).roundToLong()
                    val onGrid = slots >= 1 && abs(delta - slots * grid) <= grid / 8
                    if (onGrid) gridFrames++
                }
            }
        }
        val previousPresentation = lastPresentationUs
        lastPresentationUs = presentationTimeUs
        if (previousPresentation != UNSET) {
            val delta = presentationTimeUs - previousPresentation
            if (delta > 0) {
                contentDeltaCount++
                contentDeltaSumUs += delta
            }
        }
    }

    private fun summary(): String {
        val display = runCatching {
            displayManager?.getDisplay(Display.DEFAULT_DISPLAY)
        }.getOrNull()
        val mode = runCatching { display?.mode }.getOrNull()
        // The engaged mode is the label; the display's own answer is quoted
        // beside it. It is a claim, not a measurement, and on this chain it has
        // already been caught answering with the pre-move mode.
        val modeText = engagedLabel()
        val liveText = if (mode != null) {
            "display reports mode ${mode.modeId} at ${ms(mode.refreshRate.toDouble())} Hz"
        } else {
            "display reports no mode"
        }
        val vsync = vsyncPeriodNs
        val releaseAvg: Long
        val releaseMin: Long
        val releaseMax: Long
        val frames: Int
        val contentAvg: Long
        val gridShare: Int
        val gapCount: Int
        synchronized(lock) {
            frames = releasedFrames
            releaseAvg = if (releaseDeltaCount > 0) releaseDeltaSumNs / releaseDeltaCount else 0L
            releaseMin = if (releaseDeltaCount > 0) releaseDeltaMinNs else 0L
            releaseMax = if (releaseDeltaCount > 0) releaseDeltaMaxNs else 0L
            contentAvg = if (contentDeltaCount > 0) contentDeltaSumUs / contentDeltaCount else 0L
            gridShare = if (gridChecked > 0) gridFrames * 100 / gridChecked else NO_SHARE
            gapCount = gaps
        }
        if (releaseAvg <= 0L) {
            return "pacing: $modeText, $liveText, no frames observed (frames=$frames)"
        }
        val span = "${ms(releaseMin / 1000_000.0)}..${ms(releaseMax / 1000_000.0)} ms"
        val content = if (contentAvg > 0L) "${ms(contentAvg / 1000.0)} ms" else "unknown"
        val grid = if (gridShare >= 0) ", on vsync grid ${gridShare}%" else ""
        val gap = if (gapCount > 0) ", gaps=$gapCount" else ""
        val vsyncText = if (vsync > 0L) "${ms(vsync / 1000_000.0)} ms" else "unknown"
        val claim = if (mode != null && claimDisagrees(mode.refreshRate.toDouble(), vsync)) {
            ", display claim stale"
        } else {
            ""
        }
        val verdict = verdict(vsync, releaseAvg, contentAvg * 1000L)
        return "pacing: $modeText, $liveText, app vsync $vsyncText, " +
            "release ${ms(releaseAvg / 1000_000.0)} ms over $frames frames ($span), " +
            "content $content$grid$gap$claim — pacing=$verdict"
    }

    private fun engagedLabel(): String =
        if (engagedModeText.isNotEmpty()) {
            engagedModeText
        } else {
            "mode $engagedModeId at ${ms(engagedRefreshRate)} Hz"
        }

    /**
     * True when the rate the display claims is not the rate the compositor was
     * measured running at. The 41.71 ms session above is the case: `Display`
     * said 59.94 Hz while every measured frame callback was 41.71 ms apart.
     */
    private fun claimDisagrees(claimHz: Double, vsyncNs: Long): Boolean {
        if (claimHz <= 0.0 || vsyncNs <= 0L) return false
        val measured = 1_000_000_000.0 / vsyncNs
        return abs(claimHz - measured) * 20 > measured
    }

    /**
     * The test the field table needs, and it has three answers rather than two.
     *
     * `follows`: every spacing agrees (all within 5%) - the release path paces
     * the content, and the display really runs the rate it reports. The pacing
     * suspect is closed, and what is left is the panel (or the box's
     * composition).
     *
     * `not-follows`: the releases are not spaced like the content. That is the
     * defect as stated - the release path is pacing to something else - and it
     * is the only answer that a fix could act on.
     *
     * `display-not-at-rate`: the releases pace the content correctly, but the
     * compositor's own vsync is not the mode's rate, i.e. the box reports a
     * 23.976 mode while still composing at another rate. Nothing the player does
     * can be smooth on that, and it is worth saying plainly rather than leaving
     * the reader to compare four numbers.
     *
     * `unknown` on no evidence, rather than a guess.
     */
    private fun verdict(vsync: Long, releaseAvgNs: Long, contentNs: Long): String {
        fun near(a: Long, b: Long): Boolean = b > 0L && abs(a - b) * 20 <= b
        return when {
            contentNs <= 0L -> if (vsync > 0L && near(releaseAvgNs, vsync)) {
                "follows"
            } else {
                "unknown"
            }
            !near(releaseAvgNs, contentNs) -> "not-follows"
            vsync <= 0L -> "unknown"
            near(vsync, contentNs) -> "follows"
            else -> "display-not-at-rate"
        }
    }

    // ---- the compositor's own cadence ------------------------------------

    private val vsyncCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val previous = lastVsyncFrameNs
            lastVsyncFrameNs = frameTimeNanos
            if (previous != UNSET) {
                val delta = frameTimeNanos - previous
                if (delta > 0) vsyncDeltas.add(delta)
            }
            burstLeft--
            if (burstLeft > 0) {
                choreographer?.postFrameCallback(this)
            } else {
                vsyncPeriodNs = medianOf(vsyncDeltas)
                vsyncDeltas.clear()
                lastVsyncFrameNs = UNSET
                handler.postDelayed(burstStarter, VSYNC_BURST_PERIOD_MS)
            }
        }
    }

    private val burstStarter = Runnable { beginVsyncBurst() }

    /**
     * Samples a short run of CONSECUTIVE frames and keeps their median spacing.
     *
     * The spacing of consecutive frame callbacks is the compositor's period;
     * the spacing of runs taken 500 ms apart is not. So a burst rather than a
     * slow poll - and a burst repeated, so a move mid-session replaces the
     * value instead of freezing the one sampled at engagement.
     */
    private fun beginVsyncBurst() {
        if (!started) return
        choreographer = choreographer ?: runCatching { Choreographer.getInstance() }.getOrNull()
        lastVsyncFrameNs = UNSET
        vsyncDeltas.clear()
        burstLeft = BURST_FRAMES
        choreographer?.postFrameCallback(vsyncCallback)
    }

    private var choreographer: Choreographer? = null
    private var burstLeft = 0
    private var lastVsyncFrameNs = UNSET
    private val vsyncDeltas = ArrayList<Long>(BURST_FRAMES)

    private fun medianOf(values: List<Long>): Long {
        if (values.isEmpty()) return vsyncPeriodNs
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    private companion object {
        const val TAG = "PLAYER_AFR"
        const val UNSET = -1L
        const val NO_MODE = -1
        const val NO_SHARE = -1

        /** Consecutive frames per sampling burst (a burst of 6 is ~100 ms at 60 Hz). */
        const val BURST_FRAMES = 6

        /** How long a sampled vsync is trusted before it is sampled again. */
        const val VSYNC_BURST_PERIOD_MS = 1000L

        /** Above this, a spacing is a pause or a stall rather than a cadence. */
        const val GAP_NS = 250_000_000L

        fun ms(value: Double): String = String.format(Locale.US, "%.2f", value)
    }
}
