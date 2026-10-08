package com.kennyb1201.kbstream.data.sports

import com.kennyb1201.kbstream.data.iptv.IptvChannel
import kotlin.math.abs

/**
 * One EPG program, as the matcher sees it.
 *
 * Deliberately not the guide's own row types: the matcher needs three facts per
 * program - which channel airs it, what it is called, and when it runs - and
 * taking those by value keeps the rule pure and testable without a database, a
 * playlist or a ViewModel.
 */
data class MatcherProgram(
    val channelId: String,
    val title: String,
    val startMs: Long,
    val endMs: Long,
)

/**
 * Picks the playlist channel that is carrying a game, or null.
 *
 * Three tiers, strongest first, and it never guesses past them: a wrong game on
 * the wrong channel is worse than "Not in your playlist".
 *
 *  1. **EPG program match** (strongest). The program airing at the game's start
 *     names BOTH teams. This finds the game on any network - national, an RSN,
 *     or a team channel - without knowing the network in advance. A single-team
 *     hit is a pregame show or a repeat, not the game.
 *  2. **Broadcast network match.** ESPN's own `broadcasts[].names` resolved
 *     against the playlist by normalized name: exact, then prefix/word-boundary,
 *     then contains. "ESPN" must not land on "ESPN2" when a plain "ESPN" exists.
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
    ): IptvChannel? = matches(game, channels, programs).firstOrNull()

    /**
     * Every channel the playlist carries [game] on, strongest first.
     *
     * The head is exactly what [match] has always returned; everything behind it
     * is a feed the playlist also holds for the same game, in the order a viewer
     * would want to fall to: the rest of tier 1 (another channel the guide says
     * is airing it), then the networks the feed names, then the teams' own
     * regional networks.
     *
     * The tiers are read as one ordered list rather than short-circuiting at the
     * first hit, which is the whole point: "the game is on ESPN" and "the game is
     * on YES" are both true when a provider carries the national feed AND the
     * local one, and a dead national feed leaves the local one worth playing.
     */
    fun matches(
        game: SportsGame,
        channels: List<IptvChannel>,
        programs: List<MatcherProgram>,
    ): List<IptvChannel> {
        if (channels.isEmpty()) return emptyList()
        epgHits(game, channels, programs).let { if (it.isNotEmpty()) return cap(it) }
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
    ): IptvChannel? = matches(event, channels, programs).firstOrNull()

    /** The channels carrying a tournament event, strongest first. See [matches]. */
    fun matches(
        event: TournamentEvent,
        channels: List<IptvChannel>,
        programs: List<MatcherProgram>,
    ): List<IptvChannel> {
        if (channels.isEmpty()) return emptyList()
        epgNameHits(event, channels, programs).let { if (it.isNotEmpty()) return cap(it) }
        return cap(event.broadcastNames.flatMap { networkChannels(it, channels) })
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

    private fun epgHits(
        game: SportsGame,
        channels: List<IptvChannel>,
        programs: List<MatcherProgram>,
    ): List<IptvChannel> {
        val hits = programs.filter { program ->
            overlapsWindow(program, game.dateMs) &&
                namesBothTeams(program.title, game.away, game.home)
        }
        if (hits.isEmpty()) return emptyList()
        // The program whose start sits closest to the game's own start comes
        // first, when a provider carries the same game on more than one channel.
        val byId = channels.associateBy { it.id }
        return hits
            .sortedBy { abs(it.startMs - game.dateMs) }
            .mapNotNull { byId[it.channelId] }
    }

    private fun epgNameHits(
        event: TournamentEvent,
        channels: List<IptvChannel>,
        programs: List<MatcherProgram>,
    ): List<IptvChannel> {
        val eventWords = words(event.name)
        if (eventWords.isEmpty()) return emptyList()
        val hits = programs.filter { program ->
            overlapsWindow(program, event.dateMs) &&
                words(program.title).containsSequence(eventWords)
        }
        if (hits.isEmpty()) return emptyList()
        val byId = channels.associateBy { it.id }
        return hits
            .sortedBy { abs(it.startMs - event.dateMs) }
            .mapNotNull { byId[it.channelId] }
    }

    private fun overlapsWindow(program: MatcherProgram, startMs: Long): Boolean =
        program.startMs <= startMs + EPG_WINDOW_MS && program.endMs >= startMs - EPG_WINDOW_MS

    /**
     * True when [title] names BOTH sides. One team is a pregame show, a repeat
     * or a season-long magazine; only both together are the game.
     */
    private fun namesBothTeams(title: String, away: SportsTeam, home: SportsTeam): Boolean {
        val titleWords = words(title)
        return titleNamesTeam(titleWords, away) && titleNamesTeam(titleWords, home)
    }

    /**
     * Team-name matching, in the order the spec sets out: the full display name,
     * the abbreviation, then the nickname on its own. All three are matched as
     * whole words, so "Boston" cannot hit a title that merely contains "BOS"
     * inside another word, and case/punctuation never matter.
     */
    private fun titleNamesTeam(titleWords: List<String>, team: SportsTeam): Boolean {
        val candidates = listOfNotNull(
            team.displayName.takeIf { it.isNotBlank() },
            team.abbreviation.takeIf { it.length >= 2 },
            team.displayName.trim().split(' ').lastOrNull()?.takeIf { it.length >= 3 },
        )
        return candidates.any { candidate ->
            val target = words(candidate)
            target.isNotEmpty() && titleWords.containsSequence(target)
        }
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

    /** Normalized broadcast name resolved through the alias table. */
    private fun canonicalNetwork(raw: String): String {
        val normalized = compact(raw)
        return NETWORK_ALIASES[normalized] ?: normalized
    }

    // ── normalization ────────────────────────────────────────────────

    /** Lowercased, punctuation folded to spaces, trimmed. */
    private fun words(raw: String): List<String> =
        raw.lowercase()
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotEmpty() }

    /** [words] with the spaces removed, for whole-name comparison. */
    private fun compact(raw: String): String = words(raw).joinToString("")

    /** True when [target] appears as a contiguous run inside [source]. */
    private fun List<String>.containsSequence(target: List<String>): Boolean {
        if (target.isEmpty() || target.size > size) return false
        for (start in 0..(size - target.size)) {
            if (subList(start, start + target.size) == target) return true
        }
        return false
    }
}
