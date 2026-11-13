package com.kennyb1201.kbstream.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The skip-source chain's rules (spec 2026-11-13).
 *
 * Build 0.9.262 asked two services and, when both missed, showed no button at
 * all. The chain now asks four - IntroDB.app, TheIntroDB, SkipDB, AniSkip - and
 * these are the decisions that make that a chain rather than a first-hit race:
 * which segment kinds keep it running, which source wins a kind, and how an
 * anime title is matched before AniSkip is ever asked.
 */
class IntroDbChainRulesTest {

    private fun stamp(
        type: IntroDbMarkerType,
        start: Long,
        end: Long? = start + 30_000L,
        source: IntroDbSource = IntroDbSource.IntroDbApp
    ): IntroDbStamp = IntroDbStamp(start, end, type, confidence = 0.9, source = source)

    // ── F5: kinds combine ─────────────────────────────────────────────────

    @Test
    fun `a source fills only the kinds the earlier ones left open`() {
        val intro = stamp(IntroDbMarkerType.Intro, 30_000L)
        val credits = stamp(IntroDbMarkerType.Credits, 2_400_000L, source = IntroDbSource.TheIntroDb)
        val laterIntro = stamp(IntroDbMarkerType.Intro, 45_000L, source = IntroDbSource.SkipDb)

        val merged = mergeIntroDbStamps(listOf(intro), listOf(laterIntro, credits))

        assertEquals(
            "the first source's intro wins and the later source's credits fill the gap",
            listOf(intro, credits),
            merged
        )
    }

    @Test
    fun `two sources are never mixed inside one kind`() {
        val intro = stamp(IntroDbMarkerType.Intro, 30_000L)
        val otherIntro = stamp(IntroDbMarkerType.Intro, 31_000L, source = IntroDbSource.SkipDb)

        val merged = mergeIntroDbStamps(listOf(intro), listOf(otherIntro))

        assertEquals("one intro, from the source that answered first", listOf(intro), merged)
        assertTrue("and never both", merged.none { it.source == IntroDbSource.SkipDb })
    }

    @Test
    fun `a source that answers with several kinds keeps all of them`() {
        val intro = stamp(IntroDbMarkerType.Intro, 30_000L)
        val recap = stamp(IntroDbMarkerType.Recap, 0L, 20_000L)
        val preview = stamp(IntroDbMarkerType.Preview, 2_600_000L)

        val merged = mergeIntroDbStamps(emptyList(), listOf(intro, recap, preview))

        assertEquals(listOf(intro, recap, preview), merged)
    }

    @Test
    fun `an empty answer changes nothing`() {
        val stamps = listOf(stamp(IntroDbMarkerType.Intro, 30_000L))
        assertEquals(stamps, mergeIntroDbStamps(stamps, emptyList()))
    }

    // ── F5: what keeps the chain running ──────────────────────────────────

    @Test
    fun `the chain stays open while any core kind is missing`() {
        val introOnly = listOf(stamp(IntroDbMarkerType.Intro, 30_000L))
        assertTrue("an intro is not the whole rail", introDbChainStillOpen(introOnly))
        assertTrue("and nothing at all certainly is not", introDbChainStillOpen(emptyList()))
    }

    @Test
    fun `the chain closes once every core kind is answered`() {
        val complete = INTRO_DB_CORE_KINDS.mapIndexed { index, kind ->
            stamp(kind, index * 60_000L, source = IntroDbSource.TheIntroDb)
        }
        assertFalse(introDbChainStillOpen(complete))
    }

    @Test
    fun `PostCredits never keeps the chain running`() {
        // Rare enough that requiring it would walk every source on every
        // lookup; a source that happens to carry it still contributes it.
        val complete = INTRO_DB_CORE_KINDS.mapIndexed { index, kind ->
            stamp(kind, index * 60_000L)
        }
        assertFalse(INTRO_DB_CORE_KINDS.contains(IntroDbMarkerType.PostCredits))

        val withScene = complete + stamp(IntroDbMarkerType.PostCredits, 3_000_000L)
        assertFalse(introDbChainStillOpen(withScene))

        val withoutScene = complete
        assertFalse("and its absence is not a gap", introDbChainStillOpen(withoutScene))

        // It is kept when a consulted source offers it.
        val scene = stamp(IntroDbMarkerType.PostCredits, 3_000_000L)
        assertTrue(mergeIntroDbStamps(complete, listOf(scene)).contains(scene))
    }

    // ── F2: the anime title gate ──────────────────────────────────────────

    @Test
    fun `titles match across case, punctuation and spacing`() {
        assertTrue(animeTitleMatches("Attack on Titan", "attack on titan", null, emptyList()))
        assertTrue(
            animeTitleMatches(
                "Frieren Beyond Journeys End",
                "Sousou no Frieren",
                "Frieren: Beyond Journey's End",
                emptyList()
            )
        )
        // A subtitle is a different title, not a match: accepting a prefix is
        // exactly how a wrong anime would get picked.
        assertFalse(
            animeTitleMatches(
                "attack on titan",
                "Attack on Titan: Shingeki no Kyojin",
                null,
                emptyList()
            )
        )
    }

    @Test
    fun `a different show does not match, however close the search was`() {
        // Jikan answers a fuzzy search: this is the gate that keeps a
        // live-action show from inheriting an anime's OP/ED times.
        assertFalse(animeTitleMatches("The Office", "Office", null, emptyList()))
        assertFalse(
            animeTitleMatches(
                "Attack on Titan",
                "Attack on Titan: Junior High",
                null,
                emptyList()
            )
        )
        assertFalse(animeTitleMatches("Breaking Bad", "Death Note", null, emptyList()))
    }

    @Test
    fun `every name the result carries is compared`() {
        // An anime's romanized title and its English title are different
        // fields, and KBStream's title can be either.
        assertTrue(
            animeTitleMatches(
                showTitle = "Fullmetal Alchemist Brotherhood",
                title = "Hagane no Renkinjutsushi: Fullmetal Alchemist",
                titleEnglish = null,
                titles = listOf("鋼の錬金術師 FULLMETAL ALCHEMIST", "Fullmetal Alchemist: Brotherhood")
            )
        )
    }

    @Test
    fun `an empty title can never match`() {
        assertFalse(animeTitleMatches(null, "Anything", null, emptyList()))
        assertFalse(animeTitleMatches("   ", "Anything", null, emptyList()))
        assertFalse(animeTitleMatches("Show", null, null, listOf(null, "")))
    }

    // ── F1: SkipDB's query ────────────────────────────────────────────────

    @Test
    fun `SkipDB is asked once, for every type, with conservative shifting`() {
        val url = skipDbSegmentsUrl("tt0903747", 1, 1, durationMs = 3_500_000L)

        assertEquals(
            "https://api.skipdb.tv/api/segments?imdb_id=tt0903747&season=1&episode=1" +
                "&duration=3500&adjust=conservative",
            url
        )
        assertFalse("no per-type query", url.contains("type="))
        assertFalse("and no key: reading is open", url.contains("api_key"))
    }

    @Test
    fun `a movie is asked for without a season or episode`() {
        val url = skipDbSegmentsUrl("tt1160419", null, null, durationMs = 9_660_000L)

        assertTrue(url.contains("imdb_id=tt1160419"))
        assertTrue(url.contains("duration=9660"))
        assertFalse("a movie has no episode to name", url.contains("season="))
        assertFalse(url.contains("episode="))
    }

    @Test
    fun `an unknown duration is omitted rather than sent as zero`() {
        val url = skipDbSegmentsUrl("tt0903747", 1, 1, durationMs = null)
        assertFalse("SkipDB answers `agnostic` instead of shifting", url.contains("duration="))

        val zero = skipDbSegmentsUrl("tt0903747", 1, 1, durationMs = 0L)
        assertFalse("and 0 is not a duration", zero.contains("duration="))
    }

    @Test
    fun `only an IMDb id is a SkipDB id`() {
        assertTrue(isSkipDbImdbId("tt0903747"))
        assertTrue(isSkipDbImdbId(" tt0903747 "))
        assertFalse("a TMDB id has no SkipDB lookup", isSkipDbImdbId("1396"))
        assertFalse(isSkipDbImdbId("tmdb:1396"))
        assertFalse(isSkipDbImdbId("tt"))
        assertFalse(isSkipDbImdbId("ttabc"))
    }

    // ── F2: AniSkip's query ───────────────────────────────────────────────

    @Test
    fun `AniSkip is asked for the three types it can supply`() {
        val url = aniSkipTimesUrl(malId = 16498, episode = 3, durationMs = 1_423_000L)

        assertEquals(
            "https://api.aniskip.com/v2/skip-times/16498/3?" +
                "types[]=op&types[]=ed&types[]=recap&episodeLength=1423",
            url
        )
    }

    @Test
    fun `an unknown length is omitted from AniSkip too`() {
        val url = aniSkipTimesUrl(16498, 3, null)
        assertFalse(url.contains("episodeLength="))
    }

    @Test
    fun `the Jikan search carries the title, encoded`() {
        val url = jikanSearchUrl("Frieren: Beyond Journey's End & More")

        assertTrue(url.startsWith("https://api.jikan.moe/v4/anime?q="))
        assertTrue(url.endsWith("&limit=1"))
        assertFalse("the query value is escaped", url.contains(" "))
        assertFalse(url.contains("Journey's"))
        assertTrue(url.contains("%3A"))
        assertTrue(url.contains("%26"))
    }

    @Test
    fun `encoding leaves ids and intervals alone and escapes everything else`() {
        assertEquals("tt0903747", urlEncode("tt0903747"))
        assertEquals("2026-11-13", urlEncode("2026-11-13"))
        assertEquals("a%20b", urlEncode("a b"))
        assertEquals("%C3%A9", urlEncode("\u00e9"))
    }

    // ── Anime Skip: the fifth leg (spec 2026-11-13) ──────────────────────

    private fun point(at: Double, type: String?) = AnimeSkipPoint(at, type)

    @Test
    fun `the skippable type names are the ones the API actually sends`() {
        val skippable = listOf(
            "Intro", "New Intro", "Mixed Intro",
            "Recap",
            "Credits", "New Credits", "Mixed Credits",
            "Preview"
        )
        skippable.forEach { name ->
            assertTrue("$name must be offered", animeSkipKind(name) != null)
        }
        assertEquals(IntroDbMarkerType.Intro, animeSkipKind("New Intro"))
        assertEquals(IntroDbMarkerType.Intro, animeSkipKind("mixed intro"))
        assertEquals(IntroDbMarkerType.Credits, animeSkipKind("New Credits"))
        assertEquals(IntroDbMarkerType.Recap, animeSkipKind("Recap"))
        assertEquals(IntroDbMarkerType.Preview, animeSkipKind("Preview"))

        listOf("Branding", "Canon", "Filler", "Must Watch", "Title Card", "Transition", "Unknown")
            .forEach { name ->
                assertEquals("$name is content or too short to offer", null, animeSkipKind(name))
            }
        assertEquals("an absent type is not a kind", null, animeSkipKind(null))
    }

    @Test
    fun `a point is bounded by the next point, whatever its type`() {
        // The verified shape of a real episode: intro at 57s, then a title card
        // at 147s. The title card is not offerable, but it is what ENDS the intro.
        val points = listOf(
            point(0.0, "Branding"),
            point(10.0, "Canon"),
            point(57.0, "New Intro"),
            point(147.0, "Title Card")
        )

        val stamps = pairAnimeSkipTimestamps(points, durationMs = null)

        assertEquals("one offerable segment", 1, stamps.size)
        assertEquals(57_000L, stamps[0].startMs)
        assertEquals("the non-skippable point still ends it", 147_000L, stamps[0].endMs)
        assertEquals(IntroDbMarkerType.Intro, stamps[0].type)
        assertEquals(IntroDbSource.AnimeSkip, stamps[0].source)
        assertEquals("no confidence is invented", null, stamps[0].confidence)
    }

    @Test
    fun `a skippable point followed by another skippable one ends at it`() {
        val stamps = pairAnimeSkipTimestamps(
            listOf(point(0.0, "Intro"), point(90.0, "Recap"), point(120.0, "Canon")),
            durationMs = null
        )

        assertEquals(listOf(0L, 90_000L), stamps.map { it.startMs })
        assertEquals(
            "the intro ends where the recap starts, and the recap where content does",
            listOf(90_000L, 120_000L),
            stamps.map { it.endMs }
        )
        assertEquals(IntroDbMarkerType.Intro, stamps[0].type)
        assertEquals(IntroDbMarkerType.Recap, stamps[1].type)
    }

    @Test
    fun `the last point runs to the end of the file`() {
        val stamps = pairAnimeSkipTimestamps(
            listOf(point(1365.0833333, "New Credits"), point(1460.0, "Preview")),
            durationMs = null
        )

        assertEquals(1_365_083L, stamps[0].startMs)
        assertEquals(1_460_000L, stamps[1].startMs)
        assertEquals(
            "the preview is the last point, so its segment is open-ended",
            null,
            stamps[1].endMs
        )
    }

    @Test
    fun `two points at the same second emit no zero-length stamp`() {
        val stamps = pairAnimeSkipTimestamps(
            listOf(point(60.0, "Intro"), point(60.0, "Canon"), point(90.0, "Credits")),
            durationMs = null
        )

        assertEquals("the empty intro is dropped, the credits survive", 1, stamps.size)
        assertEquals(IntroDbMarkerType.Credits, stamps[0].type)
        assertEquals(90_000L, stamps[0].startMs)
    }

    @Test
    fun `a known runtime clamps the end and drops points past it`() {
        val stamps = pairAnimeSkipTimestamps(
            listOf(point(1_400.0, "Credits"), point(1_600.0, "Preview")),
            durationMs = 1_500_000L
        )

        assertEquals("one segment, because the preview starts after the file ends", 1, stamps.size)
        assertEquals(1_400_000L, stamps[0].startMs)
        assertEquals("the end is clamped to the runtime", 1_500_000L, stamps[0].endMs)
    }

    @Test
    fun `an empty or degenerate episode is no stamps, not a crash`() {
        assertEquals(emptyList<IntroDbStamp>(), pairAnimeSkipTimestamps(emptyList(), null))
        assertEquals(
            emptyList<IntroDbStamp>(),
            pairAnimeSkipTimestamps(listOf(point(Double.NaN, "Intro")), null)
        )
        assertEquals(
            "a negative point cannot be acted on",
            emptyList<IntroDbStamp>(),
            pairAnimeSkipTimestamps(listOf(point(-5.0, "Intro")), null)
        )
        assertEquals(
            "and a single unknown type is nothing to offer",
            emptyList<IntroDbStamp>(),
            pairAnimeSkipTimestamps(listOf(point(0.0, "Canon")), null)
        )
    }

    @Test
    fun `the episode match prefers the season, then the absolute number`() {
        val episodes = listOf(
            AnimeSkipEpisode("s1e1", "1", "1", null),
            AnimeSkipEpisode("s3e1", "3", "1", "25"),
            AnimeSkipEpisode("s3e5", "3", "5", "29")
        )

        assertEquals(
            "season three's episode one, not season one's",
            1,
            selectAnimeSkipEpisode(episodes, season = 3, episode = 1)
        )
        assertEquals(
            "with no season to disambiguate, the first match wins",
            0,
            selectAnimeSkipEpisode(episodes, season = null, episode = 1)
        )
        assertEquals(
            "an episode only the absolute number names is still found",
            2,
            selectAnimeSkipEpisode(episodes, season = 3, episode = 29)
        )
        assertEquals(
            "and a numbered string is matched as a number",
            0,
            selectAnimeSkipEpisode(
                listOf(AnimeSkipEpisode("a", "1", "01", null)),
                season = 1,
                episode = 1
            )
        )
        assertEquals(
            "an episode the show does not list is no match",
            null,
            selectAnimeSkipEpisode(episodes, season = 3, episode = 9)
        )
    }

    @Test
    fun `the show lookup is the AniList external-id query, escaped as JSON`() {
        val body = animeSkipShowsByAniListIdQuery(5114)

        assertEquals(
            "{\"query\":\"{ findShowsByExternalId(service: ANILIST, serviceId: \\\"5114\\\") " +
                "{ id name externalLinks { service serviceId } } }\"}",
            body
        )
        assertTrue(
            "externalLinks is asked for, because the match is loose server-side",
            body.contains("externalLinks { service serviceId }")
        )
    }

    @Test
    fun `a title in the search query cannot break the document`() {
        val body = animeSkipShowSearchQuery("Frieren: Beyond Journey's End \"Special\"")

        assertTrue(body.startsWith("{\"query\":\""))
        assertTrue(body.contains("searchShows(search: "))
        assertTrue("the title survives intact", body.contains("Frieren: Beyond Journey's End"))
        assertTrue(
            "the title's quotes are escaped at both layers (GraphQL string, then JSON member), " +
                "so no unescaped pair survives to close the document",
            !body.contains("\"Special\"") && body.contains("Special")
        )
        assertTrue(body.contains("limit: 5"))
        assertFalse("a title is never a URL parameter here", body.contains("&"))
        assertEquals(
            "the body is one JSON member, and its quotes are escaped",
            "{\"query\":\"a \\\"b\\\" c\"}",
            animeSkipQueryBody("a \"b\" c")
        )
    }

    @Test
    fun `the episode and timestamp queries address ids, never titles`() {
        assertEquals(
            "{\"query\":\"{ findEpisodesByShowId(showId: \\\"abc\\\") " +
                "{ id season number absoluteNumber } }\"}",
            animeSkipEpisodesQuery("abc")
        )
        assertEquals(
            "{\"query\":\"{ findTimestampsByEpisodeId(episodeId: \\\"abc\\\") " +
                "{ at type { name } } }\"}",
            animeSkipTimestampsQuery("abc")
        )
    }

    @Test
    fun `the endpoint is the one the web client pins`() {
        assertEquals("https://api.anime-skip.com/graphql", ANIME_SKIP_GRAPHQL_URL)
    }
}
