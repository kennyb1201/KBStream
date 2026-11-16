package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The remembered audio and subtitle fall-throughs run once per FILE, not once
 * per player (spec 2026-11-16).
 *
 * Field case (2026-11-16 07:06:50, TCL, build 336): autoplay E2 → E5 → E7 on
 * profile/3. The stored pick `en|aac|2|0|1` missed on every multi-audio file
 * (`file=[sv|eac3|2, en|eac3|6, ...]`), and E7's source was switched mid-play
 * from the Zilean row. The switch REUSED the player - `rebuilds=3` for three
 * episodes - so neither of the two resets that existed ran: the fall-through had
 * been spent on the first file, the new file's miss armed nothing, and the
 * muxer's own default (the Swedish first track) played.
 *
 * The fix is a fingerprint of the file's track list, taken at the top of the
 * READY pass: when it differs from the list the latches were spent on, they are
 * given back. A rebuffer re-reads the same list, so nothing is rearmed - the
 * reason the latch exists survives.
 *
 * Read out of the source, like its two precedents (the 2026-11-13 subtitle and
 * 2026-11-14 audio specs), because exercising it needs a player.
 */
class PerFileTrackFallThroughContractTest {

    private val bridge by lazy { read(TRACK_BRIDGE) }
    private val mpvActivity by lazy { read(MPV_ACTIVITY) }

    private val readyPass by lazy { block("private fun applyOnReady(player: Player) {") }
    private val rearm by lazy { block("private fun rearmFallThroughsForThisFile(player: Player) {") }
    private val fingerprint by lazy {
        block("private fun trackListFingerprint(player: Player, type: Int): String {")
    }
    private val languagePass by lazy { block("fun reapplyLanguageSelection(player: Player?) {") }
    private val audioFallThrough by lazy { block("private fun applyAudioFallThrough(player: Player) {") }
    private val subtitleFallThrough by lazy {
        block("private fun applySubtitleFallThrough(player: Player) {")
    }
    private val audioBranch by lazy {
        rearm.slice("if (audio != armedAudioTracksFor) {", "val text = trackListFingerprint").trim()
    }
    private val textBranch by lazy {
        rearm.slice("if (text != armedTextTracksFor) {", "    }").trim()
    }

    // ── F1/F2: the rearm runs first, and only on a different list ─────────

    @Test
    fun `the ready pass rearms the latches before anything consults them`() {
        assertTrue(
            "the READY pass looks at the file's track list first",
            readyPass.contains("rearmFallThroughsForThisFile(player)")
        )
        assertTrue(
            "before the language pass, the re-assert and both fall-throughs - a " +
                "fall-through decision made on the previous file's latch is the bug",
            readyPass.indexOf("rearmFallThroughsForThisFile(player)") <
                readyPass.indexOf("reapplyLanguageSelection(player)") &&
                readyPass.indexOf("rearmFallThroughsForThisFile(player)") <
                readyPass.indexOf("applyRememberedAudioTrack(player)") &&
                readyPass.indexOf("rearmFallThroughsForThisFile(player)") <
                readyPass.indexOf("applySubtitleFallThrough(player)")
        )
    }

    @Test
    fun `a different track list gives the audio latch back, a rebuffer does not`() {
        assertTrue(
            "the reset is inside the fingerprint comparison, so the same list (a " +
                "rebuffer's READY) leaves the latch spent - S3",
            rearm.contains("if (audio != armedAudioTracksFor) {")
        )
        assertTrue(
            "the list is remembered as the one the latch was spent on",
            audioBranch.contains("armedAudioTracksFor = audio")
        )
        assertEquals(
            "and the latch really is given back - once, on that branch",
            1,
            Regex("audioFallThroughApplied = false").findAll(rearm).count()
        )
        assertTrue(
            "the fall-through's own guard is untouched: once per file",
            audioFallThrough.contains("if (audioFallThroughApplied) return")
        )
    }

    @Test
    fun `the subtitle latch follows the text list the same way`() {
        assertTrue(
            "the subtitle twin gets the same per-file rearm",
            rearm.contains("if (text != armedTextTracksFor) {") &&
                textBranch.contains("armedTextTracksFor = text")
        )
        assertEquals(
            "once, on the branch that saw a different list",
            1,
            Regex("subtitleFallThroughApplied = false").findAll(rearm).count()
        )
        assertTrue(
            "and its own guard still gates it",
            subtitleFallThrough.contains("if (subtitleFallThroughApplied) return")
        )
        assertFalse(
            "the language pass resets neither latch on a rebuffer's READY",
            languagePass.contains("audioFallThroughApplied =") ||
                languagePass.contains("subtitleFallThroughApplied =")
        )
    }

    @Test
    fun `the fingerprint is the signature's own granularity, so a hit is a hit`() {
        assertTrue(
            "languages alone are not a file: two files can share them and differ in " +
                "codec, channels or count - which is exactly how the stored pick missed",
            fingerprint.contains("format.language") &&
                fingerprint.contains("format.sampleMimeType") &&
                fingerprint.contains("format.channelCount")
        )
        assertTrue(
            "every track counts, in order (the stored signature's index and trackCount)",
            fingerprint.contains("for (group in player.currentTracks.groups)") &&
                fingerprint.contains("for (i in 0 until group.length)")
        )
        assertTrue(
            "and an empty list is a fingerprint too, so an early READY that ran the " +
                "pass before the tracks arrived re-arms when they do",
            fingerprint.contains("if (tracks.isEmpty()) \"no tracks\"")
        )
        assertTrue(
            "the audio and text lists are asked for separately, scored by type",
            rearm.contains("trackListFingerprint(player, C.TRACK_TYPE_AUDIO)") &&
                rearm.contains("trackListFingerprint(player, C.TRACK_TYPE_TEXT)")
        )
    }

    // ── F4: the miss line follows the file as well ─────────────────────────

    @Test
    fun `a second file's miss is reported, not swallowed`() {
        assertTrue(
            "the miss report is cleared with the audio latch - once per file, not " +
                "once per player",
            audioBranch.contains("reportedAudioMissFor = null")
        )
        assertFalse(
            "and only with the audio one: the text branch has no miss report to clear",
            textBranch.contains("reportedAudioMissFor")
        )
        assertTrue(
            "the report itself still fires once per signature",
            bridge.contains("if (reportedAudioMissFor != signature)")
        )
    }

    // ── Invariants ────────────────────────────────────────────────────────

    @Test
    fun `a hit still wins and a hand pick still sticks`() {
        assertTrue(
            "the fall-through only ever runs after a MISS",
            readyPass.contains("if (!applyRememberedAudioTrack(player)) applyAudioFallThrough(player)") &&
                readyPass.contains("if (!applyRememberedSubtitleTrack(player)) applySubtitleFallThrough(player)")
        )
        assertTrue(
            "and never over a hand pick this session - the rearm does not touch that " +
                "marker, which is why a pick made by hand keeps winning",
            audioFallThrough.contains("if (rememberedTrackApplied) return") &&
                subtitleFallThrough.contains("if (rememberedSubtitleTrackApplied) return")
        )
        assertFalse(
            "rearming must not clear what the viewer remembers: the next file may carry " +
                "the very layout the pick was made from",
            rearm.contains("audioTrackSignature =") || rearm.contains("subtitleTrackSignature =")
        )
        assertFalse(
            "and it invents nothing - no language is guessed on a rearm",
            rearm.contains("applyLanguage(")
        )
    }

    @Test
    fun `MPV is unchanged, because it already rearms per file load`() {
        assertFalse(
            "no per-player fall-through latch exists on the MPV side to fix",
            mpvActivity.contains("FallThroughApplied")
        )
        assertTrue(
            "its arms run from the file load, which is the granularity this fix gives Exo",
            mpvActivity.contains("private fun applyRememberedTracks()") &&
                mpvActivity.contains("private fun fallThroughToSubtitleAutoPick(")
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
