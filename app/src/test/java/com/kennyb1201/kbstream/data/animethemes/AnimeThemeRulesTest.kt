package com.kennyb1201.kbstream.data.animethemes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Themes tab's rules (spec 2026-11-13).
 *
 * Anime has a community archive of its openings and endings - the creditless
 * video, the song and the artist - and the Detail Media row offers it as a fourth
 * page. These are the decisions a JVM test can hold: what a card says, the order
 * the cards are drawn in, which version a theme plays, and which of its video
 * files that version plays.
 */
class AnimeThemeRulesTest {

    private fun video(
        link: String = "https://cdn.example/op.webm",
        resolution: Int? = 720,
        source: String? = "BD",
        nc: Boolean = true
    ) = AnimeThemeVideo(link = link, resolution = resolution, source = source, nc = nc)

    private fun theme(
        slug: String = "op1",
        type: String = "OP",
        sequence: Int = 1,
        songTitle: String? = "Kimi no Kioku",
        artists: List<String> = listOf("Yui"),
        version: Int? = 1,
        episodes: String? = "1-13",
        videoUrl: String? = "https://cdn.example/op.webm"
    ) = AnimeTheme(
        slug = slug,
        type = type,
        sequence = sequence,
        songTitle = songTitle,
        artists = artists,
        version = version,
        episodes = episodes,
        videoUrl = videoUrl
    )

    // ── The card ─────────────────────────────────────────────────────────

    @Test
    fun `the badge is the type and sequence, with the version when it is above the first`() {
        assertEquals("OP1", AnimeThemeRules.badge("OP", 1, version = null))
        assertEquals("OP2", AnimeThemeRules.badge("OP", 2, version = 1))
        assertEquals("ED1v2", AnimeThemeRules.badge("ed", 1, version = 2))
        assertEquals("ED1v3", AnimeThemeRules.badge("ED", 1, version = 3))
    }

    @Test
    fun `a theme with no song title still draws`() {
        assertEquals("Theme", AnimeThemeRules.title(null))
        assertEquals("Theme", AnimeThemeRules.title("   "))
        assertEquals("Kimi no Kioku", AnimeThemeRules.title(" Kimi no Kioku "))
    }

    @Test
    fun `the second line is the artist and the episode range`() {
        assertEquals(
            "Artist \u00b7 Eps 1\u201313",
            AnimeThemeRules.subtitle(listOf("Artist"), "1-13")
        )
        assertEquals("Artist", AnimeThemeRules.subtitle(listOf("Artist"), null))
        assertEquals("Eps 1\u201313", AnimeThemeRules.subtitle(emptyList(), "1-13"))
        assertNull(AnimeThemeRules.subtitle(emptyList(), null))
        assertNull(AnimeThemeRules.subtitle(listOf("  "), "  "))
    }

    @Test
    fun `several artists are one line, and a range that is not a range is left alone`() {
        assertEquals(
            "Yui, Aimer \u00b7 Eps 1\u201313",
            AnimeThemeRules.subtitle(listOf("Yui", " Aimer "), "1-13")
        )
        assertEquals(
            "Eps 1,3,5",
            AnimeThemeRules.subtitle(emptyList(), "1,3,5")
        )
    }

    // ── The order ────────────────────────────────────────────────────────

    @Test
    fun `OPs come first by sequence, then EDs by sequence`() {
        val ed1 = theme(slug = "ed1", type = "ED", sequence = 1)
        val op2 = theme(slug = "op2", type = "OP", sequence = 2)
        val ed2 = theme(slug = "ed2", type = "ED", sequence = 2)
        val op1 = theme(slug = "op1", type = "OP", sequence = 1)

        assertEquals(
            listOf("op1", "op2", "ed1", "ed2"),
            AnimeThemeRules.ordered(listOf(ed2, op2, ed1, op1)).map { it.slug }
        )
    }

    // ── The version ──────────────────────────────────────────────────────

    @Test
    fun `the newest version is the one the theme plays`() {
        val entries = listOf(
            AnimeThemeEntry(version = 1, episodes = "1-12", videos = listOf(video("v1"))),
            AnimeThemeEntry(version = 3, episodes = "13", videos = listOf(video("v3"))),
            AnimeThemeEntry(version = 2, episodes = "1-12", videos = listOf(video("v2")))
        )

        assertEquals(3, AnimeThemeRules.bestVersion(entries)?.version)
        assertEquals("v3", AnimeThemeRules.bestVersion(entries)?.videos?.first()?.link)
    }

    @Test
    fun `an entry with no version counts as the first one`() {
        val entries = listOf(
            AnimeThemeEntry(version = null, episodes = "1-12", videos = listOf(video("a"))),
            AnimeThemeEntry(version = 2, episodes = "13", videos = listOf(video("b")))
        )
        assertEquals(2, AnimeThemeRules.bestVersion(entries)?.version)
    }

    // ── The video ────────────────────────────────────────────────────────

    @Test
    fun `creditless wins over everything`() {
        val chosen = AnimeThemeRules.bestVideo(
            listOf(
                video(link = "credited-4k", resolution = 2160, source = "BD", nc = false),
                video(link = "creditless-720", resolution = 720, source = "WEB", nc = true)
            )
        )
        assertEquals("creditless-720", chosen?.link)
    }

    @Test
    fun `resolution is capped at 1080, and a higher one buys nothing`() {
        val chosen = AnimeThemeRules.bestVideo(
            listOf(
                video(link = "2160", resolution = 2160),
                video(link = "1080", resolution = 1080),
                video(link = "720", resolution = 720)
            )
        )
        assertEquals("1080", chosen?.link)
    }

    @Test
    fun `between equal resolutions the better source wins - BD, then WEB, then DVD`() {
        val chosen = AnimeThemeRules.bestVideo(
            listOf(
                video(link = "dvd", source = "DVD"),
                video(link = "web", source = "WEB"),
                video(link = "raw", source = "RAW"),
                video(link = "bd", source = "BD")
            )
        )
        assertEquals("bd", chosen?.link)

        assertEquals(
            "web",
            AnimeThemeRules.bestVideo(
                listOf(video(link = "dvd", source = "DVD"), video(link = "web", source = "WEB"))
            )?.link
        )
    }

    @Test
    fun `a file with no link is never chosen, and no files means no video`() {
        assertNull(AnimeThemeRules.bestVideo(emptyList()))
        assertNull(AnimeThemeRules.bestVideo(listOf(video(link = "  "))))
        assertEquals("ok", AnimeThemeRules.bestVideo(listOf(video(link = "  "), video(link = "ok")))?.link)
    }

    // ── The whole tab ────────────────────────────────────────────────────

    @Test
    fun `one card per theme, not one per version`() {
        val themes = AnimeThemeRules.themesOf(
            listOf(
                AnimeThemeRules.RawTheme(
                    slug = "ed1",
                    type = "ED",
                    sequence = 1,
                    songTitle = "Kimi no Kioku",
                    artists = listOf("Yui"),
                    entries = listOf(
                        AnimeThemeEntry(version = 1, episodes = "1-12", videos = listOf(video("ed-v1"))),
                        AnimeThemeEntry(version = 2, episodes = "13", videos = listOf(video("ed-v2")))
                    )
                ),
                AnimeThemeRules.RawTheme(
                    slug = "op1",
                    type = "OP",
                    sequence = 1,
                    songTitle = "Again",
                    artists = listOf("Yui"),
                    entries = listOf(
                        AnimeThemeEntry(version = 1, episodes = "1-14", videos = listOf(video("op-v1")))
                    )
                )
            )
        )

        assertEquals(listOf("op1", "ed1"), themes.map { it.slug })
        assertEquals("op-v1", themes[0].videoUrl)
        assertEquals("ed-v2", themes[1].videoUrl)
        assertEquals(2, themes[1].version)
        assertEquals("13", themes[1].episodes)
    }

    @Test
    fun `a theme with no playable video is still a theme`() {
        val themes = AnimeThemeRules.themesOf(
            listOf(
                AnimeThemeRules.RawTheme(
                    slug = "op1",
                    type = "OP",
                    sequence = 1,
                    songTitle = null,
                    artists = emptyList(),
                    entries = emptyList()
                )
            )
        )

        assertEquals(1, themes.size)
        assertNull("the card is drawn unplayable rather than dropped", themes[0].videoUrl)
        assertEquals("Theme", AnimeThemeRules.title(themes[0].songTitle))
    }

    // ── The scope gate ───────────────────────────────────────────────────

    @Test
    fun `only a title TMDB calls animation is looked up`() {
        assertTrue(AnimeThemeRules.isAnime(listOf(16)))
        assertTrue(AnimeThemeRules.isAnime(listOf(18, 16, 10765)))
        assertFalse(AnimeThemeRules.isAnime(listOf(18, 80)))
        assertFalse("an unknown genre list is not animation", AnimeThemeRules.isAnime(emptyList()))
    }
}
