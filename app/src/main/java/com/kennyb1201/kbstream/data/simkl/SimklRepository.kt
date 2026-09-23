package com.kennyb1201.kbstream.data.simkl

import android.content.Context
import android.util.Log
import com.kennyb1201.kbstream.BuildConfig
import com.kennyb1201.kbstream.data.cache.TmdbJsonCacheDao
import com.kennyb1201.kbstream.data.cache.TmdbJsonCacheEntity
import com.kennyb1201.kbstream.data.history.WatchHistoryDao
import com.kennyb1201.kbstream.data.history.WatchHistoryDatabase
import com.kennyb1201.kbstream.data.library.LibraryItem
import com.kennyb1201.kbstream.data.library.LibrarySource
import com.kennyb1201.kbstream.data.sync.SimklAuthRules
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.kennyb1201.kbstream.data.reporting.NetworkTraceInterceptor
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory

/**
 * The profile whose Simkl token produced this repository's process-wide
 * Continue Watching snapshot.
 *
 * Simkl auth is per-PROFILE (scoped simkl_auth prefs), so the feed belongs to
 * exactly one profile. Clearing the cache on a profile switch is not enough
 * on its own: a fetch that STARTED under the profile the user just left
 * finishes afterwards and repopulates both the memory copy and the disk blob
 * (whose key is resolved at write time, i.e. under the NEW profile). The
 * incoming profile then served the previous profile's Continue Watching until
 * the process restarted - the "kids profile's cards leaked onto my rail"
 * report. Stamping the owner lets every read and publish notice that and
 * refuse the entry.
 */
@Volatile
private var cachedContinueWatchingOwner: String = ""

/** Active profile id; "" is the legacy pre-profiles scope. */
private fun activeOwner(): String =
    com.kennyb1201.kbstream.data.sync.ProfileManager
        .activeProfile.value?.id
        ?: ""

class SimklRepository(
    private val context: Context? = null
) {

    internal val clientId =
        BuildConfig.SIMKL_CLIENT_ID

    private val clientSecret =
        BuildConfig.SIMKL_CLIENT_SECRET

    private val prefs
        get() = context?.applicationContext?.let { appContext ->
            appContext.getSharedPreferences(
                com.kennyb1201.kbstream.data.sync.ProfileStorage.prefsName(
                    appContext, PREFS_NAME
                ),
                Context.MODE_PRIVATE
            )
        }

    private val moshi =
        Moshi.Builder()
            .add(
                KotlinJsonAdapterFactory()
            )
            .build()

    private val okHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(
                SimklQueryInterceptor(
                    clientId = clientId,
                    appName = SimklConfig.APP_NAME,
                    appVersion = SimklConfig.APP_VERSION
                )
            )
            // Per-service request timing for the diagnostics perf block.
            .addInterceptor(NetworkTraceInterceptor())
            .build()

    internal val api: SimklApiService =
        Retrofit.Builder()
            .baseUrl(
                SimklConfig.BASE_URL
            )
            .client(
                okHttpClient
            )
            .addConverterFactory(
                MoshiConverterFactory.create(
                    moshi
                )
            )
            .build()
            .create(
                SimklApiService::class.java
            )

    private val allShowItemsMutex =
        Mutex()

    // Written under allShowItemsMutex, also read bare for cache checks;
    // @Volatile gives cross-thread visibility of the published list.
    @Volatile
    internal var cachedAllShowItems:
        SimklAllShowsResponse? = null

    @Volatile
    internal var cachedAllShowItemsFetchedAt =
        0L

    private val tmdbJsonCacheDao:
        TmdbJsonCacheDao? =
        context
            ?.applicationContext
            ?.let {
                WatchHistoryDatabase
                    .getInstance(it)
                    .tmdbJsonCacheDao()
            }

    init {
        // One-time sweep: builds before the per-profile disk-cache keys
        // stored Simkl blobs under GLOBAL keys in this shared cache table.
        // Those rows are now unreachable (all reads/writes go through the
        // profile-scoped diskKey()) and can hold ANOTHER account's data —
        // delete them once so they don't sit stale forever.
        tmdbJsonCacheDao?.let { dao ->
            CoroutineScope(Dispatchers.IO).launch {
                runCatching {
                    dao.deleteByKeys(
                        listOf(
                            "simkl:all_show_items",
                            "simkl:continue_watching",
                            "simkl:completed_movies"
                        )
                    )
                }
            }
        }
    }

    // Scoped history DAO resolved per access so Continue Watching filtering
    // sees the ACTIVE profile's completed episodes (same per-access
    // rebinding pattern the other history consumers rely on).
    private val scopedHistoryDao: WatchHistoryDao?
        get() = context
            ?.applicationContext
            ?.let { appContext ->
                runCatching {
                    WatchHistoryDatabase
                        .getInstanceScoped(appContext)
                        .watchHistoryDao()
                }.getOrNull()
            }

    /**
     * True when the active profile's local history has a completed row for
     * the given episode of a show. Accepts both id flavors local rows use
     * (the raw imdb id and a "tmdb:<id>" parent). Failures read as false so
     * a DB problem can never blank the Continue Watching rail.
     */
    private suspend fun localCompletedEpisodeExists(
        parentIds: List<String>,
        season: Int,
        episode: Int
    ): Boolean {

        if (
            parentIds.isEmpty()
        ) {
            return false
        }

        val dao =
            scopedHistoryDao
                ?: return false

        return parentIds.any { parentId ->
            runCatching {
                dao
                    .getCompletedForParent(parentId)
                    .any { row ->
                        row.season == season &&
                            row.episode == episode
                    }
            }.getOrDefault(false)
        }
    }

    /**
     * Watchlist (Plan to Watch) items for the Library tab: movies and
     * shows currently on the account's plan-to-watch list. Two GETs,
     * one per type; failures fail soft per type so a partial result
     * still renders.
     */
    suspend fun getWatchlistItems(): List<LibraryItem> {
        if (!isConfigured() || !hasToken()) return emptyList()
        val token = requireAccessToken()

        val out = mutableListOf<LibraryItem>()

        runCatching {
            api.getWatchlistMovies(bearer(token)).let { resp ->
                if (resp.isSuccessful) {
                    resp.body()?.movies?.forEach { entry ->
                        val movie = entry.movie ?: return@forEach
                        out += LibraryItem(
                            source = LibrarySource.SIMKL_WATCHLIST,
                            mediaType = "movie",
                            title = movie.title ?: "Untitled",
                            year = movie.year,
                            posterUrl = normalizePosterUrl(movie.poster),
                            imdbId = movie.ids?.imdb?.takeIf { it.isNotBlank() },
                            tmdbId = movie.ids?.tmdb?.takeIf { it > 0 },
                            simklId = movie.ids?.simkl
                        )
                    }
                }
            }
        }

        runCatching {
            api.getWatchlistShows(bearer(token)).let { resp ->
                if (resp.isSuccessful) {
                    resp.body()?.shows?.forEach { item ->
                        val show = item.show ?: return@forEach
                        out += LibraryItem(
                            source = LibrarySource.SIMKL_WATCHLIST,
                            mediaType = "series",
                            title = show.title ?: "Untitled",
                            year = show.year,
                            posterUrl = normalizePosterUrl(show.poster),
                            imdbId = show.ids?.imdb?.takeIf { it.isNotBlank() },
                            tmdbId = show.ids?.tmdb?.takeIf { it > 0 },
                            simklId = show.ids?.simkl
                        )
                    }
                }
            }
        }

        return out
    }

    /**
     * Add to Watchlist (Plan to Watch): POST /sync/add-to-list with the
     * destination status on the request root. Title/year ride along so
     * Simkl can resolve titles that only carry a TMDB id.
     */
    suspend fun addToWatchlist(
        mediaType: String,
        imdbId: String?,
        tmdbId: Int?,
        simklId: Int?,
        title: String?,
        year: Int?
    ): Boolean {
        if (!isConfigured() || !hasToken()) return false
        val ids = SimklAddToListIds(
            imdb = imdbId?.takeIf { it.isNotBlank() },
            tmdb = tmdbId?.takeIf { it > 0 },
            simkl = simklId
        )
        if (ids.imdb == null && ids.tmdb == null && ids.simkl == null) {
            return false
        }

        val isMovie = mediaType.lowercase() == "movie"
        val entry = SimklAddToListEntry(title = title, ids = ids)
        val body = SimklAddToListRequest(
            to = "plantowatch",
            movies = if (isMovie) listOf(entry) else emptyList(),
            shows = if (!isMovie) listOf(entry) else emptyList()
        )

        return runCatching {
            api.addToWatchlist(bearer(requireAccessToken()), body).isSuccessful
        }.getOrDefault(false)
    }

    private val allShowsJsonAdapter:
        JsonAdapter<SimklAllShowsResponse> =
        moshi.adapter(
            SimklAllShowsResponse::class.java
        )

    private val continueWatchingJsonAdapter:
        JsonAdapter<List<SimklContinueWatchingItem>> =
        moshi.adapter(
            Types.newParameterizedType(
                List::class.java,
                SimklContinueWatchingItem::class.java
            )
        )

    private val completedMovieKeysJsonAdapter:
        JsonAdapter<List<String>> =
        moshi.adapter(
            Types.newParameterizedType(
                List::class.java,
                String::class.java
            )
        )

    private val completedMovieKeysMutex =
        Mutex()

    @Volatile
    internal var cachedCompletedMovieKeys:
        Set<String>? =
        null

    @Volatile
    internal var cachedCompletedMovieKeysFetchedAt =
        0L

    fun isConfigured(): Boolean {
        return clientId.isNotBlank() &&
            clientSecret.isNotBlank()
    }

    fun hasToken(): Boolean {
        // NOTE: deliberately no logging here - this getter is polled on the
        // UI/main thread several times a second, and logging on every call
        // flooded logcat so heavily it crashed logd and drowned out real
        // diagnostics (just like the trailer resolver lines here).
        return !getSavedAccessToken().isNullOrBlank()
    }

    fun getSavedAccessToken(): String? {
        return prefs?.getString(
            KEY_ACCESS_TOKEN,
            null
        )
    }

    /**
     * Instance-level half of the profile-switch reset (called via the
     * companion's [Companion.clearTransientCaches]): clears the completed-
     * shows / completed-movies snapshots, which live per-instance.
     */
    fun clearWatchedCachesForProfileSwitch() {
        cachedAllShowItems = null
        cachedAllShowItemsFetchedAt = 0L
        cachedCompletedMovieKeys = null
        cachedCompletedMovieKeysFetchedAt = 0L
    }

    fun clearAuth() {
        cachedContinueWatching =
            null

        cachedContinueWatchingFetchedAt =
            0L

        cachedAllShowItems =
            null

        cachedAllShowItemsFetchedAt =
            0L

        cachedCompletedMovieKeys =
            null

        cachedCompletedMovieKeysFetchedAt =
            0L

        CoroutineScope(
            Dispatchers.IO
        ).launch {
            runCatching {
                tmdbJsonCacheDao?.deleteByKeys(
                    listOf(
                        diskKey(ALL_SHOW_ITEMS_DISK_KEY_BASE),
                        diskKey(CONTINUE_WATCHING_DISK_KEY_BASE),
                        diskKey(COMPLETED_MOVIES_DISK_KEY_BASE)
                    )
                )
            }
        }

        prefs
            ?.edit()
            ?.remove(
                KEY_ACCESS_TOKEN
            )
            ?.remove(
                KEY_LAST_WATCHED_ACTIVITY_ALL
            )
            // Tombstone: this is a DELIBERATE disconnect, which is the only
            // thing that may clear the session on another device. Without it
            // the blank token is indistinguishable from "this device never
            // connected Simkl" and can no longer be published at all.
            ?.putBoolean(
                SimklAuthRules.SIGNED_OUT_FIELD,
                true
            )
            ?.apply()

        // Cross-device sync: propagate the sign-out. Without this the cloud
        // row keeps the old token forever and every pull (app start, Sync
        // now, profile switch) resurrects the signed-out account on this and
        // every other device — the profile could never actually sign out.
        // The tombstoned empty access_token tells applySimklAuth to clear too.
        publishSimklAuthToSync()
    }

    /**
     * Publishes this profile's Simkl session blob immediately (connect and
     * disconnect paths). This bypasses the bulk push's "nothing to publish"
     * skip on purpose: a deliberate disconnect has no token but must still
     * reach the other devices as a tombstone (see [SimklAuthRules]).
     */
    private fun publishSimklAuthToSync() {
        com.kennyb1201.kbstream.data.addon.AppContextHolder.appContext?.let { appContext ->
            com.kennyb1201.kbstream.data.sync.SupabaseSync.enqueuePrefs(
                appContext,
                com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder.KEY_SIMKL_AUTH,
                com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder.buildSimklAuth(appContext)
            )
        }
    }

    suspend fun createPinCode():
        SimklPinCodeResponse {

        require(
            clientId.isNotBlank()
        ) {
            "SIMKL_CLIENT_ID is missing"
        }

        return api.createPinCode()
    }

    suspend fun checkPin(
        userCode: String
    ): SimklTokenResponse {

        require(
            clientId.isNotBlank()
        ) {
            "SIMKL_CLIENT_ID is missing"
        }

        val response =
            api.checkPin(
                userCode
            )

        response.accessToken
            ?.takeIf {
                it.isNotBlank()
            }
            ?.let {
                saveAccessToken(
                    it
                )
            }

        return response
    }

    suspend fun getActivities(
        accessToken: String =
            requireAccessToken()
    ): SimklActivitiesResponse {

        require(
            clientId.isNotBlank()
        ) {
            "SIMKL_CLIENT_ID is missing"
        }

        return api.getActivities(
            authorization =
                bearer(
                    accessToken
                )
        )
    }

    suspend fun hasWatchedActivityChanged(
        accessToken: String =
            requireAccessToken()
    ): Boolean {

        if (
            !isConfigured() ||
            !hasToken()
        ) {
            return false
        }

        val activities =
            runCatching {
                getActivities(
                    accessToken
                )
            }
                .onFailure {
                    Log.e(
                        "SIMKL_REPO",
                        "getActivities failed: " +
                            it.message,
                        it
                    )
                }
                .getOrNull()
                ?: return false

        val latest =
            activities.all
                ?.trim()
                .orEmpty()

        if (
            latest.isBlank()
        ) {
            Log.e(
                "SIMKL_REPO",
                "activities.all was blank; " +
                    "treating as unchanged"
            )

            return false
        }

        val saved =
            getSavedWatchedActivityAll()
                .orEmpty()

        val changed =
            saved != latest

        Log.e(
            "SIMKL_REPO",
            "hasWatchedActivityChanged " +
                "changed=$changed " +
                "saved=$saved " +
                "latest=$latest"
        )

        return changed
    }

    suspend fun markWatchedActivitySynced(
        accessToken: String =
            requireAccessToken()
    ) {

        if (
            !isConfigured() ||
            !hasToken()
        ) {
            return
        }

        val activities =
            runCatching {
                getActivities(
                    accessToken
                )
            }
                .onFailure {
                    Log.e(
                        "SIMKL_REPO",
                        "markWatchedActivitySynced " +
                            "getActivities failed: " +
                            it.message,
                        it
                    )
                }
                .getOrNull()
                ?: return

        val latest =
            activities.all
                ?.trim()
                .orEmpty()

        if (
            latest.isBlank()
        ) {
            return
        }

        saveWatchedActivityAll(
            latest
        )

        Log.e(
            "SIMKL_REPO",
            "markWatchedActivitySynced " +
                "saved activity all=$latest"
        )
    }

    /**
     * Auth header for the outbound tracking calls in
     * [com.kennyb1201.kbstream.data.simkl.SimklHistoryWrites] and
     * [com.kennyb1201.kbstream.data.simkl.SimklPlaybackTracking]. Those are
     * [SimklRepository] extensions, so they cannot reach the token helpers
     * directly the way the members below can.
     */
    internal fun trackedAuthHeader(): String = bearer(requireAccessToken())

    /**
     * Access token for the read helpers in
     * [com.kennyb1201.kbstream.data.simkl.SimklReads]. Same reason as
     * [trackedAuthHeader]: those are [SimklRepository] extensions.
     */
    internal fun trackedAccessToken(): String = requireAccessToken()

    /** Auth header for an explicit token; twin of [trackedAuthHeader]. */
    internal fun trackedAuthHeaderFor(token: String): String = bearer(token)

    /*
     * Outbound scrobble: record a completed movie or episode to the
     * user's Simkl history (POST /sync/history). Called from the player
     * when playback completes; failures are logged, never thrown, so
     * playback is never blocked by tracking.
     *
     * Bodies live in SimklHistoryWrites.kt.
     */
    suspend fun pushWatchedMovie(
        imdbId: String,
        title: String? = null,
        tmdbId: Int? = null
    ): Boolean = pushWatchedMovieImpl(imdbId, title, tmdbId)

    /*
     * Outbound scrobble: record a WHOLE show as watched (POST
     * /sync/history). Sending a show with no seasons array makes Simkl
     * implicitly auto-fill every episode as watched — the documented
     * "mark whole show watched" behavior. Used by the poster long-press
     * "Mark as Watched".
     */
    suspend fun pushWatchedShow(
        showImdbId: String,
        title: String? = null,
        tmdbId: Int? = null
    ): Boolean = pushWatchedShowImpl(showImdbId, title, tmdbId)

    /*
     * Outbound "mark unwatched": POST /sync/history/remove for a whole
     * movie. Called from the poster long-press "Mark as Unwatched" menu
     * action so the title leaves the user's Simkl history when it is
     * unmarked locally.
     */
    suspend fun removeWatchedMovie(
        imdbId: String,
        title: String? = null,
        tmdbId: Int? = null
    ): Boolean = removeWatchedMovieImpl(imdbId, title, tmdbId)

    /*
     * Outbound "mark unwatched": POST /sync/history/remove for a WHOLE
     * show. Sending the show with no seasons array removes every episode of
     * it from the user's Simkl history at once, the mirror of
     * [pushWatchedShow].
     */
    suspend fun removeWatchedShow(
        showImdbId: String,
        title: String? = null,
        tmdbId: Int? = null
    ): Boolean = removeWatchedShowImpl(showImdbId, title, tmdbId)

    /**
     * Drops EVERY cached Simkl watched-state snapshot - the show library,
     * the completed-movie set and the Continue Watching feed - in memory AND
     * on disk.
     *
     * The disk half matters as much as the memory half: [getAllShowItemsCached]
     * falls straight back to the 12h disk blob the moment the memory copy is
     * cleared, so a write that only nulled the memory copy left the PRE-write
     * library answering the very next read. That is how a series the user had
     * just unmarked - with Simkl's own record already rewritten correctly -
     * kept resolving as fully watched here: the stale blob still said every
     * episode was watched, so the poster kept its completed checkmark instead
     * of turning into the eye, and Continue Watching saw nothing left to
     * resume and left the show off the rail.
     */
    internal suspend fun invalidateWatchedSnapshots() {
        invalidateShowLibraryCache()

        cachedCompletedMovieKeys = null
        cachedCompletedMovieKeysFetchedAt = 0L

        // Clears the Continue Watching feed in memory and on disk too.
        clearContinueWatchingCache()

        runCatching {
            tmdbJsonCacheDao?.deleteByKeys(
                listOf(
                    diskKey(COMPLETED_MOVIES_DISK_KEY_BASE)
                )
            )
        }
    }

    /**
     * Drops the cached show library - memory AND the 12h disk blob - so the
     * next read re-fetches it instead of answering from the snapshot taken
     * before the write.
     */
    internal suspend fun invalidateShowLibraryCache() {
        cachedAllShowItems = null
        cachedAllShowItemsFetchedAt = 0L

        runCatching {
            tmdbJsonCacheDao?.deleteByKeys(
                listOf(
                    diskKey(ALL_SHOW_ITEMS_DISK_KEY_BASE)
                )
            )
        }
    }

    /**
     * Drops the in-memory and on-disk Continue Watching snapshot so the next
     * rail refresh re-fetches from Simkl instead of serving the stale list
     * (e.g. right after a title was removed from history via
     * [removeWatchedMovie]/[removeWatchedShow]).
     */
    internal suspend fun clearContinueWatchingCache() {
        cachedContinueWatching = null
        cachedContinueWatchingFetchedAt = 0L
        runCatching {
            tmdbJsonCacheDao?.deleteByKeys(
                listOf(diskKey(CONTINUE_WATCHING_DISK_KEY_BASE))
            )
        }
    }

    /** Player completion scrobble for one episode; body in SimklHistoryWrites.kt. */
    suspend fun pushWatchedEpisode(
        showImdbId: String,
        season: Int,
        episode: Int,
        title: String? = null,
        tmdbId: Int? = null
    ): Boolean = pushWatchedEpisodeImpl(showImdbId, season, episode, title, tmdbId)

    /*
     * Outbound "mark a whole season watched": POST /sync/history with one
     * season listing every episode number, so Simkl marks exactly that
     * season instead of auto-filling every season of the show (which is
     * what sending a show with no seasons array does). Used by the
     * season-chip long-press "Mark as Watched" action.
     */
    suspend fun pushWatchedSeason(
        showImdbId: String,
        season: Int,
        episodes: List<Int>,
        title: String? = null,
        tmdbId: Int? = null
    ): Boolean = pushWatchedSeasonImpl(showImdbId, season, episodes, title, tmdbId)

    /*
     * Outbound "mark a whole season unwatched": POST /sync/history/remove
     * with one season listing every episode number, so Simkl removes
     * exactly that season's episodes from history while other seasons stay.
     * The mirror of [pushWatchedSeason].
     */
    suspend fun removeWatchedSeason(
        showImdbId: String,
        season: Int,
        episodes: List<Int>,
        title: String? = null,
        tmdbId: Int? = null
    ): Boolean = removeWatchedSeasonImpl(showImdbId, season, episodes, title, tmdbId)

    /*
     * Outbound "Remove from Continue Watching" for Simkl-backed cards:
     * deletes the paused playback session (the progress record behind the
     * Continue Watching feed) so the title stops coming back from the
     * remote feed even though watch history is stored separately. Used
     * together with the history removals above.
     *
     * Bodies live in SimklPlaybackTracking.kt, along with the shared
     * "which sessions belong to this parent" matching rule.
     */
    suspend fun deletePlaybackSession(
        playbackId: Int?
    ): Boolean = deletePlaybackSessionImpl(playbackId)

    /**
     * Deletes every open Simkl playback session whose show/movie matches the
     * given parent, regardless of which deduped card surfaced it. A paused
     * title is usually backed by BOTH a local resume row and a Simkl paused
     * session; when the local twin wins the rail dedupe the visible card
     * carries no playbackId, so deleting only the card's own session (or none
     * at all) leaves the remote session open and the title gets re-added from
     * /sync/playback on the very next feed refresh. Returns how many sessions
     * were removed (including ones already gone server-side).
     */
    suspend fun deletePlaybackSessionsForParent(
        parentId: String,
        title: String? = null
    ): Int = deletePlaybackSessionsForParentImpl(parentId, title)

    /**
     * Deletes every open Simkl playback session for a title whose watched
     * position has passed the given threshold. Called right after a title
     * (or a specific episode of a show) is marked completed so the paused
     * pre-completion session record can't keep resurfacing in Continue
     * Watching at its old progress (e.g. "99% watched"). Returns the number
     * of sessions removed.
     */
    suspend fun deleteOpenPlaybackSessionsForWatched(
        parentId: String,
        tmdbId: Int? = null,
        season: Int? = null,
        episode: Int? = null,
        episodes: List<Int>? = null,
        minProgress: Float = 95f
    ): Int = deleteOpenPlaybackSessionsForWatchedImpl(
        parentId = parentId,
        tmdbId = tmdbId,
        season = season,
        episode = episode,
        episodes = episodes,
        minProgress = minProgress
    )

    /*
     * Live scrobble (POST /scrobble/start|pause|stop). Called from the
     * player on play/pause/end so Simkl records in-progress playback and
     * extrapolates the watch between events.
     */
    suspend fun scrobble(
        action: String,
        parentId: String,
        parentType: String,
        season: Int? = null,
        episode: Int? = null,
        title: String? = null,
        progress: Double,
        tmdbId: Int? = null
    ): Boolean = scrobbleImpl(
        action = action,
        parentId = parentId,
        parentType = parentType,
        season = season,
        episode = episode,
        title = title,
        progress = progress,
        tmdbId = tmdbId
    )

    /*
     * Normalize a catalog item id into Simkl id refs. Accepts:
     *   imdb:tt1234567 / tt1234567  -> imdb
     *   tmdb:123 / 123              -> tmdb
     *   tvdb:123 (unknown flavors)  -> tmdb (when resolvedTmdbId is known)
     *   anything else               -> best-effort imdb passthrough
     * (movie/show refs may carry both imdb + tmdb; Simkl resolves
     * whichever identifier it can match server-side. A server-resolved
     * TMDB id wins over anything derived from the raw string, which is what
     * makes TVDB-sourced titles scrobble correctly.)
     */
    internal fun parsePlaybackIds(
        rawId: String,
        resolvedTmdbId: Int? = null
    ): SimklPlaybackIdsRef? {

        val trimmed =
            rawId.trim()

        if (
            trimmed.isBlank()
        ) {
            return null
        }

        val lower =
            trimmed.lowercase()

        var imdb: String? = null
        var tmdb: Int? = resolvedTmdbId

        when {
            lower.startsWith("imdb:") ->
                imdb =
                    trimmed
                        .substringAfter(":")
                        .trim()
                        .takeIf { it.isNotBlank() }

            lower.startsWith("tt") ->
                imdb = trimmed.substringBefore(':')

            lower.startsWith("tmdb:") ->
                tmdb =
                    tmdb
                        ?: trimmed
                            .substringAfter(":")
                            .trim()
                            .toIntOrNull()

            trimmed.all(Char::isDigit) ->
                tmdb =
                    tmdb
                        ?: trimmed.toIntOrNull()

            else ->
                // Best-effort passthrough for unknown flavors; the resolved
                // tmdb above (when present) is the reliable identifier.
                imdb = trimmed
        }

        if (
            imdb == null &&
            tmdb == null
        ) {
            return null
        }

        return SimklPlaybackIdsRef(
            imdb = imdb,
            tmdb = tmdb
        )
    }

    fun forceClearWatchedActivitySync() {
        prefs
            ?.edit()
            ?.remove(
                KEY_LAST_WATCHED_ACTIVITY_ALL
            )
            ?.apply()
    }

    fun getSavedWatchedActivityAll(): String? {
        return prefs?.getString(
            KEY_LAST_WATCHED_ACTIVITY_ALL,
            null
        )
    }

    // Bodies live in SimklReads.kt.
    suspend fun getPlaybackItems(
        accessToken: String =
            trackedAccessToken()
    ): List<SimklPlaybackItem> = getPlaybackItemsImpl(accessToken)

    suspend fun getWatchingShows(
        accessToken: String =
            trackedAccessToken()
    ): SimklWatchingShowsResponse = getWatchingShowsImpl(accessToken)

    /**
     * Library totals for the connect screen: how many distinct shows and
     * movies the Simkl account has any watch history for. Shows come from
     * the cached all-shows library (the same source Continue Watching
     * resolves against) and movies from the all-items movies endpoint.
     * Returns null on failure so the UI can simply hide the counters.
     */
    suspend fun getWatchedCounts(): SimklWatchedCounts? = getWatchedCountsImpl()

    /**
     * The connected account's identity for the connect screen ("Signed in
     * as …"). Best-effort: null on any failure so the UI simply omits the
     * line. No caching — the call is cheap and correctness here matters
     * more than latency (this is how the user verifies WHICH profile's
     * Simkl they are looking at).
     */
    suspend fun getAccountInfo(): SimklUser? = getAccountInfoImpl()

    suspend fun getWatchedBulkImport(
        movieImdbIds: List<String> =
            emptyList(),

        movieSimklIds: List<Int> =
            emptyList(),

        showImdbIds: List<String> =
            emptyList(),

        showSimklIds: List<Int> =
            emptyList(),

        accessToken: String =
            requireAccessToken()
    ): SimklWatchedImport {

        require(
            clientId.isNotBlank()
        ) {
            "SIMKL_CLIENT_ID is missing"
        }

        val movieRefs =
            buildIdRefs(
                imdbIds =
                    movieImdbIds,

                simklIds =
                    movieSimklIds
            )

        val showRefs =
            buildIdRefs(
                imdbIds =
                    showImdbIds,

                simklIds =
                    showSimklIds
            )

        if (
            movieRefs.isEmpty() &&
            showRefs.isEmpty()
        ) {
            return SimklWatchedImport(
                watchedMovieImdbIds =
                    emptySet(),

                watchedMovieSimklIds =
                    emptySet(),

                watchedShowImdbIds =
                    emptySet(),

                watchedShowSimklIds =
                    emptySet(),

                watchedEpisodesByShowKey =
                    emptyMap()
            )
        }

        val requestBody =
            SimklWatchedBulkRequest(
                movies =
                    movieRefs.map {
                        SimklWatchedLookupMovie(
                            ids = it
                        )
                    },

                shows =
                    showRefs.map {
                        SimklWatchedLookupShow(
                            ids = it
                        )
                    }
            )

        val rawResponse =
            api.getWatchedBulkRaw(
                authorization =
                    bearer(
                        accessToken
                    ),

                body =
                    requestBody
            )

        val rawText =
            try {
                rawResponse
                    .body()
                    ?.string()
            } catch (e: Exception) {
                "unreadable raw body: " +
                    e.message
            }

        Log.e(
            "SIMKL_REPO",
            "getWatchedBulk RAW " +
                "code=${rawResponse.code()} " +
                "message=${rawResponse.message()} " +
                "contentType=" +
                "${rawResponse.headers()["Content-Type"]} " +
                "body=$rawText"
        )

        return SimklWatchedImport(
            watchedMovieImdbIds =
                emptySet(),

            watchedMovieSimklIds =
                emptySet(),

            watchedShowImdbIds =
                emptySet(),

            watchedShowSimklIds =
                emptySet(),

            watchedEpisodesByShowKey =
                emptyMap()
        )
    }

    internal suspend fun getAllShowItemsCached(
        accessToken: String,
        forceRefresh: Boolean =
            false
    ): SimklAllShowsResponse? {

        val now =
            System.currentTimeMillis()

        /*
         * Capture the profile this fetch belongs to (the token was resolved
         * from ITS scoped auth prefs). A mid-flight switch must not publish
         * the result: the memory snapshot would serve the OLD account's
         * shows to the NEW profile, and diskKey() below resolves the ACTIVE
         * profile at write time — writing the old account's blob under the
         * new profile's cache key for 12 hours (the phantom watched-marker
         * leak). The caller's own post-switch preload re-fetches cleanly.
         */
        val profileAtStart =
            com.kennyb1201.kbstream.data.sync.ProfileManager.activeProfile.value?.id

        fun profileChanged(): Boolean =
            com.kennyb1201.kbstream.data.sync.ProfileManager.activeProfile.value?.id !=
                profileAtStart

        return allShowItemsMutex.withLock {

            val cached =
                cachedAllShowItems

            if (
                !forceRefresh &&
                cached != null &&
                now - cachedAllShowItemsFetchedAt <
                    ALL_SHOW_ITEMS_TTL_MS
            ) {
                return@withLock cached
            }

            // Disk fallback so a cold start doesn't re-download the entire
            // library (hundreds of shows with full episode data) before the
            // Continue Watching rail can resolve anything.
            if (
                !forceRefresh &&
                cached == null
            ) {
                val diskCached =
                    readSimklJsonFromDisk(
                        diskKey(ALL_SHOW_ITEMS_DISK_KEY_BASE)
                    )

                if (
                    diskCached != null &&
                    now - diskCached.updatedAt <
                        ALL_SHOW_ITEMS_DISK_TTL_MS
                ) {
                    val parsed =
                        runCatching {
                            allShowsJsonAdapter
                                .fromJson(
                                    diskCached.json
                                )
                        }
                            .getOrNull()

                    if (parsed != null) {
                        cachedAllShowItems =
                            parsed

                        cachedAllShowItemsFetchedAt =
                            now

                        return@withLock parsed
                    }
                }
            }

            val response =
                try {
                    api.getAllShowItems(
                        authorization =
                            bearer(
                                accessToken
                            ),

                        extended =
                            "full",

                        includeAllEpisodes =
                            "original",

                        episodeWatchedAt =
                            "yes"
                    )
                } catch (e: Exception) {
                    Log.e(
                        "SIMKL_REPO",
                        "getAllShowItems failed: " +
                            e.message,
                        e
                    )

                    return@withLock cached
                }

            if (
                !response.isSuccessful
            ) {
                val errorText =
                    try {
                        response
                            .errorBody()
                            ?.string()
                    } catch (e: Exception) {
                        "unreadable: " +
                            e.message
                    }

                Log.e(
                    "SIMKL_REPO",
                    "getAllShowItems failed " +
                        "code=${response.code()} " +
                        "body=$errorText"
                )

                return@withLock cached
            }

            val body =
                response.body()

            if (
                body != null
            ) {
                if (profileChanged()) {
                    Log.w(
                        "SIMKL_REPO",
                        "all-show response dropped: profile switched mid-fetch"
                    )
                    return@withLock cached
                }

                cachedAllShowItems =
                    body

                cachedAllShowItemsFetchedAt =
                    now

                runCatching {
                    tmdbJsonCacheDao?.upsert(
                        TmdbJsonCacheEntity(
                            key =
                                diskKey(ALL_SHOW_ITEMS_DISK_KEY_BASE),

                            json =
                                allShowsJsonAdapter
                                    .toJson(
                                        body
                                    ),

                            updatedAt =
                                now
                        )
                    )
                }

                Log.e(
                    "SIMKL_REPO",
                    "all-show cache refreshed: " +
                        "shows=${body.shows.size}"
                )
            }

            body ?: cached
        }
    }

    private suspend fun readSimklJsonFromDisk(
        key: String
    ): TmdbJsonCacheEntity? {
        return runCatching {
            tmdbJsonCacheDao?.getByKey(
                key
            )
        }
            .getOrNull()
    }

    suspend fun getCompletedMovieImdbIds(
        accessToken: String =
            requireAccessToken()
    ): Set<String> {

        val httpResponse =
            api.getCompletedMovies(
                authorization =
                    bearer(
                        accessToken
                    ),

                dateFrom =
                    null,

                extended =
                    "full"
            )

        Log.e(
            "SIMKL_REPO",
            "getCompletedMovies raw: " +
                "code=${httpResponse.code()}, " +
                "message=${httpResponse.message()}"
        )

        if (
            !httpResponse.isSuccessful
        ) {
            val errorText =
                try {
                    httpResponse
                        .errorBody()
                        ?.string()
                } catch (e: Exception) {
                    "unreadable: " +
                        e.message
                }

            Log.e(
                "SIMKL_REPO",
                "getCompletedMovies failed body: " +
                    errorText
            )

            return emptySet()
        }

        val body =
            httpResponse.body()

        if (
            body == null
        ) {
            Log.e(
                "SIMKL_REPO",
                "getCompletedMovies succeeded " +
                    "but body was null"
            )

            return emptySet()
        }

        Log.e(
            "SIMKL_REPO",
            "getCompletedMovies parsed ok: " +
                "movies=${body.movies.size}"
        )

        return body.movies
            .mapNotNull {
                it.movie
                    ?.ids
                    ?.imdb
                    ?.takeIf { id ->
                        id.isNotBlank()
                    }
            }
            .toSet()
    }

    suspend fun isShowWatchedByImdb(
        imdbId: String,
        tmdbId: Int? =
            null,

        accessToken: String =
            requireAccessToken()
    ): Boolean {

        if (
            imdbId.isBlank()
        ) {
            return false
        }

        val body =
            getAllShowItemsCached(
                accessToken =
                    accessToken
            )
                ?: return false

        val match =
            body.shows.firstOrNull { item ->
                item.show
                    ?.ids
                    ?.imdb == imdbId ||
                    (
                        tmdbId != null &&
                        item.show
                            ?.ids
                            ?.tmdb == tmdbId
                        )
            }
                ?: return false

        return isShowFullyWatched(
            match
        )
    }

    suspend fun getWatchedEpisodesForShowByImdb(
        imdbId: String,
        tmdbId: Int? =
            null,

        accessToken: String =
            requireAccessToken()
    ): Set<Pair<Int, Int>> {

        if (
            imdbId.isBlank()
        ) {
            return emptySet()
        }

        val body =
            getAllShowItemsCached(
                accessToken =
                    accessToken
            )
                ?: return emptySet()

        val match =
            body.shows.firstOrNull { item ->
                item.show
                    ?.ids
                    ?.imdb == imdbId ||
                    (
                        tmdbId != null &&
                        item.show
                            ?.ids
                            ?.tmdb == tmdbId
                        )
            }
                ?: return emptySet()

        return match.seasons
            ?.flatMap { season ->

                val seasonNumber =
                    season.number

                season.episodes.mapNotNull { episode ->

                    val episodeNumber =
                        episode.number
                            ?: episode.episode

                    if (
                        seasonNumber != null &&
                        episodeNumber != null &&
                        !episode.watchedAt
                            .isNullOrBlank()
                    ) {
                        seasonNumber to
                            episodeNumber
                    } else {
                        null
                    }
                }
            }
            ?.toSet()
            .orEmpty()
    }

    suspend fun getCompletedShowImdbIds(
        accessToken: String =
            requireAccessToken()
    ): Set<String> {

        val body =
            getAllShowItemsCached(
                accessToken =
                    accessToken
            )
                ?: return emptySet()

        return body.shows
            .asSequence()
            .filter { item ->
                isShowFullyWatched(
                    item
                )
            }
            .mapNotNull { item ->
                item.show
                    ?.ids
                    ?.imdb
                    ?.takeIf {
                        it.isNotBlank()
                    }
            }
            .toSet()
    }

    suspend fun refreshAllShowItems(
        accessToken: String =
            requireAccessToken()
    ) {
        getAllShowItemsCached(
            accessToken = accessToken,

            forceRefresh = true
        )
    }

    /**
     * IMDB ids of shows the user has STARTED on Simkl but not finished:
     * watchedEpisodesCount > 0 while [isShowFullyWatched] is false. Reads
     * the same cached all-shows response as [getCompletedShowImdbIds], so
     * this costs no extra network round-trip — the eye badge (partially
     * watched) and the checkmark (fully watched) resolve together.
     */
    suspend fun getPartiallyWatchedShowImdbIds(
        accessToken: String =
            requireAccessToken()
    ): Set<String> {

        val body =
            getAllShowItemsCached(
                accessToken = accessToken
            )
                ?: return emptySet()

        return body.shows
            .asSequence()
            .filter { item ->
                !isShowFullyWatched(item) &&
                    (item.watchedEpisodesCount ?: 0) > 0
            }
            .mapNotNull { item ->
                item.show
                    ?.ids
                    ?.imdb
                    ?.takeIf {
                        it.isNotBlank()
                    }
            }
            .toSet()
    }

    /**
     * TMDB twin of [getCompletedShowImdbIds]: "tmdb:<n>" keys for finished
     * shows.
     *
     * Half the rails carry TMDB ids instead of IMDb ones — every
     * TMDB-discover rail, and therefore all of the hardcoded kids rails — and
     * an imdb-only set can never match them, so Simkl knew the show while the
     * poster stayed bare. Both sets come from the SAME cached all-shows
     * response, so carrying both costs no extra request.
     */
    suspend fun getCompletedShowTmdbKeys(
        accessToken: String =
            requireAccessToken()
    ): Set<String> {

        val body =
            getAllShowItemsCached(
                accessToken =
                    accessToken
            )
                ?: return emptySet()

        return body.shows
            .asSequence()
            .filter { item ->
                isShowFullyWatched(
                    item
                )
            }
            .mapNotNull { item ->
                item.show
                    ?.ids
                    ?.tmdb
                    ?.takeIf {
                        it > 0
                    }
                    ?.let {
                        "tmdb:$it"
                    }
            }
            .toSet()
    }

    /** TMDB twin of [getPartiallyWatchedShowImdbIds]: started-not-finished shows. */
    suspend fun getPartiallyWatchedShowTmdbKeys(
        accessToken: String =
            requireAccessToken()
    ): Set<String> {

        val body =
            getAllShowItemsCached(
                accessToken =
                    accessToken
            )
                ?: return emptySet()

        return body.shows
            .asSequence()
            .filter { item ->
                !isShowFullyWatched(
                    item
                ) &&
                    (item.watchedEpisodesCount ?: 0) > 0
            }
            .mapNotNull { item ->
                item.show
                    ?.ids
                    ?.tmdb
                    ?.takeIf {
                        it > 0
                    }
                    ?.let {
                        "tmdb:$it"
                    }
            }
            .toSet()
    }

    suspend fun getCompletedMovieKeys(
        accessToken: String =
            requireAccessToken()
    ): Set<String> {

        val now =
            System.currentTimeMillis()

        /*
         * Same mid-flight-switch guard as getAllShowItemsCached: this fetch
         * belongs to the profile whose auth produced [accessToken]. Its
         * result must not be published (memory) or persisted (diskKey()
         * resolves the ACTIVE profile at write time) once that profile is
         * no longer active — otherwise the OLD account's completed movies
         * become the NEW profile's badge set for up to 12 hours.
         */
        val profileAtStart =
            com.kennyb1201.kbstream.data.sync.ProfileManager.activeProfile.value?.id

        fun profileChanged(): Boolean =
            com.kennyb1201.kbstream.data.sync.ProfileManager.activeProfile.value?.id !=
                profileAtStart

        return completedMovieKeysMutex.withLock {

            val cached =
                cachedCompletedMovieKeys

            if (
                cached != null &&
                now - cachedCompletedMovieKeysFetchedAt <
                    COMPLETED_MOVIES_TTL_MS
            ) {
                return@withLock cached
            }

            // Disk fallback so a cold start doesn't re-fetch the completed
            // movie lists before the watched markers can resolve.
            if (
                cached == null
            ) {
                val diskCached =
                    readSimklJsonFromDisk(
                        diskKey(COMPLETED_MOVIES_DISK_KEY_BASE)
                    )

                if (
                    diskCached != null &&
                    now - diskCached.updatedAt <
                        COMPLETED_MOVIES_DISK_TTL_MS
                ) {
                    val parsed =
                        runCatching {
                            completedMovieKeysJsonAdapter
                                .fromJson(
                                    diskCached.json
                                )
                                ?.toSet()
                        }
                            .getOrNull()

                    if (parsed != null) {
                        cachedCompletedMovieKeys =
                            parsed

                        cachedCompletedMovieKeysFetchedAt =
                            now

                        return@withLock parsed
                    }
                }
            }

            val completedResponse =
            api.getCompletedMovies(
                authorization =
                    bearer(
                        accessToken
                    ),

                dateFrom =
                    null,

                extended =
                    "full"
            )

        val allItemsResponse =
            api.getAllMovieItems(
                authorization =
                    bearer(
                        accessToken
                    ),

                dateFrom =
                    null,

                extended =
                    "full"
            )

        fun extractKeys(
            body: SimklCompletedMoviesResponse?
        ): Set<String> {

            if (
                body == null
            ) {
                return emptySet()
            }

            return body.movies
                .flatMap { item ->

                    val ids =
                        item.movie
                            ?.ids

                    buildList {

                        ids
                            ?.imdb
                            ?.takeIf {
                                it.isNotBlank()
                            }
                            ?.let {
                                add(
                                    "imdb:$it"
                                )
                            }

                        ids
                            ?.tmdb
                            ?.let {
                                add(
                                    "tmdb:$it"
                                )
                            }

                        ids
                            ?.simkl
                            ?.let {
                                add(
                                    "simkl:$it"
                                )
                            }
                    }
                }
                .toSet()
        }

        val completedKeys =
            if (
                completedResponse.isSuccessful
            ) {
                extractKeys(
                    completedResponse.body()
                )
            } else {
                emptySet()
            }

        val allMovieKeys =
            if (
                allItemsResponse.isSuccessful
            ) {
                extractKeys(
                    allItemsResponse.body()
                )
            } else {
                emptySet()
            }

            val merged =
                completedKeys +
                    allMovieKeys

            // Discard when the profile switched mid-fetch: publishing here
            // would write the OLD account's keys under the NEW profile's
            // disk key (diskKey() resolves the ACTIVE profile at write
            // time), poisoning its watched badges for 12 hours.
            if (profileChanged()) {
                Log.w(
                    "SIMKL_REPO",
                    "completed-movies response dropped: profile switched mid-fetch"
                )
                return@withLock cached ?: emptySet()
            }

            cachedCompletedMovieKeys =
                merged

            cachedCompletedMovieKeysFetchedAt =
                now

            runCatching {
                tmdbJsonCacheDao?.upsert(
                    TmdbJsonCacheEntity(
                        key =
                            diskKey(COMPLETED_MOVIES_DISK_KEY_BASE),

                        json =
                            completedMovieKeysJsonAdapter
                                .toJson(
                                    merged.toList()
                                ),

                        updatedAt =
                            now
                    )
                )
            }

            merged
        }
    }

    suspend fun getContinueWatching(
        accessToken: String =
            requireAccessToken(),

        forceRefresh: Boolean =
            false
    ): List<SimklContinueWatchingItem> {

        // Captured up front: everything published below has to still belong
        // to this profile when it lands (see [cachedContinueWatchingOwner]).
        val ownerAtStart =
            activeOwner()

        if (
            !forceRefresh &&
            cachedContinueWatching != null &&
            cachedContinueWatchingOwner == ownerAtStart &&
            System.currentTimeMillis() -
                cachedContinueWatchingFetchedAt <
                CONTINUE_WATCHING_TTL_MS
        ) {
            return cachedContinueWatching.orEmpty()
        }

        // Disk fallback so the rail renders immediately on a cold start
        // instead of waiting for playback + watching-shows network calls.
        if (
            !forceRefresh &&
            cachedContinueWatching == null
        ) {
            val diskCached =
                readSimklJsonFromDisk(
                    diskKey(CONTINUE_WATCHING_DISK_KEY_BASE)
                )

            if (
                diskCached != null &&
                System.currentTimeMillis() -
                    diskCached.updatedAt <
                    CONTINUE_WATCHING_DISK_TTL_MS
            ) {
                val parsed =
                    runCatching {
                        continueWatchingJsonAdapter
                            .fromJson(
                                diskCached.json
                            )
                    }
                        .getOrNull()

                if (parsed != null) {
                    cachedContinueWatching =
                        parsed

                    cachedContinueWatchingFetchedAt =
                        System.currentTimeMillis()

                    cachedContinueWatchingOwner =
                        ownerAtStart

                    return parsed
                }
            }
        }

        try {
            val playbackItems =
                getPlaybackItems(
                    accessToken
                )

            val watchingShows =
                getWatchingShows(
                    accessToken
                ).shows

            val watchingBySimklId:
                Map<String, SimklWatchingShowItem> =
                watchingShows
                    .mapNotNull { watchedShow ->
                        watchedShow.show
                            ?.ids
                            ?.simkl
                            ?.toString()
                            ?.let {
                                it to watchedShow
                            }
                    }
                    .toMap()

            fun isTrulyCompleted(
                simklId: String
            ): Boolean {

                val watchingEntry =
                    watchingBySimklId[
                        simklId
                    ]
                        ?: return false

                /*
                 * Continue Watching only cares that there is nothing AIRED
                 * left to resume, so a caught-up show still drops its stale
                 * 95%-progress session here. The badge rule is stricter
                 * (isShowFullyWatched) and no longer counts a caught-up
                 * show as finished.
                 */
                return isCaughtUpOnAiredEpisodes(
                    watchingEntry
                )
            }

            val playbackMapped =
                playbackItems.mapNotNull { item ->

                    when {

                        item.movie != null -> {

                            val movie =
                                item.movie

                            val simklId =
                                movie.ids
                                    ?.simkl
                                    ?.toString()
                                    ?: item.id
                                        ?.toString()
                                    ?: return@mapNotNull null

                            val imdbId =
                                movie.ids
                                    ?.imdb
                                    ?.takeIf {
                                        it.isNotBlank()
                                    }

                            // A paused session for a movie this profile has
                            // already completed locally is a leftover record
                            // (e.g. finished on another device). Drop it and
                            // delete it server-side so the movie doesn't
                            // linger in Continue Watching at its old
                            // progress.
                            val movieParentIds =
                                listOfNotNull(
                                    imdbId,
                                    movie.ids?.tmdb?.let { "tmdb:$it" }
                                )

                            val movieLocallyCompleted =
                                movieParentIds.any { parentId ->
                                    runCatching {
                                        scopedHistoryDao
                                            ?.getCompletedForParent(parentId)
                                            ?.isNotEmpty() == true
                                    }.getOrDefault(false)
                                }

                            if (movieLocallyCompleted) {
                                Log.d(
                                    "SIMKL_REPO",
                                    "continueWatching: dropping stale movie " +
                                        "session movie=$simklId (completed locally)"
                                )

                                deletePlaybackSession(item.id)

                                return@mapNotNull null
                            }

                            SimklContinueWatchingItem(
                                id =
                                    "movie-$simklId",

                                playbackId =
                                    item.id,

                                imdbId =
                                    imdbId,

                                tmdbId =
                                    movie.ids
                                        ?.tmdb,

                                simklId =
                                    movie.ids
                                        ?.simkl
                                        ?: simklId
                                            .toIntOrNull(),

                                title =
                                    movie.title
                                        ?: "Untitled movie",

                                year =
                                    movie.year,

                                posterUrl =
                                    normalizePosterUrl(
                                        movie.poster
                                    ),

                                lastWatchedAt =
                                    item.pausedAt,

                                progress =
                                    item.progress,

                                upNextText =
                                    "Resume movie",

                                mediaType =
                                    "movie",

                                source =
                                    "playback",

                                season =
                                    null,

                                episode =
                                    null
                            )
                        }

                        item.show != null -> {

                            val show =
                                item.show

                            val simklId =
                                show.ids
                                    ?.simkl
                                    ?.toString()
                                    ?: item.id
                                        ?.toString()
                                    ?: return@mapNotNull null

                            if (
                                isTrulyCompleted(
                                    simklId
                                ) &&
                                (
                                    item.progress
                                        ?: 0f
                                    ) >= 95f
                            ) {
                                return@mapNotNull null
                            }

                            val imdbId =
                                show.ids
                                    ?.imdb
                                    ?.takeIf {
                                        it.isNotBlank()
                                    }

                            // Episode-level staleness: a paused playback
                            // session for an episode that is ALREADY watched
                            // — either marked watched on Simkl (the
                            // completion synced a moment after the scrobble
                            // stop, or the episode was finished on another
                            // device) or completed locally on this profile —
                            // is a leftover session record, not a resume
                            // point. Drop it — and delete it server-side —
                            // so Continue Watching doesn't show the episode
                            // stuck at its pre-completion progress (e.g.
                            // "99% watched") forever.
                            val sessionSeason =
                                item.episode
                                    ?.season

                            val sessionEpisode =
                                item.episode
                                    ?.episode

                            if (
                                sessionSeason != null &&
                                sessionEpisode != null
                            ) {
                                val watchedEpisodes =
                                    runCatching {
                                        getWatchedEpisodesForShowByImdb(
                                            imdbId = imdbId.orEmpty(),
                                            tmdbId = show.ids?.tmdb
                                        )
                                    }
                                        .getOrDefault(
                                        emptySet()
                                    )

                            val locallyCompleted =
                                localCompletedEpisodeExists(
                                    parentIds = listOfNotNull(
                                        imdbId,
                                        show.ids?.tmdb?.let { "tmdb:$it" }
                                    ),
                                    season = sessionSeason,
                                    episode = sessionEpisode
                                )

                            if (
                                (sessionSeason to sessionEpisode) in watchedEpisodes ||
                                locallyCompleted
                            ) {
                                Log.d(
                                    "SIMKL_REPO",
                                    "continueWatching: dropping stale session " +
                                        "show=$simklId s=$sessionSeason e=$sessionEpisode " +
                                        "(episode already watched: " +
                                        if (locallyCompleted) "local)" else "simkl)"
                                )

                                deletePlaybackSession(item.id)

                                return@mapNotNull null
                            }
                        }

                        SimklContinueWatchingItem(
                            id =
                                "show-$simklId",

                                playbackId =
                                    item.id,

                                imdbId =
                                    imdbId,

                                tmdbId =
                                    show.ids
                                        ?.tmdb,

                                simklId =
                                    show.ids
                                        ?.simkl
                                        ?: simklId
                                            .toIntOrNull(),

                                title =
                                    show.title
                                        ?: "Untitled show",

                                year =
                                    show.year,

                                posterUrl =
                                    normalizePosterUrl(
                                        show.poster
                                    ),

                                lastWatchedAt =
                                    item.pausedAt,

                                progress =
                                    item.progress,

                                upNextText =
                                    buildPlaybackUpNextText(
                                        item.episode
                                    ),

                                mediaType =
                                    "series",

                                source =
                                    "playback",

                                season =
                                    item.episode
                                        ?.season,

                                episode =
                                    item.episode
                                        ?.episode
                            )
                        }

                        else ->
                            null
                    }
                }

            val playbackIds =
                playbackMapped
                    .map {
                        it.id
                    }
                    .toSet()

            val watchingMapped =
                watchingShows
                    .asSequence()
                    .filter { item ->

                        // One shared rule: dropped shows never appear, a show
                        // the user still has aired episodes to watch does -
                        // even when Simkl's list status still says
                        // "completed" (whole-show mark, then a season
                        // unmarked), and a caught-up show does not.
                        isContinueWatchingCandidate(
                            item
                        )
                    }
                    .mapNotNull { item ->

                        val show =
                            item.show
                                ?: return@mapNotNull null

                        val simklId =
                            show.ids
                                ?.simkl
                                ?.toString()
                                ?: return@mapNotNull null

                        val mergedId =
                            "show-$simklId"

                        if (
                            mergedId in playbackIds
                        ) {
                            return@mapNotNull null
                        }

                        val imdbId =
                            show.ids
                                ?.imdb
                                ?.takeIf {
                                    it.isNotBlank()
                                }

                        val parsedNext =
                            parseNextTarget(
                                item.nextToWatch
                            )
                                ?: parseNextTarget(
                                    item.lastWatched
                                )

                        val nextSeason =
                            parsedNext?.first

                        val nextEpisode =
                            parsedNext?.second

                        SimklContinueWatchingItem(
                            id =
                                mergedId,

                            imdbId =
                                imdbId,

                            tmdbId =
                                show.ids
                                    ?.tmdb,

                            simklId =
                                show.ids
                                    ?.simkl,

                            title =
                                show.title
                                    ?: "Untitled show",

                            year =
                                show.year,

                            posterUrl =
                                normalizePosterUrl(
                                    show.poster
                                ),

                            lastWatchedAt =
                                item.lastWatchedAt
                                    ?: item.addedToWatchlistAt,

                            progress =
                                null,

                            upNextText =
                                buildWatchingUpNextText(
                                    nextToWatch =
                                        item.nextToWatch,

                                    lastWatched =
                                        item.lastWatched,

                                    status =
                                        item.status
                                ),

                            mediaType =
                                "series",

                            source =
                                "watching",

                            season =
                                nextSeason,

                            episode =
                                nextEpisode
                        )
                    }
                    .toList()

            val result =
                (
                    playbackMapped +
                        watchingMapped
                    )
                    .sortedWith(
                        compareByDescending<
                            SimklContinueWatchingItem
                            > {
                                scoreContinueWatchingItem(
                                    it
                                )
                            }
                            .thenByDescending {
                                sortableTimestamp(
                                    it.lastWatchedAt
                                )
                            }
                            .thenBy {
                                it.title
                                    .lowercase()
                            }
                    )

            // A switch mid-fetch cleared the cache above; publishing now
            // would stamp THIS profile's feed under the NEW profile's memory
            // slot and disk key. Return the result to the (already stale)
            // caller without caching it.
            if (activeOwner() != ownerAtStart) {
                return result
            }

            cachedContinueWatching =
                result

            cachedContinueWatchingFetchedAt =
                System.currentTimeMillis()

            cachedContinueWatchingOwner =
                ownerAtStart

            runCatching {
                tmdbJsonCacheDao?.upsert(
                    TmdbJsonCacheEntity(
                        key =
                            diskKey(CONTINUE_WATCHING_DISK_KEY_BASE),

                        json =
                            continueWatchingJsonAdapter
                                .toJson(
                                    result
                                ),

                        updatedAt =
                            System.currentTimeMillis()
                    )
                )
            }

            return result

        } catch (e: Exception) {

            Log.e(
                "SIMKL_REPO",
                "getContinueWatching failed: " +
                    e.message,
                e
            )

            return cachedContinueWatching
                .orEmpty()
        }
    }

    data class WatchedActivityRefreshResult(
        val attempted: Boolean,
        val changed: Boolean,
        val latestActivity: String? =
            null,

        val errorMessage: String? =
            null
    )

    suspend fun refreshWatchedActivity():
        WatchedActivityRefreshResult {

        if (
            !isConfigured()
        ) {
            return WatchedActivityRefreshResult(
                attempted =
                    false,

                changed =
                    false,

                errorMessage =
                    "Simkl is not configured"
            )
        }

        if (
            !hasToken()
        ) {
            return WatchedActivityRefreshResult(
                attempted =
                    false,

                changed =
                    false,

                errorMessage =
                    "Simkl is not authenticated"
            )
        }

        return try {

            val activities =
                getActivities()

            val latestActivity =
                activities.all
                    ?.trim()
                    .orEmpty()

            val savedActivity =
                getSavedWatchedActivityAll()
                    ?.trim()
                    .orEmpty()

            if (
                latestActivity.isBlank()
            ) {
                return WatchedActivityRefreshResult(
                    attempted =
                        true,

                    changed =
                        false,

                    latestActivity =
                        null
                )
            }

            val changed =
                latestActivity !=
                    savedActivity

            WatchedActivityRefreshResult(
                attempted =
                    true,

                changed =
                    changed,

                latestActivity =
                    latestActivity
            )

        } catch (e: Exception) {

            WatchedActivityRefreshResult(
                attempted =
                    true,

                changed =
                    false,

                errorMessage =
                    e.message
            )
        }
    }

    private fun buildIdRefs(
        imdbIds: List<String>,
        simklIds: List<Int>
    ): List<SimklPlaybackIdsRef> {

        val refs =
            mutableListOf<
                SimklPlaybackIdsRef
                >()

        imdbIds
            .asSequence()
            .map {
                it.trim()
            }
            .filter {
                it.isNotBlank()
            }
            .distinct()
            .forEach { imdb ->
                refs +=
                    SimklPlaybackIdsRef(
                        imdb =
                            imdb
                    )
            }

        simklIds
            .asSequence()
            .distinct()
            .forEach { simkl ->
                refs +=
                    SimklPlaybackIdsRef(
                        simkl =
                            simkl
                    )
            }

        return refs
    }

    private fun scoreContinueWatchingItem(
        item: SimklContinueWatchingItem
    ): Int {

        var score =
            0

        if (
            item.source ==
                "playback"
        ) {
            score += 500
        }

        if (
            item.mediaType ==
                "movie" &&
            item.source ==
                "playback"
        ) {
            score += 50
        }

        if (
            item.source ==
                "watching" &&
            item.mediaType ==
                "series"
        ) {
            if (
                item.season != null &&
                item.episode != null
            ) {
                score += 350
            } else if (
                item.season != null
            ) {
                score += 300
            } else {
                score += 150
            }
        }

        val text =
            item.upNextText
                .orEmpty()
                .lowercase()

        if (
            text.contains(
                "resume"
            )
        ) {
            score += 100
        }

        return score
    }

    private fun buildPlaybackUpNextText(
        episode: SimklPlaybackEpisode?
    ): String {

        if (
            episode == null
        ) {
            return "Resume show"
        }

        val code =
            buildEpisodeCode(
                season =
                    episode.season,

                episode =
                    episode.episode
            )

        return buildString {

            append(
                "Up next"
            )

            if (
                code != null
            ) {
                append(
                    ": "
                )

                append(
                    code
                )
            }

            episode.title
                ?.takeIf {
                    it.isNotBlank()
                }
                ?.let { title ->
                    append(
                        " • "
                    )

                    append(
                        title
                    )
                }
        }
    }

    private fun buildWatchingUpNextText(
        nextToWatch: String?,
        lastWatched: String?,
        status: String?
    ): String {

        val next =
            nextToWatch
                ?.trim()
                .orEmpty()

        if (
            next.isNotBlank()
        ) {
            return "Up next: $next"
        }

        return "Up next"
    }

    private fun parseNextTarget(
        value: String?
    ): Pair<Int, Int?>? {

        val trimmed =
            value
                ?.trim()
                .orEmpty()

        if (
            trimmed.isBlank()
        ) {
            return null
        }

        val match =
            Regex(
                """s(\d+)\s*(?:e(\d+))?""",
                RegexOption.IGNORE_CASE
            )
                .find(
                    trimmed
                )
                ?: return null

        val season =
            match.groupValues[1]
                .toIntOrNull()
                ?: return null

        val episode =
            match.groupValues
                .getOrNull(2)
                ?.takeIf {
                    it.isNotBlank()
                }
                ?.toIntOrNull()

        return season to
            episode
    }

    private fun buildEpisodeCode(
        season: Int?,
        episode: Int?
    ): String? {

        if (
            season == null &&
            episode == null
        ) {
            return null
        }

        return buildString {

            season?.let {
                append(
                    "S$it"
                )
            }

            episode?.let {
                append(
                    "E$it"
                )
            }

        }.ifBlank {
            null
        }
    }

    private fun sortableTimestamp(
        value: String?
    ): String {
        return value
            ?.trim()
            .orEmpty()
    }

    private fun normalizePosterUrl(
        raw: String?
    ): String? {

        if (
            raw.isNullOrBlank()
        ) {
            return null
        }

        if (
            raw.startsWith(
                "http://"
            ) ||
            raw.startsWith(
                "https://"
            )
        ) {
            return raw
        }

        return "https://simkl.in/posters/" +
            "${raw}_m.jpg"
    }

    private fun requireAccessToken(): String {
        return getSavedAccessToken()
            ?.takeIf {
                it.isNotBlank()
            }
            ?: error(
                "Simkl access token is missing"
            )
    }

    private fun saveAccessToken(
        token: String
    ) {
        prefs
            ?.edit()
            ?.putString(
                KEY_ACCESS_TOKEN,
                token
            )
            ?.apply()

        // Cross-device sync: share the Simkl session so other devices come
        // up already connected.
        com.kennyb1201.kbstream.data.addon.AppContextHolder.appContext?.let { appContext ->
            com.kennyb1201.kbstream.data.sync.SupabaseSync.enqueuePrefs(
                appContext,
                com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder.KEY_SIMKL_AUTH,
                com.kennyb1201.kbstream.data.sync.PrefsPayloadBuilder.buildSimklAuth(appContext)
            )
        }
    }

    private fun saveWatchedActivityAll(
        value: String
    ) {
        prefs
            ?.edit()
            ?.putString(
                KEY_LAST_WATCHED_ACTIVITY_ALL,
                value
            )
            ?.apply()
    }

    private fun bearer(
        token: String
    ): String =
        "Bearer $token"

    companion object {

        @Volatile
        private var INSTANCE:
            SimklRepository? = null

        fun getInstance(
            context: Context
        ): SimklRepository {
            return INSTANCE
                ?: synchronized(this) {
                    INSTANCE
                        ?: SimklRepository(
                            context.applicationContext
                        )
                            .also {
                                INSTANCE = it
                            }
                }
        }

        private const val PREFS_NAME =
            "simkl_auth"

        private const val KEY_ACCESS_TOKEN =
            "access_token"

        private const val KEY_LAST_WATCHED_ACTIVITY_ALL =
            "last_watched_activity_all"

        private const val ALL_SHOW_ITEMS_TTL_MS =
            15L * 60L * 1000L

        private const val ALL_SHOW_ITEMS_DISK_TTL_MS =
            12L * 60L * 60L * 1000L

        /*
         * How long the in-memory Continue Watching list may be trusted. It
         * used to be trusted forever (cleared only by a watched write), so a
         * list fetched at the wrong instant - right after an unmark, before
         * Simkl's feed had caught up - stayed for the whole session and the
         * show never came back to the rail until an app restart. The disk
         * copy already had a TTL; this is the memory copy's.
         */
        private const val CONTINUE_WATCHING_TTL_MS =
            3L * 60L * 1000L

        private const val CONTINUE_WATCHING_DISK_TTL_MS =
            6L * 60L * 60L * 1000L

        // Disk-cache keys are PROFILE-SCOPED at use time (see diskKey()):
        // the underlying tmdb_json_cache table is a shared DB, and these
        // blobs are per-Simkl-account data. Global keys let profile B read
        // profile A's cached continue-watching/completed lists for hours —
        // the "Simkl account follows me between profiles" leak.
        private const val ALL_SHOW_ITEMS_DISK_KEY_BASE =
            "simkl:all_show_items"

        private const val CONTINUE_WATCHING_DISK_KEY_BASE =
            "simkl:continue_watching"

        private const val COMPLETED_MOVIES_TTL_MS =
            15L * 60L * 1000L

        private const val COMPLETED_MOVIES_DISK_TTL_MS =
            12L * 60L * 60L * 1000L

        private const val COMPLETED_MOVIES_DISK_KEY_BASE =
            "simkl:completed_movies"

        /**
         * Resolves a disk-cache key against the ACTIVE profile so each
         * profile's cached Simkl blobs never collide in the shared cache
         * table. With no active profile (legacy startup) it returns the
         * bare key, matching the pre-profiles layout.
         */
        private fun diskKey(base: String): String {
            val pid = com.kennyb1201.kbstream.data.sync.ProfileManager
                .activeProfile.value?.id ?: return base
            return "$pid/$base"
        }

        // Companion-level and read/written from arbitrary coroutines
        // without a mutex: @Volatile guarantees visibility and atomic
        // publication of the immutable list reference.
        @Volatile
        private var cachedContinueWatching:
            List<SimklContinueWatchingItem>? =
            null

        // When [cachedContinueWatching] was last built, so it can age out
        // (see CONTINUE_WATCHING_TTL_MS).
        @Volatile
        private var cachedContinueWatchingFetchedAt: Long =
            0L

        /**
         * Profile-switch isolation: drops every in-memory watched-state
         * snapshot. Simkl auth is per-profile (scoped simkl_auth prefs), so
         * after a switch these caches can belong to a DIFFERENT Simkl
         * account - the CW feed AND the completed-shows / completed-movies
         * sets must all go. Companion-level so ProfileManager can call it
         * without an instance; the instance-level caches are reached through
         * the singleton [INSTANCE].
         */
        fun clearTransientCaches() {
            cachedContinueWatching = null
            cachedContinueWatchingFetchedAt = 0L
            INSTANCE?.let { instance ->
                instance.clearWatchedCachesForProfileSwitch()
            }
        }
    }
}
