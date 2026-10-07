package com.kennyb1201.kbstream.ui.detail

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The resume bar under the detail page's PLAY control follows the theme.
 *
 * Reported: it did not. It painted with `LocalContentColor`, on the theory
 * that a card's content color is the accent while focused - but [KBCard]
 * deliberately keeps its content bright in BOTH states (`KBTextHi` idle and
 * focused), so the bar sat there as plain white while every other progress bar
 * in the app (poster tiles, Up Next cards - see `KBProgressBar`) moved with the
 * viewer's accent.
 *
 * The accent is profile-scoped live state, so the read has to happen INSIDE the
 * composable: a top-level val is evaluated once when the class loads and freezes
 * on the first-loaded theme, which is exactly how the watch markers broke (see
 * [com.kennyb1201.kbstream.ui.components.WatchMarkerAccentContractTest]).
 *
 * Read from the source: this is a Brush on a Box behind a composable that needs
 * a device to lay out.
 */
class DetailPlayProgressThemeContractTest {

    private val detail: String by lazy {
        val file = File(findMainSourceRoot(), DETAIL)
        assertTrue("source missing: $file", file.isFile)
        file.readText()
    }

    /** The button body alone, up to the next top-level declaration's doc. */
    private val buttonBody: String by lazy {
        val afterSignature = detail.substringAfter("private fun IconButtonBody(")
        assertTrue(
            "the detail page must still have the shared button body",
            afterSignature != detail
        )
        afterSignature.substringBefore("\n/**")
    }

    private fun findMainSourceRoot(): File {
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

    @Test
    fun `the bar paints with the theme accent`() {
        assertTrue(
            "the bar's colour must be the theme accent, read in the composable",
            buttonBody.contains("val barColor = KBAccent")
        )
        assertTrue(
            "the fill is the accent",
            buttonBody.contains(".background(barColor)")
        )
        assertFalse(
            "the button's content color is KBTextHi in both focus states, so " +
                "painting the bar with it leaves the one white progress bar in " +
                "the app",
            buttonBody.contains("val barColor = androidx.tv.material3.LocalContentColor.current")
        )
    }

    @Test
    fun `the accent is not captured into a top-level val`() {
        // The exact freeze: a top-level val is evaluated once, when the file's
        // class loads, so it never moves on an accent or profile switch.
        val captured = Regex("""^\s*private val \w+\s*=\s*KBAccent\s*$""", RegexOption.MULTILINE)
        assertFalse(
            "DetailScreen.kt must not capture KBAccent into a top-level val",
            captured.containsMatchIn(detail)
        )
    }

    @Test
    fun `the icon tint still follows the card it sits in`() {
        // The bar was the only thing that had to change: the glyphs keep
        // tinting with the card's content colour (and the brand mark keeps its
        // own rule), or focus would stop moving the icons at all.
        assertTrue(
            buttonBody.contains("androidx.tv.material3.LocalContentColor.current")
        )
        assertTrue(
            "the brand mark's own accent rule is untouched",
            buttonBody.contains("kbAccentIndexState.value == DEFAULT_ACCENT_INDEX")
        )
    }

    private companion object {
        const val DETAIL = "com/kennyb1201/kbstream/ui/detail/DetailScreen.kt"
    }
}
