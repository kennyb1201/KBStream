package com.kennyb1201.kbstream.ui.player

import android.graphics.Bitmap
import android.util.Log

/**
 * Raw JNI surface of `libassjni.so` (built by `scripts/build_libass.sh`).
 *
 * The declaration names here are load-bearing: the C side is named
 * `Java_com_kennyb1201_kbstream_ui_player_AssNative_native*`, so the package,
 * the object name and each method name must not change without changing
 * `scripts/libass-jni/libassjni.cpp` in the same commit. The members are
 * deliberately left at Kotlin's default visibility - an `internal` *function*
 * is name-mangled in the bytecode, which would break that lookup, while an
 * `internal` object keeps its members out of the rest of the app anyway.
 *
 * Nothing here may be called unless [available] is true: the JVM resolves an
 * `external fun` lazily, so an absent library raises UnsatisfiedLinkError at
 * the call site rather than at class load.
 */
internal object AssNative {

    private const val TAG = "AssNative"

    /** Library name passed to [System.loadLibrary]. */
    const val LIBRARY = "assjni"

    /**
     * Whether this build actually carries the renderer. False on any build
     * made without running `scripts/build_libass.sh` (including every local
     * build and any CI run where the native step failed), which is the
     * signal the player uses to keep ASS sidecars on the flattened-cue path
     * instead of handing them to a library that is not there.
     */
    val available: Boolean = runCatching { System.loadLibrary(LIBRARY) }
        .onFailure { Log.i(TAG, "libassjni.so is not in this build: ASS rendering unavailable", it) }
        .isSuccess

    external fun nativeCreate(configPath: String?, cacheDir: String?): Long

    external fun nativeDestroy(handle: Long)

    external fun nativeAddFont(handle: Long, name: String, data: ByteArray)

    external fun nativeSetViewport(handle: Long, width: Int, height: Int)

    external fun nativeLoadTrack(handle: Long, content: String): Boolean

    external fun nativeRenderFrame(handle: Long, timeMs: Long, bitmap: Bitmap): Boolean
}
