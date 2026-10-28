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
 * frames decode, and nothing reaches the Surface — and the Onn 4K Plus, which
 * the swap appeared to fix, was black-screening again on the same OpenGL path
 * by the 11 October field test. (The `updateAcquireFence: Did not find frame`
 * those reports quote is the platform's libgui frame-timestamp bookkeeping —
 * absent from every native library in the AAR — not mpv's and not
 * SurfaceFlinger's: it reports frames being queued, not lost.)
 * Swapping the video output is what fixes either box, so the choice is a
 * function of the device and the stored mode, and the table has to follow the
 * field tests rather than the history: an entry that says "known good" about a
 * path that no longer is one is how the second black screen was shipped.
 *
 * The resolution itself is pinned behaviorally ([MpvVideoOutput.resolve]) because
 * no test here can run a GPU, and the wiring is read out of the source the way
 * this tree's other player contracts are: a libmpv Activity cannot be driven
 * from a JVM test, and a resolver nobody calls fixes nothing.
 *
 * The 2026-10-28 field test closed the loop the old per-device table was
 * waiting on: the GPU path delivers frames on both boxes that had been stalling,
 * so `auto` is the GPU path everywhere and the MediaCodec workaround is an
 * explicit choice only. These tests were re-pinned to that, and the two box
 * cases are kept because they are the boxes the decision was made on.
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
    fun `auto opens the GPU path on the Fire TV Stick 4K Plus`() {
        // Re-pinned after the 2026-10-28 field confirmation: this is the box the
        // 6.51 swap black-screened, and the GPU path now delivers frames on it,
        // so `auto` no longer sends it to the workaround.
        val settings = MpvVideoOutput.resolve(
            AppPreferences.MPV_VO_AUTO, "Amazon", "AFTKRT", "karat"
        )
        assertEquals("gpu-next", settings.vo)
        assertEquals("android", settings.gpuContext)
        assertEquals(null, settings.gpuApi)
        assertTrue(settings.openglEs)
    }

    @Test
    fun `the model and device spellings of that box resolve the same way`() {
        // `Build.MODEL` and `Build.DEVICE` do not always carry the same string;
        // the field reports name both, and the resolution must not depend on
        // which one a report happens to quote.
        val byDevice = MpvVideoOutput.resolve(
            AppPreferences.MPV_VO_AUTO, "amazon", "AFTKA", "karat"
        )
        assertEquals("gpu-next", byDevice.vo)
    }

    @Test
    fun `auto leaves another Amazon box on the OpenGL path`() {
        // "Amazon" alone is every Fire TV ever made. Only the known-broken model
        // may be moved to the workaround, or the fix becomes a regression on
        // boxes that never had the bug.
        val settings = MpvVideoOutput.resolve(
            AppPreferences.MPV_VO_AUTO, "Amazon", "AFTKA", "sabrina"
        )
        assertEquals("gpu-next", settings.vo)
        assertEquals("android", settings.gpuContext)
        assertEquals(null, settings.gpuApi)
        assertTrue(settings.openglEs)
    }

    @Test
    fun `auto opens the GPU path on the Onn 4K Plus too`() {
        // Same re-pin as the Fire TV above: this box was moved to the workaround
        // by the 11 October test and is moved back by the confirmation, so no
        // device gets a different output from `auto` any more.
        val settings = MpvVideoOutput.resolve(
            AppPreferences.MPV_VO_AUTO, "onn", "onn. 4K Plus", "onn"
        )
        assertEquals("gpu-next", settings.vo)
        assertEquals("android", settings.gpuContext)
        assertEquals(null, settings.gpuApi)
        assertTrue(settings.openglEs)
    }

    @Test
    fun `an explicit MediaCodec mode is still obeyed on the Onn`() {
        // The workaround did not disappear, it stopped being a default: a box
        // that ever needs it again is one selection away, and that selection
        // must win over the GPU default.
        val forced = MpvVideoOutput.resolve(
            AppPreferences.MPV_VO_MEDIACODEC_EMBED, "onn", "onn. 4K Plus", "onn"
        )
        assertEquals("mediacodec_embed", forced.vo)
        assertEquals(
            "the MediaCodec path must name no GPU context",
            null,
            forced.gpuContext
        )
        assertEquals(null, forced.gpuApi)
        assertFalse(forced.openglEs)

        assertTrue(
            "and the Auto default it overrides is the GPU path",
            MpvVideoOutput.resolve(
                AppPreferences.MPV_VO_AUTO, "onn", "onn. 4K Plus", "onn"
            ).vo == "gpu-next"
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
        assertEquals("gpu-next", settings.vo)
        assertEquals("android", settings.gpuContext)
        assertEquals(null, settings.gpuApi)
        assertTrue(settings.openglEs)
    }

    @Test
    fun `the GPU path opens gpu-next, the output the new library is built for`() {
        // The 2026-10-15 library swap made this load-bearing rather than
        // cosmetic: the linked AAR carries libplacebo with `-Ddovi=enabled` and
        // libdovi statically linked, and it is `gpu-next` - libplacebo's own
        // renderer - that performs the Dolby Vision reshape. `vo=gpu`'s
        // libplacebo path does not, so shipping `gpu` here would silently give
        // back the HDR10-base-layer-only playback the swap was made to remove.
        val gpu = MpvVideoOutput.resolve(
            AppPreferences.MPV_VO_GPU, "NVIDIA", "SHIELD Android TV", "mdarcy"
        )
        assertEquals("gpu-next", gpu.vo)
        assertEquals(
            "the Android context is the same for gpu-next as it was for gpu",
            "android",
            gpu.gpuContext
        )
        assertEquals(
            "gpu-api stays unset (mpv's `auto`), so gpu-next picks Vulkan or GL from the " +
                "context - and the explicit fallback modes remain the way to pin one",
            null,
            gpu.gpuApi
        )
        assertTrue(
            "opengl-es=yes is still what asks the Android context for a GL context",
            gpu.openglEs
        )
        assertEquals(
            "the Vulkan mode opens the same gpu-next output",
            "gpu-next",
            MpvVideoOutput.resolve(
                AppPreferences.MPV_VO_GPU_VULKAN, "NVIDIA", "SHIELD Android TV", "mdarcy"
            ).vo
        )
        assertEquals(
            "and the MediaCodec path is still the one output that names no gpu-* option",
            "mediacodec_embed",
            MpvVideoOutput.resolve(
                AppPreferences.MPV_VO_MEDIACODEC_EMBED, "NVIDIA", "SHIELD Android TV", "mdarcy"
            ).vo
        )
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
        assertEquals("gpu-next", forcedGpu.vo)
        assertEquals(null, forcedGpu.gpuApi)
    }

    @Test
    fun `the Vulkan mode pins the Vulkan API and no GL context`() {
        val settings = MpvVideoOutput.resolve(
            AppPreferences.MPV_VO_GPU_VULKAN, "Amazon", "AFTKRT", "karat"
        )
        assertEquals("gpu-next", settings.vo)
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
        // The stored vocabulary is unchanged by the swap (`gpu` is still the
        // stored name of the GPU path - see MPV_VO_GPU), so a value that is not
        // in it is still a corrupt or older stored string. `gpu-next` is the
        // right thing to probe with: it is a real mpv output now, which is
        // exactly why it must NOT be accepted as a stored MODE. The stored
        // strings are a contract with what is on disk; the `vo` they resolve to
        // is not.
        val unknown = MpvVideoOutput.resolve("gpu-next", "Amazon", "AFTKRT", "karat")
        val auto = MpvVideoOutput.resolve(
            AppPreferences.MPV_VO_AUTO, "Amazon", "AFTKRT", "karat"
        )
        assertEquals(auto, unknown)
        assertTrue(
            "and gpu-next is not one of the stored modes: adding it would be a second " +
                "name for the same output",
            "gpu-next" !in AppPreferences.MPV_VO_MODES
        )
    }

    @Test
    fun `no GPU path ever leaves gpu-api unset`() {
        // gpu-api defaults to `auto`, and a Vulkan-capable box answers the
        // OpenGL context with the Vulkan API: opens without error, plays audio,
        // shows nothing. Every mode that opens a GPU context must resolve an
        // API, except that the GPU path's pin has been dropped (gpuApi null, so
        // mpv's `auto` applies) and only the MediaCodec path is allowed to
        // surface no gpu-api. `auto` resolves the GPU path on every box since
        // the 2026-10-28 confirmation, so the baseline install reads this too.
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
            view.contains("MpvBackend.setOptionString(\"vo\", vo.vo)")
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
                view.contains("MpvBackend.setOptionString(\"vo\", vo.vo)")
        )
        assertTrue(
            "and the surface cycle re-opens that same resolution as a property",
            view.contains("MpvBackend.setPropertyString(\"vo\", videoOutput().vo)")
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
