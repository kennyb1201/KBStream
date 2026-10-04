package com.kennyb1201.kbstream.ui.components

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The watch markers (the eye for started-but-unfinished, the check for
 * finished) follow the active profile's theme.
 *
 * Reported: the markers "aren't matching profiles themes". The accent is
 * profile-scoped live state ([com.kennyb1201.kbstream.ui.theme.kbAccentIndexState],
 * refreshed on every profile switch), but the badges captured it once into a
 * top-level `private val WatchedBadgeAccent = KBAccent`. A top-level val is
 * evaluated a single time when the file's class first loads, so the marker
 * froze at whatever accent was active then and never moved again - not on a
 * profile switch, not on an accent change in Settings.
 *
 * The fix reads [com.kennyb1201.kbstream.ui.theme.KBAccent] INSIDE each badge
 * composable, which is a snapshot read and so recomposes when the accent
 * changes. This test reads the source the way [SplashClearLogoContractTest]
 * does; reintroducing the captured val silently re-breaks both badges.
 */
class WatchMarkerAccentContractTest {

    private companion object {
        const val POSTER_CARD = "com/kennyb1201/kbstream/ui/components/PosterCard.kt"

        /** A top-level property initialized from [KBAccent] - the exact bug. */
        val CAPTURED_ACCENT = Regex("""^\s*private val \w+\s*=\s*KBAccent\s*$""", RegexOption.MULTILINE)
    }

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

    @Test
    fun `the marker accent is not captured into a top-level val`() {
        val card = source(POSTER_CARD)
        assertFalse(
            "PosterCard.kt must not capture KBAccent into a top-level val; the " +
                "accent is profile-scoped live state, so a captured copy freezes " +
                "the watch markers on the first-loaded theme",
            CAPTURED_ACCENT.containsMatchIn(card)
        )
    }

    @Test
    fun `both badges read KBAccent live`() {
        val card = source(POSTER_CARD)
        // One live read per badge: the check and the eye.
        assertEquals(
            "both watch badges (check + eye) must read KBAccent inside the " +
                "composable, so each recomposes with the profile's theme",
            2,
            Regex("""val accent = KBAccent""").findAll(card).count()
        )
    }

    @Test
    fun `both badges paint with the live accent`() {
        val card = source(POSTER_CARD)
        // The check's border + glyph and the eye's border + tint.
        assertTrue(
            "the completed badge must paint its ring and glyph with the live accent",
            card.contains(".border(1.5.dp, accent, CircleShape)") &&
                card.contains("color = accent,")
        )
        assertTrue(
            "the eye badge must tint its ring and glyph with the live accent",
            card.contains("tint = accent,")
        )
    }
}
