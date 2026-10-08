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
    private val failed = HashSet<String>()

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
     * Whether the last attempt for [leaguePath] failed.
     *
     * An empty list alone cannot say why it is empty, and the difference
     * matters to the viewer: a league out of season has genuinely nothing on
     * ("No games today") while a league with a network error has to say so and
     * offer a retry. Set on every fetch, so it always describes the attempt
     * that produced the list just returned.
     */
    fun lastFetchFailed(leaguePath: String): Boolean = leaguePath in failed

    /** Drops every cached league: what a "Refresh" press runs. */
    fun clearCache() {
        scoreboards.clear()
        tournaments.clear()
        failed.clear()
    }

    private fun ttlMs(live: Boolean): Long = if (live) LIVE_TTL_MS else IDLE_TTL_MS

    private suspend fun fetch(leaguePath: String): String? = withContext(Dispatchers.IO) {
        val url = SCOREBOARD_BASE + leaguePath + "/scoreboard"
        runCatching {
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "scoreboard $leaguePath -> HTTP ${response.code}")
                    return@runCatching null
                }
                response.body.string()
            }
        }.getOrElse { error ->
            Log.w(TAG, "scoreboard $leaguePath failed: ${error.message}")
            null
        }
    }

    companion object {
        private const val TAG = "ESPN_SPORTS"
        private const val SCOREBOARD_BASE = "https://site.api.espn.com/apis/site/v2/sports/"

        /** A live game changes on every drive; a minute is the hub's own tick. */
        private const val LIVE_TTL_MS = 60_000L

        /** Nothing in play: a schedule does not move for a quarter of an hour. */
        private const val IDLE_TTL_MS = 15L * 60_000L

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
    )
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
    return SportsTeam(
        abbreviation = abbreviation,
        displayName = displayName,
        logoUrl = team?.optJSONArray("logos")?.optJSONObject(0)?.optString("href", "")
            ?.takeIf { it.isNotBlank() },
        score = row.optString("score", "").takeIf { it.isNotBlank() },
        isHome = homeAway.equals("home", ignoreCase = true),
        record = row.optJSONArray("records")?.optJSONObject(0)?.optString("summary", "")
            ?.takeIf { it.isNotBlank() },
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
        ) to (row.optInt("order", i) to score.toDoubleOrNull())
    }
    return rows
        .sortedWith(compareBy({ it.second.second ?: Double.MAX_VALUE }, { it.second.first }))
        .take(5)
        .map { it.first }
}
