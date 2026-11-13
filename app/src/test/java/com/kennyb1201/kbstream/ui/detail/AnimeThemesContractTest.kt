package com.kennyb1201.kbstream.ui.detail

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AnimeThemes on the Detail Media row (spec 2026-11-13).
 *
 * The tab's rules are unit-tested in
 * [com.kennyb1201.kbstream.data.animethemes.AnimeThemeRulesTest]; this covers the
 * wiring a JVM test cannot run: which endpoint is asked with which headers, how
 * a GraphQL answer becomes themes, how the ruling gate and the shared MAL
 * resolution are reached, and how a theme's press becomes a one-off player
 * session with no title identity behind it.
 *
 * Read-only: no key, no account, no writes anywhere.
 */
class AnimeThemesContractTest {

    private val client by lazy { source(CLIENT) }
    private val rules by lazy { source(RULES) }
    private val viewModel by lazy { source(VIEW_MODEL) }
    private val mediaRow by lazy { source(MEDIA_ROW) }
    private val launch by lazy { source(LAUNCH) }
    private val tv by lazy { source(TV) }
    private val phone by lazy { source(PHONE) }

    // ── The source ───────────────────────────────────────────────────────

    @Test
    fun `AnimeThemes is asked over GraphQL, and never over the retired REST surface`() {
        assertTrue(
            "the documented GraphQL endpoint",
            rules.contains("""internal const val ANIME_THEMES_GRAPHQL_URL = "https://rs.animethemes.moe/graphql"""")
        )
        assertTrue(
            "the request is a POST - a GraphQL query is a body, not a path",
            client.contains(".post(requestBody(malId))")
        )
        assertTrue(
            "with the JSON content type and the app's own User-Agent",
            client.contains(""""application/json".toMediaType()""") &&
                client.contains("""const val USER_AGENT = "KBStream/" + BuildConfig.VERSION_NAME""") &&
                client.contains(""".header("User-Agent", USER_AGENT)""")
        )
        assertFalse(
            // The prose may name the retired host to say why it is not used; a
            // URL must not.
            "the JSON:API is deprecated and will be removed: no call to it",
            client.contains("https://api.animethemes.moe") ||
                rules.contains("https://api.animethemes.moe")
        )
        assertFalse(
            "and no key or account anywhere: reading is open",
            client.contains("api_key") || client.contains("apiKey") || client.contains("Authorization")
        )
    }

    @Test
    fun `one query asks for every field the tab draws, by MAL id`() {
        val query = client.substringAfter("private const val QUERY =").substringBefore("internal fun readThemes(")

        assertTrue(
            "the documented external-site lookup, with MAL as the site",
            query.contains("filterAnimeByExternalSite(site: MAL, id: \\\$id)")
        )
        listOf(
            "type sequence slug",
            "song { title artists { name } }",
            "entries { version episodes videos { nodes { link resolution source nc } } }"
        ).forEach { field ->
            assertTrue("asks for $field", query.contains(field))
        }
        assertTrue(
            "and passes the id as GraphQL variables, never as string-spliced SQL-like text",
            client.contains("""put("variables", JSONObject().put("id", JSONArray().put(malId)))""")
        )
    }

    @Test
    fun `the answer is matched by title before any of it is used`() {
        val reader = client.substringAfter("internal fun readThemes(")
            .substringBefore("private fun readTheme(")

        assertTrue(
            "an errors body is no themes, not a failure the screen has to handle",
            reader.contains("""if (root.optJSONArray("errors")?.length() ?: 0 > 0) return emptyList()""")
        )
        assertTrue(
            "a resource can link to several anime, so the first whose title matches wins",
            reader.contains("animeTitleMatches(showTitle, title, null, emptyList())")
        )
        assertTrue(
            "through the SAME normalized matcher the Jikan resolution uses, not a second rule",
            rules.length > 0 && client.contains("import com.kennyb1201.kbstream.ui.player.animeTitleMatches")
        )
        assertTrue(
            "and the tab's order/version/video choices are the rules object's",
            reader.contains("AnimeThemeRules.themesOf(it)")
        )
    }

    @Test
    fun `an unreadable answer is an absent tab, never an exception`() {
        val fetcher = client.substringAfter("suspend fun fetchThemes(")
            .substringBefore("fun request(")
        assertTrue(
            "the call itself is wrapped, and a non-2xx body is null",
            fetcher.contains("runCatching {") &&
                fetcher.contains("if (!response.isSuccessful) null else JSONObject(")
        )
        assertTrue(
            "and null reads as no themes",
            fetcher.contains("?: return@withContext emptyList()")
        )
    }

    // ── The tab ──────────────────────────────────────────────────────────

    @Test
    fun `Themes is the fourth page, last, and only when it has something on it`() {
        assertTrue(
            "the tab exists and is declared after POSTERS",
            mediaRow.indexOf("POSTERS(\"Posters\")") in 0 until
                mediaRow.indexOf("THEMES(\"Themes\")")
        )
        val tabs = mediaRow.substringAfter("fun tabs(")
            .substringBefore("fun switched(")
        assertTrue(
            "it is offered on a count, exactly like the other three",
            tabs.contains("if (themeCount > 0) add(MediaTab.THEMES)") &&
                tabs.contains("themeCount: Int = 0")
        )
        assertTrue(
            "and it is the last thing added, so the strip reads Trailers/Backdrops/Posters/Themes",
            tabs.indexOf("if (posterCount > 0) add(MediaTab.POSTERS)") <
                tabs.indexOf("if (themeCount > 0) add(MediaTab.THEMES)")
        )
    }

    @Test
    fun `a theme card is a video with no video, so it says what it is`() {
        val projection = mediaRow.substringAfter("fun themes(themes: List<AnimeTheme>, backdropUrl: String?)")
            .substringBefore("/**\n     * The YouTube still for a video")

        assertTrue(
            "keyed on the theme's own slug",
            projection.contains("id = \"theme:\" + theme.slug")
        )
        assertTrue(
            "badged OP/ED + sequence, titled by the song, lined with artist and range",
            projection.contains("badge = AnimeThemeRules.badge(theme.type, theme.sequence, theme.version)") &&
                projection.contains("title = AnimeThemeRules.title(theme.songTitle)") &&
                projection.contains("subtitle = AnimeThemeRules.subtitle(theme.artists, theme.episodes)")
        )
        assertTrue(
            "its picture is the title's backdrop: AnimeThemes serves no still for a theme",
            projection.contains("imageUrl = backdropUrl")
        )
        assertTrue(
            "it carries the URL it plays and the marker that makes its press a theme's",
            projection.contains("themeVideoUrl = theme.videoUrl") &&
                projection.contains("isThemeCard = true")
        )
        assertTrue(
            "and a theme with no chosen video is unplayable rather than dropped",
            projection.contains("playable = !theme.videoUrl.isNullOrBlank()")
        )
        assertTrue(
            "the long-press menu offers nothing there, as on Trailers",
            mediaRow.contains("MediaTab.TRAILERS, MediaTab.THEMES, null -> emptyList()")
        )
    }

    // ── Identity and the gate ────────────────────────────────────────────

    @Test
    fun `the lookup is gated on TMDB's Animation genre and rides the detail load`() {
        val load = viewModel.substringAfter("fun loadAnimeThemes()")
            .substringBefore("\n    /**")

        assertTrue(
            "nothing is fetched until the Detail payload has landed",
            load.contains("val detail = _tmdbDetail.value ?: return")
        )
        assertTrue(
            "the Animation genre is the whole scope gate",
            load.contains("if (!AnimeThemeRules.isAnime(detail.genres.map { genre -> genre.id })) {")
        )
        assertTrue(
            "one lookup per Detail open, never one per recomposition or tab switch",
            load.contains("if (animeThemesRequested) return") &&
                load.contains("animeThemesRequested = true")
        )
        assertTrue(
            "off the main thread, past the first paint",
            load.contains("viewModelScope.launch {") &&
                load.contains("withContext(Dispatchers.IO) {")
        )
        assertTrue(
            "and a missing MAL id or an empty archive leaves the page off",
            load.contains("val malId = resolveMalId(title) ?: return@withContext") &&
                load.contains("if (themes.isNotEmpty()) _animeThemes.value = themes")
        )
    }

    @Test
    fun `the MAL id is the one the intro-skip chain already resolves`() {
        assertTrue(
            "one resolver, one cache - not a second Jikan call for the same title",
            viewModel.contains("import com.kennyb1201.kbstream.ui.player.resolveMalId") &&
                load2(viewModel).contains("resolveMalId(title)")
        )
        assertFalse(
            "and no second Jikan client is written here",
            viewModel.contains("api.jikan.moe") || viewModel.contains("jikanSearchUrl")
        )
    }

    @Test
    fun `both form factors grow the same page from the same counts`() {
        listOf(TV to tv, PHONE to phone).forEach { (path, screen) ->
            assertTrue(
                "$path collects the themes",
                screen.contains("val animeThemes by viewModel.animeThemes.collectAsStateWithLifecycle()")
            )
            assertTrue(
                "$path asks for them when the detail lands",
                screen.contains("LaunchedEffect(tmdbDetail?.id) { viewModel.loadAnimeThemes() }")
            )
            assertTrue(
                "$path passes the count to the same tab rule",
                screen.contains("animeThemes.size")
            )
            assertTrue(
                "$path projects the page through the rule object",
                screen.contains("MediaTab.THEMES -> DetailMediaRow.themes(animeThemes, mediaThemeArt)")
            )
            assertTrue(
                "$path draws the theme's caption, because every card has the same backdrop",
                screen.contains("isThemeCard")
            )
        }
    }

    // ── Playing a theme ──────────────────────────────────────────────────

    @Test
    fun `a theme plays as a one-off video with no title identity behind it`() {
        val playUrl = launch.substringAfter("fun playUrl(")
            .substringBefore("fun play(")

        assertTrue(
            "the URL is the file - nothing is resolved",
            playUrl.contains("putExtra(\"stream_url\", link)") &&
                !playUrl.contains("resolvePlayableUrl")
        )
        assertTrue(
            "and the title is only the player's label",
            playUrl.contains("putExtra(\"item_name\"")
        )
        assertFalse(
            "NO parent id: every history/resume/Continue Watching write returns early on " +
                "a blank parent, which is what makes this a viewing and not a session",
            playUrl.contains("parent_id") || playUrl.contains("parent_type")
        )
        assertFalse(
            "and no trailer identity: this is not a googlevideo session to re-sign",
            playUrl.contains("EXTRA_TRAILER_VIDEO_ID")
        )
        assertTrue(
            "both screens reach the player through it",
            tv.contains("DetailTrailerLaunch.playUrl(context, card.themeVideoUrl, card.title)") &&
                phone.contains("DetailTrailerLaunch.playUrl(")
        )
    }

    @Test
    fun `an unplayable theme answers its press instead of opening artwork`() {
        assertTrue(
            "the TV press branches on the theme card first",
            tv.indexOf("card.isThemeCard -> {") in 0 until tv.indexOf("video != null && !card.playable -> Unit")
        )
        assertTrue(
            "and only a playable one reaches the player",
            tv.contains("if (card.playable) {") && phone.contains("if (card.playable) {")
        )
    }

    private fun load2(src: String): String =
        src.substringAfter("fun loadAnimeThemes()").substringBefore("\n    /**")

    private fun source(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) {
                    val file = File(candidate, relative)
                    assertTrue("source missing: $file", file.isFile)
                    return file.readText()
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError("main source root not found")
    }

    private companion object {
        const val CLIENT = "com/kennyb1201/kbstream/data/animethemes/AnimeThemesClient.kt"
        const val RULES = "com/kennyb1201/kbstream/data/animethemes/AnimeTheme.kt"
        const val VIEW_MODEL = "com/kennyb1201/kbstream/ui/detail/DetailViewModel.kt"
        const val MEDIA_ROW = "com/kennyb1201/kbstream/ui/detail/DetailMediaRow.kt"
        const val LAUNCH = "com/kennyb1201/kbstream/ui/detail/DetailTrailerLaunch.kt"
        const val TV = "com/kennyb1201/kbstream/ui/detail/DetailScreen.kt"
        const val PHONE = "com/kennyb1201/kbstream/ui/mobile/DetailScreenMobile.kt"
    }
}
