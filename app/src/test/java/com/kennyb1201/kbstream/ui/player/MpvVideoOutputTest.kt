package com.kennyb1201.kbstream.ui.player

import com.kennyb1201.kbstream.data.settings.AppPreferences
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MPV engine's video output, resolved per device.
 *
 * Field problem the resolver exists for: the 6.51 mpvlibAndroid swap fixed the
 * Onn 4K Plus and black-screened the Fire TV Stick 4K Plus (`karat`, MediaTek) —
 * audio plays, frames decode, and nothing reaches the Surface
 * (`updateAcquireFence: Did not find frame`). With `vo=gpu` +
 * `gpu-context=android` there is no one configuration that works on both, so the
 * choice is a function of the device and the stored mode.
 *
 * The device table is pinned behaviorally ([MpvVideoOutput.resolve]) because no
 * test here can run a GPU, and the wiring is read out of the source the way this
 * tree's other player contracts are: a libmpv Activity cannot be driven from a
 * JVM test, and a resolver nobody calls fixes nothing.
 *
 * The default for a box that is neither of the two named ones is deliberately
 * pinned last: it is what every existing install gets on upgrade, so it has to
 * stay the OpenGL path the app configured before the library swap.
 */
class MpvVideoOutputTest {

    private val view: String by lazy { read(MPV_VIEW) }

    // ── the stored vocabulary ─────────────────────────────────────────────

    @Test
    fun `the stored modes are the strings a stored value has to keep`() {
        // These four strings are written to disk, so a rename is not a refactor:
        // it resets every install to Auto and orphans whatever was stored. Pin
        // them, exactly as the value migrator would need to read them back.
        assertEquals(
            listOf("auto", "gpu", "mediacodec_embed", "gpu-vulkan"),
            AppPreferences.MPV_VO_MODES
        )
    }

    // ── the auto device table ─────────────────────────────────────────────

    @Test
    fun `auto sends the Fire TV Stick 4K Plus to MediaCodec`() {
        val settings = MpvVideoOutput.resolve(
            AppPreferences.MPV_VO_AUTO, "Amazon", "AFTKRT", "karat"
        )
        assertEquals("mediacodec_embed", settings.vo)
        assertEquals(
            "the MediaCodec path must name no GPU context — naming one is the " +
                "mismatch the box stalls on",
            null,
            settings.gpuContext
        )
        assertEquals(null, settings.gpuApi)
        assertFalse(settings.openglEs)
    }

    @Test
    fun `auto matches the Fire TV Stick on the device name too`() {
        // `Build.MODEL` and `Build.DEVICE` do not always carry the same string;
        // the field reports name both.
        val byDevice = MpvVideoOutput.resolve(
            AppPreferences.MPV_VO_AUTO, "amazon", "AFTKA", "karat"
        )
        assertEquals("mediacodec_embed", byDevice.vo)
    }

    @Test
    fun `auto leaves another Amazon box on the OpenGL path`() {
        // "Amazon" alone is every Fire TV ever made. Only the known-broken model
        // may be moved to the workaround, or the fix becomes a regression on
        // boxes that never had the bug.
        val settings = MpvVideoOutput.resolve(
            AppPreferences.MPV_VO_AUTO, "Amazon", "AFTKA", "sabrina"
        )
        assertEquals("gpu", settings.vo)
        assertEquals("android", settings.gpuContext)
        assertEquals("opengl", settings.gpuApi)
        assertTrue(settings.openglEs)
    }

    @Test
    fun `auto keeps the Onn 4K Plus on the GPU path it displays with`() {
        val settings = MpvVideoOutput.resolve(
            AppPreferences.MPV_VO_AUTO, "onn", "onn. 4K Plus", "onn"
        )
        assertEquals("gpu", settings.vo)
        assertEquals("android", settings.gpuContext)
        assertEquals("opengl", settings.gpuApi)
        assertTrue(settings.openglEs)
    }

    @Test
    fun `auto keeps the OpenGL path on hardware it does not know`() {
        // The upgrade path: an install on any other box must behave exactly as
        // it did before the library swap introduced this setting.
        val settings = MpvVideoOutput.resolve(
            AppPreferences.MPV_VO_AUTO, "NVIDIA", "SHIELD Android TV", "mdarcy"
        )
        assertEquals("gpu", settings.vo)
        assertEquals("android", settings.gpuContext)
        assertEquals("opengl", settings.gpuApi)
        assertTrue(settings.openglEs)
    }

    // ── the explicit modes ────────────────────────────────────────────────

    @Test
    fun `an explicit mode overrides the device table`() {
        // This is what makes the row a diagnostic: the field test cycles these
        // on the box that is black-screening, so an explicit value must be
        // obeyed even where the table would choose otherwise.
        val forcedGpu = MpvVideoOutput.resolve(
            AppPreferences.MPV_VO_GPU, "Amazon", "AFTKRT", "karat"
        )
        assertEquals("gpu", forcedGpu.vo)
        assertEquals("opengl", forcedGpu.gpuApi)
    }

    @Test
    fun `the Vulkan mode pins the Vulkan API and no GL context`() {
        val settings = MpvVideoOutput.resolve(
            AppPreferences.MPV_VO_GPU_VULKAN, "Amazon", "AFTKRT", "karat"
        )
        assertEquals("gpu", settings.vo)
        assertEquals("android", settings.gpuContext)
        assertEquals("vulkan", settings.gpuApi)
        assertFalse(
            "asking for the GL context on the Vulkan API is the mismatched pair " +
                "that shows no frame at all",
            settings.openglEs
        )
    }

    @Test
    fun `an unknown mode resolves as auto rather than a vo mpv does not have`() {
        val unknown = MpvVideoOutput.resolve("gpu-next", "Amazon", "AFTKRT", "karat")
        val auto = MpvVideoOutput.resolve(
            AppPreferences.MPV_VO_AUTO, "Amazon", "AFTKRT", "karat"
        )
        assertEquals(auto, unknown)
    }

    @Test
    fun `no GPU path ever leaves gpu-api unset`() {
        // `gpu-api` defaults to auto, and a Vulkan-capable box answers the
        // OpenGL context with the Vulkan API - it opens without error, plays
        // audio and shows nothing. Every mode that opens a GPU context has to
        // name its API; only the MediaCodec path may name none.
        AppPreferences.MPV_VO_MODES.forEach { mode ->
            val settings = MpvVideoOutput.resolve(mode, "Amazon", "AFTKRT", "karat")
            if (settings.gpuContext != null) {
                assertTrue(
                    "$mode opens a GPU context without pinning gpu-api",
                    settings.gpuApi != null
                )
            }
        }
    }

    // ── the wiring ────────────────────────────────────────────────────────

    @Test
    fun `the player opens on the resolved output and logs which one`() {
        assertTrue(
            "applyOptions must open on what the resolver returns, not a pinned vo",
            view.contains("val vo = videoOutput()")
        )
        assertTrue(
            "and vo/gpu-context/gpu-api must come from that resolution",
            view.contains("MPVLib.setOptionString(\"vo\", vo.vo)")
        )
        assertTrue(
            "the field test reads this line, so it has to carry the mode and the device",
            view.contains("\"MPV vo mode=")
        )
        assertTrue(
            "the resolution has to be shared with the surface restore, not re-derived",
            view.contains("MpvVideoOutput.resolve(")
        )
    }

    @Test
    fun `a surface re-attach restores the resolved output, not a fixed gpu`() {
        assertFalse(
            "restoring a hardcoded \"gpu\" would black-screen the exact box the " +
                "MediaCodec mode exists for, on the first background/foreground",
            view.contains("setPropertyString(\"vo\", \"gpu\")")
        )
        assertTrue(
            "the VO put back on re-attach must be the session's own",
            view.contains("MPVLib.setPropertyString(\"vo\", videoOutput().vo)")
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
