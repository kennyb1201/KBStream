package com.kennyb1201.kbstream.data.sports

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.runCatchingCancellable
import com.kennyb1201.kbstream.data.settings.AppPreferences
import java.net.URLEncoder
import java.time.ZoneId
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * TheSportsDB's free V1 API, read into the hub's own models (spec 2026-11-13).
 *
 * One rule, and the rest follows from it: **ESPN keeps live scores; TheSportsDB
 * adds the leagues ESPN lacks, their fixtures and tables, and badges where ESPN
 * has none.** There is no livescore leg here - the free key has no endpoint for
 * one, and the paid V2 path is explicitly out of scope - so nothing in this file
 * is ever polled on the hub's 30-second beat. Its work is a fixture list and a
 * table per league, cached until the viewer's next midnight and for six hours
 * respectively (see [theSportsDbNextDayStartMs] and [TABLE_TTL_MS]); a full
 * refresh is a handful of calls once, then quiet for hours.
 *
 * **The key.** The public test key `123` is the built-in default: TheSportsDB
 * publishes it in its own docs, it is shared by design, and it is not a secret, so
 * it ships as [THESPORTSDB_TEST_KEY]. A viewer who buys a dedicated key pastes it
 * in Settings (SYNCED_API_KEYS, per profile, encrypted - the Anime Skip client id
 * is the pattern), and [keyFor] prefers it. A paid key is never baked into the
 * build.
 *
 * **The rate limit** is 30 requests/minute on the shared key. A 429 is answered
 * with whatever is cached and no retry for at least a minute (the endpoint sends
 * `Retry-After`, and its own docs say wait another minute) - so a burst can cost
 * freshness, never a blank Hub. Failures never blank anything: the cached list is
 * returned even when stale, exactly as [EspnSportsRepository] does.
 *
 * **The endpoints this build actually uses**, checked against live answers on
 * 2026-11-14 rather than assumed from the docs: `eventsnextleague` (fixtures) and
 * `eventsround` (the whole matchday the next fixture names), `lookuptable` (the
 * table), `searchteams` (a badge by team name). Two V1 endpoints the spec listed
 * are deliberately NOT called: `eventslast` answers an empty string on the free
 * key (verified), and `lookup_all_teams` ignored its id and returned the same
 * English League 1 clubs for two different leagues - so results stay ESPN's and a
 * missing badge is looked up one team at a time.
 */
class TheSportsDbClient(
    private val client: OkHttpClient = defaultClient(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {

    private data class Cached<T>(val expiresAtMs: Long, val value: T)

    /**
     * One cache per endpoint family, keyed by `<key>:<league id>` - the key is
     * part of the identity because a personal key and the test key must never
     * answer for each other. [_activeKey] flushes everything when the key in the
     * pref store changes, which is what a paste in Settings does mid-session.
     */
    private val fixtures =
        java.util.concurrent.ConcurrentHashMap<String, Cached<List<SportsGame>>>()
    private val tables =
        java.util.concurrent.ConcurrentHashMap<String, Cached<List<StandingGroup>>>()
    private val badges =
        java.util.concurrent.ConcurrentHashMap<String, Cached<String?>>()
    private val failed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private val tableFailed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var activeKey: String? = null

    /**
     * Wall-clock millis before which no request is sent at all: set by a 429 to
     * the endpoint's own `Retry-After` (a minute when it sends none). Reads
     * inside the window answer their cache and never the network.
     */
    private var blockedUntilMs = 0L

    /** When the last 429 was seen, for the hub's diagnostics only. */
    var lastRateLimitedAtMs: Long = 0L
        private set

    /** Whether the last fixtures attempt for [leagueId] failed. */
    fun lastFetchFailed(leagueId: String): Boolean = leagueId in failed

    /** Whether the last table attempt for [leagueId] failed. */
    fun lastStandingsFetchFailed(leagueId: String): Boolean = leagueId in tableFailed

    /**
     * The league's fixtures, earliest first: the thin slice `eventsnextleague`
     * answers, widened to the whole matchday through `eventsround` when the
     * payload names a round (the free key's next-fixtures answer is often a single
     * event, and one card is not a fixtures tab).
     *
     * Cached until the viewer's next local midnight: a fixture list does not move
     * during a day, and the day boundary is what makes "tomorrow" appear without
     * a poll.
     */
    suspend fun fixtures(key: String, leagueId: String): List<SportsGame> {
        syncKey(key)
        val cacheKey = "$key:$leagueId"
        val cached = fixtures[cacheKey]
        val now = clock()
        if (cached != null && now < cached.expiresAtMs) return cached.value
        if (now < blockedUntilMs) return cached?.value.orEmpty()

        val nextBody = httpGet(key, "eventsnextleague.php?id=$leagueId", "fixtures $leagueId")
        // The STRICT parse: a body that is not an events document takes the
        // failure path below instead of being cached as an empty slate for a day.
        val next = nextBody?.let { body -> strictEvents(body, "tsdb:$leagueId") }
        if (next == null) {
            failed += leagueId
            return cached?.value.orEmpty()
        }
        failed -= leagueId
        val round = nextBody.let { body -> body?.let(::theSportsDbRound) }
        val roundGames = if (round == null) {
            emptyList()
        } else {
            val roundBody = httpGet(
                key,
                "eventsround.php?id=$leagueId&r=${round.round}&s=${round.season}",
                "round $leagueId ${round.round}"
            )
            roundBody?.let { body -> strictEvents(body, "tsdb:$leagueId") }.orEmpty()
        }
        val merged = (next + roundGames).distinctBy { it.id }.sortedBy { it.dateMs }
        fixtures[cacheKey] = Cached(
            expiresAtMs = theSportsDbNextDayStartMs(now, zone()),
            value = merged
        )
        return merged
    }

    /**
     * The league's table, cached for six hours (`STANDINGS_TTL_MS` in the ESPN
     * repository - a table moves at most once a day).
     *
     * Empty when the league has no table: `lookuptable` answers `{"table":""}` and
     * [theSportsDbTable] answers nothing, which the hub draws as no table rather
     * than an empty one that reads as last place (spec F4). The free key caps the
     * table at its top five rows; the client returns what the endpoint sends
     * rather than inventing the rest.
     */
    suspend fun standings(key: String, league: SportsLeague): List<StandingGroup> {
        syncKey(key)
        val leagueId = league.theSportsDbId ?: return emptyList()
        val cacheKey = "$key:$leagueId"
        val cached = tables[cacheKey]
        val now = clock()
        if (cached != null && now < cached.expiresAtMs) return cached.value
        if (now < blockedUntilMs) return cached?.value.orEmpty()

        val body = httpGet(key, "lookuptable.php?l=$leagueId", "table $leagueId")
        val parsedBody = body?.let { payload ->
            val hasTable = runCatching { JSONObject(payload).has("table") }.getOrNull()
            if (hasTable == true) {
                theSportsDbTable(payload, league.label, league.path)
            } else {
                null
            }
        }
        if (parsedBody == null) {
            tableFailed += leagueId
            return cached?.value.orEmpty()
        }
        tableFailed -= leagueId
        tables[cacheKey] = Cached(now + TABLE_TTL_MS, parsedBody)
        return parsedBody
    }

    /**
     * [games] with TheSportsDB's badge filled in where the feed carried none
     * (spec F3).
     *
     * Only sides with no logo are looked up - ESPN's own crest always wins, so a
     * team that already has one cannot flicker between sources - and only one
     * team name costs one call, cached for the day. When nothing is missing this
     * makes no request at all, which is the common case: the fixture payload
     * already carries the two badges, and an image URL costs no API call.
     */
    suspend fun withBadges(key: String, games: List<SportsGame>): List<SportsGame> {
        if (games.isEmpty()) return games
        syncKey(key)
        val missing = ArrayList<String>()
        games.forEach { game ->
            listOf(game.away, game.home).forEach { side ->
                if (side.logoUrl.isNullOrBlank()) {
                    val badgeKey = theSportsDbBadgeKey(side.displayName)
                    if (!badges.containsKey(badgeCacheKey(key, badgeKey))) missing += badgeKey
                }
            }
        }
        if (missing.isEmpty()) return games

        val found = HashMap<String, String>()
        missing.distinct().forEach { badgeKey ->
            val cacheKey = badgeCacheKey(key, badgeKey)
            val cached = badges[cacheKey]
            if (cached != null && clock() < cached.expiresAtMs) {
                cached.value?.let { found[badgeKey] = it }
                return@forEach
            }
            if (clock() < blockedUntilMs) return@forEach
            val body = httpGet(
                key,
                "searchteams.php?t=" + URLEncoder.encode(badgeKey, "UTF-8"),
                "badge $badgeKey",
                quiet = true
            )
            val badge = body?.let(::theSportsDbTeamBadge)
            // A miss is cached too: a team TheSportsDB cannot name must not cost
            // a request on every beat.
            val now = clock()
            badges[cacheKey] = Cached(
                expiresAtMs = theSportsDbNextDayStartMs(now, zone()),
                value = badge
            )
            badge?.let { found[badgeKey] = it }
        }
        return theSportsDbFillBadges(games, found)
    }

    /** The cache identity of one badge lookup; the name is already normalised. */
    private fun badgeCacheKey(key: String, badgeKey: String) = "$key:badge:$badgeKey"

    /**
     * Drops every cache entry when the key changes.
     *
     * A paste in Settings has to take effect immediately: the test key's cached
     * fixture list must not answer for the viewer's own key (different limits,
     * different freshness). Cheap because it only runs when the key actually
     * changed.
     */
    private fun syncKey(key: String) {
        if (activeKey == key) return
        synchronized(this) {
            if (activeKey == key) return
            activeKey = key
            fixtures.clear()
            tables.clear()
            badges.clear()
            failed.clear()
            tableFailed.clear()
        }
    }

    /**
     * One V1 request, or null on any failure.
     *
     * A 429 is the one status handled by name: it arms [blockedUntilMs] from the
     * response's own `Retry-After` (never less than a minute), so the next call
     * inside the window does not spend the shared key's budget on a request that
     * is already known to be refused.
     */
    private suspend fun httpGet(
        key: String,
        path: String,
        what: String,
        quiet: Boolean = false
    ): String? = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            if (clock() < blockedUntilMs) return@runCatchingCancellable null
            val url = THESPORTSDB_BASE + key + "/" + path
            val request = Request.Builder().url(url).get().build()
            client.newCall(request).execute().use { response ->
                if (response.code == 429) {
                    val retryAfterMs = response.header("Retry-After")
                        ?.trim()
                        ?.toLongOrNull()
                        ?.times(1_000L)
                        ?: RETRY_AFTER_MS
                    lastRateLimitedAtMs = clock()
                    blockedUntilMs = clock() + retryAfterMs.coerceAtLeast(RETRY_AFTER_MS)
                    if (!quiet) Log.w(TAG, "$what -> HTTP 429, backing off ${retryAfterMs}ms")
                    return@runCatchingCancellable null
                }
                if (!response.isSuccessful) {
                    if (!quiet) Log.w(TAG, "$what -> HTTP ${response.code}")
                    return@runCatchingCancellable null
                }
                response.body.string()
            }
        }.getOrElse { error ->
            if (!quiet) Log.w(TAG, "$what failed: ${error.message}")
            null
        }
    }

    companion object {
        private const val TAG = "THESPORTSDB"

        /** A table moves at most daily; held for a quarter of a day, like ESPN's. */
        internal const val TABLE_TTL_MS = 6L * 60L * 60_000L

        /** The wait after a 429 when the endpoint sends no `Retry-After`. */
        internal const val RETRY_AFTER_MS = 60L * 1_000L

        /**
         * The key every request uses: the viewer's own when they pasted one, the
         * published test key otherwise. Read per call rather than at
         * construction, so a paste in Settings takes effect on the next refresh.
         */
        fun keyFor(context: Context): String =
            AppPreferences.getTheSportsDbKey(context).ifBlank { THESPORTSDB_TEST_KEY }

        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15L, TimeUnit.SECONDS)
            .readTimeout(15L, TimeUnit.SECONDS)
            .build()

        @Volatile
        private var sharedInstance: TheSportsDbClient? = null

        /**
         * The one client the app shares: the cache IS the client, and the hub is
         * opened and closed many times a session - a second instance would refetch
         * the same fixture list on every entry.
         */
        fun shared(): TheSportsDbClient =
            sharedInstance ?: synchronized(this) {
                sharedInstance ?: TheSportsDbClient().also { sharedInstance = it }
            }
    }
}

/**
 * The events a V1 body carries, or null when the body is not an events document
 * at all (an error page, a truncated answer).
 *
 * The distinction is the whole "never blanks" rule: `{"events":""}` is a real
 * answer about an empty matchday and is cached as one; `error code: 1015` is a
 * failure and must leave the previous cache in place.
 */
private fun strictEvents(body: String, leaguePath: String): List<SportsGame>? {
    val hasEvents = runCatching { JSONObject(body).has("events") }.getOrNull() ?: return null
    if (!hasEvents) return null
    return theSportsDbGames(body, leaguePath)
}
