package com.kennyb1201.kbstream.data.youtube

import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The one classification the playback side acts on: is this failure YouTube
 * refusing the CLIENT IDENTITY (the cached watch config's visitor data), or
 * something else?
 *
 * The distinction is what the 2026-10-18 hero-trailer 403 log turned on. The
 * player showed `ERROR_CODE_IO_BAD_HTTP_STATUS` after
 * [YoutubeChunkedDataSourceFactory] had already cycled every client UA,
 * ratebypass and bounded-range mode - so every mode was refused, and the one
 * retry re-resolved through [TrailerPlayerLauncher.invalidate] and 403'd again
 * on its FIRST request with the very identity the watch config still held.
 * Fresh signature, refused identity: re-resolving with the same visitor can
 * never recover that, which is what makes telling it apart from the other 403s
 * worth a function.
 *
 * Robolectric rather than plain JVM because the answer is read from the
 * exception's own [DataSpec], and building one needs a real `android.net.Uri`
 * (the unit-test stub returns null from `Uri.parse`).
 */
@RunWith(AndroidJUnit4::class)
class YouTubeErrorsTest {

    // ------------------------------------------------------- the classifier --

    @Test
    fun `a 403 from googlevideo is an identity block`() {
        assertTrue(
            playbackError(
                forbidden(GOOGLEVIDEO_URL)
            ).isYouTubeIdentityBlocked()
        )
    }

    @Test
    fun `a 403 from another host is not an identity block`() {
        // A debrid/addon host refusing its own link says nothing about
        // YouTube's view of this device, and invalidating the watch config for
        // it would only cost the next resolve a watch-page fetch.
        assertFalse(
            playbackError(
                forbidden("https://cdn.realdebrid.example/stream/abc.mkv")
            ).isYouTubeIdentityBlocked()
        )
    }

    @Test
    fun `a lookalike host is not googlevideo`() {
        assertFalse(
            playbackError(
                forbidden("https://googlevideo.com.evil.example/videoplayback")
            ).isYouTubeIdentityBlocked()
        )
    }

    @Test
    fun `a 404 from googlevideo is not an identity block`() {
        // A gone file is not a refused identity, and the plain retry is right.
        assertFalse(
            playbackError(
                forbidden(GOOGLEVIDEO_URL, responseCode = 404)
            ).isYouTubeIdentityBlocked()
        )
    }

    @Test
    fun `a timeout is not an identity block`() {
        assertFalse(
            PlaybackException(
                "timeout",
                HttpDataSource.HttpDataSourceException(
                    DataSpec(Uri.parse(GOOGLEVIDEO_URL)),
                    HttpDataSource.HttpDataSourceException.TYPE_READ,
                    PlaybackException.ERROR_CODE_TIMEOUT
                ),
                PlaybackException.ERROR_CODE_TIMEOUT
            ).isYouTubeIdentityBlocked()
        )
        assertFalse(
            PlaybackException(
                "connection timed out",
                null,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
            ).isYouTubeIdentityBlocked()
        )
    }

    @Test
    fun `a 403 two causes deep is still found`() {
        val error = PlaybackException(
            "player failed",
            RuntimeException("source failed", forbidden(GOOGLEVIDEO_URL)),
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        )
        assertTrue(error.isYouTubeIdentityBlocked())
    }

    @Test
    fun `the outer error code must be the bad http status`() {
        // The 403 is in the chain, but the player reported something else, so
        // this is not the failure shape the retry is being given a fresh
        // identity for.
        val error = PlaybackException(
            "unexpected",
            forbidden(GOOGLEVIDEO_URL),
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED
        )
        assertFalse(error.isYouTubeIdentityBlocked())
    }

    @Test
    fun `a bad http status with no response in the chain is not an identity block`() {
        assertFalse(
            PlaybackException(
                "server returned an error",
                RuntimeException("no status here"),
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
            ).isYouTubeIdentityBlocked()
        )
    }

    // -------------------------------------------------------------- helpers --

    private fun playbackError(cause: Throwable): PlaybackException =
        PlaybackException(
            "playback failed",
            cause,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        )

    private fun forbidden(
        url: String,
        responseCode: Int = 403
    ): HttpDataSource.InvalidResponseCodeException =
        HttpDataSource.InvalidResponseCodeException(
            responseCode,
            if (responseCode == 403) "Forbidden" else "Not Found",
            null,
            emptyMap(),
            DataSpec(Uri.parse(url)),
            ByteArray(0)
        )

    private companion object {
        /**
         * The shape of a signed googlevideo URL, trimmed: the classifier reads
         * only the host, and the signature params are per-request anyway.
         */
        const val GOOGLEVIDEO_URL =
            "https://rr3---sn-abc.googlevideo.com/videoplayback?expire=1800000000&id=gc0t8AfMP6k"
    }
}
