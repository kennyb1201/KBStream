package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The remembered audio pick's fall-through (spec 2026-11-14).
 *
 * Field case (phone, ExoPlayer): PAW Patrol came up in French although the file
 * carries English and the language is English. English had been picked by hand on
 * an earlier episode; the stored pick is a positional fingerprint
 * (`language|codecs|channels|index|trackCount`) and the episodes of one show do
 * not lay their audio out identically, so it missed. The language pass is skipped
 * whenever a pick is stored - correctly, since it would flatten "ENG DD+ 5.1"
 * into "ENG stereo" - and nothing followed the miss, so the muxer's own default
 * (the French track that sits first) played.
 *
 * The rule is [SubtitleStaleSignatureFallThroughContractTest]'s, mirrored guard
 * for guard: a non-blank signature that misses falls through to the SAME
 * language pass the no-signature path takes, once per player instance, with the
 * signature kept. MPV already does this on its own engine, which is why this fix
 * is Exo-only.
 *
 * Read out of the source, because exercising it needs a player and a surface.
 */
class AudioStaleSignatureFallThroughContractTest {

    private val bridge by lazy { read(TRACK_BRIDGE) }
    private val mpvActivity by lazy { read(MPV_ACTIVITY) }

    private val readyPass by lazy { block("private fun applyOnReady(player: Player) {") }
    private val fallThrough by lazy { block("private fun applyAudioFallThrough(player: Player) {") }
    private val languagePass by lazy { block("fun reapplyLanguageSelection(player: Player?) {") }
    private val remembered by lazy { block("private fun applyRememberedAudioTrack(player: Player?): Boolean {") }
    private val attached by lazy { block("fun onPlayerAttached(player: Player) {") }
    private val languageApply by lazy { block("fun applyLanguage(") }
    private val mpvRemembered by lazy {
        mpvActivity.slice("private fun applyRememberedTracks()", "private fun fallThroughToSubtitleAutoPick(")
    }

    // ── The rule ──────────────────────────────────────────────────────────

    @Test
    fun `a missed remembered audio pick falls through to the language pass`() {
        assertTrue(
            "the READY pass must fall through when the remembered pick did not resolve",
            readyPass.contains("if (!applyRememberedAudioTrack(player)) applyAudioFallThrough(player)")
        )
        assertTrue(
            "and it runs after the language pass, which is the pass it corrects",
            readyPass.indexOf("reapplyLanguageSelection(player)") <
                readyPass.indexOf("applyAudioFallThrough(player)")
        )
        assertTrue(
            "the fall-through arms the same pick the no-signature path takes",
            fallThrough.contains("applyLanguage(player, C.TRACK_TYPE_AUDIO, language)")
        )
    }

    @Test
    fun `the pick is the very language pass, not a second audio picker`() {
        assertTrue(
            "the ordinary no-signature path calls it",
            languagePass.contains("if (audioTrackSignature.isBlank())") &&
                languagePass.contains("applyLanguage(player, C.TRACK_TYPE_AUDIO, effectiveAudioLanguage())")
        )
        assertEquals(
            "exactly one place may arm the audio language",
            1,
            Regex("applyLanguage\\(player, C\\.TRACK_TYPE_AUDIO, language\\)").findAll(bridge).count()
        )
        assertTrue(
            "and that pass already avoids a track known to be the wrong language",
            languageApply.contains("fallbackAudioTrack(player, language)") &&
                bridge.contains("LanguageMatch.fallbackTrackIndex(")
        )
    }

    @Test
    fun `it is a miss that triggers it, not a missing signature`() {
        assertTrue(
            "no signature is the ordinary path, and its language pass already ran",
            fallThrough.contains("if (audioTrackSignature.isBlank()) return")
        )
        assertTrue(
            "a blank signature still goes straight to the language pass",
            languagePass.contains("if (audioTrackSignature.isBlank())")
        )
        assertFalse(
            "and no fall-through line is logged on that ordinary path",
            languagePass.contains("fell through to")
        )
    }

    @Test
    fun `a hand pick this session always wins`() {
        assertTrue(
            "the re-assert's latch is the hand-pick marker",
            fallThrough.contains("if (rememberedTrackApplied) return")
        )
        assertEquals(
            "only the re-assert and the hand pick set that latch",
            2,
            Regex("rememberedTrackApplied = true").findAll(bridge).count()
        )
        assertFalse(
            "the fall-through itself must not claim the pick was applied",
            fallThrough.contains("rememberedTrackApplied = true")
        )
        assertTrue(
            "and the picker really does set it",
            block("fun chooseAudioTrack(context: Context, signature: String) {")
                .contains("rememberedTrackApplied = true")
        )
    }

    // ── Guards ────────────────────────────────────────────────────────────

    @Test
    fun `it runs once per player instance, under its own latch`() {
        assertTrue(
            "the latch is checked before anything is armed",
            fallThrough.indexOf("if (audioFallThroughApplied) return") <
                fallThrough.indexOf("applyLanguage(player, C.TRACK_TYPE_AUDIO, language)")
        )
        assertEquals(
            "and set exactly once, when the fall-through actually runs",
            1,
            Regex("audioFallThroughApplied = true").findAll(bridge).count()
        )
        assertTrue(
            "a new player instance gets its own pass",
            attached.contains("audioFallThroughApplied = false")
        )
        assertEquals(
            "reset per player build and per title load, nowhere else",
            2,
            bridge.lines().count { it.trim() == "audioFallThroughApplied = false" }
        )
        assertFalse(
            "the language pass must not reset it on a rebuffer's READY",
            languagePass.contains("audioFallThroughApplied =")
        )
        assertFalse(
            "and it is its own latch - a subtitle miss must not consume it",
            attached.contains("subtitleFallThroughApplied = audioFallThroughApplied")
        )
    }

    @Test
    fun `a blank preference leaves the file's own default in charge`() {
        assertTrue(
            "the fall-through refuses to run without a preferred language",
            fallThrough.contains("if (language.isBlank()) return")
        )
        assertFalse(
            "and nothing in it disables a track or invents a language",
            fallThrough.contains("setTrackTypeDisabled") || fallThrough.contains("\"\"")
        )
    }

    @Test
    fun `live audio still follows the preference`() {
        assertFalse(
            "unlike subtitles, a channel's audio is a real choice - no live guard",
            fallThrough.contains("if (live) return")
        )
        assertTrue(
            "and the language pass applies audio before its live gate",
            languagePass.indexOf("applyLanguage(player, C.TRACK_TYPE_AUDIO, effectiveAudioLanguage())") <
                languagePass.indexOf("if (live) return")
        )
    }

    @Test
    fun `the fall-through says what the file carried`() {
        assertTrue(
            "the line names the remembered pick and the layout it did not match",
            fallThrough.contains("did not match this file's") &&
                fallThrough.contains("fell through to")
        )
        assertTrue(
            "and counts the file's own audio tracks",
            bridge.contains("private fun audioTrackLayout(player: Player): String") &&
                bridge.contains("\"no audio tracks\"")
        )
        assertTrue(
            "the log comes only after every guard",
            fallThrough.indexOf("Log.i(") > fallThrough.indexOf("if (language.isBlank()) return")
        )
    }

    // ── Invariants ────────────────────────────────────────────────────────

    @Test
    fun `the stored signature is kept on a miss, and the miss is still reported`() {
        assertTrue(
            "the remembered track still returns before its latch on a miss",
            remembered.contains("if (signature.isBlank() || rememberedTrackApplied) return false")
        )
        assertTrue(
            "the miss is reported once per file, as before",
            remembered.contains("if (reportedAudioMissFor != signature)") &&
                remembered.contains("noteAudioMemoryMiss(signature, tracks)")
        )
        assertFalse(
            "the fall-through adds no second report",
            fallThrough.contains("noteAudioMemoryMiss")
        )
        assertFalse(
            "and nothing clears the stored signature - the next file may carry it",
            fallThrough.contains("audioTrackSignature =") || remembered.contains("audioTrackSignature =")
        )
    }

    @Test
    fun `the matching path is unchanged`() {
        assertTrue(
            "a match still re-states the exact track",
            remembered.contains(
                "setOverrideForType(TrackSelectionOverride(match.group.mediaTrackGroup, match.index))"
            )
        )
        assertTrue(
            "and logs its own line",
            remembered.contains("re-applied remembered audio track")
        )
        assertFalse(
            "with no fall-through on that path",
            remembered.contains("fell through")
        )
    }

    // ── The MPV engine (F2) ───────────────────────────────────────────────

    @Test
    fun `MPV already falls through, which is why the fix is Exo-only`() {
        assertTrue(
            "its remembered audio pick falls through on a miss",
            mpvRemembered.contains(
                "if (audioTrackSignature.isBlank() || !view.applyRememberedAudioTrack(audioTrackSignature)) {"
            )
        )
        assertTrue(
            "to the engine's own language pass",
            mpvRemembered.contains("view.selectAudioLanguage(effectiveAudioLanguage())")
        )
        assertFalse(
            "and it needs no Exo bridge latch to do it",
            mpvActivity.contains("audioFallThroughApplied")
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** One function's source, by matching its declaration and balancing braces. */
    private fun block(declaration: String): String {
        val at = bridge.indexOf(declaration)
        require(at >= 0) { "missing: $declaration" }
        val bodyStart = bridge.indexOf("{", at)
        require(bodyStart > at) { "no body: $declaration" }
        var depth = 0
        for (i in bodyStart until bridge.length) {
            when (bridge[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return bridge.substring(at, i + 1)
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
        const val TRACK_BRIDGE = "com/kennyb1201/kbstream/ui/player/PlayerTrackBridge.kt"
        const val MPV_ACTIVITY = "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
    }
}
