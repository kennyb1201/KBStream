package com.kennyb1201.kbstream.data.sports

import android.util.Log
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * ESPN's public scoreboard feed, read into the hub's own models.
 *
 * No key and no account: the endpoint is
 * `https://site.api.espn.com/apis/site/v2/sports/{league}/scoreboard`, which is
 * what the ESPN site itself calls. Thin on purpose - HTTP and parsing only, so
 * the interesting rules (which channel carries a game) stay in the pure
 * [SportsChannelMatcher] where they can be tested.
 *
 * Cached in memory, per league: 60 seconds while a league has a live game, 15
 * minutes otherwise. A hub opened twice in a minute must not refetch, and a
 * viewer tapping between league tabs should never watch a spinner for a
 * scoreboard the app already has.
 *
 * Failures are never fatal: the cached list is returned even when stale, and an
 * empty list when there is nothing cached - the hub renders an error state.
 */
class EspnSportsRepository(
    private val client: OkHttpClient = defaultClient(),
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private data class Cached<T>(val atMs: Long, val value: T)

    private val scoreboards = HashMap<String, Cached<List<SportsGame>>>()
    private val tournaments = HashMap<String, Cached<List<TournamentEvent>>>()
    private val tableCache = HashMap<String, Cached<List<StandingGroup>>>()
    private val failed = HashSet<String>()
    private val tableFailed = HashSet<String>()

    /**
     * The games in [leaguePath], live/upcoming/final, newest event order as
     * ESPN sends it. Empty on a failure with nothing cached.
     */
    suspend fun scoreboard(leaguePath: String): List<SportsGame> {
        val cached = scoreboards[leaguePath]
        val now = clock()
        if (cached != null && now - cached.atMs < ttlMs(cached.value.any { it.state == GameState.LIVE })) {
            return cached.value
        }
        val fetched = fetch(leaguePath)?.let { body -> parseScoreboard(body, leaguePath) }
        if (fetched == null) {
            // The cached list, however stale, beats an empty one: a scoreboard
            // from twenty minutes ago still names the games and the networks.
            failed += leaguePath
            return cached?.value.orEmpty()
        }
        failed -= leaguePath
        scoreboards[leaguePath] = Cached(now, fetched)
        return fetched
    }

    /** The tournament events (golf, F1) in [leaguePath]. */
    suspend fun tournamentEvents(leaguePath: String): List<TournamentEvent> {
        val cached = tournaments[leaguePath]
        val now = clock()
        if (cached != null && now - cached.atMs < ttlMs(cached.value.any { it.state == GameState.LIVE })) {
            return cached.value
        }
        val fetched = fetch(leaguePath)?.let { body -> parseTournamentEvents(body, leaguePath) }
        if (fetched == null) {
            failed += leaguePath
            return cached?.value.orEmpty()
        }
        failed -= leaguePath
        tournaments[leaguePath] = Cached(now, fetched)
        return fetched
    }

    /**
     * The league's standings, grouped by conference/division in the feed's own
     * order, with the empty groups skipped. Empty on a failure with nothing
     * cached.
     *
     * A six-hour TTL rather than the scoreboard's minute-scale one: a table
     * moves at most once a day, and this is a much heavier document than a
     * scoreboard, so refetching it with every tab switch would be pure cost.
     */
    suspend fun standings(leaguePath: String): List<StandingGroup> {
        val cached = tableCache[leaguePath]
        val now = clock()
        if (cached != null && now - cached.atMs < STANDINGS_TTL_MS) return cached.value
        val fetched = fetchStandings(leaguePath)?.let(::parseStandings)
        if (fetched == null) {
            tableFailed += leaguePath
            return cached?.value.orEmpty()
        }
        tableFailed -= leaguePath
        tableCache[leaguePath] = Cached(now, fetched)
        return fetched
    }

    /**
     * Whether the last attempt for [leaguePath] failed.
     *
     * An empty list alone cannot say why it is empty, and the difference
     * matters to the viewer: a league out of season has genuinely nothing on
     * ("No games today") while a league with a network error has to say so and
     * offer a retry. Set on every fetch, so it always describes the attempt
     * that produced the list just returned.
     */
    fun lastFetchFailed(leaguePath: String): Boolean = leaguePath in failed

    /** Whether the last standings attempt for [leaguePath] failed. See above. */
    fun lastStandingsFetchFailed(leaguePath: String): Boolean = leaguePath in tableFailed

    /** Drops every cached league: what a "Refresh" press runs. */
    fun clearCache() {
        scoreboards.clear()
        tournaments.clear()
        tableCache.clear()
        failed.clear()
        tableFailed.clear()
    }

    private fun ttlMs(live: Boolean): Long = if (live) LIVE_TTL_MS else IDLE_TTL_MS

    private suspend fun fetch(leaguePath: String): String? =
        httpGet(SCOREBOARD_BASE + leaguePath + "/scoreboard", "scoreboard $leaguePath")

    private suspend fun fetchStandings(leaguePath: String): String? =
        httpGet(
            STANDINGS_BASE + leaguePath + "/standings?region=us&lang=en&type=0",
            "standings $leaguePath"
        )

    private suspend fun httpGet(url: String, what: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder().url(url).get().build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "$what -> HTTP ${response.code}")
                        return@runCatching null
                    }
                    response.body.string()
                }
            }.getOrElse { error ->
                Log.w(TAG, "$what failed: ${error.message}")
                null
            }
        }

    companion object {
        private const val TAG = "ESPN_SPORTS"
        private const val SCOREBOARD_BASE = "https://site.api.espn.com/apis/site/v2/sports/"

        /**
         * The standings host, which is deliberately NOT the scoreboard's: the
         * scoreboard lives on `site.api`, the table on `site.web.api`.
         */
        private const val STANDINGS_BASE = "https://site.web.api.espn.com/apis/v2/sports/"

        /** A live game changes on every drive; a minute is the hub's own tick. */
        private const val LIVE_TTL_MS = 60_000L

        /** Nothing in play: a schedule does not move for a quarter of an hour. */
        private const val IDLE_TTL_MS = 15L * 60_000L

        /** A table moves at most daily, so it is held for a quarter of a day. */
        private const val STANDINGS_TTL_MS = 6L * 60L * 60_000L

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15L, TimeUnit.SECONDS)
            .readTimeout(15L, TimeUnit.SECONDS)
            .build()

        @Volatile
        private var sharedInstance: EspnSportsRepository? = null

        /**
         * The one repository the app shares.
         *
         * The cache IS the repository, and the hub is opened and closed (and
         * recomposed, and returned to from the player) many times a session:
         * a second instance would mean a 30-second live refresh that refetched
         * a scoreboard the app had already downloaded, every single time.
         * Stateless apart from that cache, so one instance serves every
         * caller.
         */
        fun shared(): EspnSportsRepository =
            sharedInstance ?: synchronized(this) {
                sharedInstance ?: EspnSportsRepository().also { sharedInstance = it }
            }
    }
}

// ── Parsing ─────────────────────────────────────────────────────────────
//
// Pure functions over ESPN's JSON, internal so a canned payload can be parsed
// in a unit test without a network or an Android context.

/**
 * ESPN's `status.type.state` -> the hub's own three states.
 *
 * `in` is live, `pre` has not started, `post` is over. Anything unrecognised is
 * read as UPCOMING rather than dropped: a game on the schedule the app does not
 * understand is still a game worth listing.
 */
internal fun espnGameState(state: String?): GameState = when (state?.trim()?.lowercase()) {
    "in" -> GameState.LIVE
    "post" -> GameState.FINAL
    else -> GameState.UPCOMING
}

/** `2024-01-01T00:30Z` -> epoch millis; 0 when unparseable. */
internal fun espnDateMs(raw: String?): Long =
    // OffsetDateTime, not Instant: ESPN writes whole minutes (`00:30Z`), and
    // Instant's ISO format demands seconds, so every kick-off time in the feed
    // parsed to zero - and a card whose date is 0 has no clock to show.
    runCatching { OffsetDateTime.parse(raw).toInstant().toEpochMilli() }.getOrDefault(0L)

internal fun parseScoreboard(json: String, leaguePath: String): List<SportsGame> {
    val events = runCatching { JSONObject(json).optJSONArray("events") }.getOrNull()
        ?: return emptyList()
    return (0 until events.length()).mapNotNull { index ->
        events.optJSONObject(index)?.let { event -> espnEventToGame(event, leaguePath) }
    }
}

private fun espnEventToGame(event: JSONObject, leaguePath: String): SportsGame? {
    val id = event.optString("id", "").ifBlank { return null }
    val competition = event.optJSONArray("competitions")?.optJSONObject(0)
    val competitors = competition?.optJSONArray("competitors") ?: event.optJSONArray("competitors")
    if (competitors == null) return null

    var away: SportsTeam? = null
    var home: SportsTeam? = null
    for (i in 0 until competitors.length()) {
        val row = competitors.optJSONObject(i) ?: continue
        val team = espnTeamOf(row, homeAway = row.optString("homeAway", ""))
        if (team.isHome) home = team else away = team
    }
    // A head-to-head card needs two sides; a one-sided payload is not a game.
    val awayTeam = away ?: return null
    val homeTeam = home ?: return null

    val status = event.optJSONObject("status")?.optJSONObject("type")
    return SportsGame(
        id = id,
        league = leaguePath,
        name = event.optString("name", ""),
        dateMs = espnDateMs(event.optString("date", "")),
        state = espnGameState(status?.optString("state")),
        statusDetail = status?.optString("shortDetail", "")
            ?.takeIf { it.isNotBlank() }
            ?: status?.optString("detail", "").orEmpty(),
        away = awayTeam,
        home = homeTeam,
        broadcastNames = espnBroadcasts(event, competition),
        // The venue is on the competition for a club game; an event that only
        // names one at the top (a tour stop) reads from there instead.
        venue = espnVenue(competition?.optJSONObject("venue"), event.optJSONObject("venue")),
        note = espnNote(competition?.optJSONArray("notes"), event.optJSONArray("notes")),
        // A football schedule is read in weeks, and `week.number` is where ESPN
        // puts it. Every other sport leaves it out.
        week = espnWeek(event.optJSONObject("week")),
        // Present on only some sports/feed shapes; the sheet skips the section
        // when it is empty rather than reserving a blank block.
        leaders = espnGameLeaders(competition),
    )
}

/**
 * ESPN's per-side leaders on a competition: the first stat line of each side's
 * leader block, up to two - one per team.
 *
 * The shape is `competitions[0].leaders[] = { team, leaders[] = { athlete,
 * displayValue } }`, one entry per side. Absent on most scoreboards (the NBA's
 * regular-season feed carries no `leaders` at all), which is not a fault: the
 * sheet simply draws no leaders section. A block with no athlete or no value is
 * skipped rather than rendered as an empty row.
 */
internal fun espnGameLeaders(competition: JSONObject?): List<GameLeader> {
    val blocks = competition?.optJSONArray("leaders") ?: return emptyList()
    val out = ArrayList<GameLeader>(2)
    for (i in 0 until blocks.length()) {
        if (out.size >= 2) break
        val block = blocks.optJSONObject(i) ?: continue
        val abbreviation = block.optJSONObject("team")
            ?.optString("abbreviation", "").orEmpty()
        // Each block is a list of stat groups (points, rebounds, ...), and each
        // of those carries the athletes in its own `leaders` array. The head of
        // the first non-empty group is the side's top performer.
        val groups = block.optJSONArray("leaders") ?: continue
        var line: GameLeader? = null
        for (g in 0 until groups.length()) {
            val rows = groups.optJSONObject(g)?.optJSONArray("leaders") ?: continue
            for (r in 0 until rows.length()) {
                val row = rows.optJSONObject(r) ?: continue
                val athlete = row.optJSONObject("athlete")
                val name = athlete?.optString("displayName", "")?.takeIf { it.isNotBlank() }
                    ?: row.optString("displayName", "").takeIf { it.isNotBlank() }
                    ?: continue
                val summary = row.optString("displayValue", "").takeIf { it.isNotBlank() }
                    ?: row.optString("summary", "").takeIf { it.isNotBlank() }
                    ?: ""
                line = GameLeader(name = name, teamAbbreviation = abbreviation, summary = summary)
                break
            }
            if (line != null) break
        }
        if (line != null) out += line
    }
    return out
}

// ── Standings ───────────────────────────────────────────────────────────

/**
 * ESPN's standings document into the hub's groups.
 *
 * The endpoint nests differently per sport and has changed shape before: the
 * live NFL/NBA/MLB payload returns conferences each carrying their own
 * `standings.entries` and no division children, while a payload that DOES split
 * into divisions (and the canned case the tests pin) carries them as
 * `children` of the conference. Both are read the same way:
 *
 *  - a conference WITH division children is flattened into its divisions, in
 *    order, and the conference itself is not emitted (the spec's "conferences
 *    containing divisions");
 *  - a conference with no children is emitted as one group under its own name.
 *
 * A group with no entries is skipped rather than drawn as an empty heading, and
 * a payload that is not standings yields no groups rather than throwing.
 */
internal fun parseStandings(json: String): List<StandingGroup> {
    val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyList()
    val out = ArrayList<StandingGroup>()
    val conferences = root.optJSONArray("children")
    if (conferences == null || conferences.length() == 0) {
        // A flat payload (no conference grouping) is one group of its own.
        val entries = espnStandingEntries(root)
        if (entries.isNotEmpty()) {
            out += StandingGroup(root.optString("name", "Standings"), entries)
        }
        return out
    }
    for (i in 0 until conferences.length()) {
        val conference = conferences.optJSONObject(i) ?: continue
        val conferenceName = conference.optString("name", "")
        val divisions = conference.optJSONArray("children")
        if (divisions != null && divisions.length() > 0) {
            for (j in 0 until divisions.length()) {
                val division = divisions.optJSONObject(j) ?: continue
                val entries = espnStandingEntries(division)
                if (entries.isEmpty()) continue
                // The division keeps the conference it was nested under, so the
                // two-column table can stack each conference's divisions in its
                // own column - a name like "Atlantic" cannot say which one it is.
                out += StandingGroup(
                    name = division.optString("name", ""),
                    entries = entries,
                    conference = conferenceName.takeIf { it.isNotBlank() },
                )
            }
        } else {
            val entries = espnStandingEntries(conference)
            if (entries.isEmpty()) continue
            out += StandingGroup(conferenceName, entries)
        }
    }
    return out
}

private fun espnStandingEntries(node: JSONObject): List<StandingEntry> {
    val entries = node.optJSONObject("standings")?.optJSONArray("entries") ?: return emptyList()
    return (0 until entries.length()).mapNotNull { index ->
        entries.optJSONObject(index)?.let(::espnStandingEntry)
    }
}

private fun espnStandingEntry(row: JSONObject): StandingEntry? {
    val team = row.optJSONObject("team") ?: return null
    val displayName = team.optString("displayName", "").takeIf { it.isNotBlank() }
        ?: team.optString("name", "").takeIf { it.isNotBlank() }
        ?: return null
    val stats = row.optJSONArray("stats")
    return StandingEntry(
        abbreviation = team.optString("abbreviation", "").takeIf { it.isNotBlank() }
            ?: displayName.take(3).uppercase(),
        displayName = displayName,
        // The same two places a scoreboard's crest can sit, read the same way.
        logoUrl = team.optString("logo", "").takeIf { it.isNotBlank() }
            ?: team.optJSONArray("logos")?.optJSONObject(0)
                ?.optString("href", "")?.takeIf { it.isNotBlank() },
        wins = espnStatText(stats, "wins").toIntOrNull() ?: 0,
        losses = espnStatText(stats, "losses").toIntOrNull() ?: 0,
        ties = espnStatText(stats, "ties").toIntOrNull() ?: 0,
        winPercent = espnStatText(stats, "winPercent"),
        gamesBehind = espnStatText(stats, "gamesBehind"),
        streak = espnStatText(stats, "streak"),
    )
}

/**
 * One named stat's value as text, or "" when the row has no such stat.
 *
 * `displayValue` first (ESPN's own formatting: "1.000", "-", "W4"), falling
 * back to the raw numeric `value` where a sport omits the display string.
 */
internal fun espnStatText(stats: JSONArray?, name: String): String {
    if (stats == null) return ""
    for (i in 0 until stats.length()) {
        val stat = stats.optJSONObject(i) ?: continue
        if (stat.optString("name", "") != name) continue
        return stat.optString("displayValue", "").takeIf { it.isNotBlank() }
            ?: stat.optString("value", "").takeIf { it.isNotBlank() }
            ?: ""
    }
    return ""
}

/**
 * ESPN's week number as the card's context line: `{"number": 6}` -> "Week 6".
 *
 * Only a numbered week becomes one. ESPN writes `"number": 0` for the
 * preseason and for a neutral-site game it carries no week for, and "Week 0"
 * is not a week anybody plays - so zero reads as no week at all and the card
 * falls back to the venue.
 */
internal fun espnWeek(week: JSONObject?): String? {
    val number = week?.optInt("number", 0) ?: 0
    if (number <= 0) return null
    val label = week?.optString("text", "").orEmpty().takeIf { it.isNotBlank() }
    return label ?: "Week $number"
}

/**
 * A venue line: "Rocket Arena · Cleveland, OH".
 *
 * ESPN names the place either as a full name plus an address or, on a tour,
 * as a display name on its own ("Beijing, China PR"), and both are read the
 * same way. The parts are joined with a middot so the line reads as one fact,
 * and a shape this does not understand yields null rather than a dangling
 * separator.
 */
internal fun espnVenue(vararg venues: JSONObject?): String? {
    venues.forEach { venue ->
        val name = venue?.optString("fullName", "")?.takeIf { it.isNotBlank() }
            ?: venue?.optString("displayName", "")?.takeIf { it.isNotBlank() }
            ?: return@forEach
        val address = venue?.optJSONObject("address")
        val city = address?.optString("city", "")?.takeIf { it.isNotBlank() }
        val region = address?.optString("state", "")?.takeIf { it.isNotBlank() }
            ?: address?.optString("country", "")?.takeIf { it.isNotBlank() }
        val where = listOfNotNull(city, region).distinct().joinToString(", ")
        return if (where.isBlank()) name else "$name · $where"
    }
    return null
}

/**
 * ESPN's own context line for an event: `notes[0].headline` ("ALDS - Game 4",
 * "Preseason"), or its `text` where the note is a sentence (a tennis result).
 * Blank and missing notes are the same thing here - no line at all.
 */
internal fun espnNote(vararg notes: JSONArray?): String? {
    notes.forEach { notesArray ->
        val head = notesArray?.optJSONObject(0) ?: return@forEach
        head.optString("headline", "").takeIf { it.isNotBlank() }?.let { return it }
        head.optString("text", "").takeIf { it.isNotBlank() }?.let { return it }
    }
    return null
}

/**
 * One side of a card, whether the feed calls it a team or an athlete.
 *
 * Tennis and MMA publish a competitor with `athlete` where the ball sports
 * publish `team` - the same slot, a different name - so both are read, and a
 * competitor that is only an athlete still gets a name to print. There is no
 * abbreviation and no crest for a person, which is fine: the card falls back to
 * the name's initials on the same plate a club without a logo gets.
 */
private fun espnTeamOf(row: JSONObject, homeAway: String): SportsTeam {
    val team = row.optJSONObject("team")
    val athlete = row.optJSONObject("athlete")
    val displayName = team?.optString("displayName", "")?.takeIf { it.isNotBlank() }
        ?: team?.optString("name", "")?.takeIf { it.isNotBlank() }
        ?: athlete?.optString("displayName", "")?.takeIf { it.isNotBlank() }
        ?: athlete?.optString("shortName", "")?.takeIf { it.isNotBlank() }
        ?: athlete?.optString("fullName", "")?.takeIf { it.isNotBlank() }
        ?: row.optString("displayName", "")
    val abbreviation = team?.optString("abbreviation", "").orEmpty()
        .ifBlank { row.optString("abbreviation", "") }
        // An athlete has no code of their own, so derive one from the name -
        // "Carlos Alcaraz" reads ALCARAZ next to the score, which is what a
        // bracket actually prints.
        .ifBlank { displayName.trim().split(' ').lastOrNull().orEmpty().uppercase() }
    // The mark, in the order ESPN offers one. `logo` (a single string) is
    // where the LIVE scoreboard carries a club crest - `logos`, the array the
    // detail pages use, is simply absent from the feed, so reading only that
    // left every card printing initials instead of a crest. A person has no
    // crest at all: a headshot where the sport publishes one, else the country
    // flag ESPN does publish for tennis, MMA and golf.
    val mark = team?.optString("logo", "")?.takeIf { it.isNotBlank() }
        ?: team?.optJSONArray("logos")?.optJSONObject(0)?.optString("href", "")
            ?.takeIf { it.isNotBlank() }
        ?: athlete?.optJSONObject("headshot")?.optString("href", "")
            ?.takeIf { it.isNotBlank() }
        ?: athlete?.optJSONObject("flag")?.optString("href", "")
            ?.takeIf { it.isNotBlank() }
    // ESPN's own id, which is what a favourite is stored under: a club can be
    // renamed, and a person's display name can be spelled six ways, but the id
    // is the same row in the feed every week.
    val id = team?.optString("id", "")?.takeIf { it.isNotBlank() }
        ?: athlete?.optString("id", "")?.takeIf { it.isNotBlank() }
    return SportsTeam(
        id = id,
        abbreviation = abbreviation,
        displayName = displayName,
        logoUrl = mark,
        score = row.optString("score", "").takeIf { it.isNotBlank() },
        isHome = homeAway.equals("home", ignoreCase = true),
        record = row.optJSONArray("records")?.optJSONObject(0)?.optString("summary", "")
            ?.takeIf { it.isNotBlank() },
        colorHex = team?.optString("color", "")?.takeIf { it.isNotBlank() },
        // The nickname ESPN publishes ("Yankees", "Red Sox"), used where a
        // heading reads better with the name than the code - see a game
        // reminder's title.
        shortName = team?.optString("shortDisplayName", "")?.takeIf { it.isNotBlank() }
            ?: athlete?.optString("shortName", "")?.takeIf { it.isNotBlank() },
    )
}

/**
 * The networks airing an event.
 *
 * ESPN puts them either on the event or on its first competition, depending on
 * the league, so both are read and de-duplicated in order.
 */
private fun espnBroadcasts(event: JSONObject, competition: JSONObject?): List<String> =
    buildList {
        listOfNotNull(event.optJSONArray("broadcasts"), competition?.optJSONArray("broadcasts"))
            .forEach { array ->
                for (i in 0 until array.length()) {
                    val names = array.optJSONObject(i)?.optJSONArray("names") ?: continue
                    for (j in 0 until names.length()) {
                        names.optString(j, "").trim().takeIf { it.isNotEmpty() }?.let(::add)
                    }
                }
            }
    }.distinct()

internal fun parseTournamentEvents(json: String, leaguePath: String): List<TournamentEvent> {
    val events = runCatching { JSONObject(json).optJSONArray("events") }.getOrNull()
        ?: return emptyList()
    // Golf and F1 both publish ONE event object for the whole tournament; the
    // first is the tournament itself.
    val event = events.optJSONObject(0) ?: return emptyList()
    val id = event.optString("id", "").ifBlank { return emptyList() }
    val competition = event.optJSONArray("competitions")?.optJSONObject(0)
    val status = event.optJSONObject("status")?.optJSONObject("type")
    return listOf(
        TournamentEvent(
            id = id,
            league = leaguePath,
            name = event.optString("name", "").ifBlank { event.optString("shortName", "") },
            dateMs = espnDateMs(event.optString("date", "")),
            state = espnGameState(status?.optString("state")),
            statusDetail = status?.optString("shortDetail", "")
                ?.takeIf { it.isNotBlank() }
                ?: status?.optString("detail", "").orEmpty(),
            leaders = espnLeaders(competition?.optJSONArray("competitors")),
            broadcastNames = espnBroadcasts(event, competition),
            // A race names its track on the event (`circuit`); a golf round or
            // a tennis tournament names a venue there instead.
            venue = espnVenue(
                event.optJSONObject("circuit"),
                event.optJSONObject("venue"),
                competition?.optJSONObject("venue")
            ),
            note = espnNote(competition?.optJSONArray("notes"), event.optJSONArray("notes")),
        )
    )
}

/**
 * The top five of a tournament field.
 *
 * Golf reports a score relative to par as a string ("-8"); racing reports a
 * finishing/starting position. Both are handled by reading a numeric score when
 * one is present (lowest wins) and falling back to ESPN's own `order`, which is
 * the ordered field for either sport. When neither is available the ordering is
 * the feed's own, which is already best-first - and an unreadable shape yields
 * no leaderboard rather than a wrong one.
 */
private fun espnLeaders(competitors: JSONArray?): List<Leader> {
    if (competitors == null) return emptyList()
    val rows = (0 until competitors.length()).mapNotNull { i ->
        val row = competitors.optJSONObject(i) ?: return@mapNotNull null
        val name = row.optJSONObject("athlete")?.optString("displayName", "")
            ?.takeIf { it.isNotBlank() }
            ?: row.optJSONObject("team")?.optString("displayName", "")?.takeIf { it.isNotBlank() }
            ?: row.optString("displayName", "").takeIf { it.isNotBlank() }
            ?: return@mapNotNull null
        val score = row.optString("score", "").takeIf { it.isNotBlank() }
            ?: row.optJSONObject("status")?.optString("position", "")?.takeIf { it.isNotBlank() }
            ?: ""
        Leader(
            name = name,
            score = score,
            // Golfers, drivers and fighters carry a country flag and nothing
            // else, so it is the mark a leaderboard row can draw.
            flagUrl = row.optJSONObject("athlete")
                ?.optJSONObject("flag")?.optString("href", "")
                ?.takeIf { it.isNotBlank() },
        ) to (row.optInt("order", i) to score.toDoubleOrNull())
    }
    return rows
        .sortedWith(compareBy({ it.second.second ?: Double.MAX_VALUE }, { it.second.first }))
        .take(5)
        .map { it.first }
}
