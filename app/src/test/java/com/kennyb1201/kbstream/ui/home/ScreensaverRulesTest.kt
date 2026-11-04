package com.kennyb1201.kbstream.ui.home

import com.kennyb1201.kbstream.data.addon.MetaPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The TV screensaver's rules, which a device can only show and never assert.
 *
 * The two that matter most are the ones the spec is strict about: a slide is
 * backdrop AND clearlogo or it does not exist (no poster fallback, no text
 * title), and a show's cadence is deliberate (10 s, not the billboard's 6).
 */
class ScreensaverRulesTest {

    private fun item(
        id: String,
        background: String? = "https://img/backdrop.jpg",
        logo: String? = "https://img/logo.png",
        type: String = "movie"
    ) = MetaPreview(
        id = id,
        type = type,
        name = "Title $id",
        background = background,
        logo = logo
    )

    @Test
    fun `a slide needs both a backdrop and a clearlogo`() {
        assertTrue("a title with both is a slide", item("a").hasScreensaverArt())
        assertFalse("a poster alone is not a slide", item("b", logo = null).hasScreensaverArt())
        assertFalse("a logo alone is not a slide", item("c", background = null).hasScreensaverArt())
        assertFalse(
            "a blank URL is the same as none - it would ask Coil for nothing",
            item("d", background = "  ").hasScreensaverArt()
        )
        assertFalse(
            "and so is a blank logo",
            item("e", logo = "").hasScreensaverArt()
        )
    }

    @Test
    fun `the pool keeps rail order and only the titles that qualify`() {
        val slides = screensaverSlides(
            listOf(
                item("one"),
                item("no-logo", logo = null),
                item("two"),
                item("no-backdrop", background = null),
                item("three")
            )
        )
        assertEquals(
            "skipped titles must not reorder the ones that qualify",
            listOf("one", "two", "three"),
            slides.map { it.id }
        )
    }

    @Test
    fun `an add-on that ships no artwork never arms an empty show`() {
        assertEquals(
            emptyList<MetaPreview>(),
            screensaverSlides(listOf(item("a", logo = null), item("b", background = null)))
        )
        assertEquals(emptyList<MetaPreview>(), screensaverSlides(emptyList()))
    }

    @Test
    fun `the genre is the first one known, trimmed, and nothing when there is none`() {
        assertEquals("Action", screensaverGenre(listOf("Action", "Drama")))
        assertEquals(
            "a padded first entry is the genre, not a blank caption",
            "Action",
            screensaverGenre(listOf("  Action ", "Drama"))
        )
        assertEquals(
            "a blank leading entry is skipped rather than drawn",
            "Drama",
            screensaverGenre(listOf("", " ", "Drama"))
        )
        assertNull("no genres resolved means no caption", screensaverGenre(null))
        assertNull(screensaverGenre(emptyList()))
        assertNull(screensaverGenre(listOf("", " ")))
    }

    @Test
    fun `the dwell is ten seconds, longer than the billboard's default`() {
        assertEquals(
            "the overlay's own cadence, not the billboard's 6 s",
            10_000L,
            SCREENSAVER_DWELL_MS
        )
        assertTrue(
            "the dissolve has to fit inside the dwell",
            SCREENSAVER_CROSSFADE_MS < SCREENSAVER_DWELL_MS
        )
    }

    @Test
    fun `the idle wait is the chosen minutes, clamped to something a TV can wait for`() {
        assertEquals(120_000L, screensaverIdleTimeoutMs(2))
        assertEquals(300_000L, screensaverIdleTimeoutMs(5))
        assertEquals(900_000L, screensaverIdleTimeoutMs(15))
        assertEquals("zero must not mean 'immediately'", 60_000L, screensaverIdleTimeoutMs(0))
        assertEquals("nor a negative", 60_000L, screensaverIdleTimeoutMs(-4))
        assertEquals("nor an hour and a half", 3_600_000L, screensaverIdleTimeoutMs(90))
        assertTrue(
            "the timer must not spin faster than a smoke alarm",
            SCREENSAVER_IDLE_POLL_MS in 250L..5_000L
        )
    }
}
