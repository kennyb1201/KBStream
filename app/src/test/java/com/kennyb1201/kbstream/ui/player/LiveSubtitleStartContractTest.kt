package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Live TV starts with the text track disabled, in every subtitle mode.
 *
 * The report (Kenny, 2026-11-07): a live channel came up with subtitles in mode
 * On, and turning them off stuck only for the session. The cause was the
 * subtitle section of [NativePlayerActivity.autoSelectPreferredLanguages]
 * declining to do anything for live: with nothing disabling text, media3's
 * DefaultTrackSelector armed the stream's default-flagged caption track on
 * every player build. These tests hold the fix's shape: a live branch FIRST,
 * independent of the mode, that disables and latches, with the VOD branches
 * untouched below it.
 *
 * Media3 is not available to a plain JVM test, so this is a source contract -
 * the same shape as [SubtitleModeContractTest] and
 * [LiveSubtitleSuppressionContractTest]. The behavioural half is settled on a
 * device (see the spec's S1-S3).
 */
class LiveSubtitleStartContractTest {

    private fun source(): String =
        File(mainSourceRoot(), NATIVE).readText()

    /** The text of the member starting at [signature] up to the next member. */
    private fun functionBody(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("source missing function: $signature", start >= 0)
        val rest = source.substring(start + signature.length)
        val end = Regex("\\n {4}\\S").find(rest)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    /** The block whose `{` follows [marker], with braces matched. */
    private fun bracedBlock(source: String, marker: String): String {
        val start = source.indexOf(marker)
        assertTrue("source missing: $marker", start >= 0)
        // The marker's own brace when it carries one: a `{` inside a string
        // template in the branch body would otherwise be taken for the block.
        val open = if (marker.endsWith("{")) {
            start + marker.length - 1
        } else {
            source.indexOf('{', start + marker.length)
        }
        assertTrue("no block after: $marker", open >= 0)
        var depth = 0
        for (i in open until source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open + 1, i)
                }
            }
        }
        throw AssertionError("unbalanced braces after: $marker")
    }

    private val autoSelect: String by lazy {
        functionBody(source(), "private fun autoSelectPreferredLanguages() {")
    }

    /** The live branch of the subtitle section, first in that section. */
    private val liveBranch: String by lazy {
        bracedBlock(autoSelect, "if (isLiveChannel) {")
    }

    /** Where the live branch sits, so "FIRST" is a fact about the order. */
    private val liveHead: Int by lazy { autoSelect.indexOf("if (isLiveChannel) {") }

    private val offBranchHead: Int by lazy {
        autoSelect.indexOf("subtitleMode == SubtitleModeRules.OFF")
    }

    private val pickBranchHead: Int by lazy {
        autoSelect.indexOf("subtitleMode == SubtitleModeRules.FORCED")
    }

    // ── T1 / T2 / T3: live disables, whatever the mode ──────────────────────

    @Test
    fun `live disables the text track at start before any mode is consulted`() {
        assertTrue(
            "the live branch must come first, so no VOD mode branch can claim a channel",
            liveHead >= 0 && liveHead < offBranchHead && liveHead < pickBranchHead
        )
        assertTrue(
            "nothing may be left to media3's own default selection on live: it is what " +
                "armed the stream's default-flagged caption track in the first place",
            liveBranch.contains("setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)")
        )
    }

    @Test
    fun `the live branch does not read the subtitle mode, so On and Off behave alike`() {
        // The branch is `if (isLiveChannel)`, not `if (isLiveChannel && mode == ...)`:
        // the mode is a VOD preference, and a live start is clean in all three.
        val head = autoSelect.substring(liveHead, liveHead + "if (isLiveChannel) {".length)
        assertFalse(
            "the live branch must fire in On, Forced and Off alike",
            head.contains("subtitleMode")
        )
        assertFalse(
            "and it must not branch on one of the modes in its body either",
            liveBranch.contains("SubtitleModeRules.OFF") ||
                liveBranch.contains("SubtitleModeRules.FORCED") ||
                liveBranch.contains("SubtitleModeRules.ON")
        )
    }

    @Test
    fun `live never arms a track, forced included`() {
        assertFalse(
            "forced is a VOD concept; on live the same pick is unasked-for arming",
            liveBranch.contains("setOverrideForType")
        )
        assertFalse(
            "the track rules must not run for a channel",
            liveBranch.contains("SubtitleTrackRules.choose(")
        )
        assertFalse(
            "and no fetched subtitle is attached there either",
            liveBranch.contains("maybeAutoFetchSubtitle()")
        )
    }

    // ── T4: the latch keeps a hand pick through a rebuffer ──────────────────

    @Test
    fun `the live disable latches, so a rebuffer cannot undo a hand pick`() {
        val disable = liveBranch.indexOf("setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)")
        val latch = liveBranch.indexOf("changed = true")
        assertTrue("the live branch must disable text", disable >= 0)
        assertTrue(
            "the disable and its latch are one decision: without it the next READY re-ran " +
                "the pass over a caption the viewer had just switched on by hand",
            latch > disable
        )
        assertTrue(
            "and the pass stops at the latch on every later READY",
            autoSelect.substringAfter("private fun autoSelectPreferredLanguages() {")
                .contains("if (languagesAutoSelected) return")
        )
    }

    @Test
    fun `a hand pick still turns text back on and is not routed through the pass`() {
        val chosen = functionBody(source(), "private fun applyChosenSubtitleLanguage(code: String) {")
        assertTrue(
            "the panel's subtitle pick applies through the track bridge, which re-enables " +
                "text; the latch only stops the AUTO pass, as on VOD Off",
            chosen.contains("PlayerTrackBridge.applyLanguage(") &&
                chosen.contains("C.TRACK_TYPE_TEXT")
        )
        assertFalse(
            "the pick must not be gated on the auto pass's latch",
            chosen.contains("languagesAutoSelected")
        )
    }

    // ── T6: a zap gets its own clean start ──────────────────────────────────

    @Test
    fun `zapping to the next channel gets a fresh pass`() {
        val switch = functionBody(
            source(),
            "fun switchToSource(stream: Stream, isAutoRecovery: Boolean = false) {"
        )
        assertTrue(
            "the latch is per player build; a zap must clear it or the second channel " +
                "inherits the first one's decision and starts armed again",
            switch.contains("languagesAutoSelected = false")
        )
        // And a rebuilt player resets it too (sidecar attach, engine change, DV
        // retry), which is the other way a channel gets a fresh instance.
        assertTrue(
            "a rebuilt player gets its own pass",
            source().contains("languagesAutoSelected = false")
        )
    }

    // ── F2: the trace line ─────────────────────────────────────────────────

    @Test
    fun `the live disable is traceable in COPY DIAGNOSTICS`() {
        assertTrue(
            "one PLAYER_LANG line naming the mode, so a field run reads as a decision",
            liveBranch.contains("\"PLAYER_LANG\"") &&
                liveBranch.contains("Live channel: text track disabled at start") &&
                liveBranch.contains("SubtitleModeRules.label(subtitleMode)")
        )
    }

    // ── M1: the bridge's ready-time pass cannot undo it ─────────────────────

    @Test
    fun `the track bridge never re-arms a caption on a live channel`() {
        // The activity's disable is only half the fix (M1, release-readiness
        // audit 2026-11-10): this object's READY listener is registered when the
        // view attaches the player, i.e. AFTER the activity's, so it ran its
        // subtitle-language pass over the disabled channel and put a caption
        // back. T4 above cannot catch that - the activity latch stops the
        // activity's pass, not this one.
        val bridge = File(mainSourceRoot(), BRIDGE).readText()
        val reapply = functionBody(bridge, "fun reapplyLanguageSelection(player: Player?) {")
        assertTrue(
            "the bridge has to be told the session is live: a null titleKey is not " +
                "the same fact",
            bridge.contains("isLive: Boolean = false") &&
                bridge.contains("live = isLive")
        )
        assertTrue(
            "and its subtitle-language pass must stop there",
            reapply.contains("if (live) return")
        )
        assertTrue(
            "the audio pass still runs above it, and a hard OFF is still re-asserted",
            reapply.indexOf("C.TRACK_TYPE_AUDIO") in 0 until reapply.indexOf("if (live) return") &&
                reapply.indexOf("if (subtitlesOff)") in 0 until reapply.indexOf("if (live) return")
        )
        assertTrue(
            "and the activity hands its live flag over",
            source().contains("isLive = isLiveChannel")
        )
    }

    private fun mainSourceRoot(): File {
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
        private const val NATIVE =
            "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        private const val BRIDGE =
            "com/kennyb1201/kbstream/ui/player/PlayerTrackBridge.kt"
    }
}
