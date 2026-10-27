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
    fun `a stale live word is overruled once the event's own end is hours past`() {
        // The reported bug: ESPN's golf feed stays "in" for days, so the hub
        // showed the Baycurrent Classic as LIVE 47 hours after it finished. Past
        // the buffer an "in" is read as FINAL whatever the feed says.
        val end = NOW - 47 * hour
        assertEquals(GameState.FINAL, espnGameState("in", eventEndMs = end, nowMs = NOW))
        // And it applies to games, not just tournaments - a box score does not
        // stay live either, and the same rule reads both.
        assertEquals(GameState.FINAL, espnGameState("in", eventEndMs = NOW - 7 * hour, nowMs = NOW))
    }

    @Test
    fun `a live word inside the buffer is left alone`() {
        // A game that ran long, or an end time the feed wrote a fraction early:
        // within the buffer the state word still rules, so the card keeps its
        // live clock rather than flipping early.
        assertEquals(GameState.LIVE, espnGameState("in", eventEndMs = NOW - 5 * hour, nowMs = NOW))
        // And a tournament on right now - end time still ahead - is LIVE.
        assertEquals(GameState.LIVE, espnGameState("in", eventEndMs = NOW + 30 * hour, nowMs = NOW))
    }

    @Test
    fun `a suspended game stops reading as live`() {
        // Audit C4's remaining half: a league game carries no endDate, so the
        // clock check above has nothing to compare, and ESPN keeps the state
        // word "in" for as long as a suspended game sits there - the hub listed
        // it under LIVE NOW indefinitely.
        assertEquals(
            GameState.UPCOMING,
            espnGameState("in", eventEndMs = 0L, nowMs = NOW, feedDetail = "Suspended")
        )
        // The same fact in the shapes ESPN writes it.
        assertEquals(GameState.UPCOMING, espnGameState("in", feedDetail = "Suspend. Bottom 6th"))
        assertEquals(GameState.UPCOMING, espnGameState("in", feedDetail = "Postponed"))
        assertEquals(GameState.UPCOMING, espnGameState("in", feedDetail = "Canceled"))
    }

    @Test
    fun `a rain delay is still a live game`() {
        // The guard must not fire on the one stoppage that IS a game in
        // progress: reading a delay as not-in-play would drop it out of LIVE NOW
        // during a rain shower, which is the opposite fault.
        assertEquals(GameState.LIVE, espnGameState("in", feedDetail = "Delayed"))
        assertEquals(GameState.LIVE, espnGameState("in", feedDetail = "Rain Delay"))
        // And a feed that says nothing keeps the word, as it always did.
        assertEquals(GameState.LIVE, espnGameState("in", feedDetail = ""))
    }

    @Test
    fun `a suspended scoreboard event parses as not live, with its own status line`() {
        val suspended = """
            {
              "events": [
                {
                  "id": "777",
                  "name": "Yankees at Red Sox",
                  "date": "2026-10-24T00:10Z",
                  "status": {
                    "period": 6,
                    "type": { "state": "in", "detail": "Suspended", "completed": false }
                  },
                  "competitions": [
                    {
                      "competitors": [
                        { "homeAway": "home", "score": "3", "team": { "abbreviation": "BOS", "displayName": "Boston Red Sox" } },
                        { "homeAway": "away", "score": "2", "team": { "abbreviation": "NYY", "displayName": "New York Yankees" } }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val game = parseScoreboard(suspended, "baseball/mlb").single()

        assertEquals(GameState.UPCOMING, game.state)
        assertEquals("Suspended", game.statusDetail)
        // Nothing else about the fixture is lost: the score it stopped at stays
        // on the card, which is the only record a suspended game has.
        assertEquals("2", game.away.score)
        assertEquals("3", game.home.score)
    }

    @Test
    fun `no end time leaves the state word as the only signal`() {
        // Most US league events carry no endDate at all: the sanity check must
        // stay out of the way rather than reading a live game as final because
        // there is nothing to compare against.
        assertEquals(GameState.LIVE, espnGameState("in", eventEndMs = 0L, nowMs = NOW))
        assertEquals(GameState.FINAL, espnGameState("post", eventEndMs = 0L, nowMs = NOW))
    }

    @Test
    fun `a tournament carries its end time into the state`() {
        // ESPN dates a multi-day tournament with date..endDate; reading only the
        // start left the sanity check with nothing to compare against, which is
        // how a finished tournament kept its LIVE badge.
        val golf = """
            {
              "events": [
                {
                  "id": "g3",
                  "name": "Baycurrent Classic",
                  "date": "2020-10-08T04:00Z",
                  "endDate": "2020-10-11T04:00Z",
                  "status": { "type": { "state": "in", "shortDetail": "Final Round" } },
                  "competitions": [ { "competitors": [] } ]
                }
              ]
            }
        """.trimIndent()

        val event = parseTournamentEvents(golf, "golf/pga").single()

        // 2020 is far past the six-hour buffer: the stale "in" reads FINAL.
        assertEquals(GameState.FINAL, event.state)
    }

    private val hour = 60L * 60_000L
    private val NOW = 1_760_000_000_000L

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
    fun `a game whose networks live only in geoBroadcasts still names them`() {
        // A number of leagues ship the per-market geo listing INSTEAD of
        // `broadcasts`. Reading only the first shape left the network tier dead
        // for every game in them - the card said "Not in your playlist" while
        // the payload named the network plainly (audit C7, 2026-10-23).
        val geo = """
            {
              "events": [
                {
                  "id": "501",
                  "name": "Utah Jazz at Denver Nuggets",
                  "date": "2023-11-15T02:00Z",
                  "status": { "type": { "state": "pre", "shortDetail": "9:00 PM ET" } },
                  "competitions": [
                    {
                      "competitors": [
                        {
                          "homeAway": "away",
                          "team": { "abbreviation": "UTAH", "displayName": "Utah Jazz" }
                        },
                        {
                          "homeAway": "home",
                          "team": { "abbreviation": "DEN", "displayName": "Denver Nuggets" }
                        }
                      ],
                      "geoBroadcasts": [
                        {
                          "type": { "id": "1", "shortName": "TV" },
                          "market": { "id": "1", "type": "Home" },
                          "media": { "shortName": "Altitude", "name": "Altitude Sports" }
                        }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val game = parseScoreboard(geo, "basketball/nba").single()

        // Short name first, then the long one: both are the same network, and
        // the matcher reads them as the alternatives they are.
        assertEquals(listOf("Altitude", "Altitude Sports"), game.broadcastNames)
    }

    @Test
    fun `a bare broadcast string is read, and a broadcast object is not mistaken for one`() {
        // The summary endpoint's header carries `broadcast` as a plain STRING.
        // Some shapes carry an OBJECT under the same key, and optString answers
        // the stringified object for it - not a network name, and not something
        // the matcher should ever be handed.
        val stringShape = """
            {
              "events": [
                {
                  "id": "601",
                  "name": "Boston Celtics at New York Knicks",
                  "date": "2023-11-15T02:00Z",
                  "status": { "type": { "state": "pre", "shortDetail": "7:30 PM ET" } },
                  "broadcast": "ABC",
                  "competitions": [
                    {
                      "competitors": [
                        {
                          "homeAway": "away",
                          "team": { "abbreviation": "BOS", "displayName": "Boston Celtics" }
                        },
                        {
                          "homeAway": "home",
                          "team": { "abbreviation": "NY", "displayName": "New York Knicks" }
                        }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()
        val objectShape = stringShape
            .replace("\"broadcast\": \"ABC\"", "\"broadcast\": { \"shortName\": \"ABC\" }")

        assertEquals(
            listOf("ABC"),
            parseScoreboard(stringShape, "basketball/nba").single().broadcastNames
        )
        assertTrue(
            "an object under `broadcast` names no network",
            parseScoreboard(objectShape, "basketball/nba").single().broadcastNames.isEmpty()
        )
    }

    // ── The AP Top 25 ranking (college sports) ─────────────────────

    @Test
    fun `a ranked college side carries its ranking, and an unranked one does not`() {
        // ESPN publishes the ranking on the COMPETITOR: `curatedRank` is a
        // sibling of `team` in the scoreboard's JSON, not a field inside it -
        // and an unranked side is the sentinel 99 rather than a missing key, so
        // a number only becomes a rank when it is inside the poll.
        val college = """
            {
              "events": [
                {
                  "id": "700",
                  "name": "Georgia Bulldogs at Texas Longhorns",
                  "date": "2024-11-16T20:00Z",
                  "status": { "type": { "state": "pre", "shortDetail": "Sat 3:30 PM" } },
                  "competitions": [
                    {
                      "competitors": [
                        {
                          "homeAway": "home",
                          "curatedRank": { "current": 5 },
                          "team": { "abbreviation": "TEX", "displayName": "Texas Longhorns" },
                          "records": [{ "summary": "8-0" }]
                        },
                        {
                          "homeAway": "away",
                          "curatedRank": { "current": 99 },
                          "team": { "abbreviation": "UGA", "displayName": "Georgia Bulldogs" },
                          "records": [{ "summary": "4-0" }]
                        }
                      ]
                    }
                  ]
                },
                {
                  "id": "701",
                  "name": "Kansas Jayhawks at Houston Cougars",
                  "date": "2024-11-16T21:00Z",
                  "status": { "type": { "state": "pre", "shortDetail": "Sat 4:30 PM" } },
                  "competitions": [
                    {
                      "competitors": [
                        {
                          "homeAway": "home",
                          "team": { "abbreviation": "HOU", "displayName": "Houston Cougars" }
                        },
                        {
                          "homeAway": "away",
                          "curatedRank": { "current": 1 },
                          "team": { "abbreviation": "KU", "displayName": "Kansas Jayhawks" }
                        }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val games = parseScoreboard(college, "football/college-football")

        assertEquals(5, games[0].home.rank)
        // 99 is the feed's "outside the poll", not the 99th-best team.
        assertNull(games[0].away.rank)
        // A side the feed says nothing about is unranked too, and the rank sits
        // on the competitor rather than on the team it belongs to.
        assertNull(games[1].home.rank)
        assertEquals(1, games[1].away.rank)
        // The record rides along untouched: the card prints "#5 TEX · 8-0".
        assertEquals("8-0", games[0].home.record)
    }

    @Test
    fun `a pro scoreboard, and a sport whose sides are people, carry no ranking`() {
        // Nothing here has an AP poll to be in. If a pro feed ever did send a
        // rank, the card would print it - which is why this is asserted rather
        // than assumed.
        val mlb = parseScoreboard(scoreboard, "baseball/mlb").first()
        assertNull(mlb.home.rank)
        assertNull(mlb.away.rank)
        val tennis = parseScoreboard(
            """
            {
              "events": [
                {
                  "id": "710",
                  "name": "Carlos Alcaraz vs Jannik Sinner",
                  "date": "2024-11-16T20:00Z",
                  "status": { "type": { "state": "pre", "shortDetail": "Not started" } },
                  "competitions": [
                    {
                      "competitors": [
                        { "homeAway": "home", "athlete": { "displayName": "Jannik Sinner" } },
                        { "homeAway": "away", "athlete": { "displayName": "Carlos Alcaraz" } }
                      ]
                    }
                  ]
                }
              ]
            }
            """.trimIndent(),
            "tennis/atp"
        ).single()
        assertNull(tennis.home.rank)
        assertNull(tennis.away.rank)
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
    fun `a card with no home-away flags keeps its two sides, in the feed's order`() {
        // A fight has no home side, and ESPN then sends no `homeAway` at all.
        // Both competitors used to fall to `away`, `home` stayed null and the
        // event was dropped - so a league whose payload looks like this parsed
        // to nothing while the fetch counted as a success, with no error state
        // to show for it (audit C2).
        val unflagged = """
            {
              "events": [
                {
                  "id": "930",
                  "name": "UFC 400: Main Card",
                  "date": "2024-11-16T23:00Z",
                  "status": { "type": { "state": "pre", "shortDetail": "7:00 PM" } },
                  "competitions": [
                    {
                      "competitors": [
                        { "athlete": { "displayName": "Alex Pereira" } },
                        { "athlete": { "displayName": "Magomed Ankalaev" } }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val fight = parseScoreboard(unflagged, "mma/ufc").single()
        // No flags: the feed's order carries the sides - first away, second home.
        assertEquals("Alex Pereira", fight.away.displayName)
        assertEquals("Magomed Ankalaev", fight.home.displayName)
    }

    @Test
    fun `one named side still leaves the other filled from the feed's order`() {
        // Half-flagged is the same failure one step in: the unflagged side is
        // the one the feed left unnamed, not a side that does not exist.
        val halfFlagged = """
            {
              "events": [
                {
                  "id": "931",
                  "name": "Semi-final",
                  "date": "2024-11-16T23:00Z",
                  "status": { "type": { "state": "pre", "shortDetail": "7:00 PM" } },
                  "competitions": [
                    {
                      "competitors": [
                        { "athlete": { "displayName": "Carlos Alcaraz" } },
                        { "homeAway": "home", "athlete": { "displayName": "Jannik Sinner" } }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val match = parseScoreboard(halfFlagged, "tennis/atp").single()
        assertEquals("Carlos Alcaraz", match.away.displayName)
        assertEquals("Jannik Sinner", match.home.displayName)
    }

    @Test
    fun `the inning comes from the status when the competition has no details block`() {
        // Audit C6: only `competitions[0].details.inning` was read, so on a feed
        // that keeps the inning on `status.period` the situation line never
        // rendered for any MLB game - and silence reads as "no situation".
        val noDetails = """
            {
              "events": [
                {
                  "id": "940",
                  "name": "Boston Red Sox at New York Yankees",
                  "date": "2024-06-08T23:00Z",
                  "status": {
                    "period": 7,
                    "type": { "state": "in", "shortDetail": "Bot 7th" }
                  },
                  "competitions": [
                    {
                      "competitors": [
                        { "homeAway": "away", "team": { "abbreviation": "BOS", "displayName": "Boston Red Sox" } },
                        { "homeAway": "home", "team": { "abbreviation": "NYY", "displayName": "New York Yankees" } }
                      ],
                      "details": { "outs": 2 }
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val fromStatus = parseScoreboard(noDetails, "baseball/mlb").single()
        assertEquals("Bot 7th · 2 outs", fromStatus.situation)

        // The details block still wins where it exists: it is the feed's own
        // inning, and the status period is only the fallback.
        assertEquals(
            "Top 5th · 1 out",
            espnSituation(
                state = GameState.LIVE,
                sport = EspnSport.BASEBALL,
                competition = org.json.JSONObject(
                    """{"details":{"inning":5,"outs":1}}"""
                ),
                statusDetail = "Top 5th",
                period = 7,
            )?.line
        )
    }

    @Test
    fun `a card with several competitions becomes one game per bout`() {
        // Audit C3: only `competitions[0]` was read. UFC 333 ships fourteen
        // bouts, so the hub drew the first prelim under the card's own title and
        // the other thirteen did not exist at all.
        val card = """
            {
              "events": [
                {
                  "id": "600",
                  "name": "UFC 333: Volkanovski vs. Evloev",
                  "date": "2026-10-24T14:00Z",
                  "status": { "type": { "state": "pre", "shortDetail": "7:00 PM" } },
                  "competitions": [
                    {
                      "id": "c1",
                      "date": "2026-10-24T14:00Z",
                      "competitors": [
                        { "athlete": { "displayName": "Nicolle Caliari" } },
                        { "athlete": { "displayName": "Iasmin Lucindo" } }
                      ]
                    },
                    {
                      "id": "c2",
                      "date": "2026-10-24T22:00Z",
                      "competitors": [
                        { "athlete": { "displayName": "Alexander Volkanovski" } },
                        { "athlete": { "displayName": "Movsar Evloev" } }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val bouts = parseScoreboard(card, "mma/ufc")

        assertEquals(2, bouts.size)
        assertEquals(listOf("600:c1", "600:c2"), bouts.map { it.id })
        assertTrue(
            "every bout names the same EVENT, which is what its summary is fetched by",
            bouts.all { it.eventId == "600" }
        )
        assertEquals("Alexander Volkanovski", bouts[1].away.displayName)
        assertEquals("Movsar Evloev", bouts[1].home.displayName)
        assertEquals(
            "and each bout keeps its own start, not the card's first bell",
            espnDateMs("2026-10-24T22:00Z"),
            bouts[1].dateMs
        )
    }

    @Test
    fun `a competition carrying a field is not a game`() {
        // A racing session's twenty-two drivers are a field, not a fixture: the
        // fan-out must not invent a two-sided game out of a grid.
        val field = """
            {
              "events": [
                {
                  "id": "700",
                  "name": "United States Grand Prix",
                  "date": "2026-10-23T17:30Z",
                  "status": { "type": { "state": "post", "shortDetail": "Final" } },
                  "competitions": [
                    {
                      "id": "s1",
                      "competitors": [
                        { "athlete": { "displayName": "Max Verstappen" } },
                        { "athlete": { "displayName": "Lando Norris" } },
                        { "athlete": { "displayName": "Charles Leclerc" } }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        assertTrue(parseScoreboard(field, "racing/f1").isEmpty())
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

    @Test
    fun `a tennis tournament reads its fixtures out of the event's groupings`() {
        // ESPN ships a tournament as ONE event with no `competitions` of its own:
        // each draw (Men's Singles, Women's Singles) is a grouping holding that
        // draw's matches. Reading only the top-level array found no game at all,
        // and a body whose events all yield nothing is the STRICT parse's
        // failure - so the Tennis tab drew "Couldn't reach ESPN" over a healthy
        // response (HTTP 200, the whole ATP field).
        val atp = """
            {
              "events": [
                {
                  "id": "10-2026",
                  "name": "Erste Bank Open",
                  "date": "2026-10-24T04:00Z",
                  "groupings": [
                    {
                      "grouping": { "slug": "mens-singles", "displayName": "Men's Singles" },
                      "competitions": [
                        {
                          "id": "187149",
                          "competitors": [
                            { "homeAway": "home", "athlete": { "displayName": "Jannik Sinner" } },
                            { "homeAway": "away", "athlete": { "displayName": "Alexander Zverev" } }
                          ]
                        },
                        {
                          "id": "187150",
                          "competitors": [
                            { "homeAway": "home", "athlete": { "displayName": "Carlos Alcaraz" } },
                            { "homeAway": "away", "athlete": { "displayName": "Novak Djokovic" } }
                          ]
                        }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val games = parseScoreboard(atp, "tennis/atp")

        assertEquals(2, games.size)
        assertEquals("Jannik Sinner", games[0].home.displayName)
        assertEquals("Carlos Alcaraz", games[1].home.displayName)
        // Each fixture keeps its own competition id, so two matches of one
        // tournament are two games rather than one shared card.
        assertEquals("10-2026:187149", games[0].id)
        assertEquals("10-2026:187150", games[1].id)
        // And the body is a scoreboard, not a fault: the tab shows the games.
        assertTrue(parseScoreboardOrNull(atp, "tennis/atp") != null)
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
    fun `a racing weekend's card is about the session that is on, not the first one`() {
        // Audit C3, tournaments: a Grand Prix is ONE event with five
        // competitions. Reading `[0]` put Friday practice's order on the race
        // card.
        val weekend = """
            {
              "events": [
                {
                  "id": "f1-weekend",
                  "name": "United States Grand Prix",
                  "date": "2026-10-23T17:30Z",
                  "status": { "type": { "state": "in", "shortDetail": "Race" } },
                  "competitions": [
                    { "id": "p1", "date": "2026-10-23T17:30Z", "endDate": "2026-10-23T18:30Z",
                      "competitors": [ { "athlete": { "displayName": "Practice Leader" }, "status": { "position": "1" } } ] },
                    { "id": "q1", "date": "2026-10-24T21:00Z", "endDate": "2026-10-24T22:00Z",
                      "competitors": [ { "athlete": { "displayName": "Qualifying Leader" }, "status": { "position": "1" } } ] },
                    { "id": "r1", "date": "2026-10-25T20:00Z", "endDate": "2026-10-25T22:00Z",
                      "competitors": [ { "athlete": { "displayName": "Race Leader" }, "status": { "position": "1" } } ] }
                  ]
                }
              ]
            }
        """.trimIndent()

        // During qualifying: the session on now is the one the card shows.
        val duringQualifying = parseTournamentEvents(
            weekend,
            "racing/f1",
            nowMs = espnDateMs("2026-10-24T21:30Z")
        ).single()
        assertEquals(listOf("Qualifying Leader"), duringQualifying.leaders.map { it.name })

        // After the race: the most recent session, not the first.
        val afterTheRace = parseTournamentEvents(
            weekend,
            "racing/f1",
            nowMs = espnDateMs("2026-10-26T12:00Z")
        ).single()
        assertEquals(listOf("Race Leader"), afterTheRace.leaders.map { it.name })

        // Before the weekend: the first session due.
        val beforeTheWeekend = parseTournamentEvents(
            weekend,
            "racing/f1",
            nowMs = espnDateMs("2026-10-22T12:00Z")
        ).single()
        assertEquals(listOf("Practice Leader"), beforeTheWeekend.leaders.map { it.name })
    }

    @Test
    fun `a tournament payload that is not a scoreboard yields nothing`() {
        assertTrue(parseTournamentEvents("not json", "golf/pga").isEmpty())
        assertTrue(parseTournamentEvents("""{"events": []}""", "golf/pga").isEmpty())
    }

    @Test
    fun `a golf leaderboard carries each row's country flag`() {
        val golf = """
            {
              "events": [
                {
                  "id": "g2",
                  "name": "Baycurrent Classic",
                  "date": "2023-11-14T14:00Z",
                  "status": { "type": { "state": "in", "shortDetail": "Round 1 - Play Complete" } },
                  "competitions": [
                    {
                      "competitors": [
                        {
                          "athlete": {
                            "displayName": "Tom Kim",
                            "flag": { "href": "https://a.espncdn.com/i/teamlogos/countries/500/kor.png" }
                          },
                          "score": "-6"
                        },
                        { "athlete": { "displayName": "Rory McIlroy" }, "score": "-5" }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val leaders = parseTournamentEvents(golf, "golf/pga").single().leaders

        assertEquals(
            "https://a.espncdn.com/i/teamlogos/countries/500/kor.png",
            leaders.first().flagUrl
        )
        // No flag in the feed is not a missing flag on the card: the row draws
        // without one rather than reserving an empty plate.
        assertNull(leaders[1].flagUrl)
    }

    // ── The marks: crests, heads and flags ──────────────────────────

    @Test
    fun `the scoreboard's own logo field is the crest the card draws`() {
        // The LIVE scoreboard carries a club's mark as `logo` - a single string
        // - and carries no `logos` array at all; `logos` is what the detail
        // pages use. Reading only that array left every card printing initials
        // where a crest belonged.
        val withLogo = """
            {
              "events": [
                {
                  "id": "501",
                  "name": "Boston Celtics at Los Angeles Lakers",
                  "date": "2023-11-15T03:00Z",
                  "status": { "type": { "state": "pre", "shortDetail": "10:00 PM ET" } },
                  "competitions": [
                    {
                      "competitors": [
                        {
                          "homeAway": "home",
                          "team": {
                            "abbreviation": "LAL",
                            "displayName": "Los Angeles Lakers",
                            "logo": "https://a.espncdn.com/i/teamlogos/nba/500/lal.png",
                            "color": "552583"
                          }
                        },
                        {
                          "homeAway": "away",
                          "team": {
                            "abbreviation": "BOS",
                            "displayName": "Boston Celtics",
                            "logo": "https://a.espncdn.com/i/teamlogos/nba/500/bos.png",
                            "color": "007a33"
                          }
                        }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val game = parseScoreboard(withLogo, "basketball/nba").single()

        assertEquals("https://a.espncdn.com/i/teamlogos/nba/500/lal.png", game.home.logoUrl)
        assertEquals("https://a.espncdn.com/i/teamlogos/nba/500/bos.png", game.away.logoUrl)
        // ...and the club's own colour rides along, for the plate behind it.
        assertEquals("552583", game.home.colorHex)
    }

    @Test
    fun `a person with no crest falls back to a headshot, then to their flag`() {
        val people = """
            {
              "events": [
                {
                  "id": "502",
                  "name": "Carlos Alcaraz vs. Alexander Zverev",
                  "date": "2023-11-15T18:00Z",
                  "status": { "type": { "state": "in", "shortDetail": "2nd Set" } },
                  "competitions": [
                    {
                      "competitors": [
                        {
                          "homeAway": "home",
                          "athlete": {
                            "displayName": "Carlos Alcaraz",
                            "headshot": { "href": "https://a.espncdn.com/i/headshots/tennis/players/full/900.png" }
                          }
                        },
                        {
                          "homeAway": "away",
                          "athlete": {
                            "displayName": "Alexander Zverev",
                            "flag": { "href": "https://a.espncdn.com/i/teamlogos/countries/500/ger.png" }
                          }
                        }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val game = parseScoreboard(people, "tennis/atp").single()

        assertEquals(
            "https://a.espncdn.com/i/headshots/tennis/players/full/900.png",
            game.home.logoUrl
        )
        assertEquals(
            "https://a.espncdn.com/i/teamlogos/countries/500/ger.png",
            game.away.logoUrl
        )
        // A person's plate keeps the plain raised surface: `color` belongs to
        // clubs, and ESPN carries none for an athlete.
        assertNull(game.home.colorHex)
    }

    @Test
    fun `the venue and ESPN's own note are read onto the card`() {
        val detailed = """
            {
              "events": [
                {
                  "id": "503",
                  "name": "Boston Red Sox at New York Yankees",
                  "date": "2023-11-15T00:30Z",
                  "status": { "type": { "state": "in", "shortDetail": "Bot 5th" } },
                  "competitions": [
                    {
                      "venue": {
                        "fullName": "Yankee Stadium",
                        "address": { "city": "Bronx", "state": "NY" }
                      },
                      "notes": [{ "headline": "ALDS - Game 4" }],
                      "competitors": [
                        {
                          "homeAway": "home",
                          "score": "3",
                          "team": { "abbreviation": "NYY", "displayName": "New York Yankees" }
                        },
                        {
                          "homeAway": "away",
                          "score": "2",
                          "team": { "abbreviation": "BOS", "displayName": "Boston Red Sox" }
                        }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val game = parseScoreboard(detailed, "baseball/mlb").single()

        assertEquals("Yankee Stadium · Bronx, NY", game.venue)
        assertEquals("ALDS - Game 4", game.note)
    }

    @Test
    fun `a venue that is only a name, and a note that is only a sentence, still read`() {
        val tour = """
            {
              "events": [
                {
                  "id": "504",
                  "name": "Boston Celtics at Denver Nuggets",
                  "date": "2023-11-16T02:00Z",
                  "status": { "type": { "state": "pre", "shortDetail": "9:00 PM ET" } },
                  "venue": { "displayName": "Denver, CO" },
                  "competitions": [
                    {
                      "notes": [{ "text": "Preseason" }],
                      "competitors": [
                        {
                          "homeAway": "home",
                          "team": { "abbreviation": "DEN", "displayName": "Denver Nuggets" }
                        },
                        {
                          "homeAway": "away",
                          "team": { "abbreviation": "BOS", "displayName": "Boston Celtics" }
                        }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val game = parseScoreboard(tour, "basketball/nba").single()

        assertEquals("Denver, CO", game.venue)
        assertEquals("Preseason", game.note)
    }

    @Test
    fun `a playoff series is read from the competition's own summary`() {
        // The World Series as the feed sends it: the game itself is 0-0 before
        // first pitch, and only the series says Milwaukee is two games up. Same
        // shape in every team sport that plays one (an NBA round, a Stanley Cup
        // Final), which is why nothing here is league-specific.
        val postseason = """
            {
              "events": [
                {
                  "id": "401",
                  "name": "Milwaukee Brewers at Tampa Bay Rays",
                  "date": "2026-10-26T00:00Z",
                  "competitions": [
                    {
                      "notes": [{ "headline": "World Series - Game 3" }],
                      "series": {
                        "type": "playoff",
                        "title": "Playoff Series",
                        "summary": "MIL leads series 2-0",
                        "completed": false,
                        "totalCompetitions": 7,
                        "competitors": [
                          { "id": "8", "wins": 2 },
                          { "id": "30", "wins": 0 }
                        ]
                      },
                      "competitors": [
                        {
                          "homeAway": "home",
                          "team": { "abbreviation": "TB", "displayName": "Tampa Bay Rays" }
                        },
                        {
                          "homeAway": "away",
                          "team": { "abbreviation": "MIL", "displayName": "Milwaukee Brewers" }
                        }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val game = parseScoreboard(postseason, "baseball/mlb").single()

        assertEquals("MIL leads series 2-0", game.series)
        assertEquals("World Series - Game 3", game.note)
    }

    @Test
    fun `a regular-season game has no series line`() {
        // No series object at all, and a series object with no summary, are the
        // same answer: nothing to draw. The card must not invent a 0-0 series.
        val regular = """
            {
              "events": [
                {
                  "id": "402",
                  "name": "Boston Red Sox at New York Yankees",
                  "date": "2026-06-01T23:05Z",
                  "competitions": [
                    {
                      "competitors": [
                        {
                          "homeAway": "home",
                          "team": { "abbreviation": "NYY", "displayName": "New York Yankees" }
                        },
                        {
                          "homeAway": "away",
                          "team": { "abbreviation": "BOS", "displayName": "Boston Red Sox" }
                        }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        assertNull(parseScoreboard(regular, "baseball/mlb").single().series)
        assertNull(espnSeries(null))
        assertNull(espnSeries(org.json.JSONObject("{ \"summary\": \"  \" }")))
    }

    @Test
    fun `a side is keyed by ESPN's own id, which is what a favourite is stored under`() {
        val withIds = """
            {
              "events": [
                {
                  "id": "701",
                  "name": "Tampa Bay Buccaneers at New Orleans Saints",
                  "date": "2023-10-15T17:00Z",
                  "status": { "type": { "state": "pre", "shortDetail": "Thu, 8:15 PM" } },
                  "competitions": [
                    {
                      "competitors": [
                        {
                          "homeAway": "home",
                          "team": {
                            "id": "18",
                            "abbreviation": "NO",
                            "displayName": "New Orleans Saints"
                          }
                        },
                        {
                          "homeAway": "away",
                          "team": {
                            "id": "27",
                            "abbreviation": "TB",
                            "displayName": "Tampa Bay Buccaneers"
                          }
                        }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val game = parseScoreboard(withIds, "football/nfl").single()

        assertEquals("27", game.away.id)
        assertEquals("18", game.home.id)
        // The id is the key, not the name: a club can be renamed without the
        // viewer losing the team they follow - and the league it was read from
        // is the key's scope, since an id is unique only within its league.
        assertEquals("football/nfl:27", game.away.favoriteKey)
        assertEquals("football/nfl:18", game.home.favoriteKey)
    }

    @Test
    fun `a person's ESPN id is read too, and their name is the fallback key`() {
        val tennis = """
            {
              "events": [
                {
                  "id": "t9",
                  "name": "Carlos Alcaraz vs. Alexander Zverev",
                  "date": "2023-11-15T18:00Z",
                  "status": { "type": { "state": "pre", "shortDetail": "12:00 PM" } },
                  "competitions": [
                    {
                      "competitors": [
                        {
                          "homeAway": "home",
                          "athlete": { "id": "900", "displayName": "Carlos Alcaraz" }
                        },
                        {
                          "homeAway": "away",
                          "athlete": { "displayName": "Alexander Zverev" }
                        }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val game = parseScoreboard(tennis, "tennis/atp").single()

        assertEquals("900", game.home.id)
        assertEquals("tennis/atp:900", game.home.favoriteKey)
        // No id in the feed: the name is the only stable thing left, normalised
        // so the next fetch's casing cannot orphan the favourite - and scoped
        // the same way an id is.
        assertNull(game.away.id)
        assertEquals("tennis/atp:alexander zverev", game.away.favoriteKey)
    }

    @Test
    fun `a numbered football week becomes the card's context line`() {
        val nfl = """
            {
              "events": [
                {
                  "id": "601",
                  "name": "Tampa Bay Buccaneers at New Orleans Saints",
                  "date": "2023-10-15T17:00Z",
                  "week": { "number": 6 },
                  "status": { "type": { "state": "pre", "shortDetail": "Thu, 8:15 PM" } },
                  "competitions": [
                    {
                      "venue": {
                        "fullName": "Caesars Superdome",
                        "address": { "city": "New Orleans", "state": "LA" }
                      },
                      "competitors": [
                        {
                          "homeAway": "home",
                          "team": { "abbreviation": "NO", "displayName": "New Orleans Saints" },
                          "records": [{ "summary": "3-2" }]
                        },
                        {
                          "homeAway": "away",
                          "team": { "abbreviation": "TB", "displayName": "Tampa Bay Buccaneers" },
                          "records": [{ "summary": "4-1" }]
                        }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val game = parseScoreboard(nfl, "football/nfl").single()

        assertEquals("Week 6", game.week)
        // The venue still parses; the card prefers the week, because a season
        // is read in weeks and the stadium is the same building all year.
        assertEquals("Caesars Superdome · New Orleans, LA", game.venue)
        // And the record the card prints under the code: "TB · 4-1".
        assertEquals("4-1", game.away.record)
        assertEquals("TB", game.away.abbreviation)
    }

    @Test
    fun `a week the feed does not number is no week at all`() {
        // ESPN writes 0 for the preseason and for a neutral-site game it has no
        // week for; "Week 0" is not a week anybody plays, and the card falls
        // back to the venue rather than inventing one.
        assertNull(espnWeek(org.json.JSONObject("""{"number":0}""")))
        assertNull(espnWeek(null))
        assertEquals("Week 6", espnWeek(org.json.JSONObject("""{"number":6}""")))
    }

    @Test
    fun `an event the feed gives no venue or note draws neither`() {
        val bare = """
            {
              "events": [
                {
                  "id": "505",
                  "name": "Boston Celtics at Denver Nuggets",
                  "date": "2023-11-16T02:00Z",
                  "status": { "type": { "state": "post", "shortDetail": "Final" } },
                  "competitions": [
                    {
                      "notes": [],
                      "competitors": [
                        {
                          "homeAway": "home",
                          "score": "110",
                          "team": { "abbreviation": "DEN", "displayName": "Denver Nuggets" }
                        },
                        {
                          "homeAway": "away",
                          "score": "102",
                          "team": { "abbreviation": "BOS", "displayName": "Boston Celtics" }
                        }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val game = parseScoreboard(bare, "basketball/nba").single()

        // Null, not an empty string: the card draws nothing at all rather than
        // a blank line under the status.
        assertNull(game.venue)
        assertNull(game.note)
    }

    // ── Leaders ─────────────────────────────────────────────────────

    @Test
    fun `a competition's leaders are read one per side, up to two`() {
        val withLeaders = """
            {
              "events": [
                {
                  "id": "801",
                  "name": "Boston Celtics at Los Angeles Lakers",
                  "date": "2023-11-15T03:00Z",
                  "status": { "type": { "state": "in", "shortDetail": "Q3 4:32" } },
                  "competitions": [
                    {
                      "competitors": [
                        { "homeAway": "home", "team": { "abbreviation": "LAL", "displayName": "Los Angeles Lakers" } },
                        { "homeAway": "away", "team": { "abbreviation": "BOS", "displayName": "Boston Celtics" } }
                      ],
                      "leaders": [
                        {
                          "team": { "abbreviation": "LAL" },
                          "leaders": [
                            {
                              "name": "points",
                              "displayName": "Points",
                              "leaders": [
                                { "displayValue": "28 PTS, 11 REB", "athlete": { "displayName": "LeBron James" } }
                              ]
                            }
                          ]
                        },
                        {
                          "team": { "abbreviation": "BOS" },
                          "leaders": [
                            {
                              "name": "points",
                              "leaders": [
                                { "displayValue": "24 PTS", "athlete": { "displayName": "Jayson Tatum" } }
                              ]
                            }
                          ]
                        },
                        {
                          "team": { "abbreviation": "BOS" },
                          "leaders": [
                            { "leaders": [ { "displayValue": "9 REB", "athlete": { "displayName": "Third Man" } } ] }
                          ]
                        }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val leaders = parseScoreboard(withLeaders, "basketball/nba").single().leaders

        // Two rows, not three: the sheet carries one line per side, and the
        // stat group is descended into for the athlete and the summary.
        assertEquals(2, leaders.size)
        assertEquals(GameLeader("LeBron James", "LAL", "28 PTS, 11 REB"), leaders[0])
        assertEquals(GameLeader("Jayson Tatum", "BOS", "24 PTS"), leaders[1])
    }

    @Test
    fun `a scoreboard with no leader block yields no leaders`() {
        // The NBA's regular-season feed carries none at all: the sheet draws no
        // leaders section rather than an empty one.
        val game = parseScoreboard(scoreboard, "basketball/nba").first()
        assertTrue(game.leaders.isEmpty())
    }

    // ── Standings ───────────────────────────────────────────────────

    /**
     * A nested table: two conferences, each with four divisions - the shape the
     * spec pins (NFL: 2 conferences, 8 divisions).
     */
    private val standings = """
        {
          "name": "National Football League",
          "children": [
            {
              "name": "American Football Conference",
              "abbreviation": "AFC",
              "children": [
                {
                  "name": "AFC East",
                  "standings": { "entries": [
                    { "team": { "abbreviation": "BUF", "displayName": "Buffalo Bills" },
                      "stats": [ { "name": "wins", "displayValue": "3" }, { "name": "losses", "displayValue": "1" } ] }
                  ] }
                },
                {
                  "name": "AFC North",
                  "standings": { "entries": [
                    { "team": { "abbreviation": "BAL", "displayName": "Baltimore Ravens" },
                      "stats": [ { "name": "wins", "displayValue": "2" } ] }
                  ] }
                },
                {
                  "name": "AFC South",
                  "standings": { "entries": [
                    { "team": { "abbreviation": "HOU", "displayName": "Houston Texans" },
                      "stats": [ { "name": "wins", "displayValue": "2" } ] }
                  ] }
                },
                {
                  "name": "AFC West",
                  "standings": { "entries": [
                    { "team": {
                        "abbreviation": "KC",
                        "displayName": "Kansas City Chiefs",
                        "logos": [{ "href": "https://a.espncdn.com/i/teamlogos/nfl/500/kc.png" }]
                      },
                      "stats": [
                        { "name": "wins", "displayValue": "4" },
                        { "name": "losses", "displayValue": "0" },
                        { "name": "ties", "displayValue": "0" },
                        { "name": "winPercent", "displayValue": "1.000" },
                        { "name": "gamesBehind", "displayValue": "-" },
                        { "name": "streak", "displayValue": "W4" }
                      ] }
                  ] }
                }
              ]
            },
            {
              "name": "National Football Conference",
              "abbreviation": "NFC",
              "children": [
                { "name": "NFC East", "standings": { "entries": [ { "team": { "abbreviation": "DAL", "displayName": "Dallas Cowboys" }, "stats": [] } ] } },
                { "name": "NFC North", "standings": { "entries": [ { "team": { "abbreviation": "DET", "displayName": "Detroit Lions" }, "stats": [] } ] } },
                { "name": "NFC South", "standings": { "entries": [] } },
                { "name": "NFC West", "standings": { "entries": [ { "team": { "abbreviation": "SF", "displayName": "San Francisco 49ers" }, "stats": [] } ] } }
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `a soccer table's points are read, so the table can be read on them`() {
        // Audit C8: the feed publishes `points` for every soccer table and the
        // hub never asked, so Premier League rows carried W-L-T and three blank
        // columns (win pct, games behind, streak) where the one number a viewer
        // reads was missing.
        val soccer = """
            {
              "children": [
                {
                  "name": "English Premier League",
                  "standings": { "entries": [
                    { "team": { "abbreviation": "ARS", "displayName": "Arsenal" },
                      "stats": [
                        { "name": "wins", "displayValue": "7" },
                        { "name": "losses", "displayValue": "0" },
                        { "name": "ties", "displayValue": "0" },
                        { "name": "points", "displayValue": "21" }
                      ] }
                  ] }
                }
              ]
            }
        """.trimIndent()

        val row = parseStandings(soccer).single().entries.single()
        assertEquals("21", row.points)
        assertEquals(7, row.wins)
        // The stats soccer does not publish stay blank, which is what lets the
        // table choose its columns from the data rather than from a league list.
        assertEquals("", row.winPercent)
        assertEquals("", row.gamesBehind)
    }

    @Test
    fun `a table with no points stat reports none, so the six-column shape stays`() {
        val football = parseStandings(standings).first { it.name == "AFC West" }
        assertEquals("", football.entries.single().points)
    }

    @Test
    fun `conferences are flattened into their divisions, in order, with empties skipped`() {
        val groups = parseStandings(standings)

        // NFC South had no entries, so it is not a heading; the other seven
        // divisions are, in the feed's own order.
        assertEquals(
            listOf(
                "AFC East", "AFC North", "AFC South", "AFC West",
                "NFC East", "NFC North", "NFC West"
            ),
            groups.map { it.name }
        )
        assertEquals(7, groups.size)
    }

    @Test
    fun `a flattened division keeps the conference it was nested under`() {
        // The two-column table lays each conference's divisions out in their own
        // column, and a division's own name (the NHL's bare "Atlantic") cannot
        // say which conference it is in - so the conference has to survive the
        // flattening.
        val groups = parseStandings(standings)
        assertEquals(
            setOf("American Football Conference"),
            groups.filter { it.name.startsWith("AFC") }.mapNotNull { it.conference }.toSet()
        )
        assertEquals(
            setOf("National Football Conference"),
            groups.filter { it.name.startsWith("NFC") }.mapNotNull { it.conference }.toSet()
        )
    }

    @Test
    fun `a division's rows carry the code, the record and the streak`() {
        val kc = parseStandings(standings)
            .first { it.name == "AFC West" }
            .entries
            .single { it.abbreviation == "KC" }

        assertEquals("Kansas City Chiefs", kc.displayName)
        assertEquals(4, kc.wins)
        assertEquals(0, kc.losses)
        assertEquals(0, kc.ties)
        assertEquals("1.000", kc.winPercent)
        assertEquals("-", kc.gamesBehind)
        assertEquals("W4", kc.streak)
        assertEquals("https://a.espncdn.com/i/teamlogos/nfl/500/kc.png", kc.logoUrl)
    }

    @Test
    fun `a conference with no divisions is emitted as one group`() {
        // The live endpoint returns exactly this shape: conferences each with
        // their own `standings.entries` and no division children.
        val flat = """
            {
              "name": "National Basketball Association",
              "children": [
                { "name": "Eastern Conference", "standings": { "entries": [
                    { "team": { "abbreviation": "BOS", "displayName": "Boston Celtics" },
                      "stats": [ { "name": "wins", "displayValue": "1" }, { "name": "winPercent", "displayValue": ".000" } ] }
                ] } },
                { "name": "Western Conference", "standings": { "entries": [] } }
              ]
            }
        """.trimIndent()

        val groups = parseStandings(flat)
        assertEquals(listOf("Eastern Conference"), groups.map { it.name })
        assertEquals("BOS", groups.single().entries.single().abbreviation)
        // The group IS the top level here, so it carries no conference of its
        // own - the two-column rule keys it on its own name instead.
        assertEquals(null, groups.single().conference)
    }

    @Test
    fun `a payload that is not standings yields no groups rather than throwing`() {
        assertTrue(parseStandings("not json at all").isEmpty())
        assertTrue(parseStandings("{}").isEmpty())
        assertTrue(parseStandings("""{"children": []}""").isEmpty())
    }

    @Test
    fun `a stat is read by its display value, falling back to the raw number`() {
        val stats = org.json.JSONArray(
            """[{"name":"wins","value":4,"displayValue":"4"},{"name":"ties","value":0}]"""
        )
        assertEquals("4", espnStatText(stats, "wins"))
        // A stat the feed omits its display string for still reads its value.
        assertEquals("0", espnStatText(stats, "ties"))
        assertEquals("", espnStatText(stats, "streak"))
    }
}
