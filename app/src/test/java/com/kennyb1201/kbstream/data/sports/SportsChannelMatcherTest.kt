package com.kennyb1201.kbstream.data.sports

import com.kennyb1201.kbstream.data.iptv.IptvChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rule that decides which channel a game plays on.
 *
 * Every case below is a way the hub could play the WRONG thing, which is the
 * one failure that matters here: a viewer who taps "Lakers at Celtics" and gets
 * a poker rerun has been told something false about their own playlist, and no
 * amount of "close enough" makes that better than the card simply saying it is
 * not in the playlist.
 *
 * The three tiers are pinned in order, not just individually - tier 1 winning
 * over tier 2 is the difference between finding the game on whichever network
 * has it and guessing the network from a name the playlist may spell
 * differently.
 */
class SportsChannelMatcherTest {

    private val firstPitch = 1_700_000_000_000L
    private val minute = 60_000L

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

    private fun team(abbreviation: String, name: String, home: Boolean) = SportsTeam(
        abbreviation = abbreviation,
        displayName = name,
        logoUrl = null,
        score = null,
        isHome = home,
        record = null,
    )

    private fun game(
        away: SportsTeam,
        home: SportsTeam,
        dateMs: Long = firstPitch,
        broadcasts: List<String> = emptyList(),
        state: GameState = GameState.UPCOMING,
    ) = SportsGame(
        id = "401",
        league = "baseball/mlb",
        name = "${away.displayName} at ${home.displayName}",
        dateMs = dateMs,
        state = state,
        statusDetail = "",
        away = away,
        home = home,
        broadcastNames = broadcasts,
    )

    private fun program(
        channelId: String,
        title: String,
        startMs: Long,
        endMs: Long,
    ) = MatcherProgram(channelId = channelId, title = title, startMs = startMs, endMs = endMs)

    private val yankees = team("NYY", "New York Yankees", home = false)
    private val redSox = team("BOS", "Boston Red Sox", home = true)

    // ── Tier 1: the EPG program names both teams ────────────────────

    @Test
    fun `tier one matches the program that names both teams`() {
        val mlbNetwork = channel("mlb", "MLB Network")
        val game = game(yankees, redSox)

        val hit = SportsChannelMatcher.match(
            game = game,
            channels = listOf(mlbNetwork),
            programs = listOf(
                program("mlb", "Yankees vs. Red Sox", firstPitch - minute, firstPitch + 3 * 60 * minute)
            ),
        )

        assertEquals(mlbNetwork, hit)
    }

    @Test
    fun `tier one matches on the nickname alone when the feed spells the full name`() {
        val yes = channel("yes", "YES Network")
        val game = game(team("LAL", "Los Angeles Lakers", false), team("BOS", "Boston Celtics", true))

        val hit = SportsChannelMatcher.match(
            game = game,
            channels = listOf(yes),
            programs = listOf(
                program("yes", "Lakers at Celtics", firstPitch, firstPitch + 3 * 60 * minute)
            ),
        )

        assertEquals(yes, hit)
    }

    @Test
    fun `tier one refuses a program that names only one team`() {
        // A pregame show, a repeat, or the season-long magazine: all name one
        // side and are not the game.
        val espn = channel("espn", "ESPN")
        val game = game(yankees, redSox)

        val hit = SportsChannelMatcher.match(
            game = game,
            channels = listOf(espn),
            programs = listOf(
                program("espn", "Yankees Pregame", firstPitch - minute, firstPitch + 30 * minute)
            ),
        )

        assertNull(hit)
    }

    @Test
    fun `tier one refuses a program outside the forty-five minute window`() {
        val espn = channel("espn", "ESPN")
        val game = game(yankees, redSox)

        val hit = SportsChannelMatcher.match(
            game = game,
            channels = listOf(espn),
            programs = listOf(
                // Tomorrow's replay, correctly titled and correctly named.
                program(
                    "espn",
                    "Yankees vs. Red Sox",
                    firstPitch + 24 * 60 * minute,
                    firstPitch + 27 * 60 * minute
                )
            ),
        )

        assertNull(hit)
    }

    @Test
    fun `tier one wins over a tier two network hit`() {
        // The game is on YES (matched by its guide row), and the playlist also
        // carries a channel literally called "ESPN" because the feed lists it
        // as a broadcast. The EPG hit is the one that is actually the game.
        val yes = channel("yes", "YES Network")
        val espn = channel("espn", "ESPN")
        val game = game(yankees, redSox, broadcasts = listOf("ESPN"))

        val hit = SportsChannelMatcher.match(
            game = game,
            channels = listOf(espn, yes),
            programs = listOf(
                program("yes", "Yankees vs. Red Sox", firstPitch, firstPitch + 3 * 60 * minute)
            ),
        )

        assertEquals(yes, hit)
    }

    @Test
    fun `tier one prefers the program that starts closest to first pitch`() {
        val early = channel("early", "Channel A")
        val exact = channel("exact", "Channel B")
        val game = game(yankees, redSox)

        val hit = SportsChannelMatcher.match(
            game = game,
            channels = listOf(early, exact),
            programs = listOf(
                program("early", "Yankees vs Red Sox", firstPitch - 40 * minute, firstPitch + 170 * minute),
                program("exact", "Yankees vs Red Sox", firstPitch, firstPitch + 3 * 60 * minute)
            ),
        )

        assertEquals(exact, hit)
    }

    // ── Tier 2: ESPN's broadcast network ────────────────────────────

    @Test
    fun `tier two prefers an exact name over a prefix and a contains`() {
        val exact = channel("espn", "ESPN")
        val prefix = channel("espn-news", "ESPN News")
        val contains = channel("mine", "My ESPN Channel")
        val game = game(yankees, redSox, broadcasts = listOf("ESPN"))

        val hit = SportsChannelMatcher.match(
            game = game,
            channels = listOf(contains, prefix, exact),
            programs = emptyList(),
        )

        assertEquals(exact, hit)
    }

    @Test
    fun `tier two does not resolve ESPN to ESPN2 when a plain ESPN exists`() {
        val espn2 = channel("espn2", "ESPN2")
        val espn = channel("espn", "ESPN")

        val hit = SportsChannelMatcher.match(
            game = game(yankees, redSox, broadcasts = listOf("ESPN")),
            channels = listOf(espn2, espn),
            programs = emptyList(),
        )

        assertEquals(espn, hit)
    }

    @Test
    fun `tier two still finds ESPN2 when it is the only one there`() {
        // The rule is "do not prefer ESPN2 over ESPN", not "never match ESPN2":
        // a game the feed says is on ESPN2 belongs there.
        val espn2 = channel("espn2", "ESPN2")

        val hit = SportsChannelMatcher.match(
            game = game(yankees, redSox, broadcasts = listOf("ESPN2")),
            channels = listOf(espn2),
            programs = emptyList(),
        )

        assertEquals(espn2, hit)
    }

    @Test
    fun `tier two resolves an alias from either spelling`() {
        val longForm = channel("fox", "FOX Sports 1")

        val hit = SportsChannelMatcher.match(
            game = game(yankees, redSox, broadcasts = listOf("FS1")),
            channels = listOf(longForm),
            programs = emptyList(),
        )

        assertEquals(longForm, hit)
    }

    @Test
    fun `tier two ignores case and punctuation`() {
        val channel = channel("tnt", "TNT")

        val hit = SportsChannelMatcher.match(
            game = game(yankees, redSox, broadcasts = listOf("t.n.t")),
            channels = listOf(channel),
            programs = emptyList(),
        )

        assertEquals(channel, hit)
    }

    @Test
    fun `tier two tries each broadcast name in turn`() {
        val tnt = channel("tnt", "TNT")

        val hit = SportsChannelMatcher.match(
            game = game(yankees, redSox, broadcasts = listOf("ESPN", "TNT")),
            channels = listOf(tnt),
            programs = emptyList(),
        )

        assertEquals(tnt, hit)
    }

    // ── Tier 3: the team's own regional network ─────────────────────

    @Test
    fun `tier three falls back to the team's RSN`() {
        val nesn = channel("nesn", "NESN")

        val hit = SportsChannelMatcher.match(
            game = game(yankees, redSox, broadcasts = listOf("Some Network Nobody Carries")),
            channels = listOf(nesn),
            programs = emptyList(),
        )

        assertEquals(nesn, hit)
    }

    @Test
    fun `tier three tries the home team's RSN before the away team's`() {
        val yes = channel("yes", "YES Network")
        val nesn = channel("nesn", "NESN")

        val hit = SportsChannelMatcher.match(
            game = game(yankees, redSox),
            channels = listOf(yes, nesn),
            programs = emptyList(),
        )

        // Boston is home, so NESN is the home broadcast and the one to try first.
        assertEquals(nesn, hit)
    }

    @Test
    fun `no tier matching means no channel`() {
        val hit = SportsChannelMatcher.match(
            game = game(yankees, redSox, broadcasts = listOf("ESPN")),
            channels = listOf(channel("poker", "Poker Central")),
            programs = emptyList(),
        )

        assertNull(hit)
    }

    @Test
    fun `an empty lineup matches nothing rather than throwing`() {
        val hit = SportsChannelMatcher.match(
            game = game(yankees, redSox),
            channels = emptyList(),
            programs = listOf(program("mlb", "Yankees vs Red Sox", firstPitch, firstPitch + minute)),
        )

        assertNull(hit)
    }

    // ── Tournaments ─────────────────────────────────────────────────

    @Test
    fun `a tournament matches its own title in the guide`() {
        val golf = channel("golf", "Golf Channel")
        val event = TournamentEvent(
            id = "t1",
            league = "golf/pga",
            name = "Baycurrent Classic",
            dateMs = firstPitch,
            state = GameState.LIVE,
            statusDetail = "Round 1 - Play Complete",
            leaders = emptyList(),
            broadcastNames = listOf("Golf Channel"),
        )

        val hit = SportsChannelMatcher.match(
            event = event,
            channels = listOf(golf),
            programs = listOf(
                program("golf", "Baycurrent Classic", firstPitch - minute, firstPitch + 4 * 60 * minute)
            ),
        )

        assertEquals(golf, hit)
    }

    @Test
    fun `a tournament falls back to its broadcast network`() {
        val golf = channel("golf", "Golf Channel")
        val event = TournamentEvent(
            id = "t1",
            league = "golf/pga",
            name = "Baycurrent Classic",
            dateMs = firstPitch,
            state = GameState.LIVE,
            statusDetail = "",
            leaders = emptyList(),
            broadcastNames = listOf("Golf Channel"),
        )

        val hit = SportsChannelMatcher.match(event = event, channels = listOf(golf), programs = emptyList())

        assertEquals(golf, hit)
    }
}
