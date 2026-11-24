package com.kennyb1201.kbstream.data.omdb

import android.app.Application
import android.util.Log
import com.kennyb1201.kbstream.data.cache.TmdbJsonCacheDao
import com.kennyb1201.kbstream.data.cache.TmdbJsonCacheEntity
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.memory.evictOldest
import com.kennyb1201.kbstream.data.network.BaseHttpClient
import com.kennyb1201.kbstream.data.runCatchingCancellable
import com.kennyb1201.kbstream.data.settings.AppPreferences
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Awards text for a title, from OMDB.
 *
 * The Detail screen shows this as its own fact next to Budget and Revenue; it
 * is display-only and owns no other state.
 *
 * Two properties matter more than the fetch itself:
 *
 *  - **It never throws.** Every failure — no key, no IMDb id, no network, a
 *    rejected key, a quota error, a title nobody has awarded — answers null.
 *    A missing awards line is not an error state, and the fact row simply does
 *    not appear.
 *  - **It is cached hard.** OMDB's free tier is 1,000 requests/day, and awards
 *    are effectively static (a title's Oscar count does not change between
 *    two openings of its detail page). A lookup is therefore memoised by IMDb
 *    id in memory for the session and on disk with the app's other JSON
 *    cache — the same `tmdb_json_cache` table TVmaze's air dates use, rather
 *    than a schema change of its own — so a second visit to a title, a
 *    re-composition, or a configuration change costs no request.
 */
class OmdbRepository internal constructor(
    private val apiProvider: () -> OmdbApiService,
    private val cacheProvider: () -> TmdbJsonCacheDao,
    private val apiKey: () -> String,
) {

    /**
     * Production entry point. Everything heavyweight stays behind a provider
     * and is built on first use (inside [awardsFor]'s IO dispatcher) rather
     * than here, because this object is constructed from a ViewModel on the
     * main thread — the same reason [com.kennyb1201.kbstream.data.tmdb.TmdbRepository]
     * keeps its Retrofit stack lazy.
     */
    @Inject constructor(app: Application) : this(
        apiProvider = { sharedApi },
        cacheProvider = { WatchHistoryDatabase.getInstance(app).tmdbJsonCacheDao() },
        apiKey = { runCatching { AppPreferences.getOmdbApiKey(app) }.getOrDefault("") },
    )

    // imdb id -> (fetchedAt, awards). A blank answer is cached too (as a null
    // value): a title OMDB has no awards for does not gain them on the next
    // screen open, and the point of the cache is to spend one request per title,
    // not one per view. Capped like every other bounded cache in the data layer
    // (data.memory.evictOldest), oldest-fetched dropped first.
    private val memoryCache = ConcurrentHashMap<String, Pair<Long, String?>>()

    /**
     * When the last request failed, or 0 (2026-11-21, rule 3).
     *
     * Awards are read per title on the Detail page, so an offline box used to
     * retry once per title per open against a quota of 1,000 a day. Five minutes
     * is longer than a screen redraw and far shorter than the 30-day TTL.
     */
    @Volatile
    private var lastFailureAtMs = 0L // rule 3: failure clock (see FAILURE_BACKOFF_MS)

    /**
     * Awards text for [imdbId], or null when unknown/unavailable. Never throws.
     *
     * Null when: no API key is saved, [imdbId] is blank, OMDB has no awards for
     * the title (`Response = "False"`, `Awards = "N/A"`), or the request failed
     * AND this device never had an answer for it. Since 2026-11-21 a failed
     * refresh serves the last known awards (rule 2) instead of dropping them.
     */
    suspend fun awardsFor(imdbId: String): String? = withContext(Dispatchers.IO) {
        val id = imdbId.trim()
        if (id.isEmpty()) return@withContext null

        val key = apiKey().trim()
        if (key.isEmpty()) return@withContext null

        val now = System.currentTimeMillis()

        // The last answer this process or the disk has, kept even when it is past
        // its TTL: rule 2 (2026-11-21) serves it when the refresh FAILS. The TTL
        // decides when to ask again, never when to forget.
        var lastKnown: String? = null

        memoryCache[id]?.let { (fetchedAt, awards) ->
            lastKnown = awards
            if (now - fetchedAt < TTL_MS) return@withContext awards
        }

        val diskKey = DISK_KEY_PREFIX + id
        runCatchingCancellable { cacheProvider().getByKey(diskKey) }.getOrNull()?.let { row ->
            val awards = row.json.takeIf { it.isNotBlank() }
            if (lastKnown == null) lastKnown = awards
            if (now - row.updatedAt < TTL_MS) {
                memoryCache[id] = row.updatedAt to awards
                evictOldest(memoryCache, { it.first }, MAX_MEMORY_ENTRIES)
                return@withContext awards
            }
        }

        // Rule 3: a failed read backs off, so a screen that keeps asking does not
        // spend the same request against the same fault - and OMDB's free tier
        // is 1,000 requests a DAY.
        if (lastFailureAtMs > 0L && now - lastFailureAtMs < FAILURE_BACKOFF_MS) {
            return@withContext lastKnown
        }

        val outcome = runCatchingCancellable {
            omdbAwardsOrNull(apiProvider().getByImdbId(id, key))
        }
            .onFailure { error ->
                // A failure is not user-visible, so it is worth a line: it is
                // how a spent daily quota or a rejected key would otherwise be
                // invisible. The key is a query parameter, so the URL (and the
                // key with it) is deliberately never logged or included here.
                Log.i("KBStream", "omdb awards unavailable for $id: ${error.message}")
            }

        if (outcome.isFailure) {
            // A failed request is NOT "this title has no awards": caching it
            // would hide the awards for a whole TTL after one blip.
            lastFailureAtMs = now
            return@withContext lastKnown
        }

        lastFailureAtMs = 0L
        val fetched = outcome.getOrNull()

        memoryCache[id] = now to fetched
        evictOldest(memoryCache, { it.first }, MAX_MEMORY_ENTRIES)

        // Only a real answer is written to disk. The table's maintenance pass
        // ages rows out after 30 days, which is also this cache's lifetime.
        if (fetched != null) {
            runCatchingCancellable {
                cacheProvider().upsert(
                    TmdbJsonCacheEntity(key = diskKey, json = fetched, updatedAt = now)
                )
            }
        }

        fetched
    }

    companion object {

        /** Namespaces this cache inside the shared `tmdb_json_cache` table. */
        private const val DISK_KEY_PREFIX = "omdb:awards:"

        /**
         * How long an answer may be trusted. Awards do not move, so this is
         * only how long a *missing* answer stays missing before it is asked
         * again; matching the cache table's own 30-day age cutoff keeps the
         * two from disagreeing about when a row is stale.
         */
        private const val TTL_MS = 30L * 24L * 60L * 60L * 1000L

        /** Bound so a long browsing session cannot grow the map without end. */
        private const val MAX_MEMORY_ENTRIES = 256

        /** How long a failed request keeps the next caller away (2026-11-21). */
        private const val FAILURE_BACKOFF_MS = 5L * 60L * 1000L

        // Built on first use, shared by every instance: one Retrofit stack for
        // the process instead of one per Detail ViewModel. The client is the
        // app's shared base client, which installs NO logging interceptor, so
        // the `apikey` query parameter is never printed.
        private val sharedApi: OmdbApiService by lazy {
            omdbRetrofitApi(BaseHttpClient.get())
        }
    }
}
