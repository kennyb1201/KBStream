package com.kennyb1201.kbstream.data.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bug this pins: the settings store ISO 639-1 codes ("en") while files tag
 * their tracks with ISO 639-2 ones ("eng"), so comparing them as strings
 * matched nothing on real content — audio stayed on the muxer's first track and
 * a subtitle preference disabled subtitles entirely.
 */
class LanguageMatchTest {

    // ── the two spellings of the same language ─────────────────────────────

    @Test
    fun `a two letter preference matches the three letter tag in the file`() {
        assertTrue(LanguageMatch.matches("en", "eng"))
        assertTrue(LanguageMatch.matches("es", "spa"))
        assertTrue(LanguageMatch.matches("it", "ita"))
        assertTrue(LanguageMatch.matches("ru", "rus"))
        assertTrue(LanguageMatch.matches("ja", "jpn"))
        assertTrue(LanguageMatch.matches("ko", "kor"))
        assertTrue(LanguageMatch.matches("pt", "por"))
    }

    @Test
    fun `both spellings of the 639-2 code are accepted`() {
        // Muxers write the bibliographic form as often as the terminological
        // one; which one shows up is luck, not intent.
        assertTrue(LanguageMatch.matches("de", "ger"))
        assertTrue(LanguageMatch.matches("de", "deu"))
        assertTrue(LanguageMatch.matches("fr", "fre"))
        assertTrue(LanguageMatch.matches("fr", "fra"))
        assertTrue(LanguageMatch.matches("zh", "chi"))
        assertTrue(LanguageMatch.matches("zh", "zho"))
    }

    @Test
    fun `the match is symmetric and case insensitive`() {
        assertTrue(LanguageMatch.matches("eng", "en"))
        assertTrue(LanguageMatch.matches("EN", "Eng"))
        assertTrue(LanguageMatch.matches("Eng", "en"))
    }

    // ── region and script subtags ──────────────────────────────────────────

    @Test
    fun `region subtags do not stop a match`() {
        assertTrue(LanguageMatch.matches("en", "en-US"))
        assertTrue(LanguageMatch.matches("en", "ENG-GB"))
        assertTrue(LanguageMatch.matches("pt", "pt-BR"))
        assertTrue(LanguageMatch.matches("pt-BR", "pt"))
        assertTrue(LanguageMatch.matches("pt-BR", "por"))
        assertTrue(LanguageMatch.matches("zh", "zh-Hans"))
        assertTrue(LanguageMatch.matches("zh", "cmn"))
    }

    @Test
    fun `underscore separated regions are handled too`() {
        assertTrue(LanguageMatch.matches("pt", "pt_BR"))
    }

    // ── what must NOT match ────────────────────────────────────────────────

    @Test
    fun `different languages never match`() {
        assertFalse(LanguageMatch.matches("en", "spa"))
        assertFalse(LanguageMatch.matches("en", "jpn"))
        assertFalse(LanguageMatch.matches("ru", "ita"))
    }

    /**
     * The trap a prefix rule would fall into: "est" is Estonian, and it starts
     * with the Spanish code. Only the table may equate two codes.
     */
    @Test
    fun `a longer code beginning with a known one is not that language`() {
        assertFalse(LanguageMatch.matches("es", "est"))
        assertFalse(LanguageMatch.matches("es", "estonian"))
    }

    @Test
    fun `an undetermined tag never matches a request`() {
        assertFalse(LanguageMatch.matches("en", "und"))
        assertFalse(LanguageMatch.matches("en", "unknown"))
        assertFalse(LanguageMatch.matches("en", "mul"))
        assertFalse(LanguageMatch.matches("und", "eng"))
    }

    @Test
    fun `a missing tag never matches a request`() {
        assertFalse(LanguageMatch.matches("en", null))
        assertFalse(LanguageMatch.matches("en", ""))
        assertFalse(LanguageMatch.matches("en", "   "))
        assertFalse(LanguageMatch.matches(null, "eng"))
        assertFalse(LanguageMatch.matches("", "eng"))
    }

    @Test
    fun `Auto never matches`() {
        assertFalse(LanguageMatch.matches("", ""))
        assertFalse(LanguageMatch.matches("", null))
    }

    // ── languages the table does not list ──────────────────────────────────

    @Test
    fun `an unlisted tag still matches itself`() {
        assertTrue(LanguageMatch.matches("fi", "fi"))
        assertTrue(LanguageMatch.matches("fi", "FI"))
        assertTrue(LanguageMatch.matches("sv-FI", "sv"))
    }

    @Test
    fun `canonical folds to the two letter code`() {
        assertEquals("en", LanguageMatch.canonical("eng"))
        assertEquals("en", LanguageMatch.canonical("EN-us"))
        assertEquals("de", LanguageMatch.canonical("GER"))
        assertEquals("en", LanguageMatch.canonical("en"))
        // Unlisted tags are their own canonical form, so identity still works.
        assertEquals("fi", LanguageMatch.canonical("fi"))
        assertNull(LanguageMatch.canonical(null))
        assertNull(LanguageMatch.canonical(""))
        assertNull(LanguageMatch.canonical("und"))
    }

    /**
     * Storing the canonical form is what makes the panel's language pills
     * highlight after picking a track: the track says "eng", the pill is "en".
     */
    @Test
    fun `canonical output matches the code the panel offers`() {
        val offered = listOf("en", "es", "fr", "de", "ja", "ko", "zh", "pt", "it", "ru")
        val fromFiles = listOf(
            "eng", "spa", "fre", "fra", "ger", "deu", "jpn", "kor",
            "chi", "zho", "por", "ita", "rus"
        )
        for (tag in fromFiles) {
            assertTrue(
                "canonical($tag) should be one of the offered codes",
                LanguageMatch.canonical(tag) in offered
            )
        }
    }

    // ── the fallback for a file with no track in the wanted language ───────

    /**
     * The reported failure: an English preference on an EN/RU release whose
     * English track carries no language tag ("und", common in dual-audio
     * rips). Nothing matched, so the muxer's DEFAULT — the Russian track —
     * played. An untagged track might be the English one; a `rus` track
     * certainly is not, so the untagged one is what the fallback must take.
     */
    @Test
    fun `an untagged track is preferred over one tagged in another language`() {
        assertEquals(0, LanguageMatch.fallbackTrackIndex(listOf("und", "rus"), "en"))
        assertEquals(1, LanguageMatch.fallbackTrackIndex(listOf("rus", "und"), "en"))
        assertEquals(0, LanguageMatch.fallbackTrackIndex(listOf(null, "rus"), "en"))
        assertEquals(1, LanguageMatch.fallbackTrackIndex(listOf("rus", null), "en"))
        assertEquals(0, LanguageMatch.fallbackTrackIndex(listOf("unknown", "rus"), "en"))
        assertEquals(1, LanguageMatch.fallbackTrackIndex(listOf("rus", "unknown"), "en"))
        // "mul" means several languages at once, which is not English either:
        // it is undetermined, so it is eligible like any other untagged track.
        assertEquals(0, LanguageMatch.fallbackTrackIndex(listOf("mul", "eng"), "en"))
    }

    @Test
    fun `every track known-wrong leaves the muxer default alone`() {
        assertNull(LanguageMatch.fallbackTrackIndex(listOf("rus", "ger"), "en"))
        assertNull(LanguageMatch.fallbackTrackIndex(listOf("rus"), "en"))
        assertNull(LanguageMatch.fallbackTrackIndex(emptyList(), "en"))
    }

    @Test
    fun `the first eligible track wins`() {
        assertEquals(0, LanguageMatch.fallbackTrackIndex(listOf("und", "und"), "en"))
        assertEquals(0, LanguageMatch.fallbackTrackIndex(listOf(null, "und"), "en"))
        assertEquals(1, LanguageMatch.fallbackTrackIndex(listOf("rus", "und", "und"), "en"))
    }

    /**
     * A direct hit is returned where it sits, not skipped in favour of an
     * earlier untagged track: this is only called when nothing matched, so the
     * branch is defensive, but it must never prefer a guess over an answer.
     */
    @Test
    fun `a direct hit is returned even when it is not the first track`() {
        assertEquals(0, LanguageMatch.fallbackTrackIndex(listOf("eng", "rus"), "en"))
        assertEquals(1, LanguageMatch.fallbackTrackIndex(listOf("rus", "eng"), "en"))
        assertEquals(1, LanguageMatch.fallbackTrackIndex(listOf("rus", "Eng"), "en"))
        assertEquals(1, LanguageMatch.fallbackTrackIndex(listOf("rus", "en-US"), "en"))
    }

    /**
     * "Auto" with no global default, and anything that names no language, has
     * nothing to fall back TO: a guess here would be worse than the file's own
     * choice.
     */
    @Test
    fun `nothing asked for means no fallback at all`() {
        assertNull(LanguageMatch.fallbackTrackIndex(listOf("und", "rus"), ""))
        assertNull(LanguageMatch.fallbackTrackIndex(listOf("und", "rus"), "   "))
        assertNull(LanguageMatch.fallbackTrackIndex(listOf("und", "rus"), "und"))
        assertNull(LanguageMatch.fallbackTrackIndex(listOf("und", "rus"), "unknown"))
        assertNull(LanguageMatch.fallbackTrackIndex(listOf("rus"), "und"))
    }

    /**
     * The loose spelling a file's own tag may carry still counts as a direct
     * hit, and the region subtag is not a reason to skip a real track.
     */
    @Test
    fun `the fallback reads the same tags the matcher does`() {
        assertEquals(0, LanguageMatch.fallbackTrackIndex(listOf("fi", "rus"), "FI"))
        assertEquals(1, LanguageMatch.fallbackTrackIndex(listOf("rus", "pt-BR"), "pt"))
    }
}
