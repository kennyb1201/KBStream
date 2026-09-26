package com.kennyb1201.kbstream.data.airdates

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.data.cache.TmdbJsonCacheDao
import com.kennyb1201.kbstream.data.cache.TmdbJsonCacheEntity
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.network.BaseHttpClient
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Episode air dates from TVmaze, used to correct TMDB's (see
 * [AirDateCorrection] for the rule and the reason).
 *
 * Chosen as the second source because it needs no key and no account - two
 * GETs per show - and because one `shows/{id}/episodes` response covers a
 * show's entire run, so a season premiere is available without a request per
 * season. Lookup is by IMDB id, which the app already resolves for its stream
 * ids (`tmdbRepository.resolveImdbId`), so no new mapping table is needed.
 *
 * Fail-open by construction: every failure path answers an empty map, and an
 * empty map leaves the UI exactly as it is today. A user with no network, a
 * show TVmaze has never heard of, or a TVmaze outage can never make the Detail
 * screen worse than TMDB alone.
 */
class TvmazeAirDateRepository private constructor(context: Context) {

    private val moshi by lazy {
        Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
    }

    private val api: TvmazeApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(BaseHttpClient.get())
            .addConverterFactory(MoshiConverterFactory.create(moshi))
            .build()
            .create(TvmazeApiService::class.java)
    }

    private val database by lazy { WatchHistoryDatabase.getInstance(context) }
    private val cacheDao: TmdbJsonCacheDao by lazy { database.tmdbJsonCacheDao() }

    private val datesJsonAdapter: JsonAdapter<Map<String, String>> by lazy {
        moshi.adapter(
            Types.newParameterizedType(
                Map::class.java,
                String::class.java,
                String::class.java
            )
        )
    }

    // imdb id -> (fetchedAt, dates). ConcurrentHashMap: several Detail screens
    // and the Home enrichment path can ask for different shows at once.
    private val memoryCache =
        ConcurrentHashMap<String, Pair<Long, Map<String, String>>>()

    /**
     * Caps in-flight requests across every caller. The Upcoming rail fans out
     * over all of its shows at once and each show costs two calls, so without
     * this a full rail could trip the source's per-IP rate limit and turn a
     * correction into a pile of failed lookups.
     */
    private val limiter = Semaphore(permits = MAX_CONCURRENT_REQUESTS)

    /**
     * Every episode air date this source knows for [imdbId], keyed
     * `"season:episode"` -> `yyyy-MM-dd` (see [AirDateCorrection.episodeKey]).
     *
     * Returns an empty map when the show is unknown to the source, when the
     * network fails, or when [imdbId] isn't an IMDB id.
     */
    suspend fun episodeAirDates(imdbId: String): Map<String, String> =
        withContext(Dispatchers.IO) {
            val id = imdbId.trim()
            // Lookup is by IMDB id only; a "tmdb:1413"-style parent id has to
            // be resolved by the caller.
            if (!id.startsWith("tt", ignoreCase = true)) return@withContext emptyMap()

            val now = System.currentTimeMillis()

            memoryCache[id]?.let { (fetchedAt, dates) ->
                // A miss is cached too, but far more briefly: a show TVmaze is
                // missing today can be added tomorrow, and the negative answer
                // is only there to stop a stuck screen retrying in a loop.
                val ttl = if (dates.isEmpty()) MISS_TTL_MS else TTL_MS
                if (now - fetchedAt < ttl) return@withContext dates
            }

            val diskKey = DISK_KEY_PREFIX + id
            runCatching { cacheDao.getByKey(diskKey) }.getOrNull()?.let { row ->
                if (now - row.updatedAt < DISK_TTL_MS) {
                    runCatching { datesJsonAdapter.fromJson(row.json) }
                        .getOrNull()
                        ?.let { dates ->
                            memoryCache[id] = now to dates
                            return@withContext dates
                        }
                }
            }

            val fetched = runCatching { limiter.withPermit { fetch(id) } }
                .onFailure { error ->
                    Log.i(
                        "KBStream",
                        "tvmaze air dates unavailable for $id: ${error.message}"
                    )
                }
                .getOrDefault(emptyMap())

            memoryCache[id] = now to fetched

            // Only real data is written to disk. Persisting an empty answer for
            // a week would pin a missing show as "no dates" long after it
            // appeared.
            if (fetched.isNotEmpty()) {
                runCatching {
                    cacheDao.upsert(
                        TmdbJsonCacheEntity(
                            key = diskKey,
                            json = datesJsonAdapter.toJson(fetched),
                            updatedAt = now
                        )
                    )
                }
            }

            fetched
        }

    private suspend fun fetch(imdbId: String): Map<String, String> {
        val showId = api.lookupShow(imdbId).id ?: return emptyMap()

        val dates = HashMap<String, String>()
        for (episode in api.episodes(showId)) {
            val season = episode.season ?: continue
            val number = episode.number ?: continue
            val airDate = episode.airDate?.trim().orEmpty()
            if (airDate.isEmpty()) continue
            dates[AirDateCorrection.episodeKey(season, number)] = airDate
        }
        return dates
    }

    companion object {

        // No key: TVmaze's read API is open to anyone.
        private const val BASE_URL = "https://api.tvmaze.com/"

        private const val DISK_KEY_PREFIX = "tvmaze:eps:"

        /** In-flight request cap (each show costs two calls). */
        private const val MAX_CONCURRENT_REQUESTS = 3

        /**
         * How long a fetched run may be trusted. Air dates for episodes that
         * have already aired do not change, so this only governs how quickly a
         * NEWLY announced date shows up - hours, not days.
         */
        private const val TTL_MS = 6L * 60L * 60L * 1000L

        private const val DISK_TTL_MS = 7L * 24L * 60L * 60L * 1000L

        private const val MISS_TTL_MS = 30L * 60L * 1000L

        @Volatile
        private var instance: TvmazeAirDateRepository? = null

        fun getInstance(context: Context): TvmazeAirDateRepository =
            instance ?: synchronized(this) {
                instance ?: TvmazeAirDateRepository(context.applicationContext)
                    .also { instance = it }
            }
    }
}
