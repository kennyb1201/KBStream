package com.kennyb1201.kbstream.data.sports

import java.io.IOException
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The game summary: what is parsed out of it, when it is fetched at all, and how
 * long a copy lasts.
 *
 * The summary is the hub's only second ESPN document, so three separate promises
 * have to hold and each is pinned here:
 *
 *  - the PARSE is per league and disappears quietly where ESPN sends nothing
 *    (that is what keeps a thinly covered league looking like today's sheet);
 *  - the FETCH policy is the spec's hard one - a game in play or one that has
 *    ended, lazily, never from a card - so an upcoming game costs no request and
 *    a finished one's box score is one tap away;
 *  - the CACHE is what makes reopening the sheet, and riding the hub's 30s live
 *    tick, free inside the minute.
 *
 * The HTTP tests run against an OkHttp interceptor rather than a server: this
 * module has no web-server harness, and the only thing being checked is which
 * document was requested and how often.
 */
class EspnGameSummaryTest {

    // ── The policy ──────────────────────────────────────────────────

    @Test
    fun `a game in play or a finished one may cost a summary request, an upcoming one may not`() {
        assertTrue(EspnSummaryRules.shouldFetch(GameState.LIVE))
        assertTrue(
            "the hub keeps a just-ended slate on the board, and the box score is" +
                " exactly what the viewer goes back into it for",
            EspnSummaryRules.shouldFetch(GameState.FINAL)
        )
        assertFalse(
            "an upcoming game has no plays and no team stats yet, so a request" +
                " would buy a round trip and nothing to draw",
            EspnSummaryRules.shouldFetch(GameState.UPCOMING)
        )
    }

    @Test
    fun `each league compares its own stats`() {
        assertEquals(
            listOf("Total Yards", "Passing", "Rushing", "Turnovers"),
            EspnSummaryRules.statPicks(EspnSport.FOOTBALL).map { it.label }
        )
        assertEquals(
            listOf("FG%", "3PT%", "Rebounds", "Turnovers"),
            EspnSummaryRules.statPicks(EspnSport.BASKETBALL).map { it.label }
        )
        assertEquals(
            listOf("Hits", "Errors", "Left on Base"),
            EspnSummaryRules.statPicks(EspnSport.BASEBALL).map { it.label }
        )
        assertEquals(
            listOf("Shots", "Hits", "Penalty Minutes"),
            EspnSummaryRules.statPicks(EspnSport.HOCKEY).map { it.label }
        )
        assertEquals(
            listOf("Possession", "Shots"),
            EspnSummaryRules.statPicks(EspnSport.SOCCER).map { it.label }
        )
        assertTrue(
            "the individual sports have no team box score to compare",
            EspnSummaryRules.statPicks(EspnSport.OTHER).isEmpty()
        )
    }

    // ── Parsing ─────────────────────────────────────────────────────

    private val nflSummary = """
        {
          "boxscore": {
            "teams": [
              {
                "homeAway": "away",
                "statistics": [
                  { "name": "totalYards", "label": "Total Yards", "displayValue": "312" },
                  { "name": "passingYards", "label": "Passing", "displayValue": "241" },
                  { "name": "rushingYards", "label": "Rushing", "displayValue": "71" },
                  { "name": "turnovers", "label": "Turnovers", "displayValue": "2" },
                  { "name": "firstDowns", "label": "1st Downs", "displayValue": "19" }
                ]
              },
              {
                "homeAway": "home",
                "statistics": [
                  { "name": "totalYards", "label": "Total Yards", "displayValue": "298" },
                  { "name": "passingYards", "label": "Passing", "displayValue": "205" },
                  { "name": "rushingYards", "label": "Rushing", "displayValue": "93" },
                  { "name": "turnovers", "label": "Turnovers", "displayValue": "1" },
                  { "name": "firstDowns", "label": "1st Downs", "displayValue": "17" }
                ]
              }
            ]
          },
          "winprobability": [
            { "homeWinPercentage": 0.42 },
            { "homeWinPercentage": 0.68 }
          ],
          "drives": {
            "current": {
              "plays": [
                { "text": "J. Allen sacked for a loss of 6" },
                { "text": "J. Allen pass to S. Diggs for 14 yds" }
              ]
            }
          }
        }
    """.trimIndent()

    @Test
    fun `an NFL summary yields the stat rows, the win chance and the last play`() {
        val summary = parseGameSummary(nflSummary, "football/nfl")

        assertEquals(
            listOf(
                Triple("Total Yards", "312", "298"),
                Triple("Passing", "241", "205"),
                Triple("Rushing", "71", "93"),
                Triple("Turnovers", "2", "1")
            ),
            summary.teamStats
        )
        assertEquals(
            "the LAST entry of winprobability, read as a percentage",
            68,
            summary.homeWinProbability
        )
        assertEquals("J. Allen pass to S. Diggs for 14 yds", summary.lastPlay)
    }

    @Test
    fun `a stat ESPN sends for only one side is dropped, never a dash`() {
        val half = nflSummary.replace(
            "{ \"name\": \"turnovers\", \"label\": \"Turnovers\", \"displayValue\": \"2\" },", ""
        )

        assertEquals(
            "the row the away side has no value for is not drawn at all",
            listOf("Total Yards", "Passing", "Rushing"),
            parseGameSummary(half, "football/nfl").teamStats.map { it.first }
        )
    }

    @Test
    fun `a soccer summary has stats and no win probability`() {
        val soccer = """
            {
              "boxscore": {
                "teams": [
                  {
                    "homeAway": "away",
                    "statistics": [
                      { "name": "possessionPct", "label": "Possession", "displayValue": "54%" },
                      { "name": "totalShots", "label": "Shots", "displayValue": "11" }
                    ]
                  },
                  {
                    "homeAway": "home",
                    "statistics": [
                      { "name": "possessionPct", "label": "Possession", "displayValue": "46%" },
                      { "name": "totalShots", "label": "Shots", "displayValue": "8" }
                    ]
                  }
                ]
              },
              "plays": [ { "text": "Corner taken short" } ]
            }
        """.trimIndent()

        val summary = parseGameSummary(soccer, "soccer/eng.1")

        assertEquals(
            listOf(
                Triple("Possession", "54%", "46%"),
                Triple("Shots", "11", "8")
            ),
            summary.teamStats
        )
        assertNull("ESPN carries no win probability for soccer", summary.homeWinProbability)
        assertEquals("Corner taken short", summary.lastPlay)
    }

    @Test
    fun `a league the hub has no stat set for yields no rows`() {
        // Tennis/MMA: the box score shape differs and there is no team table, so
        // nothing is compared - and the sheet simply has no stats section.
        val summary = parseGameSummary(nflSummary, "tennis/atp")

        assertTrue(summary.teamStats.isEmpty())
    }

    @Test
    fun `a body that is not JSON is an empty summary, never a throw`() {
        val summary = parseGameSummary("<html>502 Bad Gateway</html>", "football/nfl")

        assertTrue(summary.teamStats.isEmpty())
        assertNull(summary.homeWinProbability)
        assertNull(summary.lastPlay)
    }

    @Test
    fun `a summary with no boxscore, no winprobability and no plays is empty`() {
        val summary = parseGameSummary("""{ "header": {} }""", "football/nfl")

        assertTrue(summary.teamStats.isEmpty())
        assertNull(summary.homeWinProbability)
        assertNull(summary.lastPlay)
    }

    // ── Fetching ────────────────────────────────────────────────────

    /** Counts the documents requested and can be told to fail the next call. */
    private class CountingClient(private val body: String) {
        val paths = mutableListOf<String>()
        var failing = false

        val client: OkHttpClient = OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    paths += chain.request().url.encodedPath
                    if (failing) throw IOException("timed out")
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }
            )
            .build()

        fun summaries(): Int = paths.count { it.endsWith("/summary") }
    }

    /** One document that satisfies both parsers, so one stub serves every call. */
    private val stubbedBody = """
        {
          "events": [
            {
              "id": "401",
              "name": "Buffalo Bills at New England Patriots",
              "date": "2024-11-17T18:00Z",
              "status": { "type": { "state": "in", "shortDetail": "Q3 4:32" } },
              "competitions": [
                {
                  "competitors": [
                    {
                      "homeAway": "home",
                      "score": "17",
                      "team": { "abbreviation": "NE", "displayName": "Patriots" }
                    },
                    {
                      "homeAway": "away",
                      "score": "21",
                      "team": { "abbreviation": "BUF", "displayName": "Bills" }
                    }
                  ]
                }
              ]
            }
          ],
          "boxscore": {
            "teams": [
              {
                "homeAway": "away",
                "statistics": [
                  { "name": "totalYards", "label": "Total Yards", "displayValue": "312" }
                ]
              },
              {
                "homeAway": "home",
                "statistics": [
                  { "name": "totalYards", "label": "Total Yards", "displayValue": "298" }
                ]
              }
            ]
          },
          "winprobability": [ { "homeWinPercentage": 0.68 } ],
          "plays": [ { "text": "BUF 21, NE 17" } ]
        }
    """.trimIndent()

    @Test
    fun `the repository caches a summary for the live minute`() {
        var now = 1_000L
        val http = CountingClient(stubbedBody)
        val repo = EspnSportsRepository(client = http.client, clock = { now })

        runBlocking {
            val first = repo.gameSummary("football/nfl", "401")
            assertEquals(68, first?.homeWinProbability)
            // Reopening the sheet inside the TTL: no request.
            repo.gameSummary("football/nfl", "401")
            assertEquals(1, http.summaries())

            // The live tick past the TTL asks again.
            now += 61_000L
            repo.gameSummary("football/nfl", "401")
            assertEquals(2, http.summaries())
        }
    }

    @Test
    fun `reading a scoreboard never asks for a summary`() {
        val http = CountingClient(stubbedBody)
        val repo = EspnSportsRepository(client = http.client, clock = { 1_000L })

        runBlocking {
            val games = repo.scoreboard("football/nfl")
            assertEquals(1, games.size)
            assertEquals("the scoreboard is the only document a card costs", 0, http.summaries())
            assertTrue(http.paths.all { it.endsWith("/scoreboard") })
        }
    }

    @Test
    fun `a failed summary is null, never an exception, and keeps the last good copy`() {
        var now = 1_000L
        val http = CountingClient(stubbedBody)
        val repo = EspnSportsRepository(client = http.client, clock = { now })

        runBlocking {
            http.failing = true
            assertNull("nothing cached yet, so nothing to show", repo.gameSummary("football/nfl", "401"))
            assertTrue(repo.lastSummaryFetchFailed("401"))

            http.failing = false
            now += 61_000L
            assertTrue(repo.gameSummary("football/nfl", "401") != null)
            assertFalse(repo.lastSummaryFetchFailed("401"))

            // Past the TTL with the feed failing: the sheet keeps the stats it
            // was already drawing rather than blinking them away.
            now += 61_000L
            http.failing = true
            val stale = repo.gameSummary("football/nfl", "401")
            assertEquals(68, stale?.homeWinProbability)
        }
    }

    @Test
    fun `an empty event id costs no request`() {
        val http = CountingClient(stubbedBody)
        val repo = EspnSportsRepository(client = http.client, clock = { 1_000L })

        runBlocking {
            assertNull(repo.gameSummary("football/nfl", ""))
            assertEquals(0, http.summaries())
        }
    }
}
