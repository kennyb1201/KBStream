package com.kennyb1201.kbstream.ui.player

import android.content.Context
import android.view.Surface
import java.util.IdentityHashMap
import `is`.xyz.mpv.MPV
import `is`.xyz.mpv.MPVNode

/**
 * The engine's MPV library for a TEST build: `io.github.abdallahmehiz:mpv-android-lib`
 * (Maven Central), the library Nuvio's GPU video output is built on.
 *
 * Chosen with `-Pmpv.lib=nuvio` (see the flag block in app/build.gradle.kts).
 * Nothing here ships by default; the default arm is `src/mpvrites/.../MpvBackend.kt`
 * around Riteshp2001/mpvlibAndroid. This is the controlled library swap for the
 * `vo=gpu` black-screen failure - same mpv core, same options, same engine code
 * above it, a different build of the library underneath.
 *
 * What differs from the default arm, and why this file cannot be a one-line
 * delegate like that one:
 *
 *  - the library exposes `MPV` as a CLASS, not `MPVLib` as an object. One
 *    instance is held here and every call goes through it - the same lifetime
 *    the singleton gave the engine (create on [create], destroy on [destroy],
 *    one per engine session), which is why nothing above this file changes;
 *  - its constant holders (`MPV.mpvEvent`, `MPV.mpvFormat`, `MPV.mpvLogLevel`)
 *    are named differently from the other library's (`MpvEvent`, ...). Nothing
 *    in the engine reads them: it reads [MpvEvent]/[MpvFormat]/[MpvLogLevel] in
 *    `MpvEngineApi.kt`, whose values are the mpv C ABI values both libraries
 *    carry, and the `format` this file passes to `observeProperty` is exactly
 *    that int;
 *  - its observers are `MPV.EventObserver`/`MPV.LogObserver` with the same seven
 *    and one method the other library has, so the bridges below are the same
 *    two bridges with the same shape.
 *
 * The member set below is deliberately IDENTICAL to the default arm's, name for
 * name and parameter for parameter - `MpvLibrarySwapContractTest` pins that, and
 * the engine compiling in both arms is what it protects.
 */
internal object MpvBackend {

    /**
     * The library instance, or null before [create] / after [destroy].
     *
     * Null rather than an error thrown at construction: the singleton the other
     * arm wraps is itself unusable before `create()`, and [engine] reproduces
     * that by failing loudly instead of silently no-op'ing, which is the
     * difference between a missing native library reporting itself and a player
     * that appears to initialize and then does nothing.
     */
    private var instance: MPV? = null

    private fun engine(): MPV = instance ?: error("MPV.create() has not been called")

    // --- Lifetime -----------------------------------------------------------

    fun create(context: Context) {
        instance = MPV().also { it.create(context) }
    }

    fun init() = engine().init()

    /**
     * Frees the instance and clears it, so a second [destroy] cannot reach a
     * freed native handle (the engine destroys once per session, but the
     * `initialize()` failure path also destroys after a failed `init()`, and a
     * double free is not something that can be caught here).
     */
    fun destroy() {
        val mpv = engine()
        instance = null
        mpv.destroy()
    }

    // --- The video surface --------------------------------------------------

    fun attachSurface(surface: Surface) = engine().attachSurface(surface)

    fun detachSurface() = engine().detachSurface()

    // --- Options and properties ---------------------------------------------

    fun command(vararg args: String) = engine().command(*args)

    /** Returns mpv's own status code; only ever read to log a rejected option. */
    fun setOptionString(name: String, value: String): Int = engine().setOptionString(name, value)

    fun setPropertyString(name: String, value: String) = engine().setPropertyString(name, value)

    fun setPropertyInt(name: String, value: Int) = engine().setPropertyInt(name, value)

    fun setPropertyDouble(name: String, value: Double) = engine().setPropertyDouble(name, value)

    fun setPropertyBoolean(name: String, value: Boolean) = engine().setPropertyBoolean(name, value)

    /** Null when mpv has nothing to say (an unknown property, or no value yet). */
    fun getPropertyString(name: String): String? = engine().getPropertyString(name)

    fun getPropertyInt(name: String): Int? = engine().getPropertyInt(name)

    fun getPropertyDouble(name: String): Double? = engine().getPropertyDouble(name)

    fun getPropertyBoolean(name: String): Boolean? = engine().getPropertyBoolean(name)

    /**
     * Asks mpv to deliver [name] on every change, in the format [format] names
     * ([MpvFormat] - the int the two libraries both declare). Registration
     * happens before `init()`, as the reference player does it.
     */
    fun observeProperty(name: String, format: Int) = engine().observeProperty(name, format)

    // --- Observers ----------------------------------------------------------

    /**
     * The bridge for [observer], per registered observer - identity-keyed for
     * the same reason as in the default arm: the bridge has to be handed back to
     * `removeObserver` as the object that was registered.
     */
    private val eventBridges = IdentityHashMap<MpvEventObserver, MPV.EventObserver>()

    private val logBridges = IdentityHashMap<MpvLogObserver, MPV.LogObserver>()

    fun addObserver(observer: MpvEventObserver) {
        val bridge = object : MPV.EventObserver {
            override fun event(eventId: Int, data: MPVNode) = observer.event(eventId)

            override fun eventProperty(property: String) = observer.eventProperty(property)

            override fun eventProperty(property: String, value: Long) =
                observer.eventProperty(property, value)

            override fun eventProperty(property: String, value: Double) =
                observer.eventProperty(property, value)

            override fun eventProperty(property: String, value: Boolean) =
                observer.eventProperty(property, value)

            override fun eventProperty(property: String, value: String) =
                observer.eventProperty(property, value)

            // The node overload is the one method the engine never had a use
            // for: it carried the same property-change payload as the typed
            // overloads above and the engine's own override was `= Unit`.
            override fun eventProperty(property: String, value: MPVNode) = Unit
        }
        eventBridges[observer] = bridge
        engine().addObserver(bridge)
    }

    fun removeObserver(observer: MpvEventObserver) {
        eventBridges.remove(observer)?.let { engine().removeObserver(it) }
    }

    fun addLogObserver(observer: MpvLogObserver) {
        val bridge = object : MPV.LogObserver {
            override fun logMessage(prefix: String, level: Int, text: String) =
                observer.logMessage(prefix, level, text)
        }
        logBridges[observer] = bridge
        engine().addLogObserver(bridge)
    }

    fun removeLogObserver(observer: MpvLogObserver) {
        logBridges.remove(observer)?.let { engine().removeLogObserver(it) }
    }
}
