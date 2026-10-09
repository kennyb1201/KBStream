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
}
