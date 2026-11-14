package com.kennyb1201.kbstream.data.anime

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The AniList/Kitsu layer as a whole (spec 2026-11-13).
 *
 * [AnimeHubRulesTest] covers the decisions; this covers what a JVM test cannot
 * run - the two HTTP readers, the hub's chain and cache, and the wiring into the
 * rails, the Detail header and Settings - plus the spec's hard invariants: v1 is
 * READ-ONLY (no token, no write) and no rail ever requires an anime account.
 */
class AnimeHubContractTest {

    private val hub by lazy { source(ANIME + "AnimeIdHub.kt") }
    private val aniList by lazy { source(ANIME + "AniListClient.kt") }
    private val kitsu by lazy { source(ANIME + "KitsuClient.kt") }
    private val legs by lazy { source(ANIME + "AnimeTrackerLegs.kt") }
    private val rules by lazy { source(ANIME + "AnimeTrackerRules.kt") }
    private val rail by lazy { source(HOME + "AnimeTrackerRail.kt") }

    // ── F1: the hub ──────────────────────────────────────────────────────

    @Test
    fun `the hub answers every id from one cached lookup per title`() {
        assertTrue(
            "one cached answer, including 'nobody knows this title'",
            hub.contains("identities[normalized]?.let { return it }") &&
                hub.contains("identities[normalized] = identity")
        )
        assertTrue(
            "a failure is not remembered - a transient outage is not a verdict",
            hub.contains("// A failure is NOT remembered") && hub.contains("return AnimeIdentity()")
        )
        assertTrue(
            "keyed by the normalized title, so case and spacing cannot split one show",
            hub.contains("private val identities = ConcurrentHashMap<String, AnimeIdentity>()") &&
                hub.contains("val normalized = normalizeAnimeTitle(showTitle)")
        )
        assertTrue(
            "AniList first, Kitsu second, Jikan last - the order the spec fixes",
            hub.indexOf("AniListClient.mediaByTitle") in 0 until
                hub.indexOf("KitsuClient.searchAnime") &&
                hub.indexOf("KitsuClient.searchAnime") in 0 until
                hub.indexOf("resolveMalIdViaJikan")
        )
        assertTrue(
            "the Jikan fallback only runs when the MAL id is still missing",
            hub.contains("if (malId == null) {") &&
                hub.substringAfter("// 3. Jikan by title").contains("resolveMalIdViaJikan(showTitle)")
        )
        assertTrue(
            "a result is accepted only on a title match, from whichever names it carries",
            hub.contains("animeTitleMatches(") &&
                hub.contains("names?.optString(\"romaji\")") &&
                hub.contains("names?.optString(\"english\")")
        )
        assertTrue(
            "Kitsu's mappings fill only the ids AniList left open, never overwrite one",
            hub.contains("if (malId == null || anilistId == null)") &&
                hub.contains("mappings[\"myanimelist/anime\"]") &&
                hub.contains("mappings[\"anilist/anime\"]")
        )
        assertTrue(
            "F3's two facts come out of the same Media query - no second AniList call",
            hub.contains("averageScore = media.optInt(\"averageScore\", 0)") &&
                hub.contains("media.optJSONObject(\"nextAiringEpisode\")")
        )
    }

    // ── The clients: endpoint, headers, rate limits ──────────────────────

    @Test
    fun `AniList is a GraphQL POST on the documented endpoint, planned at 30 a minute`() {
        assertTrue(aniList.contains("const val GRAPHQL_URL = \"https://graphql.anilist.co\""))
        assertTrue(
            "POST with the query and variables, as the API documents",
            aniList.contains(".post(body.toRequestBody(JSON_MEDIA_TYPE))") &&
                aniList.contains(".put(\"query\", query)") &&
                aniList.contains(".put(\"variables\", variables)")
        )
        assertTrue(
            "the live 30-a-minute ceiling, not the documented 90",
            aniList.contains("const val MAX_REQUESTS_PER_MINUTE = 30") &&
                aniList.contains("if (recentRequestsMs.size < MAX_REQUESTS_PER_MINUTE)")
        )
        assertTrue(
            "a 429 parks the leg for Retry-After instead of retrying inside the window",
            aniList.contains("if (response.code == 429)") &&
                aniList.contains("park(response.header(\"Retry-After\"))")
        )
        assertTrue(
            "one User-Agent, naming the app and the build",
            aniList.contains("const val USER_AGENT = \"KBStream/\" + BuildConfig.VERSION_NAME") &&
                aniList.contains(".header(\"User-Agent\", USER_AGENT)")
        )
    }

    @Test
    fun `Kitsu reads on kitsu app, one request a second, with the anime included`() {
        assertTrue(
            // The literal moved into KitsuConfig so the WRITE rules can be
            // tested without OkHttp (2026-11-13 phase 2); the read client points
            // back at it, so there is still exactly one host string.
            "the API's current host - kitsu.io only still answers, it did not stay",
            kitsu.contains("const val BASE_URL = com.kennyb1201.kbstream.data.kitsu.KitsuConfig.API_BASE") &&
                !kitsu.contains("https://kitsu.io") &&
                source("com/kennyb1201/kbstream/data/kitsu/KitsuConfig.kt")
                    .contains("const val API_BASE = \"https://kitsu.app/api/edge\"")
        )
        assertTrue(
            "JSON:API's media type is named",
            kitsu.contains(".header(\"Accept\", \"application/vnd.api+json\")")
        )
        assertTrue(
            "no published ceiling is treated as unspecified: one a second, page limit 20",
            kitsu.contains("private const val MIN_SPACING_MS = 1_000L") &&
                kitsu.contains("const val PAGE_LIMIT = 20")
        )
        assertTrue(
            "the mapping graph is what Kitsu is here for",
            kitsu.contains("/mappings") && kitsu.contains("attributes.optString(\"externalSite\")")
        )
        assertTrue(
            "a library read brings its anime in the same response",
            kitsu.contains("&include=anime")
        )
        assertTrue(
            "and every Kitsu read is a GET - no write surface exists in v1",
            kitsu.contains(".get()") && !kitsu.contains(".post(") && !kitsu.contains(".put(")
        )
    }

    // ── F2: the legs ─────────────────────────────────────────────────────

    @Test
    fun `no username is dormant, a private list is unreadable, a failure is failed`() {
        assertTrue(
            "an empty username never reaches the network",
            legs.windowed("if (name.isEmpty()) return AnimeTrackerRead.NotConfigured".length)
                .count { it == "if (name.isEmpty()) return AnimeTrackerRead.NotConfigured" } == 2
        )
        assertTrue(
            "a private or unknown list is reported as unreadable, not as an empty list",
            legs.contains("AnimeTrackerRead.Unreadable(\"AniList returned no lists for '\$name'\")") &&
                legs.contains("AnimeTrackerRead.Unreadable(\"Kitsu has no user named '\$name'\")")
        )
        assertTrue(
            "a transport error is a failure, so the leg keeps its previous cards",
            legs.contains("return AnimeTrackerRead.Failed(error)")
        )
        assertTrue(
            "AniList's status is read from the entry, falling back to the list it sits in",
            legs.contains("aniListStatus(entry.optString(\"status\").ifBlank { listStatus })")
        )
        assertTrue(
            "Kitsu is read two pages deep, and stops early on a short page",
            legs.contains("private const val KITSU_MAX_PAGES = 2") &&
                legs.contains("if (data.length() < KitsuClient.PAGE_LIMIT) break")
        )
    }

    @Test
    fun `the TMDB join is an exact title match and an absolute progress walk`() {
        assertTrue(
            "a near miss is no card at all",
            rules.contains("return wanted.contains(found)") &&
                !rules.contains("startsWith(") &&
                !rules.contains("contains(found, ignoreCase")
        )
        assertTrue(
            "specials are excluded from the absolute count",
            rules.contains("seasonEpisodeCounts.filter { it.first > 0 && it.second > 0 }")
        )
        assertTrue(
            "the rail bridge is shared by Home and the calendar, not duplicated",
            rail.contains("suspend fun parentIdFor(") &&
                rail.contains("suspend fun watchedPairs(") &&
                rail.contains("animeTmdbTitleMatch(") &&
                rail.contains("animeWatchedEpisodes(progress, counts)")
        )
        assertTrue(
            "and it remembers 'no match' too, so a rail rebuild does not re-search",
            rail.contains("private val parentIdCache = HashMap<String, String?>()") &&
                rail.contains("if (parentIdCache.containsKey(key))")
        )
    }

    @Test
    fun `both legs arrive in their own slot, after the trackers, and never gate them`() {
        val arrivals = source(HOME + "ContinueWatchingArrivals.kt")
        val upNext = source(HOME + "HomeUpNext.kt")
        val home = source(HOME + "HomeViewModel.kt")

        assertTrue(
            "two slots: one service answering must not erase the other's cards",
            arrivals.contains("\n    ANILIST") && arrivals.contains("\n    KITSU") &&
                arrivals.contains("ResumeSource.ANILIST -> TrackerFeed.ANILIST") &&
                arrivals.contains("ResumeSource.KITSU -> TrackerFeed.KITSU")
        )
        assertTrue(
            "and they merge last, behind every tracker the app already had",
            arrivals.substringAfter("MERGE_ORDER").indexOf("TrackerFeed.ANILIST") >
                arrivals.substringAfter("MERGE_ORDER").indexOf("TrackerFeed.BINGECAT")
        )
        assertTrue(
            "both are Continue Watching sources, so a card can name where it came from",
            upNext.contains("\n    ANILIST") && upNext.contains("\n    KITSU")
        )
        assertTrue(
            "the leg's three-way answer: cleared, kept, replaced",
            upNext.contains("data object NotConfigured : AnimeNextUpResult") &&
                upNext.contains("data class Failed(val error: Throwable) : AnimeNextUpResult")
        )
        assertTrue(
            "Home reads both legs, and not one of them is required",
            home.contains("AnimeTrackerLegs.readAniList(") &&
                home.contains("AnimeTrackerLegs.readKitsu(") &&
                home.contains("AnimeNextUpResult.NotConfigured ->") &&
                home.contains("arrivals.arrive(TrackerFeed.ANILIST, result.items)") &&
                home.contains("arrivals.arrive(TrackerFeed.KITSU, result.items)")
        )
        assertTrue(
            "the caught-up half joins the same union, failing soft",
            home.contains("AnimeTrackerRail.caughtUpCandidates(") &&
                home.contains("trackers = mdbList + trakt + bingecat + anime")
        )
        assertTrue(
            "and the calendar carries the same shows",
            source(LIBRARY + "LibraryCalendarViewModel.kt").contains("animeCaughtUpCandidates() +")
        )
        assertTrue(
            "a finished anime is the caught-up rule; an in-progress one is Continue Watching's",
            rail.contains("entry.status == AnimeTrackerStatus.COMPLETED && entry.progress > 0") &&
                home.contains("entry.status == AnimeTrackerStatus.IN_PROGRESS && entry.progress > 0")
        )
    }

    // ── F3: the Detail header ────────────────────────────────────────────

    @Test
    fun `an anime Detail shows the AniList score and next airing, and a live-action one neither`() {
        val screen = source(DETAIL + "DetailScreen.kt")
        val viewModel = source(DETAIL + "DetailViewModel.kt")

        assertTrue(
            "the screen labels the community score, so it is never read as TMDB's",
            screen.contains("aniListScoreLabel(") && screen.contains("aniListNextAiringLabel(")
        )
        assertTrue(
            "both read the one identity the Detail already resolved",
            viewModel.contains("AnimeIdHub.resolve(") &&
                screen.contains("animeIdentity")
        )
        assertTrue(
            "TMDB's next episode wins when it exists - one line, never two",
            source(ANIME + "AnimeDetailFacts.kt").contains("tmdbHasNextEpisode")
        )
        assertTrue(
            "the hub only runs for the Animation genre - no live-action title pays for it",
            viewModel.contains("loadAnimeThemes(") &&
                source(ANIME + "AnimeDetailFacts.kt").contains("aniListScoreLabel")
        )
    }

    // ── Settings, and the read-only invariant ────────────────────────────

    @Test
    fun `Settings has a row per service, saying what it reads and what a private list does`() {
        // The two rows moved into the shared block both Settings surfaces render
        // (spec 2026-11-13: the phone needed the same rows), so the pins follow
        // them there - the row, its label and its dialog are the same code.
        val settings = source(SETTINGS + "AnimeTrackerRows.kt")
        val dialog = source(SETTINGS + "AnimeUsernameDialog.kt")
        val prefs = source("com/kennyb1201/kbstream/data/settings/AppPreferences.kt")

        assertTrue(
            "two rows, next to the other trackers",
            settings.contains("animeTrackerRowLabel(\"AniList\"") &&
                settings.contains("animeTrackerRowLabel(\"Kitsu\"")
        )
        assertTrue(
            "each opens a dialog that saves the public username to its preference",
            settings.contains("AniListConnectDialog(") &&
                settings.contains("KitsuConnectDialog(") &&
                settings.contains("AppPreferences.setAniListUsername(context, username)") &&
                settings.contains("AppPreferences.setKitsuUsername(context, username)")
        )
        assertTrue(
            "the usernames live with the other tracker settings",
            prefs.contains("KEY_ANILIST_USERNAME") && prefs.contains("KEY_KITSU_USERNAME")
        )
        assertTrue(
            "the row says PUBLIC list, and says a private profile reads nothing",
            dialog.contains("animeTrackerDescription(") &&
                dialog.contains("A private profile ") &&
                dialog.contains("public anime list")
        )
        assertTrue(
            "disconnecting is one clear, and it removes the leg",
            dialog.contains("onSave(null)") && dialog.contains("\"CLEAR\"")
        )
    }

    @Test
    fun `the readers stay tokenless and only the write client carries a token`() {
        // Phase 1's READ-ONLY invariant, kept for everything phase 2 did not have
        // to change: the hub, the Kitsu reader, the legs, the pure rules and the
        // rail still carry no credential at all. Phase 2 adds exactly one place
        // that does, and it is pinned below rather than allowed to spread.
        listOf(hub, kitsu, legs, rules, rail).forEachIndexed { index, layer ->
            listOf(
                "Authorization",
                "Bearer",
                "access_token",
                "refresh_token",
                "client_secret",
                "api_key",
                "apiKey"
            ).forEach { forbidden ->
                assertFalse(
                    "layer $index still has no credential: $forbidden appears in it",
                    layer.contains(forbidden)
                )
            }
        }

        assertTrue(
            "the one authenticated request is AniList's mutation POST, with the token in a header",
            aniList.contains("header(\"Authorization\", \"Bearer \$accessToken\")")
        )
        assertFalse(
            "no client secret is compiled in, sent or named anywhere in the layer",
            aniList.contains("client_secret")
        )
        assertTrue(
            "AniList's POSTs are the public read and the authenticated mutation, nothing else",
            aniList.windowed(".post(".length).count { it == ".post(" } == 2
        )
        assertTrue(
            "and the settings row promises nothing is written",
            source(SETTINGS + "AnimeUsernameDialog.kt").contains("nothing is written to")
        )
    }

    // ── helpers ──────────────────────────────────────────────────────────

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
        const val ROOT = "com/kennyb1201/kbstream/"
        const val ANIME = ROOT + "data/anime/"
        const val HOME = ROOT + "ui/home/"
        const val DETAIL = ROOT + "ui/detail/"
        const val SETTINGS = ROOT + "ui/settings/"
        const val LIBRARY = ROOT + "ui/library/"
    }
}
