package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A session that reached its own end stays ended.
 *
 * Media transport keys bypass focus entirely. With the end panel (Up Next /
 * because-you-watched) up, focus sits on the panel's buttons, so a stray
 * PLAY/PAUSE from a remote, a Bluetooth remote, a headset, an assistant, or a
 * phone remote app never reaches the UI. It goes straight to the player, and at
 * STATE_ENDED `togglePlayPause()` reads `isPlaying == false`, flips
 * `playWhenReady` back to true, and the episode restarts from 0 — no splash, no
 * rebuild, nothing in the UI to explain it.
 *
 * `playbackEndedHandled` / `endedHandled` is the "this session finished" latch
 * both players already keep. This class pins that every play path consults it
 * before it can start playback again:
 *
 *  - the native toggle, and both raw PLAY key handlers (the PlayerView key
 *    listener, and the activity-level `onKeyDown` fallback that sees the key
 *    when the video surface does not hold focus),
 *  - the native `MediaSession`, which is how a Bluetooth remote, a headset or an
 *    assistant drives transport controls, and which media3 answers by calling
 *    `seekToDefaultPosition()` and then `play()`,
 *  - mpv's media-key branch and its own `MpvMediaSession` `onPlay`.
 *
 * Only the restart is refused. An explicit seek still moves the playhead, the
 * end panels and the handoff are untouched, and backing out and playing again
 * runs against a fresh player — so the latch is pinned to reset on every
 * (re)build.
 *
 * This is wiring inside Android activities, so a device cannot be avoided here.
 * What a contract test can pin is the shape and the order.
 */
class PlaybackEndedKeyGuardContractTest {

    private fun readSource(path: String): String {
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

    /**
     * The text of the function starting at [signature] up to the next member.
     * The next member is a line indented by exactly four spaces - not the first
     * "newline + spaces", which the body's own deeper indent would match.
     */
    private fun functionBody(source: String, signature: String): String {
        val src = readSource(source)
        val start = src.indexOf(signature)
        assertTrue("source missing function: $signature", start >= 0)
        val rest = src.substring(start + signature.length)
        val end = Regex("\\n {4}\\S").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    /** Source with per-line indentation stripped, so nested guards can be matched. */
    private fun normalized(raw: String): String = raw.lines().joinToString("\n") { it.trim() }

    @Test
    fun `native toggle refuses a post-end play press before it flips play state`() {
        val body = functionBody(NATIVE, "private fun togglePlayPause() {")
        assertTrue(
            "togglePlayPause must return early once the session has ended",
            body.contains("if (playbackEndedHandled) return")
        )
        val guard = body.indexOf("playbackEndedHandled")
        val flip = body.indexOf("playWhenReady")
        assertTrue(
            "the ended guard must precede the playWhenReady flip",
            guard in 0 until flip
        )
    }

    @Test
    fun `both native raw PLAY handlers check the ended flag before playing`() {
        val branches = Regex(
            "KeyEvent\\.KEYCODE_MEDIA_PLAY -> \\{(.{0,240}?)exoPlayer\\?\\.play\\(\\)",
            RegexOption.DOT_MATCHES_ALL
        ).findAll(readSource(NATIVE)).toList()
        assertTrue(
            "expected the PlayerView listener and the activity onKeyDown fallback, " +
                "found ${branches.size} raw PLAY branches",
            branches.size == 2
        )
        branches.forEachIndexed { index, branch ->
            assertTrue(
                "raw PLAY handler #$index must check the ended flag before play()",
                branch.groupValues[1].contains("playbackEndedHandled")
            )
        }
    }

    @Test
    fun `the native MediaSession refuses the restart media3 would otherwise make`() {
        val source = normalized(readSource(NATIVE))
        assertTrue(
            "a session transport play must be refused once the session has ended",
            source.contains(
                "override fun play() {\nif (playbackEndedHandled) return\nsuper.play()\n}"
            )
        )
        assertTrue(
            "setPlayWhenReady(true) must be refused once the session has ended",
            source.contains(
                "override fun setPlayWhenReady(playWhenReady: Boolean) {\n" +
                    "if (playWhenReady && playbackEndedHandled) return\n" +
                    "super.setPlayWhenReady(playWhenReady)\n}"
            )
        )
        assertTrue(
            "media3 restarts an ended player by seeking to the default position first",
            source.contains(
                "override fun seekToDefaultPosition() {\n" +
                    "if (playbackEndedHandled) return\nsuper.seekToDefaultPosition()\n}"
            )
        )
    }

    @Test
    fun `mpv refuses a post-end play press in its keys and its media session`() {
        val source = normalized(readSource(MPV))

        val keys = source.substringAfter("KeyEvent.KEYCODE_MEDIA_PAUSE -> {")
            .substringBefore("surface?.togglePause()")
        assertTrue(
            "mpv's media-key branch must check the ended flag before toggling",
            keys.contains("if (endedHandled) return true")
        )

        val onPlay = source.substringAfter("onPlay = {").substringBefore("onPause = {")
        val guard = onPlay.indexOf("if (!endedHandled)")
        val unpause = onPlay.indexOf("surface?.setPaused(false)")
        assertTrue(
            "mpv's session onPlay must guard the unpause",
            guard in 0 until unpause
        )
    }

    @Test
    fun `the guard blocks the toggle, never an explicit seek`() {
        // FAST_FORWARD / REWIND still reach the player untouched: an explicit
        // seek is the replay a viewer actually asked for.
        val native = readSource(NATIVE)
        assertTrue(
            "native FAST_FORWARD must stay unguarded",
            native.contains(
                "KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { exoPlayer?.seekForward(); return true }"
            )
        )
        assertTrue(
            "native REWIND must stay unguarded",
            native.contains(
                "KeyEvent.KEYCODE_MEDIA_REWIND -> { exoPlayer?.seekBack(); return true }"
            )
        )
        assertTrue(
            "mpv REWIND must stay unguarded",
            normalized(readSource(MPV))
                .contains("KeyEvent.KEYCODE_MEDIA_REWIND -> {\nseekStepBy(-SEEK_STEP_MS)")
        )
    }

    @Test
    fun `the ended latch is re-armed for every fresh session`() {
        // The latches start false and are cleared when the player is (re)built,
        // so the guard can never follow the viewer into a later session.
        assertTrue(
            readSource(NATIVE).contains("private var playbackEndedHandled = false")
        )
        assertTrue(readSource(MPV).contains("private var endedHandled = false"))

        val createPlayer = functionBody(NATIVE, "private fun createPlayer() {")
        assertTrue(
            "a rebuilt native player must clear the latch",
            createPlayer.contains("playbackEndedHandled = false")
        )
        val switchToSource = functionBody(
            MPV,
            "private fun switchToSource(stream: Stream, isAutoRecovery: Boolean = false) {"
        )
        assertTrue(
            "a fresh mpv load must clear the latch",
            switchToSource.contains("endedHandled = false")
        )
    }

    @Test
    fun `every on-screen play path in mpv routes through the ended guard`() {
        // The button, the seek bar's OK and OK-with-the-controls-down are the
        // same press the media keys make, and OK is the common one on a TV
        // remote: they have to refuse a post-end toggle for the same reason (see
        // togglePlayPauseFromControls). The play/pause button now reaches mpv
        // through the shared chrome's PlayerChromeHost.
        val source = readSource(MPV)
        assertTrue(
            "the shared chrome's play/pause must go through the guard",
            source.contains("override fun onChromePlayPause() {") &&
                source.substringAfter("override fun onChromePlayPause() {")
                    .substringBefore("}")
                    .contains("togglePlayPauseFromControls()")
        )
        val controls = functionBody(MPV, "private fun setupControls() {")
        assertTrue(
            "the seek bar's OK must still go through the guard",
            controls.contains("togglePlayPauseFromControls()")
        )
        val dispatch = functionBody(MPV, "override fun dispatchKeyEvent(")
        assertTrue(
            "OK with the controls down must go through it too",
            dispatch.contains("togglePlayPauseFromControls()")
        )
        val guard = functionBody(MPV, "private fun togglePlayPauseFromControls(): Boolean {")
        assertTrue(
            "the guard must consult the latch before toggling mpv",
            guard.indexOf("if (endedHandled) return true") in
                0 until guard.indexOf("surface?.togglePause()")
        )
    }

    @Test
    fun `an mpv source switch takes the end cards and their countdown down`() {
        // The cards belong to the episode that just ended, and the countdown
        // behind them is a Handler tick: it fires whether or not the surface is
        // playing, so a switch during the credits used to leave the card over the
        // replacement source and chain out of it a moment later.
        val switchToSource = functionBody(
            MPV,
            "private fun switchToSource(stream: Stream, isAutoRecovery: Boolean = false) {"
        )
        assertTrue(
            "the switch must take the end-of-episode cards down",
            switchToSource.contains("hideEndPanels()")
        )
        val hide = functionBody(MPV, "private fun hideEndPanels() {")
        assertTrue(
            "the countdown must be disarmed with them",
            hide.contains("cancelNextUpAutoAdvance()")
        )
        assertTrue(
            "the Up Next card must come down",
            hide.contains("nextUpPanel?.visibility = View.GONE")
        )
        assertTrue(
            "and the credits panel",
            hide.contains("becauseYouWatchedPanel?.visibility = View.GONE")
        )
        assertTrue(
            "with the credits-mode geometry restored for the next file",
            hide.contains("exitCreditsMode()")
        )
        assertTrue(
            "and the session's panel state reset with it",
            hide.contains("endPanelsShown = false")
        )
    }

    private companion object {
        private const val NATIVE =
            "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        private const val MPV =
            "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
    }
}
