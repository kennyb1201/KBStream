package com.kennyb1201.kbstream.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bug this pins: the player's LANGUAGE pills highlighted "Auto" while the
 * global Language setting was English, which reads as "my setting was ignored".
 * Auto does inherit the global preference — it just never said so.
 *
 * These tests hold the two halves of the fix in place: the Auto pill names the
 * language it inherits, and the memory label spells out what Auto is following.
 */
class PlayerTrackBridgeLanguageTest {

    // ── the Auto pill names what it inherits ───────────────────────────────

    @Test
    fun `Auto names the global language it falls back to`() {
        val options = PlayerTrackBridge.playerLanguageOptions("en")
        assertEquals("Auto · English", options.first().first)
        // Still Auto: a blank code is what makes the activity fall back to the
        // global preference, so the label must not become a real choice.
        assertEquals("", options.first().second)
    }

    @Test
    fun `the three letter tag a file remembers still names the language`() {
        assertEquals("Auto · English", PlayerTrackBridge.playerLanguageOptions("eng").first().first)
        assertEquals("Auto · German", PlayerTrackBridge.playerLanguageOptions("deu").first().first)
        assertEquals("Auto · Portuguese", PlayerTrackBridge.playerLanguageOptions("por").first().first)
    }

    @Test
    fun `a region carrying preference names its language`() {
        assertEquals("Auto · Portuguese", PlayerTrackBridge.playerLanguageOptions("pt-BR").first().first)
    }

    @Test
    fun `only the Auto entry changes`() {
        val plain = PlayerTrackBridge.LANGUAGE_OPTIONS
        val labelled = PlayerTrackBridge.playerLanguageOptions("en")

        assertEquals(plain.size, labelled.size)
        assertEquals(plain.drop(1), labelled.drop(1))
        // The codes are what get stored and applied; none of them may shift.
        assertEquals(plain.map { it.second }, labelled.map { it.second })
    }

    @Test
    fun `a language the list does not offer is still named`() {
        assertEquals("Auto · FI", PlayerTrackBridge.playerLanguageOptions("fi").first().first)
    }

    @Test
    fun `no global preference leaves the pills exactly as they were`() {
        assertEquals(PlayerTrackBridge.LANGUAGE_OPTIONS, PlayerTrackBridge.playerLanguageOptions(""))
        assertEquals(PlayerTrackBridge.LANGUAGE_OPTIONS, PlayerTrackBridge.playerLanguageOptions("   "))
    }

    // ── naming one language ────────────────────────────────────────────────

    @Test
    fun `language names resolve through the same matching as track selection`() {
        assertEquals("English", PlayerTrackBridge.languageName("en"))
        assertEquals("English", PlayerTrackBridge.languageName("eng"))
        assertEquals("English", PlayerTrackBridge.languageName("EN-us"))
        assertEquals("Spanish", PlayerTrackBridge.languageName("spa"))
        assertEquals("Japanese", PlayerTrackBridge.languageName("ja"))
    }

    @Test
    fun `nothing selected reads as Auto`() {
        assertEquals("Auto", PlayerTrackBridge.languageName(""))
        assertEquals("Auto", PlayerTrackBridge.languageName("   "))
    }

    @Test
    fun `an unlisted language falls back to its own code`() {
        assertEquals("FI", PlayerTrackBridge.languageName("fi"))
    }

    // ── the label under the LANGUAGE heading ───────────────────────────────

    /**
     * With nothing configured anywhere, Auto means each file's own default
     * tracks — the pre-memory behavior, and what the label must say.
     */
    @Test
    fun `with no global preference the summary says the file decides`() {
        val summary = PlayerTrackBridge.inheritedLanguageSummary()
        assertTrue(
            "unexpected summary with no globals set: $summary",
            summary == "Auto uses each file's default tracks" ||
                summary == "Using this title's remembered languages"
        )
    }
}
