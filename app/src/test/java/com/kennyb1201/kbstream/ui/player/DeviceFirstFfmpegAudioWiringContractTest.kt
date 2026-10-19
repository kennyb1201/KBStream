package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wiring of the device-first FFmpeg audio policy, which no JVM test can
 * reach: the swap happens inside a renderer factory that needs an Android
 * `Context`, and the renderer it wraps only exists in a build that carries the
 * FFmpeg extension AAR.
 *
 * So the shape is pinned from the source, in the two places that can silently
 * undo the fix:
 *
 *  - the factory must re-wrap the renderer the stock policy built, matched by
 *    CLASS NAME (naming `androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer` as
 *    a type is what a build without the extension cannot compile), and it must
 *    do that AFTER `super.buildAudioRenderers` - the list it edits is the one
 *    the superclass fills;
 *  - the wrapper must gate the per-format question AND nothing else: in media3
 *    1.11 the renderer's format answer is
 *    `RendererCapabilities.supportsFormat`, so that is where the decline goes,
 *    and the platform-decoder probe must ask what media3's own MediaCodec audio
 *    renderer asks (`getDecoderInfos(mime, false, false)`) or the two renderers
 *    can disagree about who can take the track.
 */
class DeviceFirstFfmpegAudioWiringContractTest {

    private val sourceRoot: File by lazy { findSourceRoot() }

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

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private val factory: String get() = source(FACTORY)
    private val renderer: String get() = source(RENDERER)

    @Test
    fun `the factory re-wraps the extension audio renderer after the stock policy ran`() {
        val src = factory
        assertTrue(
            "the audio list is built by the stock policy and then edited",
            src.contains("override fun buildAudioRenderers(") &&
                src.contains("super.buildAudioRenderers(")
        )
        assertTrue(
            "the extension renderer must be matched by name, not by type",
            src.contains(FFMPEG_AUDIO_RENDERER_CLASS) &&
                !src.contains("import androidx.media3.decoder.ffmpeg")
        )
        assertTrue(
            "the match must be the wrap's own guard, so a build without the extension is a no-op",
            src.contains("if (index == -1) return")
        )
        assertTrue(
            "and the entry is replaced by the device-first renderer",
            src.contains("out[index] = DeviceFirstFfmpegAudioRenderer(")
        )
        assertTrue(
            "the same selector MediaCodec uses is what the wrapper asks",
            src.contains("mediaCodecSelector = mediaCodecSelector")
        )
    }

    @Test
    fun `the wrapper gates the per-format question only`() {
        val src = renderer
        assertTrue(
            "the stock renderer instance is wrapped, not subclassed (the extension is not a " +
                "compile dependency here)",
            src.contains(") : Renderer by delegate {")
        )
        assertTrue(
            "media3 1.11 answers format support through the capabilities, so that is the override",
            src.contains("override fun getCapabilities(): RendererCapabilities = capabilities") &&
                src.contains(") : RendererCapabilities by delegate {")
        )
        assertTrue(
            "the gate is the pure rule",
            src.contains("SoftwareAudioRules.ffmpegDeclinesForDeviceDecoder(")
        )
        assertTrue(
            "a declined format is unsupported, so the next renderer in the list takes it",
            src.contains("return C.FORMAT_UNSUPPORTED_TYPE")
        )
        assertTrue(
            "everything else the capabilities answer is passed through",
            src.contains("return delegate.supportsFormat(format)")
        )
    }

    @Test
    fun `the platform-decoder probe asks what MediaCodec asks`() {
        val src = renderer
        assertTrue(
            "the same decoder query media3's own audio renderer pays",
            src.contains("mediaCodecSelector.getDecoderInfos(sampleMimeType, false, false)")
        )
        assertTrue(
            "a blank mime is never a decline",
            src.contains("if (sampleMimeType.isNullOrBlank()) return false")
        )
        assertTrue(
            "and an unanswerable codec list leaves the track with FFmpeg (the pre-fix answer)",
            src.contains("runCatching {") && src.contains("}.getOrDefault(false)")
        )
        assertFalse(
            "the mode the setting chose is not overridden - DTS keeps its FFmpeg-first place",
            src.contains("EXTENSION_RENDERER_MODE_ON")
        )
    }

    private companion object {
        const val PACKAGE = "com/kennyb1201/kbstream/ui/player/"
        const val FACTORY = PACKAGE + "SplitModeRenderersFactory.kt"
        const val RENDERER = PACKAGE + "DeviceFirstFfmpegAudioRenderer.kt"

        /**
         * The class the factory looks for in the renderer list. Spelled out here
         * (rather than read from the source) so a changed string in the factory
         * fails this test instead of silently matching nothing at runtime.
         */
        const val FFMPEG_AUDIO_RENDERER_CLASS =
            "\"androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer\""
    }
}
