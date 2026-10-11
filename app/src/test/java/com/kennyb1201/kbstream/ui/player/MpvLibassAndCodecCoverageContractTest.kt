package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two `applyOptions()` defaults that only worked by accident, pinned so they
 * cannot quietly drift back.
 *
 *  - **ASS rendering.** Nothing set a `--sub-ass-*` option, so a fansub's
 *    typesetting survived by luck rather than by contract: mpv's own default
 *    for `sub-ass-override` is `scale`, under which any `--sub-ass-*` option
 *    added in future would silently restyle an ASS track. `no` is the
 *    author-faithful setting ("as specified by the subtitle scripts, without
 *    overrides"), and `ass-shaper=complex` pins HarfBuzz shaping for Arabic /
 *    Indic text so a build without it fails at the option check instead of
 *    shaping wrong.
 *  - **Codec coverage.** `hwdec-codecs` was an allowlist
 *    (`h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1`), so a codec the box's
 *    MediaCodec exposes beyond it (VP9 Profile 2 10-bit, AV1 film grain,
 *    VC-1, extra DV profiles, codecs added later) never got a hardware
 *    attempt. `all` widens the ATTEMPT set only; the `hwdec` chain still
 *    decides the fallback order, so no file that used to play can start
 *    failing.
 *
 * There is no device in CI and a libmpv Activity cannot be driven in a JVM
 * test, so the options that implement this are read out of the source the way
 * this tree's other player contracts are.
 */
class MpvLibassAndCodecCoverageContractTest {

    private val view: String by lazy { read(MPV_VIEW) }

    @Test
    fun `ASS tracks are pinned author-faithful instead of relying on mpv's scale default`() {
        assertTrue(
            "sub-ass-override must be explicitly `no`: mpv's default is `scale`, under which a " +
                "future --sub-ass-* option would silently restyle a fansub's karaoke, signs and " +
                "positioning",
            view.contains("MPVLib.setOptionString(\"sub-ass-override\", \"no\")")
        )
        assertFalse(
            "and it must not be `scale` (or any override mode) - the whole point is no overrides",
            view.contains("sub-ass-override\", \"scale\"")
        )
    }

    @Test
    fun `complex text shaping is pinned for Arabic and Indic scripts`() {
        assertTrue(
            "ass-shaper=complex is the HarfBuzz path; pinning it makes a build without HarfBuzz " +
                "fail loudly at the option check rather than silently shaping wrong",
            view.contains("MPVLib.setOptionString(\"ass-shaper\", \"complex\")")
        )
    }

    @Test
    fun `hardware decode attempts every codec the device exposes`() {
        assertTrue(
            "hwdec-codecs must be `all`, not an allowlist: an allowlist only narrows the set of " +
                "codecs that get a hardware attempt at all",
            view.contains("MPVLib.setOptionString(\"hwdec-codecs\", \"all\")")
        )
        assertFalse(
            "the old allowlist constant must be gone so the two cannot drift",
            view.contains("HWDEC_CODECS")
        )
        assertFalse(
            "nor may the old codec list survive as a bare string anywhere in the file",
            view.contains("h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")
        )
    }

    @Test
    fun `the hwdec fallback chain is unchanged, so widening only adds attempts`() {
        assertTrue(
            "the chain is what decides the failure mode; `all` above it must not have changed it",
            view.contains("const val HWDEC_HW = \"mediacodec,mediacodec-copy,no\"")
        )
        assertTrue(
            "and applyOptions still installs that chain as the hwdec value",
            view.contains("MPVLib.setOptionString(\"hwdec\", HWDEC_HW)")
        )
    }

    @Test
    fun `the appearance controls stay --sub-*, which is what keeps them off ASS tracks`() {
        val appearance = view
            .substringAfter("fun applySubtitleAppearance")
            .substringBefore("fun styleOption")
        assertTrue("applySubtitleAppearance must still be present", appearance.isNotEmpty())
        assertTrue(
            "size/position are --sub-* options, so they never reach an ASS track",
            appearance.contains("\"sub-font-size\"") && appearance.contains("\"sub-pos\"")
        )
        assertTrue(
            "and the fill is --sub-back-color, still a --sub-* option",
            appearance.contains("\"sub-back-color\"")
        )
    }

    @Test
    fun `no --sub-ass-* option other than the author-faithful override is set anywhere`() {
        val optionLiterals = Regex("\"sub-ass-[a-z-]*\"").findAll(view).map { it.value }.toList()
        assertEquals(
            "exactly one --sub-ass-* option may be set - the override that disables overrides. " +
                "Any second one restyles the fansub typesetting the app promises to preserve.",
            listOf("\"sub-ass-override\""),
            optionLiterals
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
