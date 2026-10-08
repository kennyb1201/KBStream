package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scrub previews are gone, and scrubbing is not.
 *
 * The previews could not be made worth having. A preview frame was only ever
 * produced after the scrub's own seek had landed and the surface had settled on
 * it, so it arrived a second or more after the press that asked for it - by then
 * the viewer had scrubbed on, and the card showed a moment they were already
 * past. A correct version needs frames extracted ahead of time, which is a
 * feature of its own; until then the right answer is to not draw a late card.
 *
 * What must survive is the scrubbing itself. The removal ran through the
 * handlers that do it - the seek bar's drag, the D-pad's step, mpv's seekBy - so
 * both halves are pinned here: no preview pipeline left in either engine, and
 * every seek path the previews used to ride on still present.
 */
class ScrubPreviewsRemovedContractTest {

    private val sourceRoot: File by lazy { findSourceRoot() }

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

    private fun source(path: String): String {
        val file = File(sourceRoot, path)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private companion object {
        const val PACKAGE = "com/kennyb1201/kbstream/"
        const val PLAYER = PACKAGE + "ui/player/"
        const val NATIVE = PLAYER + "NativePlayerActivity.kt"
        const val MPV = PLAYER + "MpvPlayerActivity.kt"
        const val DIAGNOSTICS = PACKAGE + "data/reporting/Diagnostics.kt"

        /** The four files the preview pipeline lived in. */
        val REMOVED = listOf(
            "Trickplay.kt",
            "TrickplayFrames.kt",
            "TrickplayOverlay.kt",
            "MpvScrubPreviews.kt"
        )

        /** Any one of these in a player source means the feature has come back. */
        val MARKERS = listOf("Trickplay", "trickplay", "ScrubPreview", "scrubPreview")
    }

    @Test
    fun `no scrub preview pipeline is left in either engine`() {
        listOf(NATIVE, MPV).forEach { path ->
            val scene = source(path)
            MARKERS.forEach { marker ->
                assertFalse(
                    "$marker must be gone from $path - previews arrived too late to be worth drawing",
                    scene.contains(marker)
                )
            }
        }
        assertFalse(
            "the diagnostics report no longer carries a preview line",
            source(DIAGNOSTICS).contains("trickplay")
        )
    }

    @Test
    fun `the preview implementations are deleted, not merely unused`() {
        REMOVED.forEach { name ->
            assertFalse(
                "$name must be deleted from ui/player",
                File(sourceRoot, "com/kennyb1201/kbstream/ui/player/$name").isFile
            )
        }
    }

    @Test
    fun `every seek path the previews used to ride on is still wired`() {
        // The main player: the D-pad's ten-second step. Its bar's drag is the
        // shared chrome's now - both engines draw the same bar - so the drag half
        // is pinned against PlayerChrome below. Each anchor is a line that lives
        // only in its handler, so a comment or a declaration cannot satisfy it.
        val native = source(NATIVE)
        assertTrue(
            "a LEFT/RIGHT press must still step the scrub",
            native.contains("stepSeekBy(10_000L * scrubDirection)")
        )

        // libmpv: the same two, on its own surface.
        val mpv = source(MPV)
        assertTrue("a D-pad step must still seek the surface", mpv.contains("surface?.seekBy(deltaMs)"))
        assertTrue(
            "and the engine still exposes the seek the bar asks for",
            mpv.contains("override fun onChromeSeekTo(positionMs: Long) = seekTo(positionMs)")
        )
        // The bar's release-seek moved into the shared chrome (PlayerChrome),
        // which hands the position back through PlayerChromeHost.
        val chrome = source(PLAYER + "PlayerChrome.kt")
        assertTrue(
            "the bar must still seek when the press is released",
            chrome.contains("override fun onStopTrackingTouch(bar: SeekBar?)")
        )
        assertTrue(
            "and that release must still work out the position it lands on",
            chrome.contains("host.onChromeSeekTo(durationMs * (bar?.progress ?: 0) / SEEKBAR_MAX)")
        )
    }
}
