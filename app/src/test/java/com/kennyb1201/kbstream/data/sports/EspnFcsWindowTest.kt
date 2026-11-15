package com.kennyb1201.kbstream.data.sports

import com.kennyb1201.kbstream.data.iptv.IptvChannel
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * College football's FCS window (spec 2026-11-14, F1/F2).
 *
 * The field report: scores missing from the guide AND the hub for Idaho State at
 * Montana, live and 7-7. One feed serves both surfaces, and the feed was asking
 * ESPN for the default college-football scoreboard - the FBS division. A game
 * between two FCS sides is only in that league's `?groups=81` body, so the app
 * could not have shown it. The fix is one extra request for one league, merged
 * into the list every surface already reads; nothing else about the hub, the
 * guide or the league list changes.
 *
 * The payload below is the real event, trimmed to the fields the parser reads
 * (fetched from `.../football/college-football/scoreboard?groups=81` on
 * 2026-11-14, in play at 11:37 in the 2nd). Keeping it verbatim in shape is the
 * point: F2 asks whether an FCS event parses like any other, and the answer has
 * to come from an FCS body, not an FBS one relabelled.
 */
class EspnFcsWindowTest {

    // ── F2: the parse ─────────────────────────────────────────────────────

    @Test
    fun `an FCS event parses like any other game`() {
        val game = parseScoreboard(fcsScoreboard, COLLEGE_FOOTBALL).single()

        assertEquals("401868109", game.id)
        assertEquals("Idaho State Bengals at Montana Grizzlies", game.name)
        assertEquals(COLLEGE_FOOTBALL, game.league)
        assertEquals(GameState.LIVE, game.state)
        // The card's live line, built from the feed's own detail.
        assertEquals("11:37 - 2nd", game.statusDetail)
        assertEquals("ESPN2", game.broadcastNames.single())

        // The sides, the scores, and the home flag that makes the line read
        // AWAY - HOME rather than backwards.
        assertEquals("IDST", game.away.abbreviation)
        assertEquals("Idaho State Bengals", game.away.displayName)
        assertEquals("7", game.away.score)
        assertEquals("MONT", game.home.abbreviation)
        assertEquals("Montana Grizzlies", game.home.displayName)
        assertEquals("7", game.home.score)
        assertTrue(game.home.isHome)
        assertTrue(!game.away.isHome)
        assertEquals("6-4", game.away.record)
        assertEquals("https://a.espncdn.com/i/teamlogos/ncaa/500/304.png", game.away.logoUrl)
    }

    @Test
    fun `an FCS final banks both scores like an FBS final`() {
        val final = parseScoreboard(finalScoreboard, COLLEGE_FOOTBALL).single()

        assertEquals(GameState.FINAL, final.state)
        assertEquals("37", final.home.score)
        assertEquals("3", final.away.score)
    }

    // ── F2: the match, on a real FCS broadcast ────────────────────────────

    @Test
    fun `the game resolves to the guide cell that airs it`() {
        // The matcher is untouched by this spec, and this is why it can be: a
        // guide program titled with the two teams matches exactly as it does for
        // an FBS game, because the title is what the tier reads - not ESPN's
        // broadcast name.
        val game = parseScoreboard(fcsScoreboard, COLLEGE_FOOTBALL).single()
        val espn2 = channel("espn2", "ESPN2")
        val kickoff = game.dateMs

        val hit = SportsChannelMatcher.match(
            game = game,
            channels = listOf(espn2),
            programs = listOf(
                MatcherProgram(
                    channelId = "espn2",
                    title = "Idaho State Bengals at Montana Grizzlies",
                    startMs = kickoff - 5 * 60_000L,
                    endMs = kickoff + 4 * 60 * 60_000L,
                )
            ),
        )

        assertEquals(espn2, hit)
    }

    @Test
    fun `a broadcast the guide does not carry shows no cell - and that is not a bug`() {
        // Named in the spec as expected-empty: an FCS game on a network the
        // playlist has no channel for resolves to nothing, and the card says so.
        // It is not counted as a failure, and nothing is invented to fill it.
        val game = parseScoreboard(finalScoreboard, COLLEGE_FOOTBALL).single()
        val espn2 = channel("espn2", "ESPN2")

        val hit = SportsChannelMatcher.match(
            game = game,
            channels = listOf(espn2),
            programs = listOf(
                MatcherProgram(
                    channelId = "espn2",
                    title = "NHL Classics",
                    startMs = game.dateMs,
                    endMs = game.dateMs + 2 * 60 * 60_000L,
                )
            ),
        )

        assertNull(hit)
    }

    // ── F1: one extra request, for one league, merged into the same list ──

    @Test
    fun `the repository asks for group 81, for college football only, and merges it`() {
        val source = repoSource()

        assertTrue(
            source.contains("private const val COLLEGE_FOOTBALL = \"football/college-football\"")
        )
        assertTrue(source.contains("private const val FCS_GROUPS = \"81\""))
        assertTrue(
            "the second window is the FCS group, on the same league path",
            source.contains("fetch(COLLEGE_FOOTBALL, day, groups = FCS_GROUPS)")
        )
        assertTrue(
            "and it is read only where the league IS college football",
            source.contains("val base = if (leaguePath == COLLEGE_FOOTBALL) {")
        )
        assertTrue(
            "the default window's copy wins a duplicate",
            source.contains("EspnCacheRules.mergeSubdivision(\n                fetched,")
        )
        assertTrue(
            "and a failed second window keeps the last good games",
            source.contains("EspnCacheRules.carryStaleSubdivision(")
        )
        // The day windows (viewer's own day, a game that outlived its day) read
        // the same two groups, so an FCS game late at night is carried like an
        // FBS one instead of vanishing at midnight Eastern.
        assertTrue(source.contains("private suspend fun readWindow(leaguePath: String, day: String)"))
        assertTrue(source.contains("EspnCacheRules.mergeSubdivision(main, fcsWindow(day).orEmpty())"))
    }

    @Test
    fun `no new league, tab or setting - the FCS games join the College Football list`() {
        // S6, and the out-of-scope list: this is a window, not a division. One
        // College Football league, one tab, one setting list.
        val leagues = read("data/sports/SportsModels.kt")
        assertEquals(1, Regex("\"football/college-football\"").findAll(leagues).count())
        assertTrue(!leagues.contains("\"football/college-football-fcs\"", ignoreCase = true))
        assertTrue(!leagues.contains("groups=81"))
        // And it is one extra request, not a second league to enable: the group
        // id is used exactly once, from exactly one place.
        val repo = repoSource()
        assertEquals(1, Regex("groups = FCS_GROUPS").findAll(repo).count())
        assertTrue(!leagues.contains("fcs", ignoreCase = true))
    }

    // ── Fixtures: the real payload, trimmed to the fields the parser reads ──

    private val fcsScoreboard = """
        {
          "events": [
            {
              "id": "401868109",
              "uid": "s:20~l:23~e:401868109",
              "date": "2026-11-15T03:30Z",
              "name": "Idaho State Bengals at Montana Grizzlies",
              "shortName": "IDST @ MONT",
              "competitions": [
                {
                  "id": "401868109",
                  "date": "2026-11-15T03:30Z",
                  "attendance": 0,
                  "status": {
                    "clock": 697.0,
                    "displayClock": "11:37",
                    "period": 2,
                    "type": {
                      "id": "2",
                      "name": "STATUS_IN_PROGRESS",
                      "state": "in",
                      "completed": false,
                      "description": "In Progress",
                      "detail": "11:37 - 2nd Quarter",
                      "shortDetail": "11:37 - 2nd"
                    }
                  },
                  "competitors": [
                    {
                      "id": "149",
                      "homeAway": "home",
                      "order": 0,
                      "score": "7",
                      "records": [{ "name": "overall", "summary": "7-3" }],
                      "team": {
                        "id": "149",
                        "abbreviation": "MONT",
                        "displayName": "Montana Grizzlies",
                        "logo": "https://a.espncdn.com/i/teamlogos/ncaa/500/149.png"
                      }
                    },
                    {
                      "id": "304",
                      "homeAway": "away",
                      "order": 1,
                      "score": "7",
                      "records": [{ "name": "overall", "summary": "6-4" }],
                      "team": {
                        "id": "304",
                        "abbreviation": "IDST",
                        "displayName": "Idaho State Bengals",
                        "logo": "https://a.espncdn.com/i/teamlogos/ncaa/500/304.png"
                      }
                    }
                  ],
                  "broadcasts": [{ "market": "national", "names": ["ESPN2"] }]
                }
              ]
            }
          ]
        }
    """.trimIndent()

    private val finalScoreboard = """
        {
          "events": [
            {
              "id": "401868100",
              "date": "2026-11-14T21:00Z",
              "name": "Maine Black Bears at UAlbany Great Danes",
              "competitions": [
                {
                  "status": {
                    "displayClock": "0:00",
                    "period": 4,
                    "type": {
                      "id": "3",
                      "name": "STATUS_FINAL",
                      "state": "post",
                      "completed": true,
                      "detail": "Final",
                      "shortDetail": "Final"
                    }
                  },
                  "competitors": [
                    {
                      "homeAway": "home",
                      "score": "37",
                      "team": { "abbreviation": "ALB", "displayName": "UAlbany Great Danes" }
                    },
                    {
                      "homeAway": "away",
                      "score": "3",
                      "team": { "abbreviation": "ME", "displayName": "Maine Black Bears" }
                    }
                  ],
                  "broadcasts": [{ "market": "home", "names": ["FloSports"] }]
                }
              ]
            }
          ]
        }
    """.trimIndent()

    private fun channel(id: String, name: String) = IptvChannel(
        id = id,
        name = name,
        displayName = name,
        streamUrl = "http://playlist.test/$id",
        groupTitle = null,
        logoUrl = null,
        tvgId = null,
        tvgName = null,
        tvgChno = null,
        catchup = null,
        catchupDays = null,
        catchupSource = null,
        providerChannelId = null,
    )

    private fun repoSource(): String = read("data/sports/EspnSportsRepository.kt")

    /** A source file from either the module root or the repository root. */
    private fun read(relative: String): String {
        val prefixes = listOf("", "app/")
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            prefixes.forEach { prefix ->
                val candidate = File(dir, "${prefix}src/main/java/com/kennyb1201/kbstream/$relative")
                if (candidate.isFile) return candidate.readText()
            }
            dir = dir.parentFile
        }
        throw AssertionError("source missing: $relative")
    }

    private companion object {
        const val COLLEGE_FOOTBALL = "football/college-football"
    }
}
