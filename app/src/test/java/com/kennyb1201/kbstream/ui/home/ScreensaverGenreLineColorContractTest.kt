package com.kennyb1201.kbstream.ui.home

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The screensaver's genre line is white (spec 2026-11-25).
 *
 * The slide shows the title's clearlogo and, under it, one tracked-capitals line
 * of genre - the only text on the screen besides the wordmark. It was drawn in
 * `KBTextLo`, the muted grey-blue chosen when the line was meant to sit quietly
 * under the art; at television distance that grey read as part of the backdrop.
 * It is now `KBTextHi`, the app's own warm off-white - white on the dark art,
 * the same primary white every other line of text in the app uses, so a scene
 * can be read without walking up to the panel.
 *
 * Only the colour moved: same tracked capitals, same `bodySmall`, same 2sp
 * tracking, same one-line ellipsis, same wordmark-width cap, same 10dp gap under
 * the wordmark. That is what this pins besides the colour, because "make the
 * label white" must not turn into "re-style the label".
 *
 * The slide is one shared composable for the Home, Detail and Settings
 * screensavers, so this one file is all three surfaces.
 */
class ScreensaverGenreLineColorContractTest {

    private val overlay: String by lazy {
        val raw = read("ui/home/HomeScreensaver.kt")
        raw.replace(Regex("\\s+"), " ")
    }

    /** The genre line itself: from its `let` to the next declaration. */
    private val label: String by lazy {
        val start = overlay.indexOf("genre?.let { line ->")
        assertTrue("the slide must still draw its genre line", start >= 0)
        val end = overlay.indexOf("private val ScreensaverCornerPadding", start)
        assertTrue("the genre line must be a block of its own", end > start)
        overlay.substring(start, end)
    }

    @Test
    fun `the genre line is the app's primary white`() {
        assertTrue(
            "the line has to be KBTextHi - the app's white - not the muted grey " +
                "that vanished into the art: $label",
            label.contains("color = KBTextHi,")
        )
        assertFalse(
            "and the old grey must be gone from the line entirely",
            label.contains("KBTextLo")
        )
        assertFalse(
            "and gone from the file: the swap replaces the import rather than " +
                "adding a second colour beside it (ktlint fails an unused import)",
            overlay.contains("ui.theme.KBTextLo")
        )
    }

    @Test
    fun `nothing else about the line changed`() {
        assertTrue("tracked capitals: $label", label.contains("text = line.uppercase()"))
        assertTrue(
            "at the same small label size and tracking",
            label.contains("style = MaterialTheme.typography.bodySmall") &&
                label.contains("letterSpacing = ScreensaverGenreTracking") &&
                overlay.contains("private val ScreensaverGenreTracking = 2.sp")
        )
        assertTrue(
            "one line, centred, ellipsized at the wordmark's width",
            label.contains("maxLines = 1") &&
                label.contains("overflow = TextOverflow.Ellipsis") &&
                label.contains("textAlign = TextAlign.Center") &&
                label.contains(".width(ScreensaverLogoWidth)")
        )
        assertTrue(
            "and the same 10dp gap under the wordmark",
            label.contains(".padding(top = ScreensaverGenreGap)") &&
                overlay.contains("private val ScreensaverGenreGap = 10.dp")
        )
    }

    @Test
    fun `the wordmark and the slide's own shape are untouched`() {
        assertTrue(
            "the clearlogo is still the fitted wordmark in its box",
            overlay.contains("contentScale = ContentScale.Fit") &&
                overlay.contains("private val ScreensaverLogoWidth = 380.dp") &&
                overlay.contains("private val ScreensaverLogoHeight = 104.dp")
        )
        assertTrue(
            "a title with no genre still draws no line at all",
            overlay.contains("genre?.let { line ->")
        )
    }

    private fun read(relative: String): String {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate =
                    File(dir, "${prefix}src/main/java/com/kennyb1201/kbstream/$relative")
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("source missing: $relative")
    }
}
