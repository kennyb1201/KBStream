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
}
