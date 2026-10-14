package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MPV library swap, pinned where a JVM test can see it.
 *
 * The problem. MPV on `vo=gpu` shows black video over playing audio on the field
 * boxes with the library this app has always shipped, and Nuvio's GPU path works
 * with full libass on the same mpv core - so the library is the variable the
 * 0.6.77 test moves, and the two candidates cannot both be on the classpath:
 * they publish the SAME `is.xyz.mpv` classes and ship the same `libmpv.so` /
 * `libavcodec.so` / `libc++_shared.so` sonames, which is a duplicate class at
 * dex time and a collision inside `lib/<abi>/`. The swap is therefore a build
 * flag, and no unit test can build the other arm - the suite only ever runs
 * against the one this build linked.
 *
 * What CAN be pinned is everything that would make the swap silently wrong:
 *
 *  - the engine names no library, so both arms compile the same player, and a
 *    stray `is.xyz.mpv` reference in it is a build that only works for one;
 *  - the two arms expose the same facade, name for name and parameter for
 *    parameter, so the engine cannot compile against one and fail against the
 *    other - the failure this catches would otherwise land on whoever first
 *    builds the test variant;
 *  - neither arm's file sits on the main source path, so exactly one is
 *    compiled - and the two are never resolvable together;
 *  - the build still chooses the AAR by DEFAULT (nothing the flag does can
 *    change what a release ships) and the Maven coordinate only when asked, and
 *    never both;
 *  - the constants are mpv's own ABI values, copied from both libraries'
 *    compiled classes - they cross the JNI boundary, so a renumbered
 *    `MPV_EVENT_END_FILE` is a stream that never reports why it failed;
 *  - the linked library is named in the two places a field report reads it (the
 *    engine's start line and the diagnostics `build:` line), because a
 *    compile-time choice is otherwise invisible on the device.
 */
class MpvLibrarySwapContractTest {

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

    private val defaultArm: String
        get() = source("app/src/mpvrites/java/com/kennyb1201/kbstream/ui/player/MpvBackend.kt")

    private val testArm: String
        get() = source("app/src/mpvnuvio/java/com/kennyb1201/kbstream/ui/player/MpvBackend.kt")

    private val buildScript: String get() = source("app/build.gradle.kts")

    private val catalog: String get() = source("gradle/libs.versions.toml")

    private val diagnostics: String
        get() = source("app/src/main/java/com/kennyb1201/kbstream/data/reporting/Diagnostics.kt")

    // ── the engine is library-agnostic ───────────────────────────────────────

    @Test
    fun `the engine names no mpv library`() {
        assertFalse(
            "MpvPlayerView must not import is.xyz.mpv: that is a build that only " +
                "compiles against one of the two libraries",
            engine.contains("is.xyz.mpv")
        )
        assertFalse("the default library's singleton must not be named here", engine.contains("MPVLib"))
        assertFalse(
            "MPVNode is a different class in each library, so it cannot appear here",
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

    // ── the two arms are the same facade ─────────────────────────────────────

    /**
     * Every `fun` a facade arm declares, as `name(parameters): return`.
     *
     * Anchored on exactly four spaces of indent, which is the depth of the
     * facade's own members and NOT of the bridge objects nested inside
     * `addObserver` (their `override fun`s are deeper) - and `private fun` is
     * excluded, since the arms are free to differ in how they hold their state
     * (the default arm holds none; the test arm owns a library instance).
     */
    private fun facadeMembers(text: String): List<String> =
        Regex("^    fun (\\w+)\\(([^)]*)\\)(: *[^={]+)?", RegexOption.MULTILINE)
            .findAll(text)
            .map { match ->
                val parameters = match.groupValues[2].replace(Regex("\\s+"), " ")
                val returns = match.groupValues[3].trim().removePrefix(":").trim()
                "fun ${match.groupValues[1]}($parameters)" + if (returns.isEmpty()) "" else ": $returns"
            }
            .sorted()
            .toList()

    @Test
    fun `both arms expose the same facade`() {
        val expected = facadeMembers(defaultArm)
        // The whole surface the engine uses, so a member that disappeared from
        // BOTH arms still fails here rather than at some later call site.
        assertEquals(
            listOf(
                "fun addLogObserver(observer: MpvLogObserver)",
                "fun addObserver(observer: MpvEventObserver)",
                "fun attachSurface(surface: Surface)",
                "fun command(vararg args: String)",
                "fun create(context: Context)",
                "fun destroy()",
                "fun detachSurface()",
                "fun getPropertyBoolean(name: String): Boolean?",
                "fun getPropertyDouble(name: String): Double?",
                "fun getPropertyInt(name: String): Int?",
                "fun getPropertyString(name: String): String?",
                "fun init()",
                "fun observeProperty(name: String, format: Int)",
                "fun removeLogObserver(observer: MpvLogObserver)",
                "fun removeObserver(observer: MpvEventObserver)",
                "fun setOptionString(name: String, value: String): Int",
                "fun setPropertyBoolean(name: String, value: Boolean)",
                "fun setPropertyDouble(name: String, value: Double)",
                "fun setPropertyInt(name: String, value: Int)",
                "fun setPropertyString(name: String, value: String)",
            ),
            expected
        )
        assertEquals(
            "the test arm must expose the same members as the default one - " +
                "one arm growing a function the other lacks compiles here and " +
                "fails on whoever builds the test variant",
            expected,
            facadeMembers(testArm)
        )
        assertTrue(
            "both arms are the same object, replaced whole",
            defaultArm.contains("internal object MpvBackend {") &&
                testArm.contains("internal object MpvBackend {")
        )
    }

    @Test
    fun `each arm is compiled only by the build that chose it`() {
        // On the main source path there is no MpvBackend at all: if one were
        // here, the other arm's file would be a duplicate declaration.
        val onMainPath = File(
            projectRoot,
            "app/src/main/java/com/kennyb1201/kbstream/ui/player/MpvBackend.kt"
        )
        assertFalse("MpvBackend must not live in src/main: $onMainPath", onMainPath.isFile)
        // And each arm names the library it wraps, so a copy-paste that left the
        // default arm's import in the test arm (or the reverse) is visible here.
        assertTrue(defaultArm.contains("import `is`.xyz.mpv.MPVLib"))
        assertTrue(testArm.contains("import `is`.xyz.mpv.MPV"))
        assertTrue(testArm.contains("private var instance: MPV?"))
    }

    // ── the build chooses one, by default the shipping one ───────────────────

    @Test
    fun `the default build links the release AAR and the flag links the Maven coordinate`() {
        // The default arm: the fetched release AAR, and the label the APK
        // carries. A missing AAR is an error, not a silent fallback to the other
        // library.
        assertTrue(buildScript.contains("val mpvAar = file(\"libs/mpvlib-1.1.2.aar\")"))
        assertTrue(buildScript.contains("implementation(files(mpvAar))"))
        assertTrue(buildScript.contains("throw GradleException("))
        assertTrue(buildScript.contains("\"Riteshp2001/mpvlibAndroid mpvlib-1.1.2.aar\""))
        // The test arm: the Maven Central coordinate, and only under the flag.
        assertTrue(buildScript.contains("implementation(libs.mpv.android.lib)"))
        assertTrue(buildScript.contains("?: \"rites\""))
        assertTrue(
            buildScript.contains(
                "\"nuvio\", \"abdallahmehiz\", \"mpv-android-lib\", \"mpv-android-lib-0.1.12\" -> true"
            )
        )
        assertTrue(
            "the label has to name the version the catalog pins",
            buildScript.contains(
                "\"io.github.abdallahmehiz:mpv-android-lib:\${libs.versions.mpv.android.lib.get()}\""
            )
        )
        // Which arm's source directory is added, and that it is the Kotlin one
        // (AGP's built-in Kotlin compiles a source set's `kotlin` dirs).
        assertTrue(
            buildScript.contains(
                "val mpvEngineSourceDir = if (mpvNuvioLibrary) \"src/mpvnuvio\" else \"src/mpvrites\""
            )
        )
        assertTrue(buildScript.contains("kotlin { srcDir(\"\$projectDir/\$mpvEngineSourceDir\") }"))
        // The label reaches the app, which is how a field report can tell the
        // two APKs apart.
        assertTrue(
            buildScript.contains("buildConfigField(\"String\", \"MPV_ENGINE_LIBRARY\", \"\\\"\$mpvLibraryLabel\\\"\")")
        )
        // Never both, and never a second copy: one implementation line per arm.
        assertEquals(
            "exactly one arm may declare the AAR",
            1,
            Regex("implementation\\(files\\(mpvAar\\)\\)").findAll(buildScript).count()
        )
        assertEquals(
            "exactly one arm may declare the Maven coordinate",
            1,
            Regex("implementation\\(libs\\.mpv\\.android\\.lib\\)").findAll(buildScript).count()
        )
        // The coordinate itself, and the version, in the one place a version is
        // allowed to live.
        assertTrue(
            catalog.contains(
                "mpv-android-lib = { module = \"io.github.abdallahmehiz:mpv-android-lib\", version.ref = \"mpv-android-lib\" }"
            )
        )
        assertTrue(catalog.contains("mpv-android-lib = \"0.1.12\""))
        // Both libraries are R8-kept by name, and that rule is NOT conditional:
        // whichever one is linked constructs its classes from JNI by name, so a
        // renamed or stripped class is a NoClassDefFoundError on the first event.
        assertTrue(
            source("app/proguard-rules.pro").contains("-keep class is.xyz.mpv.** { *; }")
        )
    }

    @Test
    fun `no build pipeline can publish the test library`() {
        // The flag exists for a field test, and CI may now BUILD that arm: a
        // workflow_dispatch input, because the field boxes are the only place a
        // GPU video output can be judged and the app has to be installable to
        // get there at all.
        //
        // What must stay impossible is PUBLISHING it - the concern this test
        // was written for, a tag push handing the unproven library to every
        // install through the self-update feed. That is what is pinned below,
        // instead of the blanket ban on the flag existing anywhere in the
        // workflow: that ban saved the shipping path but left the library with
        // no way to reach a device, and it failed every run once the dispatch
        // input was added rather than protecting anything.
        val workflow = source(".github/workflows/build.yml")
        assertFalse(
            "nor may the fetch script it replaces",
            source("scripts/fetch_mpv_aar.sh").contains("MPV_LIB")
        )
        // A push or a tag has no dispatch input, so its run resolves to the
        // SHIPPED library; the test arm is reachable only by asking for it, and
        // only for the run that asked.
        assertTrue(
            "the test library must be selectable only by the dispatch input, with " +
                "everything else falling back to the shipped one",
            workflow.contains("MPV_LIB: \${{ inputs.mpv_lib || 'rites' }}")
        )
        assertEquals(
            "the input's default must be the shipped library",
            1,
            Regex("default: rites").findAll(workflow).count()
        )
        // Every step a release needs refuses the test arm: the signed release
        // build, the 16 KB native gate, the mapping upload, and all five
        // publish steps (the stale-build guard, the release notes, the
        // metadata, the tag cleanup and the release itself).
        assertEquals(
            "every release-only and publish step must be gated off FOR the test " +
                "library, so a dispatch can produce an APK and never a release",
            9,
            Regex("inputs\\.mpv_lib != 'nuvio'").findAll(workflow).count()
        )
        // And the artifact is named for the arm, so a test build cannot be
        // picked up believing it is the normal one.
        assertTrue(
            "the test build's artifact must say which arm it carries",
            workflow.contains("kbstream-debug-nuvio")
        )
    }

    // ── the constants cross the JNI boundary ────────────────────────────────

    @Test
    fun `the engine's mpv constants are the values both libraries carry`() {
        // Read out of BOTH libraries' compiled classes (javap -v on
        // MPVLib$MpvEvent / MPV$mpvEvent and the format and log-level holders):
        // they are mpv's own mpv_event_id / mpv_format / mpv_log_level values,
        // identical in the two, and they are what the native side sends back.
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
            "the engine's constants are the libraries' - a renumbered event id is " +
                "an event read as a different event",
            expected,
            declared
        )
    }

    // ── the linked library is named where a field report looks ──────────────

    @Test
    fun `the linked library is named in the engine's start line and the diagnostics report`() {
        // The engine's line is what a logcat capture of the GPU test carries.
        assertTrue(
            engine.contains("library=\${BuildConfig.MPV_ENGINE_LIBRARY}")
        )
        // The report's `build:` line is what a pasted diagnostics dump carries,
        // and it already names the version, the build number and the commit -
        // this is the fourth fact of the same kind.
        assertTrue(diagnostics.contains("mpv=\${BuildConfig.MPV_ENGINE_LIBRARY}"))
    }
}
