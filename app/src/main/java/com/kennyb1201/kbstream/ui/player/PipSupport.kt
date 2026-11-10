package com.kennyb1201.kbstream.ui.player

import android.app.Activity
import android.os.Build
import android.util.Log
import android.util.Rational
import com.kennyb1201.kbstream.data.settings.AppPreferences

/**
 * Picture-in-picture, the way both player engines enter it.
 *
 * Shared because the guards are the feature — every one of them is a bug that
 * was reported and fixed once already:
 *
 *  - **Never while the activity is finishing or destroyed.** This is called
 *    from `onPause`, which also runs on the way out of a Back press or a
 *    teardown; on a TV, entering PiP there dumps the viewer to the launcher
 *    instead of back to the screen they came from.
 *  - **Never on Amazon devices.** Fire TV OS does not display PiP windows for
 *    third-party apps at all, so the entry is not a smaller window — it is the
 *    same launcher dump, on the platform most of this app's users run.
 *  - **Never against the setting.** Settings → Playback owns the switch; a
 *    viewer who turned PiP off wants a Home press to leave playback alone.
 *  - **Never during an engine handoff.** Starting the successor activity runs
 *    this activity's `onPause` while the old engine is still tearing down, so a
 *    Home-shaped pause at that instant put a PiP window around a player that was
 *    already stopped. Worse, PiP means `onStop` never runs, and the handoff's
 *    own teardown lives there (the MPV surface is released in `onStop` because
 *    libmpv holds the one-deep decoder the successor engine has to allocate),
 *    so the hand-back was skipped and the dead window floated over the new
 *    session. Callers pass their own latch: this helper has no way to read them.
 *    [handingOver] is that latch.
 *
 * What is deliberately *not* consulted is `FEATURE_PICTURE_IN_PICTURE`. A box
 * that lacks it returns false from the call below and nothing happens, so the
 * flag would only ever be able to take PiP away - from the cheap Android TV
 * boxes that do not declare it while still honoring a PiP window. Whether the
 * platform really shrinks the window is answered by `enterPictureInPictureMode`
 * itself, not by a manifest flag.
 */
internal fun enterPipMode(
    activity: Activity,
    handingOver: Boolean = false,
    /**
     * The playing video's own size in pixels, or zeros for an engine that does
     * not report one (MPV hands nothing over here). See [pipAspectRatio].
     */
    videoWidth: Int = 0,
    videoHeight: Int = 0
): Boolean {
    if (activity.isFinishing || activity.isDestroyed) return false
    // See the class kdoc: an in-flight handoff gives the successor engine the
    // screen, and a PiP window here defeats the hand-back its `onStop` owes.
    if (handingOver) return false
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
    if (Build.MANUFACTURER.equals("Amazon", ignoreCase = true)) return false
    if (!AppPreferences.getEnablePip(activity)) return false
    return runCatching {
        activity.enterPictureInPictureMode(
            android.app.PictureInPictureParams.Builder()
                .setAspectRatio(pipAspectRatio(videoWidth, videoHeight))
                .build()
        )
    }.onFailure { error ->
        // A convenience surface, never a reason for playback to fail: the
        // picture stays on screen and the press is simply a no-op.
        Log.w(PIP_TAG, "PiP failed", error)
    }.isSuccess
}

/** The log tag every engine's PiP failures land under. */
internal const val PIP_TAG = "PLAYER_PIP"

/** Android rejects a PiP window wider than 2.39:1 or taller than 1:2.39. */
private const val PIP_MAX_RATIO = 2.39
private const val PIP_MIN_RATIO = 1.0 / PIP_MAX_RATIO

/**
 * The PiP window's shape: the video's own (m7).
 *
 * It was hardcoded 16:9, so a 4:3 title got a wide window with pillarboxing
 * inside it and a 21:9 title got a short one - the window never matched the
 * picture it was showing. The platform's own limits are applied here rather than
 * left to `setAspectRatio`, which THROWS outside them: an ultra-wide letterboxed
 * film is clamped to 2.39:1, which is the widest window Android will draw
 * anyway. Zeros - an engine with no reported size, or audio-only - keep 16:9,
 * exactly the behavior every call had before.
 */
internal fun pipAspectRatio(videoWidth: Int, videoHeight: Int): Rational {
    if (videoWidth <= 0 || videoHeight <= 0) return Rational(16, 9)
    val ratio = videoWidth.toDouble() / videoHeight.toDouble()
    return when {
        ratio <= PIP_MIN_RATIO -> Rational(100, 239)
        ratio >= PIP_MAX_RATIO -> Rational(239, 100)
        else -> Rational(videoWidth, videoHeight)
    }
}
