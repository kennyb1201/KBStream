package com.kennyb1201.kbstream.ui.player

/**
 * The MPV engine's own account of mpv: the constants, the observer interfaces and
 * the call shapes [MpvPlayerView] is written against, with no library in them.
 *
 * Why this file exists. The engine can be built against either of two mpv
 * libraries (see the `mpv.lib` flag in app/build.gradle.kts), and they are not
 * interchangeable at the source level even though both publish the
 * `is.xyz.mpv` package:
 *
 *  - the default, Riteshp2001/mpvlibAndroid (Mpv∞), exposes `MPVLib` - a Kotlin
 *    `object`, so every call reads `MPVLib.setOptionString(...)`, and its
 *    constant holders are nested types named `MpvEvent`/`MpvFormat`/
 *    `MpvLogLevel`;
 *  - io.github.abdallahmehiz:mpv-android-lib, the library Nuvio's GPU video
 *    output is built on, exposes `MPV` - a class, so the same calls read
 *    `mpv.setOptionString(...)` on an instance, and its holders are named
 *    `mpvEvent`/`mpvFormat`/`mpvLogLevel`.
 *
 * Neither name may appear in the engine, so the engine names this file and
 * [MpvBackend] instead. Each arm of the build flag supplies its own `MpvBackend`
 * from a source directory only that arm adds (`src/mpvrites`, `src/mpvnuvio`),
 * and that seam is the whole mechanism: no reflection, no second player, and no
 * second engine.
 *
 * The constants below are mpv's own C ABI values - `mpv_event_id`, `mpv_format`
 * and `mpv_log_level` - taken from BOTH libraries' compiled classes rather than
 * assumed from a header: they are identical in the two, and they are what
 * crosses the JNI boundary, so a value that drifted here would silently misread
 * every event (an `END_FILE` read as `FILE_LOADED` is a stream that never opens
 * and never reports why). `MpvLibrarySwapContractTest` pins that they still
 * match the library the build linked.
 */

/**
 * `mpv_event_id`. The engine reads three of these in its `when`
 * (`FILE_LOADED`, `END_FILE`, `VIDEO_RECONFIG`); the rest are here because this
 * is the ABI, and a constant holder that mirrored only what happens to be read
 * today would be the wrong thing to consult when the next one is needed.
 */
internal object MpvEvent {
    const val MPV_EVENT_NONE = 0
    const val MPV_EVENT_SHUTDOWN = 1
    const val MPV_EVENT_LOG_MESSAGE = 2
    const val MPV_EVENT_GET_PROPERTY_REPLY = 3
    const val MPV_EVENT_SET_PROPERTY_REPLY = 4
    const val MPV_EVENT_COMMAND_REPLY = 5
    const val MPV_EVENT_START_FILE = 6
    const val MPV_EVENT_END_FILE = 7
    const val MPV_EVENT_FILE_LOADED = 8
    const val MPV_EVENT_IDLE = 11
    const val MPV_EVENT_TICK = 14
    const val MPV_EVENT_CLIENT_MESSAGE = 16
    const val MPV_EVENT_VIDEO_RECONFIG = 17
    const val MPV_EVENT_AUDIO_RECONFIG = 18
    const val MPV_EVENT_SEEK = 20
    const val MPV_EVENT_PLAYBACK_RESTART = 21
    const val MPV_EVENT_PROPERTY_CHANGE = 22
    const val MPV_EVENT_QUEUE_OVERFLOW = 24
    const val MPV_EVENT_HOOK = 25
}

/**
 * `mpv_format` - what `observeProperty` asks mpv to deliver, and therefore which
 * `eventProperty` overload the library calls back into.
 */
internal object MpvFormat {
    const val MPV_FORMAT_NONE = 0
    const val MPV_FORMAT_STRING = 1
    const val MPV_FORMAT_OSD_STRING = 2
    const val MPV_FORMAT_FLAG = 3
    const val MPV_FORMAT_INT64 = 4
    const val MPV_FORMAT_DOUBLE = 5
    const val MPV_FORMAT_NODE = 6
    const val MPV_FORMAT_NODE_ARRAY = 7
    const val MPV_FORMAT_NODE_MAP = 8
    const val MPV_FORMAT_BYTE_ARRAY = 9
}

/** `mpv_log_level`. The engine keeps everything at WARN or above. */
internal object MpvLogLevel {
    const val MPV_LOG_LEVEL_NONE = 0
    const val MPV_LOG_LEVEL_FATAL = 10
    const val MPV_LOG_LEVEL_ERROR = 20
    const val MPV_LOG_LEVEL_WARN = 30
    const val MPV_LOG_LEVEL_INFO = 40
    const val MPV_LOG_LEVEL_V = 50
    const val MPV_LOG_LEVEL_DEBUG = 60
    const val MPV_LOG_LEVEL_TRACE = 70
}

/**
 * mpv's event callback, without a library's `MPVNode` in it.
 *
 * Both libraries declare seven methods on their observer interface - one
 * `event(id, node)` plus an `eventProperty` overload per property type - and the
 * two libraries' `MPVNode` classes, while they share a fully-qualified name, are
 * different classes. The engine overrode two of the seven and ignored the node
 * in both: what it reads is the event id, and each property through the typed
 * overloads below. So the node is not part of this interface, which is what
 * keeps it out of the engine's source.
 */
internal interface MpvEventObserver {
    fun event(eventId: Int)
    fun eventProperty(property: String)
    fun eventProperty(property: String, value: Long)
    fun eventProperty(property: String, value: Double)
    fun eventProperty(property: String, value: Boolean)
    fun eventProperty(property: String, value: String)
}

/** mpv's log callback: `prefix`, `mpv_log_level`, `text`. Identical in both libraries. */
internal interface MpvLogObserver {
    fun logMessage(prefix: String, level: Int, text: String)
}
