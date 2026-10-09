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
 *  1. **EPG program match** (strongest of the heuristics). The program airing at
 *     the game's start names BOTH teams - first in its TITLE, and - only when no
 *     title does - in its DESCRIPTION. This finds the game on any network -
 *     national, an RSN, or a team channel - without knowing the network in
 *     advance. A single-team hit is a pregame show or a repeat, not the game.
 *  2. **Broadcast network match.** ESPN's own `broadcasts[].names` resolved
 *     against the playlist by normalized name: exact, then prefix/word-boundary,
 *     then contains. "ESPN" must not land on "ESPN2" when a plain "ESPN" exists.
 *     A streaming-exclusive name ([STREAMING_EXCLUSIVES]) never matches here at
 *     all - no cable channel carries it - so a game ESPN+ streams is found by
 *     the EPG tier (the RSN or local channel simulcasting it) or the team's own
 *     RSN. If tier 1 keeps missing those, that is a bug in `epgHits` (team-name
 *     variants, window width) to fix THERE, not a reason to widen tier 2.
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
     * caller owns the storage - and a hit skips every tier below.
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
    ): List<IptvChannel> {
        if (channels.isEmpty()) return emptyList()
        // The viewer's own past pick beats every heuristic: if they once chose a
        // channel for a game involving either team, play that again. A hit skips
        // all three tiers; a pick that is gone from the playlist falls through.
        val present = channels.mapTo(HashSet()) { it.id }
        val recalled = listOfNotNull(
            remembered(game.home.favoriteKey),
            remembered(game.away.favoriteKey),
        ).filter { it.id in present }
        if (recalled.isNotEmpty()) return cap(recalled)
        epgHits(game, programs, channelById, variants).let { if (it.isNotEmpty()) return cap(it) }
        // Home before away: a regional playlist is likelier to carry the home
        // broadcast. Within a family, the best-named channel comes first.
        val networks = game.broadcastNames + rsnNetworks(game.home) + rsnNetworks(game.away)
        return cap(networks.flatMap { networkChannels(it, channels) })
    }

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
     * so its stable name is what a past pick hangs off. It is checked before the
     * EPG and broadcast tiers, exactly as on the game path, and a pick that is
     * gone from the playlist falls through to them.
     */
    fun matches(
        event: TournamentEvent,
        channels: List<IptvChannel>,
        programs: List<MatcherProgram>,
        remembered: (String) -> IptvChannel? = { null },
        channelById: Map<String, IptvChannel> = channels.associateBy { it.id },
    ): List<IptvChannel> {
        if (channels.isEmpty()) return emptyList()
        val present = channels.mapTo(HashSet()) { it.id }
        remembered(event.favoriteKey)?.takeIf { it.id in present }?.let { return cap(listOf(it)) }
        epgNameHits(event, programs, channelById).let { if (it.isNotEmpty()) return cap(it) }
        return cap(event.broadcastNames.flatMap { networkChannels(it, channels) })
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
     * city alone, which [namesBothTeams] also accepts as a weaker form. Nothing
     * about a name lives here; [searchVariants] is the one place that says what
     * a team answers to.
     *
     * Partial input matches, because typing is incremental on a remote: see
     * [matchesWords] for the two shapes a query can have (half a name, or a
     * whole name inside something longer), and [searchVariants] for what a team
     * answers to.
     *
     * Two additions over [namesBothTeams], both because this is a viewer typing
     * rather than a program title: the feed's own short name ([SportsTeam.shortName]
     * - "Yankees", where the tier deliberately reads the last word of the
     * display name instead) and the city alone, both of which are what a person
     * actually types. The tiers are untouched by this: it is a separate entry
     * point, not a change to [strongVariants].
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
     * [namesBothTeams]).
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

    /**
     * The channels whose guide row names both teams, titles first.
     *
     * A title naming both teams IS the game, so it is read on its own and, when
     * it finds anything, the description pass never runs. Only when no title
     * matches do descriptions get a turn - strictly weaker, because a synopsis
     * naming both teams can be a preview show rather than the game.
     */
    private fun epgHits(
        game: SportsGame,
        programs: List<MatcherProgram>,
        channelById: Map<String, IptvChannel>,
        variants: TeamVariants,
    ): List<IptvChannel> {
        val titleHits = programHits(game, programs, channelById, variants) { it.title }
        if (titleHits.isNotEmpty()) return titleHits
        return programHits(game, programs, channelById, variants) { it.description.orEmpty() }
    }

    private fun programHits(
        game: SportsGame,
        programs: List<MatcherProgram>,
        channelById: Map<String, IptvChannel>,
        variants: TeamVariants,
        textOf: (MatcherProgram) -> String,
    ): List<IptvChannel> {
        val hits = programs.filter { program ->
            overlapsWindow(program, game.dateMs) &&
                namesBothTeams(textOf(program), game.away, game.home, variants)
        }
        if (hits.isEmpty()) return emptyList()
        // The program whose start sits closest to the game's own start comes
        // first, when a provider carries the same game on more than one channel.
        return hits
            .sortedBy { abs(it.startMs - game.dateMs) }
            .mapNotNull { channelById[it.channelId] }
    }

    private fun epgNameHits(
        event: TournamentEvent,
        programs: List<MatcherProgram>,
        channelById: Map<String, IptvChannel>,
    ): List<IptvChannel> {
        val eventWords = words(event.name)
        if (eventWords.isEmpty()) return emptyList()
        val hits = programs.filter { program ->
            overlapsWindow(program, event.dateMs) &&
                words(program.title).containsSequence(eventWords)
        }
        if (hits.isEmpty()) return emptyList()
        return hits
            .sortedBy { abs(it.startMs - event.dateMs) }
            .mapNotNull { channelById[it.channelId] }
    }

    private fun overlapsWindow(program: MatcherProgram, startMs: Long): Boolean =
        program.startMs <= startMs + EPG_WINDOW_MS && program.endMs >= startMs - EPG_WINDOW_MS

    /**
     * True when [text] names BOTH sides.
     *
     * A side is named STRONGLY by its full name, abbreviation, nickname or a
     * short-form alias, or WEAKLY by its city alone. Both sides must be named,
     * and a city-alone hit only counts when the other side carries a real name
     * ("Tampa Bay vs Florida", "Tampa Bay vs Panthers") - a lone "Tampa Bay" is
     * a travel show, not a game, so it must never match on its own.
     */
    private fun namesBothTeams(
        text: String,
        away: SportsTeam,
        home: SportsTeam,
        variants: TeamVariants,
    ): Boolean {
        val tokens = words(text)
        if (tokens.isEmpty()) return false
        val awayStrong = namesStrongTeam(tokens, variants.away)
        val homeStrong = namesStrongTeam(tokens, variants.home)
        val awayCity = cityNamesTeam(tokens, away)
        val homeCity = cityNamesTeam(tokens, home)
        if (!(awayStrong || awayCity) || !(homeStrong || homeCity)) return false
        if (awayStrong && homeStrong) return true
        // At least one side rests on its city alone: allowed only because the
        // other side carries a real name (checked above).
        return awayCity || homeCity
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
     * Every playlist channel naming [network], best first: by descending
     * strength (exact equals, then a prefix at a word boundary, then anywhere
     * inside) and, among equals, the shortest name - "ESPN" over "ESPN
     * International".
     *
     * The head is the single channel this used to pick, so a backup can never
     * displace the primary; the rest are the same family (ESPN2 and ESPNews for
     * "ESPN", a provider's own "- Alt" feed for a regional network), which is
     * exactly where a game turns up when its main feed goes dark.
     */
    private fun networkChannels(network: String, channels: List<IptvChannel>): List<IptvChannel> {
        val target = canonicalNetwork(network)
        if (target.isEmpty()) return emptyList()
        // A streaming exclusive has no cable equivalent, so there is no tier-2
        // answer to give. Returning nothing here is the point, not a miss: the
        // caller falls through to the EPG and RSN tiers, which find whatever is
        // actually airing the game rather than the cable channel with a similar
        // name.
        if (target in STREAMING_EXCLUSIVES) return emptyList()
        data class Ranked(val strength: Int, val length: Int, val channel: IptvChannel)
        return channels
            .mapNotNull { channel ->
                val name = channel.displayName.ifBlank { channel.name }
                val strength = networkStrength(name, target)
                if (strength == 0) null else Ranked(strength, compact(name).length, channel)
            }
            .sortedWith(compareByDescending<Ranked> { it.strength }.thenBy { it.length })
            .map { it.channel }
    }

    private fun networkStrength(channelName: String, target: String): Int {
        // The channel's name goes through the SAME alias table as the
        // broadcast's, so "FOX Sports 1" and a broadcast listed as "FS1" meet
        // in the middle. Without this the alias table only worked one way
        // round, and the spelling a provider actually uses (the long one, or
        // the short one) decided the match.
        val normalized = canonicalNetwork(channelName)
        if (normalized.isEmpty()) return 0
        if (normalized == target) return 3
        // A prefix only counts at a word boundary in the ORIGINAL name, so
        // "ESPN2" is a weaker match for "ESPN" than "ESPN News" is - and a
        // plain "ESPN" still wins outright above both.
        val firstWord = words(channelName).firstOrNull().orEmpty()
        if (firstWord == target) return 2
        if (normalized.startsWith(target)) return 2
        if (normalized.contains(target)) return 1
        return 0
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
