package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AFR re-arms when the viewer comes back to a player they left.
 *
 * The reported fault: a 24 fps film switched the panel to 24 Hz, leaving the
 * player put it back to 60 Hz as designed, and returning to the film left it at
 * 60 Hz for the rest of the title. Nothing was lost - the rate had been measured
 * - but both halves that supply one had already run for the session: the probe
 * reads the stream's HEAD once, and the player reports its rate with its first
 * video track. So the panel was the only thing that needed telling again, and
 * nothing told it.
 *
 * What is pinned here is that wiring, on both engines, because it is exactly the
 * kind of code a later edit deletes without noticing: the field that remembers
 * the rate, the guards a resume must respect (a rate was really measured, the
 * setting is still on, the matcher exists), the resume hook itself, and mpv's
 * one case where the remembered rate must be FORGOTTEN instead - a source switch
 * to a stream whose rate is unknown until it reports one.
 *
 * Also pinned, because they must not move: the probe still runs once per
 * session, and teardown still restores the panel. The re-arm is the third piece,
 * not a replacement for either.
 *
 * There is no TV in CI and no Compose/Activity harness in this module, so the
 * wiring is read out of the sources, the way this tree's other player contracts
 * are (`FrameRateProbeWiringContractTest`).
 */
class FrameRateResumeContractTest {

    private val native = squash(source(NATIVE))
    private val mpv = squash(source(MPV))
    private val match = squash(source(MATCH))
    private val apply = match.window("private fun apply(", "private fun askSurfaceIfPossible(")
    private val askSurface = match.window("private fun askSurface(", "private fun requestMode(")
    private val confirm = match.window("private fun confirm(", "private fun settleMode(")

    @Test
    fun `both engines remember the rate they were told`() {
        listOf("the native player" to native, "the mpv player" to mpv).forEach { (who, src) ->
            assertTrue(
                "$who must keep the rate, or a resume has nothing to re-offer",
                src.contains("private var lastContentFps: Double? = null")
            )
            assertTrue(
                "$who must remember the PROBED rate as well as offering it: on a stream " +
                    "the player never measures, that is the only rate there is",
                src.contains("lastContentFps = fps matcher.onContentFrameRate(fps, FrameRateSource.PROBE)")
            )
        }
    }

    @Test
    fun `the native player remembers its own track report only when it describes content`() {
        assertTrue(
            "a video track that declares no rate reports media3's NO_VALUE (-1.0) - stored, it " +
                "would be the rate a resume re-offers",
            native.contains(
                "if (FrameRateMatch.normalise(reportedFrameRate) != null) { " +
                    "lastContentFps = reportedFrameRate }"
            )
        )
        assertTrue(
            "and the track's own report still goes to the matcher exactly as it did",
            native.contains("frameRateMatcher?.onContentFrameRate(reportedFrameRate)")
        )
    }

    @Test
    fun `the mpv player remembers mpv's report and its resume fallback`() {
        assertTrue(
            "mpv's own report is remembered the same way - only when it describes content",
            mpv.contains("if (FrameRateMatch.normalise(fps) != null) lastContentFps = fps")
        )
        assertTrue(
            "and onStart's re-offer of mpv's rate still runs, now keeping the memory current too",
            mpv.contains(
                "surface?.videoFrameRate()?.let { fps -> " +
                    "if (FrameRateMatch.normalise(fps) != null) lastContentFps = fps " +
                    "frameRateMatcher?.onContentFrameRate(fps) }"
            )
        )
    }

    @Test
    fun `a resume re-arms the panel from the remembered rate`() {
        val exoHook = "override fun onResume() { super.onResume() installManualSwitchFeedback() " +
            "rearmFrameRateMatch() }"
        assertTrue(
            "the native player's resume hook is where its existing onResume already was",
            native.contains(exoHook)
        )
        assertTrue(
            "the mpv player gets its own resume hook: onStart's re-offer can only say what mpv " +
                "reports, which for an addon stream is nothing",
            mpv.contains("override fun onResume() { super.onResume() rearmFrameRateMatch() }")
        )
        listOf("the native player" to native, "the mpv player" to mpv).forEach { (who, src) ->
            assertTrue(
                "$who must ask the SETTING, not the matcher's existence: it can be switched off " +
                    "while the player is away, and release() leaves the matcher alive",
                src.contains("private fun rearmFrameRateMatch() { " +
                    "if (!AppPreferences.getMatchFrameRate(this)) {")
            )
            assertTrue(
                "$who must put the panel back when the setting was switched off while away: " +
                    "a transient stop no longer restores it, so this is the only call that does",
                src.contains(
                    "if (frameRateMatcher?.hasEngaged == true) frameRateMatcher?.release()"
                )
            )
            assertTrue(
                "$who must re-offer what was measured and nothing else",
                src.contains("val fps = FrameRateMatch.resumeRate(lastContentFps) ?: return")
            )
            assertTrue(
                "$who re-offers it as a RESUME, so the diagnostics row can say the rate was " +
                    "measured earlier rather than reported now",
                src.contains("frameRateMatcher?.onContentFrameRate(fps, FrameRateSource.RESUME)")
            )
            assertTrue(
                "$who must log the re-arm under the probe's own tag, or a field capture of " +
                    "'AFR did not re-arm' cannot see it happen",
                src.contains("Log.i(\"PLAYER_AFR\", \"re-arming frame-rate match with \$fps fps\")")
            )
        }
    }

    @Test
    fun `mpv forgets a rate that belonged to the stream it switched away from`() {
        val switch = mpv.substringAfter("private fun switchToSource(stream: Stream")
        assertTrue(
            "a mid-session source switch makes the remembered rate describe the WRONG stream: " +
                "the probe is a session-start step, so nothing re-reads the new one's head",
            switch.contains("lastContentFps = null")
        )
        assertTrue(
            "and it is forgotten as the new URL is taken, before anything can offer the old rate",
            switch.indexOf("lastContentFps = null") < switch.indexOf("currentAudioUrl = stream.audioUrl")
        )
    }

    @Test
    fun `the probe still runs once, and teardown still restores the panel`() {
        listOf("the native player" to native, "the mpv player" to mpv).forEach { (who, src) ->
            assertTrue(
                "$who still releases the panel on the way out - the re-arm is an addition, not a " +
                    "replacement for putting the TV interface's own rate back",
                src.contains("frameRateMatcher?.release()")
            )
            assertTrue(
                "$who still releases from onDestroy, the net for a player destroyed without a " +
                    "stop of its own",
                src.contains("override fun onDestroy() {") &&
                    src.substringAfter("override fun onDestroy() {").contains("frameRateMatcher?.release()")
            )
            assertTrue(
                "$who still only builds the matcher when the setting is on and does not have one " +
                    "already, so a session's original mode is captured once",
                src.contains("frameRateMatcher == null && AppPreferences.getMatchFrameRate(")
            )
        }
        assertTrue(
            "the native player still probes the stream's head alongside player prep",
            native.contains("FrameRateProbe.probe(probeUrl, probeHeaders, cacheDir)")
        )
        assertTrue(
            "and mpv still probes at session start",
            mpv.contains("if (!FrameRateProbe.isProbeable(currentUrl)) return") &&
                mpv.contains("FrameRateProbe.probe(probeUrl, probeHeaders, cacheDir)")
        )
        assertFalse(
            "a re-arm must not re-probe: a second head fetch per resume is a cost the remembered " +
                "rate exists to avoid",
            native.substringAfter("private fun rearmFrameRateMatch()")
                .substringBefore("FrameRateSource.RESUME")
                .contains("FrameRateProbe.probe(")
        )
    }

    @Test
    fun `a resume repairs the mode and never re-asks the live surface`() {
        val resume = apply.window("if (source == FrameRateSource.RESUME) {", "if (ENGAGE_MODE_FIRST) {")
        assertTrue(
            "the resume is its own branch, before the engage ladder: the open ladder asked " +
                "the surface before decode, which is the smooth point for it",
            resume.contains("val best = FrameRateMatch.bestMode(contentFps, modes)")
        )
        assertTrue(
            "the panel being in the wrong mode is the one thing a resume repairs",
            resume.contains("best != null && best.id != modeId -> requestMode(best, contentFps, modeId)")
        )
        assertTrue(
            "a panel already right is a no-op with a diagnostics line saying so",
            resume.contains("FrameRateMatch.describeResumeNoRequest(contentFps, best)")
        )
        assertTrue(
            "and the surface is only reachable when there is no better mode at all, where " +
                "the idempotence guard decides whether anything is actually asked",
            resume.contains("else -> askSurfaceIfPossible(contentFps, modeId)")
        )
    }

    @Test
    fun `the surface hint is idempotent per surface and rate`() {
        assertTrue(
            "a rate already on the same surface is not re-applied: that request is what the " +
                "field capture of 2026-10-18 links to choppy playback after pause and resume",
            askSurface.contains(
                "if (surface === surfaceRateSurface && request.frameRate == surfaceRateValue)"
            )
        )
        assertTrue(
            "the no-op is recorded as one, so a capture can tell it from a refused request",
            askSurface.contains("FrameRateMatch.describeSurfaceAlreadyAt(contentFps)")
        )
        assertTrue(
            "the applied rate and its surface are remembered at the one place that applies them",
            askSurface.contains("surfaceRateSurface = surface") &&
                askSurface.contains("surfaceRateValue = request.frameRate")
        )
        assertTrue(
            "clearing the rate forgets them, so a rebuilt surface is asked again",
            match.contains("surfaceRateSurface = null") && match.contains("surfaceRateValue = 0f")
        )
    }

    @Test
    fun `a paused picture gets no backstop, and both engines say when it is paused`() {
        assertTrue(
            "the delayed second look is dropped while playback is stopped",
            confirm.contains("if (!isPlaybackActive()) return")
        )
        assertTrue(
            "the native player answers from its own play intent",
            native.contains("isPlaybackActive = { exoPlayer?.playWhenReady == true }")
        )
        assertTrue(
            "and mpv from its pause property, at both places the matcher is built",
            mpv.split("isPlaybackActive = { surface?.isPaused() == false }").size == 3
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

    /** The squashed source between [from] and the next [to], both asserted to exist. */
    private fun String.window(from: String, to: String): String {
        val start = indexOf(from)
        assertTrue("anchor missing: $from", start >= 0)
        val end = indexOf(to, start + from.length)
        assertTrue("anchor missing after '$from': $to", end > start)
        return substring(start, end)
    }

    private companion object {
        const val NATIVE = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        const val MPV = "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
        const val MATCH = "com/kennyb1201/kbstream/ui/player/FrameRateMatch.kt"
    }
}
