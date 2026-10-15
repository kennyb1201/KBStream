package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MPV library seam and the ABI values that cross the JNI boundary.
 *
 * The engine links exactly one MPV library: the fortunasxp/mpv-android-libdovi
 * `mpv-android-lib-libdovi.aar` fetched into app/libs/ (see the MPV library note
 * in app/build.gradle.kts). It replaced Riteshp2001/mpvlibAndroid for one
 * reason - the old build's libplacebo had no `-Ddovi`, so DV could only ever
 * play as its HDR10 base layer. It reuses the
 * `io.github.abdallahmehiz:mpv-android-lib` shell and therefore exposes
 * `is.xyz.mpv.MPV` (a class) where the old one exposed `MPVLib` (an object).
 * This file used to pin the swap between two libraries; what remains is the part
 * that outlives every swap:
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

    private val fetchScript: String get() = source("scripts/fetch_mpv_aar.sh")

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
        // The new library reuses the mpv-android-lib shell, so the class is
        // `MPV` (a class, one instance held) and not the old `MPVLib` object.
        // A pin on the wrong one is a build that does not compile, which is the
        // cheapest possible way to notice a half-finished swap.
        assertTrue(backend.contains("import `is`.xyz.mpv.MPV"))
        // Bound to, not merely mentioned: this file's own docs name `MPVLib` to
        // explain what the swap changed, and that is the record a reader needs.
        // What must not survive is a reference the compiler resolves - the
        // import, or a call through the old singleton - because those are what
        // would fail to compile against the AAR this build now links.
        assertFalse(
            "the superseded library's singleton may not be imported",
            backend.contains("import `is`.xyz.mpv.MPVLib")
        )
        assertFalse(
            "nor called: the new AAR exposes no such object",
            backend.contains("MPVLib.") || backend.contains("MPVLib()")
        )
        assertTrue(backend.contains("internal object MpvBackend {"))
        // The build adds that directory, and only that one.
        assertTrue(buildScript.contains("val mpvEngineSourceDir = \"src/mpvrites\""))
        assertTrue(
            buildScript.contains("kotlin { srcDir(\"\$projectDir/\$mpvEngineSourceDir\") }")
        )
        assertTrue(
            "the build must name the AAR the fetch script actually downloads",
            buildScript.contains("val mpvAar = file(\"libs/mpv-android-lib-libdovi.aar\")")
        )
        assertTrue(buildScript.contains("implementation(files(mpvAar))"))
        // Referenced, not mentioned: the build script's own note names the AAR
        // this replaced, and that is the record a reader needs. What must not
        // survive is a `file(...)` pointing at it - that is a dependency, and it
        // would either fail the guard or silently link the wrong binary.
        assertFalse(
            "the superseded AAR may not be referenced as a file dependency",
            buildScript.contains("file(\"libs/mpvlib-1.1.2.aar\")")
        )
        assertTrue("a missing AAR is an error, not a silent fallback", buildScript.contains("throw GradleException("))
        // ...and no build flag selects a second engine library any more.
        assertFalse(
            "the removed library swap must not survive in the build script",
            buildScript.contains("mpv.lib") || buildScript.contains("MPV_LIB")
        )
    }

    // ── the fetch script is the only way in, and it pins what it fetches ────

    @Test
    fun `the fetch script downloads the pinned libdovi AAR from its own release`() {
        assertTrue(
            "the asset the script fetches is the one the build and the log name",
            fetchScript.contains("ASSET=\"mpv-android-lib-libdovi.aar\"")
        )
        assertTrue(
            "from the fortunasxp/mpv-android-libdovi release, not the superseded one",
            fetchScript.contains("FortunasXP/mpv-android-libdovi") &&
                !fetchScript.contains("Riteshp2001/mpvlibAndroid/releases")
        )
        // A 64-hex pin: the digest IS the verification here, because this
        // release publishes no .sha256 beside the asset. Without it the script
        // would take whatever the release currently serves.
        val pin = Regex("PINNED_SHA256=\"([0-9a-f]{64})\"")
            .find(fetchScript)?.groupValues?.get(1)
        assertTrue(
            "the AAR must be pinned by a full sha256 - a re-uploaded asset may not " +
                "silently change the binary this build ships",
            pin != null
        )
        assertTrue(
            "and the pin must not still be the superseded library's digest",
            pin != "2bee300d6ab79a0b7317049bc62d3271b3c9e1b5d065f54907726f0da7b976da"
        )
        assertTrue(
            "a mismatch is a hard failure, not a warning",
            fetchScript.contains("differs from the one this build pins") &&
                fetchScript.contains("exit 1")
        )
    }

    @Test
    fun `the fetch script fails the run on a library that is not 16 KB aligned`() {
        // The 16 KB gate (Play requirement, Feb 1 2027) has to be on the
        // ARTIFACT as well as on the packaged APK: a swapped or re-uploaded AAR
        // is exactly the case the APK-level release check would catch, and only
        // on a release run. The script therefore checks every 64-bit .so it
        // carries and exits non-zero on one that is not aligned - and it fails
        // CLOSED on a vacuous pass, because an AAR whose layout moved would
        // otherwise silently stop being gated at all.
        assertTrue(
            "the alignment gate must look at the AAR's own 64-bit libraries",
            fetchScript.contains("for abi in arm64-v8a x86_64") &&
                fetchScript.contains("readelf -lW")
        )
        assertTrue(
            "positively-detected small alignment is the failure - 0x4000 (or larger) is 16 KB",
            fetchScript.contains("0x1000|0x800|0x400|0x200")
        )
        assertTrue(
            "and an unaligned library must stop the run before the file is placed",
            fetchScript.contains("Do not ship this AAR")
        )
        assertTrue(
            "a vacuous pass (no 64-bit libraries found) is itself a failure",
            fetchScript.contains("would be vacuous")
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
