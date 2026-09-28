package com.kennyb1201.kbstream.ui.player

import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scrub-preview arithmetic, which is the whole of it that can be decided
 * without a decoder: which frame a drag position means, whether a frame the
 * player just handed over is allowed to be cached as that position, how long
 * the pipeline keeps trying, and what a preview that has been decoded is
 * dropped for when the store is full.
 *
 * The stakes are quiet ones. A wrong bucket shows the viewer a moment they are
 * not at; a stale frame cached under the bucket that asked for the seek shows
 * them the wrong moment for the rest of the session and looks authoritative
 * while doing it; an LRU that evicts the frame just looked at re-opens the
 * stream on every press. None of those is visible in a build that compiles.
 */
class TrickplayTest {

    private val bucket = TRICKPLAY_BUCKET_MS

    // ── which frame a position means ─────────────────────────────────────────

    @Test
    fun `a position inside a bucket belongs to the bucket below it`() {
        assertEquals(0L, trickplayBucket(0L))
        assertEquals(0L, trickplayBucket(1L))
        assertEquals(0L, trickplayBucket(bucket - 1L))
        assertEquals(bucket, trickplayBucket(bucket))
        assertEquals(bucket, trickplayBucket(bucket + 500L))
        assertEquals(bucket * 2, trickplayBucket(bucket * 2 + 5_000L))
        assertEquals(bucket * 3, trickplayBucket(bucket * 3 + 9_999L))
    }

    @Test
    fun `floored rather than rounded, so a preview never runs ahead of the drag`() {
        // 19 s is inside the second bucket: rounding would ask the decoder for
        // the frame at 20 s, half a bucket ahead of where the viewer is.
        assertEquals(bucket, trickplayBucket(19_000L))
    }

    @Test
    fun `a position before the start is the first frame, not a negative one`() {
        assertEquals(0L, trickplayBucket(-1L))
        assertEquals(0L, trickplayBucket(-90_000L))
    }

    @Test
    fun `the bucket grid is stable, so repeated drags reuse a cached frame`() {
        val first = trickplayBucket(3_600_000L)
        val again = trickplayBucket(3_604_321L)

        assertEquals(first, again)
    }

    // ── whether a frame may be trusted as the bucket's ───────────────────────

    @Test
    fun `a frame the player is showing at the bucket is the bucket's`() {
        assertTrue(trickplayFrameFits(bucket, bucket))
        assertTrue(trickplayFrameFits(bucket, bucket + TRICKPLAY_ACCEPT_WINDOW_MS))
    }

    @Test
    fun `a keyframe seek lands up to a window away and still counts`() {
        // The preview player asks for the nearest keyframe, so settling short of
        // the bucket is normal, not a stale frame.
        assertTrue(trickplayFrameFits(bucket, bucket - TRICKPLAY_ACCEPT_WINDOW_MS))
        assertTrue(trickplayFrameFits(bucket, bucket + 1_500L))
    }

    @Test
    fun `a position the player has not reached is not the bucket's`() {
        assertFalse(trickplayFrameFits(bucket, bucket + TRICKPLAY_ACCEPT_WINDOW_MS + 1L))
        assertFalse(trickplayFrameFits(bucket, bucket - TRICKPLAY_ACCEPT_WINDOW_MS - 1L))
        assertFalse(trickplayFrameFits(bucket, bucket + bucket))
    }

    // ── when the session gives up ────────────────────────────────────────────

    @Test
    fun `only a streak of failures from the source ends the session`() {
        // Two was the old limit, and one slow first frame reached it: previews
        // were then off for the rest of the film.
        assertFalse(trickplayGivesUp(0))
        assertFalse(trickplayGivesUp(2))
        assertFalse(trickplayGivesUp(TRICKPLAY_MAX_TRANSIENT_FAILURES - 1))
        assertTrue(trickplayGivesUp(TRICKPLAY_MAX_TRANSIENT_FAILURES))
        assertTrue(trickplayGivesUp(TRICKPLAY_MAX_TRANSIENT_FAILURES + 5))
    }

    @Test
    fun `a decoder the device cannot spare is permanent, not a retry`() {
        // The second decoder is what a TV box runs out of, and the answer is not
        // to ask again on the next press: it ends the previews there.
        assertTrue(trickplayPermanentError(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
        assertTrue(trickplayPermanentError(PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED))
        assertTrue(
            trickplayPermanentError(
                PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES
            )
        )
    }

    @Test
    fun `a source that refused is worth another press`() {
        // A 403 from the host, a connection that timed out, a seek past the end
        // of the stream: none of those is the device's fault, so the pipeline
        // stays and the viewer's next press tries again.
        assertFalse(trickplayPermanentError(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS))
        assertFalse(
            trickplayPermanentError(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
        )
        assertFalse(trickplayPermanentError(PlaybackException.ERROR_CODE_UNSPECIFIED))
    }

    @Test
    fun `the card is armed for a whole decode, then for the time it is on screen`() {
        // The press arms it for the decode budget; the frame that arrives
        // re-arms it for the show window from the draw.
        assertEquals(TRICKPLAY_TIMEOUT_MS + TRICKPLAY_SHOW_GRACE_MS, TRICKPLAY_WAIT_MS)
        assertTrue(TRICKPLAY_WAIT_MS > TRICKPLAY_TIMEOUT_MS)
    }

    @Test
    fun `the accept window stays narrower than one scrub step`() {
        // A press moves exactly one bucket. If the window reached that far, the
        // position the viewer has just left would count as the one they are on,
        // and a frame from before the seek could be cached as the frame at it.
        assertTrue(TRICKPLAY_ACCEPT_WINDOW_MS < TRICKPLAY_BUCKET_MS)
        // A long-GOP release settles half a GOP from the bucket, so six seconds
        // covers a 12s GOP - which the previous four-second window did not.
        assertTrue(TRICKPLAY_ACCEPT_WINDOW_MS >= 6_000L)
    }

    // ── the frame store ──────────────────────────────────────────────────────

    @Test
    fun `the store holds what it was told to and no more`() {
        val cache = TrickplayFrameCache<String>(limit = 3)

        cache.put(0L, "a")
        cache.put(bucket, "b")
        cache.put(bucket * 2, "c")
        assertEquals(3, cache.size)

        cache.put(bucket * 3, "d")
        assertEquals(3, cache.size)
        assertNull("the oldest frame goes first", cache.get(0L))
        assertEquals("d", cache.get(bucket * 3))
    }

    @Test
    fun `looking a frame up keeps it, which is what makes scrubbing back free`() {
        val cache = TrickplayFrameCache<String>(limit = 3)
        cache.put(0L, "a")
        cache.put(bucket, "b")
        cache.put(bucket * 2, "c")

        // Dragging back over the first bucket ("a") must not be the frame that
        // pays for the next one.
        assertEquals("a", cache.get(0L))

        cache.put(bucket * 3, "d")
        assertNull(cache.get(bucket))
        assertEquals("a", cache.get(0L))
    }

    @Test
    fun `a re-decoded bucket replaces the frame it had`() {
        val cache = TrickplayFrameCache<String>(limit = 2)
        cache.put(bucket, "stale")
        cache.put(bucket, "fresh")

        assertEquals("fresh", cache.get(bucket))
        assertEquals(1, cache.size)
    }

    @Test
    fun `a cleared store is empty, so a new session decodes from scratch`() {
        val cache = TrickplayFrameCache<String>(limit = 2)
        cache.put(bucket, "a")
        cache.clear()

        assertEquals(0, cache.size)
        assertNull(cache.get(bucket))
    }

    @Test
    fun `a single-frame store still works rather than evicting nothing`() {
        val cache = TrickplayFrameCache<String>(limit = 1)
        cache.put(0L, "a")
        cache.put(bucket, "b")

        assertEquals(1, cache.size)
        assertNull(cache.get(0L))
        assertEquals("b", cache.get(bucket))
    }

    // ── what the preview player is told to play ──────────────────────────────

    @Test
    fun `the caller's resolved container wins`() {
        assertEquals(
            MimeTypes.APPLICATION_MPD,
            trickplayMimeHint("https://host/stream?type=.m3u8", MimeTypes.APPLICATION_MPD)
        )
    }

    @Test
    fun `an extension-less playlist is still a playlist`() {
        assertEquals(
            MimeTypes.APPLICATION_M3U8,
            trickplayMimeHint("https://host/live/stream?type=.m3u8&token=abc", null)
        )
        assertEquals(
            MimeTypes.APPLICATION_M3U8,
            trickplayMimeHint("https://host/hls/channel/master", null)
        )
    }

    @Test
    fun `everything else is left to media3`() {
        assertNull(trickplayMimeHint("https://host/movie.mkv?token=abc", null))
        assertNull(trickplayMimeHint("https://host/movie.mp4", null))
        assertNull(trickplayMimeHint("https://host/movie.mkv", ""))
    }

    // ── where the card sits ──────────────────────────────────────────────────

    @Test
    fun `a card anchored on the thumb is centred on it`() {
        // The card is laid out centred already, so the translation is simply how
        // far the anchor is from the middle of the screen.
        assertEquals(
            0f,
            trickplayAnchorTranslation(960f, 1920, 480, 32f),
            0.01f
        )
        assertEquals(
            240f,
            trickplayAnchorTranslation(1200f, 1920, 480, 32f),
            0.01f
        )
    }

    @Test
    fun `a thumb near an edge leaves the card on screen`() {
        // Half the card plus the margin in from each edge, whatever the anchor.
        val left = trickplayAnchorTranslation(0f, 1920, 480, 32f)
        val right = trickplayAnchorTranslation(1920f, 1920, 480, 32f)

        assertEquals(-688f, left, 0.01f)
        assertEquals(688f, right, 0.01f)
    }

    @Test
    fun `a card wider than the screen is centred rather than clamped`() {
        assertEquals(0f, trickplayAnchorTranslation(500f, 400, 600, 16f), 0.01f)
        assertEquals(0f, trickplayAnchorTranslation(500f, 0, 480, 16f), 0.01f)
        assertEquals(0f, trickplayAnchorTranslation(500f, 1920, 0, 16f), 0.01f)
    }
}
