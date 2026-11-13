package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F1 - the stale-signature fall-through.
 *
 * The field case (2026-11-13, phone, ExoPlayer): a remembered subtitle pick is a
 * positional fingerprint (`language|codecs|channels|index|trackCount`) of the
 * track in the file it was made on, and the episodes of one show do not all lay
 * their subtitles out the same way. On a miss the re-assert armed nothing, so the
 * player's own default took over - the file's default-flagged track, very often
 * the bitmap that sits first in the list. Every play after the first hand pick
 * therefore came up on the wrong track.
 *
 * The rule: a non-blank signature that misses falls through to the SAME latched
 * auto-pick the no-signature path takes (not a second pick path), once per
 * source, with the signature kept. No Identity tier is added - the fall-through
 * is a fresh decision from this file's tracks, not a guess from a stale seat.
 *
 * Read out of the source, because exercising it needs a player and a surface.
 */
class SubtitleStaleSignatureFallThroughContractTest {

    private val bridge: String by lazy { read(TRACK_BRIDGE) }
    private val mpvActivity: String by lazy { read(MPV_ACTIVITY) }

    private val readyPass: String by lazy {
        bridge.slice("private fun applyOnReady(", "private fun textTrackLayout(")
    }
    private val remembered: String by lazy {
        bridge.slice("private fun applyRememberedSubtitleTrack(", "private fun fallbackAudioTrack(")
    }
    private val fallThrough: String by lazy {
        bridge.slice("private fun applySubtitleFallThrough(", "private fun textTrackLayout(")
    }
    private val autoPick: String by lazy {
        bridge.slice("private fun applySubtitleAutoPick(", "fun onPlayerAttached(")
    }
    private val languagePass: String by lazy {
        bridge.slice("fun reapplyLanguageSelection(", "private fun applySubtitleAutoPick(")
    }
    private val attached: String by lazy {
        bridge.slice("fun onPlayerAttached(", "private fun applyOnReady(")
    }
    private val mpvRemembered: String by lazy {
        mpvActivity.slice("private fun applyRememberedTracks()", "private fun fallThroughToSubtitleAutoPick(")
    }
    private val mpvFallThrough: String by lazy {
        mpvActivity.slice("private fun fallThroughToSubtitleAutoPick(", "// --- State the settings panel")
    }

    // ── The rule ──────────────────────────────────────────────────────────

    @Test
    fun `a missed remembered pick falls through to the auto-pick`() {
        assertTrue(
            "the READY pass must fall through when the remembered pick did not resolve",
            readyPass.contains("if (!applyRememberedSubtitleTrack(player)) applySubtitleFallThrough(player)")
        )
        assertTrue(
            "and it runs after the language pass, which is the pass it corrects",
            readyPass.indexOf("reapplyLanguageSelection(player)") <
                readyPass.indexOf("applySubtitleFallThrough(player)")
        )
        assertTrue(
            "the fall-through arms the same pick the no-signature path takes",
            fallThrough.contains("applySubtitleAutoPick(player)")
        )
    }

    @Test
    fun `the fall-through reuses the one auto-pick instead of a second pick path`() {
        assertTrue(
            "the ordinary no-signature path calls it",
            languagePass.contains("applySubtitleAutoPick(player)")
        )
        assertTrue(
            "and so does the fall-through",
            fallThrough.contains("applySubtitleAutoPick(player)")
        )
        assertEquals(
            "exactly one place may arm the subtitle language",
            1,
            Regex("applyLanguage\\(player, C\\.TRACK_TYPE_TEXT, subtitle\\)")
                .findAll(bridge).count()
        )
        assertTrue(
            "the pick itself is still language plus the kind-ranked override",
            autoPick.contains("applyLanguage(player, C.TRACK_TYPE_TEXT, subtitle)")
        )
        assertTrue(
            "the mode and blank-preference guards live in the shared pick",
            autoPick.contains("SubtitleModeRules.normalized(subtitleMode) != SubtitleModeRules.ON") &&
                autoPick.contains("if (subtitle.isBlank()) return false")
        )
    }

    @Test
    fun `it is a miss that triggers it, not a missing signature`() {
        assertTrue(
            "no signature is the ordinary path, and its auto-pick already ran",
            fallThrough.contains("if (subtitleTrackSignature.isBlank()) return")
        )
        assertTrue(
            "a blank signature still goes straight to the language pass",
            languagePass.contains("if (subtitleTrackSignature.isNotBlank()) return")
        )
        assertTrue(
            "and no fall-through line is logged on that ordinary path",
            !languagePass.contains("fell through to auto-pick")
        )
    }

    // ── Guards ────────────────────────────────────────────────────────────

    @Test
    fun `Off, live and a non-On mode all arm nothing`() {
        assertTrue(
            "an explicit per-show OFF is still a hard disable, before the signature",
            languagePass.indexOf("if (subtitlesOff)") <
                languagePass.indexOf("if (subtitleTrackSignature.isNotBlank()) return")
        )
        assertTrue("live channels arm nothing here", fallThrough.contains("if (live) return"))
        assertTrue("an OFF subtitle preference arms nothing either", fallThrough.contains("if (subtitlesOff) return"))
        assertTrue(
            "the mode is checked in the fall-through as well, not only in the pick",
            fallThrough.contains("SubtitleModeRules.normalized(subtitleMode) != SubtitleModeRules.ON")
        )
        assertTrue(
            "and none of those branches is reached by the language pass ordering",
            languagePass.indexOf("if (live) return") <
                languagePass.indexOf("if (subtitleTrackSignature.isNotBlank()) return")
        )
    }

    @Test
    fun `a blank preference never invents a language`() {
        assertTrue(
            "the fall-through refuses to run without a preferred language",
            fallThrough.contains("if (effectiveSubtitleLanguage().isBlank()) return")
        )
        assertTrue(
            "and the shared pick leaves the file's default in place on blank",
            autoPick.contains("if (subtitle.isBlank()) return false")
        )
        assertFalse(
            "nothing in the fall-through turns the text track off",
            fallThrough.contains("setTrackTypeDisabled") ||
                fallThrough.contains("applyLanguage(") ||
                fallThrough.contains("\"\"")
        )
    }

    @Test
    fun `a hand pick this session always wins`() {
        assertTrue(
            "the re-assert's latch is the hand-pick marker",
            fallThrough.contains("if (rememberedSubtitleTrackApplied) return")
        )
        assertEquals(
            "only the re-assert and the hand pick set that latch, never the fall-through",
            2,
            Regex("rememberedSubtitleTrackApplied = true").findAll(bridge).count()
        )
        assertFalse(
            "the fall-through itself must not claim the pick was applied",
            fallThrough.contains("rememberedSubtitleTrackApplied = true")
        )
    }

    @Test
    fun `it runs once per source, under the auto-pick latch`() {
        assertTrue(
            "the latch is checked before anything is armed",
            fallThrough.indexOf("if (subtitleFallThroughApplied) return") <
                fallThrough.indexOf("applySubtitleAutoPick(player)")
        )
        assertEquals(
            "and set exactly once, when the fall-through actually runs",
            1,
            Regex("subtitleFallThroughApplied = true").findAll(bridge).count()
        )
        assertTrue(
            "a new player instance gets its own pass",
            attached.contains("subtitleFallThroughApplied = false")
        )
        assertEquals(
            "reset per player build and per title load, nowhere else",
            2,
            bridge.lines().count { it.trim() == "subtitleFallThroughApplied = false" }
        )
        assertFalse(
            "the language pass must not reset it on a rebuffer's READY",
            languagePass.contains("subtitleFallThroughApplied =")
        )
    }

    // ── Invariants ────────────────────────────────────────────────────────

    @Test
    fun `the stored signature is kept on a miss`() {
        assertTrue(
            "the remembered track still returns before its latch on a miss",
            remembered.indexOf("|| rememberedSubtitleTrackApplied) return false") <
                remembered.indexOf("rememberedSubtitleTrackApplied = true")
        )
        assertTrue(
            "so a retry with late tracks can still apply it",
            remembered.contains("val match = findTrack(player, C.TRACK_TYPE_TEXT, signature) ?: return false")
        )
        assertFalse(
            "and nothing in the fall-through clears or rewrites the signature",
            fallThrough.contains("subtitleTrackSignature =")
        )
        assertFalse(
            "nor does the missed re-assert",
            remembered.contains("subtitleTrackSignature =")
        )
    }

    @Test
    fun `the matching path is unchanged`() {
        assertTrue(
            "a match still re-states the track",
            remembered.contains("setOverrideForType(TrackSelectionOverride(match.group.mediaTrackGroup, match.index))")
        )
        assertTrue(
            "and logs its own line",
            remembered.contains("re-applied remembered subtitle track")
        )
        assertFalse(
            "with no fall-through on that path",
            remembered.contains("fell through to auto-pick")
        )
    }

    @Test
    fun `the fall-through says what the file carried`() {
        assertTrue(
            "the line names the remembered pick and the layout it did not match",
            fallThrough.contains("did not match this file's") &&
                fallThrough.contains("fell through to auto-pick")
        )
        assertTrue(
            "and counts the file's own text tracks",
            bridge.contains("private fun textTrackLayout(player: Player): String") &&
                bridge.contains("\"no text tracks\"")
        )
        assertTrue(
            "the log comes only after every guard, so no line without a fall-through",
            fallThrough.indexOf("Log.i(") > fallThrough.indexOf("if (effectiveSubtitleLanguage().isBlank()) return")
        )
    }

    // ── The MPV engine ────────────────────────────────────────────────────

    @Test
    fun `MPV takes the same rule on its own remembered-subtitle path`() {
        assertTrue(
            "its remembered pick falls through on a miss",
            mpvRemembered.contains(
                "if (!view.applyRememberedSubtitleTrack(subtitleTrackSignature)) {\n" +
                    "                fallThroughToSubtitleAutoPick(view)"
            )
        )
        assertTrue(
            "and the fall-through runs the engine's own auto-pick",
            mpvFallThrough.contains("view.selectSubtitleLanguage(language)")
        )
        assertTrue(
            "only in On mode",
            mpvFallThrough.contains("SubtitleModeRules.normalized(AppPreferences.getSubtitleMode(this)) !=")
        )
        assertTrue(
            "and only with a preferred language set",
            mpvFallThrough.contains("if (language.isBlank()) return")
        )
        assertTrue(
            "OFF still wins before it",
            mpvRemembered.contains("if (subtitlesOff) {\n            view.clearSubtitles()")
        )
        assertFalse(
            "and it never clears subtitles or invents a language",
            mpvFallThrough.contains("clearSubtitles()")
        )
        assertTrue(
            "it says the fall-through happened, with the file's layout",
            mpvFallThrough.contains("did not match this file's") &&
                mpvFallThrough.contains("fell through to auto-pick")
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────

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
        const val TRACK_BRIDGE = "com/kennyb1201/kbstream/ui/player/PlayerTrackBridge.kt"
        const val MPV_ACTIVITY = "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
    }
}
