package com.kennyb1201.kbstream.data.sports

import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import org.json.JSONObject

/**
 * The pure half of the TheSportsDB leg (spec 2026-11-13): everything that can be
 * decided from one V1 payload and the clock.
 *
 * Split from [TheSportsDbClient] for the same reason the ESPN parsers are split
 * from [EspnSportsRepository] - a canned body can be read in a JVM test with no
 * network, no key, and no Android context. The client does the HTTP, the key, the
 * rate-limit gate and the caching; this file decides what a payload MEANS.
 *
 * The field names are the V1 ones, verified against live answers from the public
 * test key on 2026-11-14: `eventsnextleague` and `eventsround` carry `idEvent`,
 * `strTimestamp` (UTC, no zone suffix), `strEvent`, `strHomeTeam`/`strAwayTeam`,
 * `intHomeScore`/`intAwayScore`, `intRound`, `strSeason`, the two
 * `str*TeamBadge` CDN URLs and `strStatus`; `lookuptable` carries `intRank`,
 * `intWin`/`intDraw`/`intLoss`, `intPoints`, `strForm`, `strBadge` and `idTeam`;
 * `searchteams` carries `strTeam`, `strBadge` and `idTeam`.
 *
 * Two shapes are empty STRINGS rather than arrays when there is nothing to say -
 * `eventslast` answers `""` and a league with no table answers `{"table":""}` -
 * so every reader below treats a missing/non-array value as "no rows" and only a
 * body that is not JSON at all as unreadable (see the client's strict-parse rule).
 */

/** The published V1 test key: shared by design, ships as a constant, not a secret. */
internal const val THESPORTSDB_TEST_KEY = "123"

/** The V1 base every request is built on; the key is the last path segment. */
internal const val THESPORTSDB_BASE = "https://www.thesportsdb.com/api/v1/json/"

/** The artwork CDN, which is not an API call: badge images cost no requests. */
internal const val THESPORTSDB_ARTWORK_HOST = "r2.thesportsdb.com"

/**
 * The next local midnight after [nowMs] - when a fixture list goes stale.
 *
 * The viewer's OWN day decides it, not a rolling 24 hours: a list read at 11 PM
 * should answer "tomorrow" in the morning, and a rolling window would keep last
 * night's answer until the same hour the next evening. Pure, so the boundary is
 * a unit test rather than something only a device could observe.
 */
internal fun theSportsDbNextDayStartMs(
    nowMs: Long,
    zone: ZoneId = ZoneId.systemDefault()
): Long = LocalDate.ofInstant(Instant.ofEpochMilli(nowMs), zone)
    .plusDays(1)
    .atStartOfDay(zone)
    .toInstant()
    .toEpochMilli()

/**
 * One row of `intRound`/`strSeason` from a fixtures payload, when it carries one.
 *
 * The free key's `eventsnextleague` answers a thin slice - often a single fixture
 * - so the client follows the round that fixture names back to `eventsround`,
 * which answers the whole matchday. Null when the payload names no round (a cup
 * tie, a TBD fixture), in which case the thin slice stands on its own.
 */
internal data class TheSportsDbRound(val round: String, val season: String)

/**
 * The round a fixtures payload points at, or null when it names none.
 *
 * The FIRST event is read because the endpoint is ordered by kickoff and a
 * payload that spans rounds is a bug the caller's dedupe absorbs anyway.
 */
internal fun theSportsDbRound(body: String): TheSportsDbRound? {
    val events = runCatching { JSONObject(body).optJSONArray("events") }.getOrNull() ?: return null
    for (index in 0 until events.length()) {
        val event = events.optJSONObject(index) ?: continue
        val round = event.optString("intRound").trim()
        val season = event.optString("strSeason").trim()
        if (round.isNotEmpty() && round != "0" && season.isNotEmpty()) {
            return TheSportsDbRound(round, season)
        }
    }
    return null
}

/**
 * The fixtures in one V1 events payload as the hub's own games.
 *
 * [leaguePath] is the catalog id the tab is keyed on (`tsdb:<id>`), so a fixture
 * read from TheSportsDB is keyed, favourited and channel-matched exactly like an
 * ESPN one. Sorted by kickoff, earliest first, because a fixtures list is read
 * forwards.
 *
 * A fixture with no event id, no team names, or no readable kickoff is dropped
 * rather than drawn with a blank half - the same rule the anime rows follow, and
 * the reason [theSportsDbGame] answers null instead of a half-built card.
 */
internal fun theSportsDbGames(
    body: String,
    leaguePath: String,
    zone: ZoneId = ZoneId.systemDefault()
): List<SportsGame> {
    val events = runCatching { JSONObject(body).optJSONArray("events") }.getOrNull()
        ?: return emptyList()
    val games = ArrayList<SportsGame>(events.length())
    for (index in 0 until events.length()) {
        val event = events.optJSONObject(index) ?: continue
        theSportsDbGame(event, leaguePath, zone)?.let(games::add)
    }
    return games.distinctBy { it.id }.sortedBy { it.dateMs }
}

/**
 * One V1 event as a [SportsGame], or null when it cannot be drawn honestly.
 *
 * No live leg: the free key has no livescore endpoint, so a status this leg has
 * never seen is read as UPCOMING and the card says when it starts rather than
 * inventing a score. The one exception is a cached payload that already says the
 * match finished - the badge and score are drawn, and the hub still refreshes
 * from ESPN for live detail.
 */
internal fun theSportsDbGame(
    event: JSONObject,
    leaguePath: String,
    zone: ZoneId = ZoneId.systemDefault()
): SportsGame? {
    val id = event.optString("idEvent").trim().takeIf { it.isNotEmpty() } ?: return null
    val homeName = event.optString("strHomeTeam").trim().takeIf { it.isNotEmpty() } ?: return null
    val awayName = event.optString("strAwayTeam").trim().takeIf { it.isNotEmpty() } ?: return null
    val startMs = theSportsDbStartMs(event) ?: return null
    val status = event.optString("strStatus").trim()
    val state = theSportsDbState(status)
    val played = state == GameState.FINAL
    val homeScore = if (played) event.optString("intHomeScore").trim().ifEmpty { null } else null
    val awayScore = if (played) event.optString("intAwayScore").trim().ifEmpty { null } else null
    val round = event.optString("intRound").trim()
    return SportsGame(
        id = id,
        eventId = id,
        league = leaguePath,
        name = event.optString("strEvent").trim().ifEmpty { "$awayName at $homeName" },
        dateMs = startMs,
        state = state,
        statusDetail = theSportsDbStatusText(state, startMs, zone),
        away = SportsTeam(
            id = event.optString("idAwayTeam").trim().ifEmpty { null },
            abbreviation = theSportsDbAbbreviation(awayName),
            displayName = awayName,
            logoUrl = event.optString("strAwayTeamBadge").trim().ifEmpty { null },
            score = awayScore,
            isHome = false,
            record = null,
            league = leaguePath
        ),
        home = SportsTeam(
            id = event.optString("idHomeTeam").trim().ifEmpty { null },
            abbreviation = theSportsDbAbbreviation(homeName),
            displayName = homeName,
            logoUrl = event.optString("strHomeTeamBadge").trim().ifEmpty { null },
            score = homeScore,
            isHome = true,
            record = null,
            league = leaguePath
        ),
        // TheSportsDB's V1 event carries no broadcast field at all. The matcher
        // still gets the fixture - it resolves channels from the TEAM NAMES (see
        // SportsChannelMatcher), which is the whole point of adding these leagues.
        broadcastNames = emptyList(),
        venue = event.optString("strVenue").trim().ifEmpty { null },
        note = round.takeIf { it.isNotEmpty() && it != "0" }?.let { "Round $it" }
    )
}

/**
 * V1's own status word -> the hub's three states.
 *
 * `NS` (not started) is the free key's fixture answer; `FT`/`AET`/`PEN` are
 * results. Anything else - including the live words, which the free key never
 * serves - is read as UPCOMING rather than claimed as live: no V2 livescore leg
 * is part of this build (spec §5).
 */
internal fun theSportsDbState(status: String): GameState = when (status.trim().uppercase()) {
    "FT", "AET", "PEN", "MATCH FINISHED", "FINISHED" -> GameState.FINAL
    "1H", "2H", "HT", "ET", "P", "LIVE" -> GameState.LIVE
    else -> GameState.UPCOMING
}

/** The card's clock line: the kickoff time for a fixture, "Final" for a result. */
internal fun theSportsDbStatusText(
    state: GameState,
    startMs: Long,
    zone: ZoneId = ZoneId.systemDefault()
): String = when (state) {
    GameState.FINAL -> "Final"
    GameState.LIVE -> "LIVE"
    GameState.UPCOMING ->
        DateTimeFormatter.ofPattern("h:mm a", Locale.US)
            .withZone(zone)
            .format(Instant.ofEpochMilli(startMs))
}

/**
 * The kickoff instant of a V1 event, or null when it carries no readable one.
 *
 * V1 publishes `strTimestamp` as UTC with no zone suffix (`2026-11-21T14:00:00`),
 * so it is parsed as UTC; `dateEvent` + `strTime` are the fallback for payloads
 * that carry no timestamp.
 */
internal fun theSportsDbStartMs(event: JSONObject): Long? {
    val timestamp = event.optString("strTimestamp").trim()
    if (timestamp.isNotEmpty()) {
        val utc = if (timestamp.endsWith("Z") || timestamp.contains("+")) timestamp
        else timestamp + "Z"
        runCatching { OffsetDateTime.parse(utc).toInstant().toEpochMilli() }
            .getOrNull()
            ?.takeIf { it > 0L }
            ?.let { return it }
    }
    val day = event.optString("dateEvent").trim()
    if (day.isEmpty()) return null
    val time = event.optString("strTime").trim().ifEmpty { "00:00:00" }
    val stamp = if (time.length == 5) "$day" + "T" + time + ":00" else "$day" + "T" + time
    return runCatching { OffsetDateTime.parse(stamp + "Z").toInstant().toEpochMilli() }
        .getOrNull()
        ?.takeIf { it > 0L }
}

/**
 * A side's short code, since a V1 event carries no abbreviation.
 *
 * The first three letters of the name's first word: "Lazio" -> "LAZ", "Inter
 * Milan" -> "INT", "Bayern Munich" -> "BAY". Initials were the other candidate
 * and they read badly on a plate ("Inter Milan" -> "IM", "AC Milan" -> "AM"),
 * while football clubs are known by exactly this kind of three-letter code. The
 * card prints it above the name, so it is never empty and never longer than the
 * plate was designed for.
 */
internal fun theSportsDbAbbreviation(name: String): String =
    name.trim().takeWhile { !it.isWhitespace() }.take(3).uppercase(Locale.US)

/**
 * A league table from one `lookuptable` payload.
 *
 * One group, named after the league: V1's table has no conferences or divisions,
 * so inventing a split would be a second opinion. Rows keep V1's `intRank` order
 * (the hub's own [com.kennyb1201.kbstream.ui.sports.SportsStandingsOrder] re-sorts
 * on points, stably, so equal rows keep the feed's tiebreak).
 *
 * Empty when the league has no table - the payload answers `{"table":""}` - and
 * then the hub draws nothing rather than an empty table that reads as last place
 * (spec F4).
 */
internal fun theSportsDbTable(
    body: String,
    leagueLabel: String,
    leaguePath: String
): List<StandingGroup> {
    val rows = runCatching { JSONObject(body).optJSONArray("table") }.getOrNull()
        ?: return emptyList()
    val entries = ArrayList<StandingEntry>(rows.length())
    for (index in 0 until rows.length()) {
        val row = rows.optJSONObject(index) ?: continue
        val name = row.optString("strTeam").trim().takeIf { it.isNotEmpty() } ?: continue
        entries += StandingEntry(
            abbreviation = theSportsDbAbbreviation(name),
            displayName = name,
            logoUrl = row.optString("strBadge").trim().ifEmpty { null },
            wins = row.optString("intWin").toIntOrNull() ?: 0,
            losses = row.optString("intLoss").toIntOrNull() ?: 0,
            ties = row.optString("intDraw").toIntOrNull() ?: 0,
            // Soccer: the third figure is a DRAW, and the table is read on points
            // (the catalog marks these leagues pointsTable - see SportsModels).
            winPercent = "",
            gamesBehind = "",
            streak = row.optString("strForm").trim(),
            points = row.optString("intPoints").trim(),
            teamId = row.optString("idTeam").trim().ifEmpty { null }
        )
    }
    if (entries.isEmpty()) return emptyList()
    return listOf(StandingGroup(name = leagueLabel, entries = entries))
}

/**
 * [games] with a badge filled in ONLY where the feed carried none (spec F3).
 *
 * [badges] is a name -> CDN URL map gathered by the client; ESPN's logos are
 * never replaced, so a team that already has one cannot flicker between sources.
 * Matched on the lowercased display name, which is also how the client looks the
 * badge up, so a caller cannot fill a different team's crest than it asked for.
 */
internal fun theSportsDbFillBadges(
    games: List<SportsGame>,
    badges: Map<String, String>
): List<SportsGame> {
    if (badges.isEmpty()) return games
    return games.map { game ->
        val away = theSportsDbFillBadge(game.away, badges)
        val home = theSportsDbFillBadge(game.home, badges)
        if (away === game.away && home === game.home) game else game.copy(away = away, home = home)
    }
}

private fun theSportsDbFillBadge(team: SportsTeam, badges: Map<String, String>): SportsTeam {
    if (!team.logoUrl.isNullOrBlank()) return team
    val badge = badges[theSportsDbBadgeKey(team.displayName)] ?: return team
    return team.copy(logoUrl = badge)
}

/** The key a badge is looked up and filled under: the display name, lowercased. */
internal fun theSportsDbBadgeKey(teamName: String): String =
    teamName.trim().lowercase(Locale.US).replace(Regex("\\s+"), " ")

/**
 * The first usable badge in a `searchteams` payload, or null when it carries
 * none. One call per team name, cached by the client for the day.
 */
internal fun theSportsDbTeamBadge(body: String): String? {
    val teams = runCatching { JSONObject(body).optJSONArray("teams") }.getOrNull() ?: return null
    for (index in 0 until teams.length()) {
        val team = teams.optJSONObject(index) ?: continue
        val badge = team.optString("strBadge").trim()
        if (badge.isNotEmpty()) return badge
    }
    return null
}
