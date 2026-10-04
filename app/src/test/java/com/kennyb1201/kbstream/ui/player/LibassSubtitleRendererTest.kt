package com.kennyb1201.kbstream.ui.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.RendererCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The streaming renderer's two jobs: claim exactly the embedded ASS tracks, and
 * feed them to libass header-then-events (with a flush on seek).
 *
 * The renderer is driven here through its internal seams with a fake sink, so
 * the sample handling is tested without a player or libassjni.so. Getting the
 * claim wrong either strips styling (a declined track is flattened by media3)
 * or steals a sidecar, and getting the feed wrong shows nothing — both silent.
 */
class LibassSubtitleRendererTest {

    private class FakeSink(override val available: Boolean = true) : LibassStreamingSink {
        var createCalls = 0
        val codecPrivates = mutableListOf<ByteArray>()
        val chunks = mutableListOf<Triple<ByteArray, Long, Long>>()
        var flushCalls = 0

        override fun create(): Boolean {
            createCalls++
            return true
        }

        override fun processCodecPrivate(data: ByteArray): Boolean {
            codecPrivates += data
            return true
        }

        override fun processChunk(data: ByteArray, timeMs: Long, durationMs: Long) {
            chunks += Triple(data, timeMs, durationMs)
        }

        override fun flushEvents() {
            flushCalls++
        }
    }

    private fun format(mime: String, initializationData: Boolean = true): Format =
        Format.Builder()
            .setSampleMimeType(mime)
            .apply {
                if (initializationData) setInitializationData(listOf(byteArrayOf(1, 2, 3)))
            }
            .build()

    private fun supportOf(renderer: LibassSubtitleRenderer, format: Format): Int =
        RendererCapabilities.getFormatSupport(renderer.supportsFormat(format))

    // ── which tracks are claimed ─────────────────────────────────────────────

    @Test
    fun `an embedded ass track is handled`() {
        val renderer = LibassSubtitleRenderer(FakeSink())
        assertEquals(
            C.FORMAT_HANDLED,
            supportOf(renderer, format(MimeTypes.TEXT_SSA, initializationData = true))
        )
    }

    @Test
    fun `an srt track is unsupported`() {
        val renderer = LibassSubtitleRenderer(FakeSink())
        assertEquals(
            C.FORMAT_UNSUPPORTED_TYPE,
            supportOf(renderer, format(MimeTypes.APPLICATION_SUBRIP))
        )
    }

    @Test
    fun `an ass track with no header is left to media3`() {
        // A sidecar SSA track carries no initializationData; claiming it would
        // feed per-cue samples into a header-based parser.
        val renderer = LibassSubtitleRenderer(FakeSink())
        assertEquals(
            C.FORMAT_UNSUPPORTED_TYPE,
            supportOf(renderer, format(MimeTypes.TEXT_SSA, initializationData = false))
        )
    }

    @Test
    fun `no libass means nothing is claimed`() {
        val renderer = LibassSubtitleRenderer(FakeSink(available = false))
        assertEquals(
            C.FORMAT_UNSUPPORTED_TYPE,
            supportOf(renderer, format(MimeTypes.TEXT_SSA))
        )
    }

    // ── feeding samples to libass ────────────────────────────────────────────

    @Test
    fun `the header is fed once and activates the sink`() {
        val sink = FakeSink()
        var activated = 0
        val renderer = LibassSubtitleRenderer(sink) { activated++ }

        renderer.handleInputFormat(format(MimeTypes.TEXT_SSA))

        assertEquals(1, sink.createCalls)
        assertEquals(1, sink.codecPrivates.size)
        assertEquals(1, activated)
    }

    @Test
    fun `a sample is appended with a millisecond timestamp`() {
        val sink = FakeSink()
        val renderer = LibassSubtitleRenderer(sink)

        renderer.handleSample("Dialogue: 0,0:00:01.00,...".toByteArray(), timeUs = 1_500_000L)

        assertEquals(1, sink.chunks.size)
        assertEquals(1500L, sink.chunks[0].second)
        assertEquals(0L, sink.chunks[0].third)
        assertEquals(1, sink.createCalls)
    }

    @Test
    fun `a seek flushes events and re-feeds the header`() {
        val sink = FakeSink()
        val renderer = LibassSubtitleRenderer(sink)
        renderer.handleInputFormat(format(MimeTypes.TEXT_SSA))

        renderer.seekFlush()

        assertEquals(1, sink.flushCalls)
        // The header went in once on format change and again after the flush.
        assertEquals(2, sink.codecPrivates.size)
    }

    @Test
    fun `a seek with no header still flushes`() {
        val sink = FakeSink()
        val renderer = LibassSubtitleRenderer(sink)

        renderer.seekFlush()

        assertEquals(1, sink.flushCalls)
        assertTrue(sink.codecPrivates.isEmpty())
    }
}
