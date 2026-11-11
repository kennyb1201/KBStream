package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The player's three minors (spec 2026-11-11).
 *
 * All three are the same shape of bug: a value the code already knows how to
 * answer was not consulted at one writer.
 *
 *  - live TV's chrome printed "Ends at <now>" because a channel has no
 *    duration, and the estimate's arithmetic turned that into zero remaining;
 *  - Exo's audio picker "Default" cleared the override and stopped, so the
 *    language preference only came back on the next READY (MPV already
 *    re-applied it);
 *  - the Up Next countdown is a wall-clock Handler, so pausing in its last
 *    seconds still launched the next episode on a paused session.
 *
 * Source-reading, like the rest of this module's contracts: the writers live
 * inside two Android-bound activities and one view-owning chrome. Every pin
 * below fails against the 0.9.223 source.
 */
class PlayerMinorsContractTest {

    private val chrome by lazy { source(CHROME) }
    private val exo by lazy { source(EXO) }
    private val mpv by lazy { source(MPV) }
    private val bridge by lazy { source(BRIDGE) }

    // ── F1: an estimate needs a duration ───────────────────────────────────

    @Test
    fun `the chrome hides the estimate when the duration is unknown`() {
        val clock = chrome
            .substringAfter("private fun refreshClock(")
            .substringBefore("companion object {")
        val guard = clock.indexOf("if (durationMs <= 0L) {")
        val hidden = clock.indexOf("endsView.visibility = View.GONE")
        val shown = clock.indexOf("endsView.visibility = View.VISIBLE")
        assertTrue("F1: the chrome guards the estimate on the duration", guard >= 0)
        assertTrue(
            "F1: and hides the line instead of printing the current clock as its end",
            hidden in guard until shown
        )
        assertTrue(
            "F1: a known duration still shows the estimate, unchanged",
            shown >= 0 && clock.contains("endsView.text = \"Ends at \$endsAtTime\$suffix\"")
        )
        assertTrue(
            "F1: the same rule as the seek bar, which already reads it as no duration",
            chrome.contains("if (durationMs > 0L) {") &&
                chrome.contains("(positionMs * SEEKBAR_MAX / durationMs)")
        )
        assertTrue(
            "F1: and raising the bar does not fade a hidden estimate back in for a frame",
            chrome.contains("if (endsAt?.visibility != View.GONE) KbViewMotion.fadeIn(endsAt)")
        )
    }

    @Test
    fun `the activity's own writer follows the same rule`() {
        val clock = exo
            .substringAfter("private fun updateClock() {")
            .substringBefore("/** Renders the current source's badge chips. */")
        assertTrue(
            "F1: unset is not a duration either - TIME_UNSET is what a live channel reports",
            clock.contains("if (durationMs <= 0L || durationMs == C.TIME_UNSET) {")
        )
        assertTrue(
            "F1: it writes the SAME shared views the chrome does, so both writers obey it",
            clock.contains("endsAtClock.visibility = View.GONE") &&
                clock.contains("endsAtClock.visibility = View.VISIBLE") &&
                clock.contains("endsAtClock.text = \"Ends at \$endsAt\${playerSleepSuffix()}\"") &&
                exo.contains("endsAtClock = findViewById(R.id.chrome_ends_at)")
        )
    }

    @Test
    fun `the sleep suffix rides the clock when the estimate is hidden`() {
        assertTrue(
            "F1: the suffix is one shared function, because both writers paint the clock text",
            chrome.contains("internal fun playerSleepSuffix(): String") &&
                chrome.contains("val sleep = SleepTimer.state.value")
        )
        assertTrue(
            "F1: the chrome's hidden branch still shows it, on the clock line",
            chrome.contains("clockView.text = DateFormats.now(formatter) + suffix") &&
                chrome.contains("val suffix = playerSleepSuffix()")
        )
        assertTrue(
            "F1: and so does the activity's, which would otherwise wipe it a second later",
            chrome.contains("playerSleepSuffix()") &&
                exo.contains("playerClock.text = DateFormats.now(formatter) + playerSleepSuffix()")
        )
        assertTrue(
            "F1: while a known duration appends it to the ends line, as before",
            chrome.contains("endsView.text = \"Ends at \$endsAtTime\$suffix\"")
        )
    }

    // ── F2: Exo's "Default" restores the language now ──────────────────────

    @Test
    fun `the Exo blank branch re-applies the language after clearing the override`() {
        val pick = bridge
            .substringAfter("fun chooseAudioTrack(context: Context, signature: String) {")
            .substringBefore("val match = findTrack(player, C.TRACK_TYPE_AUDIO, signature)")
        val clear = pick.indexOf(".clearOverridesOfType(C.TRACK_TYPE_AUDIO)")
        val apply = pick.indexOf("applyLanguage(player, C.TRACK_TYPE_AUDIO, effectiveAudioLanguage())")
        assertTrue("F2: the override is still cleared first", clear >= 0)
        assertTrue(
            "F2: and the language preference is re-applied immediately, not at the next READY",
            apply > clear
        )
        assertTrue(
            "F2: before the list refresh, so the panel shows the track that was chosen",
            pick.indexOf("refreshAudioTracks()") > apply
        )
        assertTrue(
            "F2: the non-blank branch is untouched - a remembered specific track still wins",
            bridge.indexOf("val match = findTrack(player, C.TRACK_TYPE_AUDIO, signature)") >
                bridge.indexOf("applyLanguage(player, C.TRACK_TYPE_AUDIO, effectiveAudioLanguage())") &&
                bridge.contains("setOverrideForType(TrackSelectionOverride(")
        )
    }

    @Test
    fun `a blank effective language leaves the file's own choice alone`() {
        assertTrue(
            "F2: \"Auto\" with no global default is a blank language",
            bridge.contains(
                "private fun effectiveAudioLanguage(): String = audioLanguage.ifBlank { globalAudioLanguage }"
            )
        )
        assertTrue(
            "F2: and applyLanguage answers false for audio without touching the selection - the " +
                "same call reapplyLanguageSelection already makes in this case",
            bridge.contains("if (type == C.TRACK_TYPE_AUDIO && language.isBlank()) return false") &&
                bridge.contains("applyLanguage(player, C.TRACK_TYPE_AUDIO, effectiveAudioLanguage())")
        )
        assertTrue(
            "F2: MPV's picker is the reference and is unchanged",
            mpv.contains("selectAudioLanguage(effectiveAudioLanguage())")
        )
    }

    // ── F3: a paused countdown holds ───────────────────────────────────────

    @Test
    fun `a paused Exo session holds the countdown instead of advancing`() {
        val runnable = exoCountdown()
        val sleep = runnable.indexOf("sleepTimerBlocksAutoAdvance(SleepTimer.state.value)")
        val pause = runnable.indexOf("if (exoPlayer?.playWhenReady != true) {")
        val decrement = runnable.indexOf("nextUpCountdownRemaining--")
        assertTrue("F3: the pause guard exists", pause >= 0)
        assertTrue(
            "F3: the sleep timer still wins - its stop-and-clear block comes first",
            sleep in 0 until pause
        )
        assertTrue(
            "F3: and the guard sits before the decrement, so a paused tick does not count down",
            pause < decrement
        )
        assertTrue(
            "F3: the hold re-posts the tick rather than returning for good",
            runnable.substring(pause, decrement).contains("nextUpCountdownHandler.postDelayed(this, 1_000L)")
        )
        assertTrue(
            "F3: and names itself, so the card does not look like it is still counting",
            runnable.substring(pause, decrement).contains(
                "nextUpCountdown.text = \"Paused - playing next when you resume\""
            )
        )
        assertTrue(
            "F3: playWhenReady, not isPlaying - a rebuffer is still playback and keeps ticking",
            !runnable.contains("if (exoPlayer?.isPlaying != true)") &&
                runnable.contains("nextUpCountdownRemaining--")
        )
        assertTrue(
            "F3: arming and the handoff are untouched",
            exo.contains("nextUpHandoffArmed = true") &&
                exo.contains("fun armNextUpAutoAdvance(") &&
                exo.contains("launchNextEpisode(")
        )
    }

    @Test
    fun `the MPV twin holds on its own paused flag`() {
        val runnable = mpvCountdown()
        val sleep = runnable.indexOf("sleepTimerBlocksAutoAdvance(SleepTimer.state.value)")
        val pause = runnable.indexOf("if (surface?.isPaused() != false) {")
        val decrement = runnable.indexOf("nextUpCountdownRemaining--")
        assertTrue("F3: the MPV pause guard exists", pause >= 0)
        assertTrue("F3: after the sleep-timer block", sleep in 0 until pause)
        assertTrue("F3: and before the decrement", pause < decrement)
        assertTrue(
            "F3: it holds the same way - re-post and a held line",
            runnable.substring(pause, decrement).contains(
                "nextUpCountdownHandler.postDelayed(this, 1_000L)"
            ) &&
                runnable.substring(pause, decrement).contains(
                    "nextUpCountdown?.text = \"Paused - playing next when you resume\""
                )
        )
        assertTrue(
            "F3: the flag it reads is the engine's own playWhenReady equivalent",
            mpv.contains("isPlaybackActive = { surface?.isPaused() == false }")
        )
        assertTrue(
            "F3: the sleep timer's stop-and-clear behaviour is unchanged on both engines",
            runnable.contains("nextUpCountdownHeld = false") &&
                runnable.contains("nextUpCountdownRemaining = 0") &&
                runnable.contains("\"Stopping here - sleep timer\"")
        )
    }

    // ── slices ─────────────────────────────────────────────────────────────

    private fun exoCountdown(): String =
        exo
            .substringAfter("private val nextUpCountdownRunnable = object : Runnable {")
            .substringBefore("// Scopes")

    private fun mpvCountdown(): String =
        mpv
            .substringAfter("private val nextUpCountdownRunnable = object : Runnable {")
            .substringBefore("/**\n     * Disarms the Up Next countdown.")

    private fun source(relative: String): String {
        val file = File(findSourceRoot(), relative)
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

    private companion object {
        const val CHROME = "com/kennyb1201/kbstream/ui/player/PlayerChrome.kt"
        const val EXO = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        const val MPV = "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
        const val BRIDGE = "com/kennyb1201/kbstream/ui/player/PlayerTrackBridge.kt"
    }
}
