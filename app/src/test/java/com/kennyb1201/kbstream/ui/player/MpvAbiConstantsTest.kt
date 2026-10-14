package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MPV library seam and the ABI values that cross the JNI boundary.
 *
 * The engine links exactly one MPV library: the Riteshp2001/mpvlibAndroid
 * (Mpv∞) `mpvlib-1.1.2.aar` fetched into app/libs/ (see the MPV library note in
 * app/build.gradle.kts). An alternative library
 * (`io.github.abdallahmehiz:mpv-android-lib`) was trialled for the `vo=gpu`
 * black-screen failure and removed - it produced the SAME black screen, so the
 * library was not the variable. This file used to pin the swap between the two;
 * what remains is the part that outlives the swap:
 *
 *  - the engine names no library, so `MpvPlayerView` compiles against the facade
 *    in `MpvEngineApi.kt` + `MpvBackend` alone;
 *  - `MpvBackend` lives off the main source path, in the one directory the build
 *    adds, and is the only file that imports `is.xyz.mpv`;
 *  - the constants in `MpvEngineApi.kt` are mpv's own ABI values - they cross the
 *    JNI boundary, so a renumbered `MPV_EVENT_END_FILE` is a stream that never
 *    reports why it failed;
 *  - the linked library's classes are kept by name, because the library
 *    constructs them from JNI by name and a stripped class is a
 *    NoClassDefFoundError on the first event.
 */
class MpvAbiConstantsTest {

    private val projectRoot: File by lazy { findProjectRoot() }

    private fun findProjectRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}settings.gradle.kts")
                if (candidate.isFile) return File(dir, prefix.trimEnd('/'))
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "project root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private fun source(relative: String): String {
        val file = File(projectRoot, relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private val engine: String
        get() = source("app/src/main/java/com/kennyb1201/kbstream/ui/player/MpvPlayerView.kt")

    private val api: String
        get() = source("app/src/main/java/com/kennyb1201/kbstream/ui/player/MpvEngineApi.kt")

    private val backend: String
        get() = source("app/src/mpvrites/java/com/kennyb1201/kbstream/ui/player/MpvBackend.kt")

    private val buildScript: String get() = source("app/build.gradle.kts")

    // ── the engine is library-agnostic ───────────────────────────────────────

    @Test
    fun `the engine names no mpv library`() {
        assertFalse(
            "MpvPlayerView must not import is.xyz.mpv: the library stays behind the facade",
            engine.contains("is.xyz.mpv")
        )
        assertFalse("the library's singleton must not be named here", engine.contains("MPVLib"))
        assertFalse(
            "MPVNode belongs behind the seam, not in the engine",
            engine.contains("MPVNode")
        )
        assertTrue("the engine drives the facade", engine.contains("MpvBackend."))
        assertTrue(
            "and the facade's interfaces are the engine's own",
            engine.contains("MpvEventObserver, MpvLogObserver {")
        )
        // The seam itself: what the engine calls must be declared in main, and
        // the observer payload must not carry a library node.
        assertTrue(api.contains("internal interface MpvEventObserver"))
        assertTrue(api.contains("internal interface MpvLogObserver"))
        assertTrue(api.contains("fun event(eventId: Int)"))
        assertFalse(api.contains("fun event(eventId: Int, data:"))
    }

    // ── the facade lives with the library it wraps ───────────────────────────

    @Test
    fun `the facade lives on its own source path and names the library`() {
        // On the main source path there is no MpvBackend at all: it sits in the
        // directory the build adds, so the library it wraps stays behind the seam.
        val onMainPath = File(
            projectRoot,
            "app/src/main/java/com/kennyb1201/kbstream/ui/player/MpvBackend.kt"
        )
        assertFalse("MpvBackend must not live in src/main: $onMainPath", onMainPath.isFile)
        assertTrue(backend.contains("import `is`.xyz.mpv.MPVLib"))
        assertTrue(backend.contains("internal object MpvBackend {"))
        // The build adds that directory, and only that one.
        assertTrue(buildScript.contains("val mpvEngineSourceDir = \"src/mpvrites\""))
        assertTrue(
            buildScript.contains("kotlin { srcDir(\"\$projectDir/\$mpvEngineSourceDir\") }")
        )
        assertTrue(buildScript.contains("val mpvAar = file(\"libs/mpvlib-1.1.2.aar\")"))
        assertTrue(buildScript.contains("implementation(files(mpvAar))"))
        assertTrue("a missing AAR is an error, not a silent fallback", buildScript.contains("throw GradleException("))
        // ...and no build flag selects a second engine library any more.
        assertFalse(
            "the removed library swap must not survive in the build script",
            buildScript.contains("mpv.lib") || buildScript.contains("MPV_LIB")
        )
    }

    // ── the constants cross the JNI boundary ────────────────────────────────

    @Test
    fun `the engine's mpv constants are the library's ABI values`() {
        // Read out of the linked library's compiled classes (javap -v on
        // MPVLib$MpvEvent and the format and log-level holders): they are mpv's
        // own mpv_event_id / mpv_format / mpv_log_level values, and they are what
        // the native side sends back.
        val expected = mapOf(
            "MPV_EVENT_NONE" to 0,
            "MPV_EVENT_SHUTDOWN" to 1,
            "MPV_EVENT_LOG_MESSAGE" to 2,
            "MPV_EVENT_GET_PROPERTY_REPLY" to 3,
            "MPV_EVENT_SET_PROPERTY_REPLY" to 4,
            "MPV_EVENT_COMMAND_REPLY" to 5,
            "MPV_EVENT_START_FILE" to 6,
            "MPV_EVENT_END_FILE" to 7,
            "MPV_EVENT_FILE_LOADED" to 8,
            "MPV_EVENT_IDLE" to 11,
            "MPV_EVENT_TICK" to 14,
            "MPV_EVENT_CLIENT_MESSAGE" to 16,
            "MPV_EVENT_VIDEO_RECONFIG" to 17,
            "MPV_EVENT_AUDIO_RECONFIG" to 18,
            "MPV_EVENT_SEEK" to 20,
            "MPV_EVENT_PLAYBACK_RESTART" to 21,
            "MPV_EVENT_PROPERTY_CHANGE" to 22,
            "MPV_EVENT_QUEUE_OVERFLOW" to 24,
            "MPV_EVENT_HOOK" to 25,
            "MPV_FORMAT_NONE" to 0,
            "MPV_FORMAT_STRING" to 1,
            "MPV_FORMAT_OSD_STRING" to 2,
            "MPV_FORMAT_FLAG" to 3,
            "MPV_FORMAT_INT64" to 4,
            "MPV_FORMAT_DOUBLE" to 5,
            "MPV_FORMAT_NODE" to 6,
            "MPV_FORMAT_NODE_ARRAY" to 7,
            "MPV_FORMAT_NODE_MAP" to 8,
            "MPV_FORMAT_BYTE_ARRAY" to 9,
            "MPV_LOG_LEVEL_NONE" to 0,
            "MPV_LOG_LEVEL_FATAL" to 10,
            "MPV_LOG_LEVEL_ERROR" to 20,
            "MPV_LOG_LEVEL_WARN" to 30,
            "MPV_LOG_LEVEL_INFO" to 40,
            "MPV_LOG_LEVEL_V" to 50,
            "MPV_LOG_LEVEL_DEBUG" to 60,
            "MPV_LOG_LEVEL_TRACE" to 70,
        )
        val declared = Regex("const val (MPV_\\w+) = (-?\\d+)", RegexOption.MULTILINE)
            .findAll(api)
            .associate { it.groupValues[1] to it.groupValues[2].toInt() }
        assertEquals(
            "the engine's constants are the library's - a renumbered event id is " +
                "an event read as a different event",
            expected,
            declared
        )
    }

    // ── the library's classes survive shrinking ─────────────────────────────

    @Test
    fun `the linked library's classes are kept by name`() {
        // The library constructs its classes from JNI by name, so a renamed or
        // stripped class is a NoClassDefFoundError on the first event.
        assertTrue(
            source("app/proguard-rules.pro").contains("-keep class is.xyz.mpv.** { *; }")
        )
    }
}
