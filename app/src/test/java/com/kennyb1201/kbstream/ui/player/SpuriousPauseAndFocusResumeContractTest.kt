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

    // ── F3 (2026-11-24): the window opens at source-ready ──────────────────

    /**
     * Round 3 opened the window in `markFirstFrameRendered`. The 2026-11-24
     * Stick paste is why that was wrong: the load's first-frame callback never
     * fired, so the window never opened on exactly the load it guards. READY is
     * the first playable moment and does not depend on a frame being painted;
     * the pre-frame latch still covers everything before it.
     */
    @Test
    fun `Exo opens the window at source-ready, not at the first frame`() {
        val ready = native.slice(
            "if (firstReadyAtMs == 0L) {",
            "if (rebufferStartedAtMs != 0L) {"
        )
        assertTrue(
            "the first READY of the load opens the window",
            ready.contains("armSpuriousPauseGuard()")
        )
        val firstFrame = block(native, "private fun markFirstFrameRendered() {")
        assertTrue(
            "the latch keeps its first-frame meaning",
            firstFrame.contains("hasPlayedOnce = true")
        )
        assertFalse(
            "and the window no longer hangs off a callback that may never arrive",
            firstFrame.contains("armSpuriousPauseGuard()")
        )
        assertTrue(
            "the arm itself is the one place that sets the deadline",
            block(native, "private fun armSpuriousPauseGuard() {")
                .contains("spuriousPauseGuardUntilMs = SystemClock.elapsedRealtime() + SPURIOUS_PAUSE_GUARD_MS")
        )
    }

    /**
     * Every landing path goes through one recorder, which is what makes the
     * 2026-11-24 F2 tripwire ("no pause traced") mean what it says.
     */
    @Test
    fun `every pause that lands is recorded through one helper`() {
        val helper = block(native, "private fun notePauseLanded(path: String) {")
        assertTrue(
            "the helper writes the line and clears the tripwire's question",
            helper.contains("pauseTracedForLoad = true") &&
                helper.contains("PlaybackSessionTrace.note(\"pause landed: \$path\")")
        )
        listOf("toggle", "session", "session-set-play-when-ready", "focus-loss", "media-pause-key")
            .forEach { path ->
                assertTrue(
                    "Exo's $path pause is recorded through it",
                    native.contains("notePauseLanded(\"$path\")")
                )
            }
        assertEquals(
            "and no Exo path writes a landed line behind the helper's back",
            1,
            Regex(Regex.escape("PlaybackSessionTrace.note(\"pause landed:"))
                .findAll(native)
                .count()
        )
    }

    /** The tripwire the next field paste is read against (F2). */
    @Test
    fun `a stop with no traced pause says so at READY and at the AFR confirm`() {
        val tripwire = block(native, "private fun noteStoppedWithoutPause(point: String) {")
        assertTrue(
            "it stays quiet when this load already named a pauser",
            tripwire.contains("if (pauseTracedForLoad) return")
        )
        assertTrue(
            "and says exactly what the paste needs to know",
            tripwire.contains(
                "PlaybackSessionTrace.note(\"playWhenReady false at \$point, no pause traced\")"
            )
        )
        val ready = native.slice(
            "if (firstReadyAtMs == 0L) {",
            "if (rebufferStartedAtMs != 0L) {"
        )
        assertTrue(
            "READY asks it, because playWhenReady is created true for every load but an actor return",
            ready.contains("noteStoppedWithoutPause(\"READY\")")
        )
        val confirmHook = native.slice("onConfirmDeferred = {", "preferWholeRate =")
        assertTrue(
            "and so does the matcher's deferred confirm, which is where the paste first saw the stop",
            confirmHook.contains("noteStoppedWithoutPause(\"AFR confirm\")")
        )
        assertTrue(
            "an actor return is opened paused on purpose, so neither point calls it",
            native.contains("if (!fromActorReturn) noteStoppedWithoutPause(\"AFR confirm\")") &&
                native.contains("if (!fromActorReturn && exoPlayer?.playWhenReady == false) {")
        )
    }

    // ── Round 5 (2026-11-24): the pauser the tripwire caught ──────────────

    /**
     * Round 4's tripwire proved the stop came from outside every traced path on
     * TCL too, so every transition now names its caller - and an unnameable one
     * says so. `unknown` is a finding, not a failure: it scopes the next spec to
     * the OS / MediaSession layer instead of another guard.
     */
    @Test
    fun `every playWhenReady transition names its caller`() {
        assertTrue(
            "the line is the transition's own name",
            block(native, "private fun notePlayWhenReadyFalse(caller: String) {")
                .contains("PlaybackSessionTrace.note(\"playWhenReady false: \$caller\")")
        )
        assertTrue(
            "a pause that lands names its path",
            block(native, "private fun notePauseLanded(path: String) {")
                .contains("notePlayWhenReadyFalse(path)")
        )
        assertTrue(
            "an actor return is labelled rather than silenced",
            native.contains("notePlayWhenReadyFalse(\"actor-return\")")
        )
        assertTrue(
            "a rebuild that restores a pause names itself",
            native.contains("if (!restorePlayWhenReady) notePlayWhenReadyFalse(\"rebuild\")")
        )
        assertTrue(
            "and the tripwires land in the bucket that scopes the next fix",
            block(native, "private fun noteStoppedWithoutPause(point: String) {")
                .contains("notePlayWhenReadyFalse(\"unknown\")")
        )
    }

    /**
     * Round 5, F2: a rebuild used to build its player with a default. The
     * session's intent lives on the instance being released, so it is captured
     * BEFORE that release and restored on the new one - and the four fields that
     * make that observable are pinned here.
     */
    @Test
    fun `a rebuild restores the session's play intent`() {
        val recreate = block(native, "private fun recreatePlayer(settleMs: Long = 0L) {")
        val capture = recreate.indexOf("rebuildPlayIntent = exoPlayer?.playWhenReady ?: sessionPlayIntent")
        val release = recreate.indexOf("exoPlayer?.release()")
        assertTrue("the intent is captured", capture >= 0)
        assertTrue("before the instance carrying it is released", capture in 0 until release)

        val create = block(native, "private fun createPlayer() {")
        assertTrue(
            "and the new player is handed it instead of a default",
            create.contains("val restorePlayWhenReady = restoredIntent ?: !fromActorReturn") &&
                create.contains("rebuildPlayIntent = null")
        )
        assertTrue(
            "both builder branches take it",
            Regex(Regex.escape("playWhenReady = restorePlayWhenReady"))
                .findAll(create)
                .count() == 2
        )
        assertTrue(
            "with a line at creation...",
            native.contains("\"rebuild: playWhenReady=\$restorePlayWhenReady restored\"")
        )
        assertTrue(
            "...and one at the new instance's first READY",
            native.contains("\"rebuild: playWhenReady=\${exoPlayer?.playWhenReady} at READY\"")
        )
        assertTrue(
            "the intent is also tracked by every path that decides it",
            native.contains("sessionPlayIntent = false") &&
                native.contains("sessionPlayIntent = true")
        )
    }

    /**
     * Round 5, F3: the invariant enforced. A first frame with a stopped player
     * and no pause the app made IS the field symptom, so the session resumes
     * itself - except for the two pauses a human owns.
     */
    @Test
    fun `a first frame with an unowned stop resumes the session`() {
        val callback = block(native, "override fun onRenderedFirstFrame() {")
        val gate = callback.indexOf("playWhenReady false at first frame, no pause traced")
        assertTrue("the line names the symptom", gate >= 0)
        assertTrue(
            "the resume follows it in the same block",
            callback.indexOf("exoPlayer?.play()") > gate &&
                callback.indexOf("auto-resumed at first frame") > gate
        )
        assertTrue(
            "an actor return is exempt - it is opened paused on purpose",
            callback.contains("!fromActorReturn")
        )
        assertTrue(
            "and so is a pause that was traced for this load: the viewer's own",
            callback.contains("!pauseTracedForLoad")
        )
        assertTrue(
            "it runs once per load, ahead of the latch",
            callback.contains("if (!firstFrameRendered")
        )
    }

    /**
     * F4 asks one question and changes nothing: does `onRenderedFirstFrame`
     * reach the activity on a Stick? Answered "fired" or "missing after 10s".
     */
    @Test
    fun `the first-frame callback is reported once per load`() {
        val callback = block(native, "override fun onRenderedFirstFrame() {")
        assertTrue(
            "the callback names itself when it arrives",
            callback.contains("PlaybackSessionTrace.note(\"first-frame callback: fired\")")
        )
        val watchdog = block(native, "private val firstFrameCallbackWatchdog = Runnable {")
        assertTrue(
            "and the watchdog names its absence instead",
            watchdog.contains("first-frame callback: missing after 10s") &&
                watchdog.contains("if (firstFrameCallbackNoted) return@Runnable")
        )
        val ready = native.slice(
            "if (firstReadyAtMs == 0L) {",
            "if (rebufferStartedAtMs != 0L) {"
        )
        assertTrue(
            "posted from READY, which is the clock the line is measured against",
            ready.contains("handler.postDelayed(") &&
                ready.contains("FIRST_FRAME_CALLBACK_DIAGNOSTIC_MS")
        )
    }

    @Test
    fun `Exo's two MEDIA_PAUSE arms both consult the window`() {
        val surfaceArm = native.slice("KeyEvent.KEYCODE_MEDIA_PAUSE -> {", "KeyEvent.KEYCODE_MEDIA_PLAY -> {")
        assertTrue(
            "the surface listener swallows before the first play AND inside the window",
            surfaceArm.contains("if (!hasPlayedOnce || spuriousPauseGuardActive()) {") &&
                surfaceArm.contains("noteSpuriousPauseSwallowed(\"media-pause-key\")")
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
            keyArm.contains("if (!sessionHasPlayed() || spuriousPauseGuardActive()) {") &&
                keyArm.contains("noteSpuriousPauseSwallowed(\"media-pause-key\")")
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
            "a session pause is swallowed before the first frame and inside the window",
            wrapper.contains(
                "override fun pause() {\n" +
                    "                    if (!fromActorReturn && (!hasPlayedOnce || spuriousPauseGuardActive())) {"
            ) && wrapper.contains("noteSpuriousPauseSwallowed(\"session\")")
        )
        assertTrue(
            "and so is a session setPlayWhenReady(false) - media3 reaches either",
            wrapper.contains(
                "if (!playWhenReady && !fromActorReturn &&\n" +
                    "                        (!hasPlayedOnce || spuriousPauseGuardActive())"
            ) && wrapper.contains("noteSpuriousPauseSwallowed(\"session-set-play-when-ready\")")
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
        assertTrue(
            "and F1/F2 now cover it: a loss during the load guard is swallowed, not " +
                "allowed to park the session",
            block(native, "private fun requestAudioFocus()").contains("spuriousPauseGuardActive()") &&
                block(native, "private fun requestAudioFocus()")
                    .contains("noteSpuriousPauseSwallowed(\"focus-loss\")")
        )
    }

    // ── F2 / F3: focus loss and the combined key join the load guard ──────

    @Test
    fun `both engines swallow focus loss and toggle-to-pause during the load`() {
        val nativeFocus = block(native, "private fun requestAudioFocus() {")
        assertTrue(
            "Exo's focus loss consults the same pre-frame + window guard",
            nativeFocus.contains("if (!hasPlayedOnce || spuriousPauseGuardActive()) {") &&
                nativeFocus.contains("noteSpuriousPauseSwallowed(\"focus-loss\")")
        )
        val mpvFocus = block(mpv, "private fun requestAudioFocus() {")
        assertTrue(
            "and so does MPV's",
            mpvFocus.contains("if (!sessionHasPlayed() || spuriousPauseGuardActive()) {") &&
                mpvFocus.contains("noteSpuriousPauseSwallowed(\"focus-loss\")")
        )
        assertEquals(
            "both Exo combined-key arms swallow a toggle-to-pause during the load",
            2,
            Regex("noteSpuriousPauseSwallowed\\(\"play-pause-key\"\\)").findAll(native).count()
        )
        assertTrue(
            "MPV's combined key does too",
            mpv.contains("noteSpuriousPauseSwallowed(\"play-pause-key\")")
        )
    }

    @Test
    fun `actor-return keeps its intentional pre-frame pause`() {
        assertTrue(
            "the wrapper must not swallow the pause that opens an actor-return session",
            native.contains("!fromActorReturn && (!hasPlayedOnce || spuriousPauseGuardActive())") &&
                native.contains(
                    "if (!playWhenReady && !fromActorReturn &&\n" +
                        "                        (!hasPlayedOnce || spuriousPauseGuardActive())"
                )
        )
    }

    @Test
    fun `landed external pauses are traced on both engines`() {
        assertTrue(
            "Exo's session wrapper names both landed forms",
            native.contains("notePauseLanded(\"session\")") &&
                native.contains("notePauseLanded(\"session-set-play-when-ready\")")
        )
        assertTrue(
            "both focus listeners name a landed pause",
            native.contains("notePauseLanded(\"focus-loss\")") &&
                mpv.contains("pause landed: focus-loss")
        )
        assertTrue(
            "MPV's media session and keys name theirs",
            mpv.contains("pause landed: session") &&
                mpv.contains("pause landed: media-pause-key") &&
                mpv.contains("pause landed: play-pause-key")
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
