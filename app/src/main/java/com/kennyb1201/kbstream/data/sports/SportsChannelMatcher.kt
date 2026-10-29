package com.kennyb1201.kbstream.data.sports

import com.kennyb1201.kbstream.data.iptv.IptvChannel
import kotlin.math.abs

/**
 * One EPG program, as the matcher sees it.
 *
 * Deliberately not the guide's own row types: the matcher needs a few facts per
 * program - which channel airs it, what it is called, what it is about, and when
 * it runs - and taking those by value keeps the rule pure and testable without a
 * database, a playlist or a ViewModel.
 */
data class MatcherProgram(
    val channelId: String,
    val title: String,
    val startMs: Long,
    val endMs: Long,
    /**
     * The guide's own synopsis, where it has one.
     *
     * Providers routinely title a game generically ("NHL Hockey") and name the
     * two teams only in the description ("The Tampa Bay Lightning visit the
     * Florida Panthers at Amerant Bank Arena."). Tier 1 reads it as a strictly
     * WEAKER signal than the title - a synopsis naming both teams can be a
     * preview show, while a title naming both is the game itself.
     */
    val description: String? = null,
)

/**
 * A game's two sides' name forms, resolved ONCE for a matching pass.
 *
 * The tiers used to call [SportsChannelMatcher.strongVariants] for every program
 * they looked at, which on a full slate is tens of thousands of rebuilds of the
 * same two teams' word-lists - the single biggest cost in the match loop. The
 * forms do not change between programs, so the caller builds this once per game
 * and hands it back on each call; the matching rules themselves are untouched.
 */
data class TeamVariants(
    val away: List<List<String>>,
    val home: List<List<String>>,
)

/**
 * Picks the playlist channel that is carrying a game, or null.
 *
 * Tiers, strongest first, and it never guesses past them: a wrong game on the
 * wrong channel is worse than "Not in your playlist".
 *
 *  0. **The viewer's own past pick.** If they once chose a channel for a game
 *     involving either team, that choice is remembered and wins outright (see
 *     the `remembered` argument). A pick that is gone from the playlist falls
 *     through to the tiers below.
 *  1. **EPG program match.** The program airing at the game's start names BOTH
 *     teams - first in its TITLE, and then in its DESCRIPTION (a synopsis can
 *     be a preview show, so it is the weaker text). This finds the game on any
 *     network - national, an RSN, or a team channel - without knowing the
 *     network in advance. A single-team hit is a pregame show or a repeat, not
 *     the game, and a hit that names one side by its CITY alone is weaker
 *     still: it is only used when no full hit exists (see [guideFeeds]). That
 *     description pass is what the tests call tier 1b; the name pass below is
 *     1c.
 *  1c. **Channel-name matchup.** The channel's OWN NAME names the game - a
 *     per-game League Pass feed called "Timberwolves @ Magic". This runs on
 *     every game, not as a fallback: a provider generates that name for this
 *     game tonight, so it is carriage evidence, while a guide row is a schedule
 *     claim that outlives schedule changes. It is also the only tier that can
 *     see a matchup channel with NO guide data at all, which is how those feeds
 *     ship (see [nameHits]).
 *  2. **Broadcast network match.** ESPN's own `broadcasts[].names` resolved
 *     against the playlist by normalized name. "ESPN" must not land on "ESPN2"
 *     - or on "ESPN AU" - when an ESPN feed is in the playlist: exact, then the
 *     qualified domestic feed, then the international variant, then the family.
 *     A streaming-exclusive name ([STREAMING_EXCLUSIVES]) never matches here at
 *     all - no cable channel carries it - so a game ESPN+ streams is found by
 *     the EPG tier (the RSN or local channel simulcasting it) or the team's own
 *     RSN. If tier 1 keeps missing those, that is a bug in [guideFeeds] (team-name
 *     variants, window width) to fix THERE, not a reason to widen tier 2.
 *
 * The two are not exclusive. The guide says WHAT is airing, the broadcast list
 * says WHERE the game is carried, and a viewer reads both as facts about their
 * own playlist - so the feeds the named network resolves to lead the guide's
 * hits when the guide agrees with the network, and are offered ahead of a
 * stranger's hit when it does not (see [guideFeeds]).
 *  3. **Team RSN fallback.** A small static map of team abbreviation -> the
 *     regional networks that carry them, home team first. Last resort, and
 *     best-effort by design.
 *
 * [matches] returns the same answer as a LIST: every confident hit, strongest
 * first, so a provider that airs one game on two feeds has a backup to fall to
 * and the sheet can offer it. [match] is its head, unchanged - a backup can
 * never displace the feed a card used to play.
 *
 * Pure: no I/O, no clock of its own, no Compose. [SportsChannelMatcherTest] pins
 * every tier and the ordering between them.
 */
internal object SportsChannelMatcher {

    /**
     * How far either side of a game's scheduled start a matching EPG program may
     * sit. Broadcasts routinely start a few minutes late, and a provider's
     * schedule is not to the second; 45 minutes is wide enough for the real
     * drift and narrow enough that the previous or next program cannot qualify.
     */
    const val EPG_WINDOW_MS = 45L * 60_000L

    /**
     * How many feeds one game keeps.
     *
     * Four is a long enough ladder to reach a working feed and short enough to
     * stay honest: the player itself auto-advances over at most two of them (see
     * its own switch cap), and a sheet offering ten rows of "maybe the game is
     * on this one" would be guessing in public. The primary is always first, so
     * the cap can never change what a card plays.
     */
    const val MAX_MATCHES = 4

    /**
     * Known national networks, normalized form -> canonical form.
     *
     * Only the forms that genuinely differ are listed (a broadcast "FS1" against
     * a playlist's "FOX Sports 1"); a name that normalizes to itself needs no
     * entry, which is why most of the aliases above the table are absent.
     */
    private val NETWORK_ALIASES: Map<String, String> = mapOf(
        "foxsports1" to "fs1",
        "foxsports2" to "fs2",
        "nbcsn" to "nbcsports",
        "nbcsportsnetwork" to "nbcsports",
        "nbcsportnetwork" to "nbcsports",
        "cbssportsnetwork" to "cbssn",
        "nbatelevision" to "nbatv",
        "nflnet" to "nflnetwork",
    )

    /**
     * Streaming services with no playlist equivalent.
     *
     * A broadcast naming one must not resolve to a similarly-named cable channel:
     * "ESPN+" is not "ESPN", and matching it there plays the WRONG channel with
     * confidence - the exact failure tier 2 is built to avoid. These services are
     * exclusive to their own app, so there is nothing in a provider's cable lineup
     * to match; returning nothing lets the caller fall through to the EPG tier
     * (the local/RSN simulcast a guide row names) and then the team RSN map.
     *
     * Data, not code: a service that turns out to simulcast is removed here, one
     * line. "Prime Video" is deliberately absent - Thursday Night Football is also
     * on local channels, and the EPG tier finds those - so only services whose
     * games are exclusive to the app belong.
     */
    private val STREAMING_EXCLUSIVES: Set<String> = setOf(
        "espn+",
        "appletv+",
        "peacock",
        "dazn",
        "paramount+",
    )

    /**
     * Two-letter region codes marking a channel as a non-domestic feed of its
     * brand ("ESPN AU"). Only codes that cannot be mistaken for ordinary words:
     * "in", "us", "it", "is" and their kin are deliberately absent, so a name
     * like "ESPN Inside" can never read as international.
     *
     * Data, not code: grown from observed misses like STREAMING_EXCLUSIVES and
     * TEAM_SHORT_FORMS - "au" is the Georgia/Auburn miss of 2026-10-17, when the
     * hub served ESPN AU for an ESPN broadcast. A code earns its place the same
     * way: a real card that picked the wrong region's feed.
     */
    private val INTERNATIONAL_REGION_CODES: Set<String> = setOf("au", "uk", "ca", "nz", "ie")

    /** True when the name's last token is a region code from [INTERNATIONAL_REGION_CODES]. */
    private fun List<String>.isInternationalVariant(): Boolean =
        lastOrNull()?.let { it.length == 2 && it in INTERNATIONAL_REGION_CODES } == true

    /**
     * Alternate names a provider's EPG uses in place of a team's own, keyed by
     * the short form seen in the guide and valued with the team's full name it
     * stands for ("TBL" -> "Tampa Bay Lightning").
     *
     * EMPTY on purpose. An entry goes in only when a real EPG title that missed
     * proves the need, and the title goes in the comment beside it - this is data
     * grown from observed misses, never from imagination. [strongVariants] reads
     * it, so an entry here is the last team-name variant before city-alone.
     */
    val TEAM_SHORT_FORMS: Map<String, String> = emptyMap()

    /**
     * `LEAGUE:ABBR` -> the regional networks that carry the team.
     *
     * Data, not code: a league's RSN situation changes every year and this is
     * the only part of the matcher that needs editing when it does. It is the
     * LAST resort behind a real EPG match and a real network name.
     *
     * The asymmetry is the point, and it is why this stays small: a MISSING
     * entry costs nothing but a "not in your playlist" on a game that had no
     * other signal, while a WRONG one offers a feed that is not carrying the
     * game - so a team whose own regional network is not known here is
     * deliberately absent rather than guessed. The NHL block was added whole
     * (2026-10-27): the league had no entries at all, so every regional hockey
     * game fell straight through to "not in your playlist" whenever the guide
     * and the feed's own network names both missed, which for a regional
     * hockey game on an IPTV lineup is the ordinary case (audit C12's remaining
     * half - the league-blind key was the other half, already fixed).
     *
     * The league is in the key because an abbreviation is not unique across
     * them (see [rsnNetworks]): `SF` belongs to one team here and could be
     * another league's tomorrow, and `CHI`/`CWS` share a city outright.
     */
    val TEAM_RSN: Map<String, List<String>> = mapOf(
        // Keys are "LEAGUE:ABBR" (the league path's last segment, upper-cased):
        // see rsnNetworks for why the league is part of the key rather than the
        // abbreviation alone.
        // MLB
        "MLB:NYY" to listOf("YES Network"),
        "MLB:NYM" to listOf("SNY"),
        "MLB:BOS" to listOf("NESN"),
        "MLB:LAD" to listOf("Spectrum SportsNet LA"),
        "MLB:LAA" to listOf("FanDuel Sports Network West", "Bally Sports West"),
        "MLB:CHC" to listOf("Marquee Sports Network"),
        "MLB:CWS" to listOf("Chicago Sports Network"),
        "MLB:PHI" to listOf("NBC Sports Philadelphia"),
        "MLB:SF" to listOf("NBC Sports Bay Area"),
        "MLB:SEA" to listOf("Root Sports Northwest"),
        "MLB:BAL" to listOf("MASN"),
        "MLB:WSH" to listOf("MASN"),
        "MLB:ATL" to listOf("FanDuel Sports Network South"),
        "MLB:DET" to listOf("FanDuel Sports Network Detroit"),
        "MLB:CLE" to listOf("FanDuel Sports Network Great Lakes"),
        "MLB:MIN" to listOf("FanDuel Sports Network North"),
        "MLB:KC" to listOf("FanDuel Sports Network Kansas City"),
        "MLB:STL" to listOf("FanDuel Sports Network Midwest"),
        "MLB:HOU" to listOf("Space City Home Network"),
        "MLB:TEX" to listOf("FanDuel Sports Network Southwest"),
        "MLB:COL" to listOf("Rockies.TV"),
        "MLB:SD" to listOf("Padres.TV"),
        "MLB:ARI" to listOf("Diamondbacks.TV"),
        "MLB:MIL" to listOf("FanDuel Sports Network Wisconsin"),
        "MLB:PIT" to listOf("SportsNet Pittsburgh"),
        "MLB:CIN" to listOf("FanDuel Sports Network Ohio"),
        "MLB:TOR" to listOf("Sportsnet"),
        // NBA
        "NBA:LAL" to listOf("Spectrum SportsNet"),
        "NBA:LAC" to listOf("FanDuel Sports Network SoCal"),
        "NBA:BKN" to listOf("YES Network"),
        "NBA:POR" to listOf("Root Sports Plus"),
        // The rest of the league (2026-10-27): four teams had entries, so a
        // regional NBA game outside those four markets had nothing behind the
        // guide. Same rule as the NHL block below - the team's own network, or
        // no entry.
        "NBA:ATL" to listOf("FanDuel Sports Network Southeast"),
        "NBA:BOS" to listOf("NBC Sports Boston"),
        "NBA:CHA" to listOf("FanDuel Sports Network Southeast"),
        "NBA:CHI" to listOf("Chicago Sports Network"),
        "NBA:CLE" to listOf("FanDuel Sports Network Ohio"),
        "NBA:DAL" to listOf("FanDuel Sports Network Southwest"),
        "NBA:DEN" to listOf("Altitude"),
        "NBA:DET" to listOf("FanDuel Sports Network Detroit"),
        "NBA:GS" to listOf("NBC Sports Bay Area"),
        "NBA:HOU" to listOf("Space City Home Network"),
        "NBA:IND" to listOf("FanDuel Sports Network Indiana"),
        "NBA:MEM" to listOf("FanDuel Sports Network Southeast"),
        "NBA:MIA" to listOf("FanDuel Sports Network Sun"),
        "NBA:MIL" to listOf("FanDuel Sports Network Wisconsin"),
        "NBA:MIN" to listOf("FanDuel Sports Network North"),
        "NBA:NYK" to listOf("MSG Network"),
        "NBA:OKC" to listOf("FanDuel Sports Network Oklahoma"),
        "NBA:ORL" to listOf("FanDuel Sports Network Florida"),
        "NBA:PHI" to listOf("NBC Sports Philadelphia"),
        "NBA:SA" to listOf("FanDuel Sports Network Southwest"),
        "NBA:SAC" to listOf("NBC Sports California"),
        "NBA:TOR" to listOf("Sportsnet"),
        "NBA:WAS" to listOf("Monumental Sports Network"),
        // NFL
        "NFL:NYG" to listOf("MSG Network"),
        "NFL:NYJ" to listOf("MSG Network"),
        "NFL:NE" to listOf("NBC Sports Boston"),
        "NFL:CHI" to listOf("Chicago Sports Network"),
        // NHL
        "NHL:ANA" to listOf("FanDuel Sports Network SoCal"),
        "NHL:BOS" to listOf("NESN"),
        "NHL:BUF" to listOf("MSG Network"),
        "NHL:CAR" to listOf("FanDuel Sports Network South"),
        "NHL:CBJ" to listOf("FanDuel Sports Network Ohio"),
        "NHL:CHI" to listOf("Chicago Sports Network"),
        "NHL:COL" to listOf("Altitude"),
        "NHL:DAL" to listOf("FanDuel Sports Network Southwest"),
        "NHL:DET" to listOf("FanDuel Sports Network Detroit"),
        "NHL:FLA" to listOf("FanDuel Sports Network Florida"),
        "NHL:LA" to listOf("FanDuel Sports Network West"),
        "NHL:MIN" to listOf("FanDuel Sports Network North"),
        "NHL:NJ" to listOf("MSG Sportsnet"),
        "NHL:NSH" to listOf("FanDuel Sports Network South"),
        "NHL:NYI" to listOf("MSG Sportsnet"),
        "NHL:NYR" to listOf("MSG Network"),
        "NHL:PHI" to listOf("NBC Sports Philadelphia"),
        "NHL:PIT" to listOf("SportsNet Pittsburgh"),
        "NHL:SJ" to listOf("NBC Sports California"),
        "NHL:STL" to listOf("FanDuel Sports Network Midwest"),
        "NHL:TB" to listOf("FanDuel Sports Network Sun"),
        "NHL:WSH" to listOf("Monumental Sports Network"),
    )

    /** The channel carrying [game], or null when no tier is confident. */
    fun match(
        game: SportsGame,
        channels: List<IptvChannel>,
        programs: List<MatcherProgram>,
        remembered: (String) -> IptvChannel? = { null },
    ): IptvChannel? = matches(game, channels, programs, remembered).firstOrNull()

    /**
     * Every channel the playlist carries [game] on, strongest first.
     *
     * The head is exactly what [match] has always returned; everything behind it
     * is a feed the playlist also holds for the same game, in the order a viewer
     * would want to fall to: the rest of tier 1 (another channel the guide says
     * is airing it), a channel whose own name names the matchup, then the
     * networks the feed names, then the teams' own regional networks - and, when
     * nothing stronger answered, a lone team channel.
     *
     * [remembered] is the viewer's own correction memory, keyed by a team's
     * [SportsTeam.favoriteKey]. It is a lambda so the matcher stays pure - the
     * caller owns the storage - and a hit LEADs the list: the tiers below still
     * run, so the other feeds the playlist holds for the same game stay behind
     * it and the sheet still has backups to offer (see [leadWith]).
     */
    fun matches(
        game: SportsGame,
        channels: List<IptvChannel>,
        programs: List<MatcherProgram>,
        remembered: (String) -> IptvChannel? = { null },
        /**
         * The id -> channel map the caller already built for this pass. Defaults
         * to building it here, which is what a one-off call wants; a pass over a
         * whole slate passes the shared one and never rebuilds it per game.
         */
        channelById: Map<String, IptvChannel> = channels.associateBy { it.id },
        /**
         * The game's two sides' name forms, built once by the caller. Defaults to
         * resolving them here; see [TeamVariants] for why a pass must not let
         * this happen per program.
         */
        variants: TeamVariants = TeamVariants(
            away = strongVariants(game.away),
            home = strongVariants(game.home),
        ),
        /**
         * The pass's channel index, built once and handed to EVERY card the pass
         * matches (see [ChannelIndex]): the names it normalizes are the playlist's
         * and do not change under a pass. Defaults to building one here, which is
         * what a one-off call wants.
         */
        channelIndex: ChannelIndex = ChannelIndex.of(channels),
    ): List<IptvChannel> {
        if (channels.isEmpty()) return emptyList()
        // The viewer's own past pick beats every heuristic: if they once chose a
        // channel for a game involving either team, that channel LEADS the list.
        //
        // It leads the list rather than BEING it, and that distinction is the
        // fix for a dead end: a correction reorders the feeds, it does not
        // delete them. Returning the pick alone meant the pass after a
        // correction dropped every other channel for the game, so the sheet's
        // backup rows disappeared and the feed the viewer had just rejected was
        // the only one left to play - "i clicked one of the backup links it was
        // wrong so i tried to go back and click the other backup link and it
        // wont let me see them now". A pick that is gone from the playlist is
        // ignored and the tiers answer as they always did.
        val present = channelIndex.ids
        val recalled = listOfNotNull(
            remembered(game.home.favoriteKey),
            remembered(game.away.favoriteKey),
        ).filter { it.id in present }
        val guide = guideFeeds(game, programs, channelById, variants, channelIndex)
        // The channel-name tier ALWAYS runs, and it is not a fallback for an
        // empty guide. The field case (2026-10-23) is exactly why: a
        // Timberwolves @ Magic game listed two team channels whose guide rows
        // named the matchup, so the guide answered and the list closed on two
        // feeds that were not carrying it - while the matchup-named League Pass
        // channel for the game, with no guide data at all, was never even looked
        // at. The guide cannot know a feed is dark; the channel's own name can
        // say it is carrying the game tonight.
        val named = nameHits(game, variants, channelIndex)
        if (guide.primary.isNotEmpty() || guide.strangers.isNotEmpty()) {
            // Where the names sit depends on what the guide found. Beside strong
            // guide hits they are the same evidence class, and they outrank a
            // STRANGER's guide row deliberately: a per-game generated channel
            // name is the provider asserting carriage of this game tonight, while
            // a stranger's guide row is a schedule claim that survives schedule
            // changes. When the guide only has WEAK rows (a city alone), a
            // both-teams name leads them, for the same reason.
            val ordered = if (guide.strong) {
                guide.primary + named.strong + guide.strangers
            } else {
                named.strong + guide.primary + guide.strangers
            }
            return leadWith(recalled, cap(ordered))
        }
        // Home before away: a regional playlist is likelier to carry the home
        // broadcast. Within a family, the best-named channel comes first.
        //
        // The families and the RSN feeds are kept apart and INTERLEAVED rather
        // than concatenated, because concatenation let a big family eat the
        // whole cap: ESPN + its variants could fill all four slots ahead of the
        // one RSN feed actually carrying the game (audit C13, 2026-10-23). The
        // head is unchanged - the first broadcast family's best-named channel is
        // still the pick - while the first RSN hit is now guaranteed a slot.
        val families = game.broadcastNames.flatMap { channelIndex.channelsFor(it) }
        val regionals = (rsnNetworks(game.home, game.league) + rsnNetworks(game.away, game.league))
            .flatMap { channelIndex.channelsFor(it) }
        val networks = buildList {
            families.firstOrNull()?.let(::add)
            regionals.firstOrNull()?.let(::add)
            addAll(families.drop(1))
            addAll(regionals.drop(1))
        }
        val networkHits = cap(networks)
        // A matchup name leads the networks (the provider asserted this game),
        // and a lone team channel trails them - a 24/7 channel is a viewer's
        // last resort, never a fabrication.
        return leadWith(recalled, cap(named.strong + networkHits + named.weak))
    }

    /**
     * The feeds a game or event answers with: the viewer's own recalled channel
     * (if there is one) first, then the tiers' hits behind it.
     *
     * The head is what a card plays and what the player's own ladder falls from;
     * the tail is what the sheet's BACKUP CHANNELS rows offer. So a correction
     * moves a feed to the front WITHOUT taking the alternatives away, which is
     * the one thing a viewer who has just picked the wrong feed needs. [cap]
     * de-duplicates, so a recalled channel a tier also found is listed once, at
     * the head.
     *
     * Empty [recalled] is the ordinary case and answers [tierHits] itself, so an
     * uncorrected game is byte-for-byte the list it was before this existed.
     */
    private fun leadWith(
        recalled: List<IptvChannel>,
        tierHits: List<IptvChannel>,
    ): List<IptvChannel> = if (recalled.isEmpty()) tierHits else cap(recalled + tierHits)

    /** The channel carrying a tournament event, or null. */
    fun match(
        event: TournamentEvent,
        channels: List<IptvChannel>,
        programs: List<MatcherProgram>,
        remembered: (String) -> IptvChannel? = { null },
    ): IptvChannel? = matches(event, channels, programs, remembered).firstOrNull()

    /**
     * The channels carrying a tournament event, strongest first. See [matches].
     *
     * [remembered] is the viewer's own correction memory, keyed by the event's
     * own [TournamentEvent.favoriteKey] - a tournament has no teams to key it by,
     * so its stable name is what a past pick hangs off. As on the game path it
     * leads the returned list and the tiers still run behind it, so the event
     * keeps its other feeds; a pick that is gone from the playlist falls through
     * to them unseen.
     */
    fun matches(
        event: TournamentEvent,
        channels: List<IptvChannel>,
        programs: List<MatcherProgram>,
        remembered: (String) -> IptvChannel? = { null },
        channelById: Map<String, IptvChannel> = channels.associateBy { it.id },
        /** The pass's channel index, as on the game path; see there. */
        channelIndex: ChannelIndex = ChannelIndex.of(channels),
        /**
         * The clock the guide rows are ranked against (see [epgNameHits]).
         * Defaulted so a one-off call needs no clock, and read once per call so
         * a pass cannot rank two rows against two different "now"s.
         */
        nowMs: Long = System.currentTimeMillis(),
    ): List<IptvChannel> {
        if (channels.isEmpty()) return emptyList()
        val present = channelIndex.ids
        // The tournament half of the same rule: the viewer's pick leads, the
        // tiers' other feeds stay behind it (see [leadWith]).
        val recalled = listOfNotNull(
            remembered(event.favoriteKey)?.takeIf { it.id in present }
        )
        val epg = epgNameHits(event, programs, channelById, nowMs)
        if (epg.isNotEmpty()) return leadWith(recalled, cap(epg))
        val networkHits = cap(event.broadcastNames.flatMap { channelIndex.channelsFor(it) })
        return leadWith(recalled, networkHits)
    }

    // ── Search ───────────────────────────────────────────────────────

    /**
     * Whether a search [query] names either side of [game].
     *
     * The hub's search field is a filter over cards the viewer can already see,
     * so it answers a different question from [matches] - "is this the game I
     * meant?" rather than "which channel carries it?" - and it does that with
     * the SAME team names the EPG tier reads: the forms [strongVariants]
     * resolves (full name, abbreviation, nickname, short-form aliases) and the
     * city alone, which [namesBothTeamsStrength] also accepts as a weaker form.
     * Nothing about a name lives here; [searchVariants] is the one place that
     * says what a team answers to.
     *
     * Partial input matches, because typing is incremental on a remote: see
     * [matchesWords] for the two shapes a query can have (half a name, or a
     * whole name inside something longer), and [searchVariants] for what a team
     * answers to.
     *
     * Two additions over [namesBothTeamsStrength], both because this is a
     * viewer typing rather than a program title: the feed's own short name
     * ([SportsTeam.shortName] - "Yankees", where the tier deliberately reads the
     * last word of the display name instead) and the city alone, both of which
     * are what a person actually types. The tiers are untouched by this: it is a
     * separate entry point, not a change to [strongVariants].
     */
    fun matchesQuery(game: SportsGame, query: String): Boolean {
        val typed = words(query)
        if (typed.isEmpty()) return false
        return namesQuery(typed, game.away) || namesQuery(typed, game.home)
    }

    /**
     * Whether a search [query] names [event] - the tournament twin of
     * [matchesQuery].
     *
     * A golf round or a Grand Prix has no two sides to name, so the event's own
     * name is what is matched (and its venue, which is how a viewer refers to a
     * race - "Silverstone").
     */
    fun matchesQuery(event: TournamentEvent, query: String): Boolean {
        val typed = words(query)
        if (typed.isEmpty()) return false
        return matchesWords(typed, words(event.name)) ||
            matchesWords(typed, words(event.venue.orEmpty()))
    }

    private fun namesQuery(typed: List<String>, team: SportsTeam): Boolean =
        searchVariants(team).any { variant -> matchesWords(typed, variant) }

    /**
     * The names a viewer may type to find [team].
     *
     * Everything [strongVariants] reads - which now includes the feed's short
     * name, so a guide and a search box agree on it - plus the city on its own,
     * which [matchesQuery] allows for search but a tier does not (see
     * [namesBothTeamsStrength]).
     */
    private fun searchVariants(team: SportsTeam): List<List<String>> = buildList {
        addAll(strongVariants(team))
        cityOf(team)?.let { add(it) }
    }.filter { it.isNotEmpty() }

    /**
     * True when [typed] names the name [name], in either of the two shapes a
     * half-typed query takes on a TV remote.
     *
     *  - **What was typed sits inside the name**, its last word allowed to be
     *    half-typed: "lak" -> "Los Angeles Lakers" (via its own "Lakers"),
     *    "open champ" -> "The Open Championship". The run may start anywhere in
     *    the name, because a viewer skips the "The".
     *  - **The whole name sits inside what was typed**, matched exactly - a
     *    matchup read off a card and typed back ("Lakers at Celtics" ->
     *    "Lakers").
     *
     * Word runs rather than raw substrings, so "lal" cannot match "Florida" and
     * a one-letter query cannot match every team with that letter anywhere in its
     * name.
     */
    private fun matchesWords(typed: List<String>, name: List<String>): Boolean =
        containsRun(needle = name, haystack = typed, partialLast = false) ||
            containsRun(needle = typed, haystack = name, partialLast = true)

    /**
     * Whether [needle] occurs as a consecutive run of words inside [haystack],
     * with its last word allowed to match only the start of the haystack's own
     * ([partialLast] - the half-typed word at the end of a query).
     */
    private fun containsRun(
        needle: List<String>,
        haystack: List<String>,
        partialLast: Boolean,
    ): Boolean {
        if (needle.isEmpty() || needle.size > haystack.size) return false
        for (start in 0..(haystack.size - needle.size)) {
            val hit = needle.withIndex().all { (index, word) ->
                val other = haystack[start + index]
                if (partialLast && index == needle.lastIndex) other.startsWith(word)
                else other == word
            }
            if (hit) return true
        }
        return false
    }

    /**
     * The caller's list: de-duplicated by channel and truncated to
     * [MAX_MATCHES], in the order it was handed in.
     *
     * De-duplicated here rather than in each tier because one channel can hit
     * twice - a provider that carries "ESPN" in its name AND airs the game on it
     * per the guide - and the same feed listed twice is not a second feed.
     */
    private fun cap(found: List<IptvChannel>): List<IptvChannel> {
        val seen = HashSet<String>(found.size)
        val out = ArrayList<IptvChannel>(minOf(found.size, MAX_MATCHES))
        found.forEach { channel ->
            if (out.size == MAX_MATCHES) return@forEach
            if (seen.add(channel.id)) out += channel
        }
        return out
    }

    // ── Tier 1 ───────────────────────────────────────────────────────

    /** One guide row that names both sides, and what ranking it needs. */
    private data class GuideHit(
        val channel: IptvChannel,
        /** |row start - game start|, the tiebreak INSIDE a group. */
        val distanceMs: Long,
        /** True when the row named both sides by a real name, not a city. */
        val strong: Boolean,
        /** True when the row's TITLE named them - the stronger text. */
        val title: Boolean,
    )

    /**
     * The guide's feeds for [game], ordered by what the game says about itself.
     *
     * The old rule answered with the title pass whenever it found anything and
     * never looked at descriptions at all, and the hub's tier 2 was skipped
     * outright the moment a guide row hit. Three defects composed into one
     * field report (Kenny, 2026-10-20): a Lightning game whose broadcast was
     * ESPN offered **ESPN+3**, plus **NFL RedZone**, and the plain **ESPN**
     * channel - the one the game named - was not in the list at all.
     *
     *  - ESPN's own guide row is generic ("NHL Hockey") with the teams in the
     *    DESCRIPTION, so the title hit on ESPN+3 suppressed it.
     *  - The network never got in at all: a non-empty guide list returned before
     *    the broadcast names were ever consulted.
     *  - The two rows started at the same minute, so guide order decided the
     *    head between them.
     *
     * So the hits are GROUPED by whether the game's broadcast names resolve to
     * them (the same [ChannelIndex.channelsFor] tier 2 uses, aliases and
     * [STREAMING_EXCLUSIVES] included), and the groups are ordered:
     *
     *  1. Agreeing channels - the network the game names, in the order that name
     *     resolved them, so the plain channel leads the variant that merely
     *     shares its name and both lead a stranger.
     *  2. The named network itself, when NO guide hit agrees - the network still
     *     gets its vote in the list, ahead of a stranger's claim.
     *  3. Everyone else. A TITLE hit beats a DESCRIPTION hit everywhere it can
     *     be seen (a stranger's rows, and two rows for one feed), and rows that
     *     start closest to kick-off come first when nothing else separates them.
     *     Agreeing feeds are ordered by the name's own resolution first: the
     *     field case is exactly that, where ESPN+3's title hit sat at the same
     *     minute as ESPN's description hit and the plain channel still had to
     *     lead.
     *
     * A WEAK hit - one side named by its city alone, which is how "Tampa Bay"
     * can read as the Buccaneers - is never mixed in with real hits: it is
     * dropped when any strong hit exists, and otherwise ranks below the
     * named network's fill-in. RedZone's weak hit in the field case is exactly
     * that, and it is gone.
     *
     * The grouping itself adds no work to the match loop: it partitions hit
     * lists it was handed and reads the pass's already-built [ChannelIndex].
     * What it does cost is the description half running ALONGSIDE the title half
     * rather than behind it, which is the point of rule A - the old short-circuit
     * is what hid ESPN's row - and it reads only the rows inside
     * [EPG_WINDOW_MS], the same window the description pass always read.
     *
     * The answer is split into the guide's LEADING group and its strangers (see
     * [GuideFeeds]) solely so the channel-name tier can be slotted between them;
     * the groups and their order are exactly the single list this used to return.
     */
    private fun guideFeeds(
        game: SportsGame,
        programs: List<MatcherProgram>,
        channelById: Map<String, IptvChannel>,
        variants: TeamVariants,
        channelIndex: ChannelIndex,
    ): GuideFeeds {
        val titleHits = guideHits(game, programs, channelById, variants, title = true) { it.title }
        val descriptionHits =
            guideHits(game, programs, channelById, variants, title = false) {
                it.description.orEmpty()
            }
        if (titleHits.isEmpty() && descriptionHits.isEmpty()) return GuideFeeds.EMPTY

        val agreement = broadcastAgreement(game, channelIndex)
        // Titles before descriptions, whatever group a hit lands in: the
        // preview-show doctrine is about the TEXT, not about the network.
        val hits = titleHits + descriptionHits
        val agreeing = hits.filter { it.channel.id in agreement }
        val strangers = hits.filter { it.channel.id !in agreement }

        val strongAgreeing = agreeing.filter { it.strong }
        val strongStrangers = strangers.filter { it.strong }
        // Any strong hit makes every weak one noise (rule C): a city alone is
        // not evidence beside a row that names both teams.
        if (strongAgreeing.isNotEmpty() || strongStrangers.isNotEmpty()) {
            val head = strongAgreeing.ordered(agreement)
            val fill = if (head.isEmpty()) broadcastFillIn(game, channelIndex) else emptyList()
            return GuideFeeds(
                primary = head + fill,
                strangers = strongStrangers.ordered(emptyMap()),
                strong = true,
            )
        }

        // Weak hits only: still a last resort, and still behind the network the
        // game names when none of them agrees with it.
        val weakAgreeing = agreeing.filterNot { it.strong }
        val weakStrangers = strangers.filterNot { it.strong }
        val head = weakAgreeing.ordered(agreement)
        val fill = if (head.isEmpty()) broadcastFillIn(game, channelIndex) else emptyList()
        return GuideFeeds(
            primary = head + fill,
            strangers = weakStrangers.ordered(emptyMap()),
            strong = false,
        )
    }

    /**
     * The guide's answer, SPLIT where the channel-name tier slots in.
     *
     * Same groups, same order inside each, as the single list this used to
     * return: [primary] is what the guide leads with (its agreeing hits, and the
     * named network's fill-in when nothing agreed) and [strangers] is everyone
     * else it placed. The split exists only so [matches] can put the channel-name
     * hits between the two, which is the one place the tiers now interleave.
     */
    private data class GuideFeeds(
        val primary: List<IptvChannel>,
        val strangers: List<IptvChannel>,
        /**
         * True when a STRONG hit existed anywhere - both sides by a real name.
         * Rule C applies inside the guide's own groups (weak guide rows are not
         * mixed in with strong ones); this only says which group [primary] holds,
         * so a both-teams channel name can lead a weak-only guide answer.
         */
        val strong: Boolean,
    ) {
        companion object {
            val EMPTY = GuideFeeds(emptyList(), emptyList(), strong = false)
        }
    }

    // ── Tier 1 diagnostics ───────────────────────────────────────────

    /** How many near-miss rows one game's trace names (see [traceTier1]). */
    private const val MAX_NEAR_MISS_DROPS = 3

    /**
     * What tier 1 SAW for [game], and where each of [listed] came from.
     *
     * Diagnostics only: nothing here decides a match, and the tiers above do not
     * read it. It exists because every gate between a guide row and a card
     * drops the row silently (see [SportsTier1Ledger]), so a field miss could not
     * be attributed to a gate - search coverage, the guide index, the playlist
     * map, or the grade - from a capture.
     *
     * One pass over the candidate rows, in the game's own window: each row's
     * grade (title first, description only when the title names nothing, exactly
     * as [guideFeeds] reads it), the STRONG/WEAK/NONE counts those grades add up
     * to, the rows whose channel is not in [channelById], and the best grade per
     * channel. A row that names ONE side is recorded as a NONE_GRADE near miss -
     * that shape ("NHL Hockey" plus a synopsis cut short) is precisely the row a
     * viewer expects to have matched.
     *
     * [listed] is then labelled by what produced it, strongest evidence first:
     * the guide row (title, description, or a weak city-only grade), the
     * channel's own matchup name, the game's own broadcast names, then the team's
     * regional network. Anything else is `other` - a feed the list carries for a
     * reason this trace cannot see (a remembered pick leads the list that way).
     */
    internal fun traceTier1(
        game: SportsGame,
        programs: List<MatcherProgram>,
        channelById: Map<String, IptvChannel>,
        variants: TeamVariants,
        channelIndex: ChannelIndex,
        listed: List<IptvChannel>,
    ): Tier1GameTrace {
        var strong = 0
        var weak = 0
        var none = 0
        val drops = ArrayList<Tier1Drop>()
        // Best guide grade per channel: 3 a STRONG title, 2 a STRONG description,
        // 1 a WEAK row (one side by its city).
        val guideGrade = HashMap<String, Int>()
        programs.forEach { program ->
            if (!overlapsWindow(program, game.dateMs)) return@forEach
            val titleTokens = words(program.title)
            val titleStrength = teamNameStrength(titleTokens, game.away, game.home, variants)
            val descriptionTokens = if (titleStrength == TeamNameStrength.NONE) {
                words(program.description.orEmpty())
            } else {
                emptyList()
            }
            val strength = if (titleStrength != TeamNameStrength.NONE) {
                titleStrength
            } else {
                teamNameStrength(descriptionTokens, game.away, game.home, variants)
            }
            when (strength) {
                TeamNameStrength.STRONG -> strong++
                TeamNameStrength.WEAK -> weak++
                TeamNameStrength.NONE -> none++
            }
            if (strength == TeamNameStrength.NONE) {
                val namesATeam =
                    namesStrongTeam(titleTokens, variants.away) ||
                        namesStrongTeam(titleTokens, variants.home) ||
                        namesStrongTeam(descriptionTokens, variants.away) ||
                        namesStrongTeam(descriptionTokens, variants.home)
                if (namesATeam && drops.size < MAX_NEAR_MISS_DROPS) {
                    drops += Tier1Drop(TIER1_GATE_NONE_GRADE, program.channelId, program.title)
                }
                return@forEach
            }
            if (channelById[program.channelId] == null) {
                drops += Tier1Drop(TIER1_GATE_CHANNEL_BY_ID, program.channelId, program.title)
                return@forEach
            }
            val grade = when {
                strength == TeamNameStrength.WEAK -> 1
                titleStrength != TeamNameStrength.NONE -> 3
                else -> 2
            }
            if (grade > (guideGrade[program.channelId] ?: 0)) guideGrade[program.channelId] = grade
        }
        val agreement = broadcastAgreement(game, channelIndex)
        val rsnIds = (rsnNetworks(game.home, game.league) + rsnNetworks(game.away, game.league))
            .flatMap { channelIndex.channelsFor(it) }
            .mapTo(HashSet()) { it.id }
        val listedTiers = listed.map { channel ->
            val name = channel.displayName.ifBlank { channel.name }
            val nameTier = when (nameGrade(words(name), game, variants)) {
                NameGrade.BOTH -> "name-matchup"
                NameGrade.ONE -> "name-team"
                NameGrade.NONE -> null
            }
            val tier = when {
                guideGrade[channel.id] == 3 -> "guide-title"
                guideGrade[channel.id] == 2 -> "guide-description"
                guideGrade[channel.id] == 1 -> "guide-weak"
                nameTier != null -> nameTier
                channel.id in agreement -> "network"
                channel.id in rsnIds -> "rsn"
                else -> "other"
            }
            name to tier
        }
        return Tier1GameTrace(
            strong = strong,
            weak = weak,
            none = none,
            drops = drops,
            listed = listedTiers,
        )
    }

    // ── Tier 1c: the channel's own name ─────────────────────────────

    /**
     * The playlist's channels whose own NAME names this game.
     *
     * The field case (Kenny, 2026-10-23): Timberwolves @ Magic, and the hub
     * listed an "Orlando Magic" channel and a "Timberwolves" channel - neither
     * showing the game - while the League Pass feed that WAS showing it, named
     * "Timberwolves @ Magic", carried no guide data at all and so was invisible
     * to every tier. That is not an unlucky playlist: providers name their
     * per-game feeds for the matchup and routinely ship them without EPG. The
     * name IS the carriage evidence, generated for this game tonight, and until
     * this tier nothing anywhere tested a channel's own name against the teams.
     *
     * Strength uses the guide's own machinery and its own two tests, so the two
     * tiers cannot disagree about what naming a team means:
     *
     *  - **Strong**: the name names BOTH sides ([namesBothTeamsStrength]'s
     *    both-teams test, cities allowed - "Timberwolves @ Magic", "MIN vs
     *    ORL"). Separators like `@`, `vs` and `at` are just words between the
     *    names, never team tokens.
     *  - **Weak**: the name names ONE side by a real name ([namesStrongTeam]) -
     *    a 24/7 team channel. A city alone is not enough: "Orlando" on its own
     *    is the Magic in one league and a tourism channel in another, the same
     *    reason the guide tier refuses a lone city.
     *  - Neither: nothing.
     *
     * Order inside each bucket is playlist order. Two strong names for one game
     * are both generated feeds for it (a provider's HD and SD spellings);
     * nothing here knows which is better, and the cap keeps the list honest.
     *
     * The names come from the pass's own [ChannelIndex], which normalized every
     * channel once already (see [ChannelIndex.forEachNamed]); this reads those
     * tokens and does no normalization of its own, so a full slate pays nothing
     * per game beyond the scan the network tier already does.
     */
    private fun nameHits(
        game: SportsGame,
        variants: TeamVariants,
        channelIndex: ChannelIndex,
    ): NameHits {
        val strong = ArrayList<IptvChannel>()
        val weak = ArrayList<IptvChannel>()
        channelIndex.forEachNamed { nameWords, channel ->
            when (nameGrade(nameWords, game, variants)) {
                NameGrade.BOTH -> strong += channel
                NameGrade.ONE -> weak += channel
                NameGrade.NONE -> Unit
            }
        }
        return NameHits(strong = strong, weak = weak)
    }

    /** What the channel-name tier found, in the two strengths [matches] orders by. */
    private data class NameHits(
        val strong: List<IptvChannel>,
        val weak: List<IptvChannel>,
    )

    /** How well a channel's own name names a game's two sides, per [nameHits]. */
    private enum class NameGrade {
        /** Both sides named: the provider generated this feed for tonight's game. */
        BOTH,

        /** One side named by a real name - a 24/7 team channel. */
        ONE,

        /** Neither: the name says nothing about this game. */
        NONE,
    }

    /**
     * [nameHits]' own rule, on a name the index already tokenized - and, for
     * [traceTier1], on a name read on the spot.
     *
     * One function so the tier and its trace cannot disagree about what naming
     * the game means: both sides, cities allowed ("Timberwolves @ Magic"), is
     * BOTH; one side by a real name is ONE; a city alone is not enough, and
     * neither is nothing.
     */
    private fun nameGrade(
        tokens: List<String>,
        game: SportsGame,
        variants: TeamVariants,
    ): NameGrade {
        val awayStrong = namesStrongTeam(tokens, variants.away)
        val homeStrong = namesStrongTeam(tokens, variants.home)
        val namesBoth =
            (awayStrong || cityNamesTeam(tokens, game.away)) &&
                (homeStrong || cityNamesTeam(tokens, game.home))
        return when {
            namesBoth -> NameGrade.BOTH
            awayStrong || homeStrong -> NameGrade.ONE
            else -> NameGrade.NONE
        }
    }

    /**
     * The rows whose [textOf] names both sides, with the facts the grouping
     * needs. [title] marks which pass this is, so a title hit outranks a
     * description hit for the same channel.
     */
    private fun guideHits(
        game: SportsGame,
        programs: List<MatcherProgram>,
        channelById: Map<String, IptvChannel>,
        variants: TeamVariants,
        title: Boolean,
        textOf: (MatcherProgram) -> String,
    ): List<GuideHit> = programs
        .asSequence()
        .filter { overlapsWindow(it, game.dateMs) }
        .mapNotNull { program ->
            val strength = namesBothTeamsStrength(textOf(program), game.away, game.home, variants)
            if (strength == TeamNameStrength.NONE) return@mapNotNull null
            val channel = channelById[program.channelId] ?: return@mapNotNull null
            GuideHit(
                channel = channel,
                distanceMs = abs(program.startMs - game.dateMs),
                strong = strength == TeamNameStrength.STRONG,
                title = title,
            )
        }
        .toList()

    /**
     * [hits] in the order their group reads: by the position the broadcast name
     * resolved them to, then title before description, then closest to
     * kick-off.
     *
     * The resolution position is what puts the NAMED feed ahead of its own
     * family - broadcast "ESPN" resolves to ESPN first and ESPN+3 second - which
     * is what the field report asked for: the plain channel leads, the variant
     * sits behind it as a corroborated backup. A stranger's hit has no position
     * (empty [agreement]), so the same ordering reads as "title first, then
     * closest", exactly as the old single-tier list did.
     */
    private fun List<GuideHit>.ordered(agreement: Map<String, Int>): List<IptvChannel> =
        sortedWith(
            compareBy<GuideHit>(
                { agreement[it.channel.id] ?: Int.MAX_VALUE },
                { if (it.title) 0 else 1 },
                { it.distanceMs }
            )
        ).map { it.channel }

    /**
     * The playlist channels the game's own broadcast names resolve to, with the
     * position each was resolved at (see [ChannelIndex.channelsFor]).
     *
     * The SAME path tier 2 takes, so an alias resolves here as it does there and
     * a streaming-exclusive name resolves to nothing here as it does there.
     */
    private fun broadcastAgreement(
        game: SportsGame,
        channelIndex: ChannelIndex,
    ): Map<String, Int> {
        if (game.broadcastNames.isEmpty()) return emptyMap()
        val rank = HashMap<String, Int>()
        game.broadcastNames.forEach { name ->
            channelIndex.channelsFor(name).forEachIndexed { index, channel ->
                rank.putIfAbsent(channel.id, index)
            }
        }
        return rank
    }

    /**
     * The named networks' own feeds, for rule B: used only when the guide has
     * hits but none of them is a channel the game's broadcast names resolve to.
     *
     * Broadcast names only - never the RSN map - because this is the game's own
     * claim about where it is carried, not the matcher's last-resort guess.
     */
    private fun broadcastFillIn(
        game: SportsGame,
        channelIndex: ChannelIndex,
    ): List<IptvChannel> = game.broadcastNames.flatMap { channelIndex.channelsFor(it) }

    /**
     * The channels whose guide row titles a tournament by name.
     *
     * NO TIME FILTER, deliberately - unlike a game, whose EPG tier only reads
     * rows inside [EPG_WINDOW_MS] of its kick-off. ESPN's own date for a golf,
     * tennis or racing event is ONE broadcast slot (the Baycurrent Classic is
     * dated 2026-10-08T04:00Z, a 1.5-hour window two days before its card was
     * read), while the tournament itself spans four days across several channels
     * and a tail of replays and highlights. Filtering by that slot therefore
     * killed every row the guide had for the event - 29 hits, all replays, all
     * outside the single-slot window - and the card read "not in your playlist"
     * over a playlist that was carrying it.
     *
     * So a tournament matches on its NAME alone, and the whole name at that (see
     * [words] and the sequence rule below): a replay or a highlight airing now
     * is a valid place to watch the tournament, and [TournamentEvent.dateMs]
     * stays only as the ordering key - the row whose start sits closest to
     * ESPN's slot comes first, exactly as before.
     */
    private fun epgNameHits(
        event: TournamentEvent,
        programs: List<MatcherProgram>,
        channelById: Map<String, IptvChannel>,
        nowMs: Long,
    ): List<IptvChannel> {
        val eventWords = words(event.name)
        if (eventWords.isEmpty()) return emptyList()
        val hits = programs.filter { program ->
            words(program.title).containsSequence(eventWords)
        }
        if (hits.isEmpty()) return emptyList()
        // Which of a multi-day event's guide rows is the one on now, not which
        // one ESPN dated nearest the event's slot.
        //
        // Distance alone (audit C9, 2026-10-23) let an ENDED day-1 row that
        // happened to start closer to the slot outrank the live day-3 row on the
        // event's own card - the card then offered a finished day's feed as its
        // primary. The three buckets are the viewer's own question: airing now,
        // still to come, already over; within the second the soonest start wins,
        // within the third the most recent end.
        val live = hits.filter { it.startMs <= nowMs && it.endMs >= nowMs }
        val upcoming = hits.filter { it.startMs > nowMs }.sortedBy { it.startMs }
        val finished = hits.filter { it.endMs < nowMs }.sortedByDescending { it.endMs }
        return (live + upcoming + finished)
            .mapNotNull { channelById[it.channelId] }
            .distinctBy { it.id }
    }

    private fun overlapsWindow(program: MatcherProgram, startMs: Long): Boolean =
        program.startMs <= startMs + EPG_WINDOW_MS && program.endMs >= startMs - EPG_WINDOW_MS

    /** How well a guide text names the game's two sides. */
    private enum class TeamNameStrength {
        /** At least one side is not named at all - or only by its city. */
        NONE,

        /** Both named, but at least one by its city alone. */
        WEAK,

        /** Both named by a real name ([strongVariants]). */
        STRONG,
    }

    /**
     * How well [text] names BOTH sides of the game.
     *
     * A side is named STRONGLY by its full name, abbreviation, nickname or a
     * short-form alias, or WEAKLY by its city alone. Both sides must be named,
     * and a city-alone hit only counts when the other side carries a real name
     * ("Tampa Bay vs Florida", "Tampa Bay vs Panthers") - a lone "Tampa Bay" is
     * a travel show, not a game, so it must never match on its own.
     *
     * The distinction existed only as a boolean until the field case: "Tampa
     * Bay" is also the Buccaneers, so a cross-sport row can weakly name a
     * Lightning game. [guideFeeds] uses the WEAK answer to keep those rows
     * behind every real hit instead of letting them into the list.
     */
    private fun namesBothTeamsStrength(
        text: String,
        away: SportsTeam,
        home: SportsTeam,
        variants: TeamVariants,
    ): TeamNameStrength = teamNameStrength(words(text), away, home, variants)

    /**
     * [namesBothTeamsStrength] on text that is already tokenized.
     *
     * Split out for [traceTier1], which needs the grade of a row it has had to
     * tokenize anyway; the rule itself is unchanged and lives here now.
     */
    private fun teamNameStrength(
        tokens: List<String>,
        away: SportsTeam,
        home: SportsTeam,
        variants: TeamVariants,
    ): TeamNameStrength {
        if (tokens.isEmpty()) return TeamNameStrength.NONE
        val awayStrong = namesStrongTeam(tokens, variants.away)
        val homeStrong = namesStrongTeam(tokens, variants.home)
        if (awayStrong && homeStrong) return TeamNameStrength.STRONG
        val awayNamed = awayStrong || cityNamesTeam(tokens, away)
        val homeNamed = homeStrong || cityNamesTeam(tokens, home)
        // Both sides named, and at least one of them only by its city.
        return if (awayNamed && homeNamed) TeamNameStrength.WEAK else TeamNameStrength.NONE
    }

    /**
     * Whether [tokens] carry one of the team's real names, whole-word matched:
     * the full display name, the abbreviation, the nickname on its own, then any
     * [TEAM_SHORT_FORMS] alias (in this order, so an earlier form's hit is
     * preferred by callers that care). The forms are the caller's, resolved once
     * per pass - see [TeamVariants].
     */
    private fun namesStrongTeam(tokens: List<String>, variants: List<List<String>>): Boolean =
        variants.any { tokens.containsSequence(it) }

    /**
     * The name forms [team] answers to, in the order the tiers prefer them.
     *
     * INTERNAL because the hub precomputes these once per matching pass (see
     * [TeamVariants]) rather than letting the tiers rebuild them for every
     * program; this stays the single definition of what a team is called.
     */
    internal fun strongVariants(team: SportsTeam): List<List<String>> = buildList {
        team.displayName.takeIf { it.isNotBlank() }?.let { add(words(it)) }
        // The feed's OWN short name, which is the name a guide titles a game
        // with far more often than any word of the full one: "Washington" for
        // the Washington Huskies (the full name's last word is "Huskies", which
        // a guide almost never writes), "Leeds" for Leeds United ("United"),
        // "White Sox" for the Chicago White Sox. Without it, tier 1 could not
        // see the row for whole sports - college football and basketball, and
        // every soccer league - no matter how many channels the guide carried
        // the game on. It is ADDITIVE: the full name, the abbreviation and the
        // last word are all still read, so a guide that spells any of them out
        // keeps matching exactly as before.
        team.shortName?.takeIf { it.isNotBlank() }?.let { add(words(it)) }
        team.abbreviation.takeIf { it.length >= 2 }?.let { add(words(it)) }
        team.displayName.trim().split(' ').lastOrNull()?.takeIf { it.length >= 3 }?.let { add(words(it)) }
        shortFormNames(team).forEach { add(words(it)) }
    }.filter { it.isNotEmpty() }

    /**
     * The aliases in [TEAM_SHORT_FORMS] that stand for [team]: the short forms
     * whose value is this team's full name, so a title carrying one names the
     * team. Empty while the map is empty, which is the shipped state.
     */
    private fun shortFormNames(team: SportsTeam): List<String> {
        val full = words(team.displayName)
        if (full.isEmpty()) return emptyList()
        return TEAM_SHORT_FORMS.filterValues { words(it) == full }.keys.toList()
    }

    /**
     * Whether [tokens] name the team by its CITY alone ("Tampa Bay" for the Tampa
     * Bay Lightning). A one-word display name has no city and never matches here.
     */
    private fun cityNamesTeam(tokens: List<String>, team: SportsTeam): Boolean {
        val city = cityOf(team) ?: return false
        return tokens.containsSequence(city)
    }

    private fun cityOf(team: SportsTeam): List<String>? {
        val parts = team.displayName.trim().split(' ').filter { it.isNotBlank() }
        if (parts.size < 2) return null
        return words(parts.dropLast(1).joinToString(" ")).takeIf { it.isNotEmpty() }
    }

    // ── Tiers 2 and 3 ────────────────────────────────────────────────

    /**
     * The regional networks [team] plays on in [leaguePath], the team's own
     * listing order.
     *
     * Looked up BY LEAGUE as well as by abbreviation, because an abbreviation is
     * not unique across leagues (audit C12, 2026-10-23): a bare lookup resolved
     * whichever league's entry happened to exist, and tier 2 only runs where the
     * guide and the network names both missed - so the error landed on exactly
     * the hardest games. [TEAM_RSN]'s keys carry the league for that reason.
     */
    private fun rsnNetworks(team: SportsTeam, leaguePath: String): List<String> {
        val league = leaguePath.substringAfterLast('/').uppercase()
        val abbreviation = team.abbreviation.trim().uppercase()
        if (league.isEmpty() || abbreviation.isEmpty()) return emptyList()
        return TEAM_RSN["$league:$abbreviation"].orEmpty()
    }

    /**
     * The pass's playlist, indexed ONCE so no card has to walk it again.
     *
     * This is the fix for the slow match loop, and what it removes is worth
     * stating exactly. The broadcast tier below is NOT I/O: it reads the
     * in-memory channel list and compares names. What made it cost 1-3 SECONDS
     * PER CARD on a real provider playlist, and the whole of the 70-second pass,
     * is that each card re-normalized every channel's name from scratch, twice,
     * before comparing anything: five figures of channels through
     * [canonicalNetwork] and [words] for a name that had not changed since the
     * previous card, allocating a lowercased string and a fresh word list each
     * time and throwing both away. Sixty cards over forty thousand channels is
     * millions of rebuilds of the same strings.
     *
     * So the playlist is normalized HERE, once per pass, into [Fact]s - the same
     * `displayName.ifBlank { name }`, the same [canonicalNetwork], the same first
     * word and the same compact length the tier used to compute per call - and
     * the tier becomes a comparison against precomputed strings. The answers are
     * unchanged; the tier's own tests pin them, and they run through this class
     * now, because every path to a match comes through it.
     *
     * One of these belongs to a matching PASS, not to the app: it is built from
     * the pass's channel list, handed to every card that pass matches (see
     * [matches]) and dropped with it. [byNetwork] is why a pass then pays for a
     * network at most once however many cards name it - "ESPN" is on a dozen
     * games and its family is scanned for once - and that is safe because
     * [channelsFor] is a pure function of the network name and the list this was
     * built from, and a pass matches one card at a time.
     */
    internal class ChannelIndex private constructor(private val facts: List<Fact>) {

        /**
         * The ids in the pass's playlist.
         *
         * The same set the tier used to rebuild per card, which is what makes a
         * recalled pick that is gone from the playlist fail through instead of
         * being played. Hoisted for the same reason as the names: the playlist
         * does not change under a pass, and a card should not hash it again.
         */
        val ids: Set<String> = facts.mapTo(HashSet(facts.size)) { it.channel.id }

        /**
         * One channel's name, in the forms the tier compares against.
         *
         * Computed from the name the tier reads ([IptvChannel.displayName], or
         * the name when that is blank) exactly as it used to be per comparison.
         */
        private class Fact(
            /** The channel's name through the alias table and the RSN fold. */
            val canonical: String,
            /**
             * The raw name's normalized tokens, which is what the channel-name
             * tier reads (see [forEachNamed]). Computed here because the first
             * word below already needed them.
             */
            val nameWords: List<String>,
            /**
             * The raw name's first word, which is where a broadcast name lands
             * - "ESPN HD" is the same feed under a qualified name, while "ESPN2"
             * is a different channel sharing the prefix.
             */
            val firstWord: String,
            /**
             * Whether the name ends in a foreign-region code ("ESPN AU"): the same
             * brand, not the feed the broadcast means.
             */
            val international: Boolean,
            /** [compact] length of the raw name: the tie-break between equals. */
            val length: Int,
            val channel: IptvChannel,
        ) {
            /**
             * 5 for the same normalized name, 4 for the same brand under a domestic
             * qualified name ("ESPN HD"), 3 for the brand's foreign-region variant
             * ("ESPN AU"), 2 for a different channel sharing the prefix ("ESPN2"),
             * 1 for appearing anywhere inside, 0 for no match. Read off the
             * precomputed strings, so a comparison across the whole playlist
             * allocates nothing.
             */
            fun strength(target: String): Int {
                if (canonical.isEmpty()) return 0
                if (canonical == target) return 5
                if (firstWord == target) return if (international) 3 else 4
                if (canonical.startsWith(target)) return 2
                if (canonical.contains(target)) return 1
                return 0
            }
        }

        /** Network families resolved so far, by the RAW name they were asked for. */
        private val byNetwork = HashMap<String, List<IptvChannel>>()

        /**
         * Every channel naming [network], best first: by descending strength
         * (exact equals, then the same brand under a domestic qualified name,
         * then the brand's foreign-region variant, then a different channel
         * sharing the prefix, then anywhere inside) and, among equals, the
         * shortest name - "ESPN" over "ESPN International".
         *
         * The head is the single channel this used to pick, so a backup can never
         * displace the primary; the rest are the same family (ESPN2 and ESPNews
         * for "ESPN", a provider's own "- Alt" feed for a regional network),
         * which is exactly where a game turns up when its main feed goes dark.
         * The same name is answered from [byNetwork] after the first time.
         */
        fun channelsFor(network: String): List<IptvChannel> =
            byNetwork.getOrPut(network) { networkChannels(network) }

        /**
         * Visits every channel in the pass with the name tokens this index has
         * already normalized, in playlist order.
         *
         * The channel-name tier's read (see [SportsChannelMatcher.nameHits]).
         * Handed out rather than re-derived for the same reason the network tier
         * reads precomputed strings: a pass of sixty cards visits every channel
         * for every card, and re-tokenizing forty thousand names per card is the
         * cost that took this loop to a minute before the index existed. Nothing
         * is allocated here - the lists the index already holds are handed over.
         */
        fun forEachNamed(visit: (nameWords: List<String>, channel: IptvChannel) -> Unit) {
            facts.forEach { visit(it.nameWords, it.channel) }
        }

        private fun networkChannels(network: String): List<IptvChannel> {
            val target = canonicalNetwork(network)
            if (target.isEmpty()) return emptyList()
            // A streaming exclusive has no cable equivalent, so there is no tier-2
            // answer to give. Returning nothing here is the point, not a miss: the
            // caller falls through to the EPG and RSN tiers, which find whatever is
            // actually airing the game rather than the cable channel with a similar
            // name.
            if (target in STREAMING_EXCLUSIVES) return emptyList()
            data class Ranked(val strength: Int, val length: Int, val channel: IptvChannel)
            return facts
                .mapNotNull { fact ->
                    val strength = fact.strength(target)
                    if (strength == 0) null else Ranked(strength, fact.length, fact.channel)
                }
                .sortedWith(compareByDescending<Ranked> { it.strength }.thenBy { it.length })
                .map { it.channel }
        }

        companion object {
            /** The index of an empty lineup: no network has a channel in it. */
            val EMPTY = ChannelIndex(emptyList())

            /**
             * Normalizes [channels] once for the pass that is about to use it.
             * See [TeamVariants] and the caller's own `channelById` for the same
             * hoist one tier over; an empty list short-circuits, since no tier
             * can answer against it anyway.
             */
            fun of(channels: List<IptvChannel>): ChannelIndex =
                if (channels.isEmpty()) {
                    EMPTY
                } else {
                    ChannelIndex(
                        channels.map { channel ->
                            val name = channel.displayName.ifBlank { channel.name }
                            val nameWords = words(name)
                            Fact(
                                canonical = canonicalNetwork(name),
                                nameWords = nameWords,
                                firstWord = nameWords.firstOrNull().orEmpty(),
                                international = nameWords.isInternationalVariant(),
                                length = compact(name).length,
                                channel = channel,
                            )
                        }
                    )
                }
        }
    }

    /**
     * Brand prefixes of ONE regional-sports family, newest name first: what a
     * channel was called under FanDuel, before that under Bally, and before
     * that under FOX. See [foldRsnBrand].
     */
    private val RSN_BRANDS = listOf(
        "fanduelsportsnetwork",
        "ballysports",
        "foxsports",
    )

    /** What a folded regional brand becomes; see [foldRsnBrand]. */
    private const val RSN_PREFIX = "rsn:"

    /** Normalized broadcast name resolved through the alias table and the RSN fold. */
    private fun canonicalNetwork(raw: String): String {
        val normalized = compact(raw)
        return foldRsnBrand(NETWORK_ALIASES[normalized] ?: normalized)
    }

    /**
     * Folds a regional network's brand onto its region, so a channel named
     * before the last rebranding still matches the feed's own name for it.
     *
     * FOX Sports North became Bally Sports North, and that became FanDuel
     * Sports Network North: one channel, three names, and an M3U written during
     * any of those years carries the same feed. The BRAND is not what makes a
     * regional network that channel - the region is - so both sides of a
     * comparison fold the family to one prefix and meet on the part that has
     * not changed. Only the family that has actually been renamed folds:
     * "NBC Sports Boston" and "MSG Network" are different channels and stay
     * distinct, and FS1/FS2 stay national.
     *
     * Two shapes are left alone here. A BARE brand ("Bally Sports", with no
     * region) has nothing to fold onto, and a broadcast listed as plain "FOX
     * Sports" should keep matching a channel named exactly that. A brand
     * followed by a DIGIT is the national pair - FS1, FS2 and their HD
     * spellings - which are not regional networks and must never fold onto one.
     */
    private fun foldRsnBrand(normalized: String): String {
        RSN_BRANDS.forEach { brand ->
            if (!normalized.startsWith(brand)) return@forEach
            val region = normalized.removePrefix(brand)
            if (region.isEmpty() || region.first().isDigit()) return@forEach
            return RSN_PREFIX + region
        }
        return normalized
    }

    // ── normalization ────────────────────────────────────────────────

    /**
     * Lowercased, punctuation folded to spaces, trimmed - but KEEPS '+'.
     *
     * The plus is the entire difference between "ESPN" and "ESPN+", and folding
     * it to a space made the two normalize identically, so a game ESPN+ streams
     * matched the ESPN cable channel. Every other punctuation mark still folds:
     * "t.n.t" and "TNT" must stay the same network.
     */
    internal fun words(raw: String): List<String> =
        raw.lowercase()
            .map { if (it.isLetterOrDigit() || it == '+') it else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotEmpty() }

    /** [words] with the spaces removed, for whole-name comparison. */
    internal fun compact(raw: String): String = words(raw).joinToString("")

    /** True when [target] appears as a contiguous run inside [source]. */
    private fun List<String>.containsSequence(target: List<String>): Boolean {
        if (target.isEmpty() || target.size > size) return false
        for (start in 0..(size - target.size)) {
            if (subList(start, start + target.size) == target) return true
        }
        return false
    }
}
