package com.kennyb1201.kbstream

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration

/**
 * Which form factor this process is showing its UI on.
 *
 * KBStream ships ONE artifact that self-updates by downloading its own APK, so
 * there are no product flavors to fork the updater into per-device builds. The
 * form factor is therefore decided at runtime, here, and every mobile branch in
 * the app reads it from this object.
 *
 * [isTv] is resolved once in [MainApplication.onCreate] and cached: the answer
 * cannot change while the process lives, and every activity asks for it during
 * its own create. A value that was never resolved answers `true`, deliberately.
 * This app is a television app first, and misreading a TV as a phone would hand
 * a 10-foot box a touch layout with no way back; misreading a phone as a TV only
 * leaves the established UI in place.
 *
 * Phase 0 of the mobile port uses this object only for orientation. Screen
 * dispatch (`if (FormFactor.isTv) TvContent() else MobileContent()`) arrives
 * with the mobile screens, and the TV composables themselves are never edited.
 * The television window policy is the manifest's own landscape lock, exactly as
 * it was in the tagged build; see [applyOrientation].
 */
object FormFactor {

    @Volatile
    private var cachedIsTv: Boolean? = null

    /**
     * True on a television. Safe to read from any thread and any activity,
     * including before [init] has run (see the class comment for why that
     * answers `true`).
     */
    val isTv: Boolean
        get() = cachedIsTv ?: true

    /**
     * Resolves and caches the form factor. Called once from
     * [MainApplication.onCreate], before any activity exists.
     */
    fun init(context: Context) {
        // Application.onCreate must never throw (it kills the process before
        // any activity exists), so a platform surprise here leaves the cache
        // unset - and [isTv] then answers `true`, which keeps the TV UI.
        runCatching {
            val app = context.applicationContext
            val modeType = (app.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager)
                ?.currentModeType
                ?: Configuration.UI_MODE_TYPE_UNDEFINED
            val packageManager = app.packageManager
            cachedIsTv = isTelevision(
                modeType = modeType,
                hasTouchscreen =
                    packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN)
            )
        }
    }

    /**
     * The pure decision behind [init], so the boundary can be tested without a
     * device.
     *
     * The system's own UI mode is the primary answer. The touchscreen check is
     * the fallback for the boxes that do not report TELEVISION: an Android TV
     * stick or an older set-top box can report NORMAL, and a phone always
     * reports the touchscreen feature, so "no touchscreen at all" is a TV in
     * everything but the reported mode. That direction of error is the safe
     * one - it preserves the TV UI on a device that has one, and no phone can
     * be mistaken for a TV by it.
     */
    internal fun isTelevision(
        modeType: Int,
        hasTouchscreen: Boolean
    ): Boolean =
        modeType == Configuration.UI_MODE_TYPE_TELEVISION || !hasTouchscreen

    /**
     * Clears the manifest's landscape lock on a phone, and does nothing at all
     * on a television.
     *
     * The four activities keep `android:screenOrientation="landscape"` in the
     * manifest, exactly as the tagged build had it: that is the TV window
     * policy, and the TV path must stay identical to the build that shipped.
     * A phone overrides the manifest attribute at runtime with
     * `SCREEN_ORIENTATION_UNSPECIFIED` (the system's normal sensor behavior).
     *
     * Must be called BEFORE `super.onCreate`, so the override is in place before
     * the window is created. Setting it afterwards let the already-created TV
     * window see a request it did not need and played a rotation animation on
     * every screen change - the reason this is not simply re-applied in onCreate
     * as before. On TV this returns before touching the activity: no
     * `requestedOrientation` write, no platform call.
     */
    fun applyOrientation(activity: Activity) {
        if (isTv) return
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    /**
     * Enters playback on a phone by asking for landscape, and does nothing at
     * all on a television.
     *
     * This is the other half of [applyOrientation]: browsing follows the device
     * (a phone lives in portrait), while a title plays sideways. The request is
     * `SENSOR_LANDSCAPE` rather than a hard landscape lock so the player's own
     * hold-to-rotate gesture still reaches portrait - a phone propped up or held
     * upright can be turned back, and the portrait stage in the player is still
     * reachable.
     *
     * A player calls it BEFORE `super.onCreate`, like [applyOrientation], so
     * the window is created in the orientation the viewer will watch in instead
     * of rotating a frame in. It is the PLAYER's request only: MainActivity's
     * own pre-playback splash is a screen of the browsing window and keeps
     * [applyOrientation]'s policy (portrait on a phone held upright), because a
     * landscape loading screen under a portrait app was the reported fault - the
     * splash came up sideways and then had to flip. On TV this returns before
     * touching the activity: the manifest's landscape lock is the only
     * orientation policy a television has.
     */
    fun applyPlaybackOrientation(activity: Activity) {
        if (isTv) return
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    /**
     * Releases the playback request on the way out of a player, so the browsing
     * activity is free to follow the device again.
     *
     * A phone returning from a film to a portrait-held Home must not inherit the
     * player's landscape request. On TV this is a no-op for the same reason as
     * every other method here: the manifest owns that lock.
     */
    fun releasePlaybackOrientation(activity: Activity) {
        if (isTv) return
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
}
