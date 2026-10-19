package com.kennyb1201.kbstream.ui.player

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.media.AudioManager
import android.provider.Settings
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import kotlin.math.roundToInt

/**
 * The phone player's touch layer: tap for the chrome, drag for a seek or for a
 * vertical level, pinch for the aspect modes, hold for fullscreen.
 *
 * Attached to the video surface instead of replacing its click listener, so the
 * remote's path never runs through this: the TV branch in each activity still
 * answers a D-pad press exactly as it did, and this class is only constructed
 * when [com.kennyb1201.kbstream.FormFactor.isTv] is false.
 *
 * What the gestures deliberately do NOT do is change playback state. A seek
 * commits without a pause or a play (see [PlayerTouchRules.playAfterSeek]); the
 * chrome's own scrub bar is the place that pauses, because it has a thumb that
 * says so.
 *
 * Taps are read from [GestureDetector]'s onSingleTapUp rather than a click
 * listener so that a drag never ends in an accidental toggle - the listener has
 * no way to tell a 200px swipe from a still finger.
 */
internal class PlayerTouchGestures(
    private val context: Context,
    private val target: View,
    private val callbacks: Callbacks
) : View.OnTouchListener {

    interface Callbacks {
        /** False while a panel owns the screen (settings, picker, guide). */
        fun gesturesAllowed(): Boolean

        fun toggleChrome()
        fun toggleFullscreen()
        fun cycleAspect()

        fun positionMs(): Long

        /** 0 when the stream is live or the duration is not known yet. */
        fun durationMs(): Long

        fun showSeekHint(text: String)
        fun hideSeekHint()
        fun commitSeek(targetMs: Long)

        fun brightnessFraction(): Float
        fun setBrightnessFraction(fraction: Float)

        fun volumeFraction(): Float
        fun setVolumeFraction(fraction: Float)
    }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private var downX = 0f
    private var downY = 0f
    private var dragAxis: PlayerTouchRules.Axis? = null
    private var zone = PlayerTouchRules.Zone.RIGHT
    private var startBrightness = 1f
    private var startVolume = 1f
    private var seekFromMs = 0L
    private var seekToMs = 0L
    private var seeking = false
    private var pinched = false

    private val tapDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (callbacks.gesturesAllowed()) callbacks.toggleChrome()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                // A hold is fullscreen, but only when the finger stayed put: a
                // drag that slows down mid-seek must not rotate the picture.
                if (dragAxis != null) return
                if (callbacks.gesturesAllowed()) callbacks.toggleFullscreen()
            }
        }
    )

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                // One aspect step per pinch gesture rather than per frame of it.
                if (!pinched) {
                    pinched = true
                    if (callbacks.gesturesAllowed()) callbacks.cycleAspect()
                }
                return true
            }
        }
    )

    fun attach() {
        target.setOnTouchListener(this)
    }

    fun detach() {
        target.setOnTouchListener(null)
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        if (!callbacks.gesturesAllowed()) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) return false
            resetDrag()
            return false
        }
        scaleDetector.onTouchEvent(event)
        if (event.pointerCount > 1) {
            // A second finger is a pinch, never the middle of a drag.
            if (dragAxis != null) callbacks.hideSeekHint()
            pinched = true
            resetDrag()
            return true
        }
        tapDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                dragAxis = null
                seeking = false
                pinched = false
                zone = PlayerTouchRules.zoneFor(event.x, v.width)
                startBrightness = callbacks.brightnessFraction()
                startVolume = callbacks.volumeFraction()
                seekFromMs = callbacks.positionMs()
            }

            MotionEvent.ACTION_MOVE -> {
                if (pinched) return true
                val dx = event.x - downX
                val dy = event.y - downY
                if (dragAxis == null) dragAxis = PlayerTouchRules.axisFor(dx, dy, slop)
                when (dragAxis) {
                    PlayerTouchRules.Axis.SEEK -> updateSeek(dx, v.width)
                    PlayerTouchRules.Axis.VERTICAL -> updateLevel(dy, v.height)
                    null -> Unit
                }
            }

            MotionEvent.ACTION_UP -> {
                if (dragAxis == PlayerTouchRules.Axis.SEEK && seeking) {
                    callbacks.commitSeek(seekToMs)
                }
                if (dragAxis != null) callbacks.hideSeekHint()
                resetDrag()
            }

            MotionEvent.ACTION_CANCEL -> {
                callbacks.hideSeekHint()
                resetDrag()
            }
        }
        return true
    }

    private fun updateSeek(dx: Float, widthPx: Int) {
        if (widthPx <= 0) return
        val fraction = (dx / widthPx).coerceIn(-1f, 1f)
        val target = PlayerTouchRules.seekTarget(
            seekFromMs,
            callbacks.durationMs(),
            fraction
        ) ?: return
        seekToMs = target
        seeking = true
        callbacks.showSeekHint(PlayerTouchRules.seekHint(target - seekFromMs, target))
    }

    private fun updateLevel(dy: Float, heightPx: Int) {
        if (heightPx <= 0) return
        val fraction = (dy / heightPx).coerceIn(-1f, 1f)
        when (zone) {
            PlayerTouchRules.Zone.LEFT -> {
                val level = PlayerTouchRules.brightnessAfter(startBrightness, fraction)
                callbacks.setBrightnessFraction(level)
                callbacks.showSeekHint(PlayerTouchRules.levelHint("Brightness", level))
            }

            PlayerTouchRules.Zone.RIGHT -> {
                val level = PlayerTouchRules.volumeAfter(startVolume, fraction)
                callbacks.setVolumeFraction(level)
                callbacks.showSeekHint(PlayerTouchRules.levelHint("Volume", level))
            }
        }
    }

    private fun resetDrag() {
        dragAxis = null
        seeking = false
    }
}

/**
 * The device-level halves of the touch layer: the window's brightness, the
 * music stream's volume, and the one rotation a phone player allows.
 *
 * Shared by both engines so a swipe means the same thing in either, and so the
 * two activities stay wiring rather than a second copy of the arithmetic.
 */
internal object PlayerTouchDevice {

    fun brightnessFraction(activity: Activity): Float {
        val set = activity.window.attributes.screenBrightness
        if (set >= 0f) return set.coerceIn(PlayerTouchRules.MIN_BRIGHTNESS, 1f)
        // Nothing has overridden the window yet: a drag starts from the level
        // the panel is actually at, read from the system setting that drives it.
        val raw = runCatching {
            Settings.System.getInt(
                activity.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS
            )
        }.getOrDefault(128)
        return (raw / 255f).coerceIn(PlayerTouchRules.MIN_BRIGHTNESS, 1f)
    }

    fun setBrightness(activity: Activity, fraction: Float) {
        val level = fraction.coerceIn(PlayerTouchRules.MIN_BRIGHTNESS, 1f)
        activity.window.attributes = activity.window.attributes.apply {
            screenBrightness = level
        }
    }

    fun volumeFraction(context: Context): Float {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return 0.5f
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return manager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    fun setVolume(context: Context, fraction: Float) {
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val steps = (fraction.coerceIn(0f, 1f) * max).roundToInt()
        manager.setStreamVolume(AudioManager.STREAM_MUSIC, steps, 0)
    }

    /**
     * The fullscreen toggle a hold on the picture owns: portrait goes landscape,
     * landscape goes back to the sensor. Only ever called on a phone - the TV
     * path never constructs the gesture layer, so its orientation is untouched.
     */
    fun toggleFullscreen(activity: Activity) {
        val portrait = activity.resources.configuration.orientation ==
            Configuration.ORIENTATION_PORTRAIT
        activity.requestedOrientation = if (portrait) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}

/**
 * The portrait stage: the picture keeps its 16:9 shape at the top of the screen
 * and the chrome is moved below it, rather than an entire landscape picture
 * being crushed into a sliver behind the controls.
 *
 * Applied by layout rather than by a second XML file: the activities are one
 * layout for two form factors, and a portrait copy of a 1,300-line player layout
 * would drift from the landscape one on the first change. Only the video views'
 * height and the chrome layer's top margin move; every child of the chrome keeps
 * the anchors it always had.
 *
 * The original layout params are remembered per view so a rotation back to
 * landscape restores exactly what the XML declared - including the subtitle
 * views' own gravity, which is where the picture places them.
 */
internal object PlayerPortraitStage {

    private val originals = java.util.WeakHashMap<View, IntArray>()

    fun apply(
        activity: Activity,
        root: View,
        videoViews: List<View?>,
        chromeLayer: View?
    ) {
        val portrait = activity.resources.configuration.orientation ==
            Configuration.ORIENTATION_PORTRAIT
        val applies = PlayerTouchRules.portraitStageApplies(
            com.kennyb1201.kbstream.FormFactor.isTv,
            portrait
        )
        val band = if (applies) {
            PlayerTouchRules.portraitBandHeightPx(root.width, root.height)
        } else {
            0
        }
        if (applies && band <= 0) return

        videoViews.filterNotNull().forEach { view ->
            val params = view.layoutParams as? FrameLayout.LayoutParams ?: return@forEach
            val saved = originals.getOrPut(view) {
                intArrayOf(params.height, params.gravity)
            }
            if (applies) {
                if (saved[0] == ViewGroup.LayoutParams.MATCH_PARENT) {
                    // A full-frame surface (the picture, the ASS bitmap, the P5
                    // renderer): the band IS its height, glued to the top.
                    params.height = band
                    params.gravity = Gravity.TOP
                    view.layoutParams = params
                    view.translationY = 0f
                } else {
                    // A wrap-content overlay (the styled cue line): its anchors
                    // stay, and it is lifted by the height the chrome took so it
                    // stays inside the picture rather than over the controls.
                    view.translationY = -(root.height - band).coerceAtLeast(0).toFloat()
                }
            } else {
                params.height = saved[0]
                params.gravity = saved[1]
                view.layoutParams = params
                view.translationY = 0f
            }
            if (view is ViewGroup) view.requestLayout()
        }

        val chrome = chromeLayer ?: return
        val params = chrome.layoutParams as? ViewGroup.MarginLayoutParams ?: return
        val saved = originals.getOrPut(chrome) { intArrayOf(params.topMargin) }
        params.topMargin = if (applies) band else saved[0]
        chrome.layoutParams = params
    }
}
