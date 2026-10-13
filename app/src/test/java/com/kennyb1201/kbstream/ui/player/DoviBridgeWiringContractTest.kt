package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * libdovi behind JNI, pinned where it can be seen from a JVM test.
 *
 * The problem: the ExoPlayer engine rewrites a Profile 7 RPU into Profile 8.1
 * with its own Kotlin parser, and the MediaTek decoder on the field boxes
 * stalls on every variant of that rewrite (relabel, no-relabel, VPS rewrite) --
 * audio over a black screen. libdovi parses the RPU and writes the conversion
 * itself, which is what renders on the same box with the same file.
 *
 * [DoviBridgeTest] pins the Kotlin half's behaviour. This pins the plumbing that
 * a unit test cannot execute: the JNI names that have to match between Kotlin
 * and C, the two entry points in `dovi_bridge.c`, the hook in
 * [DolbyVisionCompat.rewriteRpuTo81] and its fallback, and the build/CI wiring
 * that has to produce the two `.so` files for both ABIs. Any ONE of those
 * missing leaves the app compiling, running, and rendering the black screen the
 * feature exists to fix -- which is exactly why they are asserted together.
 */
class DoviBridgeWiringContractTest {

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

    // Paths are relative to the REPOSITORY root (the dir holding
    // settings.gradle.kts), so the app module's sources carry the `app/` prefix.
    private val compat: String
        get() = source("app/src/main/java/com/kennyb1201/kbstream/ui/player/DolbyVisionCompat.kt")

    private val bridge: String
        get() = source("app/src/main/java/com/kennyb1201/kbstream/ui/player/DoviBridge.kt")

    private val cBridge: String
        get() = source("app/src/main/cpp/dovi_bridge.c")

    private val script: String
        get() = source("scripts/build_dovi.sh")

    private val workflow: String
        get() = source(".github/workflows/build.yml")

    /**
     * The body of [DolbyVisionCompat.rewriteRpuTo81], from its declaration to
     * the next member. The hook is what makes libdovi the author of the 8.1
     * payload; every other assertion about it is about WHERE it sits.
     */
    private fun rewriteBody(): String {
        val start = compat.indexOf("fun rewriteRpuTo81(buf: ByteArray, from: Int, to: Int)")
        assertTrue("rewriteRpuTo81 not found", start >= 0)
        val end = compat.indexOf("fun transformAnnexBTo81", start)
        assertTrue("the end of rewriteRpuTo81 was not found", end > start)
        return compat.substring(start, end)
    }

    @Test
    fun `the 8_1 rewrite asks libdovi first and keeps the Kotlin path as the fallback`() {
        val body = rewriteBody()
        val hook = body.indexOf("DoviBridge.convertRpuTo81(buf, from, to)")
        val kotlinPath = body.indexOf("removeEmulationPrevention")
        assertTrue(
            "rewriteRpuTo81 must ask the bridge first: this function is the P7 -> 8.1 " +
                "payload author on the ExoPlayer path",
            hook >= 0
        )
        assertTrue(
            "and the Kotlin conversion must still be there, reached only when the " +
                "bridge returns null (no .so, or nothing convertible)",
            kotlinPath > hook
        )
        assertTrue(
            "the fallback is the `?.let { return it }` shape: a bridge result that is " +
                "used, with the Kotlin rewrite below it as the else",
            body.contains("?.let { return it }")
        )
    }

    @Test
    fun `the bridge refuses to call JNI unless the library loaded`() {
        assertTrue(
            "availability is a boolean the callers branch on, not a throwing lazy init",
            bridge.contains("val available: Boolean = runCatching { System.loadLibrary(LIBRARY) }")
        )
        val conversion = bridge.indexOf("fun convertRpuTo81(")
        assertTrue("convertRpuTo81 not found", conversion >= 0)
        assertTrue(
            "and the RPU entry point has to check it BEFORE it reaches JNI: an " +
                "external fun resolves lazily, so calling one without the library is " +
                "an UnsatisfiedLinkError at the call site, not a false",
            bridge.substring(conversion).contains("if (!available) return null")
        )
    }

    @Test
    fun `libdovi is the one asked what the RPU actually is`() {
        val probe = compat.indexOf("fun sampleDvProfileFromLibdovi(")
        assertTrue("sampleDvProfileFromLibdovi must exist", probe >= 0)
        val body = compat.substring(probe, compat.indexOf("fun sampleHasDvNalsAnnexB(", probe))
        assertTrue(
            "the profile probe has to go to libdovi's parser, not repeat the NAL-type " +
                "byte check it sits beside",
            body.contains("DoviBridge.nativeDetectProfile(buf, header, nalEnd - header)")
        )
        assertTrue(
            "and it must decline without the library rather than reach JNI",
            body.contains("if (!DoviBridge.available")
        )
        val extractor = source(
            "app/src/main/java/com/kennyb1201/kbstream/ui/player/DolbyVisionCompatExtractorsFactory.kt"
        )
        assertTrue(
            "the in-band DV log line is where a field report is read from, so it has to " +
                "carry what libdovi says the RPU is",
            extractor.contains("DolbyVisionCompat.sampleDvProfileFromLibdovi(")
        )
    }

    @Test
    fun `the JNI names on both sides are the same`() {
        // The C symbols are derived from the Kotlin package, object and method
        // names, so a rename on one side is a dlopen-time miss on a device, not a
        // compile error.
        val declarationNames = listOf("nativeDetectProfile", "nativeProcessVideoSample")
        declarationNames.forEach { name ->
            assertTrue(
                "DoviBridge must declare $name for the C side to bind to",
                bridge.contains("external fun $name(")
            )
            assertTrue(
                "dovi_bridge.c must export Java_..._DoviBridge_$name",
                cBridge.contains("Java_com_kennyb1201_kbstream_ui_player_DoviBridge_$name")
            )
        }
        assertTrue(
            "and the library name loadLibrary asks for must be the one the build produces",
            bridge.contains("const val LIBRARY = \"dovi_bridge\"") &&
                script.contains("libdovi_bridge.so")
        )
    }

    @Test
    fun `the C bridge is written against libdovi's C API and never invents RPU bytes`() {
        assertTrue(
            "the RPU parsing has to come from libdovi, not a second hand-rolled parser",
            cBridge.contains("#include <libdovi/rpu_parser.h>") ||
                cBridge.contains("#include <rpu_parser.h>")
        )
        listOf(
            "dovi_parse_unspec62_nalu",
            "dovi_rpu_get_header",
            "dovi_convert_rpu_with_mode",
            "dovi_write_unspec62_nalu",
            "dovi_rpu_free",
            "dovi_data_free"
        ).forEach { symbol ->
            assertTrue("the bridge must call $symbol", cBridge.contains(symbol))
        }
        assertTrue(
            "an unresolved libdovi symbol must fail the LINK, not dlopen on a TV",
            script.contains("-Wl,--no-undefined")
        )
    }

    @Test
    fun `the build produces both libraries for both ABIs and gates them`() {
        assertTrue(
            "the crate is fetched at a pinned version",
            script.contains("dolby_vision = { version = \"=")
        )
        assertTrue(
            "both ABIs are built",
            script.contains("arm64-v8a") && script.contains("armeabi-v7a")
        )
        assertTrue(
            "the output has to be the default jniLibs source set, or Gradle never " +
                "packages it",
            script.contains("app/src/main/jniLibs") || script.contains("jniLibs")
        )
        assertTrue(
            "the build itself must gate the 16 KB alignment (Play, Feb 1 2027)",
            script.contains("0x4000")
        )
        assertTrue(
            "and it must gate the link from the bridge to the crate",
            script.contains("libdovi\\.so") || script.contains("libdovi.so")
        )
        assertTrue(
            "the libraries are compiled (cargo ndk), not a checked-in binary: a " +
                "prebuilt .so cannot be rebuilt or audited from the tree",
            script.contains("cargo ndk")
        )
    }

    @Test
    fun `CI builds libdovi and fails the release when it is missing`() {
        assertTrue(
            "the workflow must run the build script",
            workflow.contains("scripts/build_dovi.sh")
        )
        assertTrue(
            "and it must be a hard gate: a release without libdovi ships the Kotlin " +
                "rewrite, which is the black screen this feature exists to fix",
            workflow.contains("Check whether libdovi was produced")
        )
        val gate = workflow.substringAfter("Check whether libdovi was produced")
        assertTrue(
            "the gate has to test both ABIs and both libraries",
            gate.contains("for abi in arm64-v8a armeabi-v7a") &&
                gate.contains("for lib in libdovi.so libdovi_bridge.so")
        )
        assertTrue(
            "and it has to collect failures and exit non-zero rather than warn",
            gate.contains("missing=1") && gate.contains("exit \$missing")
        )
        assertTrue(
            "the packaged-APK 16 KB check already covers these libraries, but the " +
                "workflow-level alignment check is what reports which one is wrong",
            workflow.contains("DOVI_VERSION")
        )
    }

    @Test
    fun `the shipped libraries are attributed`() {
        val notices = source("THIRD_PARTY_NOTICES.md")
        assertTrue(
            "libdovi is shipped in the APK and is MIT: it belongs in the inventory",
            notices.contains("libdovi") && notices.contains("MIT")
        )
        assertTrue(
            "and so does the bridge this repo compiles beside it",
            notices.contains("dovi_bridge.c")
        )
    }
}
