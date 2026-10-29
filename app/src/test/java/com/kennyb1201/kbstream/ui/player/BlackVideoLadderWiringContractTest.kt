package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The black-video ladder, pinned at the source.
 *
 * Two field problems are what this pins. First, the ladder armed at 3s on a
 * live channel whose low-latency join legitimately waits longer than that for
 * the next IDR, and it re-armed its own surface stage on every rebuild it
 * started - so it rebuilt healthy joins and, after a real one, paid the stage
 * again. Second, nothing armed it at all when a track arrived AFTER READY, so a
 * late live join sat black with no recovery.
 *
 * This is wiring against a real player, so it is checked in the source rather
 * than executed: no unit test can produce a READY player with audio and no
 * frame. What is asserted is the branch structure the behavior depends on.
 */
class BlackVideoLadderWiringContractTest {

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

    private fun readSource(path: String): String {
        val file = File(findSourceRoot(), path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    /** The function starting at [signature] up to the next member indented by four. */
    private fun functionBody(signature: String): String {
        val src = readSource(PLAYER)
        val start = src.indexOf(signature)
        assertTrue("source missing function: $signature", start >= 0)
        val rest = src.substring(start + signature.length)
        val end = Regex("\\n {4}\\S").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    private val source by lazy { readSource(PLAYER) }
    private val armBody by lazy { functionBody("private fun armBlackVideoWatchdog() {") }
    private val ladderBody by lazy {
        functionBody("private fun handleBlackVideoTimeout(token: Int, requirePlaying: Boolean = true) {")
    }
    private val createBody by lazy { functionBody("private fun createPlayer() {") }
    private val switchBody by lazy {
        functionBody("fun switchToSource(stream: Stream, isAutoRecovery: Boolean = false) {")
    }
    private val tracksBody by lazy { functionBody("override fun onTracksChanged(tracks: Tracks) {") }

    /** The live arm is 10s, VOD keeps 3s, and the arm uses the branch. */
    @Test
    fun `the arm duration is live aware and the fire log tells the truth`() {
        assertTrue(source.contains("private val blackVideoWatchdogMs = 3_000L"))
        assertTrue(source.contains("private val blackVideoWatchdogLiveMs = 10_000L"))
        assertTrue(
            "the arm must branch on the content type",
            armBody.contains("if (isLiveChannel) blackVideoWatchdogLiveMs else blackVideoWatchdogMs")
        )
        assertTrue("the arm must post the duration it chose", armBody.contains("}, armedMs)"))
        // The samples the progress check compares against are taken at arm time.
        assertTrue(armBody.contains("blackVideoArmBufferedMs = exoPlayer?.bufferedPosition ?: 0L"))
        assertTrue(armBody.contains("blackVideoArmPositionMs = exoPlayer?.currentPosition ?: 0L"))
        assertTrue(armBody.contains("blackVideoWatchdogArmed = true"))
        // A line that says 3000ms on a 10s arm misleads every future reading.
        assertTrue(
            "the fire log must print the effective duration",
            ladderBody.contains("Black video: no first frame after \${blackVideoArmedMs}ms")
        )
        assertFalse(
            "the fire log must not print the VOD constant",
            ladderBody.contains("no first frame after \${blackVideoWatchdogMs}ms")
        )
    }

    /** A rebuild the ladder started keeps the stage flags; any other one resets them. */
    @Test
    fun `createPlayer preserves ladder stage state and consumes the flag`() {
        assertTrue(
            createBody.contains("if (!blackVideoLadderRebuild) blackVideoSurfaceRetried = false")
        )
        assertTrue(
            "the flag must be consumed, or it leaks into the next attempt",
            createBody.contains("blackVideoLadderRebuild = false")
        )
        // Per attempt, unconditionally.
        assertTrue(createBody.contains("blackVideoProgressRearmDone = false"))
        assertTrue(createBody.contains("blackVideoWatchdogArmed = false"))
        assertTrue(createBody.contains("blackVideoWatchdogToken++"))
    }

    /** Every rebuild this ladder starts is marked, or the surface stage repeats. */
    @Test
    fun `every ladder rebuild is marked before it starts`() {
        val lines = ladderBody.lines()
        val rebuilds = lines.withIndex().filter { (_, line) -> line.contains("recreatePlayer()") }
        assertTrue("the ladder must still rebuild on its later stages", rebuilds.size >= 2)
        rebuilds.forEach { (index, _) ->
            val previous = lines.subList(0, index).lastOrNull { it.isNotBlank() }?.trim()
            assertEquals(
                "a recreatePlayer() in the ladder without the mark re-arms the surface stage",
                "blackVideoLadderRebuild = true",
                previous
            )
        }
    }

    /** A zap starts a full ladder; a non-ladder rebuild does not. */
    @Test
    fun `a source switch resets the whole ladder`() {
        assertTrue(switchBody.contains("blackVideoSurfaceRetried = false"))
        assertTrue(switchBody.contains("blackVideoProgressRearmDone = false"))
        assertTrue(switchBody.contains("blackVideoWatchdogArmed = false"))
        // The stages that were ALREADY zap-scoped stay that way.
        assertTrue(switchBody.contains("forceTextureViewFallback = false"))
        assertTrue(switchBody.contains("dvStripRetryDone = false"))
    }

    /** Progress buys exactly one more window, then the ladder acts. */
    @Test
    fun `the progress check re-arms once and then proceeds`() {
        assertTrue(source.contains("private const val BLACK_VIDEO_PROGRESS_QUANTUM_MS = 500L"))
        assertTrue(
            ladderBody.contains("bufferedNow - blackVideoArmBufferedMs >= BLACK_VIDEO_PROGRESS_QUANTUM_MS")
        )
        assertTrue(
            ladderBody.contains("positionNow - blackVideoArmPositionMs >= BLACK_VIDEO_PROGRESS_QUANTUM_MS")
        )
        assertTrue(
            ladderBody.contains("Black video watchdog: progress since arm, re-arming once")
        )
        // The flag is set BEFORE the re-arm and before any stage can run.
        val flag = ladderBody.indexOf("blackVideoProgressRearmDone = true")
        val reArm = ladderBody.indexOf("armBlackVideoWatchdog()", flag)
        val firstStage = ladderBody.indexOf("Stage 1: bounce the video surface")
        assertTrue("the flag must be set when the re-arm is taken", flag >= 0 && reArm > flag)
        assertTrue("the re-arm must precede every stage", reArm < firstStage)
        // Second fire with no progress: the guard is the flag, so it proceeds.
        assertTrue(ladderBody.contains("if (consumedArm && !blackVideoProgressRearmDone)"))
        assertFalse(
            "the progress quantum must not be a second magic number",
            ladderBody.contains(">= 500L")
        )
    }

    /** Tracks arriving after READY arm the watchdog once. */
    @Test
    fun `a late video track arms the watchdog once`() {
        assertTrue(tracksBody.contains("val trackArrived = !videoTrackPresent"))
        assertTrue(tracksBody.contains("videoTrackPresent = true"))
        val armCall = tracksBody.indexOf("armBlackVideoWatchdog()")
        val guard = tracksBody.indexOf("if (trackArrived &&")
        assertTrue("the arm must be guarded by the transition", guard in 0 until armCall)
        assertTrue(tracksBody.contains("!blackVideoWatchdogArmed"))
        assertTrue(tracksBody.contains("!firstFrameRendered"))
        assertTrue(tracksBody.contains("!blackVideoNoticeShown"))
        assertTrue(tracksBody.contains("exoPlayer?.playbackState == Player.STATE_READY"))
    }

    /** The silent exit explains itself. */
    @Test
    fun `the stood down exit logs one line`() {
        assertTrue(
            ladderBody.contains("Black video watchdog stood down: not playing/ready")
        )
        // Still an exit - the line changes nothing about the behavior.
        val stoodDown = ladderBody.indexOf("Black video watchdog stood down")
        val stageOne = ladderBody.indexOf("Stage 1: bounce the video surface")
        assertTrue(stoodDown >= 0 && stoodDown < stageOne)
        assertTrue(ladderBody.contains("if (!playing || !ready) {"))
    }

    /** The token remains the invalidation authority. */
    @Test
    fun `the token still guards every entry`() {
        assertTrue(ladderBody.contains("if (token != blackVideoWatchdogToken) return"))
        assertTrue(
            "the pending-arm flag is cleared at the top, before any stage",
            ladderBody.indexOf("blackVideoWatchdogArmed = false") in
                0 until ladderBody.indexOf("Stage 1: bounce the video surface")
        )
    }

    private companion object {
        const val PLAYER =
            "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
    }
}
