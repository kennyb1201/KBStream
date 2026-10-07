package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The chapter marker wiring, which the screen itself cannot assert without a
 * device.
 *
 * Phase 1 is MPV-only: libmpv reports a file's chapters through its property
 * interface, while ExoPlayer has no chapter API at all, so the Exo bar is
 * swapped to the same view but never given marks. Everything here is something
 * that fails SILENTLY on a TV - a stack that reads no chapters, a strip that
 * shows itself on a file with none, a button that seeks nothing - which is why
 * it is pinned by reading the source, the same approach the repo's other player
 * contracts take.
 */
class ChapterSeekBarWiringContractTest {

    private fun read(relative: String): String {
        val file = File(sourceRoot(), relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    private fun layout(relative: String): String {
        val file = File(resRoot(), relative)
        assertTrue("layout missing: $file", file.isFile)
        return file.readText()
    }

    @Test
    fun `a file with no chapters reads as an empty list, not a crash`() {
        val view = read(MPV_VIEW)
        assertTrue(
            "a missing or zero `chapters` property must mean no markers",
            view.contains("val count = getPropertyIntOrNull(\"chapters\") ?: return emptyList()")
        )
        assertTrue(
            "and a zero count must too",
            view.contains("if (count <= 0) return emptyList()")
        )
        assertTrue(
            "each chapter's time is read through mpv's indexed path, in seconds",
            view.contains("getPropertyDoubleOrNull(\"chapter-list/\$index/time\")")
        )
    }

    @Test
    fun `the chapter strip is hidden whenever the file has no chapters`() {
        val activity = read(MPV_ACTIVITY)
        assertTrue(
            "the strip must be driven by the parsed marks, not by a manual flag",
            activity.contains(
                "chapterRow?.visibility = if (chapterMarks.isEmpty()) View.GONE else View.VISIBLE"
            )
        )
        assertTrue(
            "chapters arrive with the file, so they are read on file-loaded",
            activity.contains("syncChapters()")
        )
    }

    @Test
    fun `prev and next step mpv's own chapter`() {
        val view = read(MPV_VIEW)
        assertTrue(
            "the step must go through mpv's `add chapter` command",
            view.contains("mpv.command(arrayOf(\"add\", \"chapter\", delta.toString()))")
        )
        val activity = read(MPV_ACTIVITY)
        assertTrue("PREV steps back one chapter", activity.contains("surface?.addChapter(-1)"))
        assertTrue("NEXT steps forward one", activity.contains("surface?.addChapter(1)"))
    }

    @Test
    fun `both bars are the chapter-capable view, under the same ids`() {
        val mpv = layout("layout/activity_mpv_player.xml")
        val exo = layout("layout/activity_player.xml")
        assertTrue(
            "the MPV bar keeps its id",
            mpv.contains("com.kennyb1201.kbstream.ui.player.ChapterSeekBar") &&
                mpv.contains("android:id=\"@+id/mpv_seekbar\"")
        )
        assertTrue(
            "the Exo bar keeps its id, so it draws identically with no marks",
            exo.contains("com.kennyb1201.kbstream.ui.player.ChapterSeekBar") &&
                exo.contains("android:id=\"@+id/seekbar\"")
        )
    }

    @Test
    fun `no chapter parsing was attempted on the ExoPlayer side`() {
        // Phase 1 is MPV-only, and the spec is explicit that this must not grow
        // an Exo chapter reader: nothing in the native player parses chapters.
        assertFalse(
            "the Exo engine must not read chapters",
            read(NATIVE_ACTIVITY).contains("readChapters")
        )
    }

    private fun sourceRoot(): File = findRoot("src/main/java")

    private fun resRoot(): File = findRoot("src/main/res")

    private fun findRoot(relative: String): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "$prefix$relative")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "root for $relative not found walking up from " + System.getProperty("user.dir")
        )
    }

    private companion object {
        const val MPV_VIEW = "com/kennyb1201/kbstream/ui/player/MpvPlayerView.kt"
        const val MPV_ACTIVITY = "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
        const val NATIVE_ACTIVITY = "com/kennyb1201/kbstream/ui/player/NativePlayerActivity.kt"
    }
}
