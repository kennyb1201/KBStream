package com.kennyb1201.kbstream.data.tvdb

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.network.BaseHttpClient
import com.kennyb1201.kbstream.data.settings.AppPreferences
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * TheTVDB v4's read surface (spec 2026-11-13, F1).
 *
 * Three calls, all lazy: `POST /login`, `GET /series/{id}/extended` (the
 * series' own `seasonTypes`) and `GET /series/{id}/episodes/{seasonType}?page=N`
 * (one order's episodes). Nothing here prefetches - a show is only asked about
 * once its Detail screen or its order picker needs the answer.
 *
 * ## Auth
 *
 * `/login` takes `{"apikey": ...}` and returns a bearer token valid one month
 * (verified against the live Swagger on 2026-11-13). A user-supported key also
 * needs the subscriber PIN, and ONLY then: the docs are explicit that `pin` is
 * omitted entirely otherwise, so the body is built with the field absent rather
 * than blank. The PIN comes from the synced API-key slot, never from the APK
 * (see AppPreferences.getTvdbPin).
 *
 * A 401 re-logins once (single-flight, so ten parallel reads mint one token) and
 * retries that one call; a second 401 gives up and the caller answers F4's
 * "TMDB order, exactly as today".
 *
 * ## The empty-list trap
 *
 * An INVALID season type answers HTTP 200 with an EMPTY `episodes` array
 * (Jellyfin's tvdb plugin hit this; it is TVDB behaviour, not a bug in one
 * client). An empty answer is therefore never authoritative here: the caller
 * validates the type against the series' own `seasonTypes` first, and
 * [seriesEpisodes] reports [TvdbEpisodesRead.Empty] as its own fact rather than
 * an empty list, so nothing can renumber, hide or delete on it.
 *
 * ## Rate limits
 *
 * None is published - neither the Swagger, the repo README nor the
 * api-information page states one (checked 2026-11-13). That is not the same as
 * having none, so the client is conservative rather than clever: one in-flight
 * request at a time per call site, 500-episode pages (TVDB's own page size),
 * results cached for days (see TvdbOrderStore), and a 429 parks the leg for the
 * `Retry-After` it names instead of retrying inside the window.
 *
 * ## Pagination
 *
 * The `{seasonType}/{lang}` variant is NOT used: its `page` parameter is ignored
 * and it always answers the first 500 episodes (thetvdb/v4-api#74). The
 * untranslated `{seasonType}` endpoint paginates correctly and carries the same
 * numbers, which is all an order needs.
 */
internal object TvdbApiClient {

    private const val TAG = "Tvdb"

    private const val TIMEOUT_MS = 10_000L

    private val JSON = "application/json".toMediaType()

    private val httpClient = BaseHttpClient.derived {
        callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    /** TVDB's page size; a long-running show is a handful of pages. */
    private const val PAGE_SIZE_GUARD = 500

    /** A page loop can never run longer than this (a paging bug would). */
    private const val MAX_PAGES = 12

    /** The fallback park when a 429 names no usable `Retry-After`. */
    private const val DEFAULT_PARK_MS = 60_000L

    /** One login at a time: every concurrent read waits for the same token. */
    private val loginGate = Mutex()

    @Volatile
    private var memoryToken: String? = null

    @Volatile
    private var parkedUntilMs = 0L

    /** True while a 429's `Retry-After` window has not expired. */
    internal fun isParked(nowMs: Long = System.currentTimeMillis()): Boolean =
        parkedUntilMs > nowMs

    /** Test hook: forgets the in-memory token and the park window. */
    internal fun resetForTest() {
        memoryToken = null
        parkedUntilMs = 0L
    }

    // ── Reads ─────────────────────────────────────────────────────────────

    /**
     * The series' own `seasonTypes`, or null when TVDB has nothing to say.
     *
     * Null covers "not configured", "no tvdb id", a transport failure, a 404 and
     * an unreadable body alike - every one of those is F4's "this show has no
     * alternate orders" from the viewer's side.
     */
    suspend fun seriesSeasonTypes(context: Context, tvdbId: Int?): List<TvdbSeasonType>? {
        val id = tvdbId?.takeIf { it > 0 } ?: return null
        if (!TvdbConfig.isConfigured) return null

        val body = authorizedJson(context, "$TvdbConfig.API_BASE/series/$id/extended")
            ?: return null
        return parseSeasonTypes(body.optJSONObject("data"))
    }

    /**
     * One order's episode list for a series, every page.
     *
     * [TvdbEpisodesRead.Empty] is a distinct answer on purpose: it is what an
     * unknown season type returns, and the one thing the caller must never
     * mistake for "this order has no episodes".
     */
    suspend fun seriesEpisodes(
        context: Context,
        tvdbId: Int?,
        seasonType: String
    ): TvdbEpisodesRead {
        val id = tvdbId?.takeIf { it > 0 } ?: return TvdbEpisodesRead.Failed("no tvdb id")
        val type = seasonType.trim()
        if (type.isEmpty()) return TvdbEpisodesRead.Failed("no season type")
        if (!TvdbConfig.isConfigured) return TvdbEpisodesRead.NotConfigured

        val out = ArrayList<TvdbEpisodeRecord>()
        var page = 0

        while (page < MAX_PAGES) {
            if (isParked()) return TvdbEpisodesRead.Parked

            val url =
                "$TvdbConfig.API_BASE/series/$id/episodes/$type?page=$page"
            val body = authorizedJson(context, url, allowPark = true)
                ?: return if (out.isEmpty()) {
                    TvdbEpisodesRead.Failed("read failed")
                } else {
                    TvdbEpisodesRead.Ok(out)
                }

            val data = body.optJSONObject("data")
            val rows = data?.optJSONArray("episodes") ?: return TvdbEpisodesRead.Failed("no episodes array")
            for (index in 0 until rows.length()) {
                parseEpisode(rows.optJSONObject(index))?.let { out += it }
            }

            // The paging contract: `links.next` is null on the last page. A bare
            // short page is NOT trusted as the end (the documented page size is
            // 500, but a filtered response can be shorter and still have more),
            // so only an explicit next ends the loop otherwise.
            val next = body.optJSONObject("links")?.optString("next")?.trim().orEmpty()
            if (next.isEmpty() || next == "null") break
            if (rows.length() < PAGE_SIZE_GUARD / 4) break
            page++
        }

        // Nothing parsed on page 0 is the invalid-type answer, not an order with
        // no episodes: kept as its own signal so the caller can refuse to
        // renumber (see the class docs).
        if (out.isEmpty()) return TvdbEpisodesRead.Empty
        return TvdbEpisodesRead.Ok(out)
    }

    // ── HTTP ──────────────────────────────────────────────────────────────

    private suspend fun authorizedJson(
        context: Context,
        url: String,
        allowPark: Boolean = false
    ): JSONObject? = withContext(Dispatchers.IO) {
        val first = execute(context, url, forceLogin = false, allowPark = allowPark)
        if (first is Get.Unauthorized) {
            // Single-flight re-login, then ONE retry of this call. Anything
            // that fails again is final - a loop here would hammer a key that
            // is genuinely refused.
            val retried = execute(context, url, forceLogin = true, allowPark = allowPark)
            return@withContext (retried as? Get.Ok)?.body
        }
        (first as? Get.Ok)?.body
    }

    private sealed interface Get {
        data class Ok(val body: JSONObject) : Get
        data object Unauthorized : Get
        data object Missing : Get
        data object Parked : Get
    }

    private suspend fun execute(
        context: Context,
        url: String,
        forceLogin: Boolean,
        allowPark: Boolean
    ): Get {
        val token = tokenFor(context, forceLogin) ?: return Get.Unauthorized

        val request =
            Request.Builder()
                .url(url)
                .header("User-Agent", TvdbConfig.USER_AGENT)
                .header("Accept", "application/json")
                .header("Authorization", "Bearer $token")
                .get()
                .build()

        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> {
                        val text = response.body?.string().orEmpty()
                        Get.Ok(JSONObject(text))
                    }
                    response.code == 401 -> Get.Unauthorized
                    response.code == 429 -> {
                        val parkMs = retryAfterMs(response.header("Retry-After"))
                        parkedUntilMs = System.currentTimeMillis() + parkMs
                        Log.w(TAG, "TVDB parked for ${parkMs}ms (429)")
                        if (allowPark) Get.Parked else Get.Missing
                    }
                    else -> {
                        Log.w(TAG, "TVDB read ${response.code} for ${url.substringBefore('?')}")
                        Get.Missing
                    }
                }
            }
        }.getOrElse { error ->
            Log.w(TAG, "TVDB read failed: ${error.javaClass.simpleName}")
            Get.Missing
        }
    }

    /**
     * A usable token, minting one when there is none, [forceLogin] asked for one,
     * or the cached one is absent.
     *
     * The in-memory copy short-circuits the encrypted-store read on the hot
     * path; the store is the durable copy across process starts.
     */
    private suspend fun tokenFor(context: Context, forceLogin: Boolean): String? {
        if (!forceLogin) {
            memoryToken?.let { return it }
            TvdbTokenStore(context).token()?.let {
                memoryToken = it
                return it
            }
        }
        return login(context)
    }

    /**
     * `POST /login`, single-flight.
     *
     * The PIN field is added only when one is configured: the docs instruct
     * clients without a user-supported key to remove `pin` from the call
     * entirely, and a blank string is not the same request.
     */
    private suspend fun login(context: Context): String? = loginGate.withLock {
        // Another caller minted it while this one waited for the gate.
        if (memoryToken == null) {
            TvdbTokenStore(context).token()?.let { memoryToken = it }
        }
        memoryToken?.let { return@withLock it }

        if (!TvdbConfig.isConfigured) return@withLock null

        val payload = JSONObject().put("apikey", TvdbConfig.apiKey)
        val pin = runCatching { AppPreferences.getTvdbPin(context) }.getOrDefault("")
        if (pin.isNotBlank()) payload.put("pin", pin)

        val request =
            Request.Builder()
                .url("$TvdbConfig.API_BASE/login")
                .header("User-Agent", TvdbConfig.USER_AGENT)
                .header("Accept", "application/json")
                .post(payload.toString().toRequestBody(JSON))
                .build()

        val body = withContext(Dispatchers.IO) {
            runCatching {
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "TVDB login ${response.code}")
                        return@use null
                    }
                    JSONObject(response.body?.string().orEmpty())
                }
            }.getOrElse { error ->
                Log.w(TAG, "TVDB login failed: ${error.javaClass.simpleName}")
                null
            }
        } ?: return@withLock null

        val token = body.optJSONObject("data")?.optString("token")?.trim().orEmpty()
        if (token.isEmpty()) return@withLock null

        memoryToken = token
        TvdbTokenStore(context).save(
            token = token,
            expiresAtMs = System.currentTimeMillis() + TvdbTokenStore.TOKEN_LIFETIME_MS
        )
        token
    }

    // ── Parsing ───────────────────────────────────────────────────────────

    private fun parseSeasonTypes(data: JSONObject?): List<TvdbSeasonType>? {
        val rows = data?.optJSONArray("seasonTypes") ?: return null
        val out = ArrayList<TvdbSeasonType>(rows.length())
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            val type = row.optString("type").trim()
            val name = row.optString("name").trim()
            if (type.isEmpty()) continue
            out += TvdbSeasonType(
                name = name.ifEmpty { type },
                type = type,
                alternateName = row.optString("alternateName").trim().takeIf { it.isNotEmpty() }
            )
        }
        return out
    }

    /**
     * One episode record. `number` is the order's own number; a record with no
     * usable number is not an episode this app can show, so it is dropped rather
     * than numbered 0 (the same rule
     * [com.kennyb1201.kbstream.data.namedEpisodeNumber] applies everywhere else).
     */
    private fun parseEpisode(row: JSONObject?): TvdbEpisodeRecord? {
        val node = row ?: return null
        val number = node.optInt("number", 0).takeIf { it > 0 } ?: return null
        val season = node.optInt("seasonNumber", 0).takeIf { it >= 0 } ?: 0
        return TvdbEpisodeRecord(
            season = season,
            number = number,
            name = node.optString("name").trim().takeIf { it.isNotEmpty() },
            airDate = node.optString("aired").trim().takeIf { it.isNotEmpty() },
            runtimeMinutes = node.optInt("runtime", 0).takeIf { it > 0 }
        )
    }

    /**
     * `Retry-After` as milliseconds. The header is seconds in TVDB's responses
     * (the HTTP-date form is legal but never observed); anything unparseable or
     * non-positive falls back to a minute rather than retrying immediately.
     */
    internal fun retryAfterMs(header: String?): Long {
        val seconds = header?.trim()?.toLongOrNull() ?: return DEFAULT_PARK_MS
        if (seconds <= 0L) return DEFAULT_PARK_MS
        return (seconds * 1000L).coerceAtMost(15L * 60L * 1000L)
    }
}

/** What one order's episode read produced. */
internal sealed interface TvdbEpisodesRead {
    data class Ok(val episodes: List<TvdbEpisodeRecord>) : TvdbEpisodesRead

    /**
     * HTTP 200 with an empty `episodes` array - what an unknown season type
     * answers. Never authoritative: the caller refuses to renumber on it.
     */
    data object Empty : TvdbEpisodesRead

    /** No project key in this build. */
    data object NotConfigured : TvdbEpisodesRead

    /** A 429's window is still open. */
    data object Parked : TvdbEpisodesRead

    data class Failed(val reason: String) : TvdbEpisodesRead
}
