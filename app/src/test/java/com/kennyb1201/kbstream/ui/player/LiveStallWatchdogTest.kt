package com.kennyb1201.kbstream.ui.player

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The live-channel keep-alive, decided without a TV in the room.
 *
 * The report this pins is a live channel left running overnight: "I fall asleep
 * to my IPTV and I expect it to be still playing when I wake up, but it isn't —
 * it's jammed and looks paused, and changing channels starts it again." A zap
 * starts it because a zap builds a new player, which is exactly what a re-tune
 * is; what was missing was anything at all watching a live session. The VOD
 * stall watchdog returns immediately for live and the retry ladder only runs
 * off a hard error, so a server that goes quiet leaves a frozen frame and
 * nothing else, forever.
 *
 * Every IGNORE case below is a session this recovery must NOT touch, and the
 * interesting ones are the ones that would make the bug worse:
 *
 *  - a live stream refilling a drained buffer GROWS its buffer while its
 *    playhead stands still, so being quiet at the playhead is not enough to
 *    re-tune on;
 *  - a reconnect already in flight owns the session, and a second rebuild
 *    fights the first for the one video decoder this class of box hands out;
 *  - a channel the viewer paused is not a broken one.
 */
class LiveStallWatchdogTest {

    /** A live session that has been playing happily for a while. */
    private fun action(
        live: Boolean = true,
        finishing: Boolean = false,
        reconnecting: Boolean = false,
        playWhenReady: Boolean = true,
        playbackState: Int = Player.STATE_READY,
        firstFrameRendered: Boolean = true,
        positionMs: Long = 100_000L,
        bufferedMs: Long = 104_000L,
        lastPositionMs: Long = 100_000L,
        lastBufferedMs: Long = 104_000L,
        quietMs: Long = 0L
    ) = liveWatchdogAction(
        live = live,
        finishing = finishing,
        reconnecting = reconnecting,
        playWhenReady = playWhenReady,
        playbackState = playbackState,
        firstFrameRendered = firstFrameRendered,
        positionMs = positionMs,
        bufferedMs = bufferedMs,
        lastPositionMs = lastPositionMs,
        lastBufferedMs = lastBufferedMs,
        quietMs = quietMs
    )

    // --- The recovery the report asks for --------------------------------

    @Test
    fun `a live channel with nothing moving is re-tuned`() {
        assertEquals(
            LiveWatchdogAction.RETUNE,
            action(quietMs = LIVE_STALL_NO_PROGRESS_MS)
        )
    }

    @Test
    fun `a frozen playhead with a growing buffer is waited out, not re-tuned`() {
        // The live stream is refilling: discarding this buffer is what turns a
        // slow source into a dead one.
        assertEquals(
            LiveWatchdogAction.KEEP_WAITING,
            action(
                positionMs = 100_000L,
                bufferedMs = 110_000L,
                lastPositionMs = 100_000L,
                lastBufferedMs = 104_000L,
                quietMs = LIVE_STALL_NO_PROGRESS_MS * 2
            )
        )
    }

    @Test
    fun `a playhead that is moving is always waited out`() {
        assertEquals(
            LiveWatchdogAction.KEEP_WAITING,
            action(
                positionMs = 101_000L,
                lastPositionMs = 100_000L,
                quietMs = LIVE_STALL_NO_PROGRESS_MS * 10
            )
        )
    }

    @Test
    fun `a quiet window that has not run out yet only re-posts`() {
        assertEquals(
            LiveWatchdogAction.KEEP_WAITING,
            action(quietMs = LIVE_STALL_NO_PROGRESS_MS - 1L)
        )
        assertEquals(
            LiveWatchdogAction.KEEP_WAITING,
            action(quietMs = 0L)
        )
    }

    @Test
    fun `a stalled channel still buffering is watched the same way`() {
        // BUFFERING is the state a dead live source actually sits in: no error
        // is raised, the connection is simply quiet.
        assertEquals(
            LiveWatchdogAction.RETUNE,
            action(playbackState = Player.STATE_BUFFERING, quietMs = LIVE_STALL_NO_PROGRESS_MS)
        )
    }

    // --- Sessions it must leave alone ------------------------------------

    @Test
    fun `a VOD session is left to the stall watchdog`() {
        assertEquals(
            LiveWatchdogAction.IGNORE,
            action(live = false, quietMs = LIVE_STALL_NO_PROGRESS_MS * 5)
        )
    }

    @Test
    fun `a screen on its way out is never re-tuned`() {
        assertEquals(
            LiveWatchdogAction.IGNORE,
            action(finishing = true, quietMs = LIVE_STALL_NO_PROGRESS_MS * 5)
        )
    }

    @Test
    fun `a reconnect already in flight is left to finish`() {
        assertEquals(
            LiveWatchdogAction.IGNORE,
            action(reconnecting = true, quietMs = LIVE_STALL_NO_PROGRESS_MS * 5)
        )
    }

    @Test
    fun `a live channel the viewer paused is not a broken one`() {
        assertEquals(
            LiveWatchdogAction.IGNORE,
            action(playWhenReady = false, quietMs = LIVE_STALL_NO_PROGRESS_MS * 5)
        )
    }

    @Test
    fun `IDLE and ENDED belong to the ladder and the end paths`() {
        assertEquals(
            LiveWatchdogAction.IGNORE,
            action(playbackState = Player.STATE_IDLE, quietMs = LIVE_STALL_NO_PROGRESS_MS * 5)
        )
        assertEquals(
            LiveWatchdogAction.IGNORE,
            action(playbackState = Player.STATE_ENDED, quietMs = LIVE_STALL_NO_PROGRESS_MS * 5)
        )
    }

    @Test
    fun `a first frame that has not been painted yet is the startup watchdog's business`() {
        // A live channel's first bytes can legitimately take a long time; the
        // startup watchdog owns the never-started session.
        assertEquals(
            LiveWatchdogAction.IGNORE,
            action(firstFrameRendered = false, quietMs = LIVE_STALL_NO_PROGRESS_MS * 5)
        )
    }

    // --- Movement, on its own --------------------------------------------

    @Test
    fun `movement is measured in either the playhead or the buffer`() {
        assertEquals(false, liveProgressed(100_000L, 104_000L, 100_000L, 104_000L))
        assertEquals(true, liveProgressed(100_600L, 104_000L, 100_000L, 104_000L))
        assertEquals(true, liveProgressed(100_000L, 104_600L, 100_000L, 104_000L))
    }

    @Test
    fun `a step under the threshold is not movement`() {
        assertEquals(false, liveProgressed(100_400L, 104_400L, 100_000L, 104_000L))
    }

    @Test
    fun `a seek backwards is not movement`() {
        assertEquals(false, liveProgressed(90_000L, 90_000L, 100_000L, 104_000L))
    }

    // --- The spent ladder -------------------------------------------------

    @Test
    fun `a live channel's spent ladder rolls over instead of parking`() {
        assertEquals(5, retryLoopRung(live = true, rungCount = 6))
        assertEquals(0, retryLoopRung(live = true, rungCount = 1))
    }

    @Test
    fun `VOD still parks on the error card`() {
        assertEquals(-1, retryLoopRung(live = false, rungCount = 6))
    }

    @Test
    fun `a ladder with no rungs at all cannot be looped`() {
        assertEquals(-1, retryLoopRung(live = true, rungCount = 0))
    }
}
