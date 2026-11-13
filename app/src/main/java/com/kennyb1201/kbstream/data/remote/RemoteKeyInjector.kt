package com.kennyb1201.kbstream.data.remote

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import java.lang.ref.WeakReference

/**
 * Puts a phone's key press into the TV exactly as the physical remote's would be
 * (spec 2026-11-13, F2).
 *
 * ## Why it dispatches instead of re-implementing
 *
 * The app already answers KeyEvents everywhere that matters - Compose focus on
 * Home, Detail and the guide, and the players' own `dispatchKeyEvent` (media
 * rewind / fast-forward are ±10s there, play-pause toggles). So the remote
 * delivers the same `KeyEvent` to the activity that is in front, and every
 * screen keeps behaving as it does for a real remote. There is no second
 * navigation path to drift: a screen that works with a remote works with the
 * phone, and a screen that ignores a key ignores it from both.
 *
 * ## The three keys that are not dispatched
 *
 * - **HOME** is a system key: an app that dispatches KEYCODE_HOME to itself does
 *   nothing, because the system owns it. Starting the launcher's HOME intent is
 *   what the system does with the press, so the TV ends up on its launcher
 *   exactly as it would have.
 * - **VOLUME_UP/DOWN/MUTE** are handled by the platform's audio service, never
 *   by the focused app; a dispatched volume KeyEvent is dropped (the app returns
 *   false from `dispatchKeyEvent`). The remote therefore asks AudioManager to
 *   move the same stream the hardware keys move, with the same on-screen UI.
 * - **SEEK_BACK/FORWARD** are sent as the media-rewind/forward KeyEvents, which
 *   is the key a CEC remote sends for them and which the players already step by
 *   ten seconds.
 *
 * Everything runs on the main thread, because `dispatchKeyEvent` is a UI-thread
 * call. With no activity in front (the app is closed, or a player activity is
 * mid-teardown) the press is dropped: the spec's "ignored on the TV side, never
 * an error on the phone side".
 */
internal object RemoteKeyInjector {

    private const val TAG = "RemoteKeys"

    private val main = Handler(Looper.getMainLooper())

    private var current: WeakReference<Activity>? = null

    /** Registers the activity tracker once, from [MainApplication]. */
    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityResumed(activity: Activity) {
                    current = WeakReference(activity)
                }

                override fun onActivityPaused(activity: Activity) {
                    if (current?.get() === activity) current = null
                }

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
                override fun onActivityStarted(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) {
                    if (current?.get() === activity) current = null
                }
            }
        )
    }

    /** True when there is an activity a press could reach. */
    fun hasTarget(): Boolean = current?.get() != null

    /**
     * Presses [code] (one of [RemoteRules.KEYS]).
     *
     * Returns false only when the key is unknown to this injector, never because
     * there was nowhere to deliver it.
     */
    fun press(context: Context, code: String): Boolean {
        val key = RemoteRules.normaliseKey(code) ?: return false
        val app = context.applicationContext
        main.post { deliver(app, key) }
        return true
    }

    private fun deliver(context: Context, code: String) {
        when (code) {
            "VOLUME_UP" -> volume(context) { audio, stream ->
                audio.adjustStreamVolume(
                    stream,
                    AudioManager.ADJUST_RAISE,
                    AudioManager.FLAG_SHOW_UI
                )
            }

            "VOLUME_DOWN" -> volume(context) { audio, stream ->
                audio.adjustStreamVolume(
                    stream,
                    AudioManager.ADJUST_LOWER,
                    AudioManager.FLAG_SHOW_UI
                )
            }

            "MUTE" -> volume(context) { audio, stream ->
                // The physical mute key toggles, so this does too - and the UI
                // flag is what makes the TV show the muted state.
                audio.adjustStreamVolume(
                    stream,
                    AudioManager.ADJUST_TOGGLE_MUTE,
                    AudioManager.FLAG_SHOW_UI
                )
            }

            "HOME" -> goHome(context)

            else -> {
                val codeInt = RemoteKeyCodes.keyCodeFor(code) ?: return
                val activity = current?.get() ?: return
                val now = android.os.SystemClock.uptimeMillis()
                val down = KeyEvent(
                    now,
                    now,
                    KeyEvent.ACTION_DOWN,
                    codeInt,
                    0,
                    0,
                    -1,
                    0,
                    KeyEvent.FLAG_FROM_SYSTEM
                )
                val up = KeyEvent(
                    now,
                    now,
                    KeyEvent.ACTION_UP,
                    codeInt,
                    0,
                    0,
                    -1,
                    0,
                    KeyEvent.FLAG_FROM_SYSTEM
                )
                runCatching {
                    activity.dispatchKeyEvent(down)
                    activity.dispatchKeyEvent(up)
                }.onFailure { error ->
                    Log.w(TAG, "key $code was not delivered: ${error.javaClass.simpleName}")
                }
            }
        }
    }

    private inline fun volume(
        context: Context,
        crossinline change: (AudioManager, Int) -> Unit
    ) {
        runCatching {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                ?: return
            change(audio, AudioManager.STREAM_MUSIC)
        }
    }

    private fun goHome(context: Context) {
        runCatching {
            val intent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }.onFailure { error ->
            Log.w(TAG, "home press was not delivered: ${error.javaClass.simpleName}")
        }
    }
}

/**
 * The KeyEvent a named remote key is, where one exists.
 *
 * The names are the wire's ([RemoteRules.KEYS]); this is the only place that
 * knows Android's codes, so a second transport (a widget, a test button) cannot
 * invent a different mapping.
 */
internal object RemoteKeyCodes {

    fun keyCodeFor(name: String): Int? = when (name) {
        "UP" -> KeyEvent.KEYCODE_DPAD_UP
        "DOWN" -> KeyEvent.KEYCODE_DPAD_DOWN
        "LEFT" -> KeyEvent.KEYCODE_DPAD_LEFT
        "RIGHT" -> KeyEvent.KEYCODE_DPAD_RIGHT
        "OK" -> KeyEvent.KEYCODE_DPAD_CENTER
        "BACK" -> KeyEvent.KEYCODE_BACK
        "PLAY_PAUSE" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        "SEEK_BACK" -> KeyEvent.KEYCODE_MEDIA_REWIND
        "SEEK_FORWARD" -> KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
        else -> null
    }
}
