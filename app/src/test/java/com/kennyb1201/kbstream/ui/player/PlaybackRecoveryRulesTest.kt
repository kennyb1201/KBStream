package com.kennyb1201.kbstream.ui.player

import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The verdicts that steer the recovery ladder: retry the same decoder, strip
 * Dolby Vision, or hand the session to the MPV backup engine.
 *
 * These tests are the first coverage these rules have had. They were private
 * methods on the Activity, so the branch that decides every failed stream's
 * fate could only be checked on a real TV with a real failure — which is
 * exactly the situation the two open playback bugs are stuck in. The rules are
 * pure, so each branch can now be pinned here instead.
 *
 * A note on what is NOT covered: the decoder-resource branch that reads a
 * `MediaCodec.CodecException` cannot be constructed in a JVM test (its
 * constructor is package-private), so the exhaustion cases below drive the
 * message-chain fallback — the same path a minified build takes when the
 * concrete type is buried.
 */
class PlaybackRecoveryRulesTest {

    private fun playbackError(code: Int): PlaybackException =
        PlaybackException("failure", null, code)

    // --- isContainerParseFailure ---

    @Test
    fun `an unsupported container is a container parse failure`() {
        assertTrue(
            PlaybackRecoveryRules.isContainerParseFailure(
                playbackError(PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED)
            )
        )
    }

    @Test
    fun `a malformed container is a container parse failure`() {
        assertTrue(
            PlaybackRecoveryRules.isContainerParseFailure(
                playbackError(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED)
            )
        )
    }

    @Test
    fun `a manifest failure is not a container parse failure`() {
        assertFalse(
            PlaybackRecoveryRules.isContainerParseFailure(
                playbackError(PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED)
            )
        )
    }

    @Test
    fun `a decoder failure is not a container parse failure`() {
        assertFalse(
            PlaybackRecoveryRules.isContainerParseFailure(
                playbackError(PlaybackException.ERROR_CODE_DECODING_FAILED)
            )
        )
    }

    // --- isDecoderError ---

    @Test
    fun `each decoder error code is a decoder error`() {
        val codes = listOf(
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
        )
        codes.forEach { code ->
            assertTrue("code $code should be a decoder error", PlaybackRecoveryRules.isDecoderError(code))
        }
    }

    @Test
    fun `a network error is not a decoder error`() {
        assertFalse(PlaybackRecoveryRules.isDecoderError(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
    }

    // --- isMissingDecoderFailure ---

    @Test
    fun `a null throwable is not a missing decoder`() {
        assertFalse(PlaybackRecoveryRules.isMissingDecoderFailure(null))
    }

    @Test
    fun `an unrelated throwable is not a missing decoder`() {
        assertFalse(PlaybackRecoveryRules.isMissingDecoderFailure(RuntimeException("boom")))
    }

    @Test
    fun `a decoder init failure with no codec info is a missing decoder`() {
        // No mime on the format and no candidate codec: Media3 asked the
        // platform for a decoder and got none back, so every rebuild would
        // ask for the same missing component.
        val error = MediaCodecRenderer.DecoderInitializationException(
            Format.Builder().build(),
            null,
            false,
            0
        )
        assertTrue(PlaybackRecoveryRules.isMissingDecoderFailure(error))
    }

    @Test
    fun `a decoder init failure naming a mime stays on the ladder`() {
        // The device decoder list is queried for the mime. On a JVM that query
        // fails, and the conservative answer ("a decoder exists") is what keeps
        // the pre-existing retry ladder rather than handing off early.
        val error = MediaCodecRenderer.DecoderInitializationException(
            Format.Builder().setSampleMimeType("video/avc").build(),
            null,
            false,
            0
        )
        assertFalse(PlaybackRecoveryRules.isMissingDecoderFailure(error))
    }

    @Test
    fun `a wrapped decoder init failure is still found in the cause chain`() {
        val error = RuntimeException(
            "wrapped",
            MediaCodecRenderer.DecoderInitializationException(
                Format.Builder().build(),
                null,
                false,
                0
            )
        )
        assertTrue(PlaybackRecoveryRules.isMissingDecoderFailure(error))
    }

    // --- isDecoderResourceExhausted ---

    @Test
    fun `a null throwable is not resource exhaustion`() {
        assertFalse(PlaybackRecoveryRules.isDecoderResourceExhausted(null))
    }

    @Test
    fun `the omx code in the message is resource exhaustion`() {
        // The fallback path: the concrete CodecException type is unavailable or
        // minified away, so the platform's own code in the message chain is the
        // only signal left.
        assertTrue(
            PlaybackRecoveryRules.isDecoderResourceExhausted(
                RuntimeException("OMX error 0x80001000")
            )
        )
    }

    @Test
    fun `the omx code is found in a wrapped cause`() {
        assertTrue(
            PlaybackRecoveryRules.isDecoderResourceExhausted(
                RuntimeException("wrapped", RuntimeException("decoder failed: 0x80001000"))
            )
        )
    }

    @Test
    fun `an unrelated message is not resource exhaustion`() {
        assertFalse(
            PlaybackRecoveryRules.isDecoderResourceExhausted(
                RuntimeException("decoder failed for another reason")
            )
        )
    }

    // --- isUnopenableSource ---

    @Test
    fun `the three unopenable io codes are unopenable`() {
        val codes = listOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        )
        codes.forEach { code ->
            assertTrue("code $code should be unopenable", PlaybackRecoveryRules.isUnopenableSource(playbackError(code)))
        }
    }

    @Test
    fun `a mid-playback io break is not unopenable`() {
        // This can clear on its own, so it keeps the full retry ladder rather
        // than the shortened one.
        assertFalse(
            PlaybackRecoveryRules.isUnopenableSource(
                playbackError(PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
            )
        )
    }

    @Test
    fun `a player timeout is not unopenable`() {
        assertFalse(
            PlaybackRecoveryRules.isUnopenableSource(
                playbackError(PlaybackException.ERROR_CODE_TIMEOUT)
            )
        )
    }

    // --- isLinkFailure ---
    //
    // The gate the played-link cache reads before retiring an entry (PB-P2-2):
    // the failure has to be about the LINK, not about what this box made of it.

    @Test
    fun `a refused or expired link is a link failure`() {
        // The canonical dead-debrid signature: 403/410 from the host.
        assertTrue(
            PlaybackRecoveryRules.isLinkFailure(
                playbackError(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
            )
        )
        // And the other shape of the same thing: the redirect landed on an HTML
        // error page instead of media.
        assertTrue(
            PlaybackRecoveryRules.isLinkFailure(
                playbackError(PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE)
            )
        )
        // The host was unreachable: still nothing about the box.
        assertTrue(
            PlaybackRecoveryRules.isLinkFailure(
                playbackError(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
            )
        )
    }

    @Test
    fun `a decoder failure is not a link failure`() {
        // THIS is the bug PB-P2-2 covers: the box could not decode the stream,
        // which says nothing about whether the link is alive, so the cached
        // entry must survive for the next replay.
        val codes = listOf(
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED
        )
        codes.forEach { code ->
            assertFalse("code $code must not retire a cached link", PlaybackRecoveryRules.isLinkFailure(playbackError(code)))
        }
    }

    @Test
    fun `a container or track failure is not a link failure`() {
        val codes = listOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_TIMEOUT,
            PlaybackException.ERROR_CODE_UNSPECIFIED
        )
        codes.forEach { code ->
            assertFalse("code $code must not retire a cached link", PlaybackRecoveryRules.isLinkFailure(playbackError(code)))
        }
    }
}
