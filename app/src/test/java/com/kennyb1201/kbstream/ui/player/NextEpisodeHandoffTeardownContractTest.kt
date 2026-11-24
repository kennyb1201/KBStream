package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That the Up Next handoff cannot be delivered by a session that has ended, and
 * cannot adopt a show it does not belong to.
 *
 * [com.kennyb1201.kbstream.data.player.PlayerEngineTest] pins the identity rule
 * itself. This pins the two wirings the 2026-11-23 field report needs, both of
 * which only exist at runtime:
 *
 *  - MPV's countdown is a main-looper Handler tick that HANDS OFF when it
 *    reaches zero. It has to die with the session (`onStop`, and `onDestroy` as
 *    the net), and it has to survive Picture-in-Picture, which calls `onPause`
 *    without a stop - so `onPause` must NOT cancel it;
 *  - the player-result callback in MainActivity has to ask the identity rule in
 *    BOTH of its handoff shapes (the persisted ticket and the external engine's
 *    result extras) before it builds anything from the live request.
 *
 * Read from the source: one is an Activity lifecycle method, the other is an
 * activity-result callback inside a composable. Neither can be driven from a JVM
 * test.
 */
class NextEpisodeHandoffTeardownContractTest {

    private val mpv: String by lazy { source(MPV) }
    private val main: String by lazy { source(MAIN) }

    @Test
    fun `the mpv countdown cannot outlive the session that armed it`() {
        val onStop = method(mpv, "override fun onStop() {")
        val onDestroy = method(mpv, "override fun onDestroy() {")

        assertTrue(
            "leaving the screen must cancel the Up Next countdown, or it hands off from " +
                "behind the player and persists a handoff nobody is waiting for",
            onStop.contains("nextUpCountdownHandler.removeCallbacks(nextUpCountdownRunnable)")
        )
        assertTrue(
            "and a session destroyed without a stop of its own must not keep it either",
            onDestroy.contains("nextUpCountdownHandler.removeCallbacks(nextUpCountdownRunnable)")
        )

        // PiP enters through onPause without a stop, and the episode must still
        // advance in the corner - exactly as the main player's countdown does.
        val onPause = method(mpv, "override fun onPause() {")
        assertFalse(
            "onPause is Picture-in-Picture too: cancelling there kills the advance in the corner",
            onPause.contains("nextUpCountdownHandler.removeCallbacks(")
        )
    }

    @Test
    fun `the doc lists the teardown it now really does`() {
        val doc = mpv.substring(
            mpv.indexOf("* Disarms the Up Next countdown."),
            mpv.indexOf("private fun cancelNextUpAutoAdvance()")
        )
        assertTrue(
            "the doc used to claim a teardown this engine did not have; it must name onStop",
            doc.contains("[onStop]") && doc.contains("[onDestroy]")
        )
        assertFalse(
            "and it must not keep the old claim that the switch handoff was the only caller",
            doc.contains("and from nothing else yet")
        )
    }

    @Test
    fun `both handoff shapes are checked against the live session before they are built`() {
        val callback = main.substring(
            main.indexOf("val playerResultLauncher"),
            main.indexOf("\"play_now\" ->")
        )

        assertEquals(
            "the persisted ticket AND the external engine's result extras must each ask the rule",
            2,
            Regex(Regex.escape("PlayerEngine.nextEpisodeHandoffMatches(")).findAll(callback).count()
        )
        assertEquals(
            "and each mismatch returns out of the callback, so nothing is built from the " +
                "wrong show: no pendingAutoPlay, no Streams screen",
            2,
            Regex(Regex.escape("return@rememberLauncherForActivityResult")).findAll(callback).count()
        )
        assertEquals(
            "the drop is visible in logcat, with ids only",
            2,
            Regex(Regex.escape("next-episode handoff dropped: pending show="))
                .findAll(callback)
                .count()
        )
        assertTrue(
            "the check reads the live request's parent and the handoff's own stream id",
            callback.contains("pendingStreamId = next.streamId,") &&
                callback.contains("pendingStreamId = nextStreamId.orEmpty(),") &&
                callback.contains("liveParentId = current.parentId")
        )
    }

    /** The text of the member that starts at [signature], up to its closing brace. */
    private fun method(src: String, signature: String): String {
        val start = src.indexOf(signature)
        assertTrue("source missing member: $signature", start >= 0)
        val rest = src.substring(start + signature.length)
        val end = rest.indexOf("\n    }")
        return if (end < 0) rest else rest.substring(0, end)
    }

    private fun source(relative: String): String {
        val file = File(findSourceRoot(), relative)
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

    private companion object {
        const val MAIN = "com/kennyb1201/kbstream/MainActivity.kt"
        const val MPV = "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
    }
}
