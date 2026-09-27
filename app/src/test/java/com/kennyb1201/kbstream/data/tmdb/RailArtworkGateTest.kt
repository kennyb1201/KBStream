package com.kennyb1201.kbstream.data.tmdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rail artwork gate ([hasSomethingToDraw]) decides which discover rows the
 * browse rails are allowed to draw.
 *
 * It exists because the gate used to ask a single question - "does TMDB have a
 * poster for it?" - and TMDB often does not, for a title that is real and
 * already airing. Every id below is a live discover row (checked 2026-09):
 * Game Show Network's 2025 `Bingo Blitz` and `Tic Tac Dough` revivals and The
 * CW's 2026 `The Great American Road Rally: Celebrity Edition` all carry no
 * poster, and dropping them moved those rails' heads back to 2024 and 2025
 * respectively - the recency a RECENT rail exists to show. The opposite
 * failure matters too: CuriosityStream's `Flow - the force of weather` is a
 * posterless row with no synopsis and no votes, i.e. a stub, and keeping it
 * would put a title-only card on the rail.
 */
class RailArtworkGateTest {

    private fun row(
        name: String,
        poster: String? = null,
        overview: String? = null,
        votes: Int? = null
    ) = StudioItem(
        TmdbDiscoverItem(
            id = name.hashCode(),
            name = name,
            posterPath = poster,
            overview = overview,
            voteCount = votes
        ),
        "series"
    )

    // ── kept: real entries ────────────────────────────────────────────

    @Test
    fun `an entry with artwork is kept`() {
        assertTrue(hasSomethingToDraw(row("Beat the Bridge", poster = "/abc.jpg")))
    }

    @Test
    fun `a posterless entry with a synopsis is kept`() {
        // Game Show Network (63): the newest series TMDB has for the network,
        // 2025-04-14, and it has never carried a poster.
        assertTrue(
            hasSomethingToDraw(
                row("Bingo Blitz", overview = "Two contestants compete in a dynamic blend of trivia and bingo.")
            )
        )
        assertTrue(
            hasSomethingToDraw(
                row("Tic Tac Dough", overview = "A new version (2025) of the classic TV game show")
            )
        )
        // The CW (71) / The Roku Channel (207): 2026-09-16, no poster.
        assertTrue(
            hasSomethingToDraw(
                row(
                    "The Great American Road Rally: Celebrity Edition",
                    overview = "Ten celebrity-driven vehicles, each aligned with a charitable cause."
                )
            )
        )
    }

    @Test
    fun `a posterless entry with an audience is kept`() {
        // Synopsis-less but real: the votes are the evidence.
        assertTrue(hasSomethingToDraw(row("Some Show", votes = 1)))
    }

    @Test
    fun `a blank poster string is treated as no poster`() {
        assertTrue(hasSomethingToDraw(row("Search Party with Brandon Jordan", poster = "   ", overview = "Real.")))
        assertFalse(hasSomethingToDraw(row("Search Party with Brandon Jordan", poster = "")))
    }

    // ── dropped: placeholders ─────────────────────────────────────────

    @Test
    fun `a title-only placeholder is dropped`() {
        assertFalse(hasSomethingToDraw(row("Announced Announcement")))
    }

    @Test
    fun `a posterless, synopsis-less, voteless stub is dropped`() {
        // CuriosityStream (190): 2025-10-30, empty overview, zero votes.
        assertFalse(hasSomethingToDraw(row("Flow - the force of weather", overview = "", votes = 0)))
        assertFalse(hasSomethingToDraw(row("Flow - the force of weather", overview = "   ", votes = 0)))
    }

    // ── the regression itself ─────────────────────────────────────────

    @Test
    fun `the newest real show still leads the rail when TMDB has no poster for it`() {
        val rail = listOf(
            row("Bingo Blitz", overview = "2025 revival", votes = 0), // 2025
            row("Tic Tac Dough", overview = "2025 revival", votes = 0), // 2025
            row("Beat the Bridge", poster = "/a.jpg"), // 2024
            row("Blank Slate", poster = "/b.jpg"), // 2024
            row(""), // a stub with no title at all
            row("Flow - the force of weather", overview = "", votes = 0) // a stub
        )
        val kept = rail.filter { hasSomethingToDraw(it) }
        assertEquals(4, kept.size)
        assertEquals("Bingo Blitz", kept.first().item.name)
    }
}
