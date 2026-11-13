package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Spotlight panel's still must cover the START gap and nothing else
 * (2026-11-12, build 262, TCL).
 *
 * The field paste is the whole bug: `spotlight: panel expanded=yes … trailer=
 * mounted`, `trailer.source: imdb-mp4 …`, `pot.player: state=cached
 * source=warm`. The trailer mounted, resolved and played - the audio proves the
 * player was decoding - and the panel showed the still, because the still is
 * removed by exactly ONE event (`onRenderedFirstFrame` -> `onFirstFrame`) and
 * that event never arrived. Nothing else bounded it, so a cover for a brief gap
 * became a mask for the whole trailer.
 *
 * The pins are the three rules that make that impossible:
 *
 *  1. the player reports the frame from the events it has, once, whichever
 *     arrives (`onRenderedFirstFrame`, and the video TRACK as its own evidence);
 *  2. the player also reports the opposite - READY + playing with no picture
 *     inside [HeroTrailerFrameGraceMs] - so the owner is never left holding a
 *     cover over a playing trailer;
 *  3. the panel lifts the still on that report, and if no frame follows inside
 *     its own grace it STOPS the audio-only playback and puts the still back,
 *     which is F2: an audio track with no picture is not presented as a playing
 *     trailer.
 *
 * The hero keeps its rule untouched (S4): the new callback defaults to doing
 * nothing, and no other surface passes it.
 *
 * Local harness: kotlinc -nowarn -cp kotlin-stdlib.jar:junit.jar:hamcrest.jar
 * -d out this file, then JUnitCore with -Duser.dir at the repo root.
 */
class SpotlightTrailerFirstFrameContractTest {

    private fun source(path: String): String {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) {
                    val file = File(candidate, path)
                    assertTrue("source missing: $file", file.isFile)
                    return file.readText()
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private fun between(text: String, start: String, end: String): String {
        val from = text.indexOf(start)
        if (from < 0) return ""
        val to = text.indexOf(end, from + start.length)
        if (to < 0) return ""
        return text.substring(from, to)
    }

    // ── 1. the player's first-frame report ─────────────────────────────────

    @Test
    fun `the frame is reported once, from the frame's event and from the track`() {
        val home = source(HOME)
        val listener = between(home, "val listener =", "exoPlayer.addListener(listener)")

        assertTrue(
            "the frame's own event still reports, so a healthy surface is unchanged",
            listener.contains("override fun onRenderedFirstFrame()")
        )
        assertTrue(
            "and it reports ONCE: a second frame is not a second first frame",
            listener.contains("if (frameReported) return") &&
                listener.contains("frameReported = true")
        )
        assertTrue(
            "the video track is read as its own evidence - an event this player " +
                "does not deliver for every surface/codec must not be the only proof",
            listener.contains("override fun onVideoSizeChanged(videoSize: VideoSize)") &&
                listener.contains("videoSize.width > 0 && videoSize.height > 0") &&
                listener.contains("videoTrackPresent = true")
        )
        assertTrue(
            "which needs media3's VideoSize on the import list",
            home.contains("import androidx.media3.common.VideoSize")
        )
        assertFalse(
            "the track must NOT lift a cover by itself: a size is known before a " +
                "frame is painted, and S2's no-black-flash gap is exactly that",
            listener.contains("videoTrackPresent = true\n                        frameReported = true")
        )
    }

    // ── 2. the player's opposite report: playing, no picture ───────────────

    @Test
    fun `a playing trailer with no frame is reported to the owner, bounded`() {
        val home = source(HOME)
        val watch = between(home, "val frameWatchdog =", "// Watchdog with two budgets")

        assertTrue(
            "the bound is short: a first frame lands well inside a second",
            home.contains("internal const val HeroTrailerFrameGraceMs = 1_000L")
        )
        assertTrue(
            "a frame that landed, or an owner already told, owes nothing",
            watch.contains("if (frameReported || noVideoReported) return")
        )
        assertTrue(
            "a paused player owes no picture either",
            watch.contains("if (!exoPlayer.isPlaying) return")
        )
        assertTrue(
            "the owner is told, with the position the trailer is at",
            watch.contains("onNoVideoFrame(positionMs)")
        )
        assertTrue(
            "and the line says whether the video track existed at all, which is " +
                "what tells an audio-only clip apart from a missing frame event",
            watch.contains("track=\$videoTrackPresent")
        )
        assertEquals(
            "the watch is armed wherever playback starts - READY+playing, " +
                "isPlaying, and once at attach for a WARM pooled player that was " +
                "already playing before this listener existed",
            3,
            Regex("handler\\.postDelayed\\(frameWatchdog, HeroTrailerFrameGraceMs\\)")
                .findAll(home).count()
        )
        assertTrue(
            "the attach arm is the warm-player case the field paste names",
            between(home, "exoPlayer.addListener(listener)", "handler.postDelayed(watchdog,")
                .contains("if (exoPlayer.isPlaying) {")
        )
        assertTrue(
            "and it leaves with the mount, so a pooled player cannot report into " +
                "a surface that is gone",
            between(home, "onDispose {", "exoPlayer.removeListener(listener)")
                .contains("handler.removeCallbacks(frameWatchdog)")
        )
    }

    // ── 3. the owner's callback exists and does nothing by default ─────────

    @Test
    fun `the new report is a defaulted no-op, so the hero keeps its own rule`() {
        val home = source(HOME)
        assertTrue(
            "the callback is optional, which is what keeps S4's hero untouched",
            home.contains("onNoVideoFrame: (positionMs: Long) -> Unit = {}")
        )
        assertFalse(
            "and no other surface passes it: the TV hero and the two phone heroes " +
                "hold their own art and already react to onFirstFrame",
            Regex("onNoVideoFrame = ").containsMatchIn(home)
        )
        assertFalse(
            "the phone's Home hero is unchanged",
            source(MOBILE_HOME).contains("onNoVideoFrame")
        )
        assertFalse(
            "the phone's Detail hero is unchanged",
            source(MOBILE_DETAIL).contains("onNoVideoFrame")
        )
    }

    // ── 4. F1: the panel lifts the still on that report ────────────────────

    @Test
    fun `the panel's still is a start cover, not a mask over a playing trailer`() {
        val panel = source(PANEL)

        assertTrue(
            "the still is still held until the first frame (S2: no black flash)",
            panel.contains("if (still != null && !firstFrame && !frameMissing)")
        )
        assertTrue(
            "but a playing, picture-less trailer takes it away",
            panel.contains("onNoVideoFrame = {") && panel.contains("frameMissing = true")
        )
        assertTrue(
            "a frame arriving late clears the missing verdict, so the escalation " +
                "below cannot stop a trailer that then painted",
            between(panel, "onFirstFrame = {", "},").contains("frameMissing = false")
        )
        assertEquals(
            "each new mount, retry and ending starts from 'no frame, no verdict'",
            4,
            Regex("frameMissing = false").findAll(panel).count()
        )
    }

    // ── 5. F2: audio-only is stopped, and the panel says so ────────────────

    @Test
    fun `an audio-only trailer stops instead of playing behind the still`() {
        val panel = source(PANEL)
        val escalation = panel.substringAfter(
            "LaunchedEffect(source, frameMissing, firstFrame) {"
        )

        assertTrue(
            "the panel waits its own grace after the still has lifted",
            escalation.contains("delay(PANEL_AUDIO_ONLY_GRACE_MS)")
        )
        assertTrue(
            "and only for a trailer that is still picture-less (a frame cancels it)",
            escalation.contains("if (frameMissing && !firstFrame)")
        )
        assertTrue(
            "the playback is STOPPED, not left running behind a still",
            escalation.contains("source = null") &&
                escalation.contains("TrailerPlayerPool.releaseForReuse()")
        )
        assertTrue(
            "the still is restored BEFORE the surface goes: the panel never " +
                "shows an empty box in between",
            escalation.indexOf("frameMissing = false") in
                0 until escalation.indexOf("TrailerPlayerPool.releaseForReuse()")
        )
        assertTrue(
            "and both verdicts are on the trailer line, so the next paste can " +
                "name which one happened",
            escalation.contains("report(\"playing, no picture (surface shown)\")") &&
                escalation.contains("report(\"no video: stopped the audio-only trailer\")")
        )
        assertTrue(
            "a healthy first frame is reported too, so 'it played' is visible",
            panel.contains("report(\"first-frame\")")
        )
    }

    private companion object {
        const val HOME = "com/kennyb1201/kbstream/ui/home/HomeScreen.kt"
        const val PANEL = "com/kennyb1201/kbstream/ui/home/SpotlightPanelTrailer.kt"
        const val MOBILE_HOME = "com/kennyb1201/kbstream/ui/mobile/HomeScreenMobile.kt"
        const val MOBILE_DETAIL = "com/kennyb1201/kbstream/ui/mobile/DetailScreenMobile.kt"
    }
}
