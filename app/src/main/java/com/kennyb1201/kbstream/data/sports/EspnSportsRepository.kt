package com.kennyb1201.kbstream.data.sports

import android.util.Log
import java.time.OffsetDateTime
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
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

    /**
     * One game's summary, keyed by ESPN event id (ids are ESPN's own, and are
     * unique across its feeds). Small - only games whose sheet has been opened
     * are ever in here - and the 60s [LIVE_TTL_MS] is what makes reopening a
     * sheet free while the hub's 30s tick remains the only thing that re-reads
     * it.
     */
    private val summaries = HashMap<String, Cached<EspnGameSummary>>()
    private val failed = HashSet<String>()
    private val tableFailed = HashSet<String>()
    private val summaryFailed = HashSet<String>()

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
     * One game's summary: team stats, win probability and the last play.
     *
     * Fetched LAZILY - only for a LIVE game, only while its detail sheet is
     * open (see the hub's `openDetail`) - and cached for [LIVE_TTL_MS] keyed by
     * [eventId], so reopening the sheet inside the minute, and the next 30s
     * tick, cost nothing until the TTL lapses.
     *
     * Null when there is nothing to show: a failure (timeout, HTTP error, an
     * unparseable body) with no cached copy, or a blank event id. The sheet
     * then draws the game without its stats section - never an error card,
     * never a spinner - and that is also what a league ESPN does not summarise
     * gets, because [parseGameSummary] reads every field with `opt*` and yields
     * an empty summary rather than throwing.
     */
    suspend fun gameSummary(leaguePath: String, eventId: String): EspnGameSummary? {
        if (eventId.isBlank()) return null
        val cached = summaries[eventId]
        val now = clock()
        if (cached != null && now - cached.atMs < LIVE_TTL_MS) return cached.value
        val fetched =
            fetchSummary(leaguePath, eventId)?.let { body -> parseGameSummary(body, leaguePath) }
        if (fetched == null) {
            summaryFailed += eventId
            // A stale summary still beats none, exactly as a stale scoreboard
            // does: the sheet keeps the last stats it drew rather than blinking
            // the section away over one failed request.
            return cached?.value
        }
        summaryFailed -= eventId
        summaries[eventId] = Cached(now, fetched)
        return fetched
    }

    /** Whether the last summary attempt for [eventId] failed. */
    fun lastSummaryFetchFailed(eventId: String): Boolean = eventId in summaryFailed

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
        summaries.clear()
        failed.clear()
        tableFailed.clear()
        summaryFailed.clear()
    }

    private fun ttlMs(live: Boolean): Long = if (live) LIVE_TTL_MS else IDLE_TTL_MS

    private suspend fun fetch(leaguePath: String): String? =
        httpGet(SCOREBOARD_BASE + leaguePath + "/scoreboard", "scoreboard $leaguePath")

    private suspend fun fetchStandings(leaguePath: String): String? =
        httpGet(
            STANDINGS_BASE + leaguePath + "/standings?region=us&lang=en&type=0",
            "standings $leaguePath"
        )

    /**
     * The summary document: the same `site.api` host and league path the
     * scoreboard uses, with one event named by query parameter.
     */
    private suspend fun fetchSummary(leaguePath: String, eventId: String): String? =
        httpGet(
            SCOREBOARD_BASE + leaguePath + "/summary?event=" + eventId,
            "summary $leaguePath $eventId"
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
    // Both read once, because the situation line is built from the same two
    // facts the state and the clock are (see espnSituation).
    val state = espnGameState(status?.optString("state"))
    val statusDetail = status?.optString("shortDetail", "")
        ?.takeIf { it.isNotBlank() }
        ?: status?.optString("detail", "").orEmpty()
    return SportsGame(
        id = id,
        league = leaguePath,
        name = event.optString("name", ""),
        dateMs = espnDateMs(event.optString("date", "")),
        state = state,
        statusDetail = statusDetail,
        // Free: football's down/distance and baseball's inning/outs are on the
        // competition the card was already parsed from. Null for every other
        // league, and for a game that is not in play.
        situation = espnSituation(
            state = state,
            sport = espnSport(leaguePath),
            competition = competition,
            statusDetail = statusDetail,
        ),
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

// ── The situation line ──────────────────────────────────────────────────
//
// Football's down and distance and baseball's inning and count ride the
// scoreboard the hub already has, so a live card can say what is happening
// without a second request. Everything here is `opt*`/null-safe: a league whose
// payload carries no situation (which is most of them) yields null, and the card
// draws no line at all rather than a placeholder.

/** The sports the hub reads differently, per the feed's league path. */
internal enum class EspnSport { FOOTBALL, BASKETBALL, BASEBALL, HOCKEY, SOCCER, OTHER }

/**
 * The sport behind a league path ("football/nfl").
 *
 * Unknown paths read as [EspnSport.OTHER] - "no situation, no stats table" -
 * which is exactly right for the individual sports (tennis, MMA) and is the
 * safe reading for a league added to the catalog later.
 */
internal fun espnSport(leaguePath: String): EspnSport = when {
    leaguePath.startsWith("football/") -> EspnSport.FOOTBALL
    leaguePath.startsWith("basketball/") -> EspnSport.BASKETBALL
    leaguePath.startsWith("baseball/") -> EspnSport.BASEBALL
    leaguePath.startsWith("hockey/") -> EspnSport.HOCKEY
    leaguePath.startsWith("soccer/") -> EspnSport.SOCCER
    else -> EspnSport.OTHER
}

/** "1st", "2nd", "11th" - a card that says "3 & 7" reads as a typo. */
internal fun espnOrdinal(number: Int): String {
    val lastTwo = number % 100
    val suffix = when {
        lastTwo in 11..13 -> "th"
        number % 10 == 1 -> "st"
        number % 10 == 2 -> "nd"
        number % 10 == 3 -> "rd"
        else -> "th"
    }
    return "$number$suffix"
}

/**
 * The situation line for a live game, or null when the feed carries none.
 *
 * Football: `competitions[0].situation` -> "3rd & 7 · Ball on NE 32". ESPN's
 * `yardLine` is the position the site itself prints - the ball's distance from
 * the goal line of the side WITHOUT the ball - so the line names that side,
 * which is the side the ball is on, and prints the number as sent.
 *
 * Baseball: `competitions[0].details` carries the inning and the count, and the
 * half comes from the feed's own status word ("Top 5th", "Bot 5th", "Mid
 * 5th"). Between halves there is no count to report, so the line is the break.
 *
 * Every other league, and every game that is not in play, has no situation at
 * all: a stale `situation` block on a game ESPN has just called is not shown.
 */
internal fun espnSituation(
    state: GameState,
    sport: EspnSport,
    competition: JSONObject?,
    statusDetail: String,
): String? {
    if (state != GameState.LIVE) return null
    return when (sport) {
        EspnSport.FOOTBALL -> espnFootballSituation(competition)
        EspnSport.BASEBALL -> espnBaseballSituation(competition, statusDetail)
        else -> null
    }
}

private fun espnFootballSituation(competition: JSONObject?): String? {
    val situation = competition?.optJSONObject("situation") ?: return null
    val down = situation.optInt("down", 0)
    val distance = situation.optInt("distance", 0)
    val yardLine = situation.optInt("yardLine", 0)
    if (down <= 0 || distance <= 0 || yardLine <= 0) return null
    val possession = situation.optString("possession", "").trim()
    // The side the ball is on: the competitor the possessing team is NOT.
    // Only named once possession is resolved to a side of THIS game - a feed
    // that names a team id the competition does not carry (or none at all)
    // cannot say whose half of the field it is, and guessing one side would put
    // the wrong team's code on the card.
    val sides = espnCompetitorAbbreviations(competition)
    val possessedBy = sides.firstOrNull { (id, _) -> id.isNotEmpty() && id == possession }
    val defender = possessedBy?.let { (_, _) ->
        sides.firstOrNull { (id, _) -> id.isNotEmpty() && id != possession }?.second
    }
    val downAndDistance = "${espnOrdinal(down)} & $distance"
    return if (defender == null) downAndDistance
    else "$downAndDistance · Ball on $defender $yardLine"
}

private fun espnBaseballSituation(competition: JSONObject?, statusDetail: String): String? {
    val details = competition?.optJSONObject("details") ?: return null
    val inning = details.optInt("inning", 0)
    if (inning <= 0) return null
    // Only the first word is inspected: a status like "Suspended" contains
    // "end" and would otherwise read as a mid-inning break.
    val halfWord = statusDetail.trim().substringBefore(' ').lowercase()
    val inningLabel = espnOrdinal(inning)
    if (halfWord.startsWith("mid") || halfWord.startsWith("end")) return "Mid $inningLabel"
    val half = when {
        halfWord.startsWith("bot") -> "Bot"
        halfWord.startsWith("top") -> "Top"
        else -> null
    }
    val outs = details.optInt("outs", -1)
    val count = if (outs < 0) "" else " · $outs ${if (outs == 1) "out" else "outs"}"
    return if (half == null) "$inningLabel$count" else "$half $inningLabel$count"
}

/** (team id, abbreviation) for each side of a competition, blanks dropped. */
private fun espnCompetitorAbbreviations(competition: JSONObject?): List<Pair<String, String>> =
    competition?.optJSONArray("competitors")?.let { competitors ->
        (0 until competitors.length()).mapNotNull { index ->
            val row = competitors.optJSONObject(index) ?: return@mapNotNull null
            val abbreviation = row.optJSONObject("team")?.optString("abbreviation", "").orEmpty()
                .ifBlank { row.optString("abbreviation", "") }
                .trim()
                .takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            row.optJSONObject("team")?.optString("id", "").orEmpty().trim() to abbreviation
        }
    }.orEmpty()

// ── The game summary ────────────────────────────────────────────────────

/** One stat row the sheet compares: what to print, and how to find it. */
internal data class EspnStatPick(
    /** What the row is labelled when ESPN sends no label of its own. */
    val label: String,
    /** ESPN's `name` for the stat, lowercased. */
    val names: Set<String>,
    /** ESPN's own `label`, lowercased - the fallback for a feed with no name. */
    val labels: Set<String> = emptySet(),
)

/**
 * When a summary may be fetched, and which of a league's stats the sheet shows.
 *
 * Pure and internal so both halves are unit tested without a network or a clock.
 * The "live games only" gate is the one the spec is most emphatic about - an
 * upcoming or final game must never cost a request - and the stat sets are per
 * league because ESPN's box score is: the NFL has no rebound stat and the NHL
 * has no passing yards, and asking for one would print a dash or nothing.
 */
internal object EspnSummaryRules {

    /**
     * Whether opening a game in [state] may cost a summary request.
     *
     * Only live. An upcoming game has no plays and no team stats yet, and a
     * final one's full stats are deliberately out of scope; the hub's
     * `openDetail` consults this rather than spelling the rule out itself, so
     * there is one place to change and one place to test.
     */
    fun shouldFetch(state: GameState): Boolean = state == GameState.LIVE

    /**
     * The comparison rows a league's summary is read into, in draw order.
     *
     * A short, readable set rather than everything ESPN sends: the sheet is a
     * glance, not a box score. A pick the feed does not carry for BOTH sides is
     * dropped, so a row is never a pair of dashes.
     */
    fun statPicks(sport: EspnSport): List<EspnStatPick> = when (sport) {
        EspnSport.FOOTBALL -> listOf(
            EspnStatPick("Total Yards", setOf("totalyards", "yards"), setOf("total yards")),
            EspnStatPick(
                "Passing",
                setOf("passingyards", "netpassingyards", "passyards"),
                setOf("passing", "passing yards", "pass yards")
            ),
            EspnStatPick(
                "Rushing",
                setOf("rushingyards", "rushyards"),
                setOf("rushing", "rushing yards", "rush yards")
            ),
            EspnStatPick("Turnovers", setOf("turnovers"), setOf("turnovers"))
        )

        EspnSport.BASKETBALL -> listOf(
            EspnStatPick(
                "FG%",
                setOf("fieldgoalpct", "fgpct", "fieldgoalpercentage"),
                setOf("fg%", "field goal %", "field goal pct")
            ),
            EspnStatPick(
                "3PT%",
                setOf(
                    "threepointfieldgoalpct",
                    "threepointpct",
                    "threepointpercentage"
                ),
                setOf("3pt%", "3-pt%", "3-pt %", "three point %", "three point pct")
            ),
            EspnStatPick(
                "Rebounds",
                setOf("totalrebounds", "rebounds", "reb"),
                setOf("rebounds", "total rebounds")
            ),
            EspnStatPick("Turnovers", setOf("turnovers"), setOf("turnovers"))
        )

        EspnSport.BASEBALL -> listOf(
            EspnStatPick("Hits", setOf("hits"), setOf("hits")),
            EspnStatPick("Errors", setOf("errors"), setOf("errors")),
            EspnStatPick("Left on Base", setOf("leftonbase", "lob"), setOf("left on base", "lob"))
        )

        EspnSport.HOCKEY -> listOf(
            EspnStatPick(
                "Shots",
                setOf("shots", "shotstotal", "shotsongoal", "sog"),
                setOf("shots", "shots on goal", "sog")
            ),
            EspnStatPick("Hits", setOf("hits"), setOf("hits")),
            EspnStatPick(
                "Penalty Minutes",
                setOf("penaltyminutes", "pim"),
                setOf("penalty minutes", "pim")
            )
        )

        EspnSport.SOCCER -> listOf(
            EspnStatPick(
                "Possession",
                setOf("possessionpct", "possession"),
                setOf("possession", "possession %")
            ),
            EspnStatPick(
                "Shots",
                setOf("totalshots", "shots", "shotstotal"),
                setOf("shots", "total shots")
            )
        )

        // The individual sports: no team box score exists to compare.
        EspnSport.OTHER -> emptyList()
    }
}

/**
 * ESPN's summary document into the hub's own shape.
 *
 * Every field is optional, in the feed and in here: `boxscore`, `winprobability`
 * and `plays` are each absent for some league, and a body that is not JSON at
 * all yields an EMPTY summary rather than an exception. A league ESPN covers
 * thinly therefore looks exactly like today's sheet instead of like an error -
 * which is the whole point of the null-safety here.
 */
internal fun parseGameSummary(json: String, leaguePath: String): EspnGameSummary {
    val root = runCatching { JSONObject(json) }.getOrNull() ?: return EspnGameSummary()
    val sport = espnSport(leaguePath)
    return EspnGameSummary(
        teamStats = espnTeamStatRows(root, sport),
        homeWinProbability = espnHomeWinProbability(root),
        lastPlay = espnLastPlay(root, sport),
    )
}

/**
 * The paired stat rows: (label, away, home), in the league's own order, with a
 * pick dropped unless BOTH sides carry it.
 */
private fun espnTeamStatRows(
    root: JSONObject,
    sport: EspnSport
): List<Triple<String, String, String>> {
    val picks = EspnSummaryRules.statPicks(sport)
    if (picks.isEmpty()) return emptyList()
    val teams = root.optJSONObject("boxscore")?.optJSONArray("teams") ?: return emptyList()
    var awayStats: JSONArray? = null
    var homeStats: JSONArray? = null
    for (i in 0 until teams.length()) {
        val row = teams.optJSONObject(i) ?: continue
        val stats = row.optJSONArray("statistics") ?: continue
        when (row.optString("homeAway", "").trim().lowercase()) {
            "away" -> awayStats = stats
            "home" -> homeStats = stats
        }
    }
    val away = awayStats ?: return emptyList()
    val home = homeStats ?: return emptyList()
    return picks.mapNotNull { pick ->
        val awayRow = espnStatPick(away, pick) ?: return@mapNotNull null
        val homeRow = espnStatPick(home, pick) ?: return@mapNotNull null
        Triple(awayRow.first, awayRow.second, homeRow.second)
    }
}

/** (display label, value) for one pick on one side, or null when it has none. */
private fun espnStatPick(stats: JSONArray, pick: EspnStatPick): Pair<String, String>? {
    for (i in 0 until stats.length()) {
        val stat = stats.optJSONObject(i) ?: continue
        val name = stat.optString("name", "").trim().lowercase()
        val label = stat.optString("label", "").trim()
        val matched = name.isNotEmpty() && name in pick.names ||
            label.isNotEmpty() && label.lowercase() in pick.labels
        if (!matched) continue
        val value = stat.optString("displayValue", "").takeIf { it.isNotBlank() }
            ?: stat.optString("value", "").takeIf { it.isNotBlank() }
            ?: return null
        return (if (label.isNotEmpty()) label else pick.label) to value
    }
    return null
}

/**
 * The home side's win chance, 0..100, from the last entry of `winprobability`.
 *
 * ESPN sends a fraction (0.68) on some feeds and a percentage on others, so a
 * value at or below 1 is read as a fraction. Absent for most leagues (and for
 * an upcoming game), which reads as null and drops the bar.
 */
private fun espnHomeWinProbability(root: JSONObject): Int? {
    val list = root.optJSONArray("winprobability") ?: return null
    val last = (list.length() - 1).takeIf { it >= 0 }?.let { list.optJSONObject(it) } ?: return null
    val raw = last.optDouble("homeWinPercentage", Double.NaN)
    if (raw.isNaN()) return null
    val percent = if (raw <= 1.0) raw * 100.0 else raw
    return percent.roundToInt().coerceIn(0, 100)
}

/**
 * The most recent play's text.
 *
 * Football keeps its plays under `drives.current.plays`; every other league the
 * hub reads that has play-by-play publishes a flat `plays` array. Both are read
 * last-first, the first non-blank `text` wins, and a document with neither (or
 * with plays that carry no text) yields null.
 */
private fun espnLastPlay(root: JSONObject, sport: EspnSport): String? {
    val current = root.optJSONObject("drives")?.optJSONObject("current")
    val lists = if (sport == EspnSport.FOOTBALL) {
        listOfNotNull(current?.optJSONArray("plays"), root.optJSONArray("plays"))
    } else {
        listOfNotNull(root.optJSONArray("plays"), current?.optJSONArray("plays"))
    }
    lists.forEach { plays ->
        for (i in plays.length() - 1 downTo 0) {
            val text = plays.optJSONObject(i)?.optString("text", "")?.trim().orEmpty()
            if (text.isNotEmpty()) return text
        }
    }
    return null
}
