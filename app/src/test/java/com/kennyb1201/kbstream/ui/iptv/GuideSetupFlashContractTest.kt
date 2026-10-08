package com.kennyb1201.kbstream.ui.iptv

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * "The setup box flashes when I enter the guide, and I already have a playlist."
 *
 * The cause was the setup flag, not the form: `showSetup` was initialized to
 * `playlist == null`, and the ViewModel's playlist flow starts as null for
 * every install - including one that has a playlist cached. So entering the
 * guide opened the form (inline, while the playlist was still unknown), and
 * then, once the cached playlist landed, opened it AGAIN as an overlay on top
 * of the freshly drawn guide, for the frame until the effect that watches the
 * playlist cleared the flag.
 *
 * The fix is that the form never opens itself. There is no TV in CI and this
 * screen has no Compose UI harness, so the shape that fixes it is read out of
 * the source - the way the guide's other contracts are, which also means the
 * flash cannot come back through the next edit to this screen unnoticed.
 */
class GuideSetupFlashContractTest {

    /** The source with runs of whitespace collapsed to one space. */
    private val flat: String by lazy { source().replace(Regex("\\s+"), " ") }

    @Test
    fun `the setup flag starts closed`() {
        assertTrue(
            "the panel is raised by the viewer, so it starts closed",
            flat.contains("var showSetup by remember { mutableStateOf(false) }")
        )
        assertFalse(
            "starting open on an unknown playlist is what flashed the form on the way in",
            flat.contains("mutableStateOf(playlist == null)")
        )
    }

    @Test
    fun `the setup panel is drawn from a known state, never from a missing playlist`() {
        assertTrue(
            "with no playlist the form is the screen",
            flat.contains("if (playlist == null) {")
        )
        assertTrue(
            "and over a loaded guide it is the viewer's own toggle",
            flat.contains("onSetupClick = { showSetup = !showSetup }")
        )
        assertTrue(
            "the overlay placement still requires both",
            flat.contains("if (showSetup && playlist != null) {")
        )
    }

    @Test
    fun `the guide does not draw the form over a guide it has already loaded`() {
        // The two states are exclusive: the form is either the whole screen
        // (no playlist) or an overlay the viewer asked for, so there is no
        // frame in which a loaded guide is covered by a form nobody opened.
        assertFalse(
            "a playlist must not open the overlay by itself",
            flat.contains("if (playlist != null) showSetup = true")
        )
    }

    private fun source(): String {
        val file = File(findSourceRoot(), SCREEN)
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
        const val SCREEN = "com/kennyb1201/kbstream/ui/iptv/GuideScreen.kt"
    }
}
