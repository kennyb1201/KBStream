package com.kennyb1201.kbstream.data.mal

import com.kennyb1201.kbstream.data.anime.malScoreLabel
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MyAnimeList's integration surface (spec 2026-11-13).
 *
 * MAL is the fifth tracker and the third anime id source, and it is the only one
 * with an OAuth connect (no device flow), a per-account list read and a write
 * endpoint. The parts a JVM test cannot run - the OkHttp calls, the build wiring,
 * the manifest filter - are pinned here by shape; the flow's own decisions are
 * unit-tested in [MalAuthRulesTest].
 */
class MalWiringContractTest {

    private val config by lazy { source(CONFIG) }
    private val api by lazy { source(API) }
    private val legs by lazy { source(LEGS) }
    private val hub by lazy { source(HUB) }
    private val links by lazy { source(LINKS) }
    private val manifest by lazy { source(MANIFEST) }
    private val activity by lazy { source(ACTIVITY) }
    private val gradle by lazy { repoFile("app/build.gradle.kts") }

    // ── F1: the app's client id, never a secret ───────────────────────────

    @Test
    fun `the client id is the app's build constant, like Trakt's`() {
        assertTrue(
            "the build exposes it as a BuildConfig field",
            gradle.contains(
                """buildConfigField("String", "MAL_CLIENT_ID", "\"${'$'}malClientId\"")"""
            )
        )
        assertTrue(
            "read from local.properties, then the environment, then blank",
            gradle.contains("""val malClientId = localProps.getProperty("MAL_CLIENT_ID")""") &&
                gradle.contains("""?: System.getenv("MAL_CLIENT_ID")""")
        )
        assertTrue(config.contains("val clientId: String get() = BuildConfig.MAL_CLIENT_ID"))
        assertTrue(
            "a blank id is a build problem, not a setting",
            config.contains("val isConfigured: Boolean get() = clientId.isNotBlank()")
        )
    }

    @Test
    fun `there is no client secret anywhere`() {
        // This app type has none: MAL issues one only to web apps. Baking one in
        // would be recoverable from every published APK.
        listOf(config, api, links, source(AUTH), source(TOKEN_STORE)).forEach { text ->
            assertFalse(text.contains("client_secret"))
            assertFalse(text.contains("CLIENT_SECRET"))
        }
    }

    // ── F1: the connect ───────────────────────────────────────────────────

    @Test
    fun `the connect stores the verifier before the browser opens`() {
        val auth = source(AUTH)
        val begin = auth.substringAfter("fun beginConnect(")
            .substringBefore("suspend fun completeConnect(")

        assertTrue(
            "the pending values are written first: the redirect can arrive in a " +
                "fresh process",
            begin.indexOf("savePendingFlow(") < begin.indexOf("malAuthorizeUrl(")
        )
        assertTrue(
            "and the challenge is the verifier (MAL rejects S256)",
            begin.contains("challenge = malCodeChallenge(verifier)")
        )
    }

    @Test
    fun `a 401 refreshes once, single-flight, and a dead grant stops everything`() {
        val auth = source(AUTH)
        assertTrue(
            "one refresh in flight: several reads can hit the hour boundary at once",
            auth.contains("refreshMutex.withLock {")
        )
        assertTrue(
            "a failed refresh marks the connection instead of looping",
            auth.contains("markNeedsReconnect(context, \"refresh failed\")")
        )
        val st = source(TOKEN_STORE)
        assertTrue(st.contains("const val KEY_NEEDS_RECONNECT"))
        assertTrue(
            "and nothing calls MAL while that marker stands",
            source(API).contains("if (store.needsReconnect()) return MalListRead.NeedsReconnect")
        )
    }

    @Test
    fun `disconnect is a tombstone, and local data is untouched`() {
        val store = source(TOKEN_STORE)
        val signedOut = store.substringAfter("fun markSignedOut()")
            .substringBefore("fun clearSignedOutMarker()")

        assertTrue("it removes the session", signedOut.contains("remove(KEY_ACCESS_TOKEN)"))
        assertTrue("and records the deliberate disconnect", signedOut.contains("putBoolean(KEY_SIGNED_OUT, true)"))
        assertFalse(
            "it must never touch watch history or watched markers",
            signedOut.contains("WatchHistory") || signedOut.contains("deleteAll")
        )
    }

    @Test
    fun `both link shapes are handled - the callback and the QR start link`() {
        assertTrue(links.contains("fun isCallback(rawUri: String?): Boolean"))
        assertTrue(links.contains("fun isStart(rawUri: String?): Boolean"))
        assertTrue(links.contains("""fun startLink(): String = MAL_REDIRECT_URI + "?connect=1""""))

        assertTrue(
            "the manifest receives the registered redirect",
            manifest.contains("""<data
                    android:scheme="kbstream"
                    android:host="oauth"
                    android:pathPrefix="/mal" />""")
        )
        assertTrue(
            "and a browser can hand it back: BROWSABLE is what makes the " +
                "redirect reach the app at all",
            manifest.contains("android.intent.category.BROWSABLE")
        )
        assertTrue(
            "the callback is exchanged and the start link opens MAL",
            activity.contains("MalLinks.isCallback(link)") &&
                activity.contains("MalAuth.completeConnect(context, link)") &&
                activity.contains("MalLinks.isStart(link)") &&
                activity.contains("MalLinks.openAuthorize(context)")
        )
        assertTrue(
            "and the start link is what a TV can show instead of a dead button",
            links.contains("no browser for the MAL authorize page")
        )
    }

    // ── F4: MAL is the authoritative MAL id ───────────────────────────────

    @Test
    fun `the id hub asks MAL first, then AniList, Kitsu and Jikan`() {
        val malAt = hub.indexOf("MalApiClient.resolveTitle(showTitle)")
        val anilistAt = hub.indexOf("AniListClient.mediaByTitle(showTitle)")
        val kitsuAt = hub.indexOf("KitsuClient.searchAnime(showTitle)")
        val jikanAt = hub.indexOf("resolveMalIdViaJikan(showTitle)")

        assertTrue("MAL is first: $malAt", malAt in 0 until anilistAt)
        assertTrue("AniList second", anilistAt < kitsuAt)
        assertTrue("Kitsu third", kitsuAt < jikanAt)
        assertTrue(
            "and MAL's facts ride the SAME resolution, never a second call",
            hub.contains("malScore = mal.mean") && hub.contains("malRank = mal.rank")
        )
    }

    @Test
    fun `the public read needs only the client id header`() {
        val get = api.substringAfter("private suspend fun executeGet(")
            .substringBefore("private fun malNodeMatchesTitle(")

        assertTrue(
            "X-MAL-CLIENT-ID is the whole public auth",
            get.contains("""builder.header("X-MAL-CLIENT-ID", MalConfig.clientId)""")
        )
        assertTrue(
            "authorized reads carry the bearer token instead",
            get.contains("""builder.header("Authorization", "Bearer ${'$'}token")""")
        )
        assertTrue(
            "the title gate is the shared normalized match - a fuzzy hit is not an id",
            api.contains("animeTitleMatches(")
        )
    }

    @Test
    fun `the id lookup is not a search provider`() {
        // F4: `GET /anime?q=` exists only to resolve an id for a title the app
        // already knows. The app's search stays TMDB + addons.
        assertFalse(api.contains("fun search("))
        assertTrue(api.contains("private const val SEARCH_LIMIT = 5"))
    }

    // ── F2: the tracker leg ───────────────────────────────────────────────

    @Test
    fun `the MAL leg joins the anime legs and needs the account`() {
        val home = source(HOME_VIEW_MODEL)
        val calendar = source(CALENDAR_VIEW_MODEL)

        assertTrue(
            "Home's caught-up leg",
            home.contains(""""mal" to suspend {""") &&
                home.contains("com.kennyb1201.kbstream.data.mal.MalLegs.readMyList(getApplication())")
        )
        assertTrue("and the calendar's", calendar.contains(""""mal" to suspend {"""))
        assertTrue(
            "the read is @me only: MAL has no public username list",
            api.contains("""/users/@me/animelist""") &&
                !api.contains("/users/{username}")
        )
        assertTrue(
            "the leg maps MAL's own status vocabulary",
            legs.contains(""""watching", "current", "rewatching" -> AnimeTrackerStatus.IN_PROGRESS""") &&
                legs.contains(""""completed" -> AnimeTrackerStatus.COMPLETED""")
        )
        assertTrue(
            "an unreadable list is never reported as an empty one",
            legs.contains("AnimeTrackerRead.Unreadable(\"MAL needs reconnecting\")") &&
                legs.contains("AnimeTrackerRead.Failed(")
        )
    }

    @Test
    fun `the list read pages until MAL says there is no next page`() {
        assertTrue(api.contains("body.optJSONObject(\"paging\")?.optString(\"next\")"))
        assertTrue(api.contains("val MAX_LIST_PAGES = 5"))
        assertTrue(api.contains("const val LIST_PAGE_LIMIT = 1000"))
    }

    // ── F3: the write surface ─────────────────────────────────────────────

    @Test
    fun `the list-status write tries PATCH and falls back to PUT`() {
        assertTrue(
            "the docs say PATCH, the official example says PUT: ask the live endpoint",
            api.contains("""sendStatusWrite(context, id, body, "PATCH")""") &&
                api.contains("""sendStatusWrite(context, id, body, "PUT")""")
        )
        assertTrue(
            "only a method-not-allowed answer falls back",
            api.contains("private val OTHER_METHOD_STATUSES = setOf(405, 501)")
        )
        assertTrue(
            "and no separate add endpoint: the PATCH adds a missing title",
            !api.contains("/my_list_status/add")
        )
        assertTrue(
            "a title with no MAL id is skipped silently",
            api.contains("""val id = malId?.takeIf { it > 0 } ?: return MalWriteResult.Skipped("no MAL id")""")
        )
    }

    // ── Invariants ────────────────────────────────────────────────────────

    @Test
    fun `manga, rankings and seasonal rails are out of scope`() {
        listOf("/manga", "/animelist?status", "/rankings", "/season/").forEach { forbidden ->
            assertFalse("must not call $forbidden", api.contains(forbidden))
        }
    }

    @Test
    fun `every request names the app and its build`() {
        assertTrue(config.contains("""const val USER_AGENT = "KBStream/" + BuildConfig.VERSION_NAME"""))
        assertTrue(api.contains("""header("User-Agent", MalConfig.USER_AGENT)"""))
        assertTrue(source(AUTH).contains("""header("User-Agent", MalConfig.USER_AGENT)"""))
    }

    @Test
    fun `fields are always asked for, and only what a screen uses`() {
        assertTrue(api.contains("""&fields=${'$'}{MalConfig.ID_FIELDS}"""))
        assertTrue(api.contains("""?fields=${'$'}{MalConfig.DETAIL_FIELDS}"""))
        assertTrue(api.contains("""?fields=${'$'}{MalConfig.LIST_FIELDS}"""))
        assertFalse("never the default payload", api.contains("fields=*"))
    }

    @Test
    fun `the Detail header labels MAL's score and rank`() {
        val detail = source(DETAIL_SCREEN)
        val label = source(DETAIL_FACTS)

        assertTrue(label.contains("internal fun malScoreLabel(mean: Double?, rank: Int?): String?"))
        assertTrue(detail.contains("malScoreLabel("))
        assertTrue(
            "it sits beside the AniList score, both labelled",
            detail.indexOf("aniListScoreLabel(") < detail.indexOf("malScoreLabel(")
        )
    }

    @Test
    fun `the score label omits what MAL does not have`() {
        assertEquals("MAL 8.4 \u00b7 #12", malScoreLabel(8.4, 12))
        assertEquals("MAL 8.4", malScoreLabel(8.4, null))
        assertEquals("MAL #12", malScoreLabel(null, 12))
        assertEquals("MAL 8.0", malScoreLabel(8.0, 0))
        assertNull("a zero score is not a verdict", malScoreLabel(0.0, null))
        assertNull(malScoreLabel(null, null))
        assertNotNull(malScoreLabel(7.75, null)?.let { it })
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /** A file relative to the repository root (the gradle files, the manifest). */
    private fun repoFile(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val file = File(dir, relative)
            if (file.isFile) return file.readText()
            dir = dir.parentFile
        }
        throw AssertionError("repository file missing: $relative")
    }

    private fun source(relative: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            listOf("", "app/").forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main")
                if (candidate.isDirectory) {
                    val file = File(candidate, relative)
                    if (file.isFile) return file.readText()
                }
            }
            dir = dir.parentFile
        }
        throw AssertionError("source missing: $relative")
    }

    private companion object {
        const val SRC = "java/com/kennyb1201/kbstream/"
        const val MAL = "${SRC}data/mal/"
        const val CONFIG = "${MAL}MalConfig.kt"
        const val AUTH = "${MAL}MalAuth.kt"
        const val API = "${MAL}MalApiClient.kt"
        const val LEGS = "${MAL}MalLegs.kt"
        const val LINKS = "${MAL}MalLinks.kt"
        const val TOKEN_STORE = "${MAL}MalTokenStore.kt"
        const val HUB = "${SRC}data/anime/AnimeIdHub.kt"
        const val HOME_VIEW_MODEL = "${SRC}ui/home/HomeViewModel.kt"
        const val CALENDAR_VIEW_MODEL = "${SRC}ui/library/LibraryCalendarViewModel.kt"
        const val DETAIL_SCREEN = "${SRC}ui/detail/DetailScreen.kt"
        const val DETAIL_FACTS = "${SRC}data/anime/AnimeDetailFacts.kt"
        const val ACTIVITY = "${SRC}MainActivity.kt"
        const val MANIFEST = "AndroidManifest.xml"
    }
}
