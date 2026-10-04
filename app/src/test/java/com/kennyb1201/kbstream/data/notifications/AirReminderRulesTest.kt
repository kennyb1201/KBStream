package com.kennyb1201.kbstream.data.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which id an air reminder is stored under.
 *
 * It has to be a spelling the TMDB resolver and the detail deep link both
 * understand, or a flagged show would never match the show that gets fetched.
 */
class AirReminderRulesTest {

    @Test
    fun `the imdb id wins when the title carries one`() {
        assertEquals("tt1234567", AirReminderRules.showIdFor("tt1234567", 456))
    }

    @Test
    fun `a tmdb id is used when there is no imdb id`() {
        assertEquals("tmdb:456", AirReminderRules.showIdFor(null, 456))
        assertEquals("tmdb:456", AirReminderRules.showIdFor("   ", 456))
    }

    @Test
    fun `neither id means no reminder can be offered`() {
        assertNull(AirReminderRules.showIdFor(null, null))
        assertNull(AirReminderRules.showIdFor("", null))
        assertNull(AirReminderRules.showIdFor(null, 0))
        assertNull(AirReminderRules.showIdFor(null, -1))
    }
}
