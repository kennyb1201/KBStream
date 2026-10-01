package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A live channel left running must still be playing in the morning.
 *
 * The report: "I fall asleep to my IPTV and I expect it to be still playing
 * when I wake up, but it's not — it's jammed and looks paused, and if I change
 * channels it starts working again." A zap works because a zap builds a new
 * player, which is what a re-tune is; the gap was that nothing watched a live
 * session at all. The VOD stall watchdog returns immediately for live channels
 * and the retry ladder only ever runs off a hard error, so a server that goes
 * quiet leaves a frozen frame with no error and no retry — for as long as the
 * viewer leaves it running.
 *
 * The decision itself is [liveWatchdogAction] and is tested in
 * [LiveStallWatchdogTest]. What cannot be tested there is the wiring, which is
 * the whole risk: a watchdog that is never armed, or a re-tune that reaches the
 * error card instead of the reconnect ladder, compiles cleanly and is invisible
 * until someone wakes up to a frozen television. Reading the source is how this
 * repo pins that (see PlayerBackgroundReturnContractTest and
 * PlayerGuideWriteGateContractTest).
 */
class LiveChannelSurvivalContractTest {

    private companion object {
        const val MARK_FIRST_FRAME = "private fun markFirstFrameRendered() {"
        const val ARM_STARTUP = "private fun armStartupWatchdog() {"
        const val LISTENER = "private fun createPlayerListener() = object : Player.Listener {"
        const val STATE_CHANGED = "override fun onPlaybackStateChanged"
        const val RECREATE = "private fun recreatePlayer(settleMs: Long = 0L) {"
        const val SCHEDULE_RETRY = "private fun scheduleRetry() {"
        const val PLAYBACK_ENDED = "// --- Playback Ended ---"
        const val TICK_LIVE = "private fun tickLiveWatchdog(token: Int) {"
        const val ARM_LIVE = "private fun armLiveWatchdog() {"
    }

    private val playerDir: File by lazy { findPlayerSourceDir() }

    /**
     * Resolves `…/ui/player` from the test's working directory, which is the
     * module dir under Gradle (`app/`) but the repo root under some runners.
     * Walking up covers both; a miss is loud rather than a silent skip, because
     * a green run that read nothing is worse than no test.
     */
    private fun findPlayerSourceDir(): File {
        val relative = "com/kennyb1201/kbstream/ui/player"
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java/$relative")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "player sources not found walking up from " +
                System.getProperty("user.dir")
        )
    }

    private fun source(): String {
        val file = File(playerDir, "NativePlayerActivity.kt")
        assertTrue("player source missing: $file", file.isFile)
        return file.readText()
    }

    /** The body of one declaration, up to the next one. */
    private fun bodyBetween(src: String, from: String, to: String): String {
        val start = src.indexOf(from)
        assertTrue("$from not found in the activity", start >= 0)
        val end = src.indexOf(to, start + from.length)
        assertTrue("$to not found after $from", end > start)
        return src.substring(start, end)
    }

    private fun sliceFrom(src: String, marker: String, length: Int = 1_200): String {
        val at = src.indexOf(marker)
        assertTrue("$marker not found in the activity", at >= 0)
        return src.substring(at, minOf(src.length, at + length))
    }

    @Test
    fun `the keep-alive starts when a live channel puts a frame on screen`() {
        val frame = bodyBetween(source(), MARK_FIRST_FRAME, ARM_STARTUP)
        assertTrue(
            "markFirstFrameRendered must arm the live keep-alive; without it a " +
                "live channel that goes quiet overnight is watched by nothing",
            frame.contains("armLiveWatchdog()")
        )
    }

    @Test
    fun `the keep-alive is re-armed on every playing session`() {
        val listener = bodyBetween(source(), LISTENER, STATE_CHANGED)
        assertTrue(
            "createPlayerListener must arm the live keep-alive beside the stall " +
                "watchdog, or a session that resumed is never watched again",
            listener.contains("armLiveWatchdog()")
        )
    }

    @Test
    fun `a stall hands the session to the reconnect ladder, not to the error card`() {
        val tick = sliceFrom(source(), TICK_LIVE, length = 4_000)
        assertTrue(
            "the live keep-alive must recover through scheduleRetry(), the same " +
                "reconnect path a hard error uses, so the channel is re-tuned " +
                "against the same URL",
            tick.contains("scheduleRetry()")
        )
        assertTrue(
            "the keep-alive must read its verdict from liveWatchdogAction() " +
                "rather than re-deriving the rule in the activity",
            tick.contains("liveWatchdogAction(")
        )
    }

    @Test
    fun `a spent ladder on a live channel rolls over instead of parking`() {
        val retry = bodyBetween(source(), SCHEDULE_RETRY, PLAYBACK_ENDED)
        assertTrue(
            "scheduleRetry must ask retryLoopRung() whether this session may " +
                "give up, or a live channel left running lands on a card nobody " +
                "is awake to press",
            retry.contains("retryLoopRung(")
        )
        assertTrue(
            "the ladder must still be able to end for VOD: the loop rung is " +
                "what keeps the error card off the live path",
            retry.contains("loopRung < 0")
        )
    }

    @Test
    fun `each rebuild takes the previous session's keep-alive with it`() {
        val recreate = sliceFrom(source(), RECREATE, length = 1_400)
        assertTrue(
            "recreatePlayer must invalidate the live keep-alive token beside the " +
                "stall watchdog's, or a tick from the released player can ask " +
                "for a second rebuild",
            recreate.contains("liveWatchdogToken++")
        )
    }

    @Test
    fun `the keep-alive is only ever armed for a live session`() {
        val arm = sliceFrom(source(), ARM_LIVE, length = 1_400)
        assertTrue(
            "armLiveWatchdog must bail out of a non-live session - the VOD stall " +
                "watchdog owns those",
            arm.contains("if (!isLiveChannel) return")
        )
    }
}
