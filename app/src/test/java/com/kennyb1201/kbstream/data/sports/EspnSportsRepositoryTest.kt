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
        // viewer losing the team they follow.
        assertEquals("27", game.away.favoriteKey)
        assertEquals("18", game.home.favoriteKey)
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
        assertEquals("900", game.home.favoriteKey)
        // No id in the feed: the name is the only stable thing left, normalised
        // so the next fetch's casing cannot orphan the favourite.
        assertNull(game.away.id)
        assertEquals("alexander zverev", game.away.favoriteKey)
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
