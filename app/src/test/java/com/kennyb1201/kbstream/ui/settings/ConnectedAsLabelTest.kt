package com.kennyb1201.kbstream.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The connected-row label (spec 2026-11-14, F1).
 *
 * "Kitsu — connected" was the whole complaint: the row said a session existed
 * and nothing about WHOSE. The rule is one rule for every service, and its
 * fallback is as load-bearing as its success: when the name could not be
 * resolved (offline, a failed identity read) the row has to read exactly as it
 * did before - never a blank, never an id, never an invented name.
 */
class ConnectedAsLabelTest {

    @Test
    fun `a resolved name is shown after "connected as"`() {
        assertEquals("Kitsu — connected as Kennyb12", connectedAsLabel("Kitsu", "Kennyb12"))
        assertEquals("AniList — connected as Kennyb12", connectedAsLabel("AniList", "Kennyb12"))
        assertEquals("Simkl — connected as kenny", connectedAsLabel("Simkl", "kenny"))
        assertEquals("Trakt — connected as kennyb12", connectedAsLabel("Trakt", "kennyb12"))
        assertEquals("BingeCat — connected as Willow", connectedAsLabel("BingeCat", "Willow"))
    }

    @Test
    fun `an unresolved name falls back to the plain label, never a blank`() {
        listOf(null, "", "   ").forEach { name ->
            assertEquals("Kitsu — connected", connectedAsLabel("Kitsu", name))
        }
    }

    @Test
    fun `surrounding whitespace on a real name is trimmed`() {
        assertEquals("Kitsu — connected as Kennyb12", connectedAsLabel("Kitsu", "  Kennyb12  "))
    }
}
