package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Player M3: both engines resume after an interruption.
 *
 * MPV has owned its own audio focus since 2026-11-17 and resumes on a gain.
 * Native (ExoPlayer) left focus to ExoPlayer's built-in handler, which resumes
 * after a TRANSIENT loss and treats a permanent one as the end of the session -
 * so the same notification ended the two engines differently: MPV came back,
 * Native sat paused until the viewer pressed play. Focus is now the activity's
 * on both engines, one policy, and this pins the parts that made them differ.
 */
class AudioFocusParityContractTest {

    private val sourceRoot: File by lazy { findSourceRoot() }

    private fun findSourceRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
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

    private fun squash(text: String): String = text.replace(Regex("\\s+"), " ")

    private val native = squash(source("com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"))
    private val mpv = squash(source("com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"))

    private fun slice(text: String, from: String, to: String): String {
        val start = text.indexOf(from)
        assertTrue("missing anchor: $from", start >= 0)
        val end = text.indexOf(to, start + from.length)
        assertTrue("missing end anchor: $to", end > start)
        return text.substring(start, end)
    }

    @Test
    fun `ExoPlayer no longer owns the focus`() {
        assertTrue(
            "handleAudioFocus is false: Exo's permanent-loss rule is the one being replaced",
            native.contains("setAudioAttributes(audioAttrs, false)")
        )
        assertFalse(
            "the old hand-off must be gone, or two policies fight over one session",
            native.contains("setAudioAttributes(audioAttrs, true)")
        )
    }

    @Test
    fun `a permanent loss is remembered and a gain resumes exactly that pause`() {
        val focus = slice(
            native,
            "private fun requestAudioFocus()",
            "private fun togglePlayPause("
        )
        assertTrue(
            "a loss that stops a PLAYING session is remembered, once the load guard " +
                "has closed",
            focus.contains("if (player.playWhenReady) {") &&
                focus.contains("if (!hasPlayedOnce || spuriousPauseGuardActive()) {") &&
                focus.contains("} else { pausedByFocusLoss = true") &&
                focus.contains("player.pause()")
        )
        assertTrue(
            "and the gain resumes it - the branch Exo did not have",
            focus.contains("if (pausedByFocusLoss && !playbackEndedHandled) { pausedByFocusLoss = false player.play() }")
        )
        assertTrue(
            "a finished session stays finished",
            focus.contains("!playbackEndedHandled")
        )
    }

    @Test
    fun `the policy matches MPV's, loss for loss`() {
        listOf(
            "AudioManager.AUDIOFOCUS_LOSS," to "a permanent loss",
            "AudioManager.AUDIOFOCUS_LOSS_TRANSIENT ->" to "a transient one",
            "AudioManager.AUDIOFOCUS_GAIN ->" to "and the gain"
        ).forEach { (pattern, why) ->
            assertTrue("Native handles $why", native.contains(pattern))
            assertTrue("MPV handles $why", mpv.contains(pattern))
        }
    }

    @Test
    fun `focus is asked for only while the session is on screen`() {
        assertTrue(
            "asked for on start",
            native.contains(
                "override fun onStart() { super.onStart() " +
                    "if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) requestAudioFocus()"
            )
        )
        assertTrue(
            "and released on stop, so a gain in the background cannot start audio",
            native.contains(
                "if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) abandonAudioFocus()"
            )
        )
        assertTrue(
            "with the flag cleared too - the next foreground is not owed a resume",
            native.contains("abandonAudioFocus() pausedByFocusLoss = false")
        )
    }

    @Test
    fun `the viewer's own press outranks a focus gain`() {
        val toggle = slice(
            native,
            "private fun togglePlayPause(fromOverlay: Boolean = false)",
            "private fun "
        )
        assertTrue(
            "a play/pause press clears the debt, so a later gain cannot undo the viewer",
            toggle.contains("pausedByFocusLoss = false")
        )
    }
}
