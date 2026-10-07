package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The player batch: subtitle Off staying off, the in-player picker's choices
 * surviving a rebuffer, and the MPV engine's completion being as sticky as the
 * main player's.
 *
 * All three are wiring that fails silently - the app compiles, the control does
 * nothing, and the symptom only appears on a device after a rebuffer or an end
 * of episode. Each fix has a seam a pure unit test cannot reach (an Android
 * Activity, a per-title preference blob, a media3 ready transition), so the
 * shape is pinned here at the source.
 *
 *  1. **P1-1 / Off re-armed by the language pass.** `PlayerTrackBridge` and
 *     `MpvPlayerActivity.applyRememberedTracks` both re-apply the subtitle
 *     language on every file open / ready transition. Left mode-blind, a session
 *     the viewer set to Off had a track armed on the first rebuffer, because a
 *     configured subtitle language is exactly what the pass acts on.
 *  2. **P1-2 / the picker's choices undone on the next rebuffer.** The picker
 *     wrote the player directly, so the bridge's ready-time pass - which re-runs
 *     on every rebuffer - reverted a hand-picked audio track, a hand-picked
 *     subtitle track, and even an explicit OFF. The fix routes all three through
 *     the bridge, where a choice is remembered and re-stated.
 *  3. **P1-3 / MPV completion not sticky.** `MpvPlayerActivity.saveProgress`
 *     judged completion from the playhead alone, so a seek back and a pause
 *     after a finale rewrote the finished row as resumable. The main player
 *     already ORs its session latch in; MPV now does too.
 */
class PlayerRelayBatchAContractTest {

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

    /** Source with per-line indentation stripped, so nested guards can be matched. */
    private fun normalized(raw: String): String = raw.lines().joinToString("\n") { it.trim() }

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

    // ── 1. the ready-time language pass is mode-aware ──────────────────────

    @Test
    fun `the bridge language pass never arms a subtitle track against Off`() {
        val body = normalized(
            functionBody(BRIDGE, "fun reapplyLanguageSelection(player: Player?) {")
        )
        assertTrue(
            "the mode must be known to the pass at all",
            readSource(BRIDGE).contains("private var subtitleMode: Int = SubtitleModeRules.DEFAULT")
        )
        assertTrue(
            "Off must be published with the global languages",
            readSource(BRIDGE).contains("fun setSubtitleMode(mode: Int)")
        )
        assertTrue(
            "the mode must gate the language subtitles, not only ride along",
            body.contains("if (SubtitleModeRules.normalized(subtitleMode) != SubtitleModeRules.ON) return")
        )
        assertTrue(
            "an explicit per-show Off must be a hard disable, since the " +
                "activity's own pass may have armed a track first",
            body.contains("if (subtitlesOff) {") &&
                body.contains("applyLanguage(player, C.TRACK_TYPE_TEXT, \"\")")
        )
        assertTrue(
            "a remembered specific track must not be replaced by a language pick",
            body.contains("if (subtitleTrackSignature.isNotBlank()) return")
        )
    }

    @Test
    fun `the native session publishes the subtitle mode to the bridge`() {
        assertTrue(
            "without this the bridge cannot be mode-aware",
            readSource(NATIVE).contains(
                "PlayerTrackBridge.setSubtitleMode(AppPreferences.getSubtitleMode(this))"
            )
        )
    }

    @Test
    fun `the mpv language re-assertion is gated on the On mode`() {
        val body = normalized(
            functionBody(
                MPV_PLAYER,
                "private fun applyRememberedTracks() {"
            )
        )
        val guard = "if (SubtitleModeRules.normalized(AppPreferences.getSubtitleMode(this)) =="
        assertTrue("the open-time track pass must consult the mode", body.contains(guard))
        assertTrue(
            "and only then re-select the remembered subtitle language",
            body.contains(
                "view.selectSubtitleLanguage(effectiveSubtitleLanguage())"
            )
        )
        // The language call must sit INSIDE the guard: a mode-blind call is the
        // bug. It is the line after the guard's closing brace, so its offset is
        // greater than the guard's.
        assertTrue(
            body.indexOf("view.selectSubtitleLanguage(") > body.indexOf(guard)
        )
    }

    // ── 2. the picker routes through the bridge, and OFF is remembered ─────

    @Test
    fun `the native picker writes through the bridge`() {
        val native = readSource(NATIVE)
        assertTrue(
            "the audio row must not write the player directly",
            native.contains("PlayerTrackBridge.chooseAudioTrack(")
        )
        assertTrue(
            "the subtitle row must not write the player directly",
            native.contains("PlayerTrackBridge.chooseSubtitleTrack(")
        )
        assertTrue(
            "the OFF row must be a remembered per-show choice",
            native.contains("PlayerTrackBridge.chooseSubtitlesOff(")
        )
    }

    @Test
    fun `the bridge remembers a per-show Off and track`() {
        val prefs = readSource(TITLE_PREFS)
        assertTrue(prefs.contains("val subtitleOff: Boolean = false"))
        assertTrue(prefs.contains("val subtitleTrackSignature: String = \"\""))
        assertTrue(
            "an Off that is not persisted dies with the session",
            prefs.contains("!subtitleOff &&")
        )
        val bridge = readSource(BRIDGE)
        assertTrue(
            "the per-show Off has to reach the persisted blob",
            bridge.contains("subtitleOff = subtitlesOff,")
        )
        assertTrue(
            "and come back on the next session",
            bridge.contains("subtitlesOff = remembered?.subtitleOff == true")
        )
        assertTrue(
            "choosing a language clears both the Off and the track",
            normalized(functionBody(BRIDGE, "fun chooseSubtitleLanguage(context: Context, code: String) {"))
                .contains("subtitlesOff = false")
        )
    }

    @Test
    fun `a remembered subtitle track is re-asserted once per player instance`() {
        val bridge = readSource(BRIDGE)
        assertTrue(
            "the re-assertion has to exist",
            bridge.contains("private fun applyRememberedSubtitleTrack(player: Player?): Boolean")
        )
        assertTrue(
            "and run on the ready pass",
            normalized(functionBody(BRIDGE, "private fun applyOnReady(player: Player) {"))
                .contains("applyRememberedSubtitleTrack(player)")
        )
    }

    // ── 3. MPV completion is sticky for the session ────────────────────────

    @Test
    fun `every MPV save in a finished session is filed as completed`() {
        val mpv = readSource(MPV_PLAYER)
        assertTrue(
            "the session latch has to be OR'd into the completion verdict, so a " +
                "pause/stop/actor-return after a seek back cannot downgrade the row",
            mpv.contains("val sessionCompleted = forceCompleted || endedHandled")
        )
        assertTrue(
            "and the verdict must be written from it",
            mpv.contains("val completed =\n            sessionCompleted ||")
        )
        // The two early returns are what a rewind trips first (a position at
        // 0): they must not abandon the write for a finished session.
        assertTrue(
            "a zero-duration finished session must still be written",
            mpv.contains("if (duration <= 0L && !sessionCompleted) return")
        )
        assertTrue(
            "and so must one whose playhead is back at the start",
            mpv.contains("if (position < MIN_RESUME_POSITION_MS && !sessionCompleted) return")
        )
        val ended = normalized(functionBody(MPV_PLAYER, "private fun onPlaybackEnded() {"))
        assertTrue(
            "and it is that latch which the end sets",
            ended.contains("endedHandled = true")
        )
    }

    // ── 4. the MPV credits panel owns the horizontal press immediately ─────

    @Test
    fun `the mpv credits panel swallows LEFT and RIGHT before the row is built`() {
        val mpv = readSource(MPV_PLAYER)
        val body = normalized(functionBody(MPV_PLAYER, "override fun dispatchKeyEvent(event: KeyEvent): Boolean {"))
        assertTrue(
            "a horizontal press the panel cannot hand to a pick must still be " +
                "swallowed, not run through focusFirst() as a condition",
            body.contains("bywUi?.focusFirst()\nreturn true")
        )
        assertFalse(
            "focusFirst() must not gate the swallow - until the row is built it " +
                "finds nothing, and the press would scrub the finished episode",
            mpv.contains("bywUi?.focusFirst() == true")
        )
    }

    private companion object {
        private const val NATIVE =
            "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        private const val MPV_PLAYER =
            "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
        private const val BRIDGE =
            "com/kennyb1201/kbstream/ui/player/PlayerTrackBridge.kt"
        private const val TITLE_PREFS =
            "com/kennyb1201/kbstream/data/player/PlayerTitlePrefs.kt"
    }
}
