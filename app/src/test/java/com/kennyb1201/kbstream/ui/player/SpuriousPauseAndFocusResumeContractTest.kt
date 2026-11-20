package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spontaneous-pause fix (spec 2026-11-17): F1 the spurious MEDIA_PAUSE window on
 * both engines, F2 MPV's resume on audio-focus gain, F3 the Next Up countdown
 * unchanged, F4 scope.
 *
 * The reports: a Fire TV Stick paused itself right after a file loaded and had
 * to be un-paused by hand; a TCL session parked itself paused mid-episode and
 * the Next Up card then said "Paused - playing next when you resume".
 *
 * F1 - the Fire OS guard only covered BEFORE the first frame. Fire OS keeps
 * emitting MEDIA_PAUSE around HDMI-CEC and voice-remote activity after the
 * picture is up too, so one landed, paused the session and nothing resumed it.
 * The guard is now a ten-second window that opens with the load's first play
 * (Exo) or with the file open (MPV), one shared constant.
 *
 * F2 - the countdown only READS the paused flag; the pause came from MPV's audio
 * focus listener, which paused on LOSS / LOSS_TRANSIENT and had no GAIN branch,
 * so a notification or a system sound parked the session until the viewer
 * pressed play. Exo handles focus internally and needs no change.
 *
 * Read out of the source, like this folder's other engine contracts, because
 * exercising any of it needs a device, a remote and an interruption.
 */
class SpuriousPauseAndFocusResumeContractTest {

    private val native by lazy { read(NATIVE) }
    private val mpv by lazy { read(MPV) }

    // ── F1: one window, defined once ──────────────────────────────────────

    @Test
    fun `the window is one shared ten-second constant`() {
        assertTrue(
            "one named constant, on the engine the other already reads its shared " +
                "constants from",
            native.contains("internal const val SPURIOUS_PAUSE_GUARD_MS = 10_000L")
        )
        assertTrue(
            "and MPV uses it rather than carrying a second copy",
            mpv.contains("SPURIOUS_PAUSE_GUARD_MS") &&
                !mpv.contains("const val SPURIOUS_PAUSE_GUARD_MS")
        )
        assertEquals(
            "and exactly one use of it - the window IS that constant, with no scale of its own",
            1,
            Regex("SPURIOUS_PAUSE_GUARD_MS").findAll(mpv).count()
        )
    }

    @Test
    fun `both engines measure the window on the monotonic clock`() {
        assertTrue(
            "Exo arms and reads it from elapsedRealtime, so a wall-clock jump (NTP, a " +
                "timezone change) cannot swallow a real pause",
            native.contains("spuriousPauseGuardUntilMs = SystemClock.elapsedRealtime() + SPURIOUS_PAUSE_GUARD_MS") &&
                native.contains("SystemClock.elapsedRealtime() < spuriousPauseGuardUntilMs")
        )
        assertTrue(
            "and so does MPV",
            // Whitespace-collapsed: the arm sits inside `onPictureReady`'s guard
            // now (Player M1) and wraps there (spec audit 2026-11-18).
            mpv.replace(Regex("\\s+"), " ").contains(
                "spuriousPauseGuardUntilMs = SystemClock.elapsedRealtime() + SPURIOUS_PAUSE_GUARD_MS"
            ) &&
                mpv.contains("SystemClock.elapsedRealtime() < spuriousPauseGuardUntilMs")
        )
    }

    // ── F1, Exo: the window opens with the load's first play ───────────────

    @Test
    fun `Exo opens the window on the load's first play, not on every first frame`() {
        val firstFrame = block(native, "private fun markFirstFrameRendered() {")
        val read = firstFrame.indexOf("val firstPlayOfThisLoad = !hasPlayedOnce")
        val latch = firstFrame.indexOf("hasPlayedOnce = true")
        val arm = firstFrame.indexOf("if (firstPlayOfThisLoad) armSpuriousPauseGuard()")
        assertTrue("the load's first play is read from the latch", read >= 0)
        assertTrue("before the latch is set, which is the whole point", read < latch)
        assertTrue("and it is what opens the window", arm > latch)
        assertTrue(
            "the arm itself is the one place that sets the deadline",
            block(native, "private fun armSpuriousPauseGuard() {")
                .contains("spuriousPauseGuardUntilMs = SystemClock.elapsedRealtime() + SPURIOUS_PAUSE_GUARD_MS")
        )
    }

    @Test
    fun `Exo's two MEDIA_PAUSE arms both consult the window`() {
        val surfaceArm = native.slice("KeyEvent.KEYCODE_MEDIA_PAUSE -> {", "KeyEvent.KEYCODE_MEDIA_PLAY -> {")
        assertTrue(
            "the surface listener swallows before the first play AND inside the window",
            surfaceArm.contains("if (!hasPlayedOnce || spuriousPauseGuardActive()) true else {")
        )
        assertTrue(
            "and pauses normally once the window has closed",
            surfaceArm.contains("exoPlayer?.pause(); showControls(); true")
        )
        val activityArm = native.slice(
            "override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {",
            "KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {"
        )
        assertTrue(
            "the activity fallback - external remotes deliver keys when the surface has " +
                "no focus - carries the same rule",
            activityArm.contains("if (hasPlayedOnce && !spuriousPauseGuardActive()) {") &&
                activityArm.contains("exoPlayer?.pause(); showControls()")
        )
    }

    @Test
    fun `the first-play latch keeps its other jobs`() {
        assertEquals(
            "the guard resets the latch nowhere: splash dismissal and the rebuffer branching " +
                "still own it, and only the two load resets write false",
            3,
            Regex("\\bhasPlayedOnce = false\\b").findAll(native).count()
        )
        assertTrue(
            "still set on the first frame",
            block(native, "private fun markFirstFrameRendered() {").contains("hasPlayedOnce = true")
        )
        assertTrue(
            "a manual source switch still resets it (automatic ones deliberately do not)",
            native.contains("if (!isAutoRecovery || !hasPlayedOnce) {")
        )
    }

    // ── F1, MPV: the window opens with the first PICTURE of a file ─────────

    @Test
    fun `MPV opens the window once per file and both pause arms honour it`() {
        val fileLoaded = block(mpv, "private fun onFileLoaded(mediaTitle: String?) {")
        assertTrue(
            "a new file CLOSES the previous window",
            fileLoaded.contains("spuriousPauseGuardUntilMs = 0L") &&
                fileLoaded.contains("spuriousPauseGuardArmedForFile = false")
        )
        // Spec audit 2026-11-18, Player M1: arming at FILE_LOADED spent the
        // window while a slow source was still buffering.
        assertTrue(
            "and the FIRST PICTURE opens it, once per file, as the main player does",
            mpv.contains(
                "if (!spuriousPauseGuardArmedForFile) {\n" +
                    "                spuriousPauseGuardArmedForFile = true"
            ) &&
                mpv.contains(
                    "spuriousPauseGuardUntilMs =\n" +
                        "                    SystemClock.elapsedRealtime() + SPURIOUS_PAUSE_GUARD_MS"
                )
        )
        val keyArm = mpv.slice("KeyEvent.KEYCODE_MEDIA_PAUSE -> {", "KeyEvent.KEYCODE_MEDIA_NEXT -> {")
        assertTrue(
            "the media key swallows before the playhead moves and inside the window",
            keyArm.contains("if (!sessionHasPlayed() || spuriousPauseGuardActive()) return true")
        )
        val sessionPause = mpv.slice("onPause = {", "onSkipNext =")
        assertTrue(
            "and so does the MediaSession's pause - the same Fire OS events arrive through it",
            sessionPause.contains("if (sessionHasPlayed() && !spuriousPauseGuardActive()) {")
        )
        assertTrue(
            "while sessionHasPlayed keeps its own meaning for the completion rules",
            mpv.contains("private fun sessionHasPlayed(): Boolean =") &&
                mpv.contains("surface?.positionMs() ?: 0L }.getOrDefault(0L) > 0L")
        )
    }

    // ── F2: MPV resumes when focus returns ────────────────────────────────

    @Test
    fun `MPV has a gain branch, and only resumes its own pause`() {
        val focus = block(mpv, "private fun requestAudioFocus() {")
        assertTrue(
            "the listener still stops playback on a loss",
            focus.contains("AudioManager.AUDIOFOCUS_LOSS,") &&
                focus.contains("AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {")
        )
        assertTrue(
            "a loss only claims the pause when it actually stopped a PLAYING session, so a " +
                "viewer who had already paused keeps their pause",
            focus.contains("if (surface?.isPaused() == false) {") &&
                focus.indexOf("pausedByFocusLoss = true") > focus.indexOf("if (surface?.isPaused() == false) {")
        )
        assertTrue(
            "and a gain resumes only that pause",
            focus.contains("AudioManager.AUDIOFOCUS_GAIN -> {") &&
                focus.contains("if (pausedByFocusLoss && foreground) {")
        )
        assertTrue(
            "clearing the flag before resuming, so one gain resumes once",
            focus.indexOf("pausedByFocusLoss = false") in
                (focus.indexOf("if (pausedByFocusLoss && foreground) {") + 1)..focus.indexOf("surface?.setPaused(false)")
        )
        assertTrue(
            "and never in the background: a call that ends behind the app must not start audio",
            focus.contains("lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)")
        )
    }

    @Test
    fun `every manual pause or play clears the focus pause`() {
        assertTrue(
            "the chrome / OK toggle",
            block(mpv, "private fun togglePlayPauseFromControls(): Boolean {")
                .contains("pausedByFocusLoss = false")
        )
        val keys = mpv.slice("KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {", "KeyEvent.KEYCODE_MEDIA_NEXT -> {")
        assertEquals(
            "the three transport keys - toggle, PLAY and PAUSE - each clear it",
            3,
            Regex("pausedByFocusLoss = false").findAll(keys).count()
        )
        assertTrue(
            "the MediaSession's play",
            mpv.slice("onPlay = {", "onPause = {").contains("pausedByFocusLoss = false")
        )
        assertTrue(
            "the MediaSession's pause",
            mpv.slice("onPause = {", "onSkipNext =").contains("pausedByFocusLoss = false")
        )
        assertTrue(
            "and the background pause, which is not a focus pause at all",
            mpv.contains("pausedByFocusLoss = false\n            surface?.setPaused(true)")
        )
    }

    @Test
    fun `Exo's MediaSession route carries the same guard`() {
        // Spec audit 2026-11-18, Player H1: media3 routes an external pause
        // straight to the player wrapper, so the two key arms could be guarded
        // and a session pause still parked the session.
        val wrapper = native.substringAfter("sessionPlayer = object : ForwardingPlayer(base)")
            .substringBefore("mediaSession =")
        assertTrue(
            "a session pause is swallowed inside the window",
            wrapper.contains("override fun pause() {\n                    if (spuriousPauseGuardActive()) return")
        )
        assertTrue(
            "and so is a session setPlayWhenReady(false) - media3 reaches either",
            wrapper.contains("if (!playWhenReady && spuriousPauseGuardActive()) return")
        )
    }

    // ── F3 / F4: what must not change ────────────────────────────────────

    @Test
    fun `the Next Up countdown is untouched and only reads the paused flag`() {
        assertTrue(
            "MPV still holds its own count and says so",
            mpv.contains("nextUpCountdown?.text = \"Paused - playing next when you resume\"") &&
                // The one exemption added since is the DECLARED END (mpv pauses
                // itself at EOF, see MpvNextUpEndDeclaredContractTest); the flag
                // is the player's own state, not the session pause guard this
                // test is about.
                mpv.contains("if (surface?.isPaused() != false && !endDeclared) {")
        )
        assertTrue(
            "so does Exo",
            native.contains("nextUpCountdown.text = \"Paused - playing next when you resume\"")
        )
        listOf(
            "private val nextUpCountdownRunnable = object : Runnable {" to mpv,
            "private val nextUpCountdownRunnable = object : Runnable {" to native
        ).forEach { (declaration, source) ->
            assertFalse(
                "the countdown must not learn about the pause guard - it counts what the " +
                    "player is doing, and F1/F2 are what leave it playing",
                block(source, declaration).contains("spuriousPauseGuard")
            )
        }
    }

    @Test
    fun `Exo owns focus now, so ONE listener may pause or resume around it`() {
        // REVERSED (spec audit 2026-11-18, Player M3). This used to pin the
        // opposite - "media3 already handles focus, so no listener of ours" -
        // and it was right about media3 handling it and wrong about what that
        // handling does: media3 resumes after a TRANSIENT loss and treats a
        // permanent AUDIOFOCUS_LOSS as the end of the session, so an
        // interruption that took focus away for good left Exo paused while MPV
        // resumed. The activity owns focus on both engines now, which is the
        // only way the two behave alike.
        assertTrue(
            "Exo himself no longer manages focus",
            native.contains("setAudioAttributes(audioAttrs, false)")
        )
        assertFalse(
            "and the hand-off it used to take is gone, or two policies would fight",
            native.contains("setAudioAttributes(audioAttrs, true)")
        )
        assertTrue(
            "the session's own listener is the one policy - see AudioFocusParityContractTest",
            native.contains("setOnAudioFocusChangeListener")
        )
        assertFalse(
            "and F1/F2 are untouched by it: the guard is still the key/session path's",
            block(native, "private fun requestAudioFocus()").contains("spuriousPauseGuard")
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** One function's source, by matching its declaration and balancing braces. */
    private fun block(source: String, declaration: String): String {
        val at = source.indexOf(declaration)
        require(at >= 0) { "missing: $declaration" }
        val bodyStart = source.indexOf("{", at)
        require(bodyStart > at) { "no body: $declaration" }
        var depth = 0
        for (i in bodyStart until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(at, i + 1)
                }
            }
        }
        throw AssertionError("unbalanced: $declaration")
    }

    private fun read(relative: String): String {
        val file = File(findSourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun String.slice(startMarker: String, endMarker: String): String {
        val start = indexOf(startMarker)
        assertTrue("$startMarker is missing", start >= 0)
        val end = indexOf(endMarker, start)
        assertTrue("$endMarker must follow $startMarker", end > start)
        return substring(start, end)
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

    private companion object {
        const val NATIVE = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        const val MPV = "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
    }
}
