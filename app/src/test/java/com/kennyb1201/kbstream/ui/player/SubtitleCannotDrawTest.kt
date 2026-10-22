package com.kennyb1201.kbstream.ui.player

import androidx.media3.common.MimeTypes
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rows in the player's SUBTITLES picker that used to do nothing.
 *
 * A track in a format this engine does not draw is still listed (it is in the
 * file), and selecting it used to arm it, draw nothing and report nothing -
 * the one failure a viewer cannot describe, because the row said as little as
 * every other row and the picker did not change. Two halves of the answer are
 * pinned here: the row now says so ([SubtitleTrackRules.cannotDrawNote]), and
 * the press hands the track to the engine that CAN draw it.
 *
 * Which formats those are is now per format (see
 * [SubtitleTrackRules.bitmapRenderable]): PGS draws here, so its row carries no
 * note and arms the track; VobSub and DVBSubs keep the handoff.
 */
class SubtitleCannotDrawTest {

    @Test
    fun `a bitmap format this engine does not draw says it needs the other engine`() {
        assertEquals("needs MPV", SubtitleTrackRules.cannotDrawNote(MimeTypes.APPLICATION_VOBSUB, false))
        assertEquals("needs MPV", SubtitleTrackRules.cannotDrawNote(MimeTypes.APPLICATION_DVBSUBS, false))
        // Even if a future media3 claims one supported: these two formats have
        // no path for their images in this build.
        assertEquals("needs MPV", SubtitleTrackRules.cannotDrawNote(MimeTypes.APPLICATION_VOBSUB, true))
        assertEquals("needs MPV", SubtitleTrackRules.cannotDrawNote(MimeTypes.APPLICATION_DVBSUBS, true))
    }

    @Test
    fun `a bitmap format this engine draws reads the ordinary supported verdict`() {
        // The whole point of the per-format flag: PGS is no longer special-
        // cased, so its row and the selection rules agree with every text
        // track's.
        assertNull(
            "PGS draws here now, so its row carries the badge and nothing else",
            SubtitleTrackRules.cannotDrawNote(MimeTypes.APPLICATION_PGS, true)
        )
        assertEquals(
            "and an unsupported verdict still says so, exactly like a text track",
            "not supported here",
            SubtitleTrackRules.cannotDrawNote(MimeTypes.APPLICATION_PGS, false)
        )
    }

    @Test
    fun `a text track no renderer claims says it is unsupported here`() {
        assertEquals("not supported here", SubtitleTrackRules.cannotDrawNote("application/cea-608", false))
    }

    @Test
    fun `the tracks this engine does draw carry no note`() {
        assertNull(SubtitleTrackRules.cannotDrawNote(MimeTypes.APPLICATION_SUBRIP, true))
        assertNull(SubtitleTrackRules.cannotDrawNote(MimeTypes.TEXT_VTT, true))
        // ASS is flattened to plain cues rather than failing, so it stays a
        // pressable row that draws (see the picker's own note).
        assertNull(SubtitleTrackRules.cannotDrawNote(MimeTypes.TEXT_SSA, true))
        assertNull(SubtitleTrackRules.cannotDrawNote(null, true))
    }

    @Test
    fun `the picker names the note in the row and hands the track over on the press`() {
        val player = read(PLAYER)
        assertTrue(
            "the note has to be computed from this engine's own answer",
            player.contains("val cannotDraw = SubtitleTrackRules.cannotDrawNote(")
        )
        assertTrue(
            "and it must be part of the row, or the viewer still cannot tell",
            player.contains("cannotDraw\n                            ).joinToString(\" \\u00b7 \")")
        )
        val press = player.substringAfter("if (cannotDraw != null) {")
        assertTrue(
            "the press must hand off rather than arm a track that draws nothing",
            press.substring(0, minOf(1400, press.length)).contains(
                "MpvPlayerActivity.FALLBACK_REASON_SUBTITLE"
            )
        )
        assertTrue(
            "a press IS the request for it, so the handoff may not be gated on the " +
                "automatic-fallback setting - the same rule the SWITCH button uses",
            press.substring(0, minOf(1400, press.length)).contains("manual = true")
        )
    }

    /**
     * The presentation half: a decoded bitmap cue group has to reach the one
     * view that can draw a Cue.bitmap, and a text group has to take it back.
     *
     * Pinned from the source because this app has no JVM player to call (same
     * approach as the picker half above). Without the hand-back the stock view,
     * which PlayerView forwards every cue group to, keeps drawing the last
     * bitmap frame under the text.
     */
    @Test
    fun `the cue handler draws a bitmap group on the stock view and gives it back for text`() {
        val handler = read(PLAYER)
            .substringAfter("private inner class SubtitleCueHandler : Player.Listener {")
            .substringBefore("private fun readSubtitleText")
        assertTrue(
            "the route is decided by what the group carries, not by a track format",
            handler.contains("cueGroup.cues.any { it.bitmap != null }")
        )
        assertTrue(
            "a bitmap group goes to the only view that can draw Cue.bitmap",
            handler.contains("view.setCues(cueGroup.cues)") &&
                handler.contains("view.visibility = View.VISIBLE")
        )
        assertTrue(
            "and the text path hides the view again before it renders, so the two " +
                "never draw at once and no bitmap frame outlives its track",
            Regex("clearBitmapCues\\(\\)\\s*if \\(positionDriven\\)").containsMatchIn(handler)
        )
        assertTrue(
            "a rebuilt player or a live zap must not leave the last frame on screen",
            handler.substringAfter("fun cancelPending()")
                .substringBefore("fun reapplyOffset")
                .contains("clearBitmapCues()")
        )
    }

    private fun read(relative: String): String {
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
        const val PLAYER = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
    }
}
