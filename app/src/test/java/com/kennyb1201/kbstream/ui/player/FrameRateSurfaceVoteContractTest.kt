package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No surface frame-rate vote is cast once the panel has a mode answer.
 *
 * Field bisect, 2026-10-22 (Onn 4K Plus, AFR on): stutter from the first seconds
 * of the file with no pause or seek needed, while decode was provably clean
 * (~24 fps output cadence, `dropped 0, rebuffers 0`) and the panel was in mode
 * 161 at 23.976 Hz. A `Surface.setFrameRate(23.976, FIXED_SOURCE)` vote had
 * landed on the player's live surface and is never cleared before teardown
 * (`clearSurfaceRate` has one caller, `release()`), so the vote sat under a panel
 * the window had separately forced to the same rate — the combination the
 * 2026-10-18 captures already convict ("choppy playback", "no present fence").
 *
 * Two doors still cast it, and both are pinned shut here:
 *
 *  1. A fresh matcher that found the panel ALREADY in the best mode fell through
 *     to the surface hint.
 *  2. The 1.5 s confirm, finding the mode not yet moved (HDMI renegotiation is
 *     slower than that), fired the surface hint as the ladder's second rung —
 *     onto a surface that is live and decoding by then, with the forced mode
 *     landing on top of the vote moments later.
 *
 * Also pinned: every player creation silences Media3's own seamless strategy, so
 * a REBUILT player cannot put a second, un-silenced voter back on the surface.
 *
 * There is no TV in CI and this class is Android-only, so the rules are read out
 * of the sources, the way this tree's other player contracts are
 * (`FrameRateEngageOrderContractTest`, `FrameRateResumeContractTest`).
 */
class FrameRateSurfaceVoteContractTest {

    private val match = squash(source(MATCH))
    private val native = squash(source(NATIVE))
    private val apply = window(match, "private fun apply(", "private fun askSurfaceIfPossible(")
    private val modeFirst = window(apply, "if (ENGAGE_MODE_FIRST) {", "// Pre-fix ladder")

    @Test
    fun `no surface vote is cast when the panel already has a mode answer`() {
        val alreadyInBestMode = modeFirst.substringAfter("else -> {")

        assertTrue(
            "the arm that finds the panel already right still owes the restore: after an " +
                "engine switch this session is the only one left holding the panel",
            alreadyInBestMode.contains("restoreOnRelease = true")
        )
        assertFalse(
            "Door 1: a fresh matcher that found the panel in its best mode must not cast the " +
                "vote either — a mode answer exists, so the surface has nothing to add",
            alreadyInBestMode.contains("askSurfaceIfPossible(")
        )
        assertEquals(
            "and the hint survives exactly one arm: the panel that has no mode answer at all",
            1,
            count(modeFirst, "askSurfaceIfPossible(")
        )
        assertTrue(
            "which is the single-rate case it was written for",
            modeFirst.contains("best == null -> askSurfaceIfPossible(contentFps, modeId)")
        )
    }

    @Test
    fun `a mode not landed at the first look gets a second look, not the surface`() {
        val confirm = window(match, "private fun confirm(", "private fun settleMode(")

        assertTrue(
            "Door 2: the 1.5 s look must schedule the later one rather than fire the hint",
            confirm.contains("scheduleSecondLook(serial, beforeModeId, contentFps, mode)")
        )
        val secondLook = window(match, "private fun scheduleSecondLook(", "private fun clearSurfaceRate(")
        assertTrue(
            "and that later look is still a MODE look, at ~3.5 s from the mode request " +
                "(1.5 s + 2 s), still inside the title's opening",
            secondLook.contains("EngageStep.MODE_SECOND_LOOK") &&
                secondLook.contains("SECOND_CONFIRM_DELAY_MS") &&
                match.contains("const val CONFIRM_DELAY_MS = 1500L") &&
                match.contains("const val SECOND_CONFIRM_DELAY_MS = 2000L")
        )
        assertFalse(
            "no surface ask anywhere in the confirm chain",
            confirm.contains("askSurfaceIfPossible(")
        )
        assertTrue(
            "a paused picture drops every look, the second one included: a repair against a " +
                "stopped picture is the mid-session surface request this avoids",
            confirm.contains("if (!isPlaybackActive()) return")
        )
    }

    @Test
    fun `a mode still unmoved at the second look is recorded as refused`() {
        val settle = window(match, "private fun settleMode(", "private fun clearSurfaceRate(")

        assertTrue(
            "the row must say the panel refused a MODE, not that a request was not taken: the " +
                "ladder had nothing else to try",
            settle.contains(
                "FrameRateMatch.describeModeRefused(contentFps, requested, afterModeId)"
            )
        )
        assertTrue(
            "re-asked by attribute once — the only request left once the panel has a mode answer",
            settle.contains("setPreferredModeId(requested.id)")
        )
        assertFalse(
            "and never a surface vote on the way out of the ladder",
            settle.contains("askSurfaceIfPossible(") || settle.contains("surface.setFrameRate(")
        )
        assertTrue(
            "a panel that did land is left alone: the second look is a look, not a re-request",
            settle.contains("afterModeId == requested.id")
        )
    }

    @Test
    fun `every player silences media3's own surface vote`() {
        val createPlayer =
            window(native, "private fun createPlayer() {", "private fun createPlayerListener()")

        assertEquals(
            "media3's seamless strategy must be switched off at each player creation",
            1,
            count(
                createPlayer,
                "setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)"
            )
        )
        assertTrue(
            "and outside the one-per-activity matcher guard: the strategy is not the matcher's " +
                "business, and a REBUILT player never reaches that guard, so a hoist that stayed " +
                "inside it would leave media3 as a second voter exactly on the rebuild path",
            createPlayer.indexOf("setVideoChangeFrameRateStrategy(") <
                createPlayer.indexOf(
                    "frameRateMatcher == null && AppPreferences.getMatchFrameRate("
                )
        )
        assertEquals(
            "there is one player seam, so silencing it there covers the first player and every " +
                "rebuilt one",
            1,
            count(native, "ExoPlayer.Builder(this, renderersFactory)")
        )
        assertEquals(
            "and exactly one site sets the strategy, so no path can find a second voter",
            1,
            count(native, "setVideoChangeFrameRateStrategy(")
        )
    }

    private fun source(path: String): String {
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

    private fun squash(text: String): String = text.replace(Regex("\\s+"), " ")

    /** How many times [needle] occurs in [text]. */
    private fun count(text: String, needle: String): Int =
        Regex(Regex.escape(needle)).findAll(text).count()

    /** The squashed source between [from] and the next [to], both asserted to exist. */
    private fun window(text: String, from: String, to: String): String {
        val start = text.indexOf(from)
        assertTrue("anchor missing: $from", start >= 0)
        val end = text.indexOf(to, start + from.length)
        assertTrue("anchor missing after '$from': $to", end > start)
        return text.substring(start, end)
    }

    private companion object {
        const val MATCH = "com/kennyb1201/kbstream/ui/player/FrameRateMatch.kt"
        const val NATIVE = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
    }
}
