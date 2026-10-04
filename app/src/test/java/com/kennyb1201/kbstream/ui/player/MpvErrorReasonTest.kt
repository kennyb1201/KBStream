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
    fun `a tls trust failure is named over the generic open line`() {
        // libmpv reads no system trust store; with tls-verify on and no CA
        // bundle every https handshake reports exactly this, and it used to
        // fall through to the generic "Failed to open" line - which is why the
        // card never named the cause.
        val lines = listOf(
            "ffmpeg tls: Unable to get local issuer certificate",
            "stream Failed to open https://host/signed/file.mkv"
        )

        assertEquals(
            "ffmpeg tls: Unable to get local issuer certificate",
            MpvErrorReason.pick(lines)
        )
    }

    @Test
    fun `a host containing tls does not out-rank the real cause`() {
        // The bare token "tls" is deliberately absent from SPECIFIC: a debrid
        // host that contains it would otherwise win as the last matching line
        // and hide the actual certificate failure.
        val lines = listOf(
            "ffmpeg tls: Unable to get local issuer certificate",
            "stream Failed to open https://tls-cdn.host/signed/file.mkv"
        )

        assertEquals(
            "ffmpeg tls: Unable to get local issuer certificate",
            MpvErrorReason.pick(lines)
        )
    }

    @Test
    fun `blank lines and an empty log yield nothing`() {
        assertNull(MpvErrorReason.pick(emptyList()))
        assertNull(MpvErrorReason.pick(listOf("", "   ")))
    }

    // --- failureDetail: what the failed-open report says when mpv was silent ---

    @Test
    fun `a named cause is the whole failure detail`() {
        assertEquals(
            "stream HTTP error 403",
            MpvErrorReason.failureDetail("stream HTTP error 403", "cdn.example")
        )
    }

    @Test
    fun `a silent failure still names the host`() {
        assertEquals(
            "no reason reported (host=cdn.example)",
            MpvErrorReason.failureDetail(null, "cdn.example")
        )
    }

    @Test
    fun `a silent failure with no host says only that much`() {
        assertEquals("no reason reported", MpvErrorReason.failureDetail(null, null))
        assertEquals("no reason reported", MpvErrorReason.failureDetail("  ", ""))
    }
}
