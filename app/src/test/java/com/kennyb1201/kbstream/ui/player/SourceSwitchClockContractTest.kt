package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A source switch must not carry a broken source's clock.
 *
 * `switchToSource` used to carry the old player's `currentPosition`
 * unconditionally. ExoPlayer's position advances on wall-clock time whenever
 * the player sits in READY with `playWhenReady`, even when no frame has been
 * rendered - the black-video / endless-buffering failure mode this app already
 * tracks with `firstFrameRendered`. So switching away from a source that never
 * opened started the new source N seconds in, where N was roughly how long the
 * viewer had waited on the dead one.
 *
 * The fix gates the carry on `firstFrameRendered`: a source that rendered video
 * hands the new one its playhead (mid-playback switches are unchanged), and a
 * source that never did hands it the LAST KNOWN carry instead. `carryPositionMs`
 * starts at the session's start/resume point, so the first switch from a
 * never-rendered source still lands on that point - but a later switch in a
 * multi-source fallback keeps the position a previous source already carried,
 * instead of the frozen launch position (`startPositionMs`), which restarted the
 * fallback at 0.
 *
 * This is wiring inside an Android activity, so a unit test cannot drive it
 * without a TV in the room. What it can pin is the branch itself: the gate on
 * `firstFrameRendered`, the `startPositionMs` fallback, the live-channel zero,
 * and the per-attempt reset in `createPlayer` that makes the gate meaningful.
 */
class SourceSwitchClockContractTest {

    private fun readSource(path: String): String {
        val file = File(findSourceRoot(), path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

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

    /**
     * The text of the function starting at [signature] up to the next member.
     * The next member is a line indented by exactly four spaces - not the first
     * "newline + spaces", which the body's own deeper indent would match.
     */
    private fun functionBody(source: String, signature: String): String {
        val src = readSource(source)
        val start = src.indexOf(signature)
        assertTrue("source missing function: $signature", start >= 0)
        val rest = src.substring(start + signature.length)
        val end = Regex("\\n {4}\\S").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    private val nativeSwitch: String by lazy {
        functionBody(NATIVE, "fun switchToSource(stream: Stream, isAutoRecovery: Boolean = false) {")
    }

    @Test
    fun `the carry is gated on a frame actually having rendered`() {
        assertTrue(
            "switchToSource must gate the carried playhead on firstFrameRendered",
            nativeSwitch.contains("carryPositionMs = if (isLiveChannel) {")
        )
        assertTrue(
            "a source that rendered a frame must still carry currentPosition",
            nativeSwitch.contains("} else if (firstFrameRendered) {")
        )
        assertTrue(
            "the rendered branch is the old player's own position",
            nativeSwitch.contains("exoPlayer?.currentPosition?.coerceAtLeast(0L) ?: 0L")
        )
    }

    @Test
    fun `a source that never rendered falls back to the last known carry`() {
        assertTrue(
            "no frame rendered must hand the new source the last known carry",
            nativeSwitch.contains("carryPositionMs.coerceAtLeast(0L)")
        )
        assertFalse(
            "the frozen launch point must not restart a multi-source fallback at 0",
            nativeSwitch.contains("startPositionMs.coerceAtLeast(0L)")
        )
        // The gate must be checked before the position is carried: the reset
        // lives in createPlayer() and runs after this line.
        val gate = nativeSwitch.indexOf("} else if (firstFrameRendered) {")
        val fallback = nativeSwitch.indexOf("carryPositionMs.coerceAtLeast(0L)")
        assertTrue("the frame gate must precede the fallback", gate in 0 until fallback)
    }

    @Test
    fun `a live channel zap still starts at zero`() {
        assertTrue(
            "live zaps must not carry a clock",
            nativeSwitch.contains("carryPositionMs = if (isLiveChannel) {\n            0L")
        )
    }

    @Test
    fun `createPlayer re-arms the frame latch for each attempt`() {
        val body = functionBody(NATIVE, "private fun createPlayer() {")
        assertTrue(
            "createPlayer must reset firstFrameRendered so the new source re-arms it",
            body.contains("firstFrameRendered = false")
        )
    }

    @Test
    fun `the reader is not the engine-handoff carry`() {
        // carriedPositionMs() is a different path with its own fallback chain;
        // this fix must not have leaked into it.
        val body = functionBody(NATIVE, "private fun carriedPositionMs()")
        assertFalse(
            "the engine-handoff carry must keep its own chain",
            body.contains("firstFrameRendered")
        )
    }

    @Test
    fun `mpv already falls back to the session start`() {
        val body = functionBody(MPV, "private fun switchToSource(stream: Stream, isAutoRecovery: Boolean = false) {")
        assertTrue(
            "mpv keeps its startPositionMs fallback when the clock never advanced",
            body.contains("if (positionMs > 0L) positionMs else startPositionMs")
        )
    }

    private companion object {
        private const val NATIVE = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        private const val MPV = "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
    }
}
