package com.kennyb1201.kbstream.ui.player

import android.app.Activity
import android.os.Build
import android.util.Log
import android.util.Rational
import com.kennyb1201.kbstream.ui.settings.AppPreferences

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
 *
 * What is deliberately *not* consulted is `FEATURE_PICTURE_IN_PICTURE`. A box
 * that lacks it returns false from the call below and nothing happens, so the
 * flag would only ever be able to take PiP away - from the cheap Android TV
 * boxes that do not declare it while still honoring a PiP window. Whether the
 * platform really shrinks the window is answered by `enterPictureInPictureMode`
 * itself, not by a manifest flag.
 */
internal fun enterPipMode(activity: Activity): Boolean {
    if (activity.isFinishing || activity.isDestroyed) return false
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
    if (Build.MANUFACTURER.equals("Amazon", ignoreCase = true)) return false
    if (!AppPreferences.getEnablePip(activity)) return false
    return runCatching {
        activity.enterPictureInPictureMode(
            android.app.PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
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
