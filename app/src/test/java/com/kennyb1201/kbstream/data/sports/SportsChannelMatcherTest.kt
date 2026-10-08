package com.kennyb1201.kbstream.data.sports

import com.kennyb1201.kbstream.data.iptv.IptvChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    // ── Streaming exclusives ───────────────────────────────────────
    //
    // "ESPN+" and "ESPN" are different networks, and only one of them is in a
    // cable lineup. Folding '+' away made them identical, so an ESPN+ game
    // matched the ESPN cable channel - a confident wrong answer, which is the
    // one failure this matcher exists to avoid.

    @Test
    fun `the plus survives normalization so ESPN plus is not ESPN`() {
        assertEquals("espn+", SportsChannelMatcher.compact("ESPN+"))
        assertEquals("espn", SportsChannelMatcher.compact("ESPN"))
        assertNotEquals(
            "the whole bug was these two compacting identically",
            SportsChannelMatcher.compact("ESPN+"),
            SportsChannelMatcher.compact("ESPN")
        )
    }

    @Test
    fun `a streaming exclusive never resolves to a similarly named cable channel`() {
        val espn = channel("espn", "ESPN")

        val hit = SportsChannelMatcher.match(
            game = game(yankees, redSox, broadcasts = listOf("ESPN+")),
            channels = listOf(espn),
            programs = emptyList(),
        )

        assertNull(hit)
    }

    @Test
    fun `a plain broadcast name still resolves to its cable channel`() {
        // The other half of the invariant: nothing without a '+' changed.
        val espn = channel("espn", "ESPN")

        val hit = SportsChannelMatcher.match(
            game = game(yankees, redSox, broadcasts = listOf("ESPN")),
            channels = listOf(espn),
            programs = emptyList(),
        )

        assertEquals(espn, hit)
    }

    @Test
    fun `an exclusive game is found by the guide on the channel actually airing it`() {
        // Tier 1 is the correct path for an ESPN+ game: the RSN or local channel
        // simulcasting it lists both teams in the guide, and that beats both the
        // ESPN cable channel and the team's own RSN.
        val yes = channel("yes", "YES Network")
        val espn = channel("espn", "ESPN")

        val hit = SportsChannelMatcher.match(
            game = game(yankees, redSox, broadcasts = listOf("ESPN+")),
            channels = listOf(espn, yes),
            programs = listOf(
                program("yes", "Yankees vs. Red Sox", firstPitch, firstPitch + 3 * 60 * minute)
            ),
        )

        assertEquals(yes, hit)
    }

    @Test
    fun `an exclusive game with no guide hit falls to the team's RSN`() {
        val nesn = channel("nesn", "NESN")
        val espn = channel("espn", "ESPN")

        val hit = SportsChannelMatcher.match(
            game = game(yankees, redSox, broadcasts = listOf("ESPN+")),
            channels = listOf(espn, nesn),
            programs = emptyList(),
        )

        // Boston is home, so NESN is tier 3 - never the ESPN cable channel.
        assertEquals(nesn, hit)
    }

    @Test
    fun `Apple TV plus normalizes distinctly and is an exclusive`() {
        assertEquals("appletv+", SportsChannelMatcher.compact("Apple TV+"))

        val hit = SportsChannelMatcher.match(
            game = game(yankees, redSox, broadcasts = listOf("Apple TV+")),
            channels = listOf(channel("apple", "Apple TV")),
            programs = emptyList(),
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

    // ── Backups: every feed the playlist holds, not just the best one ───
    //
    // A game carried on two feeds is the case this list exists for: the head is
    // what a card has always played, and everything behind it is what the sheet
    // offers and the player falls to when the head will not open.

    @Test
    fun `every guide feed airing the game is kept, closest first`() {
        val espn = channel("espn", "ESPN")
        val espn2 = channel("espn2", "ESPN2")
        val game = game(yankees, redSox)
        val programs = listOf(
            program("espn2", "Yankees vs. Red Sox", firstPitch - 30 * minute, firstPitch + 180 * minute),
            program("espn", "Yankees vs. Red Sox", firstPitch, firstPitch + 180 * minute),
        )

        val found = SportsChannelMatcher.matches(game, listOf(espn2, espn), programs)

        assertEquals(listOf(espn, espn2), found)
        assertEquals(
            "and the head is exactly what a single match has always returned",
            found.first(),
            SportsChannelMatcher.match(game, listOf(espn2, espn), programs)
        )
    }

    @Test
    fun `the rest of the network family becomes the backups`() {
        val espn = channel("espn", "ESPN")
        val espn2 = channel("espn2", "ESPN2")
        val news = channel("news", "ESPN News")
        val contains = channel("mine", "My ESPN Channel")

        val found = SportsChannelMatcher.matches(
            game = game(yankees, redSox, broadcasts = listOf("ESPN")),
            channels = listOf(contains, news, espn2, espn),
            programs = emptyList(),
        )

        // Exact first, then the word-boundary hits shortest-name-first, then the
        // one that only contains the name - the same strength order the single
        // pick used, now with everything it used to discard behind it.
        assertEquals(listOf(espn, espn2, news, contains), found)
    }

    @Test
    fun `a team's regional network rides behind the national feed`() {
        val espn = channel("espn", "ESPN")
        val yes = channel("yes", "YES Network")
        val nesn = channel("nesn", "NESN")

        val found = SportsChannelMatcher.matches(
            game = game(yankees, redSox, broadcasts = listOf("ESPN")),
            channels = listOf(espn, yes, nesn),
            programs = emptyList(),
        )

        // Boston is home, so its own network is the backup tried first.
        assertEquals(listOf(espn, nesn, yes), found)
    }

    @Test
    fun `a feed that answers to two tiers is offered once`() {
        // YES is both the game's listed broadcast and the Yankees' own network,
        // and the same feed listed twice is not a second feed.
        val yes = channel("yes", "YES Network")

        val found = SportsChannelMatcher.matches(
            game = game(yankees, redSox, broadcasts = listOf("YES Network")),
            channels = listOf(yes),
            programs = emptyList(),
        )

        assertEquals(listOf(yes), found)
    }

    @Test
    fun `the list is capped at the feeds a ladder can use`() {
        val many = (1..6).map { index ->
            channel("c$index", if (index == 1) "ESPN" else "ESPN $index")
        }

        val found = SportsChannelMatcher.matches(
            game = game(yankees, redSox, broadcasts = listOf("ESPN")),
            channels = many,
            programs = emptyList(),
        )

        assertEquals(SportsChannelMatcher.MAX_MATCHES, found.size)
        assertEquals("the exact name is still the head", many.first(), found.first())
    }

    @Test
    fun `an empty lineup lists nothing rather than throwing`() {
        assertEquals(
            emptyList<IptvChannel>(),
            SportsChannelMatcher.matches(
                game = game(yankees, redSox),
                channels = emptyList(),
                programs = listOf(program("mlb", "Yankees vs Red Sox", firstPitch, firstPitch + minute)),
            )
        )
    }
}
