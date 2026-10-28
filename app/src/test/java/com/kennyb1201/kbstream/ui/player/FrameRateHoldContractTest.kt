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

    private fun mpvPlayer(): String =
        source("com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt")

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
    fun `mpv holds the panel too, on a tick of its own`() {
        // This engine has no one-second position tick (its clock only runs while
        // the control bar is up), so the hold landing earlier held only for the
        // Media3 player and mpv's panel was never re-checked mid-film (audit AFR
        // C4). The tick is what gives verifyEngagedMode the chance to look.
        val mpv = mpvPlayer()
        assertTrue(
            "mpv runs the same hold from its own tick",
            mpv.contains("frameRateMatcher?.verifyEngagedMode()")
        )
        assertTrue(
            "and the tick re-posts itself, or it would look exactly once",
            mpv.contains("afrHoldHandler.postDelayed(this, AFR_HOLD_TICK_MS)")
        )
    }

    @Test
    fun `verification re-asks the mode this session chose, once`() {
        val verify = matcher().substringAfter("fun verifyEngagedMode()")
            .substringBefore("/** Puts the panel back.")

        assertTrue(
            "nothing to hold: a session that never asked by id must do nothing",
            verify.contains("heldModeId == UNSET")
        )
        assertTrue(
            "only a moving picture is repaired - a paused session's panel is " +
                "the resume path's business",
            verify.contains("if (!isPlaybackActive()) return")
        )
        assertTrue(
            "the repair is the mode this session holds - asked for by id, or taken " +
                "over from a panel already in it, which still owns the restore (audit " +
                "AFR C5) - and never a new one",
            verify.contains("setPreferredModeId(heldModeId)")
        )
        assertTrue(
            "one repair per session: a box that keeps dropping the mode must not " +
                "be renegotiated in a loop",
            verify.contains("if (released || heldModeId == UNSET || reassertedMode) return") &&
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

    @Test
    fun `the window is anchored to the source's first offer, and a new source re-anchors it`() {
        // Audit AFR S9: the age was measured from construction, which happens
        // inside createPlayer - so a slow host whose first rate landed after 90 s
        // had its opening refused. The anchor is the first usable offer, per
        // source; a source switch clears it with the rest of the per-source
        // state, while a rebuilt player does not (a rebuild is not a new source).
        val apply = matcher().substringAfter("private fun apply(")
            .substringBefore("private fun askSurfaceIfPossible(")
        assertTrue(
            "the age comes from the anchor helper",
            apply.contains("FrameRateMatch.engageAgeMs(nowMs, openedAtMs, firstOfferAtMs)")
        )
        assertTrue(
            "and only a usable rate sets the anchor, so a track that reports no rate " +
                "cannot open the window early",
            apply.contains("if (normalised != null && firstOfferAtMs == null) firstOfferAtMs = nowMs")
        )

        val begin = matcher().substringAfter("fun beginSource()")
            .substringBefore("/**")
        assertTrue(
            "a new source starts its own window",
            begin.contains("firstOfferAtMs = null")
        )
    }
}
