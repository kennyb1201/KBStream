package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hero trailer (and the whole app) must go quiet the moment the viewer
 * leaves for the launcher.
 *
 * The reported bug: on a Fire TV, pressing Home left the inline hero trailer
 * playing over the launcher. The player was paused on `Lifecycle.Event.ON_STOP`,
 * which misses both halves of how that actually happens on Fire OS:
 *
 *  1. Fire OS often only PAUSES the app behind its launcher - it never stops -
 *     so the ON_STOP handler never ran; and
 *  2. the trailer resolve is a network call that can outlive the Home press, so
 *     a trailer that MOUNTS after the pause was already delivered found a bare
 *     `playWhenReady = true` and started playing in the background.
 *
 * The fix is therefore two-sided and both sides are pinned here: react to
 * ON_PAUSE (which precedes ON_STOP wherever a stop really happens, so one
 * handler covers both), and gate the mount's autoplay on the owner's CURRENT
 * state rather than an event it may have missed.
 */
class HeroTrailerBackgroundContractTest {

    @Test
    fun `the hero pauses the pooled player on ON_PAUSE, not only ON_STOP`() {
        val screen = screen()
        assertTrue(
            "a Fire OS Home press pauses the app rather than stopping it",
            screen.contains(
                "LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { TrailerPlayerPool.pauseCurrent() }"
            )
        )
        assertFalse(
            "an ON_STOP-only pause is the bug: Fire OS never delivers it behind its launcher",
            screen.contains("Lifecycle.Event.ON_STOP")
        )
    }

    @Test
    fun `the mount only autoplays while the owner is still started`() {
        val screen = screen()
        assertTrue(
            "a trailer resolved behind the launcher must not start playing",
            screen.contains(
                "exoPlayer.playWhenReady = lifecycleOwner.lifecycle.currentState" +
                    ".isAtLeast(Lifecycle.State.STARTED)"
            )
        )
        assertFalse(
            "an ungated autoplay starts the trailer in the background",
            screen.contains("exoPlayer.playWhenReady = true")
        )
    }

    @Test
    fun `the resume epoch re-arms after a pause, not only after a stop`() {
        // The other half of ON_PAUSE handling: a trailer the launcher left
        // PAUSED has to be re-resolved when the app returns, or the hero comes
        // back frozen on its first frame.
        val screen = screen()
        assertTrue(
            "the app must remember it was paused, to re-arm on the way back",
            screen.contains("LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { appInBackground = true }")
        )
        assertTrue(
            "and returning must bump the epoch that re-resolves the trailer",
            screen.contains("resumeEpoch += 1")
        )
    }

    // --------------------------------------------------------------- helpers --

    /** The file verbatim, for anchors that span lines. */
    private fun rawScreen(): String =
        File(findSourceRoot(), SCREEN).readText()

    /** Whitespace-insensitive haystack, so indentation is not the test. */
    private fun screen(): String = rawScreen().replace(Regex("\\s+"), " ")

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
        const val SCREEN = "com/kennyb1201/kbstream/ui/home/HomeScreen.kt"
    }
}
