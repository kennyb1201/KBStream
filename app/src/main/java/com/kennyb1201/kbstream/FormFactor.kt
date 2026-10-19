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
     * Pins the window orientation for this form factor.
     *
     * The four activities stopped declaring `android:screenOrientation` in the
     * manifest so a phone is not forced into landscape. TV keeps the exact
     * landscape lock it had - the same `SCREEN_ORIENTATION_LANDSCAPE` the
     * manifest used to give it - and mobile is left unspecified, which is the
     * system's normal sensor behavior. Called from each activity's `onCreate`
     * right after `super`, the only behavioral edit any existing activity got
     * in this phase.
     */
    fun applyOrientation(activity: Activity) {
        activity.requestedOrientation =
            if (isTv) {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
    }
}
