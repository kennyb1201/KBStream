package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wiring behind F1/F2/F3, read out of the source because none of it can be
 * driven in a JVM test (the same reason this tree's other player contracts read
 * the source): the cue handler needs a player and a surface, the picker and the
 * INFO panel need an Activity, and the proof store needs a Context.
 *
 * The three things this pins, each of which is a silent failure if it drifts:
 *
 *  - **the bitmap scale is computed from the picture** and applied only on the
 *    bitmap path - the ASS overlay keeps `scaleForSize`, and the retained
 *    geometry makes a mid-cue size change rescale the line on screen;
 *  - **"Bitmap" is said only after the track drew an image** - never from the
 *    mime, which is shared with transcoded text tracks;
 *  - **the proof is remembered device-locally and read by the auto-pick**, so a
 *    replay prefers the text track without ever overriding a manual pick.
 */
class SubtitleBitmapWiringContractTest {

    private val activity: String by lazy { read(NATIVE_PLAYER) }
    private val rules: String by lazy { read(TRACK_RULES) }
    private val bridge: String by lazy { read(TRACK_BRIDGE) }
    private val proofs: String by lazy { read(BITMAP_PROOFS) }

    private val showBitmapCues: String by lazy {
        activity.slice("private fun showBitmapCues(", "private fun clearBitmapCues(")
    }
    private val clearBitmapCues: String by lazy {
        activity.slice("private fun clearBitmapCues(", "private fun applyBitmapCueGeometry(")
    }
    private val applyBitmapCueGeometry: String by lazy {
        activity.slice("private fun applyBitmapCueGeometry(", "fun reapplyOffset(")
    }
    private val infoBlock: String by lazy {
        activity.slice("val drawingBitmaps =", "infoSubtitles.text =")
    }
    private val formatLabel: String by lazy {
        rules.slice("fun formatLabel(", "fun cannotDrawNote(")
    }

    @Test
    fun `the bitmap scale is computed from the picture, not from scaleForSize`() {
        assertTrue(
            "the authored height of the cue is what the size has to be scaled against",
            showBitmapCues.contains("cue.bitmap?.height")
        )
        assertTrue(
            "and it is retained, so a mid-cue size change does not wait for the next cue",
            showBitmapCues.contains("bitmapGeometry.remember(")
        )
        assertTrue(
            applyBitmapCueGeometry.contains("bitmapGeometry.scaleFor(")
        )
        assertFalse(
            "the bitmap path must not go back to the ASS overlay's scale around the authored size",
            applyBitmapCueGeometry.contains("scaleForSize(")
        )
        assertTrue(
            "and dropping the frame drops its geometry",
            clearBitmapCues.contains("bitmapGeometry.clear()")
        )
    }

    @Test
    fun `the ass overlay keeps its own sizing`() {
        val ass = activity.slice("private fun applyAssOverlayStyle()", "private fun selectedTextTrackPosition(")
        assertTrue(ass.contains("SubtitleOverlayStyle.scaleForSize("))
        assertFalse("the ASS overlay must not adopt the bitmap rule", ass.contains("bitmapScaleForSize("))
    }

    @Test
    fun `a track is named bitmap only after it has drawn one`() {
        assertTrue(
            "the picker badges a row only from the proof set",
            activity.contains("if (position in provenPositions) \"Bitmap\" else null")
        )
        assertTrue(
            "and the proof set comes from the session's drawn tracks plus the stored records",
            activity.contains("val provenPositions = provenBitmapPositionsForTextGroups(textGroups)") &&
                activity.contains("SubtitleBitmapProofs.proofs(")
        )
        assertTrue(
            "INFO says Bitmap only for a transcoded track that has actually drawn, and never guesses",
            infoBlock.contains("drawingBitmaps && format.sampleMimeType == MimeTypes.APPLICATION_MEDIA3_CUES") &&
                infoBlock.contains("\"Bitmap\"")
        )
        assertTrue(
            "the panel names the drawing path and the one caveat an image has",
            infoBlock.contains("\"Drawn by: bitmap subtitles (images)\"") &&
                infoBlock.contains("Clean SDH does not apply")
        )
        assertFalse(
            "formatLabel must have no x-media3-cues branch: a factory-parsed SRT track carries " +
                "the same mime, so any branch there mislabels text",
            formatLabel.contains("APPLICATION_MEDIA3_CUES")
        )
    }

    @Test
    fun `the proof is recorded when a bitmap group draws`() {
        assertTrue(showBitmapCues.contains("recordProvenBitmapTrack()"))
        val record = activity.slice(
            "private fun recordProvenBitmapTrack()",
            "private fun provenBitmapPositionsForTextGroups("
        )
        assertTrue(
            "the session set is what the picker and INFO read this play",
            record.contains("provenBitmapTrackPositions += position")
        )
        assertTrue(
            "and the store is what the next play reads",
            record.contains("SubtitleBitmapProofs.record(")
        )
        assertTrue(
            "the track's location, language and kind are the record - a different rip must not match",
            record.contains("position = position") &&
                record.contains("language = format.language") &&
                record.contains("kind = kind")
        )
    }

    @Test
    fun `the proof store is device-local and never synced`() {
        assertTrue(
            "same profile-scoped prefs file as the other per-title playback memory",
            proofs.contains("kbstream_player_memory")
        )
        assertFalse(
            "a local fact about this device's files must never reach a synced payload",
            proofs.contains("Supabase") ||
                proofs.contains("sync_outbox") ||
                proofs.contains("SyncPrefsPayload")
        )
        assertTrue(
            "and it is bounded, oldest first out",
            proofs.contains("MAX_TITLES") && proofs.contains("sortedBy { it.value.at }")
        )
    }

    @Test
    fun `the auto-pick reads the proof and the manual pick does not`() {
        assertTrue(
            "the initial selection gets the proven positions",
            activity.contains("provenPositions\n                )") ||
                activity.contains("subtitleMode,\n                    provenPositions")
        )
        assertTrue(
            "and so does the language pass, which re-arms a track on every READY",
            activity.contains("provenBitmapPositionsForTextGroups(textGroups)") &&
                activity.contains("C.TRACK_TYPE_TEXT,\n            code,")
        )
        assertTrue(
            "the bridge's re-assert pass carries the same set into the rule",
            bridge.contains("provenBitmapPositions: Set<Int> = emptySet()") &&
                bridge.contains("bestLanguageMatchIndex(\n                taggedCandidates,\n                provenBitmapPositions")
        )
        assertTrue(
            "a manual picker tap arms the track it names; nothing here re-picks after it",
            activity.contains("PlayerTrackBridge.chooseSubtitleTrack(")
        )
        assertEquals(
            "the picker's positions, not a second numbering, are what a record is matched on",
            true,
            activity.contains("position = prefixes[groupIdx] + i")
        )
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
        const val NATIVE_PLAYER = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
        const val TRACK_RULES = "com/kennyb1201/kbstream/ui/player/SubtitleTrackRules.kt"
        const val TRACK_BRIDGE = "com/kennyb1201/kbstream/ui/player/PlayerTrackBridge.kt"
        const val BITMAP_PROOFS =
            "com/kennyb1201/kbstream/data/player/SubtitleBitmapProofs.kt"
    }
}
