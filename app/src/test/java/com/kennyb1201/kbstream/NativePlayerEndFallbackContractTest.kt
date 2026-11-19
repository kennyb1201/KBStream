package com.kennyb1201.kbstream

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Exo end-of-episode deadlock (spec 2026-11-19, F1 and F2).
 *
 * The report: the Up Next card sat on "Paused - playing next when you resume"
 * with a second left and Play did nothing, on the TCL. Three legs made that
 * state: the stall fallback latched the episode ended AND paused the player
 * ("freeze the clock"), the countdown holds while `playWhenReady` is false, and
 * the ended latch refuses every play press. F1 removes the pause - the latch
 * alone is what stops the fallback re-firing - so the countdown keeps ticking
 * and hands off as it does after a real ENDED.
 *
 * What the fix must not break is pinned here too: the countdown's hold (that IS
 * the viewer-pause protection), the Play guards, and the fallback's refusal to
 * read a paused player as a completion signal.
 *
 * The third leg F1 needs is the idempotence guard in `onPlaybackEnded`. With the
 * pause gone the file really reaches its end, so ENDED's listener calls the
 * completion path a second time: without the guard the ending was filed and
 * scrobbled twice.
 *
 * A source-level contract test in the established style (see
 * [ActorReturnToPlayerContractTest], `AudioFocusParityContractTest`): the
 * fallback is private and the deadlock is a three-way interaction with a live
 * player, so the behavioural gate is the spec's field acceptance, not a JVM test
 * that pretends to run Exo.
 */
class NativePlayerEndFallbackContractTest {

    private val sourceRoot: File by lazy { findSourceRoot() }

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

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun stripComments(text: String): String =
        text.lineSequence()
            .joinToString("\n") { line ->
                val marker = line.indexOf("//")
                if (marker < 0) line else line.substring(0, marker)
            }

    private fun squash(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    private val native = squash(
        stripComments(source("com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"))
    )

    private fun slice(text: String, from: String, to: String): String {
        val start = text.indexOf(from)
        assertTrue("missing anchor: $from", start >= 0)
        val end = text.indexOf(to, start + from.length)
        assertTrue("missing end anchor: $to", end > start)
        return text.substring(start, end)
    }

    private fun fallbackBody(): String = slice(
        native,
        "private fun detectStallEndedFallback(",
        "private val introStampRunnable = object : Runnable {"
    )

    // ── F1: the pause is gone, the latch is not ───────────────────────────

    @Test
    fun `the fallback latches the end without pausing the player`() {
        val fallback = fallbackBody()
        assertTrue(
            "the latch stays: it is what stops the fallback re-firing on later ticks",
            fallback.contains("playbackEndedHandled = true")
        )
        assertTrue(
            "and the end still routes through the completion path",
            fallback.contains("onPlaybackEnded()")
        )
        assertFalse(
            "no pause at the tail. The ended latch refuses every play press, so a pause " +
                "taken here can never be resumed - and the countdown holds while paused, " +
                "which is the 'Paused - playing next when you resume' deadlock",
            fallback.contains(".pause()")
        )
    }

    @Test
    fun `a viewer pause is still never read as the end`() {
        assertTrue(
            "the isPlaying early return stays, and still clears the stall counter: a " +
                "paused session must never latch itself finished",
            fallbackBody().contains(
                "if (!player.isPlaying) { lastPolledPos = -1L posStallTicks = 0 return }"
            )
        )
    }

    // ── The viewer-pause protection F1 must not break ─────────────────────

    @Test
    fun `the countdown still holds for a paused session`() {
        val countdown = slice(
            native,
            "private val nextUpCountdownRunnable = object : Runnable {",
            "private var scope: CoroutineScope? = null"
        )
        assertTrue(
            "the hold reads playWhenReady, not isPlaying, so a rebuffer does not freeze it",
            countdown.contains("if (exoPlayer?.playWhenReady != true) {")
        )
        assertTrue(
            "and it says so - the exact text the field report quoted",
            countdown.contains("Paused - playing next when you resume")
        )
    }

    @Test
    fun `a finished session still refuses a play press`() {
        val toggle = slice(native, "private fun togglePlayPause()", "private fun ")
        assertTrue(
            "the toggle returns early while the latch is set",
            toggle.contains("if (playbackEndedHandled) return")
        )
        assertTrue(
            "and both MEDIA_PLAY arms swallow the press, one per key handler",
            native.contains("if (playbackEndedHandled) true else {") &&
                native.contains("if (playbackEndedHandled) return true")
        )
    }

    // ── The leg F1 adds: the completion runs once per episode ─────────────

    @Test
    fun `the completion runs once per episode`() {
        val ended = slice(
            native,
            "private fun onPlaybackEnded()",
            "private fun routeAfterCompletedEnd()"
        )
        val guard = ended.indexOf("if (playbackEndReached) return")
        assertTrue(
            "the episode-finished latch is the gate. playbackEndedHandled cannot be: a " +
                "seek clears it so the ending can be rewatched, which makes it unable to " +
                "tell a repeat caller from a second ending",
            guard >= 0
        )
        assertTrue(
            "and it is read before any side effect, so a repeat caller changes nothing",
            guard < ended.indexOf("scrobbleSimkl(\"stop\"") &&
                guard < ended.indexOf("saveProgress(reason = \"ended\"")
        )
    }

    @Test
    fun `arming the countdown does not touch the player`() {
        val arm = slice(
            native,
            "private fun armNextUpAutoAdvance(",
            "private fun maybeStartHeldNextUpCountdown("
        )
        assertFalse(
            "F1 removes the pause instead of resuming on arm: resuming a latched session " +
                "to make the countdown tick is the state the Play guard refuses, and it " +
                "would need a flag to tell our pause from the viewer's",
            arm.contains("playWhenReady") || arm.contains(".pause()") || arm.contains(".play()")
        )
    }
}
