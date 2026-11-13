package com.kennyb1201.kbstream.data.fanart

import android.content.Context
import com.kennyb1201.kbstream.data.cache.TmdbJsonCacheDao
import com.kennyb1201.kbstream.data.cache.TmdbJsonCacheEntity
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.settings.AppPreferences
import com.kennyb1201.kbstream.data.tmdb.TmdbRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * The one place the app asks fanart.tv for anything.
 *
 * Every surface goes through here - the season posters, the clearlogo fallback,
 * the backdrop fallback - so one title costs at most one call however many
 * screens want its art, and the cache rules live in exactly one file (see
 * [FanartRules] for the keying and the TTLs, and `EntityLogoCache` for the shape
 * this copies).
 *
 * ## The contract, which is the whole spec
 *
 * - **Lazy.** Nothing calls this ahead of a surface that is about to draw the
 *   art: no prefetch sweep, no rail-wide walk. A card that needs a logo asks for
 *   a logo; a season header that needs a poster asks for that season's poster.
 * - **TMDB first, always.** Callers reach here only after TMDB answered nothing.
 *   This object never decides to override TMDB art; it answers "what does fanart
 *   have", and the caller has already established that it needs it.
 * - **Fail soft.** No key, offline, a 429, a body that is not art: the answer is
 *   `null`, which every caller already treats as "draw whatever you drew before".
 *   Nothing here throws to a screen and nothing retries in a loop.
 * - **Cached.** Found art: 12h memory / 30 days disk. A proven miss: 24h. A
 *   failure: 1h, and it can never be mistaken for a miss. A title never resolved
 *   still costs one call - that is the price of a first answer.
 */
class FanartArtRepository private constructor(context: Context) {

    private val appContext = context.applicationContext

    private val dao: TmdbJsonCacheDao by lazy {
        WatchHistoryDatabase.getInstance(appContext).tmdbJsonCacheDao()
    }

    private val tmdb by lazy { TmdbRepository.getInstance(appContext) }

    /** Guards the maps; never held across the network call. */
    private val mutex = Mutex()

    /** `(fetchedAt, art)` - art itself, or the JSON row's parsed bundle. */
    private val memory = ConcurrentHashMap<String, Pair<Long, FanartArt>>()

    /** When this title was proven to have no art (24h, see [FANART_MISS_TTL_MS]). */
    private val misses = ConcurrentHashMap<String, Long>()

    /** When a call for this title failed (1h). */
    private val failures = ConcurrentHashMap<String, Long>()

    /** Concurrent lookups of one title share one request. */
    private val inFlight = HashMap<String, CompletableDeferred<FanartArt?>>()

    private val personalKey: String
        get() = AppPreferences.getFanartClientKey(appContext)

    /**
     * One title's bundle, or null.
     *
     * Null means "no art to draw" for every reason at once: no key in this build,
     * the service holds nothing, the call failed, or a previous call said one of
     * those recently. Callers treat them all the same, which is exactly what
     * fail-soft asks for.
     */
    suspend fun art(kind: FanartKind, id: Int?): FanartArt? {
        // No project key: fanart is silently off, and no request is made at all.
        if (!FanartTv.isConfigured) return null
        val resolvedId = id?.takeIf { it > 0 } ?: return null
        val key = fanartCacheKey(kind, resolvedId)
        val now = System.currentTimeMillis()

        var shared: CompletableDeferred<FanartArt?>? = null
        var own: CompletableDeferred<FanartArt?>? = null

        mutex.withLock {
            memory[key]?.let { (fetchedAt, cached) ->
                if (now - fetchedAt < FANART_MEMORY_TTL_MS) return cached
                memory.remove(key)
            }

            misses[key]?.let { missAt ->
                if (now - missAt < FANART_MISS_TTL_MS) return null
                misses.remove(key)
            }

            val disk = runCatching { dao.getByKey(key) }.getOrNull()
            if (disk != null) {
                val parsed = fanartArtFromJson(disk.json)
                if (parsed != null && !parsed.isEmpty) {
                    if (now - disk.updatedAt < FANART_DISK_TTL_MS) {
                        memory[key] = now to parsed
                        return parsed
                    }
                } else if (disk.json == FANART_DISK_MISS_JSON) {
                    if (now - disk.updatedAt < FANART_MISS_TTL_MS) {
                        misses[key] = disk.updatedAt
                        return null
                    }
                }
            }

            inFlight[key]?.let { pending ->
                shared = pending
                return@withLock
            }

            failures[key]?.let { failedAt ->
                if (now - failedAt < FANART_FAILURE_TTL_MS) return null
                failures.remove(key)
            }

            val registered = CompletableDeferred<FanartArt?>()
            inFlight[key] = registered
            own = registered
        }

        shared?.let { return it.await() }
        val deferred = own ?: return null

        val result = withContext(Dispatchers.IO) {
            FanartTv.fetch(kind, resolvedId, personalKey.ifBlank { null })
        }
        val answer: FanartArt? = when (result) {
            is FanartResult.Found -> {
                val at = System.currentTimeMillis()
                memory[key] = at to result.art
                failures.remove(key)
                misses.remove(key)
                runCatching {
                    dao.upsert(
                        TmdbJsonCacheEntity(
                            key = key,
                            json = fanartArtToJson(result.art),
                            updatedAt = at
                        )
                    )
                }
                result.art
            }
            FanartResult.NotFound -> {
                // A real answer, kept for a day: fanart has nothing for this
                // title, and re-asking on every screen load is the cost this row
                // exists to avoid.
                val at = System.currentTimeMillis()
                misses[key] = at
                runCatching {
                    dao.upsert(
                        TmdbJsonCacheEntity(
                            key = key,
                            json = FANART_DISK_MISS_JSON,
                            updatedAt = at
                        )
                    )
                }
                null
            }
            FanartResult.Failed -> {
                // Never recorded as an answer - only as a reason not to hammer
                // the service again for an hour.
                failures[key] = System.currentTimeMillis()
                null
            }
        }

        mutex.withLock { inFlight.remove(key) }
        deferred.complete(answer)
        return answer
    }

    /**
     * The poster for exactly [season] on a show, or null.
     *
     * fanart's `seasonposter` first, then TMDB's season poster (the same pick
     * rule: English first, then language-neutral, then most liked), then nothing.
     * It never answers with the show's own poster or another season's - a season
     * request returns season art or nothing (see [fanartSeasonPoster]).
     *
     * [tvdbId] is the fanart key for a show; [tmdbTvId] is the TMDB season
     * fallback's key. Either may be null; both null answers null.
     */
    suspend fun seasonPoster(
        tvdbId: Int?,
        tmdbTvId: Int?,
        season: Int?,
        wantFullSize: Boolean = false
    ): String? {
        val number = season?.takeIf { it >= 0 } ?: return null

        val fanart = art(FanartKind.Tv, tvdbId)
            ?.let { fanartSeasonPoster(it.seasonPosters, number) }
            ?.url
            ?.let { if (wantFullSize) it else fanartBigPreviewUrl(it) }
        if (!fanart.isNullOrBlank()) return fanart

        return tmdb.seasonPosterPath(tmdbTvId, number)
    }

    /**
     * A wordmark for a title TMDB had none for, or null.
     *
     * Movies go by TMDB id; shows go by TVDB id, because that is the id space
     * fanart's TV endpoint reads (see [resolveClearlogo]). The caller is the one
     * that established TMDB's logo is missing - this never competes with it.
     */
    suspend fun clearlogo(kind: FanartKind, id: Int?): String? =
        art(kind, id)?.let { bundle -> pickFanartImage(bundle.logos)?.url }

    /**
     * A backdrop for a title TMDB had none for, or null.
     *
     * [want4k] asks for the 3840x2160 set first (a hero that draws large), and
     * still answers a 1080p one when the 4K set is empty - a slightly softer
     * backdrop beats none.
     */
    suspend fun backdrop(kind: FanartKind, id: Int?, want4k: Boolean = false): String? {
        val bundle = art(kind, id) ?: return null
        val preferred = if (want4k) {
            pickFanartImage(bundle.backgrounds4k) ?: pickFanartImage(bundle.backgrounds)
        } else {
            pickFanartImage(bundle.backgrounds) ?: pickFanartImage(bundle.backgrounds4k)
        }
        return preferred?.url
    }

    /** Drops the session memo (memory pressure, same as the other caches). */
    fun releaseMemory() {
        memory.clear()
    }

    companion object {
        /**
         * The disk row that records a proven miss. Not JSON at all: a row with
         * this marker can only ever mean "fanart answered nothing", so it can
         * never be parsed as art and can never be confused with an empty bundle
         * written for another reason.
         */
        internal const val FANART_DISK_MISS_JSON = "{\"__fanart_miss\":true}"

        @Volatile
        private var instance: FanartArtRepository? = null

        /** Process-wide instance; context is retained as the application one. */
        fun getInstance(context: Context): FanartArtRepository =
            instance ?: synchronized(this) {
                instance ?: FanartArtRepository(context).also { instance = it }
            }
    }
}
