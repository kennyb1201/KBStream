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
    fun `a candidate needs its own clearlogo - its picture comes from the resolved art`() {
        assertTrue("a title with a logo is a candidate", item("a").hasScreensaverArt())
        assertFalse("a poster alone is not", item("b", logo = null).hasScreensaverArt())
        assertTrue(
            "a rail item with no backdrop of its own is still a candidate: the " +
                "overlay draws the resolved clean backdrop, not this one",
            item("c", background = null).hasScreensaverArt()
        )
        assertTrue(
            "and a blank one is the same as none",
            item("d", background = "  ").hasScreensaverArt()
        )
        assertFalse(
            "but a blank logo is not a logo",
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
                item("blank-logo", logo = " "),
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
    fun `an add-on that ships no clearlogo never arms an empty show`() {
        assertEquals(
            emptyList<MetaPreview>(),
            screensaverSlides(listOf(item("a", logo = null), item("b", logo = null)))
        )
        assertEquals(emptyList<MetaPreview>(), screensaverSlides(emptyList()))
    }

    @Test
    fun `a slide needs a resolved clean backdrop, in rail order, with its logo kept`() {
        val pool = listOf(item("one"), item("art-miss"), item("three"))
        val clean = mapOf(
            "one" to "https://img/clean-one.jpg",
            "three" to "https://img/clean-three.jpg"
        )

        val slides = screensaverCleanSlides(pool) { clean[it.id] }

        assertEquals(
            "a title whose art did not resolve is dropped, and the order is kept",
            listOf("one", "three"),
            slides.map { it.id }
        )
        assertEquals(
            "the shown backdrop is the resolved one, never the rail's own",
            listOf("https://img/clean-one.jpg", "https://img/clean-three.jpg"),
            slides.map { it.background }
        )
        assertEquals(
            "the clearlogo stays the item's own",
            listOf("https://img/logo.png", "https://img/logo.png"),
            slides.map { it.logo }
        )
        assertEquals(
            "and nothing else about the item is rewritten",
            "Title one",
            slides.first().name
        )
    }

    @Test
    fun `nothing resolved means no show at all, never the raw art`() {
        assertEquals(
            "no clean backdrop means no slide - a raw fallback would put the promo " +
                "art back on screen",
            emptyList<MetaPreview>(),
            screensaverCleanSlides(listOf(item("a"), item("b"))) { null }
        )
        assertEquals(
            "and a blank resolve is the same as none",
            emptyList<MetaPreview>(),
            screensaverCleanSlides(listOf(item("a"))) { "  " }
        )
    }

    @Test
    fun `the idle wait resolves at most the first thirty, in slide order`() {
        val pool = (1..45).map { item("t$it") }
        assertEquals(30, SCREENSAVER_PRERESOLVE_LIMIT)
        assertEquals(
            "a prefix, not a sample: the tour starts at the top of Home",
            (1..30).map { "t$it" },
            screensaverPreresolveItems(pool).map { it.id }
        )
        assertEquals(
            "a short pool is walked whole",
            3,
            screensaverPreresolveItems(pool.take(3)).size
        )
        assertEquals(emptyList<MetaPreview>(), screensaverPreresolveItems(emptyList()))
    }

    @Test
    fun `the genre line is the first two known, joined, and nothing when there is none`() {
        assertEquals(
            "the corner reads as a genre tag, not as the title's full classification",
            "Action · Drama",
            screensaverGenre(listOf("Action", "Drama"))
        )
        assertEquals(
            "two, not the whole list: a third genre is a paragraph under a wordmark",
            "Action · Drama",
            screensaverGenre(listOf("Action", "Drama", "Sci-Fi"))
        )
        assertEquals(
            "a padded entry is the genre, not a blank half of the line",
            "Action · Drama",
            screensaverGenre(listOf("  Action ", "Drama "))
        )
        assertEquals(
            "one genre still shows one, with no dangling separator",
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

    /**
     * Field (TV, 2026-11-05): "the screensaver opens and interrupts trailers
     * playing". The gate knew about menus and the top bar and nothing about the
     * trailer; the idle clock only knew about key presses, and a trailer watched
     * without touching the remote produced none. So a trailer longer than the
     * timeout was covered by the overlay - which then stopped it through the
     * suppression path that existed for exactly that cover. The interruption was
     * the ARMING.
     */
    @Test
    fun `only a mounted trailer that has drawn a frame is playing`() {
        assertTrue(
            "a trailer on screen is the case the gate exists for",
            heroTrailerPlaying(mounted = true, frameRendered = true)
        )
        assertFalse(
            "mounted but unpainted is a black rectangle with the backdrop still over it",
            heroTrailerPlaying(mounted = true, frameRendered = false)
        )
        assertFalse(
            "no source is a pre-warm, a resolve in flight, a hold, or the ended " +
                "backdrop - none of which may hold Home awake (settler 4)",
            heroTrailerPlaying(mounted = false, frameRendered = false)
        )
    }

    @Test
    fun `the wait restarts from the moment playback ends, and only then`() {
        val pressed = 1_000L
        val ended = 400_000L
        assertEquals(
            "a long trailer's last key press is stale: the wait starts at its end, " +
                "not at the press before it, or the overlay arms the instant the " +
                "picture returns to its backdrop",
            ended,
            screensaverIdleClockAfterTrailer(
                wasPlaying = true,
                playing = false,
                nowMs = ended,
                lastKeyAtMs = pressed
            )
        )
        assertEquals(
            "the rising edge must not reset: starting a trailer is not a press",
            pressed,
            screensaverIdleClockAfterTrailer(
                wasPlaying = false,
                playing = true,
                nowMs = ended,
                lastKeyAtMs = pressed
            )
        )
        assertEquals(
            "and a report that changes nothing must not drift the deadline either",
            pressed,
            screensaverIdleClockAfterTrailer(
                wasPlaying = true,
                playing = true,
                nowMs = ended,
                lastKeyAtMs = pressed
            )
        )
        assertEquals(
            "a still-idle Home keeps the clock it had",
            pressed,
            screensaverIdleClockAfterTrailer(
                wasPlaying = false,
                playing = false,
                nowMs = ended,
                lastKeyAtMs = pressed
            )
        )
    }
}
