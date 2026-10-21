package com.kennyb1201.kbstream.data.sports

import com.kennyb1201.kbstream.data.iptv.IptvChannel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

    private fun team(
        abbreviation: String,
        name: String,
        home: Boolean,
        shortName: String? = null,
    ) = SportsTeam(
        abbreviation = abbreviation,
        displayName = name,
        logoUrl = null,
        score = null,
        isHome = home,
        record = null,
        shortName = shortName,
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
        description: String? = null,
    ) = MatcherProgram(
        channelId = channelId,
        title = title,
        startMs = startMs,
        endMs = endMs,
        description = description,
    )

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
    fun `the named network leads a guide hit that does not agree with it`() {
        // The game says ESPN carries it; the guide's only row is on YES. The
        // network still gets its vote in the list - ahead of the stranger's
        // claim, which is what the field report asked for - and the guide hit
        // stays behind it as a backup rather than being dropped.
        val yes = channel("yes", "YES Network")
        val espn = channel("espn", "ESPN")
        val game = game(yankees, redSox, broadcasts = listOf("ESPN"))

        val feeds = SportsChannelMatcher.matches(
            game = game,
            channels = listOf(yes, espn),
            programs = listOf(
                program("yes", "Yankees vs. Red Sox", firstPitch, firstPitch + 3 * 60 * minute)
            ),
        )

        assertEquals(listOf(espn, yes), feeds)
    }

    @Test
    fun `a guide hit that agrees with the named network leads its family`() {
        // The guide says the game is on ESPN2 - a feed the ESPN broadcast name
        // resolves to - so that row is the head and the rest of the family is
        // not guessed in ahead of it.
        val espn = channel("espn", "ESPN")
        val espn2 = channel("espn2", "ESPN2")
        val game = game(yankees, redSox, broadcasts = listOf("ESPN"))

        val feeds = SportsChannelMatcher.matches(
            game = game,
            channels = listOf(espn, espn2),
            programs = listOf(
                program("espn2", "Yankees vs. Red Sox", firstPitch, firstPitch + 3 * 60 * minute)
            ),
        )

        assertEquals(listOf(espn2), feeds)
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
    fun `tier three matches a regional network by an older brand name`() {
        // The feed's own table says FanDuel Sports Network North. A playlist
        // last written under Bally - or under FOX before that - carries the
        // same channel, and it is the one airing the game. Before the brand
        // fold this was a "Not in your playlist" on a channel the viewer has.
        val bally = channel("ballynorth", "Bally Sports North")
        val wild = team("MIN", "Minnesota Wild", home = true)
        val avalanche = team("COL", "Colorado Avalanche", home = false)

        val hit = SportsChannelMatcher.match(
            game = game(avalanche, wild, broadcasts = listOf("Nobody Carries This")),
            channels = listOf(bally),
            programs = emptyList(),
        )

        assertEquals(bally, hit)
    }

    @Test
    fun `the brand fold leaves the national FOX channels national`() {
        // FS1 and FS2 sit in the same brand family and are NOT regional
        // networks: folding them onto one would play a national channel for a
        // game it does not carry.
        val fs1 = channel("fs1", "FOX Sports 1")
        val fs1hd = channel("fs1hd", "FOX Sports 1 HD")
        val wild = team("MIN", "Minnesota Wild", home = true)
        val avalanche = team("COL", "Colorado Avalanche", home = false)

        assertNull(
            SportsChannelMatcher.match(
                game = game(avalanche, wild, broadcasts = listOf("Nobody Carries This")),
                channels = listOf(fs1),
                programs = emptyList(),
            )
        )
        assertNull(
            SportsChannelMatcher.match(
                game = game(avalanche, wild, broadcasts = listOf("Nobody Carries This")),
                channels = listOf(fs1hd),
                programs = emptyList(),
            )
        )
    }

    @Test
    fun `an abbreviation-only guide title still finds the game`() {
        // "MIN @ TB" names neither team's full name, and it is how plenty of
        // providers title a game. The matcher already reads an abbreviation as
        // a strong name; the row only has to reach it (see the hub's
        // abbreviation fallback lookup).
        val nhlNetwork = channel("nhl", "NHL Network")
        val wild = team("MIN", "Minnesota Wild", home = false)
        val lightning = team("TB", "Tampa Bay Lightning", home = true)

        val hit = SportsChannelMatcher.match(
            game = game(wild, lightning),
            channels = listOf(nhlNetwork),
            programs = listOf(
                program("nhl", "MIN @ TB", firstPitch, firstPitch + 150 * minute)
            ),
        )

        assertEquals(nhlNetwork, hit)
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
    fun `a tournament matches a replay days after ESPN's own slot`() {
        // The reported bug: ESPN dates the Baycurrent Classic with ONE 1.5-hour
        // broadcast slot two days before the card was read, while the guide
        // carries the tournament as 29 replays and highlights across the week.
        // The old time filter dropped all 29 and the card read "not in your
        // playlist" over a playlist that was carrying it; the tier now matches
        // a tournament on its name alone.
        val golf = channel("golf", "Golf Channel")
        val slot = firstPitch - 2 * 24 * 60 * minute
        val event = TournamentEvent(
            id = "401850916",
            league = "golf/pga",
            name = "Baycurrent Classic",
            dateMs = slot,
            state = GameState.LIVE,
            statusDetail = "Round 1 - Play Complete",
            leaders = emptyList(),
            // No broadcast name on purpose: the ONLY way to this channel is the
            // guide row, so the test pins the EPG tier rather than falling
            // through to the network tier and proving nothing.
            broadcastNames = emptyList(),
        )
        // A replay well outside the old window: the slot has been over for two
        // days and the program itself airs now.
        val replay = program("golf", "Baycurrent Classic", firstPitch, firstPitch + 3 * 60 * minute)

        assertEquals(
            listOf(golf),
            SportsChannelMatcher.matches(
                event = event,
                channels = listOf(golf),
                programs = listOf(replay),
            ),
        )
    }

    @Test
    fun `a game's EPG tier still insists on its window`() {
        // The change is tournaments only: a game's guide row has to overlap its
        // kick-off, or every re-run of last night's game would look like this
        // afternoon's.
        // No broadcast name either, for the same reason: the row is the only
        // signal, and it is now too old to count.
        val espn = channel("espn", "ESPN")
        val game = game(yankees, redSox)
        val lastNight = program(
            "espn",
            "Yankees vs. Red Sox",
            firstPitch - 30 * 60 * minute,
            firstPitch - 27 * 60 * minute,
        )

        assertNull(
            SportsChannelMatcher.match(game = game, channels = listOf(espn), programs = listOf(lastNight)),
        )
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

    @Test
    fun `a tournament with no EPG hit and no broadcast names matches nothing`() {
        // The new candidate path must not manufacture a match: the guide row a
        // tournament lookup happens to return is not the tournament unless the
        // matcher finds the whole name in it, so this stays a no-match.
        val golf = channel("golf", "Golf Channel")
        val event = TournamentEvent(
            id = "t1",
            league = "golf/pga",
            name = "Baycurrent Classic",
            dateMs = firstPitch,
            state = GameState.LIVE,
            statusDetail = "",
            leaders = emptyList(),
            broadcastNames = emptyList(),
        )

        val hit = SportsChannelMatcher.match(
            event = event,
            channels = listOf(golf),
            programs = listOf(
                program("golf", "PGA Tour Highlights", firstPitch, firstPitch + 60 * minute)
            ),
        )

        assertNull(hit)
    }

    @Test
    fun `a remembered tournament channel wins over its tiers`() {
        // The same correction memory the game path has: a viewer who moved a
        // golf tournament to its own channel gets that back, keyed by the
        // event's stable name.
        val golf = channel("golf", "Golf Channel")
        val other = channel("other", "Other Sports")
        val event = TournamentEvent(
            id = "t1",
            league = "golf/pga",
            name = "Baycurrent Classic",
            dateMs = firstPitch,
            state = GameState.LIVE,
            statusDetail = "",
            leaders = emptyList(),
            broadcastNames = listOf("Other Sports"),
        )
        val remembered: (String) -> IptvChannel? = { key -> golf.takeIf { key == event.favoriteKey } }

        val hit = SportsChannelMatcher.match(
            event = event,
            channels = listOf(other, golf),
            programs = emptyList(),
            remembered = remembered,
        )

        assertEquals(golf, hit)
    }

    @Test
    fun `a remembered tournament channel that is gone falls through to its tiers`() {
        val other = channel("other", "Other Sports")
        val dead = channel("dead", "Gone Channel")
        val event = TournamentEvent(
            id = "t1",
            league = "golf/pga",
            name = "Baycurrent Classic",
            dateMs = firstPitch,
            state = GameState.LIVE,
            statusDetail = "",
            leaders = emptyList(),
            broadcastNames = listOf("Other Sports"),
        )
        val remembered: (String) -> IptvChannel? = { dead }

        val hit = SportsChannelMatcher.match(
            event = event,
            channels = listOf(other),
            programs = emptyList(),
            remembered = remembered,
        )

        assertEquals(other, hit)
    }

    @Test
    fun `a remembered tournament channel leads and keeps the other feeds too`() {
        // The tournament half of the same rule, and the same dead end if it is
        // missed: a golf pick that turned out to be the wrong channel must not
        // take the other feeds with it.
        val golf = channel("golf", "Golf Channel")
        val other = channel("other", "Other Sports")
        val event = TournamentEvent(
            id = "t1",
            league = "golf/pga",
            name = "Baycurrent Classic",
            dateMs = firstPitch,
            state = GameState.LIVE,
            statusDetail = "",
            leaders = emptyList(),
            broadcastNames = listOf("Other Sports"),
        )
        val remembered: (String) -> IptvChannel? = { key -> golf.takeIf { key == event.favoriteKey } }

        val feeds = SportsChannelMatcher.matches(
            event = event,
            channels = listOf(other, golf),
            programs = emptyList(),
            remembered = remembered,
        )

        assertEquals(listOf(golf, other), feeds)
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

        // Exact first, then the domestic qualified names ("ESPN News", shortest
        // first), then the prefix family ("ESPN2"), then the one that only
        // contains the name - the same strength order the single pick used, now
        // with everything it used to discard behind it.
        assertEquals(listOf(espn, news, espn2, contains), found)
    }

    @Test
    fun `tier two prefers a qualified domestic feed over a different channel sharing the prefix`() {
        val espn2 = channel("espn2", "ESPN2")
        val espnHd = channel("espnhd", "ESPN HD")

        // The pick must not depend on playlist order: ESPN HD is a domestic feed
        // of the brand, ESPN2 is a different channel sharing its prefix.
        listOf(
            listOf(espn2, espnHd),
            listOf(espnHd, espn2),
        ).forEach { channels ->
            assertEquals(
                "the domestic feed wins in either playlist order",
                espnHd,
                SportsChannelMatcher.match(
                    game = game(yankees, redSox, broadcasts = listOf("ESPN")),
                    channels = channels,
                    programs = emptyList(),
                ),
            )
        }
    }

    @Test
    fun `tier two demotes an international variant below the domestic feed`() {
        // The Georgia/Auburn game of 2026-10-17: ESPN's own scoreboard said the
        // broadcast was "ESPN", and the hub served "ESPN AU" because both names
        // tied at the old strength 2 and the tie fell to playlist order.
        val espnAu = channel("espnau", "ESPN AU")
        val espnHd = channel("espnhd", "ESPN HD")

        listOf(
            listOf(espnAu, espnHd),
            listOf(espnHd, espnAu),
        ).forEach { channels ->
            assertEquals(
                "the domestic feed wins in either playlist order",
                espnHd,
                SportsChannelMatcher.match(
                    game = game(yankees, redSox, broadcasts = listOf("ESPN")),
                    channels = channels,
                    programs = emptyList(),
                ),
            )
        }
    }

    @Test
    fun `an international variant stays in the backups behind the domestic feed`() {
        val espnHd = channel("espnhd", "ESPN HD")
        val espnAu = channel("espnau", "ESPN AU")
        val espn2 = channel("espn2", "ESPN2")

        val found = SportsChannelMatcher.matches(
            game = game(yankees, redSox, broadcasts = listOf("ESPN")),
            channels = listOf(espnAu, espn2, espnHd),
            programs = emptyList(),
        )

        // The variant is demoted, not excluded: it may genuinely carry the game,
        // so it beats "not in your playlist" while staying behind the feed the
        // broadcast actually means.
        assertEquals(listOf(espnHd, espnAu, espn2), found)
    }

    @Test
    fun `a region code only counts as a trailing token`() {
        val auFirst = channel("auespn", "AU ESPN")
        val espn2 = channel("espn2", "ESPN2")

        val hit = SportsChannelMatcher.match(
            game = game(yankees, redSox, broadcasts = listOf("ESPN")),
            channels = listOf(auFirst, espn2),
            programs = emptyList(),
        )

        // "AU ESPN" only contains the name (band 1); a leading token is not a
        // region suffix, so the prefix family still outranks it.
        assertEquals(espn2, hit)
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

    // ── The broadcast network outranks guide order (2026-10-20) ─────
    //
    // Kenny's live game: broadcast ESPN, and the hub offered ESPN+3 and NFL
    // RedZone with the plain ESPN channel missing entirely. Three rules
    // composed into that: a title hit anywhere suppressed the description pass
    // everywhere, any guide hit suppressed the network tier outright, and guide
    // hits ranked on start-closeness alone. The tests below pin each half of
    // the fix, and the first one pins the report itself.

    @Test
    fun `the field case - the named network leads its variant and the weak row is gone`() {
        val espn = channel("espn", "ESPN")
        val espn3 = channel("espn3", "ESPN+3")
        val redZone = channel("redzone", "NFL RedZone")
        val lightning = team("TB", "Tampa Bay Lightning", home = false)
        val panthers = team("FLA", "Florida Panthers", home = true)
        val game = game(lightning, panthers, broadcasts = listOf("ESPN"))

        val feeds = SportsChannelMatcher.matches(
            game = game,
            channels = listOf(redZone, espn3, espn),
            programs = listOf(
                // ESPN's own guide row: a generic title, the teams only in the
                // synopsis. This is the hit the title short-circuit used to
                // suppress.
                program(
                    "espn",
                    "NHL Hockey",
                    firstPitch,
                    firstPitch + 180 * minute,
                    description = "The Tampa Bay Lightning visit the Florida " +
                        "Panthers at Amerant Bank Arena.",
                ),
                // The variant's row TITLES the game, at the same minute - which
                // is what put it at the head on guide order alone.
                program("espn3", "Lightning vs Panthers", firstPitch, firstPitch + 180 * minute),
                // Cross-sport: "Tampa Bay" is also the Buccaneers, so this is a
                // WEAK hit and must not sit beside real ones.
                program(
                    "redzone",
                    "Tampa Bay at Florida",
                    firstPitch,
                    firstPitch + 180 * minute,
                    description = "NFL RedZone",
                ),
            ),
        )

        assertEquals(
            "the plain channel the game names leads, its variant keeps its place " +
                "as a corroborated backup, and the weak cross-sport row is gone",
            listOf(espn, espn3),
            feeds
        )
    }

    @Test
    fun `an agreeing description hit beats a stranger's title hit`() {
        // Rule A: the guide's synopsis on the channel the game actually names
        // outranks a title hit on a channel nothing about the game points to.
        val espn = channel("espn", "ESPN")
        val stranger = channel("other", "Some Sports Channel")
        val game = game(yankees, redSox, broadcasts = listOf("ESPN"))

        val feeds = SportsChannelMatcher.matches(
            game = game,
            channels = listOf(stranger, espn),
            programs = listOf(
                program("other", "Yankees vs. Red Sox", firstPitch, firstPitch + 180 * minute),
                program(
                    "espn",
                    "MLB Baseball",
                    firstPitch,
                    firstPitch + 180 * minute,
                    description = "Yankees and Red Sox meet tonight.",
                ),
            ),
        )

        assertEquals("the named network's row leads; the stranger stays behind", listOf(espn, stranger), feeds)
    }

    @Test
    fun `the named network is offered when no guide row names it`() {
        // Rule B: the guide has a row for the game, but on a channel the game's
        // broadcast name does not resolve to. The network's own feeds are still
        // offered - ahead of the stranger - instead of the tier being skipped.
        val espn = channel("espn", "ESPN")
        val espn2 = channel("espn2", "ESPN2")
        val stranger = channel("other", "Some Sports Channel")
        val game = game(yankees, redSox, broadcasts = listOf("ESPN"))

        val feeds = SportsChannelMatcher.matches(
            game = game,
            channels = listOf(stranger, espn, espn2),
            programs = listOf(
                program("other", "Yankees vs. Red Sox", firstPitch, firstPitch + 180 * minute)
            ),
        )

        assertEquals(listOf(espn, espn2, stranger), feeds)
    }

    @Test
    fun `a weak-only slate still answers with its weak hit`() {
        // Rule C is about ranking, not about refusing: when nothing names both
        // sides strongly, a city-only hit is still the best evidence there is.
        val rsn = channel("rsn", "Local Sports")
        val game = game(
            team("TB", "Tampa Bay Lightning", home = false),
            team("FLA", "Florida Panthers", home = true),
        )

        val feeds = SportsChannelMatcher.matches(
            game = game,
            channels = listOf(rsn),
            programs = listOf(program("rsn", "Tampa Bay vs Florida", firstPitch, firstPitch + 180 * minute)),
        )

        assertEquals(listOf(rsn), feeds)
    }

    @Test
    fun `a weak hit ranks below the named network when both are all there is`() {
        val espn = channel("espn", "ESPN")
        val rsn = channel("rsn", "Local Sports")
        val game = game(
            team("TB", "Tampa Bay Lightning", home = false),
            team("FLA", "Florida Panthers", home = true),
            broadcasts = listOf("ESPN"),
        )

        val feeds = SportsChannelMatcher.matches(
            game = game,
            channels = listOf(rsn, espn),
            programs = listOf(program("rsn", "Tampa Bay vs Florida", firstPitch, firstPitch + 180 * minute)),
        )

        assertEquals("the game's own network is ahead of a city-only guess", listOf(espn, rsn), feeds)
    }

    @Test
    fun `a guide hit the broadcast name cannot resolve adds no fill-in`() {
        // ESPN+ is a streaming exclusive: it resolves to nothing on either path,
        // so an ESPN+-streamed game is still found by the guide alone.
        val rsn = channel("rsn", "Local Sports")
        val game = game(yankees, redSox, broadcasts = listOf("ESPN+"))

        val feeds = SportsChannelMatcher.matches(
            game = game,
            channels = listOf(rsn),
            programs = listOf(program("rsn", "Yankees vs. Red Sox", firstPitch, firstPitch + 180 * minute)),
        )

        assertEquals(listOf(rsn), feeds)
    }

    // ── Tier 1b: the description names both teams ───────────────────
    //
    // Providers routinely title a game generically ("NHL Hockey") and name the
    // two teams only in the synopsis, so the title pass alone leaves those
    // games unmatched. The description is read only when no title does, and it
    // obeys the same both-teams rule.

    @Test
    fun `tier one matches the description when the title names no team`() {
        val rsn = channel("rsn", "Local Sports")
        val game = game(
            team("TB", "Tampa Bay Lightning", home = false),
            team("FLA", "Florida Panthers", home = true),
        )

        val hit = SportsChannelMatcher.match(
            game = game,
            channels = listOf(rsn),
            programs = listOf(
                program(
                    "rsn",
                    "NHL Hockey",
                    firstPitch - minute,
                    firstPitch + 3 * 60 * minute,
                    description = "The Tampa Bay Lightning visit the Florida Panthers at Amerant Bank Arena.",
                )
            ),
        )

        assertEquals(rsn, hit)
    }

    @Test
    fun `a title hit beats a description hit for the same game`() {
        // A synopsis naming both teams can be a preview show; the title naming
        // both is the game itself.
        val byTitle = channel("title", "Title Channel")
        val byDesc = channel("desc", "Description Channel")

        val hit = SportsChannelMatcher.match(
            game = game(yankees, redSox),
            channels = listOf(byDesc, byTitle),
            programs = listOf(
                program(
                    "desc",
                    "MLB Baseball",
                    firstPitch - minute,
                    firstPitch + 3 * 60 * minute,
                    description = "Yankees and Red Sox meet tonight.",
                ),
                program("title", "Yankees vs. Red Sox", firstPitch, firstPitch + 3 * 60 * minute),
            ),
        )

        assertEquals(byTitle, hit)
    }

    @Test
    fun `a description naming only one team does not match`() {
        val espn = channel("espn", "ESPN")

        val hit = SportsChannelMatcher.match(
            game = game(yankees, redSox),
            channels = listOf(espn),
            programs = listOf(
                program(
                    "espn",
                    "MLB Baseball",
                    firstPitch,
                    firstPitch + 3 * 60 * minute,
                    description = "The Yankees look to extend their winning streak.",
                )
            ),
        )

        assertNull(hit)
    }

    // ── Team-name variants: city alone, short forms ────────────────

    @Test
    fun `both cities alone name the game`() {
        val rsn = channel("rsn", "Local Sports")
        val game = game(
            team("TB", "Tampa Bay Lightning", home = false),
            team("FLA", "Florida Panthers", home = true),
        )

        val hit = SportsChannelMatcher.match(
            game = game,
            channels = listOf(rsn),
            programs = listOf(program("rsn", "Tampa Bay vs Florida", firstPitch, firstPitch + 3 * 60 * minute)),
        )

        assertEquals(rsn, hit)
    }

    @Test
    fun `a lone city does not name anything`() {
        // "Tampa Bay Travel Guide" is not a hockey game, and one city alone must
        // never be enough.
        val travel = channel("travel", "Travel Channel")
        val game = game(
            team("TB", "Tampa Bay Lightning", home = false),
            team("FLA", "Florida Panthers", home = true),
        )

        val hit = SportsChannelMatcher.match(
            game = game,
            channels = listOf(travel),
            programs = listOf(
                program("travel", "Tampa Bay Travel Guide", firstPitch, firstPitch + 3 * 60 * minute)
            ),
        )

        assertNull(hit)
    }

    @Test
    fun `separators like vs and at are tokenized away`() {
        // "vs"/"v"/"at" must not stand in the way of the two names on either
        // side: the tokenizer splits on everything that is not a letter/digit.
        val rsn = channel("rsn", "Local Sports")
        val game = game(
            team("TB", "Tampa Bay Lightning", home = false),
            team("FLA", "Florida Panthers", home = true),
        )
        listOf("Lightning vs Panthers", "Lightning v Panthers", "Lightning at Panthers")
            .forEach { title ->
                assertEquals(
                    title,
                    rsn,
                    SportsChannelMatcher.match(
                        game = game,
                        channels = listOf(rsn),
                        programs = listOf(program("rsn", title, firstPitch, firstPitch + 3 * 60 * minute)),
                    )
                )
            }
    }

    @Test
    fun `the short-form map ships empty until a miss cites an entry`() {
        // The map is grown from observed EPG misses, never from imagination: an
        // entry without a cited real title does not belong in it.
        assertEquals(emptyMap<String, String>(), SportsChannelMatcher.TEAM_SHORT_FORMS)
    }

    // ── The feed's own short name ──────────────────────────────────

    @Test
    fun `a guide that titles a game by the feed's short name matches`() {
        // College football: ESPN's display name is "Washington Huskies", but a
        // guide titles the game "Washington vs Iowa" - the short name, not the
        // last word "Huskies". Before the short name was a tier variant this
        // game could not be found on any channel, however many carried it.
        val rsn = channel("rsn", "Local Sports")
        val game = game(
            team("WASH", "Washington Huskies", home = false, shortName = "Washington"),
            team("IOWA", "Iowa Hawkeyes", home = true, shortName = "Iowa"),
        )

        val hit = SportsChannelMatcher.match(
            game = game,
            channels = listOf(rsn),
            programs = listOf(
                program("rsn", "Washington vs Iowa", firstPitch, firstPitch + 3 * 60 * minute)
            ),
        )

        assertEquals(rsn, hit)
    }

    @Test
    fun `a soccer club is found by its short name, not the last word of its full one`() {
        // "Leeds United"'s last word is "United": a guide writes "Leeds", and
        // that is the name the feed's short name carries. Same for a club whose
        // display name is a single word - the short name is itself.
        val nbc = channel("nbc", "NBC Sports")
        val game = game(
            team("LEE", "Leeds United", home = false, shortName = "Leeds"),
            team("ARS", "Arsenal", home = true, shortName = "Arsenal"),
        )

        val hit = SportsChannelMatcher.match(
            game = game,
            channels = listOf(nbc),
            programs = listOf(
                program("nbc", "Leeds United v Arsenal", firstPitch, firstPitch + 3 * 60 * minute)
            ),
        )

        assertEquals(nbc, hit)
    }

    @Test
    fun `the short name is additive, so a nickname-only title still matches`() {
        // The full name's last word stays a variant: a guide that writes the
        // mascot alone keeps matching exactly as it did before.
        val rsn = channel("rsn", "Local Sports")
        val game = game(
            team("WASH", "Washington Huskies", home = false, shortName = "Washington"),
            team("IOWA", "Iowa Hawkeyes", home = true, shortName = "Iowa"),
        )

        val hit = SportsChannelMatcher.match(
            game = game,
            channels = listOf(rsn),
            programs = listOf(
                program("rsn", "Huskies vs Hawkeyes", firstPitch, firstPitch + 3 * 60 * minute)
            ),
        )

        assertEquals(rsn, hit)
    }

    @Test
    fun `one side's short name alone still does not name a game`() {
        // The rule is unchanged by the new variant: BOTH sides must be named, so
        // a show about one of them is still not the game.
        val travel = channel("travel", "Travel Channel")
        val game = game(
            team("WASH", "Washington Huskies", home = false, shortName = "Washington"),
            team("IOWA", "Iowa Hawkeyes", home = true, shortName = "Iowa"),
        )

        val hit = SportsChannelMatcher.match(
            game = game,
            channels = listOf(travel),
            programs = listOf(
                program("travel", "Washington Travel Guide", firstPitch, firstPitch + 3 * 60 * minute)
            ),
        )

        assertNull(hit)
    }

    // ── The viewer's own correction memory ──────────────────────────

    @Test
    fun `a remembered channel wins over every tier`() {
        // Tier 2 would answer "ESPN"; the viewer's own past pick overrides it.
        val yes = channel("yes", "YES Network")
        val espn = channel("espn", "ESPN")
        val game = game(yankees, redSox, broadcasts = listOf("ESPN"))
        val remembered: (String) -> IptvChannel? = { key -> yes.takeIf { key == game.home.favoriteKey } }

        val hit = SportsChannelMatcher.match(
            game = game,
            channels = listOf(espn, yes),
            programs = emptyList(),
            remembered = remembered,
        )

        assertEquals(yes, hit)
    }

    @Test
    fun `a remembered channel that is gone falls through to the tiers`() {
        val espn = channel("espn", "ESPN")
        val dead = channel("dead", "Gone Channel")
        val game = game(yankees, redSox, broadcasts = listOf("ESPN"))
        val remembered: (String) -> IptvChannel? = { dead }

        val hit = SportsChannelMatcher.match(
            game = game,
            channels = listOf(espn),
            programs = emptyList(),
            remembered = remembered,
        )

        assertEquals(espn, hit)
    }

    @Test
    fun `a remembered channel leads the list and keeps the other feeds behind it`() {
        // Reported 2026-10-11: "i clicked one of the backup links it was wrong so
        // i tried to go back and click the other backup link and it wont let me
        // see them now or scroll them". The recall returned the picked channel
        // ALONE, so the pass after a correction deleted every other feed for the
        // game: the sheet's BACKUP CHANNELS rows vanished and the one feed the
        // viewer had just rejected was the only one left to press.
        val yes = channel("yes", "YES Network")
        val espn = channel("espn", "ESPN")
        val game = game(yankees, redSox, broadcasts = listOf("ESPN"))
        val remembered: (String) -> IptvChannel? = { key -> yes.takeIf { key == game.home.favoriteKey } }

        val feeds = SportsChannelMatcher.matches(
            game = game,
            channels = listOf(espn, yes),
            programs = emptyList(),
            remembered = remembered,
        )

        assertEquals(
            "the pick is the head, and the tier's own feed is still there behind it",
            listOf(yes, espn),
            feeds
        )
    }

    @Test
    fun `a remembered channel leads the guide's own hits too`() {
        // The same rule when the tier that answers is the guide rather than the
        // network name: the correction reorders, it does not prune.
        val yes = channel("yes", "YES Network")
        val espn = channel("espn", "ESPN")
        val game = game(yankees, redSox)
        val remembered: (String) -> IptvChannel? = { key -> yes.takeIf { key == game.home.favoriteKey } }

        val feeds = SportsChannelMatcher.matches(
            game = game,
            channels = listOf(espn, yes),
            programs = listOf(
                program("espn", "Yankees vs. Red Sox", firstPitch, firstPitch + 180 * minute)
            ),
            remembered = remembered,
        )

        assertEquals(listOf(yes, espn), feeds)
    }

    // ── The pass's channel index ────────────────────────────────
    //
    // The broadcast tier is a LOCAL scan of the playlist - a matching pass makes
    // no request at all - and on a real provider it was the whole of a 70-second
    // pass: every card re-normalized every channel's name before it compared
    // anything, so sixty cards over forty thousand channels rebuilt millions of
    // identical strings. [SportsChannelMatcher.ChannelIndex] normalizes them once
    // for a pass instead.
    //
    // What is pinned here is that the hoist is invisible from the outside. "Same
    // matches, paid for once" is the entire contract, and a card that finds a
    // different channel is the one failure this file exists to prevent.

    @Test
    fun `a shared index answers a card exactly as one built for it would`() {
        // The hub hands ONE index to every card in a pass; the matcher's own
        // default builds a fresh one per call. Both must answer identically, or
        // the pass's speed was bought with its matches.
        val channels = listOf(
            channel("espn", "ESPN"),
            channel("espn2", "ESPN2"),
            channel("espnhd", "ESPN HD"),
            channel("fox", "FOX Sports 1"),
            channel("yes", "YES Network"),
            channel("nesn", "NESN"),
            channel("poker", "Poker Central"),
        )
        val shared = SportsChannelMatcher.ChannelIndex.of(channels)

        listOf(
            game(yankees, redSox, broadcasts = listOf("ESPN")),
            game(yankees, redSox, broadcasts = listOf("FS1")),
            game(yankees, redSox, broadcasts = listOf("Nobody Carries This")),
            game(yankees, redSox),
        ).forEach { card ->
            assertEquals(
                "a card must match the same feeds however its index was built",
                SportsChannelMatcher.matches(card, channels, emptyList()),
                SportsChannelMatcher.matches(card, channels, emptyList(), channelIndex = shared),
            )
        }
    }

    @Test
    fun `a network family is resolved once for the pass, not once per card`() {
        val espn = channel("espn", "ESPN")
        val espn2 = channel("espn2", "ESPN2")
        val fox = channel("fox", "FOX Sports 1")
        val index = SportsChannelMatcher.ChannelIndex.of(listOf(espn, espn2, fox))

        assertSame(
            "ESPN is on a dozen cards and its family is scanned for once",
            index.channelsFor("ESPN"),
            index.channelsFor("ESPN"),
        )
        assertEquals(
            "the family behind the head, strongest first: the exact name, then its siblings",
            listOf(espn, espn2),
            index.channelsFor("ESPN"),
        )
        assertEquals(
            "and a broadcast named by its alias still meets a channel named by its long form",
            listOf(fox),
            index.channelsFor("FS1"),
        )
    }

    @Test
    fun `the index carries the pass's ids, which is what fails a stale correction through`() {
        val index = SportsChannelMatcher.ChannelIndex.of(
            listOf(channel("espn", "ESPN"), channel("poker", "Poker Central"))
        )

        assertEquals(setOf("espn", "poker"), index.ids)
        assertEquals(emptySet<String>(), SportsChannelMatcher.ChannelIndex.EMPTY.ids)
    }
}
