package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AFR holds the panel it engaged.
 *
 * Two halves, both from the 2026-10-24 report ("with AFR on it's still causing
 * judder, like halfway through movies"):
 *
 *  1. The engage ladder runs in the opening seconds and then stops looking. A
 *     panel that leaves the chosen mode later stayed wrong for the rest of the
 *     film, because nothing asked again — the player's tick now verifies the
 *     engaged mode and re-asks it once.
 *  2. The opposite mistake is equally live: `onContentFrameRate` is offered on
 *     every track report, so a rebuilt player or a source switched mid-film
 *     could renegotiate HDMI under a playing picture. Only the opening window
 *     may move the panel; a late offer is recorded instead.
 *
 * Source text is what a JVM test can see for the Android shell; the ladder's
 * arithmetic is covered by [FrameRateMatchTest].
 */
class FrameRateHoldContractTest {

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

    private fun source(path: String): String =
        File(sourceRoot, path).readText().replace(Regex("\\s+"), " ")

    private fun matcher(): String =
        source("com/kennyb1201/kbstream/ui/player/FrameRateMatch.kt")

    private fun player(): String =
        source("com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt")

    @Test
    fun `the player's tick holds the panel to the engaged mode`() {
        val tick = player().substringAfter("private val positionRunnable")
            .substringBefore("private fun startPositionPolling")

        assertTrue(
            "the one-second tick must verify the engaged mode: a panel that moves " +
                "after the opening seconds has nothing else looking at it",
            tick.contains("frameRateMatcher?.verifyEngagedMode()")
        )
    }

    @Test
    fun `verification re-asks the mode this session chose, once`() {
        val verify = matcher().substringAfter("fun verifyEngagedMode()")
            .substringBefore("/** Puts the panel back.")

        assertTrue(
            "nothing to hold: a session that never asked by id must do nothing",
            verify.contains("appliedModeId == UNSET")
        )
        assertTrue(
            "only a moving picture is repaired - a paused session's panel is " +
                "the resume path's business",
            verify.contains("if (!isPlaybackActive()) return")
        )
        assertTrue(
            "the repair is the mode this session already asked for, never a new one",
            verify.contains("setPreferredModeId(appliedModeId)")
        )
        assertTrue(
            "one repair per session: a box that keeps dropping the mode must not " +
                "be renegotiated in a loop",
            verify.contains("if (released || appliedModeId == UNSET || reassertedMode) return") &&
                verify.contains("reassertedMode = true")
        )
    }

    @Test
    fun `a rate offered after the opening may not move the panel`() {
        // The whole ladder, from the guard to the last request it can make.
        val apply = matcher().substringAfter("private fun apply(")
            .substringBefore("private fun askSurfaceIfPossible(")

        assertTrue(
            "the engage window is checked before any request is made",
            apply.contains("FrameRateMatch.engageWindowAllows(ageMs, source)")
        )
        assertTrue(
            "a late offer has to be visible on the diagnostics row, not silently dropped",
            apply.contains("FrameRateMatch.describeLateOffer(normalised, ageMs)")
        )
        assertTrue(
            "the refused offer returns before the ladder can ask for anything",
            apply.indexOf("engageWindowAllows") < apply.indexOf("askSurfaceIfPossible")
        )
        assertFalse(
            "the window must not apply to a resume repair",
            apply.contains("engageWindowAllows(ageMs = 0")
        )
    }
}
