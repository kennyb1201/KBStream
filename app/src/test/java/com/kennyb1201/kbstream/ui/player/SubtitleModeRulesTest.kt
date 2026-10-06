package com.kennyb1201.kbstream.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The subtitle mode model: off / forced only / on.
 *
 * The mapping is the whole contract - a stored Int becomes either "show
 * nothing", "show only forced cues" or "follow the language" - so it is pinned
 * here without a player. An unknown value from a newer build must degrade to
 * the language rules rather than to something the viewer did not pick.
 */
class SubtitleModeRulesTest {

    @Test
    fun `the default is on, the language-following behavior`() {
        assertEquals(SubtitleModeRules.ON, SubtitleModeRules.DEFAULT)
        // AppPreferences cannot depend on ui.player, so it spells its own
        // default; this is the bridge that keeps the two in step.
        assertEquals(2, SubtitleModeRules.ON)
    }

    @Test
    fun `an unknown stored value degrades to on`() {
        assertEquals(SubtitleModeRules.ON, SubtitleModeRules.normalized(-1))
        assertEquals(SubtitleModeRules.ON, SubtitleModeRules.normalized(99))
    }

    @Test
    fun `the declared values survive normalization`() {
        SubtitleModeRules.OPTIONS.forEach { (_, value) ->
            assertEquals(value, SubtitleModeRules.normalized(value))
        }
    }

    @Test
    fun `off never auto-selects and the other modes do`() {
        assertFalse(SubtitleModeRules.autoSelects(SubtitleModeRules.OFF))
        assertTrue(SubtitleModeRules.autoSelects(SubtitleModeRules.FORCED))
        assertTrue(SubtitleModeRules.autoSelects(SubtitleModeRules.ON))
    }

    @Test
    fun `the labels name the three states`() {
        assertEquals("Off", SubtitleModeRules.label(SubtitleModeRules.OFF))
        assertEquals("Forced only", SubtitleModeRules.label(SubtitleModeRules.FORCED))
        assertEquals("On", SubtitleModeRules.label(SubtitleModeRules.ON))
    }

    @Test
    fun `the option list carries each mode exactly once, off first`() {
        assertEquals(
            listOf(SubtitleModeRules.OFF, SubtitleModeRules.FORCED, SubtitleModeRules.ON),
            SubtitleModeRules.OPTIONS.map { it.second }
        )
    }
}
