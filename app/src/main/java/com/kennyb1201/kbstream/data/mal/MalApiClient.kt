package com.kennyb1201.kbstream.data.mal

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.anime.animeTitleMatches
import com.kennyb1201.kbstream.data.anime.animeUrlEncode
import com.kennyb1201.kbstream.data.network.BaseHttpClient
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * MyAnimeList's v2 read/write surface (spec 2026-11-13).
 *
 * TWO modes, exactly as MAL defines them:
 *
 *  - **Public** reads carry `X-MAL-CLIENT-ID` and need no account. This is what
 *    makes the MAL id an authoritative id source (F4): the id lookup works for
 *    every user, before anyone connects anything, as long as the build carries
 *    the app's client id.
 *  - **Authorized** reads and ALL writes carry `Authorization: Bearer …` and go
 *    through [MalAuth]'s token, which refreshes itself once on a 401 and gives up
 *    honestly (needs-reconnect) when it cannot.
 *
 * Every call is bounded by one 8s ceiling and a 429 park: MAL publishes a rate
 * limit (1000/hour per token, by secondary report) rather than documenting it, so
 * the client honours `Retry-After` instead of retrying inside the window - a
 * retry there only extends it.
 *
 * The `fields` parameter is always sent. MAL's default body is sparse, and the
 * spec forbids requesting more than a screen uses, so each read names exactly
 * what it needs.
 */
internal object MalApiClient {

    private const val TAG = "MalApi"

    private const val TIMEOUT_MS = 8_000L

    private val JSON = "application/json".toMediaType()

    private val httpClient = BaseHttpClient.derived {
        callTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    /** Public-search page size. Small on purpose: this is an id lookup, not a browser. */
    private const val SEARCH_LIMIT = 5

    /** One list page is MAL's maximum; a big list is two or three pages. */
    const val LIST_PAGE_LIMIT = 1000

    /** How many list pages a leg reads before it stops (guards a paging bug). */
    private const val MAX_LIST_PAGES = 5

    // ── Public reads (client id only) ─────────────────────────────────────

    /**
     * The MAL id for a title, and the facts the Detail header shows.
     *
     * One `GET /anime?q=` for the id, then - only when the title matched - the
     * detail call for `mean`/`rank`, which is the same call F4 says must carry
     * the score ("do not make a second MAL call for the score"). Two requests
     * total for a title that resolves; none of the second one when it does not.
     *
     * The title gate is the same exact normalized match the hub's other steps
     * use: MAL answers a fuzzy search, and a live-action show that resembles an
     * anime must not inherit that anime's id.
     */
    suspend fun resolveTitle(showTitle: String?): MalTitle? {
        if (!MalConfig.isConfigured) return null

        val query = showTitle?.trim().orEmpty()
        if (query.isEmpty()) return null

        val search = getJson(
            "$API_BASE/anime?q=${animeUrlEncode(query)}&limit=$SEARCH_LIMIT&fields=${MalConfig.ID_FIELDS}",
            authorized = false
        ) ?: return null

        val rows = search.optJSONArray("data") ?: return null
        var matchedId: Int? = null
        for (index in 0 until rows.length()) {
            val node = rows.optJSONObject(index)?.optJSONObject("node") ?: continue
            if (!malNodeMatchesTitle(node, query)) continue
            matchedId = node.optInt("id", 0).takeIf { it > 0 } ?: continue
            break
        }

        val id = matchedId ?: return null

        val detail = getJson(
            "$API_BASE/anime/$id?fields=${MalConfig.DETAIL_FIELDS}",
            authorized = false
        )

        return MalTitle(
            id = id,
            title = detail?.optString("title")?.takeIf { it.isNotBlank() }
                ?: query,
            mean = detail?.optDouble("mean", Double.NaN)?.takeIf { !it.isNaN() && it > 0.0 },
            rank = detail?.optInt("rank", 0)?.takeIf { it > 0 },
            popularity = detail?.optInt("popularity", 0)?.takeIf { it > 0 },
            numEpisodes = detail?.optInt("num_episodes", 0)?.takeIf { it > 0 }
        )
    }

    // ── Authorized reads ──────────────────────────────────────────────────

    /**
     * This profile's own anime list, one page at a time.
     *
     * `@me` only: MAL has no public username read (unlike AniList and Kitsu), so
     * this leg REQUIRES the OAuth connection - there is no username-only v1.
     *
     * The result is the raw entries plus the reason it stopped, because "could
     * not read" and "empty list" are different facts and only the second may
     * clear a leg's cards.
     */
    suspend fun myAnimeList(context: Context): MalListRead {
        if (!MalConfig.isConfigured) return MalListRead.NotConfigured

        val store = MalTokenStore(context)
        if (store.needsReconnect()) return MalListRead.NeedsReconnect
        if (!store.hasSession()) return MalListRead.NotConnected

        return readAnimeList(context, "$API_BASE/users/@me/animelist")
    }

    /**
     * Somebody else's anime list, by username (spec 2026-11-13, F2).
     *
     * MAL's routes are OAuth-only even for a public profile - there is no
     * unauthenticated `/users/{name}/animelist` - so this read requires THIS
     * profile's connection exactly as the `@me` read does. Without one the
     * caller is told [MalListRead.NotConnected] and the public-lists surface
     * asks for the connection instead of offering a username box that could not
     * answer.
     */
    suspend fun userAnimeList(context: Context, username: String): MalListRead {
        if (!MalConfig.isConfigured) return MalListRead.NotConfigured

        val name = username.trim().removePrefix("@")
        if (name.isEmpty()) return MalListRead.NotConfigured

        val store = MalTokenStore(context)
        if (store.needsReconnect()) return MalListRead.NeedsReconnect
        if (!store.hasSession()) return MalListRead.NotConnected

        val encoded = runCatching {
            java.net.URLEncoder.encode(name, "UTF-8")
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return MalListRead.Failed

        return readAnimeList(context, "$API_BASE/users/$encoded/animelist")
    }

    /** The paged list walk both list reads share. */
    private suspend fun readAnimeList(context: Context, baseUrl: String): MalListRead {
        val entries = ArrayList<JSONObject>()
        var offset = 0
        var page = 0

        while (page < MAX_LIST_PAGES) {
            val body = getJson(
                baseUrl +
                    "?fields=${MalConfig.LIST_FIELDS}" +
                    "&limit=$LIST_PAGE_LIMIT&offset=$offset",
                authorized = true,
                context = context
            ) ?: return if (entries.isEmpty()) {
                MalListRead.Failed
            } else {
                MalListRead.Ok(entries)
            }

            val data = body.optJSONArray("data") ?: break
            for (index in 0 until data.length()) {
                data.optJSONObject(index)?.let { entries += it }
            }

            val next = body.optJSONObject("paging")?.optString("next")?.trim().orEmpty()
            if (next.isEmpty() || data.length() < LIST_PAGE_LIMIT) break
            offset += data.length()
            page++
        }

        return MalListRead.Ok(entries)
    }

    /**
     * The connected account's own name, for Settings' "connected as".
     *
     * The one place the app asks MAL who it is: `GET /users/@me?fields=name`,
     * the same `@me` surface the list leg reads, so the Settings row never grows
     * a second profile source of its own. The name is a DISPLAY fact, not a
     * credential, and it is cached per profile in the token store - composing
     * the row must not spend a request every time. A read that fails (offline, a
     * parked 429) answers null and leaves the row saying "connected"; the next
     * compose fills the name in.
     */
    suspend fun viewerName(context: Context): String? {
        if (!MalConfig.isConfigured) return null

        val store = MalTokenStore(context)
        store.viewerName()?.let { return it }
        if (!store.hasSession() || store.needsReconnect()) return null

        val body = getJson(
            "$API_BASE/users/@me?fields=name",
            authorized = true,
            context = context
        ) ?: return null

        val name = body.optString("name").trim().takeIf { it.isNotEmpty() } ?: return null
        store.saveViewerName(name)
        return name
    }

    // ── Writes ────────────────────────────────────────────────────────────

    /**
     * Updates this anime's list status - the ONE write MAL has.
     *
     * There is no add endpoint: the same call adds a title that is not on the
     * list yet ("Add specified anime to my anime list. If specified anime already
     * exists, update its status."), which is what lets a completion write be a
     * single request. Only the parameters passed are changed.
     *
     * METHOD: the docs label this PATCH while the official request example uses
     * PUT, so the client sends PATCH and falls back to PUT on 405/501, returning
     * which one answered. That is the live-endpoint validation the spec asks for,
     * done at run time on the device that actually talks to MAL.
     *
     * Returns [MalWriteResult.Skipped] when there is no session or no id - a
     * title with no MAL id is simply not written (no error, no toast), and the
     * other trackers' writes are unaffected.
     */
    suspend fun updateListStatus(
        context: Context,
        malId: Int?,
        params: Map<String, String>
    ): MalWriteResult {
        if (!MalConfig.isConfigured) return MalWriteResult.Skipped("not configured")
        val id = malId?.takeIf { it > 0 } ?: return MalWriteResult.Skipped("no MAL id")
        if (params.isEmpty()) return MalWriteResult.Skipped("nothing to update")

        val store = MalTokenStore(context)
        if (store.needsReconnect()) return MalWriteResult.Skipped("needs reconnect")
        if (!store.hasSession()) return MalWriteResult.Skipped("not connected")

        val body = params.entries.joinToString("&") { (key, value) ->
            animeUrlEncode(key) + "=" + animeUrlEncode(value)
        }

        val patched = sendStatusWrite(context, id, body, "PATCH")
        if (patched is MalWriteResult.Written) return patched

        // 405/501: the endpoint wants the other verb. Anything else is final.
        if (patched is MalWriteResult.Failed && patched.statusCode in OTHER_METHOD_STATUSES) {
            return sendStatusWrite(context, id, body, "PUT")
        }
        return patched
    }

    /**
     * `DELETE /anime/{id}/my_list_status` - removing a title from the list. Not
     * wired to a screen yet; it exists so the surface is complete and read-only
     * screens never grow a write path of their own.
     */
    suspend fun deleteListStatus(context: Context, malId: Int?): MalWriteResult {
        val id = malId?.takeIf { it > 0 } ?: return MalWriteResult.Skipped("no MAL id")
        val token = MalAuth.accessToken(context) ?: return MalWriteResult.Skipped("not connected")

        return withContext(Dispatchers.IO) {
            val request =
                Request.Builder()
                    .url("$API_BASE/anime/$id/my_list_status")
                    .header("User-Agent", MalConfig.USER_AGENT)
                    .header("Authorization", "Bearer $token")
                    .delete()
                    .build()
            runCatching {
                httpClient.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> MalWriteResult.Written("DELETE")
                        malNeedsRefresh(response.code) ->
                            MalWriteResult.Failed(401, "unauthorized")
                        else -> MalWriteResult.Failed(response.code, "delete refused")
                    }
                }
            }.getOrElse { error -> MalWriteResult.Failed(0, error.javaClass.simpleName) }
        }
    }

    private suspend fun sendStatusWrite(
        context: Context,
        malId: Int,
        body: String,
        method: String
    ): MalWriteResult {
        val token = MalAuth.accessToken(context) ?: return MalWriteResult.Skipped("not connected")

        return withContext(Dispatchers.IO) {
            val request =
                Request.Builder()
                    .url("$API_BASE/anime/$malId/my_list_status")
                    .header("User-Agent", MalConfig.USER_AGENT)
                    .header("Authorization", "Bearer $token")
                    .method(method, body.toRequestBody(JSON))
                    .build()

            runCatching {
                httpClient.newCall(request).execute().use { response ->
                    when {
                        response.isSuccessful -> MalWriteResult.Written(method)
                        malNeedsRefresh(response.code) -> {
                            // The access token died mid-write: refresh once and
                            // let the caller's own retry (the queue) send it
                            // again. Retrying here would double-send on a
                            // response we cannot see.
                            MalAuth.accessToken(context, forceRefresh = true)
                            MalWriteResult.Failed(401, "unauthorized")
                        }
                        response.code == 429 -> {
                            Log.w(TAG, "MAL write parked: 429")
                            MalWriteResult.Failed(429, "rate limited")
                        }
                        else -> MalWriteResult.Failed(response.code, response.message)
                    }
                }
            }.getOrElse { error -> MalWriteResult.Failed(0, error.javaClass.simpleName) }
        }
    }

    // ── HTTP ──────────────────────────────────────────────────────────────

    private const val API_BASE = MalConfig.API_BASE

    private val OTHER_METHOD_STATUSES = setOf(405, 501)

    private suspend fun getJson(
        url: String,
        authorized: Boolean,
        context: Context? = null
    ): JSONObject? = withContext(Dispatchers.IO) {
        val first = executeGet(url, authorized, context, forceRefresh = false)
        if (first is MalGet.Unauthorized && authorized && context != null) {
            // Single-flight refresh, then ONE retry of this call.
            MalAuth.accessToken(context, forceRefresh = true)
            return@withContext (executeGet(url, authorized, context, forceRefresh = true) as? MalGet.Ok)?.body
        }
        (first as? MalGet.Ok)?.body
    }

    private sealed interface MalGet {
        data class Ok(val body: JSONObject) : MalGet
        data object Unauthorized : MalGet
        data object Missing : MalGet
    }

    private suspend fun executeGet(
        url: String,
        authorized: Boolean,
        context: Context?,
        forceRefresh: Boolean
    ): MalGet {
        val builder = Request.Builder().url(url).header("User-Agent", MalConfig.USER_AGENT)

        if (authorized) {
            // Suspends (never runs a nested event loop): a refresh in flight is
            // waited for, not re-entered.
            val token = context?.let { MalAuth.accessToken(it, forceRefresh) }
            if (token.isNullOrBlank()) return MalGet.Unauthorized
            builder.header("Authorization", "Bearer $token")
        } else {
            // Public reads need the app's id and no account.
            if (!MalConfig.isConfigured) return MalGet.Missing
            builder.header("X-MAL-CLIENT-ID", MalConfig.clientId)
        }

        return runCatching {
            httpClient.newCall(builder.get().build()).execute().use { response ->
                when {
                    response.isSuccessful -> {
                        val body = response.body?.string().orEmpty()
                        MalGet.Ok(JSONObject(body))
                    }
                    malNeedsRefresh(response.code) -> MalGet.Unauthorized
                    response.code == 429 -> {
                        Log.w(TAG, "MAL read parked: 429")
                        MalGet.Missing
                    }
                    else -> MalGet.Missing
                }
            }
        }.getOrElse { error ->
            Log.w(TAG, "MAL read failed: ${error.javaClass.simpleName}")
            MalGet.Missing
        }
    }

    private fun malNodeMatchesTitle(node: JSONObject, showTitle: String): Boolean {
        val alternative = node.optJSONObject("alternative_titles")
        val synonyms = alternative?.optJSONArray("synonyms")

        val names = ArrayList<String?>()
        names += alternative?.optString("en")
        names += alternative?.optString("ja")
        if (synonyms != null) {
            for (index in 0 until synonyms.length()) names += synonyms.optString(index)
        }

        return animeTitleMatches(
            showTitle = showTitle,
            title = node.optString("title"),
            titleEnglish = alternative?.optString("en"),
            titles = names
        )
    }
}

/** One resolved MAL title: the id, plus the Detail header's facts. */
internal data class MalTitle(
    val id: Int,
    val title: String,
    val mean: Double? = null,
    val rank: Int? = null,
    val popularity: Int? = null,
    val numEpisodes: Int? = null
)

/** What one list read produced. */
internal sealed interface MalListRead {
    data class Ok(val entries: List<JSONObject>) : MalListRead

    /** No app client id in this build. */
    data object NotConfigured : MalListRead

    /** Configured, but this profile has not connected. */
    data object NotConnected : MalListRead

    /** MAL answered 401 and the refresh failed: nothing calls it until reconnect. */
    data object NeedsReconnect : MalListRead

    /** The read itself failed (transport, 429, unreadable). Previous cards stand. */
    data object Failed : MalListRead
}

/** What one write produced. */
internal sealed interface MalWriteResult {
    /** [method] is the verb MAL actually accepted, which the log reports. */
    data class Written(val method: String) : MalWriteResult

    data class Failed(val statusCode: Int, val reason: String) : MalWriteResult

    /** No id, no session, or nothing to change: nothing was sent. */
    data class Skipped(val reason: String) : MalWriteResult
}
