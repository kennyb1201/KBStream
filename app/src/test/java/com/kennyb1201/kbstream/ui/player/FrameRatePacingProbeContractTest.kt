package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pacing probe: what a matched-rate session reports about itself.
 *
 * The 2026-10-30 field table (Onn 4K Plus → TCL) settles the switch and leaves
 * one question: with matching ON the picture judders at BOTH matched rates
 * (24.000 and 23.976), from the first second, while 60 Hz is smooth. The stated
 * suspect is that the player's frame release keeps pacing against the display it
 * sampled before the mode moved.
 *
 * Pinned here, because none of it is observable from a JVM test on a device:
 *
 *  1. The engagement is reported when it is CONFIRMED (the look that saw the
 *     move land, and the take-over of a panel that was already right), plus the
 *     hold tick's re-ask, which has no confirm of its own.
 *  2. The probe is attached to the player that is alive at that moment and
 *     detached when the session stops holding the panel, so it cannot outlive the
 *     engagement it describes.
 *  3. It measures the compositor's own vsync (consecutive Choreographer frames,
 *     resampled) as well as the release cadence, because a box that reports a
 *     23.976 mode while composing at 60 Hz is a different defect from a player
 *     that paces to the wrong grid - and the log has to tell them apart.
 *  4. It stays read-only: no surface rate, no window attribute, no player
 *     setting. A diagnostic that can change what it measures is not one.
 */
class FrameRatePacingProbeContractTest {

    private val match = squash(source(MATCH))
    private val native = squash(source(NATIVE))
    private val probe = squash(source(PROBE))

    @Test
    fun `the engagement is reported when the panel is confirmed, not when it is asked for`() {
        val confirm = window(match, "private fun confirm(", "private fun settleMode(")

        assertTrue(
            "the look that saw the mode land is where the hold is reported: a request is not a " +
                "fact, and a refused mode has nothing to report",
            confirm.contains("if (heldModeId != UNSET && afterModeId == heldModeId) {") &&
                confirm.contains("onModeHeld(heldModeId, afterRate)")
        )
        assertTrue(
            "a session that found the panel already in its best mode engaged without asking, so " +
                "its hold starts at the take-over",
            match.contains("onModeHeld(best.id, best.refreshRate)")
        )
        assertTrue(
            "and the hold tick's re-ask reports itself: it repairs a mode this session already " +
                "held and never goes through confirm()",
            window(match, "private fun verifyEngagedMode(", "fun release()").contains(
                "onModeHeld("
            )
        )
        assertEquals(
            "exactly those three, so a new engagement path cannot silently go unreported",
            3,
            count(match, "onModeHeld(")
        )
    }

    @Test
    fun `the hold ends where the session stops holding the panel`() {
        assertEquals(
            "release is one; a source that will never be matched taking over is the other",
            2,
            count(match, "onModeReleased()")
        )
        assertTrue(
            "release ends the hold before it restores anything, so a probe cannot survive teardown",
            window(match, "fun release() {", "private fun restorePanelMode(").let { release ->
                val ended = release.indexOf("onModeReleased()")
                ended >= 0 && ended < release.indexOf("restorePanelMode()")
            }
        )
    }

    @Test
    fun `the activity starts it against the player that holds the surface`() {
        val ensure = window(
            native,
            "private fun ensureFrameRateMatcher()",
            "private fun restartFrameRateMatchForSource()"
        )

        assertTrue(
            "created on first engagement, so a session that never matches never pays for it",
            ensure.contains(
                "(pacingProbe ?: PlayerPacingProbe(this).also { pacingProbe = it })"
            )
        )
        assertTrue(
            "and attached to whichever player is alive now: a rebuilt player is the one the " +
                "mode applies to",
            ensure.contains(".start(exoPlayer, modeId, refreshRateHz)")
        )
        assertTrue(
            "the stop side is the matcher's own release hook",
            ensure.contains("onModeReleased = { pacingProbe?.stop() }")
        )
    }

    @Test
    fun `the probe watches the release path and the compositor, and writes one line`() {
        assertTrue(
            "release timestamps come from media3's own frame callback",
            probe.contains("player.setVideoFrameMetadataListener(frameListener)") &&
                probe.contains("player?.clearVideoFrameMetadataListener(frameListener)")
        )
        assertTrue(
            "the compositor's cadence is measured from CONSECUTIVE frames, then resampled: the " +
                "spacing of runs taken a second apart is not a vsync period",
            probe.contains("Choreographer.getInstance()") &&
                probe.contains("burstLeft = BURST_FRAMES") &&
                probe.contains("choreographer?.postFrameCallback(vsyncCallback)")
        )
        assertEquals(
            "one line per engagement (the summary) and one per display move - the session ring " +
                "holds 24 lines and the AFR and player.stats lines have to survive in it",
            2,
            count(probe, "PlaybackSessionTrace.note(")
        )
        assertTrue(
            "the summary names the mode, the measured vsync, the release cadence and the content " +
                "cadence, and ends in a verdict a report can quote",
            probe.contains("\"pacing: \$modeText, app vsync \$vsyncText, \"") &&
                probe.contains("content \$content\$grid\$gap — pacing=\$verdict")
        )
        assertTrue(
            "and the verdict has the three answers the field table needs: paced right, paced to " +
                "the wrong grid, or a display that does not compose at the mode it reports",
            probe.contains("\"not-follows\"") &&
                probe.contains("\"display-not-at-rate\"") &&
                probe.contains("\"follows\"")
        )
        assertTrue(
            "a pause is counted, not averaged: one multi-second gap would report a defect in a " +
                "session that played cleanly",
            probe.contains("if (delta > GAP_NS) {")
        )
    }

    @Test
    fun `the probe changes nothing it measures`() {
        assertFalse(
            "no surface rate: that write is the convicted 2026-10-22 combination and this is a " +
                "diagnostic, not another voter",
            probe.contains("setFrameRate(")
        )
        assertFalse(
            "no window attribute and no player setting either",
            probe.contains("preferredDisplayModeId")
        )
        assertFalse(
            "media3's own strategy stays where the player build put it",
            probe.contains("setVideoChangeFrameRateStrategy")
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
        const val PROBE = "com/kennyb1201/kbstream/ui/player/PlayerPacingProbe.kt"
    }
}
