package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MPV engine's surface cycle, and the `fast` profile added beside it.
 *
 * Field problem: the engine rendered on NO video output — audio played, the
 * track list and `video-format` both populated, and no frame ever reached the
 * Surface. The cause was in the cycle, not in the resolver:
 * `attachSurface()` attached the surface and then set `vo` as a PROPERTY, to
 * undo the `vo=null` that `surfaceDestroyed()` set. A runtime `vo` change
 * re-initializes mpv's video output, and the surface had just been attached to
 * the output that was already open — so the replacement starts without one and
 * every decoded frame is discarded. The working reference configuration never
 * sets `vo` at runtime at all: it is an option, applied once before `init()`,
 * and the surface cycle only attaches and detaches the surface.
 *
 * Three things have to hold together, which is why they are pinned in one place:
 *
 *  1. `applyOptions()` sets `vo` as an option before `init()`, from the
 *     device-resolved output — the one and only place it is ever set;
 *  2. `attachSurface()` attaches the surface and says nothing about the output;
 *  3. `surfaceDestroyed()` drops forced rendering and detaches, and does NOT
 *     null the VO — nulling it is what forced the re-init in the first place.
 *
 * The one remaining `vo` property set in the class is [MpvPlayerView.release]'s:
 * it nulls the output and destroys the instance in the same breath, so nothing
 * can re-initialize against it, and a session that starts again starts with
 * [MpvPlayerView.applyOptions] on a fresh instance. That is why the rule the
 * tests state is "set once per instance, and never on a surface cycle".
 *
 * `profile=fast` is pinned here too: it was added for the same boxes (a 4K
 * release on this class of hardware is GPU-bound), and it is only compatible
 * with points 1-3 because it names no `vo`, no GPU context and no decoder. Its
 * contents come from the bundled libmpv's own compiled-in profile list —
 * verified by reading it out of the AAR, because a profile that mpv does not
 * define would be an option-set error and a silent no-op, not a fix:
 *
 *     unzip -p app/libs/mpvlib-1.1.2.aar jni/arm64-v8a/libmpv.so > libmpv.so
 *     strings -a libmpv.so | grep -A8 '^\[fast\]$'
 *     -> scale=bilinear, dscale=bilinear, dither=no, correct-downscaling=no,
 *        linear-downscaling=no, sigmoid-upscaling=no, hdr-compute-peak=no,
 *        allow-delayed-peak-detect=yes
 *
 * There is no device in CI and a libmpv Activity cannot be driven from a JVM
 * test, so the sequence is read out of the source the way this tree's other
 * player contracts are.
 */
class MpvSurfaceCycleContractTest {

    private val view: String by lazy { read(MPV_VIEW) }

    /**
     * The same source with every comment line dropped, so a rule can be stated
     * about the CODE. It matters here: the comments that explain this fix quote
     * the very calls that had to go, and asserting against prose would fail on
     * the documentation of the bug while the bug itself went unchecked.
     */
    private val code: String by lazy {
        view.lines()
            .filterNot { line ->
                val trimmed = line.trimStart()
                trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")
            }
            .joinToString("\n")
    }

    @Test
    fun `the video output is an option set once, before init`() {
        val applications = Regex("MPVLib\\.setOptionString\\(\"vo\"[^)]*\\)").findAll(code).toList()
        assertEquals(
            "the VO must be set exactly once in the whole view - a second set is the " +
                "re-initialization that loses the surface",
            1,
            applications.size
        )
        assertTrue(
            "and it must take the device-resolved output, not a pinned one",
            applications.single().value.contains("vo.vo")
        )
        // Exactly one property set is left in the file, and it is the last thing
        // release() does before destroying the instance: a dead instance cannot
        // re-initialize anything, so that one is teardown, not part of a cycle.
        // Everywhere else - above all on attach - a `vo` set is the bug.
        assertEquals(
            "the only `vo` property set in the view is release()'s final teardown",
            1,
            Regex("setPropertyString\\(\"vo\"").findAll(code).count()
        )
        val release = code.substringAfter("fun release()")
            .substringBefore("// --- Playback control")
        assertTrue(
            "and it has to be paired with destroy(), which is what makes it final",
            release.contains("setPropertyString(\"vo\", \"null\")") &&
                release.contains("MPVLib.destroy()")
        )
        // Options only take as options before init(): after that the same call is
        // a runtime change, which is the whole distinction this fix rests on. The
        // body of initialize() is where that order is visible - the two functions
        // are declared far apart, so their positions in the file say nothing.
        val initialize = code.substringAfter("fun initialize()")
            .substringBefore("private fun ")
        val options = initialize.indexOf("applyOptions()")
        val init = initialize.indexOf("MPVLib.init()")
        assertTrue("initialize() has to apply the options", options >= 0)
        assertTrue("and initialize the engine", init >= 0)
        assertTrue(
            "applyOptions() must run before MPVLib.init()",
            options < init
        )
    }

    @Test
    fun `attaching the surface says nothing about the video output`() {
        val attach = code.substringAfter("private fun attachSurface()")
            .substringBefore("private fun startPendingLoad()")
        assertTrue(
            "the surface still has to be attached",
            attach.contains("MPVLib.attachSurface(surface)")
        )
        assertTrue(
            "and forced rendering with it, or mpv decides it has no window to draw into",
            attach.contains("MPVLib.setOptionString(\"force-window\", \"yes\")")
        )
        // The regression itself: a `vo` set here (property OR option) re-inits the
        // output after the surface went to the old one.
        assertFalse(
            "no video output is set on attach",
            attach.contains("videoOutput()") || attach.contains("\"vo\"")
        )
    }

    @Test
    fun `teardown detaches the surface without nulling the video output`() {
        val destroyed = code.substringAfter("override fun surfaceDestroyed(holder: SurfaceHolder)")
            .substringBefore("private fun attachSurface()")
        assertTrue(
            "forced rendering is dropped while there is no surface",
            destroyed.contains("MPVLib.setPropertyString(\"force-window\", \"no\")")
        )
        assertTrue(
            "and the surface is detached",
            destroyed.contains("MPVLib.detachSurface()")
        )
        // `vo=null` is what made the re-attach set a VO at all: with it gone, the
        // option set before init() survives the destroy/create cycle by itself.
        assertFalse(
            "the VO survives a surface destroy/create - it must not be torn down here",
            destroyed.contains("\"vo\"") || destroyed.contains("\"null\"")
        )
    }

    @Test
    fun `the fast profile is set with the performance options, before the output`() {
        val applyOptions = code.substringAfter("private fun applyOptions()")
            .substringBefore("private fun ")
        assertTrue(
            "the profile must be mpv's own built-in `fast`",
            applyOptions.contains("MPVLib.setOptionString(\"profile\", \"fast\")")
        )
        assertTrue(
            "set before the video output: a profile applies the options it names the moment " +
                "it is set, and this one has to stay underneath the device-resolved vo",
            applyOptions.indexOf("MPVLib.setOptionString(\"profile\", \"fast\")") <
                applyOptions.indexOf("MPVLib.setOptionString(\"vo\"")
        )
    }

    private fun read(relative: String): String {
        val file = File(findSourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun findSourceRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private companion object {
        const val MPV_VIEW = "com/kennyb1201/kbstream/ui/player/MpvPlayerView.kt"
    }
}
