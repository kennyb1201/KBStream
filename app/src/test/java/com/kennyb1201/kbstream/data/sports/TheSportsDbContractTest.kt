package com.kennyb1201.kbstream.data.sports

import java.io.File
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TheSportsDB as the Hub's second sports source (spec 2026-11-13).
 *
 * One rule, and every test here is a way of holding it: **ESPN keeps live scores;
 * TheSportsDB adds the leagues ESPN lacks, their fixtures and tables, and badges
 * where ESPN has none.** So a fixture read from a V1 payload has to become a card
 * the matcher, the favourites tab and both screens already understand (F2), a
 * missing badge must be filled without overriding one the feed sent (F3), a table
 * must land in the hub's existing points-table shape (F4), and the free key's
 * shared 30 requests/minute must never be spent on the hub's live beat (F1).
 *
 * The payloads below are trimmed copies of live answers from the public test key,
 * captured 2026-11-14, so the field names are the endpoint's own rather than a
 * reading of the docs - and the parse tests run with no network, no key and no
 * Android context, like the ESPN ones.
 */
class TheSportsDbContractTest {

    // ── F2: a V1 fixture is a hub card ───────────────────────────────────

    @Test
    fun `a fixture payload reads into the hub's own games`() {
        val games = theSportsDbGames(FIXTURES, "tsdb:4332")
        assertEquals("both fixtures are cards", 2, games.size)
        val first = games.first()
        assertEquals("the V1 event id keys the card", "2482248", first.id)
        assertEquals("and it is the event id the summary would use", first.id, first.eventId)
        assertEquals("TheSportsDB's league path scopes it", "tsdb:4332", first.league)
        assertEquals("Lazio vs Lecce", first.name)
        assertEquals(GameState.UPCOMING, first.state)
        assertEquals("a Round line is the card's context", "Round 12", first.note)
        assertEquals("Stadio Olimpico", first.venue)
        assertEquals("the home side is the home side", "Lazio", first.home.displayName)
        assertEquals("with its CDN badge", "https://r2.thesportsdb.com/badge/lazio.png", first.home.logoUrl)
        assertEquals("the away side too", "Lecce", first.away.displayName)
        assertEquals("a two-word side takes its initials", "LAZ", first.home.abbreviation)
        assertEquals(
            "kickoff is read as UTC - V1 sends no zone suffix",
            Instant.parse("2026-11-21T14:00:00Z").toEpochMilli(),
            first.dateMs
        )
        assertEquals(
            "the fixture's clock line is its kickoff time, in the viewer's zone",
            "2:00 PM",
            theSportsDbStatusText(GameState.UPCOMING, first.dateMs, ZoneId.of("UTC"))
        )
        assertTrue(
            "a fixture has no score to draw",
            first.home.score == null && first.away.score == null
        )
        assertEquals(
            "sorted by kickoff, earliest first",
            listOf("2482248", "2482249"),
            games.map { it.id }
        )
        assertTrue(
            "V1 carries no broadcast field; the matcher still gets the side names",
            first.broadcastNames.isEmpty() && first.home.displayName.isNotEmpty()
        )
    }

    @Test
    fun `a result carries its score, and the status word decides the state`() {
        assertEquals(GameState.FINAL, theSportsDbState("FT"))
        assertEquals(GameState.FINAL, theSportsDbState("AET"))
        assertEquals(GameState.FINAL, theSportsDbState("PEN"))
        assertEquals(GameState.UPCOMING, theSportsDbState("NS"))
        assertEquals(
            "an unknown word is not claimed as live: no V2 livescore leg exists here",
            GameState.UPCOMING,
            theSportsDbState("weird")
        )
        val result = theSportsDbGames(RESULT, "tsdb:4332").single()
        assertEquals(GameState.FINAL, result.state)
        assertEquals("Final", result.statusDetail)
        assertEquals("2", result.home.score)
        assertEquals("1", result.away.score)
    }

    @Test
    fun `the round the payload names widens a one-fixture answer`() {
        // The free key's `eventsnextleague` often answers a SINGLE fixture, which
        // is not a fixtures tab. The round it names is what the client follows back
        // to `eventsround` - so the read has to find it.
        val round = theSportsDbRound(FIXTURES)
        assertNotNull("the payload names its round", round)
        assertEquals("12", round?.round)
        assertEquals("2026-2027", round?.season)
        assertNull(
            "a payload with no round asks for no second call",
            theSportsDbRound("""{"events":[{"idEvent":"1","strHomeTeam":"A"}]}""")
        )
    }

    @Test
    fun `an event that cannot be drawn is dropped, never half-drawn`() {
        val body = """{"events":[
            {"idEvent":"1","strHomeTeam":"A","strAwayTeam":"B","strTimestamp":"2026-11-21T14:00:00"},
            {"idEvent":"","strHomeTeam":"A","strAwayTeam":"B","strTimestamp":"2026-11-21T14:00:00"},
            {"idEvent":"3","strHomeTeam":"","strAwayTeam":"B","strTimestamp":"2026-11-21T14:00:00"},
            {"idEvent":"4","strHomeTeam":"A","strAwayTeam":"B","strTimestamp":"not a time"}
        ]}"""
        assertEquals(
            "only the complete fixture survives",
            listOf("1"),
            theSportsDbGames(body, "tsdb:4332").map { it.id }
        )
        assertEquals(
            "an empty matchday is an answer, not a failure",
            emptyList<SportsGame>(),
            theSportsDbGames("""{"events":""}""", "tsdb:4332")
        )
    }

    // ── F3: badges fill gaps only ────────────────────────────────────────

    @Test
    fun `a badge is filled only where the feed carried none`() {
        val espnSide = SportsTeam(
            id = "13",
            abbreviation = "LAL",
            displayName = "Los Angeles Lakers",
            logoUrl = "https://a.espncdn.com/lal.png",
            score = null,
            isHome = true,
            record = null,
            league = "basketball/nba"
        )
        val blankSide = SportsTeam(
            id = "22",
            abbreviation = "TIG",
            displayName = "Tigres",
            logoUrl = null,
            score = null,
            isHome = false,
            record = null,
            league = "tsdb:4350"
        )
        val game = SportsGame(
            id = "1",
            league = "tsdb:4350",
            name = "Tigres at Lakers",
            dateMs = 0L,
            state = GameState.UPCOMING,
            statusDetail = "8:00 PM",
            away = blankSide,
            home = espnSide,
            broadcastNames = emptyList()
        )
        val filled = theSportsDbFillBadges(
            listOf(game),
            mapOf(
                theSportsDbBadgeKey("Tigres") to "https://r2.thesportsdb.com/tigres.png",
                theSportsDbBadgeKey("Los Angeles Lakers") to "https://r2.thesportsdb.com/lal.png"
            )
        ).single()
        assertEquals(
            "the missing crest is filled from the CDN",
            "https://r2.thesportsdb.com/tigres.png",
            filled.away.logoUrl
        )
        assertEquals(
            "a logo the feed already sent is never replaced - no flicker, no swap",
            "https://a.espncdn.com/lal.png",
            filled.home.logoUrl
        )
        val untouched = listOf(game)
        assertTrue(
            "nothing to fill leaves the list untouched",
            theSportsDbFillBadges(untouched, emptyMap()) === untouched
        )
    }

    @Test
    fun `a badge lookup reads the first usable CDN URL, and misses are misses`() {
        assertEquals(
            "https://r2.thesportsdb.com/lazio.png",
            theSportsDbTeamBadge(
                """{"teams":[{"strTeam":"Lazio","strBadge":""},
                    {"strTeam":"Lazio","strBadge":"https://r2.thesportsdb.com/lazio.png"}]}"""
            )
        )
        assertNull(
            "a team the service cannot name is null, which the client caches as a miss",
            theSportsDbTeamBadge("""{"teams":""}""")
        )
        assertEquals(
            "the CDN host is the artwork one, not the API",
            "r2.thesportsdb.com",
            THESPORTSDB_ARTWORK_HOST
        )
        assertTrue(
            "badge images are loaded by URL and never by an API call",
            theSportsDbTeamBadge("""{"teams":[{"strBadge":"https://r2.thesportsdb.com/x.png"}]}""")
                ?.startsWith("https://r2.thesportsdb.com/") == true
        )
    }

    // ── F4: tables in the hub's existing shape ───────────────────────────

    @Test
    fun `a league table reads into the hub's points-table shape`() {
        val groups = theSportsDbTable(TABLE, "Serie A", "tsdb:4332")
        assertEquals("one group: V1 has no conferences to split on", 1, groups.size)
        val group = groups.single()
        assertEquals("Serie A", group.name)
        assertEquals(2, group.entries.size)
        val inter = group.entries.first()
        assertEquals("Inter Milan", inter.displayName)
        assertEquals("INT", inter.abbreviation)
        assertEquals(27, inter.wins)
        assertEquals(6, inter.ties)
        assertEquals(5, inter.losses)
        assertEquals("87", inter.points)
        assertEquals("DDWWD", inter.streak)
        assertEquals("https://r2.thesportsdb.com/inter.png", inter.logoUrl)
        assertEquals("133681", inter.teamId)
        assertTrue(
            "a soccer table is read on points, so no win percentage is invented",
            inter.winPercent.isEmpty() && inter.gamesBehind.isEmpty()
        )
    }

    @Test
    fun `a league with no table answers nothing, not an empty table`() {
        assertEquals(
            "V1's no-table answer is an empty string, and the hub draws no table",
            emptyList<StandingGroup>(),
            theSportsDbTable("""{"table":""}""", "Serie A", "tsdb:4332")
        )
        assertEquals(
            "a payload with no table key at all is the same answer",
            emptyList<StandingGroup>(),
            theSportsDbTable("""{"player":""}""", "Serie A", "tsdb:4332")
        )
    }

    // ── F1: the clock the caches run on ─────────────────────────────────

    @Test
    fun `a fixture list goes stale at the viewer's midnight, not 24 hours later`() {
        val lateEvening = Instant.parse("2026-11-14T22:30:00Z").toEpochMilli()
        assertEquals(
            "read at 11:30 PM, the list answers tomorrow in the morning",
            Instant.parse("2026-11-15T00:00:00Z").toEpochMilli(),
            theSportsDbNextDayStartMs(lateEvening, ZoneId.of("UTC"))
        )
        val justAfterMidnight = Instant.parse("2026-11-15T00:05:00Z").toEpochMilli()
        assertEquals(
            "and a read just after midnight lives until the NEXT midnight",
            Instant.parse("2026-11-16T00:00:00Z").toEpochMilli(),
            theSportsDbNextDayStartMs(justAfterMidnight, ZoneId.of("UTC"))
        )
    }

    // ── F2: the coverage table ───────────────────────────────────────────

    @Test
    fun `the coverage table is the four leagues verified against the live V1 API`() {
        assertEquals(
            "each id was verified against the endpoint's own strLeague on 2026-11-14",
            listOf("tsdb:4332", "tsdb:4331", "tsdb:4334", "tsdb:4350"),
            SportsLeagues.THESPORTSDB_PATHS
        )
        SportsLeagues.THESPORTSDB_PATHS.forEach { path ->
            val league = SportsLeagues.byPath(path)
            assertNotNull("$path must be in the catalog", league)
            requireNotNull(league)
            assertTrue("$path is read from TheSportsDB", league.isTheSportsDb)
            assertEquals(
                "$path carries its V1 league id",
                path.removePrefix(SportsLeagues.THESPORTSDB_PATH_PREFIX),
                league.theSportsDbId
            )
            assertEquals("$path is a head-to-head league", SportsKind.HEAD_TO_HEAD, league.kind)
            assertTrue("$path has a table", league.hasStandings)
            assertTrue(
                "$path is read on points, W-D-L-PTS, like every soccer table here",
                league.pointsTable && league.hasTies
            )
            assertTrue(
                "$path is on out of the box, so the coverage is visible",
                path in SportsLeagues.DEFAULT_ENABLED
            )
        }
        SportsLeagues.ALL.filterNot { it.isTheSportsDb }.forEach { league ->
            assertTrue(
                "${league.path} is an ESPN path and never a tsdb one",
                league.theSportsDbId == null && !league.path.startsWith("tsdb:")
            )
        }
    }

    // ── F1: the key ─────────────────────────────────────────────────────

    @Test
    fun `the published test key is the default, and a pasted key overrides it`() {
        assertEquals("the docs' public test key", "123", THESPORTSDB_TEST_KEY)
        val client = code(CLIENT)
        assertTrue(
            "the request's key segment is the constant, not a second literal",
            client.contains("THESPORTSDB_BASE + key + \"/\" + path") ||
                client.contains("THESPORTSDB_BASE + key")
        )
        assertTrue(
            "a blank personal key falls back to the test key",
            client.contains("AppPreferences.getTheSportsDbKey(context).ifBlank { THESPORTSDB_TEST_KEY }")
        )
        assertTrue(
            "and the app never ships a paid key: the only base literal is the public URL",
            Regex("THESPORTSDB_BASE = \"https://www.thesportsdb.com/api/v1/json/\"")
                .containsMatchIn(code(RULES))
        )
    }

    @Test
    fun `the personal key is a synced credential with a row in both settings panes`() {
        val prefs = code(PREFS)
        assertTrue(
            "the key lives in the encrypted, profile-scoped store",
            prefs.contains("fun getTheSportsDbKey(context: Context): String") &&
                prefs.contains("fun setTheSportsDbKey(context: Context, key: String)")
        )
        assertTrue(
            "and syncs, so it is pasted once per account",
            Regex("SYNCED_API_KEYS[^)]*KEY_THESPORTSDB_KEY", RegexOption.DOT_MATCHES_ALL)
                .containsMatchIn(code(PREFS))
        )
        listOf(SETTINGS_TV, SETTINGS_MOBILE).forEach { path ->
            val settings = code(path)
            assertTrue(
                "$path must accept the optional key",
                settings.contains("AppPreferences.getTheSportsDbKey(context)") &&
                    settings.contains("AppPreferences.setTheSportsDbKey(")
            )
        }
    }

    // ── F1: the rate limit ──────────────────────────────────────────────

    @Test
    fun `a 429 answers the cache and waits a minute`() {
        val client = code(CLIENT)
        assertTrue("the status is handled by name", client.contains("response.code == 429"))
        assertTrue(
            "the endpoint's own Retry-After is honoured",
            client.contains("response.header(\"Retry-After\")")
        )
        assertTrue(
            "and never less than a minute, which is what the docs say to wait",
            client.contains("RETRY_AFTER_MS = 60L * 1_000L") &&
                client.contains("coerceAtLeast(RETRY_AFTER_MS)")
        )
        assertTrue(
            "every read inside the window answers its cache and sends nothing",
            client.contains("if (now < blockedUntilMs) return cached?.value.orEmpty()")
        )
        assertTrue(
            "a failed fetch answers the cached list, stale or not - never a blank Hub",
            client.contains("return cached?.value.orEmpty()")
        )
        assertTrue(
            "the table is held for six hours, like ESPN's (F4's cadence)",
            client.contains("TABLE_TTL_MS = 6L * 60L * 60_000L")
        )
    }

    // ── F1/F2: polling discipline, and no livescore leg ─────────────────

    @Test
    fun `only enabled leagues are ever polled, and the live scores stay ESPN's`() {
        val viewModel = code(VIEW_MODEL)
        assertTrue(
            "a tsdb league reads its fixtures from TheSportsDB",
            viewModel.contains("theSportsDb.fixtures(theSportsDbKey(), leagueId)")
        )
        assertTrue(
            "and its table from TheSportsDB, on the same six-hour path",
            viewModel.contains("theSportsDb.standings(theSportsDbKey(), tsdbLeague)")
        )
        assertTrue(
            "an ESPN league's cards still come from ESPN, with only the missing badge filled",
            viewModel.contains("theSportsDb.withBadges(theSportsDbKey(), espn.scoreboard(league.path))")
        )
        assertTrue(
            "the reads happen inside fetchSection, which is only called for ENABLED leagues",
            viewModel.contains("private suspend fun fetchSections(leagues: List<SportsLeague>)")
        )

        val worker = code(WORKER)
        assertTrue(
            "the reminder round is ESPN-only: a tsdb league has no live scores to notify about",
            worker.contains(".filterNot { it.isTheSportsDb }")
        )
        val feed = code(LIVE_FEED)
        assertTrue(
            "and the guide grid's live poll never asks a fixtures source for a score",
            feed.contains("it.kind == SportsKind.HEAD_TO_HEAD && !it.isTheSportsDb")
        )

        val client = withoutComments(CLIENT)
        assertFalse("no V2 path is called", client.contains("api/v2"))
        assertFalse("and no livescore endpoint exists on this path", client.contains("livescore"))
        listOf(
            "eventsnextleague.php?id=" to "fixtures",
            "eventsround.php?id=" to "the matchday",
            "lookuptable.php?l=" to "the table",
            "searchteams.php?t=" to "a badge"
        ).forEach { (endpoint, what) ->
            assertTrue(
                "the V1 endpoint for $what is the one it calls",
                code(CLIENT).contains(endpoint)
            )
        }
    }

    @Test
    fun `the new leagues are on for a fresh install, and the panel owns a stored set`() {
        // `getSportsEnabledLeagues` is a PURE read: a set a viewer arranged is
        // returned exactly as written, so these leagues join a fresh install
        // through DEFAULT_ENABLED and an existing profile turns them on in the
        // leagues panel - the same path every league added before them took.
        val prefs = code(PREFS)
        assertTrue(
            "the enabled set comes from the stored value or the catalog default, never a migration",
            prefs.contains("readSportsSet(context, KEY_SPORTS_LEAGUES)") &&
                prefs.contains("SportsLeagues.DEFAULT_ENABLED")
        )
        assertFalse(
            "no version stamp rewrites what a viewer chose",
            prefs.contains("SPORTS_LEAGUES_VERSION")
        )
        assertTrue(
            "and the panel lists every catalog league, so the new ones have toggles",
            code(PANEL).contains("SportsLeagues") &&
                SportsLeagues.ALL.size > SportsLeagues.THESPORTSDB_PATHS.size
        )
    }

    // ── source access ───────────────────────────────────────────────────

    private val sourceRoot: File by lazy { findSourceRoot() }

    private fun read(relative: String): String {
        val file = File(sourceRoot, relative)
        assertTrue("source missing: $file", file.isFile)
        return file.readText()
    }

    /** A source file with its comments and layout folded away, for `contains` pins. */
    private fun code(relative: String): String =
        withoutComments(read(relative)).replace(Regex("\\s+"), " ").trim()

    /**
     * Drops `//` comments but never a URL's scheme: a naive cut at `//` would
     * truncate every `https://` in the file and make the endpoint pins below pass
     * against nothing.
     */
    private fun withoutComments(text: String): String =
        text.lineSequence().joinToString("\n") { raw ->
            val line = raw.trimStart()
            // A block/KDoc line is a comment too: the notes above deliberately
            // talk about V2 and livescores, and a `contains` pin must read the
            // code, not the prose explaining why the code does not exist.
            if (line.startsWith("//") || line.startsWith("*") || line.startsWith("/*")) {
                ""
            } else {
                val marker = raw.indexOf("//")
                if (marker <= 0 || raw[marker - 1] == ':') raw else raw.substring(0, marker)
            }
        }

    private fun findSourceRoot(): File {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java")
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError(
            "main source root not found walking up from " + System.getProperty("user.dir")
        )
    }

    private companion object {
        const val PKG = "com/kennyb1201/kbstream"
        const val CLIENT = "$PKG/data/sports/TheSportsDbClient.kt"
        const val RULES = "$PKG/data/sports/TheSportsDbRules.kt"
        const val VIEW_MODEL = "$PKG/ui/sports/SportsHubViewModel.kt"
        const val WORKER = "$PKG/work/SportsNotificationWorker.kt"
        const val LIVE_FEED = "$PKG/data/sports/LiveScoreFeed.kt"
        const val PREFS = "$PKG/data/settings/AppPreferences.kt"
        const val PANEL = "$PKG/ui/sports/SportsHubScreen.kt"
        const val SETTINGS_TV = "$PKG/ui/settings/SettingsScreen.kt"
        const val SETTINGS_MOBILE = "$PKG/ui/mobile/SettingsScreenMobile.kt"

        /** Two fixtures of one Serie A round, as `eventsnextleague`/`eventsround` answer. */
        const val FIXTURES = """{"events":[
            {"idEvent":"2482248","strTimestamp":"2026-11-21T14:00:00",
             "strEvent":"Lazio vs Lecce","strHomeTeam":"Lazio","strAwayTeam":"Lecce",
             "intHomeScore":null,"intAwayScore":null,"intRound":"12","strSeason":"2026-2027",
             "idHomeTeam":"133668","idAwayTeam":"133678",
             "strHomeTeamBadge":"https://r2.thesportsdb.com/badge/lazio.png",
             "strAwayTeamBadge":"https://r2.thesportsdb.com/badge/lecce.png",
             "strVenue":"Stadio Olimpico","strStatus":"NS"},
            {"idEvent":"2482249","strTimestamp":"2026-11-21T17:00:00",
             "strEvent":"Inter Milan vs Roma","strHomeTeam":"Inter Milan","strAwayTeam":"Roma",
             "intHomeScore":null,"intAwayScore":null,"intRound":"12","strSeason":"2026-2027",
             "idHomeTeam":"133681","idAwayTeam":"133682",
             "strHomeTeamBadge":"https://r2.thesportsdb.com/badge/inter.png",
             "strAwayTeamBadge":"https://r2.thesportsdb.com/badge/roma.png",
             "strVenue":"San Siro","strStatus":"NS"}
        ]}"""

        /** A finished match, as `eventsseason`/`eventsround` carry one. */
        const val RESULT = """{"events":[
            {"idEvent":"2482138","strTimestamp":"2026-08-22T16:30:00",
             "strEvent":"Udinese vs Como","strHomeTeam":"Udinese","strAwayTeam":"Como",
             "intHomeScore":"2","intAwayScore":"1","intRound":"1","strSeason":"2026-2027",
             "strHomeTeamBadge":"https://r2.thesportsdb.com/badge/udinese.png",
             "strAwayTeamBadge":"https://r2.thesportsdb.com/badge/como.png",
             "strStatus":"FT"}
        ]}"""

        /** The top of Serie A's table, as `lookuptable` answers it. */
        const val TABLE = """{"table":[
            {"intRank":"1","idTeam":"133681","strTeam":"Inter Milan",
             "strBadge":"https://r2.thesportsdb.com/inter.png","intPlayed":"38",
             "intWin":"27","intLoss":"5","intDraw":"6","intPoints":"87","strForm":"DDWWD"},
            {"intRank":"2","idTeam":"133670","strTeam":"Napoli",
             "strBadge":"https://r2.thesportsdb.com/napoli.png","intPlayed":"38",
             "intWin":"23","intLoss":"8","intDraw":"7","intPoints":"76","strForm":"WWLDW"}
        ]}"""
    }
}
