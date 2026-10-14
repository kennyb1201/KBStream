package com.kennyb1201.kbstream.ui.player

import com.kennyb1201.kbstream.data.settings.AppPreferences
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The MPV engine's video output, resolved per device.
 *
 * Field problem the resolver exists for: the 6.51 mpvlibAndroid swap
 * black-screened the Fire TV Stick 4K Plus (`karat`, MediaTek) — audio plays,
 * frames decode, and nothing reaches the Surface (`updateAcquireFence: Did not
 * find frame`) — and the Onn 4K Plus, which the swap appeared to fix, was
 * black-screening again on the same OpenGL path by the 11 October field test.
 * Swapping the video output is what fixes either box, so the choice is a
 * function of the device and the stored mode, and the table has to follow the
 * field tests rather than the history: an entry that says "known good" about a
 * path that no longer is one is how the second black screen was shipped.
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
        assertEquals(null, settings.gpuApi)
        assertTrue(settings.openglEs)
    }

    @Test
    fun `auto sends the Onn 4K Plus to MediaCodec as well`() {
        // Re-pinned after the 11 October field test: the OpenGL path the swap
        // was believed to have fixed on this box black-screens it too, so the
        // workaround is its default until a GPU configuration is confirmed
        // working on it on-device.
        val settings = MpvVideoOutput.resolve(
            AppPreferences.MPV_VO_AUTO, "onn", "onn. 4K Plus", "onn"
        )
        assertEquals("mediacodec_embed", settings.vo)
        assertEquals(
            "the MediaCodec path must name no GPU context",
            null,
            settings.gpuContext
        )
        assertEquals(null, settings.gpuApi)
        assertFalse(settings.openglEs)
    }

    @Test
    fun `an explicit GPU mode still overrides the Onn default`() {
        // The box has to stay testable: the settings row is what the field test
        // cycles on it, so `gpu` is obeyed even though `auto` no longer picks
        // it, and `auto` is one setting away from the OpenGL path again.
        val forced = MpvVideoOutput.resolve(
            AppPreferences.MPV_VO_GPU, "onn", "onn. 4K Plus", "onn"
        )
        assertEquals("gpu", forced.vo)
        assertEquals("android", forced.gpuContext)
        assertEquals(null, forced.gpuApi)
        assertTrue(forced.openglEs)

        assertTrue(
            "and the Auto default it overrides is the workaround, not OpenGL",
            MpvVideoOutput.resolve(
                AppPreferences.MPV_VO_AUTO, "onn", "onn. 4K Plus", "onn"
            ).vo == "mediacodec_embed"
        )
    }

    @Test
    fun `auto keeps the OpenGL path on hardware it does not know`() {
        // The upgrade path: an install on any other box must behave exactly as
        // it did before the library swap introduced this setting. The GPU path
        // now resolves gpuApi to null (mpv's `auto`), matching the unpinned path
        // the field boxes took as the manual override.
        val settings = MpvVideoOutput.resolve(
            AppPreferences.MPV_VO_AUTO, "NVIDIA", "SHIELD Android TV", "mdarcy"
        )
        assertEquals("gpu", settings.vo)
        assertEquals("android", settings.gpuContext)
        assertEquals(null, settings.gpuApi)
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
        assertEquals(null, forcedGpu.gpuApi)
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
        // gpu-api defaults to `auto`, and a Vulkan-capable box answers the
        // OpenGL context with the Vulkan API: opens without error, plays audio,
        // shows nothing. Every mode that opens a GPU context must resolve an
        // API, except that the GPU path's pin has been dropped (gpuApi null, so
        // mpv's `auto` applies) and only the MediaCodec path is allowed to
        // surface no gpu-api. The device table still sends Fire TV / Onn to
        // MediaCodec at `auto`, so the baseline install is unaffected.
        val modes = AppPreferences.MPV_VO_MODES
        assertEquals(null, MpvVideoOutput.resolve(modes[0], "Amazon", "AFTKRT", "karat").gpuApi)
        assertEquals("vulkan", MpvVideoOutput.resolve(modes[3], "Amazon", "AFTKRT", "karat").gpuApi)
        assertNull(
            "the MediaCodec_embed path names no gpu-api",
            MpvVideoOutput.resolve(modes[2], "Amazon", "AFTKRT", "karat").gpuApi
        )
        assertNull(
            "the auto path's GPU path also surfaces no gpu-api",
            MpvVideoOutput.resolve(modes[1], "Amazon", "AFTKRT", "karat").gpuApi
        )
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
            "and the resolver must be the only place a vo is chosen from",
            view.contains("MpvVideoOutput.resolve(")
        )
    }

    @Test
    fun `the resolved output is opened as the session option and re-opened from the same resolution`() {
        // This test has been through both answers. It first pinned that the VO
        // put back on a surface re-attach had to be the session's own rather
        // than a hardcoded "gpu" - then, after the surface-timing fix, that
        // nothing put a VO back at all, on the theory that the runtime
        // re-initialization was the black screen. That theory was wrong: with
        // the output never nulled and never re-opened, mpv never re-initializes
        // against the newly attached surface, which is the same audio-over-black
        // picture by a different route. The reference player (BaseMPVView in
        // mpv-android) tears the output down on teardown and re-opens it on the
        // way back in - on a RECREATION only: a first creation has a load
        // waiting and leaves the option alone - and both halves read this one
        // resolution, so a surface cycle can never become a second, silent mode
        // change.
        // The exact call order of the cycle is MpvSurfaceCycleContractTest's.
        assertTrue(
            "the resolution is applied once, as the option applyOptions opens on",
            view.contains("val vo = videoOutput()") &&
                view.contains("MPVLib.setOptionString(\"vo\", vo.vo)")
        )
        assertTrue(
            "and the surface cycle re-opens that same resolution as a property",
            view.contains("MPVLib.setPropertyString(\"vo\", videoOutput().vo)")
        )
        assertFalse(
            "never a pinned output: the cycle re-opens what this device resolved",
            view.contains("setPropertyString(\"vo\", \"gpu\")") ||
                view.contains("setPropertyString(\"vo\", \"mediacodec_embed\")")
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
