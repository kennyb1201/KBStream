package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the large-seek resume rule is wired into ExoPlayer (spec 2026-11-13).
 *
 * [LargeSeekResumeRulesTest] covers the decision; this covers the parts no JVM
 * test can run - that both far-jump paths (the scrub release and the bar's
 * commit) hand their landing to the rule, that the hold is bounded and
 * abandonable, and that nothing the spec forbids was changed: the VOD buffer
 * durations, the after-rebuffer threshold, `SeekParameters`, and the per-tick
 * seeks that make the picture follow a held scrub.
 */
class LargeSeekResumeContractTest {

    private val activity by lazy { source(ACTIVITY) }

    private fun bodyOf(start: String, end: String): String {
        val from = activity.indexOf(start)
        assertTrue("source must still contain: $start", from >= 0)
        val to = activity.indexOf(end, from)
        assertTrue("the block starting at `$start` must be bounded by `$end`", to > from)
        return activity.substring(from, to)
    }

    // ── Where the decision lives ──────────────────────────────────────────

    @Test
    fun `one helper owns the resume, and both far-jump paths reach it`() {
        val endScrub = bodyOf("private fun endScrubPause()", "private fun resumeAfterSeek(")
        assertTrue(
            "the scrub release decides through the one helper",
            endScrub.contains("resumeAfterSeek(player, scrubStartPositionMs, scrubLandingTargetMs)")
        )
        assertTrue(
            "a small seek is byte-for-byte the old behaviour: play at once",
            activity.contains("if (!LargeSeekResumeRules.isLargeSeek(fromMs, toMs)) {") &&
                activity.contains("player.play()")
        )
        assertTrue(
            "and the helper is the only place the gate is armed",
            activity.windowed("holdForRunwayThenPlay(".length)
                .count { it == "holdForRunwayThenPlay(" } == 2 // declaration + one call
        )
    }

    @Test
    fun `every way a scrub can land records where it landed`() {
        assertTrue(
            "the surface scrub's exact landing",
            bodyOf("private fun landScrub()", "private fun commitSeekFromBar()")
                .contains("scrubLandingTargetMs = scrubTargetPosMs")
        )
        assertTrue(
            "the D-pad bar's commit",
            bodyOf("private fun commitSeekFromBar()", "private fun updateClock()")
                .contains("scrubLandingTargetMs = posMs")
        )
        assertTrue(
            "and the chrome bar's release, which is the touch path's landing",
            bodyOf("override fun onChromeSeekTo(positionMs: Long)", "override fun onChromeScrubStart()")
                .contains("if (scrubInFlight) scrubLandingTargetMs = positionMs")
        )
        assertTrue(
            "the start position is captured with the play state it is compared against",
            bodyOf("private fun beginScrubPause()", "private fun endScrubPause()")
                .contains("scrubStartPositionMs = player.currentPosition.coerceAtLeast(0L)")
        )
    }

    // ── The hold ─────────────────────────────────────────────────────────

    @Test
    fun `the hold is bounded, polled and abandonable`() {
        val hold = bodyOf("private fun pollLargeSeekHold(", "private fun cancelLargeSeekHold()")

        assertTrue(
            "it ends on runway, on a failed player, or when it has waited long enough",
            hold.contains("LargeSeekResumeRules.shouldResume(heldMs, bufferedMs, player.playerError != null)")
        )
        assertTrue("and then resumes, so the player is never left paused", hold.contains("player.play()"))
        assertTrue(
            "by token, so an abandoned hold can never resume later",
            hold.contains("if (token != largeSeekHoldToken) return")
        )
        assertTrue(
            "polled on the same main handler the player already uses",
            hold.contains("handler.postDelayed({ pollLargeSeekHold(token, startedAt) }, LargeSeekResumeRules.HOLD_POLL_MS)")
        )
    }

    @Test
    fun `a press or a new seek wins over a running hold`() {
        assertTrue(
            "play/pause wins immediately",
            bodyOf("private fun togglePlayPause()", "private fun emptyPickerMessage(")
                .contains("cancelLargeSeekHold()")
        )
        assertTrue(
            "and so does starting another scrub",
            bodyOf("private fun beginScrubPause()", "private fun endScrubPause()")
                .contains("cancelLargeSeekHold()")
        )
    }

    @Test
    fun `a scrub that starts during a hold still resumes afterwards`() {
        // The hold pauses the player, so a scrub started meanwhile would capture
        // `playWhenReady=false` and release into a paused player - a stuck
        // spinner. The hold's own pause is not the viewer's idleness.
        val begin = bodyOf("private fun beginScrubPause()", "private fun endScrubPause()")

        assertTrue(begin.contains("val holdingAfterLargeSeek = largeSeekHoldActive"))
        assertTrue(
            "read BEFORE the hold is abandoned",
            begin.indexOf("val holdingAfterLargeSeek") < begin.indexOf("cancelLargeSeekHold()")
        )
        assertTrue(
            "and it counts as intent to play",
            begin.contains("wasPlayingBeforeScrub = player.playWhenReady || holdingAfterLargeSeek")
        )
        assertTrue(
            "the flag also clears when the player vanishes under a hold",
            bodyOf("private fun pollLargeSeekHold(", "private fun cancelLargeSeekHold()")
                .contains("largeSeekHoldActive = false")
        )
    }

    // ── What must not have changed ────────────────────────────────────────

    @Test
    fun `the VOD buffer and its after-rebuffer threshold are untouched`() {
        assertTrue(
            "the deliberate compromise the buffer comment records: 15s/60s/6s/5s, " +
                "applied as the settled profile",
            activity.contains("intArrayOf(15_000, 60_000, 6_000, 5_000)") &&
                activity.contains(
                    "setBufferDurationsMs(bufferDurations[0], bufferDurations[1], " +
                        "bufferDurations[2], bufferDurations[3])"
                )
        )
        assertTrue(
            "and the byte cap with it",
            activity.contains("setTargetBufferBytes(") &&
                activity.contains("setPrioritizeTimeOverSizeThresholds(false)")
        )
    }

    @Test
    fun `seeks are still exact, and a held scrub still moves the picture`() {
        assertFalse(
            "no SeekParameters were introduced: the landing stays on the scrub target",
            activity.contains("setSeekParameters(")
        )
        assertTrue(
            "the scrub tick still seeks every tick, which is what follows the bar",
            bodyOf("private val scrubRunnable = object : Runnable {", "// Fires once a D-pad key has been held")
                .contains("player.seekTo(scrubTargetPosMs)")
        )
    }

    @Test
    fun `nothing rewired the seeks that are not scrub jumps`() {
        // Resume restore, up-next, restart and the stall watchdog's recovery
        // seek all keep their own path - the gate is for scrub/bar landings.
        assertFalse(
            "the resume-point restore is not gated",
            activity.contains("seekTo(carryPositionMs).also { resumeAfterSeek(")
        )
        assertTrue(
            "and the stall watchdog's recovery seek is unchanged",
            activity.contains("player.seekTo(targetMs)")
        )
    }

    private fun source(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) {
                    val file = File(candidate, relative)
                    assertTrue("source missing: $file", file.isFile)
                    return file.readText()
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError("main source root not found")
    }

    private companion object {
        const val ACTIVITY = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
    }
}
