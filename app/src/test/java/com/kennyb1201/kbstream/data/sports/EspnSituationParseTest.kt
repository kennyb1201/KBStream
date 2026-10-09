package com.kennyb1201.kbstream.data.sports

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The situation line a live card shows, read from the scoreboard the hub
 * already fetched.
 *
 * Free by construction - football's down and distance and baseball's inning and
 * count are on the same competition the card is built from - which is why the
 * hub can say "3rd & 7 · Ball on NE 32" without a second request. The cases
 * below are the ones a viewer sees: each supported sport's shape, and the
 * leagues (and the not-in-play games) that must show NO line rather than a
 * placeholder.
 */
class EspnSituationParseTest {

    private val nflLive = """
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
                      "team": { "id": "17", "abbreviation": "NE", "displayName": "Patriots" }
                    },
                    {
                      "homeAway": "away",
                      "score": "21",
                      "team": { "id": "2", "abbreviation": "BUF", "displayName": "Bills" }
                    }
                  ],
                  "situation": {
                    "down": 3,
                    "distance": 7,
                    "possession": "2",
                    "yardLine": 32
                  }
                }
              ]
            }
          ]
        }
    """.trimIndent()

    private val mlbLive = """
        {
          "events": [
            {
              "id": "500",
              "name": "Boston Red Sox at New York Yankees",
              "date": "2024-06-15T23:05Z",
              "status": { "type": { "state": "in", "shortDetail": "Top 5th" } },
              "competitions": [
                {
                  "competitors": [
                    {
                      "homeAway": "home",
                      "score": "3",
                      "team": { "id": "10", "abbreviation": "NYY", "displayName": "Yankees" }
                    },
                    {
                      "homeAway": "away",
                      "score": "2",
                      "team": { "id": "2", "abbreviation": "BOS", "displayName": "Red Sox" }
                    }
                  ],
                  "details": { "inning": 5, "balls": 1, "strikes": 2, "outs": 1 }
                }
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `an NFL live game reads its down, distance and field position`() {
        val game = parseScoreboard(nflLive, "football/nfl").single()

        assertEquals(GameState.LIVE, game.state)
        // BUF has the ball, so the line names the side the ball is on - NE -
        // and prints ESPN's own yard line as sent.
        assertEquals("3rd & 7 · Ball on NE 32", game.situation)
    }

    @Test
    fun `an MLB live game reads its half-inning and outs`() {
        val game = parseScoreboard(mlbLive, "baseball/mlb").single()

        assertEquals("Top 5th · 1 out", game.situation)
    }

    @Test
    fun `a half-inning break is the break, not a count`() {
        val between = mlbLive.replace("\"Top 5th\"", "\"Mid 5th\"")

        assertEquals("Mid 5th", parseScoreboard(between, "baseball/mlb").single().situation)
    }

    @Test
    fun `a league whose feed carries no situation shows no line`() {
        // Soccer: live, with a score, and nothing in the payload to say what is
        // happening on the pitch - so the card draws no line at all.
        val soccer = """
            {
              "events": [
                {
                  "id": "700",
                  "name": "Arsenal at Chelsea",
                  "date": "2024-11-17T16:30Z",
                  "status": { "type": { "state": "in", "shortDetail": "63'" } },
                  "competitions": [
                    {
                      "competitors": [
                        {
                          "homeAway": "home",
                          "score": "1",
                          "team": { "id": "1", "abbreviation": "CHE", "displayName": "Chelsea" }
                        },
                        {
                          "homeAway": "away",
                          "score": "1",
                          "team": { "id": "2", "abbreviation": "ARS", "displayName": "Arsenal" }
                        }
                      ]
                    }
                  ]
                }
              ]
            }
        """.trimIndent()

        val game = parseScoreboard(soccer, "soccer/eng.1").single()

        assertEquals(GameState.LIVE, game.state)
        assertNull("no situation block, so no line", game.situation)
    }

    @Test
    fun `an upcoming or final game never carries a situation line`() {
        // ESPN's situation block can still be attached for a moment after the
        // whistle; a card that is over must not read "3rd & 7".
        val final = nflLive.replace("\"state\": \"in\"", "\"state\": \"post\"")
        val upcoming = nflLive.replace("\"state\": \"in\"", "\"state\": \"pre\"")

        assertNull(parseScoreboard(final, "football/nfl").single().situation)
        assertNull(parseScoreboard(upcoming, "football/nfl").single().situation)
    }

    @Test
    fun `a possession the feed cannot resolve still reports the down and distance`() {
        // Half a situation beats none: the down and distance are true even when
        // the side the ball is on cannot be named - and naming one anyway would
        // put the wrong team's code on the card.
        val anonymous = nflLive.replace("\"possession\": \"2\"", "\"possession\": \"999\"")

        assertEquals("3rd & 7", parseScoreboard(anonymous, "football/nfl").single().situation)
        val missing = nflLive.replace("\"possession\": \"2\",", "")
        assertEquals("3rd & 7", parseScoreboard(missing, "football/nfl").single().situation)
    }

    @Test
    fun `the ordinal suffixes read as a scoreboard writes them`() {
        assertEquals("1st", espnOrdinal(1))
        assertEquals("2nd", espnOrdinal(2))
        assertEquals("3rd", espnOrdinal(3))
        assertEquals("4th", espnOrdinal(4))
        assertEquals("11th", espnOrdinal(11))
        assertEquals("12th", espnOrdinal(12))
        assertEquals("13th", espnOrdinal(13))
        assertEquals("21st", espnOrdinal(21))
        assertEquals("22nd", espnOrdinal(22))
        assertEquals("23rd", espnOrdinal(23))
    }

    @Test
    fun `the league path picks the sport the situation is read for`() {
        assertEquals(EspnSport.FOOTBALL, espnSport("football/nfl"))
        assertEquals(EspnSport.FOOTBALL, espnSport("football/college-football"))
        assertEquals(EspnSport.BASKETBALL, espnSport("basketball/nba"))
        assertEquals(EspnSport.BASEBALL, espnSport("baseball/mlb"))
        assertEquals(EspnSport.HOCKEY, espnSport("hockey/nhl"))
        assertEquals(EspnSport.SOCCER, espnSport("soccer/eng.1"))
        // The individual sports and anything this build does not know.
        assertEquals(EspnSport.OTHER, espnSport("tennis/atp"))
        assertEquals(EspnSport.OTHER, espnSport("mma/ufc"))
        assertEquals(EspnSport.OTHER, espnSport("golf/pga"))
    }
}
