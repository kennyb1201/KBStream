package com.kennyb1201.kbstream.ui.player

import android.content.Context
import android.view.Surface
import java.util.IdentityHashMap
import `is`.xyz.mpv.MPVLib
import `is`.xyz.mpv.MPVNode

/**
 * The engine's bridge to its MPV library: Riteshp2001/mpvlibAndroid (Mpv∞), the
 * `mpvlib-1.1.2.aar` release AAR fetched into app/libs/.
 *
 * This is almost all delegation, because the `MPVLib` singleton it wraps already
 * has the call shapes [MpvPlayerView] wants - `MPVLib.create(context)`,
 * `MPVLib.setOptionString(name, value)`. What this file adds is the two observer
 * bridges at the bottom: the library's `EventObserver` has seven methods and
 * delivers its payload as `MPVNode`, while the engine holds [MpvEventObserver]
 * and reads the event id alone (see the note on that interface). The bridge is
 * where those meet, and it is why no `MPVNode` appears in the engine's own
 * source.
 *
 * A second arm (`src/mpvnuvio/.../MpvBackend.kt`) once wrapped
 * `io.github.abdallahmehiz:mpv-android-lib` behind exactly this member set, for a
 * library swap trialled against the `vo=gpu` black-screen failure. It was
 * removed: it produced the SAME black screen, so the library was not the
 * variable. This is the only implementation now, and it lives outside src/main
 * (see the MPV library note in app/build.gradle.kts). `MpvAbiConstantsTest`
 * pins the seam.
 */
internal object MpvBackend {

    // --- Lifetime -----------------------------------------------------------

    fun create(context: Context) = MPVLib.create(context)

    fun init() = MPVLib.init()

    fun destroy() = MPVLib.destroy()

    // --- The video surface --------------------------------------------------

    fun attachSurface(surface: Surface) = MPVLib.attachSurface(surface)

    fun detachSurface() = MPVLib.detachSurface()

    // --- Options and properties ---------------------------------------------

    fun command(vararg args: String) = MPVLib.command(*args)

    /** Returns mpv's own status code; only ever read to log a rejected option. */
    fun setOptionString(name: String, value: String): Int = MPVLib.setOptionString(name, value)

    fun setPropertyString(name: String, value: String) = MPVLib.setPropertyString(name, value)

    fun setPropertyInt(name: String, value: Int) = MPVLib.setPropertyInt(name, value)

    fun setPropertyDouble(name: String, value: Double) = MPVLib.setPropertyDouble(name, value)

    fun setPropertyBoolean(name: String, value: Boolean) = MPVLib.setPropertyBoolean(name, value)

    /** Null when mpv has nothing to say (an unknown property, or no value yet). */
    fun getPropertyString(name: String): String? = MPVLib.getPropertyString(name)

    fun getPropertyInt(name: String): Int? = MPVLib.getPropertyInt(name)

    fun getPropertyDouble(name: String): Double? = MPVLib.getPropertyDouble(name)

    fun getPropertyBoolean(name: String): Boolean? = MPVLib.getPropertyBoolean(name)

    /**
     * Asks mpv to deliver [name] on every change, in the format [format] names
     * ([MpvFormat]). Registration happens before `init()`, as the reference
     * player does it.
     */
    fun observeProperty(name: String, format: Int) = MPVLib.observeProperty(name, format)

    // --- Observers ----------------------------------------------------------

    /**
     * The bridge for [observer], per registered observer.
     *
     * Identity-keyed on purpose: a bridge must be handed back to
     * `removeObserver` as the SAME object that was registered, and the engine's
     * observer is a single long-lived View whose identity is the only thing this
     * has (an `equals`-keyed map would be a different map on a future observer
     * that overrode it).
     */
    private val eventBridges = IdentityHashMap<MpvEventObserver, MPVLib.EventObserver>()

    private val logBridges = IdentityHashMap<MpvLogObserver, MPVLib.LogObserver>()

    fun addObserver(observer: MpvEventObserver) {
        val bridge = object : MPVLib.EventObserver {
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
        MPVLib.addObserver(bridge)
    }

    fun removeObserver(observer: MpvEventObserver) {
        eventBridges.remove(observer)?.let { MPVLib.removeObserver(it) }
    }

    fun addLogObserver(observer: MpvLogObserver) {
        val bridge = object : MPVLib.LogObserver {
            override fun logMessage(prefix: String, level: Int, text: String) =
                observer.logMessage(prefix, level, text)
        }
        logBridges[observer] = bridge
        MPVLib.addLogObserver(bridge)
    }

    fun removeLogObserver(observer: MpvLogObserver) {
        logBridges.remove(observer)?.let { MPVLib.removeLogObserver(it) }
    }
}
