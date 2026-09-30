package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A player the TV's launcher took away must be put back when the viewer
 * returns.
 *
 * The fullscreen player is a separate Activity and the remote's Home button
 * STOPS it — the task, and the Activity instance, survive. The native engine
 * releases everything at that stop (deliberately: this box hands out one 4K
 * decode per process, so a backgrounded player holding it is what leaves the
 * Home hero's pooled trailer with no decoder to prepare in), and for a long
 * time nothing built it again. What the viewer got was a screen that looked
 * alive — overlay, last frame, working list of sources — whose play and restart
 * presses did nothing, because the only path that builds an ExoPlayer is a
 * source switch. Hence the report this pins: "if I go to the TV's home screen
 * and come back, I have to change sources before the file plays again".
 *
 * The rule itself is [shouldRebuildAfterStop] and is tested in
 * [PlayerRebuildTest]. What cannot be tested there is the wiring: that onStart
 * asks, that onStop is what marks the teardown, and that the rebuild the
 * activity performs is the one that builds a player. Reading the source is how
 * this repo pins that (see PlayerGuideWriteGateContractTest), because a missing
 * call here compiles cleanly and is invisible until someone tries to resume a
 * film on a television.
 *
 * MpvPlayerActivity is checked the other way round: it pauses rather than
 * tears down, so it must NOT grow a rebuild of its own — a second ExoPlayer
 * started behind a paused mpv instance is the decoder fight this is careful to
 * avoid.
 */
class PlayerBackgroundReturnContractTest {

    private companion object {
        const val ON_START = "override fun onStart() {"
        const val ON_STOP = "override fun onStop() {"
        const val ON_PAUSE = "override fun onPause() {"
        const val ON_DESTROY = "override fun onDestroy() {"
        const val RESUME = "resumeAfterBackgroundReturn()"
        const val MARKER = "playerTornDownAtStop = true"
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

    private fun source(activity: String): String {
        val file = File(playerDir, "$activity.kt")
        assertTrue("player source missing: $file", file.isFile)
        return file.readText()
    }

    /** The body of one lifecycle method, up to the next one. */
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
    fun `the native player is asked to rebuild whenever it comes back`() {
        val native = source("NativePlayerActivity")
        assertTrue(
            "NativePlayerActivity.onStart does not call $RESUME, so a screen " +
                "returned to from the TV launcher stays a player with no player",
            bodyBetween(native, ON_START, ON_PAUSE).contains(RESUME)
        )
    }

    @Test
    fun `the return path rebuilds through the one path that builds a player`() {
        val rebuild = sliceFrom(source("NativePlayerActivity"), "private fun $RESUME")
        assertTrue(
            "the return path must build a player with recreatePlayer(), the same " +
                "one a source switch uses",
            rebuild.contains("recreatePlayer()")
        )
        assertTrue(
            "the return path must take the guide-write gate back, or bulk " +
                "background work resumes under a playing video",
            rebuild.contains("EpgWriteGate.setPlayerActive(true)")
        )
        assertTrue(
            "the return path must keep the playhead onStop carried",
            rebuild.contains("carryPositionMs")
        )
    }

    @Test
    fun `onStop is what marks the teardown the return answers`() {
        val native = source("NativePlayerActivity")
        assertTrue(
            "NativePlayerActivity.onStop must set the marker the return reads; " +
                "without it onStart cannot tell a return from the launch",
            bodyBetween(native, ON_STOP, ON_DESTROY).contains(MARKER)
        )
    }

    @Test
    fun `the mpv engine pauses instead and must not grow a rebuild`() {
        val mpv = source("MpvPlayerActivity")
        assertTrue(
            "MpvPlayerActivity.onStop no longer pauses its surface. If it now " +
                "releases, it needs the same return path the native player has",
            bodyBetween(mpv, ON_STOP, ON_DESTROY).contains("setPaused(true)")
        )
    }
}
