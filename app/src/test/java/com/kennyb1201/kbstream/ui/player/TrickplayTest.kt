package com.kennyb1201.kbstream.ui.player

import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
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
 * stream on every press. None of those is visible in a build that compiles,
 * and neither is the reason a failed extraction leaves behind: "I never see a
 * thumbnail" is one sentence covering three faults that want opposite fixes.
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
    fun `a card anchored on the thumb is centered on it`() {
        // The card is laid out centered already, so the translation is simply how
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
    fun `a card wider than the screen is centered rather than clamped`() {
        assertEquals(0f, trickplayAnchorTranslation(500f, 400, 600, 16f), 0.01f)
        assertEquals(0f, trickplayAnchorTranslation(500f, 0, 480, 16f), 0.01f)
        assertEquals(0f, trickplayAnchorTranslation(500f, 1920, 0, 16f), 0.01f)
    }

    // ── when the preview decoder may be given back ───────────────────────────

    @Test
    fun `an extraction in flight holds the preview decoder`() {
        // The release is armed when the scrub key comes up, which is before the
        // frame that press asked for has been decoded. Releasing there cancels
        // the request and its timeout together, so the frame is neither shown
        // nor counted: a press that leaves no card, no notice and no line in
        // the report.
        assertFalse(trickplayMayRelease(awaitingFrame = true))
    }

    @Test
    fun `an idle pipeline gives the preview decoder back`() {
        assertTrue(trickplayMayRelease(awaitingFrame = false))
    }

    @Test
    fun `the card outlives the decode that press is allowed`() {
        // A frame may take TRICKPLAY_TIMEOUT_MS to arrive and then shows for
        // TRICKPLAY_SHOW_GRACE_MS from the draw, so the press has to arm the
        // card for the two together. Arm it for less and a frame the pipeline
        // was still entitled to deliver draws onto a card that has already
        // closed - the fault the report counts as `late=`.
        assertTrue(TRICKPLAY_WAIT_MS >= TRICKPLAY_TIMEOUT_MS + TRICKPLAY_SHOW_GRACE_MS)
    }

    // ── why an extraction produced nothing ───────────────────────────────────

    @Test
    fun `a timed-out extraction names the player it was waiting on`() {
        val reason = trickplayTimeoutReason(
            timeoutMs = TRICKPLAY_TIMEOUT_MS,
            playerState = trickplayPlayerState(Player.STATE_BUFFERING),
            loading = true,
            positionMs = 12_000L,
            bucketMs = 120_000L,
            imagesSeen = 0,
            playerError = null
        )
        // The case a bare "timed out" hides: a second player that is still
        // buffering a minute short of the bucket it was asked for. Nothing about
        // the screen can explain that; the source can.
        assertTrue(reason.contains("player=buffering"))
        assertTrue(reason.contains("loading=true"))
        assertTrue(reason.contains("position=12000"))
        assertTrue(reason.contains("bucket=120000"))
        assertTrue(reason.contains("error=none"))
    }

    @Test
    fun `a capture surface that never rendered is distinguishable from a refused frame`() {
        val never = trickplayTimeoutReason(
            timeoutMs = TRICKPLAY_TIMEOUT_MS,
            playerState = trickplayPlayerState(Player.STATE_READY),
            loading = false,
            positionMs = 120_000L,
            bucketMs = 120_000L,
            imagesSeen = 0,
            playerError = null
        )
        // Ready at the right position with nothing captured at all: the decoder
        // is producing no frames, which is not a source fault and not a gate in
        // onImageAvailable either.
        assertTrue(never.contains("player=ready"))
        assertTrue(never.contains("images=0"))

        val refused = trickplayTimeoutReason(
            timeoutMs = TRICKPLAY_TIMEOUT_MS,
            playerState = trickplayPlayerState(Player.STATE_READY),
            loading = false,
            positionMs = 128_000L,
            bucketMs = 120_000L,
            imagesSeen = 7,
            playerError = null
        )
        // Frames did arrive and were not filed: the surface works, the seek
        // landed 8 s past the bucket, and the accept window refused it.
        assertTrue(refused.contains("images=7"))
    }

    @Test
    fun `an extraction with no player built says none, not idle`() {
        val reason = trickplayTimeoutReason(
            timeoutMs = TRICKPLAY_TIMEOUT_MS,
            playerState = trickplayPlayerState(null),
            loading = false,
            positionMs = null,
            bucketMs = null,
            imagesSeen = 0,
            playerError = null
        )
        assertTrue(reason.contains("player=none"))
        assertTrue(reason.contains("position=none"))
        assertTrue(reason.contains("bucket=none"))
    }

    @Test
    fun `a player error is carried into the reason`() {
        val reason = trickplayTimeoutReason(
            timeoutMs = TRICKPLAY_TIMEOUT_MS,
            playerState = trickplayPlayerState(Player.STATE_IDLE),
            loading = false,
            positionMs = 0L,
            bucketMs = 0L,
            imagesSeen = 0,
            playerError = "ERROR_CODE_IO_NETWORK_CONNECTION_FAILED"
        )
        assertTrue(reason.contains("error=ERROR_CODE_IO_NETWORK_CONNECTION_FAILED"))
    }

    @Test
    fun `every player state the preview can be in has a name`() {
        assertEquals("none", trickplayPlayerState(null))
        assertEquals("idle", trickplayPlayerState(Player.STATE_IDLE))
        assertEquals("buffering", trickplayPlayerState(Player.STATE_BUFFERING))
        assertEquals("ready", trickplayPlayerState(Player.STATE_READY))
        assertEquals("ended", trickplayPlayerState(Player.STATE_ENDED))
        assertEquals("unknown", trickplayPlayerState(-1))
    }

    // ── decoding a capture down to a card ──────────────────────────────────

    @Test
    fun `a full resolution capture is decoded at a fraction`() {
        // The libmpv path screenshots at the file's own resolution, so this is
        // the difference between a 0.5 MB frame and a 31 MB one on a box whose
        // whole Java heap is 192 MB.
        assertEquals(8, trickplaySampleSize(3840, 2160))
        assertEquals(4, trickplaySampleSize(1920, 1080))
        assertEquals(2, trickplaySampleSize(1280, 720))
    }

    @Test
    fun `the sample is the widest fraction that still fills the card`() {
        // Not merely "small enough": a frame narrower than the card would be
        // upscaled by the overlay, so the closest power of two on the safe side
        // is the one that keeps the frame sharp for what it costs.
        val widths = listOf(3840, 2560, 1920, 1440, 1280, 1024, 960, 854, 720, 640, 480, 320)

        for (width in widths) {
            val sample = trickplaySampleSize(width, width * 9 / 16)
            assertTrue(
                "sample for $width must be a power of two, was $sample",
                sample > 0 && (sample and (sample - 1)) == 0
            )
            // A source narrower than the card is sampled whole and upscaled by
            // the overlay: there is nothing to discard without throwing pixels
            // away, so the rule is "never sample below the card unless the
            // source is already below it".
            assertTrue(
                "$width/$sample is narrower than the card",
                width / sample >= TRICKPLAY_CAPTURE_WIDTH || width < TRICKPLAY_CAPTURE_WIDTH
            )
            if (sample > 1) {
                assertTrue(
                    "$width would have fit at ${sample * 2}",
                    width / (sample * 2) < TRICKPLAY_CAPTURE_WIDTH
                )
            }
        }
    }

    @Test
    fun `a source at or below the card size is not sampled away`() {
        assertEquals(1, trickplaySampleSize(TRICKPLAY_CAPTURE_WIDTH, TRICKPLAY_CAPTURE_HEIGHT))
        assertEquals(1, trickplaySampleSize(320, 180))
    }

    @Test
    fun `nonsense dimensions decode whole rather than not at all`() {
        // A capture whose bounds did not parse must still produce a bitmap: the
        // alternative here is a preview that silently never appears.
        assertEquals(1, trickplaySampleSize(0, 0))
        assertEquals(1, trickplaySampleSize(-1920, 1080))
        assertEquals(1, trickplaySampleSize(1920, 1080, targetWidth = 0))
    }
}
