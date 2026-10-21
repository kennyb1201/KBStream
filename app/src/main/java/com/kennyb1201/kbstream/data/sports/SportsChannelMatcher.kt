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
 *     still: it is only used when no full hit exists (see [guideFeeds]).
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
     * Team abbreviation -> the regional networks that carry the team.
     *
     * Data, not code: a league's RSN situation changes every year and this is
     * the only part of the matcher that needs editing when it does. Kept small
     * and US-league focused - it is the LAST resort behind a real EPG match and
     * a real network name, so a missing entry costs nothing but a "not in your
     * playlist" on a game that had no other signal.
     */
    val TEAM_RSN: Map<String, List<String>> = mapOf(
        // MLB
        "NYY" to listOf("YES Network"),
        "NYM" to listOf("SNY"),
        "BOS" to listOf("NESN"),
        "LAD" to listOf("Spectrum SportsNet LA"),
        "LAA" to listOf("FanDuel Sports Network West", "Bally Sports West"),
        "CHC" to listOf("Marquee Sports Network"),
        "CWS" to listOf("Chicago Sports Network"),
        "PHI" to listOf("NBC Sports Philadelphia"),
        "SF" to listOf("NBC Sports Bay Area"),
        "SEA" to listOf("Root Sports Northwest"),
        "BAL" to listOf("MASN"),
        "WSH" to listOf("MASN"),
        "ATL" to listOf("FanDuel Sports Network South"),
        "DET" to listOf("FanDuel Sports Network Detroit"),
        "CLE" to listOf("FanDuel Sports Network Great Lakes"),
        "MIN" to listOf("FanDuel Sports Network North"),
        "KC" to listOf("FanDuel Sports Network Kansas City"),
        "STL" to listOf("FanDuel Sports Network Midwest"),
        "HOU" to listOf("Space City Home Network"),
        "TEX" to listOf("FanDuel Sports Network Southwest"),
        "COL" to listOf("Rockies.TV"),
        "SD" to listOf("Padres.TV"),
        "ARI" to listOf("Diamondbacks.TV"),
        "MIL" to listOf("FanDuel Sports Network Wisconsin"),
        "PIT" to listOf("SportsNet Pittsburgh"),
        "CIN" to listOf("FanDuel Sports Network Ohio"),
        "TOR" to listOf("Sportsnet"),
        // NBA
        "LAL" to listOf("Spectrum SportsNet"),
        "LAC" to listOf("FanDuel Sports Network SoCal"),
        "BKN" to listOf("YES Network"),
        "POR" to listOf("Root Sports Plus"),
        // NFL
        "NYG" to listOf("MSG Network"),
        "NYJ" to listOf("MSG Network"),
        "NE" to listOf("NBC Sports Boston"),
        "CHI" to listOf("Chicago Sports Network"),
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
     * is airing it), then the networks the feed names, then the teams' own
     * regional networks.
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
        val epg = guideFeeds(game, programs, channelById, variants, channelIndex)
        if (epg.isNotEmpty()) return leadWith(recalled, cap(epg))
        // Home before away: a regional playlist is likelier to carry the home
        // broadcast. Within a family, the best-named channel comes first.
        val networks = game.broadcastNames + rsnNetworks(game.home) + rsnNetworks(game.away)
        val networkHits = cap(networks.flatMap { channelIndex.channelsFor(it) })
        return leadWith(recalled, networkHits)
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
    ): List<IptvChannel> {
        if (channels.isEmpty()) return emptyList()
        val present = channelIndex.ids
        // The tournament half of the same rule: the viewer's pick leads, the
        // tiers' other feeds stay behind it (see [leadWith]).
        val recalled = listOfNotNull(
            remembered(event.favoriteKey)?.takeIf { it.id in present }
        )
        val epg = epgNameHits(event, programs, channelById)
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
     */
    private fun guideFeeds(
        game: SportsGame,
        programs: List<MatcherProgram>,
        channelById: Map<String, IptvChannel>,
        variants: TeamVariants,
        channelIndex: ChannelIndex,
    ): List<IptvChannel> {
        val titleHits = guideHits(game, programs, channelById, variants, title = true) { it.title }
        val descriptionHits =
            guideHits(game, programs, channelById, variants, title = false) {
                it.description.orEmpty()
            }
        if (titleHits.isEmpty() && descriptionHits.isEmpty()) return emptyList()

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
            return head + fill + strongStrangers.ordered(emptyMap())
        }

        // Weak hits only: still a last resort, and still behind the network the
        // game names when none of them agrees with it.
        val weakAgreeing = agreeing.filterNot { it.strong }
        val weakStrangers = strangers.filterNot { it.strong }
        val head = weakAgreeing.ordered(agreement)
        val fill = if (head.isEmpty()) broadcastFillIn(game, channelIndex) else emptyList()
        return head + fill + weakStrangers.ordered(emptyMap())
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
    ): List<IptvChannel> {
        val eventWords = words(event.name)
        if (eventWords.isEmpty()) return emptyList()
        val hits = programs.filter { program ->
            words(program.title).containsSequence(eventWords)
        }
        if (hits.isEmpty()) return emptyList()
        return hits
            .sortedBy { abs(it.startMs - event.dateMs) }
            .mapNotNull { channelById[it.channelId] }
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
    ): TeamNameStrength {
        val tokens = words(text)
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

    /** The regional networks [team] plays on, the team's own listing order. */
    private fun rsnNetworks(team: SportsTeam): List<String> =
        TEAM_RSN[team.abbreviation.trim().uppercase()].orEmpty()

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
