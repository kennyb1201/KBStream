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
 * A track this engine ships no renderer for is still listed (it is in the
 * file), and selecting it used to arm it, draw nothing and report nothing -
 * the one failure a viewer cannot describe, because the row said as little as
 * every other row and the picker did not change. Two halves of the answer are
 * pinned here: the row now says so ([SubtitleTrackRules.cannotDrawNote]), and
 * the press hands the track to the engine that CAN draw it.
 */
class SubtitleCannotDrawTest {

    @Test
    fun `a bitmap track says it needs the other engine`() {
        assertEquals("needs MPV", SubtitleTrackRules.cannotDrawNote(MimeTypes.APPLICATION_PGS, false))
        assertEquals("needs MPV", SubtitleTrackRules.cannotDrawNote(MimeTypes.APPLICATION_VOBSUB, false))
        assertEquals("needs MPV", SubtitleTrackRules.cannotDrawNote(MimeTypes.APPLICATION_DVBSUBS, false))
        // Even if a future media3 claims to support one: bitmaps are bitmaps.
        assertEquals("needs MPV", SubtitleTrackRules.cannotDrawNote(MimeTypes.APPLICATION_PGS, true))
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
