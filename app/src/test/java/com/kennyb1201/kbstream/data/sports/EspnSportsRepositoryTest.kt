package com.kennyb1201.kbstream.data.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ESPN's payload read into the hub's models.
 *
 * The parse is the one part of the hub that cannot be reasoned about from the
 * app's own behaviour - it is somebody else's JSON, and it has already changed
 * shape once per league over the years. The cases below are the ones a card
 * visibly depends on: which side is home, whether a score exists yet, the state
 * word, the logo, and the network that bridges a game to the playlist.
 */
class EspnSportsRepositoryTest {

    // ── State and time ──────────────────────────────────────────────

    @Test
    fun `ESPN's state words map onto the hub's three`() {
        assertEquals(GameState.LIVE, espnGameState("in"))
        assertEquals(GameState.UPCOMING, espnGameState("pre"))
        assertEquals(GameState.FINAL, espnGameState("post"))
        assertEquals(GameState.LIVE, espnGameState("IN"))
    }

    @Test
    fun `an unknown state reads as upcoming rather than being dropped`() {
        // A schedule entry this build does not understand is still a game worth
        // listing; hiding it would look like a missing game, not a new state.
        assertEquals(GameState.UPCOMING, espnGameState("suspended"))
        assertEquals(GameState.UPCOMING, espnGameState(null))
    }

    @Test
    fun `an ISO date parses to epoch millis and junk reads as zero`() {
        assertEquals(1_700_000_000_000L, espnDateMs("2023-11-14T22:13:20Z"))
        assertEquals(0L, espnDateMs("tonight"))
        assertEquals(0L, espnDateMs(null))
    }

    // ── The scoreboard ──────────────────────────────────────────────

    private val scoreboard = """
        {
          "events": [
            {
              "id": "401",
              "name": "Boston Red Sox at New York Yankees",
              "date": "2023-11-15T00:30Z",
              "status": { "type": { "state": "in", "shortDetail": "Bot 5th" } },
              "competitions": [
                {
                  "competitors": [
                    {
                      "homeAway": "home",
                      "score": "3",
                      "team": {
                        "abbreviation": "NYY",
                        "displayName": "New York Yankees",
                        "logos": [{ "href": "https://a.espncdn.com/i/teamlogos/mlb/500/nyy.png" }]
                      },
                      "records": [{ "summary": "41-12" }]
                    },
                    {
                      "homeAway": "away",
                      "score": "2",
                      "team": {
                        "abbreviation": "BOS",
                        "displayName": "Boston Red Sox",
                        "logos": [{ "href": "https://a.espncdn.com/i/teamlogos/mlb/500/bos.png" }]
                      },
                      "records": [{ "summary": "33-20" }]
                    }
                  ],
                  "broadcasts": [{ "names": ["ESPN"] }]
                }
              ]
            },
            {
              "id": "402",
              "name": "Los Angeles Lakers at Boston Celtics",
              "date": "2023-11-16T00:30Z",
              "status": { "type": { "state": "pre", "shortDetail": "7:30 PM ET" } },
              "broadcasts": [{ "names": ["TNT"] }, { "names": ["NBA TV"] }],
              "competitions": [
                {
                  "competitors": [
                    {
                      "homeAway": "home",
                      "team": {
                        "abbreviation": "BOS",
                        "displayName": "Boston Celtics",
                        "logos": [{ "href": "https://a.espncdn.com/i/teamlogos/nba/500/bos.png" }]
                      }
                    },
                    {
                      "homeAway": "away",
                      "team": {
                        "abbreviation": "LAL",
                        "displayName": "Los Angeles Lakers",
                        "logos": [{ "href": "https://a.espncdn.com/i/teamlogos/nba/500/lal.png" }]
                      }
                    }
                  ]
                }
              ]
            },
            {
              "id": "403",
              "name": "Miami Heat at Denver Nuggets",
              "date": "2023-11-14T20:00Z",
              "status": { "type": { "state": "post", "shortDetail": "Final" } },
              "competitions": [
                {
                  "competitors": [
                    {
                      "homeAway": "home",
                      "score": "104",
                      "team": { "abbreviation": "DEN", "displayName": "Denver Nuggets" }
                    },
                    {
                      "homeAway": "away",
                      "score": "98",
                      "team": { "abbreviation": "MIA", "displayName": "Miami Heat" }
                    }
                  ],
                  "broadcasts": [{ "names": ["NBA TV"] }]
                }
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `a scoreboard parses every event, in ESPN's own order`() {
        val games = parseScoreboard(scoreboard, "basketball/nba")

        assertEquals(listOf("401", "402", "403"), games.map { it.id })
        assertTrue(games.all { it.league == "basketball/nba" })
    }

    @Test
    fun `a live game carries both sides, the state, the clock and the network`() {
        val game = parseScoreboard(scoreboard, "baseball/mlb").first()

        assertEquals(GameState.LIVE, game.state)
        assertEquals("Bot 5th", game.statusDetail)
        assertEquals("Boston Red Sox at New York Yankees", game.name)
        // 2023-11-15T00:30Z, in epoch millis - the card's clock reads from it.
        assertEquals(1_700_008_200_000L, game.dateMs)
        assertEquals("ESPN", game.broadcastNames.single())

        assertEquals("BOS", game.away.abbreviation)
        assertEquals("New York Yankees", game.home.displayName)
        assertEquals("https://a.espncdn.com/i/teamlogos/mlb/500/nyy.png", game.home.logoUrl)
        assertEquals("41-12", game.home.record)
        assertEquals("3", game.home.score)
        assertEquals("2", game.away.score)
    }

    @Test
    fun `an upcoming game has no scores and reads as upcoming`() {
        val game = parseScoreboard(scoreboard, "basketball/nba")[1]

        assertEquals(GameState.UPCOMING, game.state)
        assertEquals("7:30 PM ET", game.statusDetail)
        assertNull(game.away.score)
        assertNull(game.home.score)
        // Both networks listed on the EVENT, not on the competition, and both
        // kept in order: the matcher tries them one after the other.
        assertEquals(listOf("TNT", "NBA TV"), game.broadcastNames)
        assertEquals(
            "https://a.espncdn.com/i/teamlogos/nba/500/lal.png",
            game.away.logoUrl
        )
    }

    @Test
    fun `a finished game reads as final with both scores banked`() {
        val game = parseScoreboard(scoreboard, "basketball/nba")[2]

        assertEquals(GameState.FINAL, game.state)
        assertEquals("NBA TV", game.broadcastNames.single())
        assertEquals("104", game.home.score)
        assertEquals("98", game.away.score)
        // No logos in this event: the card falls back to initials rather than
        // the image loader being handed an empty URL.
        assertNull(game.home.logoUrl)
        assertNull(game.away.logoUrl)
    }

    @Test
    fun `a payload that is not a scoreboard yields no games rather than throwing`() {
        assertTrue(parseScoreboard("not json at all", "basketball/nba").isEmpty())
        assertTrue(parseScoreboard("{}", "basketball/nba").isEmpty())
        assertTrue(parseScoreboard("""{"events": []}""", "basketball/nba").isEmpty())
    }

    @Test
    fun `an event with only one side is not a game`() {
        val oneSided = """
            {
              "events": [
                {
                  "id": "900",
                  "name": "TBD at New York Yankees",
                  "date": "2023-11-15T00:30Z",
                  "status": { "type": { "state": "pre", "shortDetail": "TBD" } },
                  "competitions": [
                    {
                      "competitors": [
                        {
                          "homeAway": "home",
                          "team": { "abbreviation": "NYY", "displayName": "New York Yankees" }
                        }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        assertTrue(parseScoreboard(oneSided, "baseball/mlb").isEmpty())
    }

    @Test
    fun `an event with no id is skipped`() {
        val noId = """
            {"events": [{"name": "Ghost game", "competitions": [{"competitors": []}]}]}
        """.trimIndent()

        assertTrue(parseScoreboard(noId, "baseball/mlb").isEmpty())
    }

    // ── Head-to-head sports whose "teams" are people ────────────────

    @Test
    fun `a tennis scoreboard reads athletes as the two sides`() {
        val tennis = """
            {
              "events": [
                {
                  "id": "t1",
                  "name": "Carlos Alcaraz vs. Alexander Zverev",
                  "date": "2023-11-15T18:00Z",
                  "status": { "type": { "state": "in", "shortDetail": "2nd Set" } },
                  "competitions": [
                    {
                      "competitors": [
                        {
                          "homeAway": "home",
                          "athlete": { "displayName": "Carlos Alcaraz", "shortName": "C. Alcaraz" },
                          "score": "6 3"
                        },
                        {
                          "homeAway": "away",
                          "athlete": { "displayName": "Alexander Zverev" },
                          "score": "4 2"
                        }
                      ],
                      "broadcasts": [{ "names": ["ESPN2"] }]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val game = parseScoreboard(tennis, "tennis/atp").single()

        assertEquals(GameState.LIVE, game.state)
        assertEquals("ESPN2", game.broadcastNames.single())
        assertEquals("Carlos Alcaraz", game.home.displayName)
        assertEquals("Alexander Zverev", game.away.displayName)
        assertEquals("4 2", game.away.score)
        // No crest and no code for a person: the card prints the name's own
        // last word beside the score, exactly as a bracket would.
        assertNull(game.home.logoUrl)
        assertEquals("ALCARAZ", game.home.abbreviation)
        assertEquals("ZVEREV", game.away.abbreviation)
    }

    // ── Tournaments ─────────────────────────────────────────────────

    @Test
    fun `a golf event parses into one tournament with its top five`() {
        val golf = """
            {
              "events": [
                {
                  "id": "g1",
                  "name": "Baycurrent Classic",
                  "date": "2023-11-14T14:00Z",
                  "status": { "type": { "state": "in", "shortDetail": "Round 1 - Play Complete" } },
                  "competitions": [
                    {
                      "competitors": [
                        { "athlete": { "displayName": "Jacob Bridgeman" }, "score": "-8" },
                        { "athlete": { "displayName": "Tom Kim" }, "score": "-6" },
                        { "athlete": { "displayName": "Rory McIlroy" }, "score": "-5" },
                        { "athlete": { "displayName": "Scottie Scheffler" }, "score": "-4" },
                        { "athlete": { "displayName": "Justin Thomas" }, "score": "-3" },
                        { "athlete": { "displayName": "Rickie Fowler" }, "score": "-2" },
                        { "athlete": { "displayName": "Jordan Spieth" }, "score": "-1" }
                      ]
                    }
                  ],
                  "broadcasts": [{ "names": ["Golf Channel"] }]
                }
              ]
            }
        """.trimIndent()

        val events = parseTournamentEvents(golf, "golf/pga")

        assertEquals(1, events.size)
        val event = events.single()
        assertEquals("Baycurrent Classic", event.name)
        assertEquals(GameState.LIVE, event.state)
        assertEquals("Round 1 - Play Complete", event.statusDetail)
        assertEquals("Golf Channel", event.broadcastNames.single())

        // Five rows, best score first - the field is 150 players and the card
        // has room for the leaders.
        assertEquals(
            listOf("Jacob Bridgeman", "Tom Kim", "Rory McIlroy", "Scottie Scheffler", "Justin Thomas"),
            event.leaders.map { it.name }
        )
        assertEquals("-8", event.leaders.first().score)
    }

    @Test
    fun `a racing event with positions rather than scores still orders the grid`() {
        val f1 = """
            {
              "events": [
                {
                  "id": "f1-1",
                  "name": "Las Vegas Grand Prix",
                  "date": "2023-11-19T06:00Z",
                  "status": { "type": { "state": "pre", "shortDetail": "Sat 10:00 PM" } },
                  "competitions": [
                    {
                      "competitors": [
                        { "athlete": { "displayName": "Max Verstappen" }, "status": { "position": "1" } },
                        { "athlete": { "displayName": "Charles Leclerc" }, "status": { "position": "2" } }
                      ]
                    }
                  ],
                  "broadcasts": [{ "names": ["ESPN"] }]
                }
              ]
            }
        """.trimIndent()

        val event = parseTournamentEvents(f1, "racing/f1").single()

        assertEquals("Las Vegas Grand Prix", event.name)
        assertEquals(GameState.UPCOMING, event.state)
        assertEquals(listOf("Max Verstappen", "Charles Leclerc"), event.leaders.map { it.name })
        assertEquals("1", event.leaders.first().score)
    }

    @Test
    fun `a tournament payload that is not a scoreboard yields nothing`() {
        assertTrue(parseTournamentEvents("not json", "golf/pga").isEmpty())
        assertTrue(parseTournamentEvents("""{"events": []}""", "golf/pga").isEmpty())
    }
}
