package com.kennyb1201.kbstream.data.art

import com.kennyb1201.kbstream.data.reporting.SeasonPosterTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The season poster's write rule (spec 2026-11-14, F2).
 *
 * The field bug was an ERASURE: a re-lookup that came back empty was written over
 * a good poster, and nothing re-triggered the effect, so it stayed gone. These
 * tests pin the three outcomes apart - replace on an answer, keep through a
 * failure, clear only on an answered none - on the rule both screens call.
 */
class SeasonPosterMemoryTest {

    private fun answer(
        url: String? = null,
        fanart: ArtSourceAnswer = ArtSourceAnswer.NONE,
        tmdb: ArtSourceAnswer = ArtSourceAnswer.NONE,
        tvdbId: Int? = 12
    ) = SeasonPosterAnswer(url = url, tvdbId = tvdbId, fanart = fanart, tmdb = tmdb)

    private fun decide(
        current: String? = "old.jpg",
        answer: SeasonPosterAnswer?,
        forSeries: Boolean = true,
        season: Int? = 2,
        tvdbProvenAbsent: Boolean = false
    ) = SeasonPosterMemory.decide(current, answer, forSeries, season, tvdbProvenAbsent)

    @Test
    fun `an answered URL replaces the poster`() {
        assertEquals(
            SeasonPosterMemory.Decision.REPLACE,
            decide(answer = answer(url = "https://image.tmdb.org/new.jpg", fanart = ArtSourceAnswer.FOUND))
        )
        assertEquals(
            SeasonPosterMemory.Decision.REPLACE,
            decide(current = null, answer = answer(url = "https://image.tmdb.org/new.jpg", tmdb = ArtSourceAnswer.FOUND))
        )
    }

    @Test
    fun `a lookup that did not answer keeps the poster it had`() {
        // The field case: the TVDB resolve failed, so fanart was never asked and
        // TMDB had nothing. Nothing was PROVEN absent, so the poster stays.
        assertEquals(
            SeasonPosterMemory.Decision.KEEP,
            decide(answer = answer(fanart = ArtSourceAnswer.UNANSWERED, tmdb = ArtSourceAnswer.UNANSWERED, tvdbId = null))
        )
        assertEquals(
            SeasonPosterMemory.Decision.KEEP,
            decide(answer = answer(fanart = ArtSourceAnswer.UNANSWERED, tmdb = ArtSourceAnswer.NONE, tvdbId = null))
        )
        assertEquals(
            "a season with no answer at all is not a reason to erase",
            SeasonPosterMemory.Decision.KEEP,
            decide(answer = null)
        )
    }

    @Test
    fun `a transient null season or page keeps the poster`() {
        // A seasons re-emission mid-scroll runs the effect with a null season;
        // that is the moment the old code erased the poster.
        assertEquals(SeasonPosterMemory.Decision.KEEP, decide(answer = null, season = null))
        assertEquals(SeasonPosterMemory.Decision.KEEP, decide(answer = null, forSeries = false))
    }

    @Test
    fun `both sources answering none clears it`() {
        assertEquals(
            SeasonPosterMemory.Decision.CLEAR,
            decide(answer = answer(fanart = ArtSourceAnswer.NONE, tmdb = ArtSourceAnswer.NONE))
        )
    }

    @Test
    fun `one unanswered source is enough to keep it`() {
        assertEquals(
            "fanart answered none but TMDB never answered",
            SeasonPosterMemory.Decision.KEEP,
            decide(answer = answer(fanart = ArtSourceAnswer.NONE, tmdb = ArtSourceAnswer.UNANSWERED))
        )
    }

    @Test
    fun `a missing TVDB id clears only when TMDB proved there is no mapping`() {
        assertEquals(
            "the resolve failed: fanart was never asked, so nothing is proven",
            SeasonPosterMemory.Decision.KEEP,
            decide(
                answer = answer(fanart = ArtSourceAnswer.NO_KEY, tmdb = ArtSourceAnswer.NONE, tvdbId = null),
                tvdbProvenAbsent = false
            )
        )
        assertEquals(
            "TMDB proved no TVDB mapping, and it has no season poster either",
            SeasonPosterMemory.Decision.CLEAR,
            decide(
                answer = answer(fanart = ArtSourceAnswer.NO_KEY, tmdb = ArtSourceAnswer.NONE, tvdbId = null),
                tvdbProvenAbsent = true
            )
        )
    }

    @Test
    fun `the decision reads as one word for the paste`() {
        assertEquals("set", SeasonPosterMemory.describe(SeasonPosterMemory.Decision.REPLACE))
        assertEquals("cleared", SeasonPosterMemory.describe(SeasonPosterMemory.Decision.CLEAR))
        assertEquals("kept", SeasonPosterMemory.describe(SeasonPosterMemory.Decision.KEEP))
    }

    // ── The trace line ─────────────────────────────────────────────────────

    @Test
    fun `the run line names the trigger, both sources and the verdict`() {
        val line = SeasonPosterTrace.runLine(
            trigger = "season",
            season = 3,
            answer = answer(url = "https://image.tmdb.org/secret-path.jpg", fanart = ArtSourceAnswer.UNANSWERED, tmdb = ArtSourceAnswer.FOUND),
            decision = SeasonPosterMemory.Decision.REPLACE
        )
        assertTrue(line, line.contains("run (season, season=3)"))
        assertTrue(line, line.contains("tvdb=set"))
        assertTrue(line, line.contains("fanart=unanswered"))
        assertTrue(line, line.contains("tmdb=found"))
        assertTrue(line, line.contains("-> set"))
    }

    @Test
    fun `the run line never carries a URL`() {
        val line = SeasonPosterTrace.runLine(
            trigger = "open",
            season = 1,
            answer = answer(url = "https://image.tmdb.org/p/abc.jpg", fanart = ArtSourceAnswer.FOUND),
            decision = SeasonPosterMemory.Decision.REPLACE
        )
        assertFalse(line, line.contains("http"))
        assertFalse(line, line.contains("abc.jpg"))
    }

    @Test
    fun `a run with no lookup says so, and an unanswered id reads none`() {
        assertTrue(
            SeasonPosterTrace.runLine("show", null, null, SeasonPosterMemory.Decision.KEEP)
                .contains("nothing looked up")
        )
        assertTrue(
            SeasonPosterTrace.runLine(
                "open",
                2,
                answer(fanart = ArtSourceAnswer.NO_KEY, tmdb = ArtSourceAnswer.NONE, tvdbId = null),
                SeasonPosterMemory.Decision.KEEP
            ).contains("tvdb=none")
        )
    }

    @Test
    fun `the trace keeps a bounded ring`() {
        SeasonPosterTrace.clear()
        repeat(25) { i ->
            SeasonPosterTrace.record(SeasonPosterTrace.imageLine("tv", "network"))
        }
        val lines = SeasonPosterTrace.lines()
        assertEquals(10, lines.size)
        assertTrue(lines.first().contains("season poster image (tv): network"))
    }
}
