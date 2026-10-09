package com.kennyb1201.kbstream.ui.player

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Three reports about the MPV backup engine's screen, pinned where they can be
 * seen.
 *
 *  - **"The lists are empty when you open SOURCES / AUDIO / SUBTITLES on MPV"**:
 *    a RecyclerView lays out NOTHING without a layout manager, and this engine's
 *    picker never had one - the rows reached the adapter and were never placed,
 *    so every panel opened with its title and a blank box under it. The main
 *    player sets one in code for the same reason (see NativePlayerActivity).
 *  - **"I still see the file name and info on the MPV overlay"**: the engine's
 *    own note - the playing source's file label, that mpv is the engine, and the
 *    handoff reason - sat under the badge row, so the overlay read as a file name
 *    plus a line of diagnostics left on screen for the whole session. The badges
 *    say what is playing and the INFO readout says how.
 *  - **"Hardly anything plays, and what does is audio over a black screen"**: the
 *    video output was configured with `gpu-context=android` + `opengl-es=yes`
 *    while `gpu-api` was left on its `auto` default, which a box advertising
 *    Vulkan can answer with the Vulkan path - a mismatched pair that does not
 *    fail the open (audio plays, the track list populates) but never puts a
 *    frame on screen.
 *
 * There is no device or emulator in CI and a libmpv Activity cannot be driven in
 * a JVM test, so the structure that fixes them is read out of the source the way
 * this tree's other player contracts are.
 */
class MpvEngineOverlayContractTest {

    private val activity: String by lazy { read(MPV) }
    private val view: String by lazy { read(MPV_VIEW) }

    @Test
    fun `the picker list is given a layout manager, or every panel is a blank box`() {
        assertTrue(
            "the MPV picker's RecyclerView must be given a LinearLayoutManager: without one it " +
                "places no rows at all, which is the empty SOURCES / AUDIO / SUBTITLES panel",
            activity.contains("pickerList?.layoutManager = LinearLayoutManager(this)")
        )
        assertTrue(
            "and the import it needs has to be there with it",
            activity.contains("import androidx.recyclerview.widget.LinearLayoutManager")
        )
    }

    @Test
    fun `the overlay carries the badges, not a file name and a line of diagnostics`() {
        assertFalse(
            "the engine note (the source's file label + which engine + the handoff reason) must no " +
                "longer be inflated into the overlay",
            activity.contains("player_mpv_engine_note")
        )
        assertFalse(
            "nor held in a field and assigned anywhere else",
            activity.contains("engineNoteView")
        )
        assertTrue(
            "while the badge row that IS what the overlay should show is still what the host " +
                "hands the shared chrome",
            activity.contains("badges = currentBadges")
        )
    }

    @Test
    fun `the video output pins the API its own context and opengl-es already imply`() {
        val context = view.indexOf("mpv.setOptionString(\"gpu-context\", \"android\")")
        val api = view.indexOf("mpv.setOptionString(\"gpu-api\", \"opengl\")")
        val es = view.indexOf("mpv.setOptionString(\"opengl-es\", \"yes\")")
        assertTrue("the Android GPU context is still configured", context >= 0)
        assertTrue("and OpenGL ES is still what it renders with", es >= 0)
        assertTrue(
            "so gpu-api must not be left on auto: with the context and opengl-es above, a " +
                "Vulkan-capable box is otherwise free to answer with the Vulkan path, which is " +
                "audio over a black picture",
            api > context && api < es
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
        const val MPV = "com/kennyb1201/kbstream/ui/player/MpvPlayerActivity.kt"
        const val MPV_VIEW = "com/kennyb1201/kbstream/ui/player/MpvPlayerView.kt"
    }
}
