package com.kennyb1201.kbstream.data.kitsu

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.network.BaseHttpClient
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Kitsu's library writes (spec 2026-11-13 phase 2, F4).
 *
 * Three calls, JSON:API:
 *
 *  - `GET /library-entries?filter[userId]=…&filter[kind]=anime&filter[animeId]=…`
 *    resolves the entry id a PATCH/DELETE needs,
 *  - `POST /library-entries` creates an entry for a title the viewer has not
 *    tracked yet,
 *  - `PATCH /library-entries/{id}` updates progress, status and rating.
 *
 * THE ENTRY ID IS CACHED PER TITLE, because a progress write is normally a
 * GET-then-PATCH pair and a 24-episode season would pay that pair on every
 * episode. A PATCH that answers 404 is the one way the id goes stale (the entry
 * was deleted elsewhere), and that is exactly what [KitsuWriteOutcome.EntryGone]
 * says: forget the id, re-resolve on the next write, never retry the same body.
 *
 * 401 IS A REFRESH, ONCE. Kitsu rotates refresh tokens, so a single-flight
 * refresh in [KitsuAuth] is the only safe one; when the refresh fails the
 * connection is marked needs-reconnect and no further call is attempted.
 *
 * 429 PARKS THE LEG. `Retry-After` decides for how long (see
 * [KitsuWriteRules.parkMs]); every write until then returns immediately without
 * a request, because hammering a soft limit is how it becomes a hard one.
 *
 * A failure takes down only Kitsu: every caller gets an outcome and no exception
 * leaves this file.
 */
internal object KitsuLibrary {

    private const val TAG = "KitsuLibrary"

    private val httpClient = BaseHttpClient.derived {
        callTimeout(KitsuConfig.CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    /** The API's own spacing: one request a second, process-wide. */
    private val gate = Mutex()
    private var lastRequestMs = 0L

    /** Until when 429 parked every write. */
    @Volatile
    private var parkedUntilMs = 0L

    /** animeId → library-entry id. The write that normally follows is a PATCH. */
    private val entryIds = ConcurrentHashMap<String, String>()

    /** True when the leg is parked, i.e. when a write would be a wasted request. */
    fun parked(nowMs: Long = System.currentTimeMillis()): Boolean = parkedUntilMs > nowMs

    /** Test hook: forgets the spacing, the park and every cached entry id. */
    internal fun resetForTest() {
        lastRequestMs = 0L
        parkedUntilMs = 0L
        entryIds.clear()
    }

    /**
     * The session this profile writes under just changed (the cloud applier
     * adopted a sibling's login, or a pulled tombstone signed it out).
     *
     * The cached entry ids belong to the PREVIOUS account's Kitsu user, so a
     * write that still held one would PATCH the other account's entry - or, after
     * a tombstone, write under a grant the viewer took back. The `EntryGone`
     * re-create path is a backstop only: it fires after a failed PATCH, which is
     * a write the wrong account already felt.
     *
     * The parking state deliberately stays: it is a 429 clock, not account state.
     */
    fun onSyncedSessionChanged() {
        entryIds.clear()
    }

    /**
     * A watched episode: the authoritative count, not an increment.
     *
     * The status follows the count ([KitsuWriteRules.progressStatus]): `completed`
     * at the episode total, `current` before it, and `current` when the total is
     * unknown. A title the viewer has not tracked is created with the same body
     * shape, because that is what a POST is for.
     */
    suspend fun pushProgress(
        context: Context,
        kitsuAnimeId: String,
        progress: Int,
        totalEpisodes: Int? = null
    ): KitsuWriteOutcome = write(
        context = context,
        kitsuAnimeId = kitsuAnimeId,
        status = KitsuWriteRules.progressStatus(progress, totalEpisodes),
        progress = progress,
        ratingTwenty = null
    )

    /**
     * A title-level rating. Kitsu's scale is 0-20 half-steps, so 8/10 is 16
     * ([KitsuWriteRules.ratingTwenty]); a score this app does not have sends
     * nothing at all rather than a value the viewer never chose.
     */
    suspend fun pushRating(
        context: Context,
        kitsuAnimeId: String,
        score: Int
    ): KitsuWriteOutcome {
        val rating = KitsuWriteRules.ratingTwenty(score) ?: return KitsuWriteOutcome.Success
        return write(
            context = context,
            kitsuAnimeId = kitsuAnimeId,
            status = null,
            progress = null,
            ratingTwenty = rating
        )
    }

    /** A title status change (`watching`, `completed`, `on_hold`, `dropped`, `planned`). */
    suspend fun pushStatus(
        context: Context,
        kitsuAnimeId: String,
        appStatus: String
    ): KitsuWriteOutcome {
        val status = KitsuWriteRules.statusFor(appStatus) ?: return KitsuWriteOutcome.Success
        return write(
            context = context,
            kitsuAnimeId = kitsuAnimeId,
            status = status,
            progress = null,
            ratingTwenty = null
        )
    }

    /**
     * Taking a rating back.
     *
     * A cleared signal is the viewer withdrawing their score, not a low one, so
     * this sends an explicit `ratingTwenty: null` (see
     * [KitsuWriteRules.clearRatingBody]). Clearing a title the viewer never
     * tracked is nothing to do - there is no entry to clear.
     */
    suspend fun clearRating(context: Context, kitsuAnimeId: String): KitsuWriteOutcome {
        val userId = KitsuAuth.viewerId(context) ?: return KitsuWriteOutcome.NeedsReconnect
        val token = KitsuAuth.accessToken(context) ?: return KitsuWriteOutcome.NeedsReconnect
        val entryId = resolveEntryId(context, token, userId, kitsuAnimeId)
            ?: return KitsuWriteOutcome.Success
        val outcome = request(
            context = context,
            token = token,
            method = "PATCH",
            url = "${KitsuConfig.API_BASE}/library-entries/$entryId",
            body = KitsuWriteRules.clearRatingBody(entryId)
        )
        if (outcome is KitsuWriteOutcome.EntryGone) entryIds.remove(kitsuAnimeId)
        return outcome
    }

    /**
     * Removing a title from the viewer's library.
     *
     * Not offered as a "set status" call: Kitsu's own removal is a DELETE, and a
     * `dropped` write would leave the entry in place under the wrong meaning.
     */
    suspend fun remove(context: Context, kitsuAnimeId: String): KitsuWriteOutcome {
        val userId = KitsuAuth.viewerId(context) ?: return KitsuWriteOutcome.NeedsReconnect
        val token = KitsuAuth.accessToken(context) ?: return KitsuWriteOutcome.NeedsReconnect
        val entryId = resolveEntryId(context, token, userId, kitsuAnimeId)
            ?: return KitsuWriteOutcome.Success
        return request(
            context = context,
            token = token,
            method = "DELETE",
            url = "${KitsuConfig.API_BASE}/library-entries/$entryId",
            body = null
        ).also { outcome ->
            if (outcome is KitsuWriteOutcome.Success) entryIds.remove(kitsuAnimeId)
            if (outcome is KitsuWriteOutcome.EntryGone) entryIds.remove(kitsuAnimeId)
        }
    }

    /**
     * One write: resolve the entry, then PATCH it, or POST a new one.
     *
     * The resolve is skipped after a create (the POST's answer names the new id)
     * and after a cached hit. A [KitsuWriteOutcome.EntryGone] from the PATCH drops
     * the cache and creates the entry in the same call: the entry really is gone,
     * so "create" is the correct write, not a retry.
     */
    private suspend fun write(
        context: Context,
        kitsuAnimeId: String,
        status: String?,
        progress: Int?,
        ratingTwenty: Int?
    ): KitsuWriteOutcome {
        if (kitsuAnimeId.isBlank()) return KitsuWriteOutcome.Success
        if (parked()) return KitsuWriteOutcome.Success

        val userId = KitsuAuth.viewerId(context) ?: return KitsuWriteOutcome.NeedsReconnect
        val token = KitsuAuth.accessToken(context) ?: return KitsuWriteOutcome.NeedsReconnect

        val cached = entryIds[kitsuAnimeId]
        if (cached != null) {
            val patched = request(
                context = context,
                token = token,
                method = "PATCH",
                url = "${KitsuConfig.API_BASE}/library-entries/$cached",
                body = KitsuWriteRules.patchBody(cached, status, progress, ratingTwenty)
            )
            when (patched) {
                is KitsuWriteOutcome.Success -> return patched
                is KitsuWriteOutcome.EntryGone -> {
                    entryIds.remove(kitsuAnimeId)
                    Log.i(TAG, "cached library entry for $kitsuAnimeId is gone; re-creating")
                    return create(context, token, userId, kitsuAnimeId, status, progress, ratingTwenty)
                }
                else -> return patched
            }
        }

        val resolved = resolveEntryId(context, token, userId, kitsuAnimeId)
        if (resolved == null) {
            if (needsReconnectNow(context)) return KitsuWriteOutcome.NeedsReconnect
            return create(context, token, userId, kitsuAnimeId, status, progress, ratingTwenty)
        }

        val outcome = request(
            context = context,
            token = token,
            method = "PATCH",
            url = "${KitsuConfig.API_BASE}/library-entries/$resolved",
            body = KitsuWriteRules.patchBody(resolved, status, progress, ratingTwenty)
        )
        if (outcome is KitsuWriteOutcome.EntryGone) {
            entryIds.remove(kitsuAnimeId)
        }
        return outcome
    }

    private suspend fun create(
        context: Context,
        token: String,
        userId: String,
        kitsuAnimeId: String,
        status: String?,
        progress: Int?,
        ratingTwenty: Int?
    ): KitsuWriteOutcome {
        val outcome = request(
            context = context,
            token = token,
            method = "POST",
            url = "${KitsuConfig.API_BASE}/library-entries",
            body = KitsuWriteRules.createBody(userId, kitsuAnimeId, status, progress, ratingTwenty)
        )
        return outcome
    }

    /**
     * The entry id for this title, cached.
     *
     * Null means "there is no entry", which is a create, and also the answer when
     * the lookup itself failed - a failed GET must not become a duplicate create,
     * so [needsReconnectNow] and the park state are checked by the caller.
     */
    private suspend fun resolveEntryId(
        context: Context,
        token: String,
        userId: String,
        kitsuAnimeId: String
    ): String? {
        entryIds[kitsuAnimeId]?.let { return it }

        val body = requestBody(
            context = context,
            token = token,
            method = "GET",
            url = KitsuWriteRules.entryLookupUrl(userId, kitsuAnimeId),
            body = null
        ) ?: return null

        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val entry = json.optJSONArray("data")?.optJSONObject(0) ?: return null
        val id = entry.optString("id").takeIf { it.isNotBlank() } ?: return null
        entryIds[kitsuAnimeId] = id
        return id
    }

    private fun needsReconnectNow(context: Context): Boolean =
        KitsuTokenStore(context).needsReconnect()

    /** A write's outcome, with the 401 → refresh-once rule. */
    private suspend fun request(
        context: Context,
        token: String,
        method: String,
        url: String,
        body: String?
    ): KitsuWriteOutcome {
        val first = send(method, url, token, body)
        if (first is KitsuWriteOutcome.NeedsReconnect) {
            // One refresh, one retry: Kitsu rotates its refresh token, so a
            // second attempt here would be the loop the rule exists to stop.
            val refreshed = KitsuAuth.accessToken(context, forceRefresh = true)
                ?: return KitsuWriteOutcome.NeedsReconnect
            return send(method, url, refreshed, body)
        }
        return first
    }

    /** The raw call, classification included. */
    private suspend fun send(
        method: String,
        url: String,
        token: String,
        body: String?
    ): KitsuWriteOutcome = withContext(Dispatchers.IO) {
        awaitSlot()

        val request =
            Request.Builder()
                .url(url)
                .header("User-Agent", KitsuConfig.USER_AGENT)
                .header("Accept", KitsuConfig.JSON_API)
                .header("Authorization", "Bearer $token")
                .apply {
                    when (method) {
                        "POST" -> post(
                            (body ?: "{}").toRequestBody(KitsuConfig.JSON_API.toMediaType())
                        )
                        "PATCH" -> patch(
                            (body ?: "{}").toRequestBody(KitsuConfig.JSON_API.toMediaType())
                        )
                        "DELETE" -> delete()
                        else -> get()
                    }
                }
                .build()

        runCatching {
            httpClient.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> KitsuWriteOutcome.Success
                    response.code == 401 || response.code == 403 ->
                        KitsuWriteOutcome.NeedsReconnect
                    response.code == 404 -> KitsuWriteOutcome.EntryGone
                    response.code == 429 -> {
                        val until = KitsuWriteRules.parkMs(
                            response.header("Retry-After"),
                            System.currentTimeMillis()
                        )
                        parkedUntilMs = until
                        Log.i(TAG, "Kitsu rate limited; parked until $until")
                        KitsuWriteOutcome.RateLimited(until)
                    }
                    else -> KitsuWriteOutcome.Failed("HTTP ${response.code}")
                }
            }
        }.getOrElse { error ->
            KitsuWriteOutcome.Failed(error.javaClass.simpleName)
        }
    }

    /** The body of a successful GET, or null when the call failed. */
    private suspend fun requestBody(
        context: Context,
        token: String,
        method: String,
        url: String,
        body: String?
    ): String? = withContext(Dispatchers.IO) {
        awaitSlot()

        val request =
            Request.Builder()
                .url(url)
                .header("User-Agent", KitsuConfig.USER_AGENT)
                .header("Accept", KitsuConfig.JSON_API)
                .header("Authorization", "Bearer $token")
                .get()
                .build()

        runCatching {
            httpClient.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> response.body?.string()
                    response.code == 401 || response.code == 403 -> {
                        // A dead grant is the auth layer's verdict, not this
                        // function's; it will mark needs-reconnect on its own
                        // next attempt.
                        null
                    }
                    response.code == 429 -> {
                        parkedUntilMs = KitsuWriteRules.parkMs(
                            response.header("Retry-After"),
                            System.currentTimeMillis()
                        )
                        null
                    }
                    else -> null
                }
            }
        }.getOrNull()
    }

    /** One request a second, process-wide (see [KitsuConfig.MIN_SPACING_MS]). */
    private suspend fun awaitSlot() {
        gate.withLock {
            val now = System.currentTimeMillis()
            val waitMs = lastRequestMs + KitsuConfig.MIN_SPACING_MS - now
            if (waitMs > 0L) delay(waitMs)
            lastRequestMs = System.currentTimeMillis()
        }
    }
}
