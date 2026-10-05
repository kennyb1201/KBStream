package com.kennyb1201.kbstream.data.spoiler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one rule spoiler-free mode is built on: which episodes a viewer must not
 * be told about, and what they are called instead.
 *
 * Everything that shows an episode name, still or synopsis reads this, so the
 * three facts it is a function of are the whole contract: the profile's
 * toggle, whether the episode is watched, and whether it has been started.
 */
class SpoilerFreeTest {

    @Test
    fun `with the mode off nothing is hidden`() {
        listOf(
            false to false,
            false to true,
            true to false,
            true to true
        ).forEach { (watched, started) ->
            assertFalse(
                "mode off must hide nothing (watched=$watched started=$started)",
                SpoilerFree.hidesIdentity(enabled = false, watched = watched, started = started)
            )
        }
    }

    @Test
    fun `watched and in-progress episodes keep their identity`() {
        assertFalse(
            "an episode already watched is behind the viewer, not ahead of them",
            SpoilerFree.hidesIdentity(enabled = true, watched = true, started = false)
        )
        assertFalse(
            "the episode they are part-way through is where they left off",
            SpoilerFree.hidesIdentity(enabled = true, watched = false, started = true)
        )
    }

    @Test
    fun `an episode that was never started is hidden`() {
        assertTrue(
            SpoilerFree.hidesIdentity(enabled = true, watched = false, started = false)
        )
    }

    @Test
    fun `a hidden episode is listed by number, never by name`() {
        assertEquals(
            "Episode 7",
            SpoilerFree.episodeLabel(hidden = true, episodeNumber = 7, realTitle = "The Funeral")
        )
        // Not "The Fun…" and not blank: a placeholder that leaks the front of
        // a name is the same spoiler, one character shorter.
        assertFalse(
            SpoilerFree.episodeLabel(hidden = true, episodeNumber = 7, realTitle = "The Funeral")
                .contains("Fun")
        )
    }

    @Test
    fun `a shown episode keeps its own name, and falls back when it has none`() {
        assertEquals(
            "The Funeral",
            SpoilerFree.episodeLabel(hidden = false, episodeNumber = 7, realTitle = "The Funeral")
        )
        assertEquals(
            "Episode 7",
            SpoilerFree.episodeLabel(hidden = false, episodeNumber = 7, realTitle = null)
        )
        assertEquals(
            "Episode 7",
            SpoilerFree.episodeLabel(hidden = false, episodeNumber = 7, realTitle = "   ")
        )
        assertEquals(
            "Episode",
            SpoilerFree.episodeLabel(hidden = true, episodeNumber = 0, realTitle = "Pilot")
        )
    }
}
