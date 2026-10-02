package com.kennyb1201.kbstream.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which mpv line the failure notice shows, and how it is joined. */
class MpvErrorReasonTest {

    @Test
    fun `a prefix and its text are joined with a space`() {
        assertEquals(
            "stream Failed to open http://host/file.mkv",
            MpvErrorReason.format("stream", "Failed to open http://host/file.mkv")
        )
    }

    @Test
    fun `a blank prefix leaves the text alone`() {
        assertEquals(
            "Failed to open http://host/file.mkv",
            MpvErrorReason.format("", "  Failed to open http://host/file.mkv  ")
        )
    }

    @Test
    fun `the specific cause wins over the generic line after it`() {
        // The shape mpv actually produces for a rejected signed link.
        val lines = listOf(
            "http HTTP error 403 Forbidden",
            "stream Failed to open http://host/signed/file.mkv"
        )

        assertEquals("http HTTP error 403 Forbidden", MpvErrorReason.pick(lines))
    }

    @Test
    fun `the last specific line wins when a stream was retried`() {
        val lines = listOf(
            "http Connection refused",
            "stream Failed to open http://host/a.mkv",
            "http HTTP error 403 Forbidden",
            "stream Failed to open http://host/a.mkv"
        )

        assertEquals("http HTTP error 403 Forbidden", MpvErrorReason.pick(lines))
    }

    @Test
    fun `a generic failure falls back to the last line`() {
        val lines = listOf(
            "cplayer something unremarkable",
            "stream Failed to open http://host/a.mkv"
        )

        assertEquals(
            "stream Failed to open http://host/a.mkv",
            MpvErrorReason.pick(lines)
        )
    }

    @Test
    fun `numbers in a signed url are never mistaken for a cause`() {
        val lines = listOf(
            "stream Failed to open http://host/403/stream/tt123:1:1/-1/file.mkv",
            "ffmpeg Protocol not found"
        )

        assertEquals("ffmpeg Protocol not found", MpvErrorReason.pick(lines))
    }

    @Test
    fun `blank lines and an empty log yield nothing`() {
        assertNull(MpvErrorReason.pick(emptyList()))
        assertNull(MpvErrorReason.pick(listOf("", "   ")))
    }
}
